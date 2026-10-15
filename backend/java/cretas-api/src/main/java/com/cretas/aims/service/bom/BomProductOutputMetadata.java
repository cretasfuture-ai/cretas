package com.cretas.aims.service.bom;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;

import java.math.BigDecimal;

/** Shared, read-only SKU output prerequisite for formal BOM writes and their previews. */
public final class BomProductOutputMetadata {
    private BomProductOutputMetadata() { }

    public record NetContentSnapshot(BigDecimal quantity, String unit) { }

    /** Exactly the formal writer's historical explicit-pair or grams/g snapshot rule. */
    public static NetContentSnapshot snapshot(ProductType product) {
        if (product.getNetContentQuantity() != null && product.getNetContentUnit() != null) {
            return new NetContentSnapshot(product.getNetContentQuantity(), product.getNetContentUnit());
        }
        return new NetContentSnapshot(product.getGramsPerUnit(), product.getGramsPerUnit() == null ? null : "g");
    }

    public static void validate(ProductType product) {
        if (product.getUnit() == null || product.getUnit().isBlank()) {
            throw error("SKU 未配置产出单位，不能创建或激活 BOM", "BOM_SKU_UNIT_REQUIRED",
                    "请先在产品档案中填写基本单位", "productUnit");
        }
        BigDecimal netContent = product.getNetContentQuantity() != null
                ? product.getNetContentQuantity() : product.getGramsPerUnit();
        String netContentUnit = product.getNetContentUnit() != null
                ? product.getNetContentUnit() : (product.getGramsPerUnit() == null ? null : "g");
        if (netContent == null || netContent.compareTo(BigDecimal.ZERO) <= 0 || netContentUnit == null) {
            throw error("SKU 未配置有效净含量，不能创建或激活 BOM", "BOM_SKU_NET_CONTENT_REQUIRED",
                    "请先在产品档案中填写大于 0 的净含量及单位", "netContentQuantity");
        }
    }

    private static BusinessException error(String message, String code, String hint, String target) {
        return new BusinessException(409, message).withCode(code).withHint(hint)
                .withHintTarget(target).withSeverity("BLOCKING");
    }
}
