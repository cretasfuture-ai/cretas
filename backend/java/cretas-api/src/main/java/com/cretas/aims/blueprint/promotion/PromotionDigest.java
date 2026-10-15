package com.cretas.aims.blueprint.promotion;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Length-prefixed SHA-256 over canonical strings (the same framing the DRAFT commands use), plus the
 * canonical form both the preview digest and the readback comparison are computed from.
 */
final class PromotionDigest {

    /** Distinct from every real value, including the empty string. */
    private static final String NULL_MARKER = "\u0000NULL";

    private PromotionDigest() {
    }

    static String sha256(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNullElse(value, NULL_MARKER).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /**
     * {@code null} stays distinguishable from {@code ""}; decimals compare by value (0.50 == 0.5);
     * enums by name. Everything else by {@code toString()}, without trimming: a live service that
     * rewrites a value must show up as a difference, not be normalised away.
     */
    static String canonical(Object value) {
        if (value == null) return NULL_MARKER;
        if (value instanceof BigDecimal decimal) {
            BigDecimal normalized = decimal.stripTrailingZeros();
            return (normalized.scale() < 0 ? normalized.setScale(0) : normalized).toPlainString();
        }
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        return value.toString();
    }

    /** Key-sorted {@code key=value} lines, so field order never changes a digest. */
    static String canonicalFields(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(fields).forEach((key, value) ->
                out.append(key).append('=').append(canonical(value)).append('\n'));
        return out.toString();
    }
}
