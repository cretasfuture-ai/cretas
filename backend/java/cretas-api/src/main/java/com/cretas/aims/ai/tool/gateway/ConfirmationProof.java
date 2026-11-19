package com.cretas.aims.ai.tool.gateway;

import java.time.Instant;

/**
 * Opaque, parameter-bound confirmation evidence verified by the gateway.
 *
 * <p>{@code entityType} / {@code entityId} name the business object the caller is about to
 * execute against (as the issuer recorded it on the token). The gateway claim binds them inside
 * the atomic claim predicate, so a token issued for another object is never claimed. A proof
 * built without them ({@link #ConfirmationProof(String, String, Instant)}) carries no expected
 * object and is rejected by the gateway before any claim; it remains valid for the non-gateway
 * legacy confirm entry, which re-derives the object from the token row.</p>
 */
public record ConfirmationProof(
        String proofToken,
        String commandDigest,
        Instant expiresAt,
        String entityType,
        String entityId) {

    public ConfirmationProof {
        proofToken = ContractValidation.requireNonBlank(proofToken, "proofToken");
        commandDigest = ContractValidation.requireNonBlank(commandDigest, "commandDigest");
        expiresAt = ContractValidation.requireNonNull(expiresAt, "expiresAt");
    }

    /** Proof without an expected business object (legacy confirm entry only; the gateway rejects it). */
    public ConfirmationProof(String proofToken, String commandDigest, Instant expiresAt) {
        this(proofToken, commandDigest, expiresAt, null, null);
    }

    /** True when the caller stated which business object it executes against. */
    public boolean bindsSubject() {
        return entityType != null && !entityType.isBlank();
    }

    @Override
    public String toString() {
        return "ConfirmationProof[proofToken=<redacted>, commandDigest=" + commandDigest
                + ", expiresAt=" + expiresAt + ", entityType=" + entityType + "]";
    }
}
