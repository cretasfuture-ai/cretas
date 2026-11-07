package com.cretas.aims.service.config;

/** A first formal draft must never adopt unversioned legacy module-permission rows. */
public final class CanvasDraftVersionPolicy {
    private CanvasDraftVersionPolicy() { }

    public static int next(int latestConfigurationVersion, boolean legacyVersionOneExists) {
        int next = Math.addExact(latestConfigurationVersion, 1);
        return next == 1 && legacyVersionOneExists ? 2 : next;
    }
}
