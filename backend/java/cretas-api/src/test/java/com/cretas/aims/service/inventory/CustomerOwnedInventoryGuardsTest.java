package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.CustomerStockFulfillmentMode;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.enums.SalesProcessingMode;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.material.MaterialMainCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.processentry.ProductionInventoryOwnershipGuard;
import com.cretas.aims.service.sales.SalesFinishedGoodsOwnershipGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("客户专属库存生产与销售归属门禁")
class CustomerOwnedInventoryGuardsTest {

    @Test
    @DisplayName("库存生产只领用同客户且未绑定销售订单的原料")
    void inventoryProductionRequiresSameCustomerUnassignedRawMaterial() {
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setCustomerId("CUSTOMER-1");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setSourceOrderId(null);

        MaterialBatch allowed = new MaterialBatch();
        allowed.setFactoryId("F006");
        allowed.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        allowed.setOwnerCustomerId("CUSTOMER-1");
        allowed.setSourceSalesOrderId(null);

        assertDoesNotThrow(() -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(plan, allowed, "materialBatchId"));

        MaterialBatch alreadyBound = new MaterialBatch();
        alreadyBound.setFactoryId("F006");
        alreadyBound.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        alreadyBound.setOwnerCustomerId("CUSTOMER-1");
        alreadyBound.setSourceSalesOrderId("SO-OTHER");

        assertThrows(BusinessException.class, () -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(plan, alreadyBound, "materialBatchId"));
    }

    @Test
    @DisplayName("正式销售订单只能发出本订单已预留的同客户预生产成品")
    void prestockedSalesRequiresActiveReservationWithoutLineageRewrite() {
        SalesOrder order = new SalesOrder();
        order.setId("SO-1");
        order.setCustomerId("CUSTOMER-1");
        order.setProcessingMode(SalesProcessingMode.TOLL_PROCESSING);
        order.setCustomerStockFulfillmentMode(CustomerStockFulfillmentMode.PRESTOCKED);

        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        batch.setOwnerCustomerId("CUSTOMER-1");
        batch.setSourceSalesOrderId(null);

        assertDoesNotThrow(() -> SalesFinishedGoodsOwnershipGuard
                .assertBatchAllowed(order, batch, true, "finishedGoodsBatchId"));
        assertThrows(BusinessException.class, () -> SalesFinishedGoodsOwnershipGuard
                .assertBatchAllowed(order, batch, false, "finishedGoodsBatchId"));
    }

    // ==================== 2026-09-02: 来料加工辅料/包材可用工厂自有库存 (owner 裁定) ====================

    @Test
    @DisplayName("🔒 阳性对照: 来料加工计划下, 辅料/包材(工厂自有库存)必须放行 —— 计入加工费, 不进客户物料成本")
    void tollProcessingPlanAllowsCompanyStockForAuxiliaryAndPackaging() {
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setCustomerId("CUSTOMER-1");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setSourceOrderId("SO-1");
        plan.setSourceOrderItemId("ITEM-1");

        // ⚠️ 阳性对照的关键: 批次是 COMPANY_OWNED (不是 CUSTOMER_OWNED) —— 证明真的绕开了
        // 下面「同客户+同销售订单」那条严格链路, 而不是恰好满足了它。
        MaterialBatch companyAuxiliary = new MaterialBatch();
        companyAuxiliary.setFactoryId("F006");
        companyAuxiliary.setOwnership(InventoryOwnership.COMPANY_OWNED);
        assertDoesNotThrow(() -> ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed(
                        plan, companyAuxiliary, MaterialMainCategory.AUXILIARY, "materialBatchId"),
                "辅料 + 工厂自有库存 + 来料加工计划必须放行 (LIUSHANMEN 冰水 MT-20260902-0278 实例)");

        MaterialBatch companyPackaging = new MaterialBatch();
        companyPackaging.setFactoryId("F006");
        companyPackaging.setOwnership(InventoryOwnership.COMPANY_OWNED);
        assertDoesNotThrow(() -> ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed(
                        plan, companyPackaging, MaterialMainCategory.PACKAGING, "materialBatchId"),
                "包材同理必须放行 (L1纸箱一类)");
    }

    @Test
    @DisplayName("🔒🔴 负控①: RAW 绝不回退公司库存 —— 别的客户的批次必须仍被拒 (整个改动能上线的全部理由)")
    void tollProcessingPlanStillRejectsRawFromDifferentCustomer() {
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setCustomerId("CUSTOMER-1");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setSourceOrderId("SO-1");
        plan.setSourceOrderItemId("ITEM-1");

        MaterialBatch otherCustomerRaw = new MaterialBatch();
        otherCustomerRaw.setFactoryId("F006");
        otherCustomerRaw.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        otherCustomerRaw.setOwnerCustomerId("CUSTOMER-2"); // 别的客户
        otherCustomerRaw.setSourceSalesOrderId("SO-1");
        otherCustomerRaw.setSourceSalesOrderItemId("ITEM-1");

        BusinessException error = assertThrows(BusinessException.class, () -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(plan, otherCustomerRaw, MaterialMainCategory.RAW, "materialBatchId"));
        assertEquals("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH", error.getErrorCode());

        // 同一场景换成工厂自有批次: RAW 不在 isCompanyStockEligibleCategory 白名单里, 仍必须被拒。
        MaterialBatch companyOwnedRaw = new MaterialBatch();
        companyOwnedRaw.setFactoryId("F006");
        companyOwnedRaw.setOwnership(InventoryOwnership.COMPANY_OWNED);
        BusinessException error2 = assertThrows(BusinessException.class, () -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(plan, companyOwnedRaw, MaterialMainCategory.RAW, "materialBatchId"));
        assertEquals("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH", error2.getErrorCode());
    }

    @Test
    @DisplayName("🔒🔴 负控②: RAW 同客户但不同销售订单(血缘那一半)仍必须被拒")
    void tollProcessingPlanStillRejectsRawFromSameCustomerDifferentSalesOrder() {
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setCustomerId("CUSTOMER-1");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setSourceOrderId("SO-1");
        plan.setSourceOrderItemId("ITEM-1");

        MaterialBatch sameCustomerDifferentOrder = new MaterialBatch();
        sameCustomerDifferentOrder.setFactoryId("F006");
        sameCustomerDifferentOrder.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        sameCustomerDifferentOrder.setOwnerCustomerId("CUSTOMER-1"); // 同客户
        sameCustomerDifferentOrder.setSourceSalesOrderId("SO-OTHER"); // 不同销售订单 —— 血缘不符
        sameCustomerDifferentOrder.setSourceSalesOrderItemId("ITEM-OTHER");

        BusinessException error = assertThrows(BusinessException.class, () -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(
                        plan, sameCustomerDifferentOrder, MaterialMainCategory.RAW, "materialBatchId"));
        assertEquals("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH", error.getErrorCode());
    }

    @Test
    @DisplayName("🔒🔴 负控③: 辅料放宽只对 COMPANY_OWNED 生效 —— 别的客户的 CUSTOMER_OWNED 辅料批次仍必须被拒")
    void tollProcessingPlanStillRejectsAuxiliaryFromDifferentCustomerWhenCustomerOwned() {
        // 🔴 对抗终审揪出的洞: assertMaterialBatchAllowed 里
        //     isCompanyStockEligibleCategory(materialMainCategory)
        //             && batch.getOwnership() != InventoryOwnership.CUSTOMER_OWNED
        // 这个合取的第二个条件 (ownership != CUSTOMER_OWNED) 是跨客户轴上唯一承重的东西 ——
        // 删掉它, 之前的 52 条测试一条都不会红 (阳性对照全用 COMPANY_OWNED 的 AUX,
        // 阴性对照全用 RAW), 没有一条把 AUXILIARY/PACKAGING 和 CUSTOMER_OWNED 配在一起。
        // 而这不是纸面风险: planExplicit 的批次来自用户传的 batchId, 经
        // findByIdAndFactoryIdForUpdate 取, 那条查询只有工厂域, 没有所有权过滤 ——
        // 一个工厂服务多个客户正是来料加工的形状, 用户显式指定另一个客户的 AUX 批次时,
        // 挡住它的只有这半行。
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setCustomerId("CUSTOMER-1");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setSourceOrderId("SO-1");
        plan.setSourceOrderItemId("ITEM-1");

        MaterialBatch otherCustomerAuxiliary = new MaterialBatch();
        otherCustomerAuxiliary.setFactoryId("F006");
        otherCustomerAuxiliary.setOwnership(InventoryOwnership.CUSTOMER_OWNED); // 别的客户的客供辅料
        otherCustomerAuxiliary.setOwnerCustomerId("CUSTOMER-2");

        BusinessException error = assertThrows(BusinessException.class, () -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(
                        plan, otherCustomerAuxiliary, MaterialMainCategory.AUXILIARY, "materialBatchId"),
                "辅料的公司库存放宽口不能被 CUSTOMER_OWNED 批次借用来绕开跨客户检查");
        assertEquals("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH", error.getErrorCode());
    }

    @Test
    @DisplayName("3-arg 兼容重载逐字不变: 未传大类的旧调用点继续得到最严格口径 (不会被这次改动意外放宽)")
    void threeArgOverloadStaysStrictForCompanyOwnedBatchUnderTollProcessingPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F006");
        plan.setCustomerId("CUSTOMER-1");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setSourceOrderId("SO-1");
        plan.setSourceOrderItemId("ITEM-1");

        MaterialBatch companyOwned = new MaterialBatch();
        companyOwned.setFactoryId("F006");
        companyOwned.setOwnership(InventoryOwnership.COMPANY_OWNED);

        // 3-arg 版本没有大类信息可传, 必须继续拒绝 (等价于 materialMainCategory=null)。
        assertThrows(BusinessException.class, () -> ProductionInventoryOwnershipGuard
                .assertMaterialBatchAllowed(plan, companyOwned, "materialBatchId"));
    }
}
