package io.github.magnusp.libsql.client;

import io.github.magnusp.libsql.hrana.codec.Batch;
import io.github.magnusp.libsql.hrana.codec.BatchResult;
import io.github.magnusp.libsql.hrana.codec.DirectHranaJsonCodec;
import io.github.magnusp.libsql.hrana.codec.HranaError;
import io.github.magnusp.libsql.hrana.codec.HranaJsonCodec;
import io.github.magnusp.libsql.hrana.codec.PipelineReqBody;
import io.github.magnusp.libsql.hrana.codec.PipelineRespBody;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.StreamRequest;
import io.github.magnusp.libsql.hrana.codec.StreamResponse;
import io.github.magnusp.libsql.hrana.codec.StreamResult;

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

public class LibsqlHttpClient implements LibsqlClient {

    private static final ConcurrentHashMap<String, HttpClient> HTTP_CLIENT_CACHE = new ConcurrentHashMap<>();

    private final LibsqlClientConfig config;
    private final HttpClient httpClient;
    private final HranaJsonCodec codec;
    private final URI pipelineUri;

    /** Guards the interactive stream state below. */
    private final Object streamLock = new Object();

    private String baton;
    private URI baseUrl;

    public LibsqlHttpClient(LibsqlClientConfig config) {
        this(config, new DirectHranaJsonCodec());
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

    @Override
    public StmtResult executeOneShot(Stmt stmt) {
        // One-shot: [execute, close] in one round trip, so no idle stream is left for the server to reap.
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

    @Override
    public StmtResult executeOnStream(Stmt stmt) {
        StreamResult result;
        synchronized (streamLock) {
            PipelineReqBody req = new PipelineReqBody(baton, List.of(new StreamRequest.Execute(stmt)));
            PipelineRespBody resp = sendPipeline(req, baseUrl);
            updateStreamState(resp);
            result = firstResult(resp);
        }
        return switch (result) {
            case StreamResult.Ok ok -> {
                if (ok.response() instanceof StreamResponse.Execute exec) {
                    yield exec.result();
                }
                throw new LibsqlException("Unexpected stream response: " + ok.response(), null, 200);
            }
            case StreamResult.Error err -> throw new LibsqlException(err.error());
        };
    }

    @Override
    public BatchResult executeBatchOneShot(Batch batch) {
        // One-shot: [batch, close] in one round trip, so no idle stream is left for the server to reap.
        PipelineReqBody req = new PipelineReqBody(
                null,
                List.of(
                        new StreamRequest.BatchReq(batch),
                        StreamRequest.Close.INSTANCE
                )
        );

        PipelineRespBody resp = sendPipelineWithRetry(req, pipelineUri);
        return batchResult(firstResult(resp));
    }

    @Override
    public BatchResult executeBatchOnStream(Batch batch) {
        StreamResult result;
        synchronized (streamLock) {
            PipelineReqBody req = new PipelineReqBody(baton, List.of(new StreamRequest.BatchReq(batch)));
            PipelineRespBody resp = sendPipeline(req, baseUrl);
            updateStreamState(resp);
            result = firstResult(resp);
        }
        return batchResult(result);
    }

    @Override
    public boolean hasOpenStream() {
        synchronized (streamLock) {
            return baton != null;
        }
    }

    @Override
    public void closeStream() {
        synchronized (streamLock) {
            if (baton == null) {
                return;
            }
            try {
                PipelineReqBody req = new PipelineReqBody(baton, List.of(StreamRequest.Close.INSTANCE));
                PipelineRespBody resp = sendPipeline(req, baseUrl);
                updateStreamState(resp);
            } finally {
                baton = null;
            }
        }
    }

    @Override
    public void close() {
        // HTTP keeps no connection-level state; a pipeline request owns its whole stream.
    }

    private static StreamResult firstResult(PipelineRespBody resp) {
        if (resp.results().isEmpty()) {
            throw new LibsqlException("Empty result list in response", null, 200);
        }
        return resp.results().get(0);
    }

    private static BatchResult batchResult(StreamResult result) {
        return switch (result) {
            case StreamResult.Ok ok -> {
                if (ok.response() instanceof StreamResponse.BatchResp batchResp) {
                    yield batchResp.result();
                }
                throw new LibsqlException("Unexpected stream response: " + ok.response(), null, 200);
            }
            case StreamResult.Error err -> throw new LibsqlException(err.error());
        };
    }

    private void updateStreamState(PipelineRespBody resp) {
        this.baton = resp.baton();
        if (resp.baseUrl() != null && !resp.baseUrl().isBlank()) {
            this.baseUrl = URI.create(resp.baseUrl());
        }
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
                        .header("Accept", "application/json")
                        .timeout(config.connectTimeout() != null ? config.connectTimeout() : LibsqlClientConfig.DEFAULT_CONNECT_TIMEOUT);

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
