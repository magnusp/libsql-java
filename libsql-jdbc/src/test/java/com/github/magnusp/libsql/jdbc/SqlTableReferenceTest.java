package com.github.magnusp.libsql.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTableReferenceTest {

    private static final Map<String, SqlTableReference.TableDefinition> DEFINITIONS = Map.of(
            "w",
            new SqlTableReference.TableDefinition("CREATE TABLE w (a INTEGER NOT NULL, b TEXT)", List.of("a", "b")),
            "z",
            new SqlTableReference.TableDefinition("CREATE TABLE z (a INTEGER)", List.of("a")),
            "v",
            new SqlTableReference.TableDefinition("CREATE VIEW v AS SELECT a, b FROM w", List.of("a", "b")),
            "my table",
            new SqlTableReference.TableDefinition("CREATE TABLE [my table] (c TEXT)", List.of("c")));

    private static final SqlTableReference.Schema SCHEMA = reference -> {
        if (reference.table() == null) {
            return null;
        }
        return DEFINITIONS.get(reference.table().toLowerCase(Locale.ROOT));
    };

    @Test
    void resolvesSimpleTable() throws SQLException {
        assertThat(resolve("SELECT * FROM w").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"), new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesSchemaQualifiedTable() throws SQLException {
        assertThat(resolve("SELECT a FROM main.w").tables())
                .containsExactly(new SqlTableReference.TableRef("main", "w"));
    }

    @Test
    void resolvesQuotedAndBracketedNames() throws SQLException {
        assertThat(resolve("SELECT c FROM \"my table\"").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "my table"));
        assertThat(resolve("SELECT c FROM [my table]").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "my table"));
    }

    @Test
    void resolvesAliasesWithAndWithoutAs() throws SQLException {
        assertThat(resolve("SELECT a AS z FROM w").labels()).containsExactly("z");
        assertThat(resolve("SELECT a AS z FROM w").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"));
        assertThat(resolve("SELECT a z FROM w").labels()).containsExactly("z");
        assertThat(resolve("SELECT a z FROM w").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void ignoresStringLiteralsAndCommentsContainingFrom() throws SQLException {
        assertThat(resolve("SELECT 'from elsewhere' AS s FROM w WHERE b = 'from x'").tables())
                .containsExactly((SqlTableReference.TableRef) null);
        assertThat(resolve("SELECT a -- from noise\nFROM /* from noise */ w -- trailing from noise").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesJoins() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT w.a, z.a FROM w JOIN z ON w.a = z.a");

        assertThat(resolution.labels()).containsExactly("a", "a");
        assertThat(resolution.tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"), new SqlTableReference.TableRef(null, "z"));
    }

    @Test
    void resolvesCommaAndOuterJoins() throws SQLException {
        assertThat(resolve("SELECT w.a, z.a FROM w, z").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"), new SqlTableReference.TableRef(null, "z"));
        assertThat(resolve("SELECT x.a, y.a FROM w x LEFT OUTER JOIN z y ON x.a = y.a").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"), new SqlTableReference.TableRef(null, "z"));
    }

    @Test
    void resolvesStarExpansion() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT * FROM w JOIN z ON w.a = z.a");

        assertThat(resolution.labels()).containsExactly("a", "b", "a");
        assertThat(resolution.tables())
                .containsExactly(
                        new SqlTableReference.TableRef(null, "w"),
                        new SqlTableReference.TableRef(null, "w"),
                        new SqlTableReference.TableRef(null, "z"));
        assertThat(resolve("SELECT w.* FROM w JOIN z ON w.a = z.a").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"), new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesSubqueryInFromClause() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT q.a FROM (SELECT a FROM w) q");

        assertThat(resolution.labels()).containsExactly("a");
        assertThat(resolution.tables()).containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesScalarSubquery() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT (SELECT a FROM w) AS s");

        assertThat(resolution.labels()).containsExactly("s");
        assertThat(resolution.tables()).containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesCommonTableExpression() throws SQLException {
        SqlTableReference.Resolution resolution =
                resolve("WITH cte AS (SELECT a FROM w) SELECT cte.a FROM cte");

        assertThat(resolution.labels()).containsExactly("a");
        assertThat(resolution.tables()).containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesLeftmostCompoundSelectBranch() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT a FROM w UNION SELECT a FROM z");

        assertThat(resolution.labels()).containsExactly("a");
        assertThat(resolution.tables()).containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesInsertReturning() throws SQLException {
        SqlTableReference.Resolution resolution =
                resolve("INSERT INTO w(a, b) VALUES (1, 'x') RETURNING a AS aa, b");

        assertThat(resolution.labels()).containsExactly("aa", "b");
        assertThat(resolution.tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"), new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesUpdateAndDeleteReturning() throws SQLException {
        assertThat(resolve("UPDATE w SET b = 'x' RETURNING a").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"));
        assertThat(resolve("DELETE FROM w RETURNING a").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void leavesExpressionsAndLiteralsUnresolved() throws SQLException {
        SqlTableReference.Resolution expression = resolve("SELECT a + 1 FROM w");
        SqlTableReference.Resolution literal = resolve("SELECT 1 AS one");

        assertThat(expression.labels()).containsExactly("a+1");
        assertThat(expression.tables()).containsExactly((SqlTableReference.TableRef) null);
        assertThat(literal.labels()).containsExactly("one");
        assertThat(literal.tables()).containsExactly((SqlTableReference.TableRef) null);
    }

    @Test
    void acceptsTrailingSemicolon() throws SQLException {
        assertThat(resolve("SELECT a FROM w;").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void rejectsUnparseableInput() throws SQLException {
        assertThat(SqlTableReference.resolve(null, SCHEMA)).isNull();
        assertThat(SqlTableReference.resolve("", SCHEMA)).isNull();
        assertThat(SqlTableReference.resolve("SELECT * FROM 'w'", SCHEMA)).isNull();
        assertThat(SqlTableReference.resolve("SELECT * FROM w WHERE id IN (1, 2", SCHEMA)).isNull();
    }

    private static SqlTableReference.Resolution resolve(String sql) throws SQLException {
        return SqlTableReference.resolve(sql, SCHEMA);
    }
}
