package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #1606 审查 M4: {@link ProductionInventoryOwnershipGuard#assertRawSourceAllowed} 只判原料, 不判生产形成的在制 / 副产批次。
 *
 * <p>prod 2026-11-12 只读: LIUSHANMEN 来料加工计划上有 18 行在制 (SEMI_FINISHED) 消耗, 批次 ownership 全是
 * COMPANY_OWNED (在制批次不随计划打戳)。如果把它们也按原料判, 这些计划的小结会永久被拦。
 */
@DisplayName("#1606 M4: 原料来源归属判断不碰在制 / 副产批次")
class RawSourceOwnershipGuardContractTest {

    private static ProductionPlan customerPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setFactoryId("F");
        plan.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        plan.setCustomerId("CUST-A");
        plan.setSourceOrderId("SO-A");
        return plan;
    }

    private static MaterialBatch companyBatch() {
        MaterialBatch b = new MaterialBatch();
        b.setFactoryId("F");
        b.setBatchNumber("B-1");
        b.setOwnership(InventoryOwnership.COMPANY_OWNED);
        return b;
    }

    @Test
    void companyRawOnCustomerPlanIsRefused() {
        assertThatThrownBy(() -> ProductionInventoryOwnershipGuard.assertRawSourceAllowed(
                customerPlan(), companyBatch(), "原料", "t"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void wipBatchOnCustomerPlanIsNotJudgedAsRaw() {
        MaterialBatch wip = companyBatch();
        wip.setProductTypeId("SEMI-1");
        assertThatCode(() -> ProductionInventoryOwnershipGuard.assertRawSourceAllowed(
                customerPlan(), wip, null, "t")).doesNotThrowAnyException();
    }

    @Test
    void byproductBatchOnCustomerPlanIsNotJudgedAsRaw() {
        MaterialBatch bp = companyBatch();
        bp.setByproductSourceReportId(9L);
        assertThatCode(() -> ProductionInventoryOwnershipGuard.assertRawSourceAllowed(
                customerPlan(), bp, "原料", "t")).doesNotThrowAnyException();
    }
}
