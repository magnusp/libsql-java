package io.github.magnusp.libsql.conformance;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

/**
 * Selects the upstream xerial driver for the parity control run.
 *
 * <p>The control run compiles the same vendored test manifest as the libSQL run but executes it
 * against sqlite-jdbc 3.53.4.0. Both drivers are on the test class path, so the control run first
 * removes every driver visible to this class loader and then registers the pinned xerial driver
 * explicitly. That keeps {@code jdbc:sqlite:} URLs from reaching the libSQL driver.
 */
final class XerialControl {

    static final String ENABLED_PROPERTY = "conformance.control.driver";
    static final String XERIAL_DRIVER = "org.sqlite.JDBC";

    private static final String PROBE_URL = "jdbc:sqlite::memory:";

    private XerialControl() {}

    static boolean isEnabled() {
        return "xerial".equals(System.getProperty(ENABLED_PROPERTY));
    }

    static void register() {
        deregisterVisibleDrivers();
        try {
            Driver driver =
                    (Driver) Class.forName(XERIAL_DRIVER).getConstructor().newInstance();
            DriverManager.registerDriver(driver);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Xerial control run could not load " + XERIAL_DRIVER, e);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Xerial control run could not register " + XERIAL_DRIVER, e);
        }
        verify();
    }

    private static void deregisterVisibleDrivers() {
        ClassLoader classLoader = XerialControl.class.getClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == classLoader) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (SQLException e) {
                    throw new IllegalStateException(
                            "Xerial control run could not deregister " + driver, e);
                }
            }
        }
    }

    private static void verify() {
        try {
            Driver driver = DriverManager.getDriver(PROBE_URL);
            if (!XERIAL_DRIVER.equals(driver.getClass().getName())) {
                throw new IllegalStateException(
                        "Xerial control run resolved "
                                + driver.getClass().getName()
                                + " for "
                                + PROBE_URL
                                + " instead of "
                                + XERIAL_DRIVER);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Xerial control run found no driver for " + PROBE_URL, e);
        }
    }
}
