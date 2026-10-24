package com.github.magnusp.libsql.client;

import com.github.magnusp.libsql.hrana.codec.Batch;
import com.github.magnusp.libsql.hrana.codec.BatchResult;
import com.github.magnusp.libsql.hrana.codec.DirectHranaJsonCodec;
import com.github.magnusp.libsql.hrana.codec.HranaWsCodec;
import com.github.magnusp.libsql.hrana.codec.Stmt;
import com.github.magnusp.libsql.hrana.codec.StmtResult;
import com.github.magnusp.libsql.hrana.codec.WsClientMsg;
import com.github.magnusp.libsql.hrana.codec.WsRequest;
import com.github.magnusp.libsql.hrana.codec.WsResponse;
import com.github.magnusp.libsql.hrana.codec.WsServerMsg;

import org.apache.hc.client5.http.websocket.api.WebSocket;
import org.apache.hc.client5.http.websocket.api.WebSocketClientConfig;
import org.apache.hc.client5.http.websocket.api.WebSocketListener;
import org.apache.hc.client5.http.websocket.client.CloseableWebSocketClient;
import org.apache.hc.client5.http.websocket.client.WebSocketClients;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hrana 3 client over a WebSocket.
 *
 * <p>The supplied {@link HranaWsCodec} selects both the wire encoding and the subprotocol: {@code
 * hrana3} for JSON text frames and {@code hrana3-protobuf} for Protobuf binary frames.
 *
 * <p>One WebSocket connection multiplexes any number of streams. Requests are matched to responses
 * by {@code request_id}, so the client may send a whole one-shot sequence (open, execute, close)
 * without waiting in between and still receive the responses in order.
 *
 * <p>All request methods block the calling thread until the matching response arrives. The caller
 * (the JDBC connection) serializes calls, so a single interactive stream id is enough.
 *
 * <p><strong>Transport.</strong> Apache HttpClient's {@code httpclient5-websocket} module speaks RFC
 * 6455. The JDK's {@link java.net.http.WebSocket} cannot be used against sqld: sqld answers the
 * upgrade with a {@code 101} that carries {@code content-length: 31} and no body, and the JDK client
 * completes its opening handshake only after reading a full response body. HttpClient treats a
 * {@code 101} as informational and upgrades without reading one.
 */
public class LibsqlWebSocketClient implements LibsqlClient {

    private static final String SUBPROTOCOL_JSON = "hrana3";
    private static final String SUBPROTOCOL_PROTOBUF = "hrana3-protobuf";
    private static final int CLOSE_NORMAL = 1000;

    /**
     * One message must be buffered whole before it can be decoded, so the bound matches the payload
     * size a Hrana response may reach.
     */
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024 * 1024;

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(1);

    private final LibsqlClientConfig config;
    private final HranaWsCodec codec;
    private final String subprotocol;
    private final Duration timeout;
    private final CloseableWebSocketClient client;
    private WebSocket socket;

    private final ConcurrentHashMap<Integer, CompletableFuture<WsResponse>> pending =
            new ConcurrentHashMap<>();
    private final CompletableFuture<WsServerMsg> hello = new CompletableFuture<>();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final AtomicInteger nextStreamId = new AtomicInteger(1);

    /** Guarded by {@code this}: the id of the lazily opened interactive stream, or -1. */
    private int interactiveStreamId = -1;

    private boolean closed;

    public LibsqlWebSocketClient(LibsqlClientConfig config) {
        this(config, new DirectHranaJsonCodec());
    }

    public LibsqlWebSocketClient(LibsqlClientConfig config, HranaWsCodec codec) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.codec = Objects.requireNonNull(codec, "codec cannot be null");
        this.timeout = config.connectTimeout() != null
                ? config.connectTimeout()
                : LibsqlClientConfig.DEFAULT_CONNECT_TIMEOUT;
        this.subprotocol = codec.isBinary() ? SUBPROTOCOL_PROTOBUF : SUBPROTOCOL_JSON;

        CloseableWebSocketClient started = WebSocketClients.createDefault();
        started.start();
        this.client = started;
        try {
            this.socket = connect();
            send(new WsClientMsg.Hello(authToken()));
            awaitHello();
        } catch (RuntimeException e) {
            shutdown();
            throw e;
        }
    }

    private String authToken() {
        String token = config.authToken();
        return token == null || token.isBlank() ? null : token;
    }

    private WebSocket connect() {
        WebSocketClientConfig webSocketConfig = WebSocketClientConfig.custom()
                .setSubprotocols(List.of(subprotocol))
                .setConnectTimeout(Timeout.of(timeout))
                .setCloseWaitTimeout(Timeout.of(CLOSE_TIMEOUT))
                .setMaxFrameSize(MAX_MESSAGE_BYTES)
                .setMaxMessageSize(MAX_MESSAGE_BYTES)
                // The transport this replaces negotiated no extension, so keep the wire bytes equal.
                .enablePerMessageDeflate(false)
                .build();
        try {
            return client.connect(
                    URI.create(config.url()), new HranaListener(), webSocketConfig)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LibsqlException("Interrupted while opening the Hrana WebSocket", e);
        } catch (TimeoutException e) {
            throw new LibsqlException(
                    "Timed out after " + timeout.toMillis() + " ms opening the Hrana WebSocket", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new LibsqlException(
                    "Failed to open Hrana WebSocket to " + config.url() + ": " + cause.getMessage(),
                    cause);
        }
    }

    @Override
    public StmtResult executeOneShot(Stmt stmt) {
        int streamId = nextStreamId.getAndIncrement();
        CompletableFuture<WsResponse> open = sendRequest(new WsRequest.OpenStream(streamId));
        CompletableFuture<WsResponse> execute =
                sendRequest(new WsRequest.Execute(streamId, stmt));
        CompletableFuture<WsResponse> close = sendRequest(new WsRequest.CloseStream(streamId));

        try {
            await(open);
            WsResponse response = await(execute);
            if (!(response instanceof WsResponse.Execute result)) {
                throw new LibsqlException("Unexpected response to execute: " + response, null, 200);
            }
            return result.result();
        } finally {
            // The stream is already open, so the close must be sent even if execute failed. Its
            // outcome cannot change the result the caller sees.
            awaitCloseQuietly(close);
        }
    }

    @Override
    public BatchResult executeBatchOneShot(Batch batch) {
        int streamId = nextStreamId.getAndIncrement();
        CompletableFuture<WsResponse> open = sendRequest(new WsRequest.OpenStream(streamId));
        CompletableFuture<WsResponse> execute = sendRequest(new WsRequest.Batch(streamId, batch));
        CompletableFuture<WsResponse> close = sendRequest(new WsRequest.CloseStream(streamId));

        try {
            await(open);
            WsResponse response = await(execute);
            if (!(response instanceof WsResponse.Batch result)) {
                throw new LibsqlException("Unexpected response to batch: " + response, null, 200);
            }
            return result.result();
        } finally {
            awaitCloseQuietly(close);
        }
    }

    @Override
    public synchronized StmtResult executeOnStream(Stmt stmt) {
        int streamId = ensureStream();
        WsResponse response = await(sendRequest(new WsRequest.Execute(streamId, stmt)));
        if (!(response instanceof WsResponse.Execute result)) {
            throw new LibsqlException("Unexpected response to execute: " + response, null, 200);
        }
        return result.result();
    }

    @Override
    public synchronized BatchResult executeBatchOnStream(Batch batch) {
        int streamId = ensureStream();
        WsResponse response = await(sendRequest(new WsRequest.Batch(streamId, batch)));
        if (!(response instanceof WsResponse.Batch result)) {
            throw new LibsqlException("Unexpected response to batch: " + response, null, 200);
        }
        return result.result();
    }

    @Override
    public synchronized boolean hasOpenStream() {
        return interactiveStreamId >= 0;
    }

    @Override
    public synchronized void closeStream() {
        if (interactiveStreamId < 0) {
            return;
        }
        int streamId = interactiveStreamId;
        interactiveStreamId = -1;
        await(sendRequest(new WsRequest.CloseStream(streamId)));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            closeStream();
        } catch (LibsqlException e) {
            // Closing a connection is best effort: the peer may already be gone and the server
            // reaps the stream when it notices.
        } finally {
            shutdown();
        }
    }

    /** Callers hold {@code this}. */
    private int ensureStream() {
        if (interactiveStreamId < 0) {
            int streamId = nextStreamId.getAndIncrement();
            await(sendRequest(new WsRequest.OpenStream(streamId)));
            interactiveStreamId = streamId;
        }
        return interactiveStreamId;
    }

    private CompletableFuture<WsResponse> sendRequest(WsRequest request) {
        int requestId = nextRequestId.getAndIncrement();
        CompletableFuture<WsResponse> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            send(new WsClientMsg.Request(requestId, request));
        } catch (RuntimeException e) {
            pending.remove(requestId);
            future.completeExceptionally(e);
        }
        return future;
    }

    private void send(WsClientMsg message) {
        byte[] payload;
        try {
            payload = codec.serialize(message);
        } catch (IOException e) {
            throw new LibsqlException("Failed to serialize Hrana message: " + e.getMessage(), e);
        }
        boolean queued = codec.isBinary()
                ? socket.sendBinary(ByteBuffer.wrap(payload), true)
                : socket.sendText(new String(payload, StandardCharsets.UTF_8), true);
        if (!queued) {
            throw new LibsqlException("Hrana WebSocket is closed", "WS_CLOSED", 0);
        }
    }

    /**
     * Stops the IO reactor. sqld does not echo the close frame, so the close handshake is not
     * awaited: the reactor shutdown tears the socket down after {@link #CLOSE_TIMEOUT}.
     */
    private void shutdown() {
        if (socket != null) {
            socket.close(CLOSE_NORMAL, "closing");
        }
        client.initiateShutdown();
        try {
            client.awaitShutdown(TimeValue.of(SHUTDOWN_TIMEOUT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitHello() {
        try {
            WsServerMsg message = hello.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (message instanceof WsServerMsg.HelloError error) {
                throw new LibsqlException(error.error());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LibsqlException("Interrupted while waiting for the Hrana hello response", e);
        } catch (TimeoutException e) {
            throw new LibsqlException(
                    "Timed out after " + timeout.toMillis() + " ms waiting for the Hrana hello response",
                    e);
        } catch (ExecutionException e) {
            throw unwrap("Hrana hello failed", e);
        }
    }

    private WsResponse await(CompletableFuture<WsResponse> future) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LibsqlException("Interrupted while waiting for a Hrana response", e);
        } catch (TimeoutException e) {
            throw new LibsqlException(
                    "Timed out after " + timeout.toMillis() + " ms waiting for a Hrana response", e);
        } catch (ExecutionException e) {
            throw unwrap("Hrana request failed", e);
        }
    }

    private void awaitCloseQuietly(CompletableFuture<WsResponse> close) {
        try {
            close.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException e) {
            // The caller's result is already decided; a failed close only means the server will
            // reap the stream. The response future is removed by the reader or on error.
        }
    }

    private static LibsqlException unwrap(String message, ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof LibsqlException libsql) {
            return libsql;
        }
        return new LibsqlException(message + ": " + cause.getMessage(), cause);
    }

    private void dispatch(byte[] payload) {
        WsServerMsg message;
        try {
            message = codec.deserialize(payload);
        } catch (IOException e) {
            failAll(new LibsqlException(
                    "Failed to decode Hrana WebSocket message: " + e.getMessage(), e));
            return;
        }

        switch (message) {
            case WsServerMsg.HelloOk ok -> hello.complete(ok);
            case WsServerMsg.HelloError error -> hello.completeExceptionally(new LibsqlException(error.error()));
            case WsServerMsg.ResponseOk ok -> complete(ok.requestId(), ok.response());
            case WsServerMsg.ResponseError error -> fail(error.requestId(), new LibsqlException(error.error()));
        }
    }

    private void complete(int requestId, WsResponse response) {
        CompletableFuture<WsResponse> future = pending.remove(requestId);
        if (future == null) {
            protocolViolation("unexpected response for request_id " + requestId);
            return;
        }
        future.complete(response);
    }

    private void fail(int requestId, LibsqlException error) {
        CompletableFuture<WsResponse> future = pending.remove(requestId);
        if (future == null) {
            protocolViolation("unexpected error response for request_id " + requestId);
            return;
        }
        future.completeExceptionally(error);
    }

    private void protocolViolation(String detail) {
        failAll(new LibsqlException("Hrana WebSocket protocol violation: " + detail, "WS_PROTOCOL", 0));
        socket.close(CLOSE_NORMAL, "protocol violation");
    }

    private void failAll(LibsqlException error) {
        if (!hello.isDone()) {
            hello.completeExceptionally(error);
        }
        for (Map.Entry<Integer, CompletableFuture<WsResponse>> entry : pending.entrySet()) {
            CompletableFuture<WsResponse> future = pending.remove(entry.getKey());
            if (future != null) {
                future.completeExceptionally(error);
            }
        }
    }

    /**
     * Reassembles frames into whole messages and routes them to {@link #dispatch(byte[])}.
     *
     * <p>The buffers the client passes in are only valid during the callback, so every chunk is
     * copied before it is buffered. A message either arrives whole or in fragments ending with
     * {@code last}, and the fragments of one message never interleave with another.
     */
    private final class HranaListener implements WebSocketListener {

        private final StringBuilder text = new StringBuilder();
        private ByteArrayOutputStream binary;

        @Override
        public void onText(CharBuffer data, boolean last) {
            text.append(data);
            if (!last) {
                return;
            }
            String message = text.toString();
            text.setLength(0);
            dispatch(message.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onBinary(ByteBuffer data, boolean last) {
            if (binary == null) {
                binary = new ByteArrayOutputStream();
            }
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binary.write(chunk, 0, chunk.length);
            if (!last) {
                return;
            }
            byte[] message = binary.toByteArray();
            binary = null;
            dispatch(message);
        }

        @Override
        public void onError(Throwable cause) {
            failAll(new LibsqlException(
                    "Hrana WebSocket connection failed: " + cause.getMessage(), cause));
        }

        @Override
        public void onClose(int statusCode, String reason) {
            String detail = reason == null || reason.isBlank()
                    ? "Hrana WebSocket closed with code " + statusCode
                    : "Hrana WebSocket closed with code " + statusCode + ": " + reason;
            failAll(new LibsqlException(detail, "WS_CLOSED", 0));
        }
    }
}
