package com.cretas.aims.blueprint.draftentry;

import org.springframework.data.domain.Sort;

/**
 * Shared conventions for {@link CapabilityDraftEntry#written}: newest first, by creation time then id.
 *
 * <p>Implementations query their DRAFT repository by example with exactly the four lineage fields
 * of {@link CapabilityDraftEntry.WrittenScope} set on a fresh entity (factory, blueprint, version,
 * source digest). That is only correct while the DRAFT entity has no primitive fields and no field
 * initializers — those would silently join the probe as extra conditions. Each implementation's
 * test asserts the probe carries only those four values.</p>
 */
final class WrittenDrafts {

    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private WrittenDrafts() {
    }
}
