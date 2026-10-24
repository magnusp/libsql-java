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

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hrana 3 client over a WebSocket, speaking the JSON encoding ({@code hrana3} subprotocol).
 *
 * <p>One WebSocket connection multiplexes any number of streams. Requests are matched to responses
 * by {@code request_id}, so the client may send a whole one-shot sequence (open, execute, close)
 * without waiting in between and still receive the responses in order.
 *
 * <p>All request methods block the calling thread until the matching response arrives. The caller
 * (the JDBC connection) serializes calls, so a single interactive stream id is enough.
 *
 * <p><strong>Transport note.</strong> The WebSocket is spoken directly over a socket rather than
 * through {@link java.net.http.WebSocket}. sqld answers the upgrade with a {@code 101} that carries
 * {@code content-length: 31} and no body, and the JDK client completes its opening handshake only
 * after reading a full response body ({@code OpeningHandshake.send()} uses {@code
 * BodyHandlers.ofString()}), so it hangs against every sqld version. The framing below is the RFC
 * 6455 client subset Hrana needs: text/binary frames, fragmentation, ping/pong and close.
 */
public class LibsqlWebSocketClient implements LibsqlClient {

    private static final String SUBPROTOCOL = "hrana3";
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;
    private static final int CLOSE_NORMAL = 1000;

    private static final int MAX_FRAME_BYTES = 1024 * 1024 * 1024;

    private final LibsqlClientConfig config;
    private final HranaWsCodec codec;
    private final Duration timeout;
    private final Transport transport;

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
        this.transport = new Transport(URI.create(config.url()), timeout);

        try {
            send(new WsClientMsg.Hello(authToken()));
            awaitHello();
        } catch (RuntimeException e) {
            transport.abort();
            throw e;
        }
    }

    private String authToken() {
        String token = config.authToken();
        return token == null || token.isBlank() ? null : token;
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
            transport.close(CLOSE_NORMAL, "closing");
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
        transport.send(payload, codec.isBinary());
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
            transport.abort();
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
        transport.abort();
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
     * A minimal RFC 6455 client connection: the opening handshake plus frame encoding and decoding.
     *
     * <p>A reader thread routes complete text/binary messages to {@link #dispatch(String)} and
     * fails the pending futures when the connection ends. It is a virtual thread, so an idle
     * connection never keeps the JVM alive.
     */
    private final class Transport {

        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private final Object writeLock = new Object();

        private volatile boolean open = true;

        private Transport(URI uri, Duration timeout) {
            Socket connected = null;
            try {
                connected = connect(uri, timeout);
                this.socket = connected;
                this.in = connected.getInputStream();
                this.out = connected.getOutputStream();
                handshake(uri, in, out, timeout);
            } catch (IOException e) {
                closeQuietly(connected);
                throw new LibsqlException(
                        "Failed to open Hrana WebSocket to " + uri + ": " + e.getMessage(), e);
            }
            Thread.ofVirtual().name("hrana-ws-reader").start(this::readLoop);
        }

        private void send(byte[] payload, boolean binary) {
            sendFrame(binary ? OP_BINARY : OP_TEXT, payload);
        }

        private void close(int statusCode, String reason) {
            if (open) {
                try {
                    sendFrame(OP_CLOSE, closePayload(statusCode, reason));
                } catch (LibsqlException e) {
                    // The peer is already gone; closing the socket below is enough.
                }
            }
            open = false;
            failAll(new LibsqlException(
                    "Hrana WebSocket closed with code " + statusCode, "WS_CLOSED", 0));
            closeQuietly(socket);
        }

        private void abort() {
            open = false;
            closeQuietly(socket);
        }

        private void sendFrame(int opcode, byte[] payload) {
            synchronized (writeLock) {
                if (!open) {
                    throw new LibsqlException("Hrana WebSocket is closed", "WS_CLOSED", 0);
                }
                try {
                    out.write(0x80 | opcode);
                    int length = payload.length;
                    if (length <= 125) {
                        out.write(0x80 | length);
                    } else if (length <= 0xFFFF) {
                        out.write(0x80 | 126);
                        out.write(length >>> 8);
                        out.write(length);
                    } else {
                        out.write(0x80 | 127);
                        for (int shift = 56; shift >= 0; shift -= 8) {
                            out.write((int) ((long) length >>> shift) & 0xFF);
                        }
                    }
                    byte[] mask = new byte[4];
                    ThreadLocalRandom.current().nextBytes(mask);
                    out.write(mask);
                    for (int i = 0; i < length; i++) {
                        out.write(payload[i] ^ mask[i & 3]);
                    }
                    out.flush();
                } catch (IOException e) {
                    open = false;
                    throw new LibsqlException(
                            "Failed to send Hrana message: " + e.getMessage(), e);
                }
            }
        }

        private void readLoop() {
            ByteArrayOutputStream fragments = new ByteArrayOutputStream();
            int fragmentedOpcode = -1;
            try {
                while (open) {
                    int first = in.read();
                    if (first < 0) {
                        break;
                    }
                    int second = readByte(in);
                    boolean fin = (first & 0x80) != 0;
                    int opcode = first & 0x0F;
                    if ((second & 0x80) != 0) {
                        protocolViolation("server sent a masked frame");
                        return;
                    }
                    long length = second & 0x7F;
                    if (length == 126) {
                        length = (readByte(in) << 8) | readByte(in);
                    } else if (length == 127) {
                        length = 0;
                        for (int i = 0; i < 8; i++) {
                            length = (length << 8) | readByte(in);
                        }
                    }
                    if (length < 0 || length > MAX_FRAME_BYTES) {
                        protocolViolation("frame is too large: " + length + " bytes");
                        return;
                    }
                    byte[] payload = in.readNBytes((int) length);
                    if (payload.length != length) {
                        break;
                    }

                    switch (opcode) {
                        case OP_TEXT, OP_BINARY -> {
                            if (fragmentedOpcode != -1) {
                                protocolViolation("new message before the fragmented one finished");
                                return;
                            }
                            if (fin) {
                                deliver(opcode, payload);
                            } else {
                                fragmentedOpcode = opcode;
                                fragments.write(payload);
                            }
                        }
                        case OP_CONTINUATION -> {
                            if (fragmentedOpcode == -1) {
                                protocolViolation("continuation without a started message");
                                return;
                            }
                            fragments.write(payload);
                            if (fin) {
                                byte[] complete = fragments.toByteArray();
                                int messageOpcode = fragmentedOpcode;
                                fragments.reset();
                                fragmentedOpcode = -1;
                                deliver(messageOpcode, complete);
                            }
                        }
                        case OP_PING -> sendFrame(OP_PONG, payload);
                        case OP_PONG -> {
                            // Unsolicited pongs are allowed and need no answer.
                        }
                        case OP_CLOSE -> {
                            handlePeerClose(payload);
                            return;
                        }
                        default -> {
                            protocolViolation("unknown frame opcode 0x" + Integer.toHexString(opcode));
                            return;
                        }
                    }
                }
                if (open) {
                    connectionLost("connection closed by the server without a close frame");
                }
            } catch (IOException e) {
                if (open) {
                    connectionLost("Hrana WebSocket connection failed: " + e.getMessage());
                }
            } finally {
                open = false;
                closeQuietly(socket);
            }
        }

        private void deliver(int opcode, byte[] payload) {
            boolean expectsBinary = codec.isBinary();
            if ((opcode == OP_BINARY) != expectsBinary) {
                protocolViolation("frame type does not match the negotiated encoding");
                return;
            }
            dispatch(payload);
        }

        private void handlePeerClose(byte[] payload) {
            int statusCode = payload.length >= 2
                    ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)
                    : CLOSE_NORMAL;
            String reason = payload.length > 2
                    ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8)
                    : "";
            try {
                // Echo the close frame while the connection is still writable.
                sendFrame(OP_CLOSE, closePayload(statusCode, ""));
            } catch (LibsqlException e) {
                // The peer closed; the socket is closed by the reader's finally block.
            }
            open = false;
            connectionLost(reason.isBlank()
                    ? "Hrana WebSocket closed with code " + statusCode
                    : "Hrana WebSocket closed with code " + statusCode + ": " + reason);
        }

        private void connectionLost(String detail) {
            failAll(new LibsqlException(detail, "WS_CLOSED", 0));
        }
    }

    private static Socket connect(URI uri, Duration timeout) throws IOException {
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("WebSocket URL has no host: " + uri);
        }
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);

        Socket socket = new Socket();
        try {
            int connectTimeout = (int) Math.min(Integer.MAX_VALUE, Math.max(0, timeout.toMillis()));
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            socket.setTcpNoDelay(true);
            if (!tls) {
                return socket;
            }
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, port, true);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            return ssl;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static void handshake(URI uri, InputStream in, OutputStream out, Duration timeout)
            throws IOException {
        String key = createKey();
        String expectedAccept = acceptKey(key);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            path = path + "?" + uri.getRawQuery();
        }
        String host = uri.getHost();
        int port = uri.getPort();
        String hostHeader = port > 0 ? host + ":" + port : host;

        StringBuilder request = new StringBuilder(256);
        request.append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n")
                .append("Sec-WebSocket-Protocol: ").append(SUBPROTOCOL).append("\r\n")
                .append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String statusLine = readLine(in);
        if (statusLine == null || !statusLine.contains(" 101 ")) {
            throw new IOException("WebSocket upgrade rejected: " + statusLine);
        }
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        if (!"websocket".equalsIgnoreCase(headers.get("upgrade"))) {
            throw new IOException("WebSocket upgrade response missing Upgrade: websocket");
        }
        if (!expectedAccept.equals(headers.get("sec-websocket-accept"))) {
            throw new IOException("WebSocket upgrade response has an invalid Sec-WebSocket-Accept");
        }
        String negotiated = headers.get("sec-websocket-protocol");
        if (!SUBPROTOCOL.equals(negotiated)) {
            throw new IOException("Server did not negotiate subprotocol " + SUBPROTOCOL
                    + " (got " + negotiated + ")");
        }
    }

    private static String createKey() {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    private static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the WebSocket protocol", e);
        }
    }

    private static byte[] closePayload(int statusCode, String reason) {
        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (statusCode >>> 8);
        payload[1] = (byte) statusCode;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        return payload;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            line.append((char) b);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("Unexpected end of stream");
        }
        return b;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful can be done with a failure to close.
        }
    }
}
