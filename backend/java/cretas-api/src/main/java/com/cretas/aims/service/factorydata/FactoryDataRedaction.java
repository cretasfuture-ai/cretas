package com.cretas.aims.service.factorydata;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Stable, non-secret replacement values used in exported packages.
 *
 * <p>A single constant replacement is not safe for a unique credential column:
 * two source rows would become the same value and the package could not be
 * restored.  The ordinal is assigned while reading the source snapshot and
 * contains no part of the original credential.</p>
 */
public final class FactoryDataRedaction {

    private FactoryDataRedaction() {}

    public static byte[] newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** Stable package-scoped HMAC marker; the source value is never included in the marker. */
    public static String value(String table, String column, String original, byte[] key, int maxLength) {
        if (original == null) return null;
        if (key == null || key.length < 16) throw new IllegalArgumentException("脱敏映射 key 不足");
        final byte[] digest;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            digest = mac.doFinal(original.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("生成脱敏映射失败: " + table + "." + column, e);
        }
        String marker = "r" + java.util.HexFormat.of().formatHex(digest, 0, 16);
        if (maxLength <= 0 || marker.length() <= maxLength) return marker;
        throw new IllegalStateException("凭证脱敏占位符无法适配源列长度: " + table + "." + column);
    }
}
