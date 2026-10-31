package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Value;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Reads a parameter stream or reader into the Hrana value its setter binds.
 *
 * <p>Every method is a pure function of the stream, the optional length, and the character set.
 * A null stream binds SQL NULL. A length outside the Java array range and a stream that ends early
 * keep the sqlite-jdbc 3.53.4.0 messages, and a read failure is reported as an {@link SQLException}
 * rather than swallowed.
 */
final class ParameterStreams {

    private ParameterStreams() {}

    static Value asciiText(byte[] bytes) {
        return Value.of(bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII));
    }

    static Value utf8Text(byte[] bytes) {
        return Value.of(bytes == null ? null : new String(bytes, StandardCharsets.UTF_8));
    }

    static byte[] readAllBytes(InputStream in) throws SQLException {
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new SQLException("Cannot read from binary stream: " + e.getMessage(), e);
        }
    }

    static byte[] readBytes(InputStream in, long length) throws SQLException {
        if (in == null) {
            return null;
        }
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new SQLException("Data must have a length between 0 and Integer.MAX_VALUE");
        }
        byte[] bytes = new byte[(int) length];
        int total = 0;
        try {
            while (total < bytes.length) {
                int read = in.read(bytes, total, bytes.length - total);
                if (read < 0) {
                    throw new SQLException(
                            "End of stream reached before " + length + " bytes were read");
                }
                total += read;
            }
        } catch (IOException e) {
            throw new SQLException("Cannot read from binary stream: " + e.getMessage(), e);
        }
        return bytes;
    }

    static String readAllCharacters(Reader reader) throws SQLException {
        if (reader == null) {
            return null;
        }
        try {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                text.append(buffer, 0, read);
            }
            return text.toString();
        } catch (IOException e) {
            throw new SQLException("Cannot read from character stream: " + e.getMessage(), e);
        }
    }

    static String readCharacters(Reader reader, long length) throws SQLException {
        if (reader == null) {
            return null;
        }
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new SQLException("Data must have a length between 0 and Integer.MAX_VALUE");
        }
        StringBuilder text = new StringBuilder((int) Math.min(length, 8192));
        char[] buffer = new char[8192];
        long remaining = length;
        try {
            while (remaining > 0) {
                int read = reader.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (read < 0) {
                    throw new SQLException(
                            "End of stream reached before " + length + " characters were read");
                }
                text.append(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException e) {
            throw new SQLException("Cannot read from character stream: " + e.getMessage(), e);
        }
        return text.toString();
    }
}
