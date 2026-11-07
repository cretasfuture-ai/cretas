package com.cretas.aims.service.production;

import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanConcurrentReadinessServiceContractTest {
    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final PlanStandardCostLineRepository standards = mock(PlanStandardCostLineRepository.class);
    private final BomRecipeItemRepository bomItems = mock(BomRecipeItemRepository.class);
    private final MaterialBatchRepository materials = mock(MaterialBatchRepository.class);
    private final RawMaterialTypeRepository types = mock(RawMaterialTypeRepository.class);
    private final ProductionPlanBatchUsageRepository held = mock(ProductionPlanBatchUsageRepository.class);
    private final ProductionBatchRepository batches = mock(ProductionBatchRepository.class);
    private final MaterialConsumptionRepository consumption = mock(MaterialConsumptionRepository.class);
    private final ProductionReportRepository reports = mock(ProductionReportRepository.class);
    private final WarehouseResolver warehouses = mock(WarehouseResolver.class);
    private final FinishedGoodsBatchRepository finished = mock(FinishedGoodsBatchRepository.class);
    private final ProcessSecretRecipeGuard secrets = mock(ProcessSecretRecipeGuard.class);
    private final PlanConcurrentReadinessService service = new PlanConcurrentReadinessService(plans, standards, bomItems, materials,
            types, held, batches, consumption, reports, warehouses, finished, secrets);
    private ProductionPlan own;
    private ProductionPlan other;
    private MaterialBatch stock;
    private final Map<String, PlanStandardCostLine> lines = new HashMap<>();

    @BeforeEach void setup() {
        own = plan("own"); other = plan("other");
        when(plans.findByIdAndFactoryId("own", "F006")).thenReturn(Optional.of(own));
        cohort(own, other);
        when(warehouses.productionPickingWarehouseIds("F006")).thenReturn(List.of("raw"));
        RawMaterialType material = new RawMaterialType(); material.setId("material"); material.setFactoryId("F006"); material.setName("原料");
        when(types.findByIdAndFactoryId("material", "F006")).thenReturn(Optional.of(material));
        for (ProductionPlan p : List.of(own, other)) {
            p.setSelectedBomRecipeId("fixed-bom"); p.setSelectedBomVersion(1);
            PlanStandardCostLine line = new PlanStandardCostLine(); line.setFactoryId("F006"); line.setPlanId(p.getId());
            line.setBomRecipeId("fixed-bom"); line.setBomVersion(1); line.setProductTypeId("product");
            line.setLineType(PlanStandardCostLine.TYPE_MATERIAL); line.setMaterialTypeId("material");
            line.setStandardBasis(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT); line.setStandardRate(BigDecimal.ONE); line.setStandardUnit("kg");
            lines.put(p.getId(), line);
            when(standards.findByFactoryIdAndPlanIdOrderByIdAsc("F006", p.getId())).thenReturn(List.of(line));
        }
        BomRecipeItem item = new BomRecipeItem(); item.setMaterialTypeId("material"); item.setMaterialCategory("RAW");
        when(bomItems.findByRecipeIdsWithRecipe(eq("F006"), anyCollection())).thenReturn(List.of(item));
        stock = new MaterialBatch(); stock.setId("lot"); stock.setFactoryId("F006"); stock.setMaterialTypeId("material");
        stock.setWarehouseId("raw"); stock.setQuantityUnit("kg"); stock.setReceiptQuantity(new BigDecimal("100"));
        stock.setUsedQuantity(BigDecimal.ZERO); stock.setReservedQuantity(BigDecimal.ZERO); stock.setStatus(MaterialBatchStatus.AVAILABLE);
        stock.setOwnership(InventoryOwnership.COMPANY_OWNED);
        when(materials.findByFactoryIdAndMaterialTypeId("F006", "material")).thenReturn(List.of(stock));
        when(materials.findByIdAndFactoryId("lot", "F006")).thenReturn(Optional.of(stock));
    }

    private static ProductionPlan plan(String id) {
        ProductionPlan p = new ProductionPlan(); p.setId(id); p.setFactoryId("F006"); p.setPlanNumber(id);
        p.setProductTypeId("product"); p.setStatus(ProductionPlanStatus.PENDING); p.setPlannedQuantity(new BigDecimal("80"));
        p.setPlannedUnit("kg"); p.setBatchDate(LocalDate.of(2099, 1, 1)); return p;
    }
    private void cohort(ProductionPlan... values) {
        when(plans.findByFactoryIdAndStatusIn(eq("F006"), anyCollection(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(values)));
    }
    @Test void samePeriodUnoccupiedDemandHasBoundsAndDoesNotBecomeActualProgress() {
        own.setAllocatedQuantity(new BigDecimal("80")); own.setActualQuantity(new BigDecimal("999"));
        var result = service.read("F006", "own");
        assertThat(result.material().status()).isEqualTo("VERIFIED");
        assertThat(result.material().lowerPercent()).isEqualByComparingTo("25");
        assertThat(result.material().upperPercent()).isEqualByComparingTo("100");
        assertThat(result.progress().receivedQuantity()).isEqualByComparingTo("0");
        assertThat(result.progress().completionPercent()).isEqualByComparingTo("0");
        assertThat(result.advisoryOnly()).isTrue();
        verify(plans, never()).save(any()); verify(materials, never()).save(any());
    }
    @Test void futureAndTerminalAndForeignPlansDoNotCreateDemand() {
        other.setBatchDate(LocalDate.of(2099, 1, 2));
        ProductionPlan cancelled = plan("cancelled"); cancelled.setStatus(ProductionPlanStatus.CANCELLED);
        ProductionPlan completed = plan("completed"); completed.setStatus(ProductionPlanStatus.COMPLETED);
        ProductionPlan foreign = plan("foreign"); foreign.setFactoryId("F001");
        cohort(own, other, cancelled, completed, foreign);
        assertThat(service.read("F006", "own").material().lowerPercent()).isEqualByComparingTo("100");
        other.setStatus(ProductionPlanStatus.IN_PROGRESS);
        assertThat(service.read("F006", "own").material().lowerPercent()).isEqualByComparingTo("25");
    }
    @Test void missingDateOrFrozenStandardOrUnconvertibleUnitsNeverMeansNoShortage() {
        own.setBatchDate(null); assertThat(service.read("F006", "own").material().lowerPercent()).isNull();
        own.setBatchDate(LocalDate.of(2099, 1, 1));
        when(standards.findByFactoryIdAndPlanIdOrderByIdAsc("F006", "other")).thenReturn(List.of());
        assertThat(service.read("F006", "own").material().status()).isEqualTo("UNVERIFIED");
        when(standards.findByFactoryIdAndPlanIdOrderByIdAsc("F006", "other")).thenReturn(List.of(lines.get("other")));
        stock.setQuantityUnit("box"); assertThat(service.read("F006", "own").material().lowerPercent()).isNull();
    }
    @Test void weightConversionsAreExactAndReceiptProgressUsesPlanSkuNotActualOrAllocatedFields() {
        lines.values().forEach(l -> { l.setStandardRate(new BigDecimal("1000")); l.setStandardUnit("g"); });
        when(finished.sumProductionOutputByPlan("F006", "own")).thenReturn(Arrays.asList(
                new Object[]{"product", "g", new BigDecimal("20000")}, new Object[]{"other-sku", "kg", new BigDecimal("900")}));
        var result = service.read("F006", "own");
        assertThat(result.material().lowerPercent()).isEqualByComparingTo("25");
        assertThat(result.progress().receivedQuantity()).isEqualByComparingTo("20");
        assertThat(result.progress().completionPercent()).isEqualByComparingTo("25");
    }
    @Test void completedStateDoesNotImplyOneHundredAndOverproductionIsRetained() {
        own.setStatus(ProductionPlanStatus.COMPLETED);
        assertThat(service.read("F006", "own").material().lowerPercent()).isNull();
        assertThat(service.read("F006", "own").progress().completionPercent()).isEqualByComparingTo("0");
        when(finished.sumProductionOutputByPlan("F006", "own")).thenReturn(Collections.singletonList(new Object[]{"product", "kg", new BigDecimal("100")}));
        assertThat(service.read("F006", "own").progress().completionPercent()).isEqualByComparingTo("125");
        when(finished.sumProductionOutputByPlan("F006", "own")).thenReturn(Collections.singletonList(new Object[]{"product", "case", BigDecimal.ONE}));
        assertThat(service.read("F006", "own").progress().receivedQuantity()).isNull();
    }
    @Test void attributedOwnHeldIsAddedToSupportWithoutDeductingItFromFreeAgain() {
        ProductionPlanBatchUsage row = new ProductionPlanBatchUsage(); row.setProductionPlanId("own"); row.setMaterialBatchId("lot"); row.setReservedQuantity(new BigDecimal("20"));
        when(held.findByProductionPlanId("own")).thenReturn(List.of(row)); stock.setReservedQuantity(new BigDecimal("20"));
        var result = service.read("F006", "own");
        assertThat(result.material().items().getFirst().stockFree()).isEqualByComparingTo("80");
        assertThat(result.material().items().getFirst().ownHeldReserved()).isEqualByComparingTo("20");
        assertThat(result.material().lowerPercent()).isEqualByComparingTo("25");
    }
    @Test void unpostedConsumptionAndAmbiguousReservationAreUnverified() {
        ProductionBatch batch = new ProductionBatch(); batch.setId(1L);
        when(batches.findByFactoryIdAndProductionPlanId("F006", "own")).thenReturn(List.of(batch));
        MaterialConsumption c = new MaterialConsumption(); c.setId(1); c.setFactoryId("F006"); c.setProductionPlanId("own");
        c.setBatchId("lot"); c.setQuantity(new BigDecimal("5"));
        when(consumption.findByProductionBatchIdAndFactoryId(1L, "F006")).thenReturn(List.of(c));
        assertThat(service.read("F006", "own").material().lowerPercent()).isNull();
        c.setInterimSettledAt(LocalDateTime.now()); stock.setUsedQuantity(new BigDecimal("5"));
        assertThat(service.read("F006", "own").material().status()).isEqualTo("VERIFIED");
        stock.setSourceDocType("PRODUCTION_BATCH");
        assertThat(service.read("F006", "own").material().status()).isEqualTo("UNVERIFIED");
        assertThat(service.read("F006", "own").material().lowerPercent()).isNull();
    }
    @Test void foreignTargetIsRejectedBeforeOtherReads() {
        assertThatThrownBy(() -> service.read("F001", "own")).isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        verifyNoInteractions(standards, materials, finished);
    }
    @Test void protectedTargetOrPeerCannotLeakThroughAggregateOrSilentlyDisappear() {
        other.setProductTypeId("protected-peer");
        doThrow(new com.cretas.aims.exception.BusinessException(403,"无权查看完整工艺"))
                .when(secrets).requireProtectedAmong(eq("F006"), argThat(ids -> ids.contains("protected-peer")),
                        eq(ProcessSecretAction.VIEW_FULL), eq("PLAN_CONCURRENT_READINESS"));
        assertThatThrownBy(() -> service.read("F006", "own")).isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        verifyNoInteractions(standards, materials, finished);
    }
    @Test void pinnedRecipeAuthorizationIsRequiredEvenWhenPlanProductLooksOrdinary() {
        doThrow(new com.cretas.aims.exception.BusinessException(403,"固定配方无完整工艺权限"))
                .when(secrets).requireRecipe("F006", "fixed-bom", ProcessSecretAction.VIEW_FULL, "PLAN_CONCURRENT_READINESS");
        assertThatThrownBy(() -> service.read("F006", "own")).isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        verifyNoInteractions(standards, materials, finished);
    }
    @Test void secretAuditFailureStopsTheAggregateInsteadOfDroppingProtectedPeers() {
        doThrow(new com.cretas.aims.exception.BusinessException(503,"工艺访问审计未完成"))
                .when(secrets).requireProtectedAmong(eq("F006"), anyCollection(), eq(ProcessSecretAction.VIEW_FULL), eq("PLAN_CONCURRENT_READINESS"));
        assertThatThrownBy(() -> service.read("F006", "own")).isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        verifyNoInteractions(standards, materials, finished);
    }
}
