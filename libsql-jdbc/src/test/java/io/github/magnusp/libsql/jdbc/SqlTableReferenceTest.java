package io.github.magnusp.libsql.jdbc;

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
            new SqlTableReference.TableDefinition("CREATE TABLE [my table] (c TEXT)", List.of("c")),
            "a",
            new SqlTableReference.TableDefinition(
                    "CREATE TABLE a (id INTEGER NOT NULL, x TEXT)", List.of("id", "x")),
            "b",
            new SqlTableReference.TableDefinition("CREATE TABLE b (id INTEGER, y TEXT)", List.of("id", "y")),
            "c",
            new SqlTableReference.TableDefinition("CREATE TABLE c (z TEXT)", List.of("z")),
            "g",
            new SqlTableReference.TableDefinition(
                    "CREATE TABLE g (p INTEGER, q AS (p+1), r TEXT NOT NULL)", List.of("p", "q", "r")),
            "anv",
            new SqlTableReference.TableDefinition("CREATE TABLE anv (id INTEGER)", List.of("id")),
            "användare",
            new SqlTableReference.TableDefinition(
                    "CREATE TABLE användare (id INTEGER, namn_ö TEXT)", List.of("id", "namn_ö")));

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
    void resolvesNonAsciiTableAndColumn() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT namn_ö FROM användare");

        assertThat(resolution.labels()).containsExactly("namn_ö");
        assertThat(resolution.tables())
                .containsExactly(new SqlTableReference.TableRef(null, "användare"));
    }

    @Test
    void resolvesNonAsciiColumnThroughTableAlias() throws SQLException {
        assertThat(resolve("SELECT a.namn_ö FROM användare a").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "användare"));
        assertThat(resolve("SELECT * FROM användare a").tables())
                .containsExactly(
                        new SqlTableReference.TableRef(null, "användare"),
                        new SqlTableReference.TableRef(null, "användare"));
    }

    @Test
    void resolvesNonAsciiAliases() throws SQLException {
        assertThat(resolve("SELECT namn_ö AS namn FROM användare").labels()).containsExactly("namn");
        assertThat(resolve("SELECT namn_ö namn FROM användare").labels()).containsExactly("namn");
        assertThat(resolve("SELECT namn_ö AS namn FROM användare").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "användare"));
        assertThat(resolve("SELECT å.namn_ö FROM användare AS å").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "användare"));
    }

    @Test
    void resolvesQuotedNonAsciiNames() throws SQLException {
        assertThat(resolve("SELECT namn_ö FROM \"användare\"").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "användare"));
        assertThat(resolve("SELECT namn_ö FROM [användare]").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "användare"));
        assertThat(resolve("SELECT \"namn_ö\" FROM \"användare\"").labels()).containsExactly("namn_ö");
    }

    @Test
    void keepsNonAsciiTableWholeWhenAnAsciiPrefixTableExists() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT * FROM användare");

        assertThat(resolution.labels()).containsExactly("id", "namn_ö");
        assertThat(resolution.tables())
                .containsExactly(
                        new SqlTableReference.TableRef(null, "användare"),
                        new SqlTableReference.TableRef(null, "användare"));
        assertThat(resolve("SELECT id FROM anv").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "anv"));
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
    void dropsTheDuplicateUsingColumnWhenExpandingStar() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT * FROM a JOIN b USING(id) JOIN c", 4);

        assertThat(resolution.labels()).containsExactly("id", "x", "y", "z");
        assertThat(resolution.tables())
                .containsExactly(
                        new SqlTableReference.TableRef(null, "a"),
                        new SqlTableReference.TableRef(null, "a"),
                        new SqlTableReference.TableRef(null, "b"),
                        new SqlTableReference.TableRef(null, "c"));
    }

    @Test
    void dropsTheDuplicateNaturalColumnWhenExpandingStar() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT * FROM a NATURAL JOIN b", 3);

        assertThat(resolution.labels()).containsExactly("id", "x", "y");
        assertThat(resolution.tables())
                .containsExactly(
                        new SqlTableReference.TableRef(null, "a"),
                        new SqlTableReference.TableRef(null, "a"),
                        new SqlTableReference.TableRef(null, "b"));
    }

    @Test
    void expandsGeneratedColumnsInStar() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT * FROM g JOIN c ON 1", 4);

        assertThat(resolution.labels()).containsExactly("p", "q", "r", "z");
        assertThat(resolution.tables())
                .containsExactly(
                        new SqlTableReference.TableRef(null, "g"),
                        new SqlTableReference.TableRef(null, "g"),
                        new SqlTableReference.TableRef(null, "g"),
                        new SqlTableReference.TableRef(null, "c"));
    }

    @Test
    void discardsTheResolutionWhenTheStarWidthDiffers() throws SQLException {
        assertThat(resolve("SELECT * FROM (VALUES(1)) v, c", 2)).isNull();
        assertThat(resolve("SELECT * FROM unknown_source, c", 2)).isNull();
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
    void resolvesViewsToBaseTables() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("SELECT a, b FROM v");

        assertThat(resolution.labels()).containsExactly("a", "b");
        assertThat(resolution.tables())
                .containsExactly(new SqlTableReference.TableRef(null, "w"), new SqlTableReference.TableRef(null, "w"));
        assertThat(resolve("SELECT b AS bb FROM v").tables())
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
    void emitsLiteralTokensForStringAndBlobLiterals() throws SQLException {
        SqlTableReference.Resolution aliased = resolve("SELECT 'lit' z FROM c");

        assertThat(aliased.labels()).containsExactly("z");
        assertThat(aliased.tables()).containsExactly((SqlTableReference.TableRef) null);
        assertThat(resolve("SELECT x'41' AS b FROM a").tables())
                .containsExactly((SqlTableReference.TableRef) null);

        SqlTableReference.Resolution compared = resolve("SELECT x LIKE 'a%' FROM a");
        assertThat(compared.tables()).containsExactly((SqlTableReference.TableRef) null);
        assertThat(compared.labels()).doesNotContain("LIKE", "a%");
    }

    @Test
    void rejectsOperatorKeywordsAsImplicitAliases() throws SQLException {
        SqlTableReference.Resolution isnull = resolve("SELECT x ISNULL FROM a");
        assertThat(isnull.labels()).doesNotContain("ISNULL");
        assertThat(isnull.tables()).containsExactly((SqlTableReference.TableRef) null);

        SqlTableReference.Resolution notnull = resolve("SELECT x NOTNULL FROM a");
        assertThat(notnull.labels()).doesNotContain("NOTNULL");
        assertThat(notnull.tables()).containsExactly((SqlTableReference.TableRef) null);

        SqlTableReference.Resolution caseExpression = resolve("SELECT CASE WHEN x = 1 THEN id ELSE 2 END FROM a");
        assertThat(caseExpression.labels()).doesNotContain("END");

        SqlTableReference.Resolution counted = resolve("SELECT count(*) c FROM a");
        assertThat(counted.labels()).containsExactly("c");
        assertThat(counted.tables()).containsExactly((SqlTableReference.TableRef) null);
    }

    @Test
    void keepsTheJoinThatFollowsNotIndexed() throws SQLException {
        SqlTableReference.Resolution resolution =
                resolve("SELECT b.y FROM a NOT INDEXED JOIN b ON a.id = b.id");

        assertThat(resolution.labels()).containsExactly("y");
        assertThat(resolution.tables()).containsExactly(new SqlTableReference.TableRef(null, "b"));
    }

    @Test
    void doesNotReadLeftOrRightCallsAsJoinStarts() throws SQLException {
        assertThat(resolve("SELECT c.z FROM a JOIN b ON left(a.x, 2) = b.y, c").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "c"));
        assertThat(resolve("SELECT c.z FROM a JOIN b ON right(a.x, 2) = b.y, c").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "c"));
    }

    @Test
    void registersUnresolvedCommonTableExpressionsAsOpaque() throws SQLException {
        assertThat(resolve("WITH c AS (VALUES (1)) SELECT c.z FROM c").tables())
                .containsExactly((SqlTableReference.TableRef) null);
        assertThat(resolve("WITH c(z) AS (VALUES (1)) SELECT z FROM c").tables())
                .containsExactly((SqlTableReference.TableRef) null);
    }

    @Test
    void resolvesUnqualifiedUsingColumnToTheLeftTable() throws SQLException {
        assertThat(resolve("SELECT id FROM a JOIN b USING(id)").tables())
                .containsExactly(new SqlTableReference.TableRef(null, "a"));
    }

    @Test
    void resolvesReturningColumnsThroughTheTargetAlias() throws SQLException {
        SqlTableReference.Resolution resolution =
                resolve("INSERT INTO a AS t (id, x) VALUES (1, 'v') RETURNING t.id, t.x");

        assertThat(resolution.tables())
                .containsExactly(
                        new SqlTableReference.TableRef(null, "a"),
                        new SqlTableReference.TableRef(null, "a"));
    }

    @Test
    void resolvesReturningThroughTheTableNameWhenTheTargetHasNoAlias() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("DELETE FROM w RETURNING w.a");

        assertThat(resolution.labels()).containsExactly("a");
        assertThat(resolution.tables()).containsExactly(new SqlTableReference.TableRef(null, "w"));
        // The table reference is the nullability path: w.a maps back to the NOT NULL column of w.
        assertThat(resolution.table(0)).isEqualTo(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void doesNotBindAnImplicitUpdateAliasToTheReturningSource() throws SQLException {
        SqlTableReference.Resolution implicit = resolve("UPDATE w x RETURNING x.a");
        SqlTableReference.Resolution byTableName = resolve("UPDATE w x RETURNING w.a");

        assertThat(implicit.labels()).containsExactly("a");
        assertThat(implicit.tables()).containsExactly((SqlTableReference.TableRef) null);
        assertThat(implicit.table(0)).isNull();
        assertThat(byTableName.tables()).containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void doesNotBindAnImplicitInsertAliasToTheReturningSource() throws SQLException {
        SqlTableReference.Resolution implicit = resolve("INSERT INTO w t SELECT 1 RETURNING t.a");
        SqlTableReference.Resolution byTableName = resolve("INSERT INTO w t SELECT 1 RETURNING w.a");

        assertThat(implicit.labels()).containsExactly("a");
        assertThat(implicit.tables()).containsExactly((SqlTableReference.TableRef) null);
        assertThat(implicit.table(0)).isNull();
        assertThat(byTableName.tables()).containsExactly(new SqlTableReference.TableRef(null, "w"));
    }

    @Test
    void resolvesReturningThroughAnExplicitAsAlias() throws SQLException {
        SqlTableReference.Resolution resolution = resolve("UPDATE w AS x RETURNING x.a");

        assertThat(resolution.labels()).containsExactly("a");
        assertThat(resolution.tables()).containsExactly(new SqlTableReference.TableRef(null, "w"));
        assertThat(resolution.table(0)).isEqualTo(new SqlTableReference.TableRef(null, "w"));
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

    private static SqlTableReference.Resolution resolve(String sql, int columnCount) throws SQLException {
        SqlTableReference.Resolution resolution = SqlTableReference.resolve(sql, SCHEMA, columnCount);
        assertThat(resolution == null || resolution.labels().size() == columnCount)
                .as("resolved column count equals the result column count")
                .isTrue();
        return resolution;
    }
}
