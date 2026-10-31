package io.github.magnusp.libsql.jdbc;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties for the pure SQLite-to-Java text conversions.
 *
 * <p>The round trips encode the contract the classes were extracted for: {@code format(x)} must
 * produce text that SQLite and sqlite-jdbc interpret as {@code x} again.
 */
class SqliteValueTextPropertyTest {

    private static final int ITERATIONS = 1_000;
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    @Test
    void realTextAlwaysParsesBackToTheSameDouble() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(finiteDoubles(), value ->
                        Double.parseDouble(SqliteRealText.format(value)) == value);
    }

    @Test
    void realTextKeepsTheSqliteSpellingsOfSpecialValues() {
        assertThat(SqliteRealText.format(Double.NaN)).isEqualTo("NaN");
        assertThat(SqliteRealText.format(Double.POSITIVE_INFINITY)).isEqualTo("Inf");
        assertThat(SqliteRealText.format(Double.NEGATIVE_INFINITY)).isEqualTo("-Inf");
    }

    @Test
    void longTextAlwaysParsesBackToTheSameLong() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(longs(), value -> SqliteNumericText.parseLong(Long.toString(value)) == value);
    }

    @Test
    void longParsingIgnoresSurroundingSqliteWhitespace() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(Generator.zipWith(longs(), sqliteWhitespace(), Pair::new), pair ->
                        SqliteNumericText.parseLong(pair.whitespace() + pair.value() + pair.whitespace())
                                == pair.value());
    }

    @Test
    void doubleTextAlwaysParsesBackToTheSameDouble() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(finiteDoubles(), value ->
                        SqliteNumericText.parseDouble(Double.toString(value)) == value);
    }

    @Test
    void dateTimeTextRoundTripsThroughItsIsoRepresentation() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(dateTimes(), value -> {
                    assertThat(parseDateTime(value.toString())).isEqualTo(value);
                    return true;
                });
    }

    private record Pair(long value, String whitespace) {}

    /** jetCheck has no built-in 64-bit generator, so compose one from two 32-bit draws. */
    private static Generator<Long> longs() {
        return Generator.zipWith(
                Generator.integers(),
                Generator.integers(),
                (high, low) -> ((long) high << 32) | (low & 0xffffffffL));
    }

    private static Generator<Double> finiteDoubles() {
        return Generator.doubles().suchThat(Double::isFinite);
    }

    private static Generator<LocalDateTime> dateTimes() {
        int firstDay = (int) LocalDate.of(1, 1, 1).toEpochDay();
        int lastDay = (int) LocalDate.of(9999, 12, 31).toEpochDay();
        return Generator.zipWith(
                Generator.integers(firstDay, lastDay),
                Generator.integers(0, 86_399_999),
                (day, millisOfDay) -> LocalDate.ofEpochDay(day)
                        .atTime(LocalTime.ofNanoOfDay(millisOfDay * 1_000_000L)));
    }

    private static Generator<String> sqliteWhitespace() {
        return Generator.listsOf(
                        IntDistribution.uniform(0, 4),
                        Generator.sampledFrom(" ", "\t", "\n", "\r", "\u000b", "\f"))
                .map(parts -> String.join("", parts));
    }

    private static LocalDateTime parseDateTime(String text) {
        try {
            return SqliteDateTimeValues.parseSqliteDateTime(text, "TIMESTAMP", UTC);
        } catch (SQLException e) {
            throw new AssertionError("could not parse " + text, e);
        }
    }
}
