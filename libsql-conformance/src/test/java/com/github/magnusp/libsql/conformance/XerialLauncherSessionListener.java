package com.github.magnusp.libsql.conformance;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

public final class XerialLauncherSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (XerialControl.isEnabled()) {
            XerialControl.register();
            return;
        }
        XerialConformanceDriver.register();
        LibsqlConformanceGuard.verify();
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        XerialTestServer.stop();
    }
}
