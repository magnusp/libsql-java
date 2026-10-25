package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlClientConfig;

import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class LibsqlUrlParser {

    public static final String PREFIX = "jdbc:libsql:";
    public static final String SQLITE_PREFIX = "jdbc:sqlite:";

    public static boolean acceptsUrl(String url) {
        if (url == null) return false;
        if (url.startsWith(PREFIX)) return true;
        return url.startsWith("jdbc:sqlite:http://")
                || url.startsWith("jdbc:sqlite:https://")
                || url.startsWith("jdbc:sqlite:ws://")
                || url.startsWith("jdbc:sqlite:wss://");
    }

    public static LibsqlClientConfig parse(String url, Properties info) throws SQLException {
        if (!acceptsUrl(url)) {
            throw new SQLException("Invalid JDBC URL: " + url);
        }
        URI uri = normalizeUri(url);
        Map<String, String> queryParams = parseQuery(uri.getRawQuery());
        String authToken = resolveAuthToken(queryParams, info);
        String scheme = resolveScheme(uri.getScheme(), queryParams);
        LibsqlClientConfig.Encoding encoding = resolveEncoding(queryParams, scheme);
        Duration connectTimeout =
                resolveDuration("connectTimeout", queryParams, info, LibsqlClientConfig.DEFAULT_CONNECT_TIMEOUT);
        Duration busyTimeout =
                resolveDuration("busyTimeout", queryParams, info, LibsqlClientConfig.DEFAULT_BUSY_TIMEOUT);
        return new LibsqlClientConfig(
                buildEndpoint(uri, scheme), authToken, connectTimeout, busyTimeout, encoding);
    }

    private static URI normalizeUri(String url) throws SQLException {
        String rawUriStr = url.startsWith(PREFIX)
                ? url.substring(PREFIX.length())
                : url.substring(SQLITE_PREFIX.length());
        if (!hasExplicitScheme(rawUriStr)) {
            rawUriStr = rawUriStr.startsWith("//") ? "http:" + rawUriStr : "http://" + rawUriStr;
        }
        try {
            return URI.create(rawUriStr);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Malformed libSQL JDBC URL: " + url, e);
        }
    }

    private static boolean hasExplicitScheme(String rawUriStr) {
        return rawUriStr.startsWith("http://")
                || rawUriStr.startsWith("https://")
                || rawUriStr.startsWith("ws://")
                || rawUriStr.startsWith("wss://");
    }

    private static String resolveAuthToken(Map<String, String> queryParams, Properties info) {
        if (queryParams.containsKey("authToken")) {
            return queryParams.get("authToken");
        }
        if (queryParams.containsKey("jwt")) {
            return queryParams.get("jwt");
        }
        return info != null ? info.getProperty("authToken", info.getProperty("password", null)) : null;
    }

    private static String resolveScheme(String scheme, Map<String, String> queryParams) {
        boolean tlsRequested = "true".equalsIgnoreCase(queryParams.get("tls"))
                || "true".equalsIgnoreCase(queryParams.get("ssl"));
        if (!tlsRequested) {
            return scheme;
        }
        return switch (scheme) {
            case "http" -> "https";
            case "ws" -> "wss";
            default -> scheme;
        };
    }

    private static LibsqlClientConfig.Encoding resolveEncoding(
            Map<String, String> queryParams, String scheme) throws SQLException {
        LibsqlClientConfig.Encoding encoding = LibsqlClientConfig.Encoding.JSON;
        String encodingParam = queryParams.get("encoding");
        if (encodingParam != null) {
            encoding = switch (encodingParam.toLowerCase(Locale.ROOT)) {
                case "json" -> LibsqlClientConfig.Encoding.JSON;
                case "protobuf", "proto" -> LibsqlClientConfig.Encoding.PROTOBUF;
                default -> throw new SQLException(
                        "Unsupported encoding '" + encodingParam + "': expected json or protobuf");
            };
        }
        if (encoding == LibsqlClientConfig.Encoding.PROTOBUF
                && !"ws".equals(scheme) && !"wss".equals(scheme)) {
            throw new SQLException("encoding=protobuf is only supported for ws/wss URLs");
        }
        return encoding;
    }

    private static Duration resolveDuration(
            String name, Map<String, String> queryParams, Properties info, Duration defaultValue) {
        if (queryParams.containsKey(name)) {
            return Duration.ofMillis(Long.parseLong(queryParams.get(name)));
        }
        if (info != null && info.containsKey(name)) {
            return Duration.ofMillis(Long.parseLong(info.getProperty(name)));
        }
        return defaultValue;
    }

    private static String buildEndpoint(URI uri, String scheme) {
        int port = uri.getPort();
        String path = uri.getPath() != null ? uri.getPath() : "";
        StringBuilder endpointUrl = new StringBuilder();
        endpointUrl.append(scheme).append("://").append(uri.getHost());
        if (port > 0) {
            endpointUrl.append(":").append(port);
        }
        endpointUrl.append(path);
        return endpointUrl.toString();
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
