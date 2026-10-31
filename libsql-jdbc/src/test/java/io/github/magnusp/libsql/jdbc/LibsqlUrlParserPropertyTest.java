package io.github.magnusp.libsql.jdbc;

import java.sql.SQLException;
import java.util.Properties;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties for URL parsing.
 *
 * <p>{@link LibsqlUrlParser#parse} is a JDBC boundary: any input the driver accepts must produce a
 * config or a {@link SQLException}. An unchecked exception escaping here is a driver bug, because
 * the caller cannot distinguish it from a programming error.
 */
class LibsqlUrlParserPropertyTest {

    private static final int ITERATIONS = 500;

    @Test
    void parseNeverThrowsAnUnexpectedException() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(timeoutUrls(), url -> {
                    try {
                        LibsqlUrlParser.parse(url, new Properties());
                    } catch (SQLException expected) {
                        // A rejected timeout is a caller error, not a driver crash.
                    } catch (RuntimeException unexpected) {
                        throw new AssertionError("unexpected " + unexpected + " for " + url, unexpected);
                    }
                    return true;
                });
    }

    @Test
    void arbitraryUrlsNeverThrowAnUnexpectedException() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(arbitraryUrls().map(body -> body.startsWith("jdbc:libsql:")
                        ? body
                        : "jdbc:libsql:" + body), url -> {
                    try {
                        LibsqlUrlParser.parse(url, new Properties());
                    } catch (SQLException expected) {
                        // A malformed URL is a caller error, not a driver crash.
                    } catch (RuntimeException unexpected) {
                        throw new AssertionError("unexpected " + unexpected + " for " + url, unexpected);
                    }
                    return true;
                });
    }

    @Test
    void parseNeverThrowsAnUnexpectedExceptionFromProperties() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(timeoutValues(), value -> {
                    Properties properties = new Properties();
                    properties.setProperty("connectTimeout", value);
                    try {
                        LibsqlUrlParser.parse("jdbc:libsql:http://localhost:8080", properties);
                    } catch (SQLException expected) {
                        // A rejected timeout is a caller error, not a driver crash.
                    } catch (RuntimeException unexpected) {
                        throw new AssertionError("unexpected " + unexpected + " for " + value, unexpected);
                    }
                    return true;
                });
    }

    @Test
    void metadataUrlIsIdempotent() {
        PropertyChecker.customized()
                .withIterationCount(ITERATIONS)
                .forAll(arbitraryUrls(), url -> {
                    String once = LibsqlUrlParser.metadataUrl(url);
                    return once.equals(LibsqlUrlParser.metadataUrl(once));
                });
    }

    private static Generator<String> timeoutUrls() {
        return timeoutValues().map(value -> "jdbc:libsql:http://localhost:8080/dev/tenant?connectTimeout=" + value);
    }

    private static Generator<String> timeoutValues() {
        return Generator.anyOf(
                Generator.stringsOf(IntDistribution.uniform(0, 20), Generator.digits()),
                Generator.stringsOf(IntDistribution.uniform(0, 16), Generator.charsInRange((char) 0, (char) 127)),
                Generator.constant(""),
                Generator.constant("-1"),
                Generator.constant("+5"),
                Generator.constant("999999999999999999999999"));
    }

    private static Generator<String> arbitraryUrls() {
        Generator<String> fragments = Generator.anyOf(
                Generator.constant("jdbc:libsql:"),
                Generator.constant("http://"),
                Generator.constant("localhost:8080"),
                Generator.constant("/dev/tenant"),
                Generator.constant("?"),
                Generator.constant("&"),
                Generator.constant("="),
                Generator.constant("authToken"),
                Generator.constant("token"),
                Generator.constant("jwt"),
                Generator.constant("connectTimeout"),
                Generator.constant("user:pass@"),
                Generator.stringsOf(IntDistribution.uniform(0, 8), Generator.charsInRange((char) 0, (char) 127)));
        return Generator.listsOf(IntDistribution.uniform(0, 10), fragments)
                .map(parts -> String.join("", parts));
    }
}
