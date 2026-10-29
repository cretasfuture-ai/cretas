package com.cretas.aims.dto.material;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * A selectable purchase-packaging conversion for one material type.
 *
 * <p>{@code derivedPrice*} 三个字段是**只读**的: 只有在按供应关系查询
 * ({@code GET /material-packaging/by-material/{id}?supplierMaterialId=...}) 时才会填,
 * upsert 请求里不带、也不会被保存。它们存在的理由见
 * {@link com.cretas.aims.service.supplier.PurchasePriceDerivation} ——
 * 客户 2026-07-30「设完换算规格后不再自动带出供应商采购价」在物料侧包装换算上的那一半。
 */
public record MaterialPackagingSpecDTO(
        String id,
        String name,
        @NotBlank String packageUnit,
        @NotBlank String baseUnit,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @Digits(integer = 20, fraction = 8) BigDecimal conversionFactor,
        Boolean defaultSpec,
        Boolean active,
        Integer sortOrder,
        Long version,
        @com.cretas.aims.security.PriceSensitive(permission = com.cretas.aims.security.PriceSensitive.PURCHASE)
        BigDecimal derivedPrice,
        String derivedPriceUnit,
        String derivedPriceSource) {

    /**
     * 写入路径 (upsert 请求 / 既有构造点) 用的 9 参形态 —— 不带推导价。
     * 保留它是为了让新增字段不波及既有构造点 (硬约束 8: 改共享结构前先数, 4 处, 全部保持不变)。
     */
    public MaterialPackagingSpecDTO(String id, String name, String packageUnit, String baseUnit,
                                    BigDecimal conversionFactor, Boolean defaultSpec, Boolean active,
                                    Integer sortOrder, Long version) {
        this(id, name, packageUnit, baseUnit, conversionFactor, defaultSpec, active, sortOrder, version,
                null, null, null);
    }

    /** 读取路径用: 附上按某条供应关系推导出来的计价。 */
    public MaterialPackagingSpecDTO withDerivedPrice(BigDecimal price, String unit, String source) {
        return new MaterialPackagingSpecDTO(id, name, packageUnit, baseUnit, conversionFactor,
                defaultSpec, active, sortOrder, version, price, unit, source);
    }
}
