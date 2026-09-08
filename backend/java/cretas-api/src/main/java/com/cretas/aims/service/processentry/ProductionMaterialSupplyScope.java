package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialSupplyMode;

/** Display-only explanation of the existing ownership guard; never grants stock access. */
public final class ProductionMaterialSupplyScope {
    private ProductionMaterialSupplyScope() {}

    public static String description(ProductionPlan plan, String materialMainCategory) {
        if (plan == null || plan.getMaterialSupplyMode() != MaterialSupplyMode.CUSTOMER_SUPPLIED
                || ProductionInventoryOwnershipGuard.isCompanyStockEligibleCategory(materialMainCategory)) {
            return null;
        }
        boolean unassigned = plan.getSourceOrderId() == null || plan.getSourceOrderId().isBlank();
        return "本计划按客户供料领用：仅计入该客户"
                + (unassigned ? "尚未绑定销售订单的原料" : "对应销售订单的原料")
                + "；工厂自有库存不计入，调拨仓库不会改变库存归属。请核对计划供料方式和批次归属。";
    }
}
