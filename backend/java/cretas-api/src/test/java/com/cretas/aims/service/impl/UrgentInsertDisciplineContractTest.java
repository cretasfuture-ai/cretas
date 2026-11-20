package com.cretas.aims.service.impl;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.dto.scheduling.ConfirmUrgentInsertRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.InsertSlotPreview;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.InsertSlotPreviewRepository;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor;
import com.cretas.aims.service.scheduling.WorkerShiftAvailabilityCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 工厂纪律 A1/A2 (Steve 2026-11-10): 插单的标准人数按产线配置 (没配不比、不提示, 人不够只提示不拦);
 * 缺料和开工缺料共用工厂开关 (关: 照插并提示; 开: 拦, 填原因可强制, 原因留痕)。走真实的 confirmInsert 入口。
 */
class UrgentInsertDisciplineContractTest {
    private static final String F = "F-DISC";
    private final LocalDateTime start = LocalDateTime.of(2027, 1, 5, 9, 0);

    private ProductionPlanRepository plans;
    private WorkerShiftAvailabilityCheck shifts;
    private UrgentInsertServiceImpl service;

    private void build(Integer slotRequiredWorkers, boolean materialShort, Boolean blockSwitch) {
        InsertSlotRepository slots = mock(InsertSlotRepository.class);
        plans = mock(ProductionPlanRepository.class);
        shifts = mock(WorkerShiftAvailabilityCheck.class);
        FactorySettingsRepository settings = mock(FactorySettingsRepository.class);
        when(settings.findBlockStartOnOpenShortageByFactoryId(F)).thenReturn(blockSwitch);
        ImpactAnalysisService impact = mock(ImpactAnalysisService.class);
        LineCapacityEquipmentCheck lines = mock(LineCapacityEquipmentCheck.class);
        InsertSlot slot = InsertSlot.builder().id("S1").factoryId(F).productionLineId("L1")
                .startTime(start).endTime(start.plusHours(2)).requiredWorkers(slotRequiredWorkers)
                .availableWorkers(99).availableCapacity(BigDecimal.TEN)
                .impactLevel("none").status("available").build();
        slot.setUpdatedAt(start.minusMinutes(5));
        when(slots.findByIdAndFactoryId("S1", F)).thenReturn(Optional.of(slot));
        when(slots.claimAvailableSlot(eq("S1"), eq(F), any())).thenReturn(1);
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        when(impact.calculateChainImpact(anyString(), any(), any(), any(), any())).thenReturn(
                ChainImpactResult.builder().impactLevel("none").requiresApproval(false)
                        .totalAffectedPlans(0).directConflicts(0).cascadeDelays(0)
                        .maxDelayMinutes(0).totalDelayMinutes(0).affectedVipCustomers(0)
                        .criticalCrPlans(0).exceedingDeadlinePlans(0).impactScore(0).build());
        ResourceCheckResult ready = ResourceCheckResult.available("TEST", BigDecimal.TEN, BigDecimal.ONE);
        when(impact.checkMaterialAvailability(anyString(), anyString(), any())).thenReturn(ready);
        when(lines.checkEquipment(anyString(), anyString())).thenReturn(ready);
        when(lines.checkCapacity(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn(ready);
        InsertSlotPreviewRepository previews = mock(InsertSlotPreviewRepository.class);
        InsertSlotPreview preview = InsertSlotPreview.builder().id("PV1").factoryId(F).slotId("S1")
                .productTypeId("P1").previewQuantity(BigDecimal.TEN).quantityUnit("PIECE")
                .plannedUnit("件").productionLineId("L1").windowStart(start)
                .windowEnd(start.plusHours(2)).slotUpdatedAt(slot.getUpdatedAt())
                .impactLevel("none").requiresApproval(false).affectedPlanCount(0).candidateNumber(1)
                .materialFingerprint(UrgentInsertTestSupport.MATERIAL_FP)
                .impactFingerprint(UrgentInsertServiceImpl.impactFingerprint(null))
                .scheduleFingerprint(UrgentInsertTestSupport.SCHEDULE_FP).build();
        preview.setCreatedAt(LocalDateTime.now());
        when(previews.findByIdAndFactoryId("PV1", F)).thenReturn(Optional.of(preview));
        ProductTypeRepository products = mock(ProductTypeRepository.class);
        ProductType product = new ProductType();
        product.setId("P1");
        product.setFactoryId(F);
        product.setUnit("件");
        when(products.findByIdAndFactoryId("P1", F)).thenReturn(Optional.of(product));
        when(products.findByIdAndFactoryIdForUpdate("P1", F)).thenReturn(Optional.of(product));
        UrgentInsertMaterialAssessor assessor = mock(UrgentInsertMaterialAssessor.class);
        when(assessor.assess(any(), any(), any())).thenReturn(new UrgentInsertMaterialAssessor.Assessment(
                materialShort ? UrgentInsertMaterialAssessor.Status.SHORT : UrgentInsertMaterialAssessor.Status.OK,
                materialShort ? "带鱼 缺 40kg" : null, "RECIPE-1", 3, List.of(),
                UrgentInsertTestSupport.MATERIAL_FP));
        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.ofEntries(
                Map.entry(InsertSlotRepository.class, slots), Map.entry(ProductionPlanRepository.class, plans),
                Map.entry(WorkerShiftAvailabilityCheck.class, shifts), Map.entry(ImpactAnalysisService.class, impact),
                Map.entry(LineCapacityEquipmentCheck.class, lines), Map.entry(ObjectMapper.class, new ObjectMapper()),
                Map.entry(InsertSlotPreviewRepository.class, previews), Map.entry(ProductTypeRepository.class, products),
                Map.entry(SchedulingResourceWriteLock.class, mock(SchedulingResourceWriteLock.class)),
                Map.entry(UrgentInsertMaterialAssessor.class, assessor),
                Map.entry(FactorySettingsRepository.class, settings)));
    }

    private ConfirmUrgentInsertRequest request(List<Long> workers, String shortageReason) {
        return ConfirmUrgentInsertRequest.builder().slotId("S1").productTypeId("P1").previewId("PV1")
                .quantityUnit("PIECE").plannedQuantity(BigDecimal.TEN).assignedWorkerIds(workers)
                .urgentReason("客户加急").shortageForceReason(shortageReason).build();
    }

    // ---------------------------------------------------------------- A1 人数

    @Test
    void noStandardWorkersConfiguredMeansNoComparisonAndNoHint() {
        build(null, false, null);
        ProductionPlanDTO dto = service.confirmInsert(F, 1L, request(List.of(11L), null));
        verify(plans).save(any(ProductionPlan.class));
        assertThat(dto.getUncheckedWarnings()).isEmpty();
        verifyNoInteractions(shifts); // 没配人数就不去核对, 不会拿一个编出来的人数去比
    }

    @Test
    void configuredButShortHandedOnlyHintsAndDoesNotBlock() {
        build(6, false, null);
        when(shifts.check(F, start, start.plusHours(2), 6)).thenReturn(
                new WorkerShiftAvailabilityCheck.Result(true, 6, 2, Set.of(11L, 12L), "人员不足: 需要6人，可用2人"));
        ProductionPlanDTO dto = service.confirmInsert(F, 1L, request(List.of(11L, 12L), null));
        verify(plans).save(any(ProductionPlan.class));
        assertThat(dto.getUncheckedWarnings()).anyMatch(w -> w.contains("6") && w.contains("2"));
    }

    // ---------------------------------------------------------------- A2 缺料

    @Test
    void shortMaterialWithSwitchOffInsertsAndWarns() {
        build(null, true, false);
        ProductionPlanDTO dto = service.confirmInsert(F, 1L, request(null, null));
        verify(plans).save(any(ProductionPlan.class));
        assertThat(dto.getUncheckedWarnings()).anyMatch(w -> w.contains("缺料") && w.contains("带鱼"));
    }

    @Test
    void shortMaterialWithSwitchOnIsBlockedWithoutReason() {
        build(null, true, true);
        assertThatThrownBy(() -> service.confirmInsert(F, 1L, request(null, "  ")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_MATERIAL_SHORT"));
        verify(plans, never()).save(any());
    }

    @Test
    void shortMaterialWithSwitchOnAndReasonInsertsAndLeavesTheReasonOnThePlan() {
        build(null, true, true);
        service.confirmInsert(F, 1L, request(null, "客户同意先做, 原料明天到"));
        verify(plans).save(argThat(p -> p.getNotes() != null && p.getNotes().contains("客户同意先做, 原料明天到")));
    }
}
