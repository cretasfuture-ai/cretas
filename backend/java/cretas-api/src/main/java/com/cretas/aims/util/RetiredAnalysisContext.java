package com.cretas.aims.util;

import java.util.Locale;

/** Denial boundary for identities retained only for historical compatibility. */
public final class RetiredAnalysisContext {
    private RetiredAnalysisContext() {}

    public static boolean isRetiredIdentity(String factoryId, String role) {
        if (role != null && role.toLowerCase(Locale.ROOT).startsWith("restaurant_")) {
            return true;
        }
        String id = factoryId == null ? "" : factoryId.trim().toUpperCase(Locale.ROOT);
        return switch (id) {
            case "MOCK_REST", "DEMO_REST",
                    "R_DONGMENKOU_REAL", "R_HONGDEJI_REAL", "R_HUOGUO_GENERIC_REAL",
                    "R_ILTEATRO_REAL", "R_JINCHUAN_HG_REAL", "R_JINRINIUSHI_REAL",
                    "R_LINJIAYAN_REAL", "R_QINGHUAJIAO_REAL", "R_SHANGMA_HG_REAL",
                    "R_XIMAXIANG_REAL", "R_XINBASHU_REAL", "R_YONGHE_REAL",
                    "R_YOUZIYOUWEI_REAL", "R_YUJIUJING_REAL" -> true;
            default -> false;
        };
    }

    public static boolean isRetired(String factoryId, String domain, String role) {
        if (isRetiredIdentity(factoryId, role)) return true;
        if (domain != null && !domain.isBlank()) {
            return BusinessDomainUtils.RESTAURANT.equalsIgnoreCase(domain.trim())
                    || "BRANCH".equalsIgnoreCase(domain.trim());
        }
        String id = factoryId == null ? "" : factoryId.trim().toUpperCase(Locale.ROOT);
        return id.startsWith("RES_") || id.startsWith("REST_") || id.startsWith("DEMO_REST_")
                || id.startsWith("RESTAURANT_") || id.startsWith("R_GML") || id.startsWith("QHJ_");
    }

    public static boolean isKnownFactoryDomain(String domain) {
        if (domain == null) return false;
        return switch (domain.trim().toUpperCase(Locale.ROOT)) {
            case "FACTORY", "HEADQUARTERS", "CENTRAL_KITCHEN" -> true;
            default -> false;
        };
    }

    /** Only explicit manufacturing metadata admits requests to AI recognition or execution. */
    public static void requireFactory(String factoryId, String domain, String role) {
        if (isRetired(factoryId, domain, role)) {
            throw new com.cretas.aims.exception.BusinessException(410, "该业务能力已退役，请使用工厂业务功能。")
                    .withCode("RESTAURANT_CAPABILITY_RETIRED");
        }
        if (!isKnownFactoryDomain(domain)) {
            throw new com.cretas.aims.exception.BusinessException(503, "无法确认租户业务类型，暂不可执行意图")
                    .withCode("TENANT_TYPE_UNAVAILABLE");
        }
    }
}
