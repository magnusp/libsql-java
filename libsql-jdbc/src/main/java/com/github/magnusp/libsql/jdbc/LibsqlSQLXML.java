package com.github.magnusp.libsql.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;

import javax.xml.transform.Result;
import javax.xml.transform.Source;

/** Read-only {@link SQLXML} over the materialized text of one result column. */
final class LibsqlSQLXML implements SQLXML {

    private String text;
    private boolean freed;

    LibsqlSQLXML(String text) {
        this.text = text;
    }

    private String data() throws SQLException {
        if (freed) {
            throw new SQLException("SQLXML has been freed");
        }
        return text;
    }

    @Override
    public void free() throws SQLException {
        freed = true;
        text = null;
    }

    @Override
    public String getString() throws SQLException {
        return data();
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        return new ByteArrayInputStream(data().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        return new StringReader(data());
    }

    @Override
    public <T extends Source> T getSource(Class<T> sourceClass) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLXML sources are not supported");
    }

    @Override
    public OutputStream setBinaryStream() throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLXML is read-only");
    }

    @Override
    public Writer setCharacterStream() throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLXML is read-only");
    }

    @Override
    public void setString(String value) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLXML is read-only");
    }

    @Override
    public <T extends Result> T setResult(Class<T> resultClass) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLXML is read-only");
    }
}
