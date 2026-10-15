package com.cretas.aims.blueprint.interview;

import java.util.Locale;
import java.util.Set;

/** Adapter for the existing checkpoint writer; no limits or factory data are supplied implicitly. */
public final class AssemblyHaccpContract {
    private AssemblyHaccpContract() { }
    public static final Set<String> HAZARD_TYPES = Set.of("BIOLOGICAL", "CHEMICAL", "PHYSICAL");
    public static String checkpointCode(String name) {
        return "CCP-" + Integer.toHexString(name == null ? 0 : name.trim().hashCode()).toUpperCase(Locale.ROOT);
    }
}
