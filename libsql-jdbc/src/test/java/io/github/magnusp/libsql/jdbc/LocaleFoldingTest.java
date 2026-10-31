package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import io.github.magnusp.libsql.hrana.codec.Row;
import io.github.magnusp.libsql.hrana.codec.StmtResult;
import io.github.magnusp.libsql.hrana.codec.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Pins that every case-folding call site uses {@link Locale#ROOT}, so no default locale can change
 * a metadata result. The corpus covers the four kinds of text that reach the call sites.
 */
class LocaleFoldingTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    private static final List<Col> COLUMN_COLUMNS = List.of(
            new Col("TABLE_CAT", "TEXT"),
            new Col("TABLE_SCHEM", "TEXT"),
            new Col("TABLE_NAME", "TEXT"),
            new Col("COLUMN_NAME", "TEXT"),
            new Col("DATA_TYPE", "INTEGER"),
            new Col("TYPE_NAME", "TEXT"),
            new Col("COLUMN_SIZE", "INTEGER"),
            new Col("BUFFER_LENGTH", "INTEGER"),
            new Col("DECIMAL_DIGITS", "INTEGER"),
            new Col("NUM_PREC_RADIX", "INTEGER"),
            new Col("NULLABLE", "INTEGER"),
            new Col("REMARKS", "TEXT"),
            new Col("COLUMN_DEF", "TEXT"),
            new Col("SQL_DATA_TYPE", "INTEGER"),
            new Col("SQL_DATETIME_SUB", "INTEGER"),
            new Col("CHAR_OCTET_LENGTH", "INTEGER"),
            new Col("ORDINAL_POSITION", "INTEGER"),
            new Col("IS_NULLABLE", "TEXT"),
            new Col("SCOPE_CATALOG", "TEXT"),
            new Col("SCOPE_SCHEMA", "TEXT"),
            new Col("SCOPE_TABLE", "TEXT"),
            new Col("SOURCE_DATA_TYPE", "INTEGER"),
            new Col("IS_AUTOINCREMENT", "TEXT"),
            new Col("IS_GENERATEDCOLUMN", "TEXT"));

    private static final List<Col> FOREIGN_KEY_COLUMNS = List.of(
            new Col("PKTABLE_CAT", "TEXT"),
            new Col("PKTABLE_SCHEM", "TEXT"),
            new Col("PKTABLE_NAME", "TEXT"),
            new Col("PKCOLUMN_NAME", "TEXT"),
            new Col("FKTABLE_CAT", "TEXT"),
            new Col("FKTABLE_SCHEM", "TEXT"),
            new Col("FKTABLE_NAME", "TEXT"),
            new Col("FKCOLUMN_NAME", "TEXT"),
            new Col("KEY_SEQ", "INTEGER"),
            new Col("UPDATE_RULE", "INTEGER"),
            new Col("DELETE_RULE", "INTEGER"),
            new Col("FK_NAME", "TEXT"),
            new Col("PK_NAME", "TEXT"),
            new Col("DEFERRABILITY", "INTEGER"));

    private Locale previousDefaultLocale;

    @BeforeEach
    void captureDefaultLocale() {
        previousDefaultLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(previousDefaultLocale);
    }

    static Stream<Arguments> corpus() {
        return Stream.of(
                        // Declared type names.
                        "INTEGER",
                        "VARCHAR(10)",
                        "numeric",
                        "TEXT",
                        "REAL",
                        "DECIMAL(10,2)",
                        "BOOL",
                        "CLOB",
                        "BLOB",
                        "DOUBLE",
                        // Foreign key rules.
                        "CASCADE",
                        "RESTRICT",
                        "SET NULL",
                        "SET DEFAULT",
                        "NO ACTION",
                        "cascade",
                        "set null",
                        // SQLite error codes and messages.
                        "SQLITE_CONSTRAINT",
                        "SQLITE_ERROR",
                        "constraint failed",
                        "UNIQUE constraint failed: t.id",
                        "database is locked",
                        "no such table: main.t",
                        // Identifiers, including the Turkish dotless and dotted i and the sharp s.
                        "People",
                        "id",
                        "İstanbul",
                        "ıi",
                        "straße",
                        "i",
                        "I",
                        "İ",
                        "ı",
                        "ß")
                .map(value -> arguments(value));
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void englishAndRootFoldIdentically(String value) {
        assertThat(value.toUpperCase(Locale.ENGLISH))
                .as("upper case of %s must not depend on ENGLISH versus ROOT", value)
                .isEqualTo(value.toUpperCase(Locale.ROOT));
        assertThat(value.toLowerCase(Locale.ENGLISH))
                .as("lower case of %s must not depend on ENGLISH versus ROOT", value)
                .isEqualTo(value.toLowerCase(Locale.ROOT));
    }

    @Test
    void columnMetadataFoldsUnderATurkishDefaultLocale() {
        StmtResult raw = new StmtResult(
                List.of(),
                List.of(new Row(List.of(
                        Value.of("t"),
                        Value.of("CREATE TABLE t (c numeric)"),
                        Value.of(0L),
                        Value.of("c"),
                        Value.of("numeric"),
                        Value.of(0L),
                        Value.ofNull(),
                        Value.of(0L),
                        Value.of(0L)))),
                0,
                null);

        Locale.setDefault(TURKISH);

        assertThat(typeName(ColumnMetadata.columns(raw, COLUMN_COLUMNS)))
                .as("columnShape must fold the declared type with Locale.ROOT, not the default locale")
                .isEqualTo("NUMERIC");
    }

    @Test
    void declaredTypeFoldsUnderATurkishDefaultLocale() {
        Locale.setDefault(TURKISH);

        assertThat(DeclaredType.of(new Col("c", "numeric")).typeName())
                .as("DeclaredType must fold with Locale.ROOT, not the default locale")
                .isEqualTo("NUMERIC");
    }

    @Test
    void foreignKeyRulesFoldUnderATurkishDefaultLocale() {
        StmtResult raw = new StmtResult(
                List.of(),
                List.of(new Row(List.of(
                        Value.of(0L), // fk id
                        Value.of(0L), // seq
                        Value.of("parent"),
                        Value.of("parent_id"),
                        Value.of("id"),
                        Value.of("cascade"), // update rule
                        Value.of("set null"), // delete rule
                        Value.of("CREATE TABLE child (parent_id INTEGER REFERENCES parent(id))"),
                        Value.of("CREATE TABLE parent (id INTEGER PRIMARY KEY)"),
                        Value.of("id")))),
                0,
                null);

        Locale.setDefault(TURKISH);
        Row imported = ForeignKeyMetadata.fromImported(raw, null, null, "child")
                .rows()
                .get(0);

        assertThat(integer(imported, FOREIGN_KEY_COLUMNS, "UPDATE_RULE"))
                .as("ForeignKeyMetadata must fold the rule with Locale.ROOT, not the default locale")
                .isEqualTo(DatabaseMetaData.importedKeyCascade);
        assertThat(integer(imported, FOREIGN_KEY_COLUMNS, "DELETE_RULE"))
                .as("ForeignKeyMetadata must fold the rule with Locale.ROOT, not the default locale")
                .isEqualTo(DatabaseMetaData.importedKeySetNull);
    }

    private static String typeName(StmtResult result) {
        Row row = result.rows().get(0);
        Value value = row.values().get(indexOf(COLUMN_COLUMNS, "TYPE_NAME"));
        return ((Value.Text) value).value();
    }

    private static long integer(Row row, List<Col> columns, String columnName) {
        Value value = row.values().get(indexOf(columns, columnName));
        return ((Value.Integer) value).value();
    }

    private static int indexOf(List<Col> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no such column: " + columnName);
    }
}
