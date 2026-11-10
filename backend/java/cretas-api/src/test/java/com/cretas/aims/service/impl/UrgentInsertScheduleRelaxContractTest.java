package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.Day;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.Delay;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.PlanResult;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService.Proposal;
import com.cretas.aims.service.scheduling.daily.DailyCapacityScheduleGenerator;
import com.cretas.aims.service.shortage.DeliveryRiskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A18 (Steve 2026-11-10): 插单排程「排不进」的每种原因, 工厂各自配成「拦」或「照插并提示」, 默认全拦 (现状)。
 * 「照插」= 插单计划照建, 不写任何排程行也不挪别的计划, 结果里说清没排入及原因。
 */
class UrgentInsertScheduleRelaxContractTest {
    private static final String F = "F-RELAX";
    private static final String LINE = "L1";
    private static final LocalDate DAY = LocalDate.now().plusDays(2);

    private DailyCapacityScheduleGenerator generator;
    private LineScheduleRepository lineSchedules;
    private FactorySettingsRepository settings;
    private UrgentInsertScheduleService service;
    private InsertSlot slot;
    private ProductionPlan insert;

    @BeforeEach
    void setUp() {
        generator = mock(DailyCapacityScheduleGenerator.class);
        ApsConstraintQueryRepository constraints = mock(ApsConstraintQueryRepository.class);
        ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
        lineSchedules = mock(LineScheduleRepository.class);
        SchedulingPlanRepository schedulingPlans = mock(SchedulingPlanRepository.class);
        settings = mock(FactorySettingsRepository.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = UrgentInsertTestSupport.construct(UrgentInsertScheduleService.class, Map.of(
                DailyCapacityScheduleGenerator.class, generator,
                ApsConstraintQueryRepository.class, constraints,
                ProductionPlanRepository.class, plans,
                LineScheduleRepository.class, lineSchedules,
                SchedulingPlanRepository.class, schedulingPlans,
                DeliveryRiskService.class, mock(DeliveryRiskService.class),
                PlatformTransactionManager.class, tm,
                FactorySettingsRepository.class, settings));
        slot = InsertSlot.builder().id("S1").factoryId(F).productionLineId(LINE)
                .startTime(DAY.atTime(9, 0)).endTime(DAY.atTime(11, 0)).status("selected").build();
        insert = new ProductionPlan();
        insert.setId("PLAN-INS");
        insert.setFactoryId(F);
        insert.setPlanNumber("URGENT-1");
        ProductionPlan planB = new ProductionPlan();
        planB.setId("PLAN-B");
        planB.setFactoryId(F);
        planB.setPlanNumber("PB-1");
        LineSchedule rowB = new LineSchedule();
        rowB.setId("LS-B");
        rowB.setProductionLineId(LINE);
        rowB.setProductionPlanId("PLAN-B");
        rowB.setStatus(LineSchedule.ScheduleStatus.pending);
        rowB.setPlannedStartTime(DAY.atStartOfDay());
        rowB.setPlannedEndTime(DAY.plusDays(2).atTime(23, 59));
        rowB.setPlannedQuantity(100);
        rowB.setAdjustmentCount(0);
        when(constraints.findLiveEndingFrom(eq(F), any())).thenReturn(List.of(rowB));
        when(plans.findByIdAndFactoryId("PLAN-B", F)).thenReturn(Optional.of(planB));
        when(schedulingPlans.findDayPlanHead(eq(F), any())).thenReturn(Optional.empty());
    }

    private static PlanResult scheduled(String planId) {
        List<Day> days = new ArrayList<>();
        days.add(new Day(DAY, new BigDecimal("50"), BigDecimal.ONE));
        return new PlanResult(planId, planId, "SCHEDULED", days, List.of(), List.of(), Map.of("unit", "kg"));
    }

    private static PlanResult failed(String planId, String status) {
        return new PlanResult(planId, planId, status, List.of(),
                List.of(new Delay("EQUIPMENT_BUSY", DAY, DAY, "设备被占")), List.of(), null);
    }

    private void engine(PlanResult... results) {
        when(generator.preview(eq(F), any())).thenReturn(new ConstrainedSchedulePreview("SCHEDULED", LINE, DAY,
                List.of(), List.of(results), List.of()));
    }

    private void capacityMissing() {
        when(generator.preview(eq(F), any())).thenReturn(new ConstrainedSchedulePreview("CAPACITY_REQUIRED", LINE, DAY,
                List.of(), List.of(), List.of()));
    }

    private void relaxed(String codes) {
        when(settings.findUrgentInsertScheduleWarnReasonsByFactoryId(F)).thenReturn(codes);
    }

    // ------------------------------------------------------------------ 默认: 全拦 (现状)

    @Test
    void nothingConfiguredKeepsEveryReasonBlocking() {
        engine(failed("PLAN-INS", "EQUIPMENT_UNAVAILABLE"), scheduled("PLAN-B"));
        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED"));
        capacityMissing();
        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED"));
        verify(lineSchedules, never()).save(any());
    }

    // ------------------------------------------------------------------ 每种原因各自可配

    @Test
    void equipmentUnavailableRelaxedInsertsAndWarnsWithoutWritingASchedule() {
        relaxed("EQUIPMENT_UNAVAILABLE");
        engine(failed("PLAN-INS", "EQUIPMENT_UNAVAILABLE"), scheduled("PLAN-B"));
        Proposal p = service.apply(F, slot, insert, null, 7L);
        assertThat(p.status()).isEqualTo(UrgentInsertScheduleService.UNSCHEDULED);
        assertThat(p.message()).contains("设备");
        assertThat(p.fingerprint()).isNotNull();
        verify(lineSchedules, never()).save(any());
    }

    @Test
    void relaxingOneReasonDoesNotRelaxAnother() {
        relaxed("EXCEEDS_HORIZON");
        engine(failed("PLAN-INS", "EQUIPMENT_UNAVAILABLE"), scheduled("PLAN-B"));
        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED"));
    }

    @Test
    void exceedsHorizonRelaxedInserts() {
        relaxed("EXCEEDS_HORIZON");
        engine(failed("PLAN-INS", "EXCEEDS_HORIZON"), scheduled("PLAN-B"));
        assertThat(service.apply(F, slot, insert, null, 7L).status())
                .isEqualTo(UrgentInsertScheduleService.UNSCHEDULED);
    }

    @Test
    void predecessorNotScheduledRelaxedInserts() {
        relaxed("PREDECESSOR_NOT_SCHEDULED");
        engine(failed("PLAN-INS", "PREDECESSOR_NOT_SCHEDULED"), scheduled("PLAN-B"));
        assertThat(service.apply(F, slot, insert, null, 7L).status())
                .isEqualTo(UrgentInsertScheduleService.UNSCHEDULED);
    }

    @Test
    void missingDailyCapacityRelaxedInsertsAndSaysWhatToFill() {
        relaxed("CAPACITY_MISSING");
        capacityMissing();
        Proposal p = service.apply(F, slot, insert, null, 7L);
        assertThat(p.status()).isEqualTo(UrgentInsertScheduleService.UNSCHEDULED);
        assertThat(p.message()).contains("日产能");
        verify(lineSchedules, never()).save(any());
    }

    @Test
    void aBumpedPlanThatCannotBeRescheduledFollowsTheSameSwitchAndNothingIsMoved() {
        relaxed("EXCEEDS_HORIZON");
        engine(scheduled("PLAN-INS"), failed("PLAN-B", "EXCEEDS_HORIZON"));
        Proposal p = service.apply(F, slot, insert, null, 7L);
        assertThat(p.status()).isEqualTo(UrgentInsertScheduleService.UNSCHEDULED);
        assertThat(p.message()).contains("PB-1");
        verify(lineSchedules, never()).save(any());
    }

    @Test
    void relaxedResultHasAFingerprintThatIsStableBetweenPreviewAndConfirm() {
        relaxed("EQUIPMENT_UNAVAILABLE");
        engine(failed("PLAN-INS", "EQUIPMENT_UNAVAILABLE"), scheduled("PLAN-B"));
        String fp = service.apply(F, slot, insert, null, 7L).fingerprint();
        assertThat(service.apply(F, slot, insert, fp, 7L).status()).isEqualTo(UrgentInsertScheduleService.UNSCHEDULED);
    }
}
