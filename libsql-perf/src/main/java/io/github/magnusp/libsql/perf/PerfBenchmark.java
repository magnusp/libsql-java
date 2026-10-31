package io.github.magnusp.libsql.perf;

import io.github.magnusp.libsql.hrana.codec.DirectHranaJsonCodec;
import io.github.magnusp.libsql.hrana.codec.HranaJsonCodec;
import io.github.magnusp.libsql.hrana.codec.JacksonHranaJsonCodec;
import io.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import io.github.magnusp.libsql.hrana.codec.PipelineRespBody;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.StreamResponse;
import io.github.magnusp.libsql.hrana.codec.StreamResult;
import io.github.magnusp.libsql.jdbc.LibsqlResultSet;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Driver-side benchmark for the JSON codec and the JDBC result-set getters.
 *
 * <p>This measures work that happens in the client process. It intentionally starts no server: the
 * PR comparison runs on shared CI hardware, and server and network time would bury codec changes in
 * noise. The workloads use fixed payloads and report a median over several windows.
 *
 * <p>Run it after {@code ./mvnw -pl libsql-perf -am -DskipTests package}:
 *
 * <pre>{@code
 * java -cp 'libsql-perf/target/classes:libsql-perf/target/lib/*' \
 *   io.github.magnusp.libsql.perf.PerfBenchmark --json /tmp/perf.json
 * }</pre>
 */
public final class PerfBenchmark {

    private static final int SMALL_ROWS = 5_000;
    private static final int LARGE_ROWS = 20_000;

    private static volatile Object blackhole;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Config config = options.quick() ? new Config(500, 150, 3, 300) : new Config(1_000, 250, 5, 500);

        HranaJsonCodec direct = new DirectHranaJsonCodec();
        HranaJsonCodec jackson = new JacksonHranaJsonCodec();
        byte[] small = Payloads.scan(SMALL_ROWS);
        byte[] large = Payloads.scan(LARGE_ROWS);
        PipelineReqBody insert = Payloads.insertRequest();
        verifyCodecsAgree(direct, jackson, small, insert);

        List<Metric> metrics = new ArrayList<>();
        metrics.add(measure(
                "codec.direct.decode5k",
                SMALL_ROWS,
                () -> direct.deserializePipelineResponse(small),
                config));
        metrics.add(measure(
                "codec.jackson.decode5k",
                SMALL_ROWS,
                () -> jackson.deserializePipelineResponse(small),
                config));
        metrics.add(measure(
                "codec.direct.decode20k",
                LARGE_ROWS,
                () -> direct.deserializePipelineResponse(large),
                config));
        metrics.add(measure("codec.direct.encode", 0, () -> direct.serializePipelineRequest(insert), config));
        metrics.add(measure("codec.jackson.encode", 0, () -> jackson.serializePipelineRequest(insert), config));

        StmtResult result = firstResult(direct.deserializePipelineResponse(large));
        metrics.add(measure("jdbc.scan.all", LARGE_ROWS, () -> scanAll(result), config));
        metrics.add(measure("jdbc.scan.object", LARGE_ROWS, () -> scanObject(result), config));

        if (!options.quiet()) {
            printMarkdown(metrics, options, config);
        }
        if (options.json() != null) {
            writeJson(options.json(), metrics, options, config);
        }
    }

    private static void verifyCodecsAgree(
            HranaJsonCodec direct, HranaJsonCodec jackson, byte[] scanPayload, PipelineReqBody insert) throws IOException {
        PipelineRespBody directBody = direct.deserializePipelineResponse(scanPayload);
        PipelineRespBody jacksonBody = jackson.deserializePipelineResponse(scanPayload);
        if (!directBody.equals(jacksonBody)) {
            throw new IllegalStateException("the codecs disagree on the scan payload");
        }
        if (!Arrays.equals(
                direct.serializePipelineRequest(insert), jackson.serializePipelineRequest(insert))) {
            throw new IllegalStateException("the codecs disagree on the insert request");
        }
    }

    private static StmtResult firstResult(PipelineRespBody body) {
        StreamResult streamResult = body.results().get(0);
        if (streamResult instanceof StreamResult.Ok ok
                && ok.response() instanceof StreamResponse.Execute execute) {
            return execute.result();
        }
        throw new IllegalStateException("unexpected payload: " + streamResult);
    }

    private static Metric measure(String name, int rows, Task task, Config config) throws Exception {
        long deadline = System.nanoTime() + config.warmupMs() * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 10; i++) {
                blackhole = task.run();
            }
        }

        long allocBefore = THREADS.getCurrentThreadAllocatedBytes();
        long allocOps = 0;
        deadline = System.nanoTime() + config.allocMs() * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 10; i++) {
                blackhole = task.run();
            }
            allocOps += 10;
        }
        long allocAfter = THREADS.getCurrentThreadAllocatedBytes();
        double bytesPerOp = allocOps == 0 ? 0 : (double) (allocAfter - allocBefore) / allocOps;

        double[] rates = new double[config.windows()];
        for (int window = 0; window < rates.length; window++) {
            long count = 0;
            long start = System.nanoTime();
            long end = start + config.windowMs() * 1_000_000L;
            long now;
            do {
                for (int i = 0; i < 10; i++) {
                    blackhole = task.run();
                }
                count += 10;
                now = System.nanoTime();
            } while (now < end);
            rates[window] = count / ((now - start) / 1_000_000_000.0);
        }
        Arrays.sort(rates);
        double median = rates[rates.length / 2];
        return new Metric(name, median, rows == 0 ? 0 : median * rows, bytesPerOp);
    }

    private static Object scanAll(StmtResult result) throws Exception {
        LibsqlResultSet rs = new LibsqlResultSet(null, result);
        long sum = 0;
        while (rs.next()) {
            sum += rs.getLong(1);
            sum += rs.getString(2).length();
            sum += (long) rs.getDouble(3);
            sum += rs.getBytes(4).length;
            sum += rs.getString(5).length();
            sum += rs.getInt(6);
        }
        return sum;
    }

    private static Object scanObject(StmtResult result) throws Exception {
        LibsqlResultSet rs = new LibsqlResultSet(null, result);
        long sum = 0;
        while (rs.next()) {
            for (int column = 1; column <= 6; column++) {
                Object value = rs.getObject(column);
                if (value != null) {
                    sum += value.hashCode();
                }
            }
        }
        return sum;
    }

    private static void printMarkdown(List<Metric> metrics, Options options, Config config) {
        System.out.printf(Locale.ROOT, "Benchmark commit: %s%n", options.commit());
        System.out.printf(
                Locale.ROOT,
                "Warmup %d ms, allocation %d ms, %d windows of %d ms%n%n",
                config.warmupMs(),
                config.allocMs(),
                config.windows(),
                config.windowMs());
        System.out.println("| Workload | ops/s | rows/s | bytes/op |");
        System.out.println("|---|---:|---:|---:|");
        for (Metric metric : metrics) {
            System.out.printf(
                    Locale.ROOT,
                    "| %s | %.1f | %.0f | %.0f |%n",
                    metric.name(),
                    metric.opsPerSec(),
                    metric.rowsPerSec(),
                    metric.bytesPerOp());
        }
    }

    private static void writeJson(Path path, List<Metric> metrics, Options options, Config config)
            throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder json = new StringBuilder(1_024);
        json.append("{\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"commit\": \"").append(escape(options.commit())).append("\",\n");
        json.append("  \"javaVersion\": \"")
                .append(escape(System.getProperty("java.version")))
                .append("\",\n");
        json.append("  \"osName\": \"")
                .append(escape(System.getProperty("os.name")))
                .append("\",\n");
        json.append("  \"osArch\": \"")
                .append(escape(System.getProperty("os.arch")))
                .append("\",\n");
        json.append("  \"availableProcessors\": ")
                .append(Runtime.getRuntime().availableProcessors())
                .append(",\n");
        json.append("  \"config\": {")
                .append("\"warmupMs\": ")
                .append(config.warmupMs())
                .append(", \"allocMs\": ")
                .append(config.allocMs())
                .append(", \"windows\": ")
                .append(config.windows())
                .append(", \"windowMs\": ")
                .append(config.windowMs())
                .append("},\n");
        json.append("  \"metrics\": {\n");
        for (int i = 0; i < metrics.size(); i++) {
            Metric metric = metrics.get(i);
            json.append("    \"")
                    .append(escape(metric.name()))
                    .append("\": {\"opsPerSec\": ")
                    .append(number(metric.opsPerSec()))
                    .append(", \"rowsPerSec\": ")
                    .append(number(metric.rowsPerSec()))
                    .append(", \"bytesPerOp\": ")
                    .append(number(metric.bytesPerOp()))
                    .append("}");
            json.append(i + 1 < metrics.size() ? ",\n" : "\n");
        }
        json.append("  }\n");
        json.append("}\n");
        Files.writeString(path, json.toString());
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Metric(String name, double opsPerSec, double rowsPerSec, double bytesPerOp) {}

    private record Config(long warmupMs, long allocMs, int windows, long windowMs) {}

    private record Options(Path json, String commit, boolean quiet, boolean quick) {
        static Options parse(String[] args) {
            Path json = null;
            String commit = System.getenv().getOrDefault("GITHUB_SHA", "unknown");
            boolean quiet = false;
            boolean quick = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--json" -> json = Path.of(requireValue(args, ++i, "--json"));
                    case "--commit" -> commit = requireValue(args, ++i, "--commit");
                    case "--quiet" -> quiet = true;
                    case "--quick" -> quick = true;
                    case "--help", "-h" -> {
                        System.out.println("Usage: PerfBenchmark [--json PATH] [--commit SHA] [--quiet] [--quick]");
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            return new Options(json, commit, quiet, quick);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[index];
        }
    }

    @FunctionalInterface
    private interface Task {
        Object run() throws Exception;
    }

    private PerfBenchmark() {}
}
