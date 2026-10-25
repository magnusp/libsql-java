package io.github.magnusp.libsql.client;

import java.time.Duration;

public record LibsqlClientConfig(
    String url,
    String authToken,
    Duration connectTimeout,
    Duration busyTimeout,
    Encoding encoding
) {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_BUSY_TIMEOUT = Duration.ofSeconds(5);

    /** The wire encoding used for Hrana 3 over WebSocket. HTTP always speaks JSON. */
    public enum Encoding {
        JSON,
        PROTOBUF
    }

    public LibsqlClientConfig {
        encoding = encoding == null ? Encoding.JSON : encoding;
    }

    public LibsqlClientConfig(String url, String authToken, Duration connectTimeout, Duration busyTimeout) {
        this(url, authToken, connectTimeout, busyTimeout, Encoding.JSON);
    }

    public LibsqlClientConfig(String url, String authToken) {
        this(url, authToken, DEFAULT_CONNECT_TIMEOUT, DEFAULT_BUSY_TIMEOUT, Encoding.JSON);
    }
}
