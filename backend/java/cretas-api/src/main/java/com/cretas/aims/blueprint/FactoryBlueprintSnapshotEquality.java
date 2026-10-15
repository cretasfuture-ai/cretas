package com.cretas.aims.blueprint;

import java.util.Objects;

/** Exact confirmation/replay metadata, not physical unit equivalence.
 * A different alias, display name, scale or digest means a different confirmed
 * snapshot even when a quantity could be converted. Quantity/route identity
 * must instead go through the factory unit authority.
 */
public final class FactoryBlueprintSnapshotEquality {
    private FactoryBlueprintSnapshotEquality() { }

    public static boolean sameConfirmedValue(Object confirmed, Object persisted) {
        return Objects.equals(confirmed, persisted);
    }
}
