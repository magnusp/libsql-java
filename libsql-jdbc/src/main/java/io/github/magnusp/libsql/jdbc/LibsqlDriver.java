package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.client.LibsqlClient;
import io.github.magnusp.libsql.client.LibsqlClientConfig;
import io.github.magnusp.libsql.client.LibsqlHttpClient;
import io.github.magnusp.libsql.client.LibsqlWebSocketClient;
import io.github.magnusp.libsql.hrana.codec.DirectHranaJsonCodec;
import io.github.magnusp.libsql.hrana.codec.HranaWsCodec;
import io.github.magnusp.libsql.hrana.codec.HranaWsProtobufCodec;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

public class LibsqlDriver implements Driver {

    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 1;

    static {
        try {
            DriverManager.registerDriver(new LibsqlDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        LibsqlClientConfig config = LibsqlUrlParser.parse(url, info);
        LibsqlClient client;
        if (isWebSocketUrl(config.url())) {
            HranaWsCodec codec = config.encoding() == LibsqlClientConfig.Encoding.PROTOBUF
                    ? new HranaWsProtobufCodec()
                    : new DirectHranaJsonCodec();
            client = new LibsqlWebSocketClient(config, codec);
        } else {
            client = new LibsqlHttpClient(config);
        }
        return new LibsqlConnection(config, client, url);
    }

    private static boolean isWebSocketUrl(String url) {
        return url.startsWith("ws://") || url.startsWith("wss://");
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return LibsqlUrlParser.acceptsUrl(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used");
    }
}
