package com.github.magnusp.libsql.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

    /** Read-only {@link Clob} over the materialized text of one result column. */
class LibsqlClob implements Clob {

        private String text;
        private boolean freed;

        LibsqlClob(String text) {
            this.text = text;
        }

        private String data() throws SQLException {
            if (freed) {
                throw new SQLException("Clob has been freed");
            }
            return text;
        }

        @Override
        public long length() throws SQLException {
            return data().length();
        }

        @Override
        public String getSubString(long pos, int length) throws SQLException {
            String data = data();
            if (pos < 1) {
                throw new SQLException("Invalid position: " + pos);
            }
            if (length < 0) {
                throw new SQLException("Invalid length: " + length);
            }
            int start = (int) Math.min(pos - 1, data.length());
            int end = Math.min(start + length, data.length());
            return data.substring(start, end);
        }

        @Override
        public Reader getCharacterStream() throws SQLException {
            return new StringReader(data());
        }

        @Override
        public InputStream getAsciiStream() throws SQLException {
            return new ByteArrayInputStream(data().getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public long position(String searchstr, long start) throws SQLException {
            if (start < 1) {
                throw new SQLException("Invalid position: " + start);
            }
            String data = data();
            int from = (int) Math.min(start - 1, data.length());
            int index = data.indexOf(searchstr, from);
            return index < 0 ? -1 : index + 1L;
        }

        @Override
        public long position(Clob searchstr, long start) throws SQLException {
            return position(searchstr.getSubString(1, (int) searchstr.length()), start);
        }

        @Override
        public int setString(long pos, String str) throws SQLException {
            throw new SQLFeatureNotSupportedException("Clob is read-only");
        }

        @Override
        public int setString(long pos, String str, int offset, int len) throws SQLException {
            throw new SQLFeatureNotSupportedException("Clob is read-only");
        }

        @Override
        public OutputStream setAsciiStream(long pos) throws SQLException {
            throw new SQLFeatureNotSupportedException("Clob is read-only");
        }

        @Override
        public Writer setCharacterStream(long pos) throws SQLException {
            throw new SQLFeatureNotSupportedException("Clob is read-only");
        }

        @Override
        public void truncate(long len) throws SQLException {
            throw new SQLFeatureNotSupportedException("Clob is read-only");
        }

        @Override
        public void free() throws SQLException {
            freed = true;
            text = null;
        }

        @Override
        public Reader getCharacterStream(long pos, long length) throws SQLException {
            if (length < 0) {
                throw new SQLException("Invalid length: " + length);
            }
            return new StringReader(getSubString(pos, (int) Math.min(length, Integer.MAX_VALUE)));
        }
}
