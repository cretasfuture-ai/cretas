package com.cretas.aims.dto.supplier;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;

import java.math.BigDecimal;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SupplierMaterialDTO {
    private String id;
    private String factoryId;
    private String supplierId;
    private String supplierName;
    private String materialTypeId;
    private String materialCode;
    private String materialName;
    private String baseUnit;
    @PriceSensitive(permission = PriceSensitive.PURCHASE) private BigDecimal materialReferencePrice;
    private String materialReferencePriceUnit;
    private String supplierMaterialCode;
    @PriceSensitive(permission = PriceSensitive.PURCHASE) private BigDecimal defaultPurchasePrice;
    /**
     * 含税默认采购价 —— 编辑态回填输入框用的就是它。
     * 🔴 本字段为空而 defaultPurchasePrice 有值 = 存量基准未知,
     *    前端必须显示为「基准未知」, ⛔ 不许从未税反推一个含税值填进输入框。
     */
    @PriceSensitive(permission = PriceSensitive.PURCHASE) private BigDecimal taxIncludedDefaultPurchasePrice;
    private String currency;
    private String purchaseUnit;
    @PriceSensitive(permission = PriceSensitive.PURCHASE) private BigDecimal effectivePurchasePrice;
    private String effectivePriceUnit;
    private String priceSource;
    private BigDecimal minOrderQuantity;
    private Integer leadTimeDays;
    private Boolean preferred;
    private Boolean active;
    private Long version;
}
