package com.cretas.aims.audit.inventory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared write/query key for stock IDs longer than operation_logs.entity_id (100). */
public final class InventoryAuditIdentity {
    private InventoryAuditIdentity() { }

    public static String indexId(String id) {
        if (id == null || id.length() <= 100) return id;
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 digest unavailable", exception);
        }
    }
}
