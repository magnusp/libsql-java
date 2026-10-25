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
import java.util.Map;

/**
 * Resolves the JDBC type of a result-set column from its declared type.
 *
 * <p>A column whose {@code decltype} names a type in the registry resolves once, independently of
 * the stored value. A column with no declared type, or with a declared type outside the registry,
 * falls back to the storage class of the value and marks the result {@link Support#FALLBACK} so a
 * caller can record the exception.
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

    private static final Map<String, JdbcType> DECLARED = declaredTypes();

    private JdbcTypeMapping() {}

    static JdbcType resolve(Col col, Value value) {
        if (col != null) {
            String name = DeclaredType.of(col).declaredTypeName();
            if (name != null) {
                JdbcType declared = DECLARED.get(name);
                if (declared != null) {
                    return declared;
                }
            }
        }
        return fallback(value);
    }

    private static JdbcType fallback(Value value) {
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

    private static Map<String, JdbcType> declaredTypes() {
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
}
