package com.github.magnusp.libsql.jdbc;

import com.github.magnusp.libsql.hrana.codec.Value;

import java.sql.Types;

/** xerial 3.53.4.0 column type mapping. */
final class XerialColumnType {

    private XerialColumnType() {}

    static int jdbcType(Value value, String typeName) {
        StorageClass valueType = storageClass(value);

        if (valueType == StorageClass.INTEGER || valueType == StorageClass.NULL) {
            if ("BOOLEAN".equals(typeName)) {
                return Types.BOOLEAN;
            }
            if ("TINYINT".equals(typeName)) {
                return Types.TINYINT;
            }
            if ("SMALLINT".equals(typeName) || "INT2".equals(typeName)) {
                return Types.SMALLINT;
            }
            if ("BIGINT".equals(typeName)
                    || "INT8".equals(typeName)
                    || "UNSIGNED BIG INT".equals(typeName)) {
                return Types.BIGINT;
            }
            if ("DATE".equals(typeName)) {
                return Types.DATE;
            }
            if ("DATETIME".equals(typeName) || "TIMESTAMP".equals(typeName)) {
                return Types.TIMESTAMP;
            }
            if (valueType == StorageClass.INTEGER
                    || "INT".equals(typeName)
                    || "INTEGER".equals(typeName)
                    || "MEDIUMINT".equals(typeName)) {
                long val = integer(value);
                return val > Integer.MAX_VALUE || val < Integer.MIN_VALUE ? Types.BIGINT : Types.INTEGER;
            }
        }

        if (valueType == StorageClass.FLOAT || valueType == StorageClass.NULL) {
            if ("DECIMAL".equals(typeName)) {
                return Types.DECIMAL;
            }
            if ("DOUBLE".equals(typeName) || "DOUBLE PRECISION".equals(typeName)) {
                return Types.DOUBLE;
            }
            if ("NUMERIC".equals(typeName)) {
                return Types.NUMERIC;
            }
            if ("REAL".equals(typeName)) {
                return Types.REAL;
            }
            if (valueType == StorageClass.FLOAT || "FLOAT".equals(typeName)) {
                return Types.FLOAT;
            }
        }

        if (valueType == StorageClass.TEXT || valueType == StorageClass.NULL) {
            if ("CHARACTER".equals(typeName)
                    || "NCHAR".equals(typeName)
                    || "NATIVE CHARACTER".equals(typeName)
                    || "CHAR".equals(typeName)) {
                return Types.CHAR;
            }
            if ("CLOB".equals(typeName)) {
                return Types.CLOB;
            }
            if ("DATE".equals(typeName)) {
                return Types.DATE;
            }
            if ("DATETIME".equals(typeName) || "TIMESTAMP".equals(typeName)) {
                return Types.TIMESTAMP;
            }
            if (valueType == StorageClass.TEXT
                    || "VARCHAR".equals(typeName)
                    || "VARYING CHARACTER".equals(typeName)
                    || "NVARCHAR".equals(typeName)
                    || "TEXT".equals(typeName)) {
                return Types.VARCHAR;
            }
        }

        if (valueType == StorageClass.BLOB || valueType == StorageClass.NULL) {
            if ("BINARY".equals(typeName)) {
                return Types.BINARY;
            }
            if (valueType == StorageClass.BLOB || "BLOB".equals(typeName)) {
                return Types.BLOB;
            }
        }

        return Types.NUMERIC;
    }

    static String className(Value value) {
        if (value instanceof Value.Integer i) {
            long val = i.value();
            return val > Integer.MAX_VALUE || val < Integer.MIN_VALUE
                    ? Long.class.getName()
                    : Integer.class.getName();
        }
        if (value instanceof Value.Float) {
            return Double.class.getName();
        }
        if (value instanceof Value.Text) {
            return String.class.getName();
        }
        return Object.class.getName();
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

    enum StorageClass {
        INTEGER,
        FLOAT,
        BLOB,
        TEXT,
        NULL
    }
}
