package tech.libsql.client;

import tech.libsql.hrana.codec.HranaError;
import tech.libsql.hrana.codec.HranaJsonCodec;
import tech.libsql.hrana.codec.JacksonHranaJsonCodec;
import tech.libsql.hrana.codec.PipelineReqBody;
import tech.libsql.hrana.codec.PipelineRespBody;
import tech.libsql.hrana.codec.Stmt;
import tech.libsql.hrana.codec.StmtResult;
import tech.libsql.hrana.codec.StreamRequest;
import tech.libsql.hrana.codec.StreamResponse;
import tech.libsql.hrana.codec.StreamResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public class LibsqlHttpClient {

    private static final ConcurrentHashMap<String, HttpClient> HTTP_CLIENT_CACHE = new ConcurrentHashMap<>();

    private final LibsqlClientConfig config;
    private final HttpClient httpClient;
    private final HranaJsonCodec codec;
    private final URI pipelineUri;

    public LibsqlHttpClient(LibsqlClientConfig config) {
        this(config, new JacksonHranaJsonCodec());
    }

    public LibsqlHttpClient(LibsqlClientConfig config, HranaJsonCodec codec) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.codec = Objects.requireNonNull(codec, "codec cannot be null");
        this.httpClient = getOrCreateHttpClient(config.connectTimeout());

        String base = config.url();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.pipelineUri = URI.create(base + "/v3/pipeline");
    }

    private static HttpClient getOrCreateHttpClient(Duration connectTimeout) {
        Duration timeout = (connectTimeout != null) ? connectTimeout : LibsqlClientConfig.DEFAULT_CONNECT_TIMEOUT;
        String key = "client:" + timeout.toMillis();
        return HTTP_CLIENT_CACHE.computeIfAbsent(key, k -> HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(timeout)
                .build());
    }

    public StmtResult executeOneShot(Stmt stmt) {
        // Mitigation for Bug 1: pipeline [execute, close] in a single roundtrip to prevent 128-stream 10s stall
        PipelineReqBody req = new PipelineReqBody(
                null,
                List.of(
                        new StreamRequest.Execute(stmt),
                        StreamRequest.Close.INSTANCE
                )
        );

        PipelineRespBody resp = sendPipelineWithRetry(req, pipelineUri);
        if (resp.results().isEmpty()) {
            throw new LibsqlException("Empty result list in response", null, 200);
        }

        StreamResult firstResult = resp.results().get(0);
        return switch (firstResult) {
            case StreamResult.Ok ok -> {
                if (ok.response() instanceof StreamResponse.Execute exec) {
                    yield exec.result();
                }
                throw new LibsqlException("Unexpected stream response: " + ok.response(), null, 200);
            }
            case StreamResult.Error err -> throw new LibsqlException(err.error());
        };
    }

    public PipelineRespBody sendPipeline(PipelineReqBody reqBody, URI targetUri) {
        return sendPipelineWithRetry(reqBody, targetUri != null ? targetUri : pipelineUri);
    }

    private PipelineRespBody sendPipelineWithRetry(PipelineReqBody reqBody, URI uri) {
        long startMs = System.currentTimeMillis();
        long busyTimeoutMs = config.busyTimeout() != null ? config.busyTimeout().toMillis() : 5000;
        long backoffMs = 10;

        while (true) {
            try {
                byte[] bodyBytes = codec.serializePipelineRequest(reqBody);

                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json");

                if (config.authToken() != null && !config.authToken().isBlank()) {
                    builder.header("Authorization", "Bearer " + config.authToken());
                }

                HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                if (status == 429 || status == 503) {
                    long elapsed = System.currentTimeMillis() - startMs;
                    if (reqBody.baton() == null && elapsed < busyTimeoutMs) {
                        applyBackoff(backoffMs, busyTimeoutMs - elapsed);
                        backoffMs = Math.min(backoffMs * 2, 200);
                        continue;
                    }
                    throw new LibsqlException("HTTP " + status + ": server busy", "SERVER_BUSY", status);
                }

                if (status >= 400) {
                    HranaError err = null;
                    try {
                        err = codec.deserializeError(new ByteArrayInputStream(response.body()));
                    } catch (Exception ignored) {
                    }
                    if (err != null) {
                        throw new LibsqlException(err);
                    }
                    throw new LibsqlException("HTTP error " + status + ": " + new String(response.body()), null, status);
                }

                PipelineRespBody respBody = codec.deserializePipelineResponse(response.body());

                // Check if the first result is a transient busy error when baton is null
                if (reqBody.baton() == null && !respBody.results().isEmpty()) {
                    StreamResult r0 = respBody.results().get(0);
                    if (r0 instanceof StreamResult.Error errResult) {
                        String code = errResult.error().code();
                        if (isBusyCode(code)) {
                            long elapsed = System.currentTimeMillis() - startMs;
                            if (elapsed < busyTimeoutMs) {
                                applyBackoff(backoffMs, busyTimeoutMs - elapsed);
                                backoffMs = Math.min(backoffMs * 2, 200);
                                continue;
                            }
                        }
                    }
                }

                return respBody;

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                long elapsed = System.currentTimeMillis() - startMs;
                if (reqBody.baton() == null && elapsed < busyTimeoutMs) {
                    applyBackoff(backoffMs, busyTimeoutMs - elapsed);
                    backoffMs = Math.min(backoffMs * 2, 200);
                    continue;
                }
                throw new LibsqlException("Failed to execute pipeline request: " + e.getMessage(), e);
            }
        }
    }

    private static boolean isBusyCode(String code) {
        return "busy".equalsIgnoreCase(code)
                || "SQLITE_BUSY".equalsIgnoreCase(code)
                || "database_locked".equalsIgnoreCase(code);
    }

    private static void applyBackoff(long backoffMs, long maxRemainingMs) {
        long sleep = Math.min(backoffMs, maxRemainingMs);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, sleep / 2));
        try {
            Thread.sleep(sleep + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
