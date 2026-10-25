package io.github.magnusp.libsql.conformance;

import java.util.List;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Quarantines the xerial tests whose expectations Appendix B replaces.
 *
 * <p>These tests assert sqlite-jdbc behavior that JDBC 4.3 Appendix B defines differently. They
 * stay in the vendored tree and still run in the {@code xerial-control} profile, where the
 * reference driver is the subject. They are excluded only from the libSQL run, and the reason is
 * kept here so the exclusion cannot become an unexplained skip.
 */
public final class XerialQuarantine implements PostDiscoveryFilter {

    /** One upstream test method whose xerial expectation conflicts with Appendix B. */
    record Quarantined(String className, String methodName, String reason) {}

    static final List<Quarantined> QUARANTINED =
            List.of(
                    new Quarantined(
                            "org.sqlite.RSMetaDataTest",
                            "columnTypes",
                            "xerial reports a declared NCHAR as Types.CHAR; Appendix B defines"
                                    + " NCHAR as its own JDBC type, so the driver reports Types.NCHAR"),
                    new Quarantined(
                            "org.sqlite.RSMetaDataTest",
                            "testGetColumnClassName",
                            "xerial reports a declared BLOB as java.lang.Object; Table B-3 maps"
                                    + " BLOB to java.sql.Blob"),
                    new Quarantined(
                            "org.sqlite.PrepStmtTest",
                            "getObject",
                            "xerial returns an Integer for getObject on a declared BIT; Table B-3"
                                    + " maps BIT to Boolean"));

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (XerialControl.isEnabled()
                || !(descriptor.getSource().orElse(null) instanceof MethodSource source)) {
            return FilterResult.included("Appendix B quarantine is inactive for this run");
        }
        for (Quarantined quarantined : QUARANTINED) {
            if (quarantined.className().equals(source.getClassName())
                    && quarantined.methodName().equals(source.getMethodName())) {
                return FilterResult.excluded(quarantined.reason());
            }
        }
        return FilterResult.included("not quarantined");
    }
}
