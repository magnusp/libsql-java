package io.github.magnusp.libsql.conformance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XerialTestServerTest {

    @Test
    void acceptsOnlySuccessAndMethodNotAllowedAsReady() {
        assertThat(XerialTestServer.isReadyStatus(200)).isTrue();
        assertThat(XerialTestServer.isReadyStatus(204)).isTrue();
        assertThat(XerialTestServer.isReadyStatus(405)).isTrue();
        assertThat(XerialTestServer.isReadyStatus(301)).isFalse();
        assertThat(XerialTestServer.isReadyStatus(400)).isFalse();
        assertThat(XerialTestServer.isReadyStatus(401)).isFalse();
        assertThat(XerialTestServer.isReadyStatus(403)).isFalse();
        assertThat(XerialTestServer.isReadyStatus(404)).isFalse();
        assertThat(XerialTestServer.isReadyStatus(500)).isFalse();
    }
}
