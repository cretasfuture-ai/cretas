package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The {@code sourceDigest} every capability's DRAFT request of one blueprint version carries.
 *
 * <p>No command compares {@code sourceDigest} with the blueprint: chain-head commands only check it
 * is 64 hex characters, and downstream commands require it to equal the value stored on their
 * upstream DRAFT row. It is a lineage token passed along the chain, so all proposals of one
 * blueprint version must carry the same value — this class is the single place it is computed.</p>
 *
 * <p>Inputs: the factory, blueprint id and version, and the user-reviewed source payload, serialized
 * with sorted keys and without nulls (adding a nullable field to the payload class does not change
 * the digest of payloads that never set it). The envelope's {@code capturedAt} is deliberately
 * excluded: it changes on every save, and a re-save of an unchanged payload must not break the
 * lineage of DRAFTs already written. A changed payload does change the digest, which is intended —
 * DRAFTs written from the old payload no longer match.</p>
 */
public final class BlueprintSourceDigest {

    public static final String ALGORITHM_VERSION = "factory-blueprint-source-digest/v1";

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private BlueprintSourceDigest() {
    }

    /** @throws IllegalArgumentException when the blueprint has no source payload to derive from */
    public static String of(BlueprintResponse blueprint) {
        if (blueprint.getSourcePayload() == null) {
            throw new IllegalArgumentException("blueprint has no source payload");
        }
        String canonicalPayload;
        try {
            canonicalPayload = CANONICAL.writeValueAsString(blueprint.getSourcePayload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("source payload is not serializable", e);
        }
        String material = String.join("\n", ALGORITHM_VERSION, blueprint.getFactoryId(),
                blueprint.getId(), String.valueOf(blueprint.getVersion()), canonicalPayload);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
