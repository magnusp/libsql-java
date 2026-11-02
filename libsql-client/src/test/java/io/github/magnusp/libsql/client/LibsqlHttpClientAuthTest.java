package io.github.magnusp.libsql.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.magnusp.libsql.hrana.codec.Stmt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibsqlHttpClientAuthTest {

    private static final byte[] FORBIDDEN =
            "{\"error\":\"Authorization forbidden: nope\"}".getBytes(StandardCharsets.UTF_8);

    private static final byte[] STREAM_STARTED =
            ("{\"baton\":\"baton-1\",\"results\":[{\"type\":\"ok\",\"response\":"
                            + "{\"type\":\"execute\",\"result\":{\"cols\":[],\"rows\":[],"
                            + "\"affected_row_count\":0,\"last_insert_rowid\":null}}}]}")
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void forbiddenResponseSurfacesTheServerMessageAndStatus() throws Exception {
        try (Server server = new Server(exchange -> respond(exchange, 403, FORBIDDEN))) {
            LibsqlHttpClient client = server.client();

            assertThatThrownBy(() -> client.executeOneShot(new Stmt("SELECT 1")))
                    .isInstanceOfSatisfying(LibsqlException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("Authorization forbidden: nope");
                        assertThat(e.getHttpStatus()).isEqualTo(403);
                    });
        }
    }

    @Test
    void forbiddenResponseOnAStreamDropsTheBaton() throws Exception {
        AtomicInteger request = new AtomicInteger();
        try (Server server = new Server(exchange -> {
            if (request.getAndIncrement() == 1) {
                respond(exchange, 403, FORBIDDEN);
            } else {
                respond(exchange, 200, STREAM_STARTED);
            }
        })) {
            LibsqlHttpClient client = server.client();
            client.executeOnStream(new Stmt("SELECT 1"));
            assertThat(client.hasOpenStream()).isTrue();

            assertThatThrownBy(() -> client.executeOnStream(new Stmt("INSERT INTO t VALUES (1)")))
                    .isInstanceOfSatisfying(
                            LibsqlException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(403));
            assertThat(client.hasOpenStream()).isFalse();

            client.executeOnStream(new Stmt("SELECT 2"));
            assertThat(server.bodies.get(2)).doesNotContain("baton-1");
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static final class Server implements AutoCloseable {

        private final HttpServer server;
        private final List<String> bodies = new CopyOnWriteArrayList<>();

        Server(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v3/pipeline", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                handler.handle(exchange);
            });
            server.start();
        }

        LibsqlHttpClient client() {
            LibsqlClientConfig config = new LibsqlClientConfig(
                    "http://127.0.0.1:" + server.getAddress().getPort(), null);
            return new LibsqlHttpClient(config);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
