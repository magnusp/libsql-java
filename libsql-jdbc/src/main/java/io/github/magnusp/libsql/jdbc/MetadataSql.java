package io.github.magnusp.libsql.jdbc;

/**
 * Query builders for the JDBC metadata surfaces. The SQL text is the contract: tests assert
 * fragments byte for byte, and the test doubles dispatch on substrings such as {@code JOIN
 * pragma_foreign_key_list}. Change the construction idiom, never the bytes.
 */
final class MetadataSql {

    private MetadataSql() {}

    static String literal(String value) {
        return value.replace("'", "''");
    }

    static String tables(String tableNamePattern, String[] types) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, NAME AS TABLE_NAME, TYPE AS TABLE_TYPE, ")
                .append("NULL AS REMARKS, NULL AS TYPE_CAT, NULL AS TYPE_SCHEM, NULL AS TYPE_NAME, ")
                .append("NULL AS SELF_REFERENCING_COL_NAME, NULL AS REF_GENERATION FROM (")
                .append("SELECT 'sqlite_schema' AS NAME, 'SYSTEM TABLE' AS TYPE")
                .append(" UNION ALL SELECT NAME, UPPER(TYPE) AS TYPE FROM sqlite_schema")
                .append(" WHERE NAME NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND UPPER(TYPE) IN ('TABLE', 'VIEW')")
                .append(" UNION ALL SELECT NAME, 'GLOBAL TEMPORARY' AS TYPE FROM sqlite_temp_master")
                .append(" UNION ALL SELECT NAME, 'SYSTEM TABLE' AS TYPE FROM sqlite_schema")
                .append(" WHERE NAME LIKE 'sqlite\\_%' ESCAPE '\\'")
                .append(") WHERE TABLE_NAME LIKE '")
                .append(literal(tableNamePattern))
                .append("' ESCAPE '\\'");
        if (types != null && types.length != 0) {
            sql.append(" AND TABLE_TYPE IN (");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    sql.append(",");
                }
                sql.append("'").append(literal(types[i].toUpperCase())).append("'");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY TABLE_TYPE, TABLE_NAME");
        return sql.toString();
    }

    static String columns(String tableNamePattern, String columnNamePattern) {
        return "WITH t(TABLE_NAME, CREATE_SQL, TABLE_RANK) AS ("
                + " SELECT 'sqlite_schema' AS TABLE_NAME, NULL AS CREATE_SQL, 1 AS TABLE_RANK"
                + " UNION ALL SELECT NAME, SQL, 1 FROM sqlite_schema"
                + " WHERE NAME LIKE 'sqlite\\_%' ESCAPE '\\'"
                + " UNION ALL SELECT NAME, SQL, 2 FROM sqlite_schema"
                + " WHERE NAME NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND UPPER(TYPE) = 'TABLE'"
                + " UNION ALL SELECT NAME, SQL, 3 FROM sqlite_schema"
                + " WHERE NAME NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND UPPER(TYPE) = 'VIEW'"
                + " UNION ALL SELECT NAME, NULL, 0 FROM sqlite_temp_master"
                + ") SELECT t.TABLE_NAME AS TABLE_NAME, t.CREATE_SQL AS CREATE_SQL, p.cid AS CID, "
                + "p.name AS COLUMN_NAME, p.type AS DECLARED_TYPE, p.\"notnull\" AS NOT_NULL, "
                + "p.dflt_value AS COLUMN_DEF, p.pk AS PK, p.hidden AS HIDDEN "
                + "FROM t JOIN pragma_table_xinfo(t.TABLE_NAME) p "
                + "WHERE t.TABLE_NAME LIKE '" + literal(tableNamePattern) + "' ESCAPE '\\'"
                + " AND p.name LIKE '" + literal(columnNamePattern) + "' ESCAPE '\\'"
                + " ORDER BY t.TABLE_RANK, t.TABLE_NAME, p.cid";
    }

    static String importedKeys(String table) {
        String escapedTable = literal(table == null ? "" : table);
        return "SELECT f.id AS FK_ID, f.seq AS KEY_SEQ, f.\"table\" AS PKTABLE_NAME, "
                + "f.\"from\" AS FKCOLUMN_NAME, f.\"to\" AS PKCOLUMN_NAME, "
                + "f.on_update AS UPDATE_RULE, f.on_delete AS DELETE_RULE, "
                + "c.sql AS FK_CREATE_SQL, p.sql AS PK_CREATE_SQL, "
                + "(SELECT pi.name FROM pragma_table_info(f.\"table\") pi WHERE pi.pk = 1) AS PK_FIRST_COLUMN "
                + "FROM pragma_foreign_key_list('" + escapedTable + "') f "
                + "LEFT JOIN sqlite_schema c ON lower(c.name) = lower('" + escapedTable + "') "
                + "LEFT JOIN sqlite_schema p ON lower(p.name) = lower(f.\"table\") "
                + "ORDER BY f.id, f.seq";
    }

    static String exportedKeys(String parentTable, String childTable) {
        String escapedParent = literal(parentTable == null ? "" : parentTable);
        StringBuilder sql = new StringBuilder()
                .append("SELECT f.id AS FK_ID, f.seq AS KEY_SEQ, ")
                .append("COALESCE(p.name, f.\"table\") AS PKTABLE_NAME, f.\"from\" AS FKCOLUMN_NAME, ")
                .append("COALESCE(f.\"to\", (SELECT pi.name FROM pragma_table_info(f.\"table\") pi WHERE pi.pk = 1)) AS PKCOLUMN_NAME, ")
                .append("f.on_update AS UPDATE_RULE, f.on_delete AS DELETE_RULE, ")
                .append("m.name AS FKTABLE_NAME, m.sql AS FK_CREATE_SQL, p.sql AS PK_CREATE_SQL, ")
                .append("(SELECT group_concat(pi.name, ',') FROM pragma_table_info(f.\"table\") pi WHERE pi.pk > 0) AS PK_COLUMNS ")
                .append("FROM sqlite_schema m JOIN pragma_foreign_key_list(m.name) f ")
                .append("LEFT JOIN sqlite_schema p ON lower(p.name) = lower(f.\"table\") ")
                .append("WHERE m.type = 'table' AND lower(f.\"table\") = lower('")
                .append(escapedParent)
                .append("')");
        if (childTable != null) {
            sql.append(" AND lower(m.name) = lower('").append(literal(childTable)).append("')");
        }
        return sql.append(" ORDER BY m.name, f.id, f.seq").toString();
    }

    static String primaryKeys(String table) {
        String escapedTable = literal(table == null ? "" : table);
        return "SELECT p.name AS COLUMN_NAME, p.pk AS KEY_SEQ, s.sql AS CREATE_SQL "
                + "FROM pragma_table_info('" + escapedTable + "') p "
                + "LEFT JOIN sqlite_schema s ON lower(s.name) = lower('" + escapedTable + "') "
                + "WHERE p.pk > 0";
    }

    static String tableTypes() {
        return "SELECT 'TABLE' AS TABLE_TYPE "
                + "UNION SELECT 'VIEW' AS TABLE_TYPE "
                + "UNION SELECT 'SYSTEM TABLE' AS TABLE_TYPE "
                + "UNION SELECT 'GLOBAL TEMPORARY' AS TABLE_TYPE";
    }

    static String schemas() {
        return "SELECT NULL AS TABLE_SCHEM, NULL AS TABLE_CATALOG WHERE 0";
    }

    static String catalogs() {
        return "SELECT NULL AS TABLE_CAT WHERE 0";
    }

    static String indexInfo(String table, boolean unique) {
        String escapedTable = literal(table == null ? "" : table);
        String sql = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, '" + escapedTable + "' AS TABLE_NAME, " +
                "CASE il.\"unique\" WHEN 1 THEN 0 ELSE 1 END AS NON_UNIQUE, " +
                "NULL AS INDEX_QUALIFIER, il.name AS INDEX_NAME, 3 AS TYPE, " +
                "ii.seqno + 1 AS ORDINAL_POSITION, ii.name AS COLUMN_NAME, " +
                "'A' AS ASC_OR_DESC, 0 AS CARDINALITY, 0 AS PAGES, NULL AS FILTER_CONDITION " +
                "FROM pragma_index_list('" + escapedTable + "') il, pragma_index_info(il.name) ii";
        if (unique) {
            sql += " WHERE il.\"unique\" = 1";
        }
        sql += " ORDER BY NON_UNIQUE, TYPE, INDEX_NAME, ORDINAL_POSITION";
        return sql;
    }
}
