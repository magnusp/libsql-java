package io.github.magnusp.libsql.jdbc;

import io.github.magnusp.libsql.hrana.codec.Col;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties for metadata text that originates in the database rather than in the driver.
 *
 * <p>SQLite lets a schema declare almost any type name. The generated domain is syntactically
 * valid SQLite type text: an arbitrary string with no parentheses, or a parenthesized precision.
 * A malformed precision such as {@code DECIMAL(abc)} is deliberately excluded: {@link
 * LibsqlResultSetMetaDataTest} currently pins a {@link NumberFormatException} for that input.
 */
class SqlMetadataParsingPropertyTest {

    private static final int ITERATIONS = 500;

    @Test
    void declaredTypeNeverThrowsOnValidDeclaredText() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(declaredTypes(), declType -> {
                    DeclaredType type = DeclaredType.of(new Col("c", declType));
                    assertThat(type.declaredTypeName()).isNotNull();
                    assertThat(type.typeName()).isNotNull();
                    assertThat(type.precision()).isNotNegative();
                    assertThat(type.scale()).isNotNegative();
                    return true;
                });
    }

    @Test
    void autoIncrementDetectionNeverThrowsOnArbitraryCreateStatements() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(Generator.zipWith(arbitraryText(), arbitraryText(), Pair::new), pair -> {
                    SqlAutoIncrement.isAutoIncrementColumn(pair.createSql(), pair.columnName());
                    return true;
                });
    }

    private record Pair(String createSql, String columnName) {}

    private static Generator<String> declaredTypes() {
        return Generator.anyOf(
                Generator.stringsOf(
                                IntDistribution.uniform(0, 24), Generator.charsInRange((char) 0x20, (char) 0x7E))
                        .map(text -> text.replace('(', '_').replace(')', '_')),
                Generator.constant("INTEGER"),
                Generator.constant("DECIMAL(10,2)"),
                Generator.constant("VARCHAR(255)"),
                Generator.constant("BIG INT"),
                Generator.constant("  NUMERIC  "),
                Generator.zipWith(
                        Generator.integers(0, 1_000_000),
                        Generator.integers(0, 1_000_000),
                        (precision, scale) -> "DECIMAL(" + precision + "," + scale + ")"));
    }

    private static Generator<String> arbitraryText() {
        return Generator.stringsOf(
                IntDistribution.uniform(0, 80), Generator.charsInRange((char) 0, Character.MAX_VALUE));
    }
}
