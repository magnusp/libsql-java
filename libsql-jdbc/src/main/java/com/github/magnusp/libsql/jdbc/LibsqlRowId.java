package com.github.magnusp.libsql.jdbc;

import java.sql.RowId;
import java.util.Arrays;

/** {@link RowId} over the materialized bytes of one result column. */
final class LibsqlRowId implements RowId {

    private final byte[] bytes;

    LibsqlRowId(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public byte[] getBytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LibsqlRowId rowId && Arrays.equals(bytes, rowId.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "LibsqlRowId" + Arrays.toString(bytes);
    }
}
