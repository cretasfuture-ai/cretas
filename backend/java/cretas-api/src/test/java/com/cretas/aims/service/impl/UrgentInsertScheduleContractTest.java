package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.Day;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.Delay;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.PlanResult;
import com.cretas.aims.dto.scheduling.SchedulePreviewRequest;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService.Proposal;
import com.cretas.aims.service.scheduling.daily.DailyCapacityScheduleGenerator;
import com.cretas.aims.service.shortage.DeliveryRiskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 17 第 2 项: 批准后真正调整排程。引擎 (缺口 18) 换成桩, 其余 (挑被挤排程、落库、指纹、提醒)
 * 走真实的 {@link UrgentInsertScheduleService}。
 */
class UrgentInsertScheduleContractTest {

    private static final String F = "F-SCHED";
    private static final String LINE = "L1";
    private static final LocalDate DAY = LocalDate.now().plusDays(2);

    private DailyCapacityScheduleGenerator generator;
    private ApsConstraintQueryRepository constraints;
    private ProductionPlanRepository plans;
    private LineScheduleRepository lineSchedules;
    private SchedulingPlanRepository schedulingPlans;
    private DeliveryRiskService risk;
    private PlatformTransactionManager tm;
    private TransactionStatus txStatus;
    private UrgentInsertScheduleService service;

    private InsertSlot slot;
    private ProductionPlan insert;
    private ProductionPlan planA;
    private ProductionPlan planB;
    private LineSchedule rowA;
    private LineSchedule rowB;
    private LineSchedule runningRow;
    private LineSchedule otherLineRow;

    @BeforeEach
    void setUp() {
        generator = mock(DailyCapacityScheduleGenerator.class);
        constraints = mock(ApsConstraintQueryRepository.class);
        plans = mock(ProductionPlanRepository.class);
        lineSchedules = mock(LineScheduleRepository.class);
        schedulingPlans = mock(SchedulingPlanRepository.class);
        risk = mock(DeliveryRiskService.class);
        tm = mock(PlatformTransactionManager.class);
        txStatus = mock(TransactionStatus.class);
        when(tm.getTransaction(any())).thenReturn(txStatus);
        service = UrgentInsertTestSupport.construct(UrgentInsertScheduleService.class, Map.of(
                DailyCapacityScheduleGenerator.class, generator,
                ApsConstraintQueryRepository.class, constraints,
                ProductionPlanRepository.class, plans,
                LineScheduleRepository.class, lineSchedules,
                SchedulingPlanRepository.class, schedulingPlans,
                DeliveryRiskService.class, risk,
                PlatformTransactionManager.class, tm));

        slot = InsertSlot.builder().id("S1").factoryId(F).productionLineId(LINE)
                .startTime(DAY.atTime(9, 0)).endTime(DAY.atTime(11, 0)).status("selected").build();
        insert = plan("PLAN-INS", "URGENT-1", DAY.plusDays(1), null);
        planA = plan("PLAN-A", "PA-1", DAY.plusDays(6), "SO-A");
        planB = plan("PLAN-B", "PB-1", DAY.plusDays(8), "SO-B");
        rowA = row("LS-A", "PLAN-A", LINE, LineSchedule.ScheduleStatus.pending, DAY, DAY.plusDays(5));
        rowB = row("LS-B", "PLAN-B", LINE, LineSchedule.ScheduleStatus.pending, DAY.plusDays(6), DAY.plusDays(8));
        runningRow = row("LS-RUN", "PLAN-RUN", LINE, LineSchedule.ScheduleStatus.in_progress, DAY, DAY.plusDays(1));
        otherLineRow = row("LS-OTHER", "PLAN-OTHER", "L2", LineSchedule.ScheduleStatus.pending, DAY, DAY.plusDays(3));
        when(constraints.findLiveEndingFrom(eq(F), any())).thenReturn(List.of(rowA, runningRow, otherLineRow, rowB));
        when(plans.findByIdAndFactoryId("PLAN-A", F)).thenReturn(Optional.of(planA));
        when(plans.findByIdAndFactoryId("PLAN-B", F)).thenReturn(Optional.of(planB));
        when(schedulingPlans.findDayPlanHead(eq(F), any())).thenReturn(Optional.empty());
        when(schedulingPlans.lockDayPlan(eq(F), any())).thenReturn(Optional.empty());
        when(schedulingPlans.save(any(SchedulingPlan.class))).thenAnswer(i -> i.getArgument(0));
        when(schedulingPlans.saveAndFlush(any(SchedulingPlan.class))).thenAnswer(i -> i.getArgument(0));
    }

    // ------------------------------------------------------------------ fixtures

    private static SchedulingPlanRepository.DayPlanHead head(SchedulingPlan p) {
        return new SchedulingPlanRepository.DayPlanHead() {
            @Override public String getId() { return p.getId(); }
            @Override public SchedulingPlan.PlanStatus getStatus() { return p.getStatus(); }
            @Override public String getNotes() { return p.getNotes(); }
        };
    }

    private static ProductionPlan plan(String id, String number, LocalDate due, String orderId) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(F);
        p.setPlanNumber(number);
        p.setExpectedCompletionDate(due);
        p.setSourceOrderId(orderId);
        return p;
    }

    private static LineSchedule row(String id, String planId, String line, LineSchedule.ScheduleStatus status,
                                    LocalDate from, LocalDate to) {
        LineSchedule ls = new LineSchedule();
        ls.setId(id);
        ls.setProductionLineId(line);
        ls.setProductionPlanId(planId);
        ls.setStatus(status);
        ls.setPlannedStartTime(from.atStartOfDay());
        ls.setPlannedEndTime(to.atTime(23, 59));
        ls.setPlannedQuantity(100);
        ls.setAdjustmentCount(0);
        return ls;
    }

    private static PlanResult scheduled(String planId, LocalDate from, int days, String qtyPerDay) {
        List<Day> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            list.add(new Day(from.plusDays(i), new BigDecimal(qtyPerDay), BigDecimal.ONE));
        }
        return new PlanResult(planId, planId, "SCHEDULED", list, List.of(), List.of(), Map.of("unit", "kg"));
    }

    private static PlanResult failed(String planId, String status) {
        return new PlanResult(planId, planId, status, List.of(),
                List.of(new Delay("EQUIPMENT_BUSY", DAY, DAY, "设备被占")), List.of(), null);
    }

    private void engine(PlanResult... results) {
        when(generator.preview(eq(F), any())).thenReturn(new ConstrainedSchedulePreview("SCHEDULED", LINE, DAY, List.of(),
                List.of(results), List.of()));
    }

    /** 插单排在首日 1 天; A 顺延 1 天仍在交期内; B 顺延 4 天突破交期。 */
    private void happyEngine() {
        engine(scheduled("PLAN-INS", DAY, 1, "50"),
                scheduled("PLAN-A", DAY.plusDays(1), 5, "20"),   // 原 DAY+5 完 → 仍 DAY+5 完, 交期 DAY+6
                scheduled("PLAN-B", DAY.plusDays(7), 5, "20"));  // 原 DAY+8 完 → DAY+11 完, 交期 DAY+8
    }

    // ------------------------------------------------------------------ 请求形状

    @Test
    void requestPinsTheInsertFirstAndOnlyMovesNotStartedRowsOnTheSameLine() {
        happyEngine();

        service.apply(F, slot, insert, null, 7L);

        ArgumentCaptor<SchedulePreviewRequest> req = ArgumentCaptor.forClass(SchedulePreviewRequest.class);
        verify(generator).preview(eq(F), req.capture());
        assertThat(req.getValue().planIds()).containsExactly("PLAN-INS", "PLAN-A", "PLAN-B");
        assertThat(req.getValue().pinFirstPlanIds()).containsExactly("PLAN-INS");
        assertThat(req.getValue().movableLineScheduleIds()).containsExactlyInAnyOrder("LS-A", "LS-B")
                .doesNotContain("LS-RUN", "LS-OTHER");
        assertThat(req.getValue().productionLineId()).isEqualTo(LINE);
        assertThat(req.getValue().firstDay()).isEqualTo(DAY);
    }

    // ------------------------------------------------------------------ 批准后排程真的变了

    @Test
    void applyRewritesBumpedRowsCreatesTheInsertRowAndReportsNewCompletionDates() {
        happyEngine();

        Proposal p = service.apply(F, slot, insert, null, 7L);

        // 被挤的 A / B: 起止、数量、按天分配、调整记录都变了
        assertThat(rowA.getPlannedStartTime()).isEqualTo(DAY.plusDays(1).atStartOfDay());
        assertThat(rowA.getPlannedEndTime()).isEqualTo(DAY.plusDays(5).atTime(23, 59));
        assertThat(rowA.getPlannedQuantity()).isEqualTo(100);
        assertThat(rowA.getDailyAllocations()).containsEntry("unit", "kg");
        assertThat(rowA.getAdjustmentCount()).isEqualTo(1);
        assertThat(rowA.getAdjustmentReason()).contains("URGENT-1").contains("顺延");
        assertThat(rowB.getPlannedStartTime()).isEqualTo(DAY.plusDays(7).atStartOfDay());
        assertThat(rowB.getPlannedEndTime()).isEqualTo(DAY.plusDays(11).atTime(23, 59));
        // 不被挤的: 开工的、别的线的一行没动
        assertThat(runningRow.getAdjustmentCount()).isEqualTo(0);
        assertThat(otherLineRow.getPlannedEndTime()).isEqualTo(DAY.plusDays(3).atTime(23, 59));
        verify(lineSchedules, never()).save(runningRow);
        verify(lineSchedules, never()).save(otherLineRow);

        // 插单: 新排程行挂在首日的调度计划下
        ArgumentCaptor<LineSchedule> saved = ArgumentCaptor.forClass(LineSchedule.class);
        verify(lineSchedules, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        LineSchedule created = saved.getAllValues().stream()
                .filter(ls -> "PLAN-INS".equals(ls.getProductionPlanId())).findFirst().orElseThrow();
        assertThat(created.getProductionLineId()).isEqualTo(LINE);
        assertThat(created.getPlannedStartTime()).isEqualTo(DAY.atStartOfDay());
        assertThat(created.getPlannedQuantity()).isEqualTo(50);
        assertThat(created.getPlan()).isNotNull();
        // 计划自己的起止时间不动 (影响分析按它们判重叠); 新完成日以排程行为准
        verify(plans, never()).save(any());

        // 回执: 每个被挤订单新完成日 + 交期是否突破
        assertThat(p.moved()).hasSize(2);
        var a = p.moved().stream().filter(m -> m.planId().equals("PLAN-A")).findFirst().orElseThrow();
        var b = p.moved().stream().filter(m -> m.planId().equals("PLAN-B")).findFirst().orElseThrow();
        assertThat(a.oldEnd()).isEqualTo(DAY.plusDays(5));
        assertThat(a.newEnd()).isEqualTo(DAY.plusDays(5));
        assertThat(a.exceedsDue()).isFalse();
        assertThat(b.oldEnd()).isEqualTo(DAY.plusDays(8));
        assertThat(b.newEnd()).isEqualTo(DAY.plusDays(11));
        assertThat(b.delayDays()).isEqualTo(3);
        assertThat(b.exceedsDue()).as("新完成日晚于计划交期").isTrue();
    }

    @Test
    void onlyOrdersOfPlansThatMovedLaterAreRegisteredWithTheDeliveryRiskLoop() {
        happyEngine();

        service.apply(F, slot, insert, null, 7L);

        ArgumentCaptor<java.util.Collection<String>> orders = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(risk).refreshOrders(eq(F), orders.capture(),
                eq(new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L)));
        assertThat(orders.getValue()).containsExactly("SO-B");
    }

    @Test
    void ordersOfPlansThatMovedLaterAreRegisteredEvenWhenThePlanDueDateIsEmpty() {
        planB.setExpectedCompletionDate(null);   // 计划交期为空, 但订单可能有交期: 交给闭环按订单交期判
        happyEngine();

        service.apply(F, slot, insert, null, 7L);

        verify(risk).refreshOrders(eq(F), org.mockito.ArgumentMatchers.argThat(c -> c.contains("SO-B")), any());
    }

    @Test
    void noBrokenDueDateMeansNoReminderCall() {
        engine(scheduled("PLAN-INS", DAY, 1, "50"),
                scheduled("PLAN-A", DAY.plusDays(1), 5, "20"),
                scheduled("PLAN-B", DAY.plusDays(6), 3, "20"));   // 新完成日 DAY+8, 交期 DAY+8 (不晚于)

        service.apply(F, slot, insert, null, 7L);

        verify(risk, never()).refreshOrders(anyString(), anyCollection(), any());
    }

    @Test
    void reminderFailureDoesNotUndoTheScheduleChange() {
        happyEngine();
        when(risk.refreshOrders(anyString(), anyCollection(), any())).thenThrow(new IllegalStateException("boom"));

        Proposal p = service.apply(F, slot, insert, null, 7L);

        assertThat(p.ok()).isTrue();
        assertThat(rowB.getPlannedEndTime()).isEqualTo(DAY.plusDays(11).atTime(23, 59));
    }

    // ------------------------------------------------------------------ 预览 == 确认

    @Test
    void confirmIsRefusedAndNothingIsWrittenWhenScheduleChangedSincePreview() {
        happyEngine();
        String previewed = service.apply(F, slot, insert, null, 7L).fingerprint();
        org.mockito.Mockito.clearInvocations(lineSchedules, plans, schedulingPlans);
        // 预览之后引擎结果变了: B 多顺延一天
        engine(scheduled("PLAN-INS", DAY, 1, "50"),
                scheduled("PLAN-A", DAY.plusDays(1), 5, "20"),
                scheduled("PLAN-B", DAY.plusDays(8), 5, "20"));

        assertThatThrownBy(() -> service.apply(F, slot, insert, previewed, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("INSERT_PREVIEW_SCHEDULE_CHANGED");
                    assertThat(e.getMessage()).contains("请重新预览");
                });
        verify(lineSchedules, never()).save(any());
        verify(schedulingPlans, never()).save(any());
        verify(plans, never()).save(any());
    }

    @Test
    void sameEngineResultMatchesThePreviewFingerprintAndIsApplied() {
        happyEngine();
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        // 预览 (只算不写) 之后什么都没变: 确认重算出同一个指纹, 通过并落库
        String previewed = service.dryRun(F, slot, insert).fingerprint();

        Proposal again = service.apply(F, slot, insert, previewed, 7L);

        assertThat(again.fingerprint()).isEqualTo(previewed);
    }

    @Test
    void fingerprintMovesWithEveryBumpedPlansDays() {
        happyEngine();
        String base = service.apply(F, slot, insert, null, 7L).fingerprint();
        engine(scheduled("PLAN-INS", DAY, 1, "50"),
                scheduled("PLAN-A", DAY.plusDays(1), 5, "21"),   // 每天多 1
                scheduled("PLAN-B", DAY.plusDays(7), 5, "20"));
        assertThat(service.apply(F, slot, insert, null, 7L).fingerprint()).isNotEqualTo(base);
    }

    // ------------------------------------------------------------------ 排不进去: 拦住, 不写

    @Test
    void insertThatTheEngineCannotScheduleBlocksWithTheReasonAndWritesNothing() {
        engine(failed("PLAN-INS", "EQUIPMENT_UNAVAILABLE"),
                scheduled("PLAN-A", DAY, 3, "20"), scheduled("PLAN-B", DAY, 3, "20"));

        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED");
                    assertThat(e.getMessage()).contains("EQUIPMENT_UNAVAILABLE").contains("设备被占");
                });
        verify(lineSchedules, never()).save(any());
    }

    @Test
    void aBumpedPlanThatNoLongerFitsBlocksInsteadOfSilentlyDroppingItsSchedule() {
        engine(scheduled("PLAN-INS", DAY, 1, "50"), scheduled("PLAN-A", DAY.plusDays(1), 5, "20"),
                failed("PLAN-B", "EXCEEDS_HORIZON"));

        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED");
                    assertThat(e.getMessage()).contains("PB-1").contains("EXCEEDS_HORIZON");
                });
        verify(lineSchedules, never()).save(any());
        assertThat(rowA.getAdjustmentCount()).isEqualTo(0);
    }

    @Test
    void missingDailyCapacityBlocks() {
        when(generator.preview(eq(F), any())).thenReturn(new ConstrainedSchedulePreview("CAPACITY_REQUIRED", LINE, DAY,
                List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("日产能"));
        verify(lineSchedules, never()).save(any());
    }

    @Test
    void closedDayContainerBlocksAtPreviewTimeAndWritesNothing() {
        happyEngine();
        SchedulingPlan closed = new SchedulingPlan();
        closed.setId("SP-CLOSED");
        closed.setStatus(SchedulingPlan.PlanStatus.completed);
        when(schedulingPlans.findDayPlanHead(eq(F), eq(DAY))).thenReturn(Optional.of(head(closed)));
        when(schedulingPlans.lockDayPlan(eq(F), eq(DAY))).thenReturn(Optional.of(closed));
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Proposal preview = service.dryRun(F, slot, insert);

        assertThat(preview.status()).as("预览阶段就拦, 不等确认才失败").isEqualTo(UrgentInsertScheduleService.BLOCKED);
        assertThat(preview.message()).contains("调度计划已完成");
        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED"));
        verify(lineSchedules, never()).save(any());
    }

    // ------------------------------------------------------------------ 已取消的日调度计划: 取消后没有重开入口, 同一天只能有一个

    private SchedulingPlan cancelledDay(LineSchedule... rows) {
        SchedulingPlan cancelled = new SchedulingPlan();
        cancelled.setId("SP-CANCELLED");
        cancelled.setStatus(SchedulingPlan.PlanStatus.cancelled);
        when(schedulingPlans.findDayPlanHead(eq(F), eq(DAY))).thenReturn(Optional.of(head(cancelled)));
        when(schedulingPlans.lockDayPlan(eq(F), eq(DAY))).thenReturn(Optional.of(cancelled));
        when(lineSchedules.findByPlanId("SP-CANCELLED")).thenReturn(List.of(rows));
        // 数据库侧的条件 UPDATE / 现数: 作废几条 = 名下 pending 且没开工的行数; 已开工的现数默认 0
        long pending = java.util.Arrays.stream(rows).filter(r -> r.getStatus() == LineSchedule.ScheduleStatus.pending
                && r.getActualStartTime() == null).count();
        when(schedulingPlans.voidPendingRowsOnReopen(eq("SP-CANCELLED"), anyString())).thenReturn((int) pending);
        when(schedulingPlans.countStartedRows("SP-CANCELLED")).thenReturn(0L);
        when(schedulingPlans.reopenCancelled(eq("SP-CANCELLED"), anyString(), any())).thenReturn(1);
        return cancelled;
    }

    @Test
    void cancelledDayWithOnlyUnstartedRowsIsReopenedForTheInsertAndItsOldRowsStayCancelled() {
        happyEngine();
        LineSchedule oldPending = row("LS-OLD", "PLAN-OLD", LINE, LineSchedule.ScheduleStatus.pending, DAY, DAY.plusDays(1));
        LineSchedule oldDone = row("LS-DONE", "PLAN-DONE", LINE, LineSchedule.ScheduleStatus.completed, DAY, DAY);
        SchedulingPlan cancelled = cancelledDay(oldPending, oldDone);
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Proposal preview = service.dryRun(F, slot, insert);
        assertThat(preview.ok()).as("取消的日调度计划不再把插单堵死").isTrue();
        assertThat(preview.warnings()).anyMatch(w -> w.equals(
                com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl.reopenWarning(1, 0)));
        verify(schedulingPlans, never()).save(any());   // 预览不落库

        service.apply(F, slot, insert, preview.fingerprint(), 7L);

        // 旧未开工排程显式作废 (一条条件 UPDATE, 不经过实体), 然后才改回草稿
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(schedulingPlans);
        order.verify(schedulingPlans).lockDayPlan(F, DAY);
        order.verify(schedulingPlans).voidPendingRowsOnReopen(eq("SP-CANCELLED"), reason.capture());
        order.verify(schedulingPlans).countStartedRows("SP-CANCELLED");
        order.verify(schedulingPlans).reopenCancelled(eq("SP-CANCELLED"), anyString(), any());
        assertThat(reason.getValue()).contains("调度计划取消后重开").contains("URGENT-1");
        assertThat(oldDone.getStatus()).as("已完成的历史排程不动").isEqualTo(LineSchedule.ScheduleStatus.completed);
        ArgumentCaptor<LineSchedule> saved = ArgumentCaptor.forClass(LineSchedule.class);
        verify(lineSchedules, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anyMatch(ls -> ls.getPlan() == cancelled
                && "PLAN-INS".equals(ls.getProductionPlanId()));
    }

    @Test
    void cancelledDayThatStillHasAStartedRowIsNotReopened() {
        happyEngine();
        LineSchedule started = row("LS-RUN", "PLAN-RUN", LINE, LineSchedule.ScheduleStatus.in_progress, DAY, DAY.plusDays(1));
        SchedulingPlan cancelled = cancelledDay(started);
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Proposal preview = service.dryRun(F, slot, insert);

        assertThat(preview.status()).isEqualTo(UrgentInsertScheduleService.BLOCKED);
        assertThat(preview.message()).contains("已取消").contains("已开工");
        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_BLOCKED"));
        assertThat(cancelled.getStatus()).isEqualTo(SchedulingPlan.PlanStatus.cancelled);
        assertThat(started.getStatus()).isEqualTo(LineSchedule.ScheduleStatus.in_progress);
        verify(schedulingPlans, never()).save(any());
        verify(lineSchedules, never()).save(any());
    }

    @Test
    void reopeningLeavesATrailOfWhoWhenAndWhichInsertAndTakesTheRowLockFirst() {
        happyEngine();
        LineSchedule oldPending = row("LS-OLD", "PLAN-OLD", LINE, LineSchedule.ScheduleStatus.pending, DAY, DAY.plusDays(1));
        SchedulingPlan cancelled = cancelledDay(oldPending);
        cancelled.setNotes("原备注");

        service.apply(F, slot, insert, null, 7L);

        verify(schedulingPlans).lockDayPlan(F, DAY);
        ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
        verify(schedulingPlans).reopenCancelled(eq("SP-CANCELLED"), notes.capture(), any());
        assertThat(notes.getValue()).startsWith("原备注\n[重开 ")
                .contains("原调度计划已取消，由紧急插单 URGENT-1重开为草稿（操作人 7）；作废没开工的旧排程 1 条");
        // 与缺口 18 的重开留痕是同一个方法生成的 (去掉时间戳后逐字一致)
        String line = notes.getValue().substring(notes.getValue().indexOf('\n') + 1);
        String shared = com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl
                .reopenTrail(java.time.LocalDateTime.now(), "紧急插单 URGENT-1", 7L, 1, 0);
        String stamp = "^\\[重开 [0-9-]+ [0-9:]+\\]";
        assertThat(line.replaceFirst(stamp, "")).isEqualTo(shared.replaceFirst(stamp, ""));
    }

    @Test
    void anAlreadyUsableDayIsNeverReopenedOrTouched() {
        happyEngine();
        SchedulingPlan draft = new SchedulingPlan();
        draft.setId("SP-DRAFT");
        draft.setStatus(SchedulingPlan.PlanStatus.draft);
        draft.setNotes("原备注");
        when(schedulingPlans.findDayPlanHead(eq(F), eq(DAY))).thenReturn(Optional.of(head(draft)));
        when(schedulingPlans.lockDayPlan(eq(F), eq(DAY))).thenReturn(Optional.of(draft));

        service.apply(F, slot, insert, null, 7L);

        assertThat(draft.getStatus()).isEqualTo(SchedulingPlan.PlanStatus.draft);
        assertThat(draft.getNotes()).isEqualTo("原备注");
        verify(schedulingPlans, never()).save(any());
        verify(schedulingPlans, never()).reopenCancelled(any(), any(), any());
        verify(schedulingPlans, never()).voidPendingRowsOnReopen(any(), any());
    }

    @Test
    void aRowStartedBetweenPreviewAndWriteStopsTheReopenAndNothingIsReopened() {
        happyEngine();
        cancelledDay(row("LS-OLD", "PLAN-OLD", LINE, LineSchedule.ScheduleStatus.pending, DAY, DAY.plusDays(1)));
        // 预览那一刻没有已开工的行 (findByPlanId 看不到), 写入时数据库里现数出现了一条
        when(schedulingPlans.countStartedRows("SP-CANCELLED")).thenReturn(1L);

        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_CONTAINER_CLOSED");
                    assertThat(e.getMessage()).contains("已开工");
                });
        verify(schedulingPlans, never()).reopenCancelled(any(), any(), any());
    }

    @Test
    void ifTheDayPlanIsNoLongerCancelledWhenReopeningThe409TellsTheUserToRePreview() {
        happyEngine();
        cancelledDay(row("LS-OLD", "PLAN-OLD", LINE, LineSchedule.ScheduleStatus.pending, DAY, DAY.plusDays(1)));
        when(schedulingPlans.reopenCancelled(eq("SP-CANCELLED"), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_CONTAINER_RACE"));
    }

    @Test
    void cancelledDayWithADelayedOrStartedRowIsNotReopenedEitherWay() {
        happyEngine();
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        LineSchedule delayed = row("LS-DEL", "PLAN-D", LINE, LineSchedule.ScheduleStatus.delayed, DAY, DAY.plusDays(1));
        SchedulingPlan cancelled = cancelledDay(delayed);
        assertThat(service.dryRun(F, slot, insert).status()).isEqualTo(UrgentInsertScheduleService.BLOCKED);

        // 状态还是 pending 但已经有实际开工时间: 同样当成开了工
        LineSchedule startedPending = row("LS-SP", "PLAN-SP", LINE, LineSchedule.ScheduleStatus.pending, DAY, DAY.plusDays(1));
        startedPending.setActualStartTime(DAY.atTime(8, 0));
        when(lineSchedules.findByPlanId("SP-CANCELLED")).thenReturn(List.of(startedPending));
        Proposal second = service.dryRun(F, slot, insert);
        assertThat(second.status()).isEqualTo(UrgentInsertScheduleService.BLOCKED);
        assertThat(second.message()).contains("已开工");
        assertThat(cancelled.getStatus()).isEqualTo(SchedulingPlan.PlanStatus.cancelled);
    }

    @Test
    void cancelledRowsAndCompletedRowsUnderACancelledDayDoNotBlockTheReopen() {
        happyEngine();
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        LineSchedule alreadyCancelled = row("LS-X", "PLAN-X", LINE, LineSchedule.ScheduleStatus.cancelled, DAY, DAY);
        alreadyCancelled.setActualStartTime(DAY.atTime(8, 0));   // 开过工但已取消: 不算在做
        LineSchedule done = row("LS-DONE", "PLAN-DONE", LINE, LineSchedule.ScheduleStatus.completed, DAY, DAY);
        done.setActualStartTime(DAY.atTime(6, 0));
        cancelledDay(alreadyCancelled, done);

        assertThat(service.dryRun(F, slot, insert).ok()).isTrue();
    }

    // ------------------------------------------------------------------ 预览 == 确认: 预览之后有人手工改了被挤计划的排程

    @Test
    void manualEditOfAMovedPlansRowAfterThePreviewMakesTheConfirmRefuse() {
        happyEngine();
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        Proposal preview = service.dryRun(F, slot, insert);
        assertThat(preview.ok()).isTrue();
        org.mockito.Mockito.clearInvocations(lineSchedules, plans, schedulingPlans);

        // 预览之后: 有人把被挤计划 A 的结束日手工改晚了一天 (引擎不读已存日期, 单看引擎结果看不出来)
        rowA.setPlannedEndTime(rowA.getPlannedEndTime().plusDays(1));

        assertThatThrownBy(() -> service.apply(F, slot, insert, preview.fingerprint(), 7L))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("INSERT_PREVIEW_SCHEDULE_CHANGED"));
        verify(lineSchedules, never()).save(any());
        verify(schedulingPlans, never()).save(any());
    }

    @Test
    void creatingTheDayPlanRacingAnotherCreatorIsA409NotA500AtCommit() {
        happyEngine();
        when(schedulingPlans.saveAndFlush(any(SchedulingPlan.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_scheduling_plans_factory_date"));

        assertThatThrownBy(() -> service.apply(F, slot, insert, null, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("INSERT_SCHEDULE_CONTAINER_RACE");
                });
        verify(lineSchedules, never()).save(org.mockito.ArgumentMatchers.argThat(
                ls -> "PLAN-INS".equals(ls.getProductionPlanId())));
    }

    @Test
    void theSameStoredStateGivesTheSameFingerprintAtPreviewAndConfirm() {
        happyEngine();
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        Proposal preview = service.dryRun(F, slot, insert);

        Proposal applied = service.apply(F, slot, insert, preview.fingerprint(), 7L);

        assertThat(applied.fingerprint()).isEqualTo(preview.fingerprint());
    }

    // ------------------------------------------------------------------ 提示文字是给人看的

    @Test
    void missingDailyCapacityIsExplainedInWordsNotAsAJavaObject() {
        when(generator.preview(eq(F), any())).thenReturn(new ConstrainedSchedulePreview("NEEDS_CAPACITY", LINE, DAY,
                List.of(com.cretas.aims.dto.scheduling.DailyCapacityRequiredDTO.builder()
                        .productTypeId("PT-9").productName("牛肉丸").unit("kg").reason("MISSING")
                        .planNumbers(List.of("PA-1", "URGENT-1")).build()),
                List.of(), List.of()));
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Proposal p = service.dryRun(F, slot, insert);

        assertThat(p.status()).isEqualTo(UrgentInsertScheduleService.BLOCKED);
        assertThat(p.message()).contains("牛肉丸").contains("kg").contains("没填日产能").contains("PA-1、URGENT-1")
                .doesNotContain("DailyCapacityRequiredDTO").doesNotContain("productTypeId=").doesNotContain("PT-9")
                .doesNotContain("reason=").doesNotContain("jointPlan");
    }

    // ------------------------------------------------------------------ 谁能被挤: 只有整个计划都没开工、都在这条线上

    private java.util.Collection<String> movableIdsOfTheRequest() {
        ArgumentCaptor<SchedulePreviewRequest> req = ArgumentCaptor.forClass(SchedulePreviewRequest.class);
        verify(generator).preview(eq(F), req.capture());
        return req.getValue().movableLineScheduleIds();
    }

    @Test
    void aDelayedRowIsAStartedRowAndItsPlanIsNeverMoved() {
        // delayed 只会由已开工 (in_progress) 的行超时变来, 带实际开工时间和已完成量
        LineSchedule delayed = row("LS-DELAYED", "PLAN-D", LINE, LineSchedule.ScheduleStatus.delayed, DAY, DAY.plusDays(4));
        delayed.setActualStartTime(DAY.atTime(8, 0));
        delayed.setCompletedQuantity(40);
        when(constraints.findLiveEndingFrom(eq(F), any())).thenReturn(List.of(rowA, delayed, rowB));
        engine(scheduled("PLAN-INS", DAY, 1, "50"), scheduled("PLAN-A", DAY.plusDays(1), 5, "20"),
                scheduled("PLAN-B", DAY.plusDays(6), 3, "20"));

        service.apply(F, slot, insert, null, 7L);

        assertThat(movableIdsOfTheRequest()).containsExactlyInAnyOrder("LS-A", "LS-B").doesNotContain("LS-DELAYED");
        assertThat(delayed.getPlannedEndTime()).isEqualTo(DAY.plusDays(4).atTime(23, 59));
        assertThat(delayed.getAdjustmentCount()).isEqualTo(0);
    }

    @Test
    void aPlanWithOneStartedRowAndOnePendingRowIsNotMovedAtAll() {
        LineSchedule started = row("LS-P1", "PLAN-MIX", LINE, LineSchedule.ScheduleStatus.in_progress, DAY, DAY.plusDays(1));
        LineSchedule pending = row("LS-P2", "PLAN-MIX", LINE, LineSchedule.ScheduleStatus.pending, DAY.plusDays(2), DAY.plusDays(3));
        when(constraints.findLiveEndingFrom(eq(F), any())).thenReturn(List.of(started, pending, rowA));
        engine(scheduled("PLAN-INS", DAY, 1, "50"), scheduled("PLAN-A", DAY.plusDays(1), 5, "20"));

        service.apply(F, slot, insert, null, 7L);

        assertThat(movableIdsOfTheRequest()).containsExactly("LS-A");
    }

    @Test
    void aPlanWithARowOnAnotherLineIsNotMoved() {
        LineSchedule here = row("LS-H", "PLAN-TWO", LINE, LineSchedule.ScheduleStatus.pending, DAY.plusDays(1), DAY.plusDays(2));
        LineSchedule there = row("LS-T", "PLAN-TWO", "L2", LineSchedule.ScheduleStatus.pending, DAY.plusDays(1), DAY.plusDays(2));
        when(constraints.findLiveEndingFrom(eq(F), any())).thenReturn(List.of(here, there, rowA));
        engine(scheduled("PLAN-INS", DAY, 1, "50"), scheduled("PLAN-A", DAY.plusDays(1), 5, "20"));

        service.apply(F, slot, insert, null, 7L);

        assertThat(movableIdsOfTheRequest()).containsExactly("LS-A");
    }

    @Test
    void slotWithoutALineIsNotEvaluatedNeverGuessed() {
        InsertSlot noLine = InsertSlot.builder().id("S2").factoryId(F).startTime(DAY.atTime(9, 0)).build();

        assertThatThrownBy(() -> service.apply(F, noLine, insert, null, 7L))
                .isInstanceOf(BusinessException.class);
        verify(generator, never()).preview(anyString(), any());
    }

    // ------------------------------------------------------------------ 预览 dry-run

    @Test
    void dryRunAlwaysRollsBackItsTemporaryPlan() {
        happyEngine();
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Proposal p = service.dryRun(F, slot, insert);

        assertThat(p.ok()).isTrue();
        verify(txStatus).setRollbackOnly();
        verify(lineSchedules, never()).save(any());
        verify(schedulingPlans, never()).save(any());
    }

    @Test
    void dryRunTurnsEngineBusinessErrorsIntoBlockedWithTheMessage() {
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(generator.preview(eq(F), any())).thenThrow(new BusinessException(409, "产线不存在"));

        Proposal p = service.dryRun(F, slot, insert);

        assertThat(p.status()).isEqualTo(UrgentInsertScheduleService.BLOCKED);
        assertThat(p.message()).contains("产线不存在");
        verify(txStatus).setRollbackOnly();
    }

    @Test
    void unexpectedEngineFailureIsNotEvaluatedNotBlockedAndNotOk() {
        when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(generator.preview(eq(F), any())).thenThrow(new IllegalStateException("db down"));

        Proposal p = service.dryRun(F, slot, insert);

        assertThat(p.status()).isEqualTo(UrgentInsertScheduleService.NOT_EVALUATED);
        assertThat(p.fingerprint()).isNull();
    }

    @SuppressWarnings("unused")
    private static Set<String> unused() {
        return Set.of();
    }
}
