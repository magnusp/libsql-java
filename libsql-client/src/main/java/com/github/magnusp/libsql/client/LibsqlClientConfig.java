package com.github.magnusp.libsql.client;

import java.time.Duration;

public record LibsqlClientConfig(
    String url,
    String authToken,
    Duration connectTimeout,
    Duration busyTimeout
) {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_BUSY_TIMEOUT = Duration.ofSeconds(5);

    public LibsqlClientConfig(String url, String authToken) {
        this(url, authToken, DEFAULT_CONNECT_TIMEOUT, DEFAULT_BUSY_TIMEOUT);
    }
}
