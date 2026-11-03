package com.cretas.aims.service.production;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺口 09 实际侧: 逐道报工的计划, 最后一道领的是在制品 —— 必须沿在制品来源追到原料叶子
 * (与成本内核 traceCost 同一种走法), 否则实际用量里看不到真正的原料。
 */
class PlanCostVarianceServiceContractTest {

    private final MaterialConsumptionRepository consumptions = mock(MaterialConsumptionRepository.class);
    private final MaterialBatchRepository materialBatches = mock(MaterialBatchRepository.class);
    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final PlanCostVarianceService service = new PlanCostVarianceService(
            mock(PlanStandardCostLineRepository.class), mock(ProductionPlanRepository.class),
            mock(ProductionBatchRepository.class), consumptions, materialBatches, materials,
            mock(OrderCostBreakdownService.class), mock(UnitConversionService.class));

    private static MaterialConsumption consumption(int id, String materialBatchId, String materialTypeId,
                                                   String qty, String unitPrice) {
        MaterialConsumption c = new MaterialConsumption();
        c.setId(id);
        c.setFactoryId("F006");
        c.setBatchId(materialBatchId);
        c.setMaterialTypeId(materialTypeId);
        c.setQuantity(new BigDecimal(qty));
        c.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return c;
    }

    private static MaterialBatch materialBatch(String id, String materialTypeId, String sourceDocType, String sourceDocId,
                                               String receipt, InventoryOwnership ownership) {
        MaterialBatch mb = new MaterialBatch();
        mb.setId(id);
        mb.setBatchNumber("MB-" + id);
        mb.setMaterialTypeId(materialTypeId);
        mb.setQuantityUnit("kg");
        mb.setSourceDocType(sourceDocType);
        mb.setSourceDocId(sourceDocId);
        mb.setReceiptQuantity(receipt == null ? null : new BigDecimal(receipt));
        mb.setOwnership(ownership);
        return mb;
    }

    @Test
    void tracesWipConsumptionBackToRawLeavesProportionally() {
        ProductionBatch finalStep = new ProductionBatch();
        finalStep.setId(1L);
        // 最后一道领 50kg 在制品 (那批在制品入库 100kg, 来自上一道批次 99)
        when(consumptions.findByProductionBatchIdAndFactoryId(1L, "F006")).thenReturn(List.of(
                consumption(1, "WIP", "WIP-TYPE", "50", "13.00"),
                consumption(2, "CUST", "SALT", "3", "2.00")));
        when(materialBatches.findByIdAndFactoryId("WIP", "F006")).thenReturn(Optional.of(
                materialBatch("WIP", "WIP-TYPE", "PRODUCTION_BATCH", "99", "100", InventoryOwnership.COMPANY_OWNED)));
        when(materialBatches.findByIdAndFactoryId("CUST", "F006")).thenReturn(Optional.of(
                materialBatch("CUST", "SALT", "PURCHASE", null, null, InventoryOwnership.CUSTOMER_OWNED)));
        // 上一道: 用了 120kg 牛肉 @10
        when(consumptions.findByProductionBatchIdAndFactoryId(99L, "F006")).thenReturn(List.of(
                consumption(3, "BEEF-LOT", "BEEF", "120", "10.00")));
        when(materialBatches.findByIdAndFactoryId("BEEF-LOT", "F006")).thenReturn(Optional.of(
                materialBatch("BEEF-LOT", "BEEF", "PURCHASE", null, null, InventoryOwnership.COMPANY_OWNED)));
        when(materials.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());

        PlanCostVarianceService.ActualSide actual = service.actualUsage("F006", "PLAN", List.of(finalStep));

        assertThat(actual.byMaterial()).containsOnlyKeys("BEEF");
        PlanCostVarianceCalculator.ActualUsage beef = actual.byMaterial().get("BEEF");
        assertThat(beef.quantity()).as("50/100 × 120").isEqualByComparingTo("60");
        assertThat(beef.amount()).isEqualByComparingTo("600");
        assertThat(actual.customerSupplied()).as("客供料不计我方成本").isEqualTo(1);
    }

    @Test
    void upstreamBatchOfTheSamePlanIsNotCountedTwice() {
        ProductionBatch step1 = new ProductionBatch();
        step1.setId(7L);
        ProductionBatch step2 = new ProductionBatch();
        step2.setId(8L);
        when(consumptions.findByProductionBatchIdAndFactoryId(7L, "F006")).thenReturn(List.of(
                consumption(1, "BEEF-LOT", "BEEF", "100", "10.00")));
        when(consumptions.findByProductionBatchIdAndFactoryId(8L, "F006")).thenReturn(List.of(
                consumption(2, "WIP", "WIP-TYPE", "80", null)));
        when(materialBatches.findByIdAndFactoryId("BEEF-LOT", "F006")).thenReturn(Optional.of(
                materialBatch("BEEF-LOT", "BEEF", "PURCHASE", null, null, InventoryOwnership.COMPANY_OWNED)));
        when(materialBatches.findByIdAndFactoryId("WIP", "F006")).thenReturn(Optional.of(
                materialBatch("WIP", "WIP-TYPE", "PRODUCTION_BATCH", "7", "80", InventoryOwnership.COMPANY_OWNED)));
        when(materials.findByIdAndFactoryId(anyString(), anyString())).thenReturn(Optional.empty());

        PlanCostVarianceService.ActualSide actual = service.actualUsage("F006", "PLAN", List.of(step1, step2));

        assertThat(actual.byMaterial()).containsOnlyKeys("BEEF");
        assertThat(actual.byMaterial().get("BEEF").quantity()).isEqualByComparingTo("100");
    }
}
