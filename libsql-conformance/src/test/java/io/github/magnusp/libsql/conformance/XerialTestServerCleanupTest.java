package io.github.magnusp.libsql.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class XerialTestServerCleanupTest {

    private static final String ADMIN_URL = "http://admin.test";

    private final FakeAdmin admin = new FakeAdmin();
    private final List<String> namespaces = new ArrayList<>();

    @Test
    void failedDeleteIsVisibleAndBlocksNamespaceReuse() {
        String namespace = namespace();

        XerialTestServer.ensureNamespace(namespace, ADMIN_URL, admin);
        assertThat(admin.createRequests).hasValue(1);

        admin.deleteStatus.set(500);
        assertThatThrownBy(
                        () ->
                                XerialTestServer.releaseNamespace(
                                        namespace, ADMIN_URL, admin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to delete namespace")
                .hasMessageContaining("500");
        assertThat(admin.deleteRequests).hasValue(3);

        admin.createStatus.set(409);
        assertThatThrownBy(
                        () -> XerialTestServer.ensureNamespace(namespace, ADMIN_URL, admin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be reused");
        assertThat(admin.createRequests).hasValue(1);
    }

    @Test
    void deleteRetriesTransientFailuresAndAllowsReuseAfterwards() {
        String namespace = namespace();

        XerialTestServer.ensureNamespace(namespace, ADMIN_URL, admin);
        admin.deleteFailuresRemaining.set(2);

        assertThatCode(
                        () ->
                                XerialTestServer.releaseNamespace(
                                        namespace, ADMIN_URL, admin))
                .doesNotThrowAnyException();
        assertThat(admin.deleteRequests).hasValue(3);

        XerialTestServer.ensureNamespace(namespace, ADMIN_URL, admin);
        assertThat(admin.createRequests).hasValue(2);
    }

    @Test
    void deleteTreatsAlreadyMissingNamespaceAsSuccess() {
        String namespace = namespace();

        XerialTestServer.ensureNamespace(namespace, ADMIN_URL, admin);
        admin.deleteStatus.set(404);

        assertThatCode(
                        () ->
                                XerialTestServer.releaseNamespace(
                                        namespace, ADMIN_URL, admin))
                .doesNotThrowAnyException();
        assertThat(admin.deleteRequests).hasValue(1);
    }

    @Test
    void createConflictIsNotTreatedAsSuccess() {
        String namespace = namespace();

        admin.createStatus.set(409);
        assertThatThrownBy(
                        () -> XerialTestServer.ensureNamespace(namespace, ADMIN_URL, admin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("409")
                .hasMessageContaining("cannot be reused");
        assertThat(admin.createRequests).hasValue(1);
    }

    @AfterEach
    void resetTrackedNamespaces() {
        namespaces.forEach(XerialTestServer::resetNamespaceForTest);
    }

    private String namespace() {
        String namespace = "cleanup_" + UUID.randomUUID().toString().replace("-", "");
        namespaces.add(namespace);
        return namespace;
    }

    private static final class FakeAdmin implements XerialTestServer.AdminClient {

        private final AtomicInteger createStatus = new AtomicInteger(200);
        private final AtomicInteger deleteStatus = new AtomicInteger(200);
        private final AtomicInteger deleteFailuresRemaining = new AtomicInteger();
        private final AtomicInteger createRequests = new AtomicInteger();
        private final AtomicInteger deleteRequests = new AtomicInteger();

        @Override
        public XerialTestServer.AdminResponse send(HttpRequest request) {
            if ("POST".equals(request.method())
                    && request.uri().getPath().endsWith("/create")) {
                createRequests.incrementAndGet();
                return response(createStatus.get());
            }
            if ("DELETE".equals(request.method())) {
                deleteRequests.incrementAndGet();
                if (deleteFailuresRemaining.getAndUpdate(
                                remaining -> remaining > 0 ? remaining - 1 : 0)
                        > 0) {
                    return response(500);
                }
                return response(deleteStatus.get());
            }
            return response(404);
        }

        private static XerialTestServer.AdminResponse response(int status) {
            return new XerialTestServer.AdminResponse(status, "status " + status);
        }
    }
}
