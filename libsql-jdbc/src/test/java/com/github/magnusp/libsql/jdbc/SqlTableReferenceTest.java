package com.github.magnusp.libsql.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTableReferenceTest {

    @Test
    void parsesSimpleTable() {
        assertThat(SqlTableReference.parse("SELECT * FROM people"))
                .isEqualTo(new SqlTableReference.TableRef(null, "people"));
    }

    @Test
    void parsesSchemaQualifiedTable() {
        assertThat(SqlTableReference.parse("SELECT * FROM main.people"))
                .isEqualTo(new SqlTableReference.TableRef("main", "people"));
    }

    @Test
    void parsesTempSchema() {
        assertThat(SqlTableReference.parse("SELECT * FROM temp.people"))
                .isEqualTo(new SqlTableReference.TableRef("temp", "people"));
    }

    @Test
    void parsesQuotedNamesWithSpaces() {
        assertThat(SqlTableReference.parse("SELECT * FROM \"my schema\".\"my table\""))
                .isEqualTo(new SqlTableReference.TableRef("my schema", "my table"));
    }

    @Test
    void parsesQuotedIdentifierKinds() {
        assertThat(SqlTableReference.parse("SELECT * FROM `my table`"))
                .isEqualTo(new SqlTableReference.TableRef(null, "my table"));
        assertThat(SqlTableReference.parse("SELECT * FROM [my table]"))
                .isEqualTo(new SqlTableReference.TableRef(null, "my table"));
        assertThat(SqlTableReference.parse("SELECT * FROM \"my \"\"table\"\"\""))
                .isEqualTo(new SqlTableReference.TableRef(null, "my \"table\""));
    }

    @Test
    void parsesAliasesWithAndWithoutAs() {
        assertThat(SqlTableReference.parse("SELECT * FROM people p WHERE p.id = 1"))
                .isEqualTo(new SqlTableReference.TableRef(null, "people"));
        assertThat(SqlTableReference.parse("SELECT * FROM people AS p"))
                .isEqualTo(new SqlTableReference.TableRef(null, "people"));
    }

    @Test
    void ignoresStringLiteralsContainingFrom() {
        assertThat(SqlTableReference.parse("SELECT 'from elsewhere' AS s FROM people WHERE name = 'from x'"))
                .isEqualTo(new SqlTableReference.TableRef(null, "people"));
    }

    @Test
    void ignoresCommentsContainingFrom() {
        assertThat(SqlTableReference.parse(
                        "SELECT * -- from noise\nFROM /* from noise */ people -- trailing from noise"))
                .isEqualTo(new SqlTableReference.TableRef(null, "people"));
    }

    @Test
    void ignoresFromInNestedSubquery() {
        assertThat(SqlTableReference.parse("SELECT * FROM people WHERE id IN (SELECT id FROM other)"))
                .isEqualTo(new SqlTableReference.TableRef(null, "people"));
    }

    @Test
    void rejectsJoin() {
        assertThat(SqlTableReference.parse("SELECT * FROM a JOIN b ON a.id = b.id")).isNull();
    }

    @Test
    void rejectsCommaSeparatedTables() {
        assertThat(SqlTableReference.parse("SELECT * FROM a, b")).isNull();
    }

    @Test
    void rejectsCommonTableExpression() {
        assertThat(SqlTableReference.parse("WITH cte AS (SELECT * FROM a) SELECT * FROM cte")).isNull();
    }

    @Test
    void rejectsCompoundSelect() {
        assertThat(SqlTableReference.parse("SELECT * FROM a UNION SELECT * FROM b")).isNull();
        assertThat(SqlTableReference.parse("SELECT * FROM a INTERSECT SELECT * FROM b")).isNull();
        assertThat(SqlTableReference.parse("SELECT * FROM a EXCEPT SELECT * FROM b")).isNull();
    }

    @Test
    void rejectsSubqueryInFromPosition() {
        assertThat(SqlTableReference.parse("SELECT * FROM (SELECT * FROM a) AS x")).isNull();
    }

    @Test
    void rejectsSubqueryInSelectList() {
        assertThat(SqlTableReference.parse("SELECT (SELECT id FROM other) AS id FROM people"))
                .isNull();
    }

    @Test
    void rejectsSecondTableReferenceOrSecondFrom() {
        assertThat(SqlTableReference.parse("SELECT * FROM a b c")).isNull();
        assertThat(SqlTableReference.parse("SELECT * FROM a FROM b")).isNull();
        assertThat(SqlTableReference.parse("SELECT * FROM a AS x, b")).isNull();
    }

    @Test
    void rejectsAmbiguousInput() {
        assertThat(SqlTableReference.parse(null)).isNull();
        assertThat(SqlTableReference.parse("SELECT 1")).isNull();
        assertThat(SqlTableReference.parse("SELECT * FROM 'people'")).isNull();
        assertThat(SqlTableReference.parse("SELECT * FROM a.")).isNull();
        assertThat(SqlTableReference.parse("SELECT * FROM a.b.c")).isNull();
        assertThat(SqlTableReference.parse("SELECT * FROM a WHERE id IN (1, 2")).isNull();
    }

    @Test
    void acceptsTrailingSemicolon() {
        assertThat(SqlTableReference.parse("SELECT * FROM people;"))
                .isEqualTo(new SqlTableReference.TableRef(null, "people"));
    }
}
