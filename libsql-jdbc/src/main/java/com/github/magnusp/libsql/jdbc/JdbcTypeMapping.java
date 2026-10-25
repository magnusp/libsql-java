package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Col;
import com.github.magnusp.libsql.hrana.codec.Value;

import java.math.BigDecimal;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the JDBC type of a result-set column from its declared type.
 *
 * <p>A column whose {@code decltype} names a type in the registry resolves once, independently of
 * the stored value. A column with no declared type, or with a declared type outside the registry,
 * falls back to the storage class of the value and marks the result {@link Support#FALLBACK} so a
 * caller can record the exception.
 *
 * <p>The registry holds the 27 canonical JDBC 4.3 Appendix B type names. A separate alias table
 * maps the SQLite spellings that Appendix B does not name, such as {@code INT8} and {@code
 * DATETIME}, to the registry entry they stand for, preserving the sqlite-jdbc metadata that
 * existing users see. Both tables resolve through the declared type, never through the value.
 */
final class JdbcTypeMapping {

    enum Support {
        SUPPORTED,
        FALLBACK,
        UNSUPPORTED
    }

    enum StorageClass {
        INTEGER,
        FLOAT,
        TEXT,
        BLOB,
        NULL
    }

    record JdbcType(int sqlType, Class<?> javaClass, Class<?> objectClass, StorageClass storage, Support support) {}

    private static final JdbcType INTEGER_FALLBACK =
            fallback(Types.INTEGER, int.class, Integer.class, StorageClass.INTEGER);
    private static final JdbcType BIGINT_FALLBACK =
            fallback(Types.BIGINT, long.class, Long.class, StorageClass.INTEGER);
    private static final JdbcType FLOAT_FALLBACK =
            fallback(Types.FLOAT, double.class, Double.class, StorageClass.FLOAT);
    private static final JdbcType TEXT_FALLBACK =
            fallback(Types.VARCHAR, String.class, String.class, StorageClass.TEXT);
    private static final JdbcType BLOB_FALLBACK =
            fallback(Types.VARBINARY, byte[].class, byte[].class, StorageClass.BLOB);
    private static final JdbcType NULL_FALLBACK =
            fallback(Types.NUMERIC, Object.class, Object.class, StorageClass.NULL);

    private static final Map<String, JdbcType> DECLARED = declaredTypeRegistry();
    private static final Map<String, JdbcType> ALIASES = aliasTypes();

    private JdbcTypeMapping() {}

    /**
     * Resolves one column's JDBC type once for the life of a result set.
     *
     * <p>The declared type of a column never changes within a result set, so the registry is
     * consulted when the result set is built. {@code getObject} and the metadata then read the
     * entry. An expression column has no declared type and keeps a null entry; resolving such a
     * column is a class test and a switch over the current {@link Value}.
     */
    static JdbcType[] declaredTypes(List<Col> cols) {
        JdbcType[] declared = new JdbcType[cols.size()];
        for (int i = 0; i < declared.length; i++) {
            declared[i] = declared(cols.get(i));
        }
        return declared;
    }

    private static JdbcType declared(Col col) {
        if (col == null) {
            return null;
        }
        String name = DeclaredType.of(col).declaredTypeName();
        if (name == null) {
            return null;
        }
        JdbcType type = DECLARED.get(name);
        return type != null ? type : ALIASES.get(name);
    }

    static JdbcType fallback(Value value) {
        return switch (storageClass(value)) {
            case INTEGER -> integerFallback(value);
            case FLOAT -> FLOAT_FALLBACK;
            case TEXT -> TEXT_FALLBACK;
            case BLOB -> BLOB_FALLBACK;
            case NULL -> NULL_FALLBACK;
        };
    }

    private static JdbcType integerFallback(Value value) {
        long number = integer(value);
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? INTEGER_FALLBACK : BIGINT_FALLBACK;
    }

    static StorageClass storageClass(Value value) {
        if (value instanceof Value.Integer) {
            return StorageClass.INTEGER;
        }
        if (value instanceof Value.Float) {
            return StorageClass.FLOAT;
        }
        if (value instanceof Value.Blob) {
            return StorageClass.BLOB;
        }
        if (value instanceof Value.Text) {
            return StorageClass.TEXT;
        }
        return StorageClass.NULL;
    }

    static long integer(Value value) {
        return value instanceof Value.Integer i ? i.value() : 0L;
    }

    private static JdbcType fallback(int sqlType, Class<?> javaClass, Class<?> objectClass, StorageClass storage) {
        return new JdbcType(sqlType, javaClass, objectClass, storage, Support.FALLBACK);
    }

    private static Map<String, JdbcType> declaredTypeRegistry() {
        return Map.ofEntries(
                entry("TINYINT", Types.TINYINT, byte.class, Integer.class, StorageClass.INTEGER),
                entry("SMALLINT", Types.SMALLINT, short.class, Integer.class, StorageClass.INTEGER),
                entry("INTEGER", Types.INTEGER, int.class, Integer.class, StorageClass.INTEGER),
                entry("BIGINT", Types.BIGINT, long.class, Long.class, StorageClass.INTEGER),
                entry("REAL", Types.REAL, float.class, Float.class, StorageClass.FLOAT),
                entry("FLOAT", Types.FLOAT, double.class, Double.class, StorageClass.FLOAT),
                entry("DOUBLE", Types.DOUBLE, double.class, Double.class, StorageClass.FLOAT),
                entry("NUMERIC", Types.NUMERIC, BigDecimal.class, BigDecimal.class, StorageClass.FLOAT),
                entry("DECIMAL", Types.DECIMAL, BigDecimal.class, BigDecimal.class, StorageClass.FLOAT),
                entry("BIT", Types.BIT, boolean.class, Boolean.class, StorageClass.INTEGER),
                entry("BOOLEAN", Types.BOOLEAN, boolean.class, Boolean.class, StorageClass.INTEGER),
                entry("CHAR", Types.CHAR, String.class, String.class, StorageClass.TEXT),
                entry("VARCHAR", Types.VARCHAR, String.class, String.class, StorageClass.TEXT),
                entry("LONGVARCHAR", Types.LONGVARCHAR, String.class, String.class, StorageClass.TEXT),
                entry("NCHAR", Types.NCHAR, String.class, String.class, StorageClass.TEXT),
                entry("NVARCHAR", Types.NVARCHAR, String.class, String.class, StorageClass.TEXT),
                entry("LONGNVARCHAR", Types.LONGNVARCHAR, String.class, String.class, StorageClass.TEXT),
                entry("BINARY", Types.BINARY, byte[].class, byte[].class, StorageClass.BLOB),
                entry("VARBINARY", Types.VARBINARY, byte[].class, byte[].class, StorageClass.BLOB),
                entry("LONGVARBINARY", Types.LONGVARBINARY, byte[].class, byte[].class, StorageClass.BLOB),
                entry("DATE", Types.DATE, Date.class, Date.class, StorageClass.TEXT),
                entry("TIME", Types.TIME, Time.class, Time.class, StorageClass.TEXT),
                entry("TIMESTAMP", Types.TIMESTAMP, Timestamp.class, Timestamp.class, StorageClass.TEXT),
                entry("CLOB", Types.CLOB, Clob.class, Clob.class, StorageClass.TEXT),
                entry("NCLOB", Types.NCLOB, NClob.class, NClob.class, StorageClass.TEXT),
                entry("BLOB", Types.BLOB, Blob.class, Blob.class, StorageClass.BLOB),
                entry("DATALINK", Types.DATALINK, URL.class, URL.class, StorageClass.TEXT));
    }

    private static Map.Entry<String, JdbcType> entry(
            String name, int sqlType, Class<?> javaClass, Class<?> objectClass, StorageClass storage) {
        return Map.entry(name, new JdbcType(sqlType, javaClass, objectClass, storage, Support.SUPPORTED));
    }

    /**
     * Maps the SQLite spellings that sqlite-jdbc 3.53.4.0 reported with a canonical JDBC type to
     * the registry entry for that type.
     *
     * <p>Appendix B does not name these spellings, so sqlite-jdbc parity decides their metadata.
     * The alias resolves to the same {@link JdbcType} instance as its canonical name, which keeps
     * the {@code getObject} conversion and the metadata in agreement with the canonical type. An
     * alias never inspects the value: {@code INT8} is {@code BIGINT} even when the value is small.
     */
    private static Map<String, JdbcType> aliasTypes() {
        Map<String, JdbcType> aliases = new HashMap<>();
        alias(aliases, "INT", "INTEGER");
        alias(aliases, "INT2", "SMALLINT");
        alias(aliases, "INT8", "BIGINT");
        alias(aliases, "MEDIUMINT", "INTEGER");
        alias(aliases, "UNSIGNED BIG INT", "BIGINT");
        alias(aliases, "DATETIME", "TIMESTAMP");
        alias(aliases, "DOUBLE PRECISION", "DOUBLE");
        alias(aliases, "TEXT", "VARCHAR");
        alias(aliases, "CHARACTER", "CHAR");
        alias(aliases, "NATIVE CHARACTER", "CHAR");
        alias(aliases, "VARYING CHARACTER", "VARCHAR");
        return Map.copyOf(aliases);
    }

    private static void alias(Map<String, JdbcType> aliases, String spelling, String canonical) {
        JdbcType type = DECLARED.get(canonical);
        if (type == null) {
            throw new IllegalStateException("Unknown canonical type " + canonical + " for alias " + spelling);
        }
        aliases.put(spelling, type);
    }
}
