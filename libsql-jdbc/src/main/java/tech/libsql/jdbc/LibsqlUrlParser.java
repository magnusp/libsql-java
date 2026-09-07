package tech.libsql.jdbc;

import tech.libsql.client.LibsqlClientConfig;

import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class LibsqlUrlParser {

    public static final String PREFIX = "jdbc:libsql:";

    public static boolean acceptsUrl(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    public static LibsqlClientConfig parse(String url, Properties info) throws SQLException {
        if (!acceptsUrl(url)) {
            throw new SQLException("Invalid JDBC URL: " + url);
        }

        String rawUriStr = url.substring(PREFIX.length());
        boolean hasHttpScheme = rawUriStr.startsWith("http://") || rawUriStr.startsWith("https://");
        if (!hasHttpScheme) {
            if (rawUriStr.startsWith("//")) {
                rawUriStr = "http:" + rawUriStr;
            } else {
                rawUriStr = "http://" + rawUriStr;
            }
        }

        URI uri;
        try {
            uri = URI.create(rawUriStr);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Malformed libSQL JDBC URL: " + url, e);
        }

        Map<String, String> queryParams = parseQuery(uri.getRawQuery());

        // Extract auth token
        String authToken = null;
        if (queryParams.containsKey("authToken")) {
            authToken = queryParams.get("authToken");
        } else if (queryParams.containsKey("jwt")) {
            authToken = queryParams.get("jwt");
        } else if (info != null) {
            authToken = info.getProperty("authToken", info.getProperty("password", null));
        }

        // Check TLS override
        String scheme = uri.getScheme();
        if ("true".equalsIgnoreCase(queryParams.get("tls")) || "true".equalsIgnoreCase(queryParams.get("ssl"))) {
            scheme = "https";
        }

        // Timeouts
        Duration connectTimeout = LibsqlClientConfig.DEFAULT_CONNECT_TIMEOUT;
        if (queryParams.containsKey("connectTimeout")) {
            connectTimeout = Duration.ofMillis(Long.parseLong(queryParams.get("connectTimeout")));
        } else if (info != null && info.containsKey("connectTimeout")) {
            connectTimeout = Duration.ofMillis(Long.parseLong(info.getProperty("connectTimeout")));
        }

        Duration busyTimeout = LibsqlClientConfig.DEFAULT_BUSY_TIMEOUT;
        if (queryParams.containsKey("busyTimeout")) {
            busyTimeout = Duration.ofMillis(Long.parseLong(queryParams.get("busyTimeout")));
        } else if (info != null && info.containsKey("busyTimeout")) {
            busyTimeout = Duration.ofMillis(Long.parseLong(info.getProperty("busyTimeout")));
        }

        int port = uri.getPort();
        String host = uri.getHost();
        String path = uri.getPath() != null ? uri.getPath() : "";

        StringBuilder httpUrl = new StringBuilder();
        httpUrl.append(scheme).append("://").append(host);
        if (port > 0) {
            httpUrl.append(":").append(port);
        }
        httpUrl.append(path);

        return new LibsqlClientConfig(httpUrl.toString(), authToken, connectTimeout, busyTimeout);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0 && idx < pair.length() - 1) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            } else if (idx > 0) {
                params.put(pair.substring(0, idx), "");
            }
        }
        return params;
    }
}
