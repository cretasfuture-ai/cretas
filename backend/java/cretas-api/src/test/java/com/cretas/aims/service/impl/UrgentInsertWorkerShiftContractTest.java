package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.dto.scheduling.ConfirmUrgentInsertRequest;
import com.cretas.aims.dto.scheduling.GetInsertSlotsRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.WorkerShiftAvailabilityCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real public service entrypoints; no source-string assertions or estimate-as-fact fixtures. */
@SuppressWarnings("unchecked")
class UrgentInsertWorkerShiftContractTest {
    private static final String F = "F-ROSTER";
    private final LocalDateTime start = LocalDateTime.of(2027, 1, 5, 9, 0);
    private InsertSlotRepository slots;
    private ProductionPlanRepository plans;
    private WorkerShiftAvailabilityCheck shifts;
    private UrgentInsertServiceImpl service;

    @BeforeEach
    void setup() {
        slots = mock(InsertSlotRepository.class);
        plans = mock(ProductionPlanRepository.class);
        shifts = mock(WorkerShiftAvailabilityCheck.class);
        ImpactAnalysisService impact = mock(ImpactAnalysisService.class);
        LineCapacityEquipmentCheck lines = mock(LineCapacityEquipmentCheck.class);
        InsertSlot slot = InsertSlot.builder().id("S1").factoryId(F)
                .startTime(start).endTime(start.plusHours(2)).requiredWorkers(2)
                .availableWorkers(99).availableCapacity(BigDecimal.TEN)
                .impactLevel("none").status("available").build();
        when(slots.findByIdAndFactoryId("S1", F)).thenReturn(Optional.of(slot));
        when(slots.claimAvailableSlot(eq("S1"), eq(F), any())).thenReturn(1);
        when(plans.save(any(ProductionPlan.class))).thenAnswer(i -> i.getArgument(0));
        when(impact.calculateChainImpact(anyString(), any(), any(), any(), any()))
                .thenReturn(ChainImpactResult.builder().impactLevel("none").build());
        when(impact.calculateRecommendScore(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new ImpactAnalysisService.RecommendScoreResult());
        ResourceCheckResult ready = ResourceCheckResult.available("TEST", BigDecimal.TEN, BigDecimal.ONE);
        when(impact.checkMaterialAvailability(anyString(), anyString(), any())).thenReturn(ready);
        when(lines.checkEquipment(anyString(), anyString())).thenReturn(ready);
        when(lines.checkCapacity(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn(ready);
        service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.of(
                InsertSlotRepository.class, slots, ProductionPlanRepository.class, plans,
                WorkerShiftAvailabilityCheck.class, shifts, ImpactAnalysisService.class, impact,
                LineCapacityEquipmentCheck.class, lines, ObjectMapper.class, new ObjectMapper()));
    }

    private WorkerShiftAvailabilityCheck.Result ready() {
        return new WorkerShiftAvailabilityCheck.Result(true, 2, 2, Set.of(11L, 12L), "人员排班可用: 2人");
    }

    private Map<String, Object> analyze() {
        GetInsertSlotsRequest request = new GetInsertSlotsRequest();
        request.setProductTypeId("P1");request.setRequiredQuantity(BigDecimal.TEN);
        return service.analyzeInsertImpact(F, "S1", request);
    }

    private ConfirmUrgentInsertRequest request(List<Long> selected) {
        return ConfirmUrgentInsertRequest.builder().slotId("S1").productTypeId("P1")
                .plannedQuantity(BigDecimal.TEN).assignedWorkerIds(selected).build();
    }

    @Test
    void actualRosterCanMakeFullyCheckedImpactFeasibleWithoutClaimingReservation() {
        when(shifts.check(F, start, start.plusHours(2), 2)).thenReturn(ready());
        Map<String, Object> result = analyze();
        assertThat(result).containsEntry("isFeasible", true).containsEntry("feasibilityStatus", "FEASIBLE");
        assertThat((Map<String, Object>) result.get("resourceStatus"))
                .containsEntry("workerVerified", true).containsEntry("availableWorkers", 2)
                .containsEntry("workerReservationStatus", "NOT_RESERVED");
        verifyNoInteractions(plans);
    }

    @Test
    void actualShortageOverridesCachedNinetyNineWorkers() {
        when(shifts.check(F, start, start.plusHours(2), 2)).thenReturn(
                new WorkerShiftAvailabilityCheck.Result(true, 2, 1, Set.of(11L), "人员不足: 需要2人，可用1人"));
        Map<String, Object> result = analyze();
        assertThat(result).containsEntry("isFeasible", false).containsEntry("feasibilityStatus", "INFEASIBLE")
                .containsEntry("riskLevel", "critical");
        assertThat((Map<String, Object>) result.get("resourceStatus")).containsEntry("availableWorkers", 1)
                .containsEntry("hasEnoughWorkers", false).containsEntry("workerStatus", "SHORTAGE");
    }

    @Test
    void missingRosterIsUnknownRatherThanEstimatedAvailability() {
        when(shifts.check(F, start, start.plusHours(2), 2)).thenReturn(
                WorkerShiftAvailabilityCheck.Result.unverified(2, "人员未验证: 未配置班次"));
        Map<String, Object> result = analyze();
        assertThat(result).containsEntry("isFeasible", false).containsEntry("feasibilityStatus", "UNVERIFIED");
        assertThat((Map<String, Object>) result.get("resourceStatus")).containsEntry("workerVerified", false)
                .containsEntry("hasEnoughWorkers", false);
    }

    @Test
    void stalePreviewCannotAuthorizeNowUnavailableExplicitWorkers() {
        when(shifts.check(F, start, start.plusHours(2), 2)).thenReturn(ready(),
                new WorkerShiftAvailabilityCheck.Result(true, 2, 1, Set.of(11L), "人员不足"));
        assertThat(analyze()).containsEntry("isFeasible", true);
        assertThatThrownBy(() -> service.confirmInsert(F, 1L, request(List.of(11L, 12L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不可派");
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
        verify(plans, never()).save(any());
    }

    @Test
    void forceInsertAlsoRejectsUnknownExplicitWorkerFactsBeforeClaimOrWrite() {
        when(shifts.check(F, start, start.plusHours(2), 2)).thenReturn(
                WorkerShiftAvailabilityCheck.Result.unverified(2, "人员未验证: 班次已变更"));
        assertThatThrownBy(() -> service.forceInsert(F, 1L, request(List.of(11L, 12L))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("人员未验证");
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
        verify(plans, never()).save(any());
    }

    @Test
    void everyExplicitWorkerMustBeInVerifiedOwnFactoryAvailabilitySet() {
        when(shifts.check(F, start, start.plusHours(2), 2)).thenReturn(ready());
        for (List<Long> ids : List.of(List.of(11L), List.of(11L, 777L), List.of(11L, 12L, 777L))) {
            assertThatThrownBy(() -> service.confirmInsert(F, 1L, request(ids)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("指定工人不足");
        }
        verify(plans, never()).save(any());
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
    }

    @Test
    void duplicatesNullAndInvalidIdsNeverCountAsExtraWorkers() {
        for (List<Long> ids : Arrays.asList(List.of(11L, 11L), Arrays.asList(11L, null), List.of(11L, -1L))) {
            assertThatThrownBy(() -> service.confirmInsert(F, 1L, request(ids)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("名单无效");
        }
        verifyNoInteractions(shifts, plans);
    }

    @Test
    void validExplicitWorkersAllowOnePlanButDoNotBecomeAPersonnelReservation() {
        when(shifts.check(F, start, start.plusHours(2), 2)).thenReturn(ready());
        service.confirmInsert(F, 1L, request(List.of(11L, 12L)));
        verify(slots).claimAvailableSlot(eq("S1"), eq(F), any());
        verify(plans).save(argThat(p -> F.equals(p.getFactoryId()) && start.equals(p.getStartTime())));
    }

    @Test
    void omittedSelectionPreservesExistingPendingPlanForLaterAssignmentPath() {
        service.confirmInsert(F, 1L, request(null));
        verifyNoInteractions(shifts);
        verify(plans).save(any(ProductionPlan.class));
    }
}
