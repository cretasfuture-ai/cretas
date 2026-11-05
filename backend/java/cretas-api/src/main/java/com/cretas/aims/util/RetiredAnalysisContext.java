package com.cretas.aims.util;

import java.util.Locale;

/** Denial boundary for identities retained only for historical compatibility. */
public final class RetiredAnalysisContext {
    private RetiredAnalysisContext() {}

    public static boolean isRetired(String factoryId, String domain, String role) {
        if (role != null && role.toLowerCase(Locale.ROOT).startsWith("restaurant_")) {
            return true;
        }
        if (domain != null && !domain.isBlank()) {
            return BusinessDomainUtils.RESTAURANT.equalsIgnoreCase(domain);
        }
        String id = factoryId == null ? "" : factoryId.trim().toUpperCase(Locale.ROOT);
        return id.equals("DEMO_REST") || id.startsWith("RES_") || id.startsWith("REST_")
                || (id.startsWith("R_") && id.endsWith("_REAL"));
    }
}
