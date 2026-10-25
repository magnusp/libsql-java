package io.github.magnusp.libsql.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;

    /** Read-only {@link Blob} over the materialized bytes of one result column. */
final class LibsqlBlob implements Blob {

        private byte[] bytes;
        private boolean freed;

        LibsqlBlob(byte[] bytes) {
            this.bytes = bytes;
        }

        private byte[] data() throws SQLException {
            if (freed) {
                throw new SQLException("Blob has been freed");
            }
            return bytes;
        }

        @Override
        public long length() throws SQLException {
            return data().length;
        }

        @Override
        public byte[] getBytes(long pos, int length) throws SQLException {
            byte[] data = data();
            if (pos < 1) {
                throw new SQLException("Invalid position: " + pos);
            }
            if (length < 0) {
                throw new SQLException("Invalid length: " + length);
            }
            int start = (int) Math.min(pos - 1, data.length);
            int end = Math.min(start + length, data.length);
            return Arrays.copyOfRange(data, start, end);
        }

        @Override
        public InputStream getBinaryStream() throws SQLException {
            return new ByteArrayInputStream(data());
        }

        @Override
        public long position(byte[] pattern, long start) throws SQLException {
            if (start < 1) {
                throw new SQLException("Invalid position: " + start);
            }
            byte[] data = data();
            int from = (int) Math.min(start - 1, data.length);
            for (int i = from; i + pattern.length <= data.length; i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return i + 1L;
                }
            }
            return -1;
        }

        @Override
        public long position(Blob pattern, long start) throws SQLException {
            return position(pattern.getBytes(1, (int) pattern.length()), start);
        }

        @Override
        public int setBytes(long pos, byte[] bytes) throws SQLException {
            throw new SQLFeatureNotSupportedException("Blob is read-only");
        }

        @Override
        public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
            throw new SQLFeatureNotSupportedException("Blob is read-only");
        }

        @Override
        public OutputStream setBinaryStream(long pos) throws SQLException {
            throw new SQLFeatureNotSupportedException("Blob is read-only");
        }

        @Override
        public void truncate(long len) throws SQLException {
            throw new SQLFeatureNotSupportedException("Blob is read-only");
        }

        @Override
        public void free() throws SQLException {
            freed = true;
            bytes = null;
        }

        @Override
        public InputStream getBinaryStream(long pos, long length) throws SQLException {
            if (length < 0) {
                throw new SQLException("Invalid length: " + length);
            }
            return new ByteArrayInputStream(getBytes(pos, (int) Math.min(length, Integer.MAX_VALUE)));
        }
}
