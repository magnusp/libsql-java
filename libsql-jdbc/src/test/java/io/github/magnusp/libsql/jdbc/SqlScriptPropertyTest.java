package io.github.magnusp.libsql.jdbc;

import java.util.List;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties for the statement splitter and the local tokenizer.
 *
 * <p>The generated scripts contain the cases the hand-written tests enumerate: semicolons in
 * quoted text, comments and trigger bodies. The point is coverage of combinations, not a
 * replacement for the named examples in {@link SqlScriptTest}.
 */
class SqlScriptPropertyTest {

    private static final int ITERATIONS = 500;

    @Test
    void splittingReproducesEveryGeneratedStatement() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(scripts(), script -> {
                    assertThat(SqlScript.split(script.sql()))
                            .as("split of %s", script.sql())
                            .containsExactlyElementsOf(script.expected());
                    return true;
                });
    }

    @Test
    void splittingNeverThrowsOnArbitraryText() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(arbitraryText(), sql -> SqlScript.split(sql) != null);
    }

    @Test
    void splittingNeverReturnsABlankStatement() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(arbitraryText(), sql -> {
                    assertThat(SqlScript.split(sql)).allSatisfy(statement -> assertThat(statement)
                            .isNotBlank());
                    return true;
                });
    }

    @Test
    void everySplitStatementSplitsToItself() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(arbitraryText(), sql -> {
                    for (String statement : SqlScript.split(sql)) {
                        assertThat(SqlScript.split(statement))
                                .as("second split of %s", statement)
                                .containsExactly(statement);
                    }
                    return true;
                });
    }

    @Test
    void placeholderCountIgnoresQuotedAndCommentedQuestionMarks() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(Generator.listsOf(IntDistribution.uniform(0, 12), tokenizerFragments()), fragments -> {
                    String sql = String.join(" ", fragments);
                    long expected = fragments.stream().filter("?"::equals).count();
                    assertThat(SqlTokenizer.placeholderCount(sql))
                            .as("placeholders in %s", sql)
                            .isEqualTo((int) expected);
                    return true;
                });
    }

    private record Script(String sql, List<String> expected) {}

    private static Generator<Script> scripts() {
        return Generator.listsOf(IntDistribution.uniform(0, 6), statements())
                .map(statements -> new Script(
                        String.join(";\n", statements) + (statements.isEmpty() ? "" : ";\n"),
                        statements.stream().map(String::strip).toList()));
    }

    private static Generator<String> statements() {
        return Generator.anyOf(
                Generator.constant("select 1"),
                Generator.constant("insert into t values (1)"),
                textLiteral(),
                quotedIdentifier(),
                bracketIdentifier(),
                blockComment(),
                lineComment(),
                triggerBody());
    }

    private static Generator<String> textLiteral() {
        return Generator.stringsOf(IntDistribution.uniform(0, 24), Generator.charsInRange((char) 0, (char) 127))
                .map(text -> "select '" + text.replace("'", "''") + "' from t");
    }

    private static Generator<String> quotedIdentifier() {
        return Generator.stringsOf(IntDistribution.uniform(0, 12), Generator.charsInRange((char) 0, (char) 127))
                .map(name -> "select \"" + name.replace("\"", "\"\"") + "\" from t");
    }

    private static Generator<String> bracketIdentifier() {
        return Generator.stringsOf(IntDistribution.uniform(0, 12), Generator.charsInRange((char) 0, (char) 127))
                .map(name -> "select [" + name.replace("]", "_") + "] from t");
    }

    private static Generator<String> blockComment() {
        return Generator.stringsOf(IntDistribution.uniform(0, 24), Generator.charsInRange((char) 0, (char) 127))
                .map(text -> "select 1 /* " + text.replace("*/", "* /") + " */");
    }

    private static Generator<String> lineComment() {
        // The newline belongs to the atom: a following terminator must not be commented out.
        return Generator.stringsOf(IntDistribution.uniform(0, 24), Generator.charsInRange((char) 0, (char) 127))
                .map(text -> "select 1 -- " + text.replace('\n', ' ').replace('\r', ' ') + "\n");
    }

    private static Generator<String> triggerBody() {
        return Generator.listsOf(
                        IntDistribution.uniform(1, 3),
                        Generator.anyOf(
                                Generator.constant("update t set n = n + 1"),
                                Generator.constant("insert into audit (value) values (new.value)"),
                                Generator.constant(
                                        "select case when new.value is null then raise(abort, 'value;') end")))
                .map(body -> "create trigger trg after insert on t begin " + String.join("; ", body) + "; end");
    }

    private static Generator<String> tokenizerFragments() {
        return Generator.anyOf(
                Generator.constant("?"),
                Generator.constant("'?'"),
                Generator.constant("\"?\""),
                Generator.constant("`?`"),
                Generator.constant("[?]"),
                Generator.constant("x'3f'"),
                Generator.constant("-- ?\n"),
                Generator.constant("/* ? */"),
                Generator.constant("value"),
                Generator.constant(","));
    }

    private static Generator<String> arbitraryText() {
        return Generator.stringsOf(
                IntDistribution.uniform(0, 120), Generator.charsInRange((char) 0, Character.MAX_VALUE));
    }
}
