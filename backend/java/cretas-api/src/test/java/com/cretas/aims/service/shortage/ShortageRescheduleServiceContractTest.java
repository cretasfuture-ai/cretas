package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.shortage.RescheduleProposal;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseAction;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.RescheduleProposalRepository;
import com.cretas.aims.repository.shortage.ShortageCaseActionRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.repository.shortage.ShortageScheduleQueryRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.ScheduleLineLocks;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.entity.SchedulingPlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缺料实例的局部重排 (S4-03 局部 / 04 / 05): 按到货次日顺延或换线; 生成后排程被改、到货变了、
 * 时段被占了, 应用都整体 409 且一行不写; 到货日未知不给顺延方案。
 */
class ShortageRescheduleServiceContractTest {

    private static final ActorRef PLANNER = new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L);
    private static final LocalDate TODAY = LocalDate.now();

    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseLineRepository lines = mock(ShortageCaseLineRepository.class);
    private final ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
    private final ShortageCaseActionRepository actions = mock(ShortageCaseActionRepository.class);
    private final RescheduleProposalRepository proposals = mock(RescheduleProposalRepository.class);
    private final ShortageScheduleQueryRepository schedules = mock(ShortageScheduleQueryRepository.class);
    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
    private final ProductionLineRepository productionLines = mock(ProductionLineRepository.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final LineCapacityEquipmentCheck capacity = mock(LineCapacityEquipmentCheck.class);
    private final ScheduleLineLocks lineLocks = mock(ScheduleLineLocks.class);
    private final UnitContractService units = mock(UnitContractService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private ShortageRescheduleService service;

    private ShortageCase theCase;
    private SalesOrder order;
    private LineSchedule task;
    private ProductionLine l1;
    private ProductionLine l2;
    private ProductionLine l3;
    private ProductionPlan pp;
    private Object[] arrival;                         // [最晚到货日, 没填日期的单数, 单数]
    private final Set<String> busyLines = new HashSet<>();
    private final Map<String, RescheduleProposal> stored = new HashMap<>();
    private final List<ShortageCaseAction> savedActions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ShortageRescheduleService(cases, lines, events, actions, proposals, schedules, plans,
                productionLines, orders, capacity, lineLocks, units, entityManager);

        theCase = new ShortageCase();
        theCase.setId("C1");
        theCase.setFactoryId("F1");
        theCase.setSourceType(ShortageCase.SourceType.SALES_ORDER);
        theCase.setSourceId("SO-1");
        theCase.setStage(Stage.DETECTED);
        when(cases.findByIdAndFactoryId("C1", "F1")).thenReturn(Optional.of(theCase));
        when(cases.findOpenForUpdate("F1", ShortageCase.SourceType.SALES_ORDER, "SO-1"))
                .thenAnswer(i -> theCase.getClosedAt() == null ? Optional.of(theCase) : Optional.empty());
        when(cases.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        ShortageCaseLine gap = new ShortageCaseLine();
        gap.setCaseId("C1");
        gap.setMaterialTypeId("M1");
        gap.setMaterialName("猪大骨");
        gap.setUnit("kg");
        gap.setGapQty(new BigDecimal("5"));
        when(lines.findByCaseIdOrderByIdAsc("C1")).thenReturn(List.of(gap));

        order = new SalesOrder();
        order.setId("SO-1");
        order.setFactoryId("F1");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        order.setRequiredDeliveryDate(TODAY.plusDays(10));
        when(orders.findById("SO-1")).thenReturn(Optional.of(order));

        arrival = new Object[]{TODAY.plusDays(3), 0L, 1L};   // 第 3 天到货 ⇒ 第 4 天才能开工
        when(schedules.findLiveOrderArrival("F1", "M1")).thenAnswer(i -> List.<Object[]>of(arrival));

        task = new LineSchedule();
        task.setId("S1");
        task.setProductionLineId("L1");
        task.setProductionPlanId("PP-1");
        task.setPlannedStartTime(TODAY.plusDays(2).atTime(8, 0));
        task.setPlannedEndTime(TODAY.plusDays(2).atTime(16, 0));
        task.setStatus(LineSchedule.ScheduleStatus.pending);
        task.setUpdatedAt(LocalDateTime.now().minusHours(1));
        task.setAdjustmentCount(0);
        task.setDailyAllocations(new LinkedHashMap<>(Map.of(
                "unit", "KG",
                "capacityPerDay", new BigDecimal("100"),
                "capacitySource", "MANUAL",
                "plannedQuantity", new BigDecimal("100"),
                "reportedQuantity", new BigDecimal("0"),
                "remainingQuantity", new BigDecimal("100"),
                "days", List.of(new LinkedHashMap<>(Map.of(
                        "date", TODAY.plusDays(2).toString(), "quantity", new BigDecimal("100"), "shareOfDay", 1))))));
        when(schedules.findNotStartedForSalesOrder("F1", "SO-1")).thenAnswer(i -> List.of(task));
        when(schedules.lockByIds(eq("F1"), anyCollection())).thenAnswer(i -> List.of(task));
        when(schedules.findOverlapping(eq("F1"), anyString(), any(), any(), anyCollection()))
                .thenAnswer(i -> busyLines.contains((String) i.getArgument(1)) ? List.of(new LineSchedule()) : List.of());

        l1 = line("L1", "一号线");
        l2 = line("L2", "二号线");
        l3 = line("L3", "三号线");
        when(productionLines.findByFactoryIdAndDeletedAtIsNull("F1")).thenAnswer(i -> List.of(l1, l2, l3));

        pp = new ProductionPlan();
        pp.setId("PP-1");
        pp.setFactoryId("F1");
        pp.setPlanNumber("PP-NO-1");
        pp.setProductTypeId("P1");
        pp.setPlannedQuantity(new BigDecimal("100"));
        pp.setPlannedUnit("KG");
        pp.setWorkflowSelectionMode(ProductionBatch.WorkflowSelectionMode.WORKFLOW);
        pp.setWorkflowOutputUnitsByProduct(new LinkedHashMap<>(Map.of("P1", "KG")));
        pp.setSelectedWorkflowId(377L);
        pp.setSelectedWorkflowVersion(1);
        pp.setSelectedWorkflowRevisionId(541L);
        pp.setSelectedWorkflowRevisionHash("workflow-revision-hash");
        pp.setSelectedBomRecipeId("BOM-395");
        pp.setSelectedBomVersion(1);
        when(plans.findByIdAndFactoryId("PP-1", "F1")).thenReturn(Optional.of(pp));
        when(plans.findByIdForUpdate("PP-1")).thenReturn(Optional.of(pp));
        when(capacity.checkCapacity(eq("F1"), eq("P1"), any(), eq("KG"), eq("L2"), any(), any()))
                .thenReturn(ResourceCheckResult.available("CAPACITY", new BigDecimal("200"), new BigDecimal("100"), "二号线够"));

        when(proposals.saveAndFlush(any())).thenAnswer(i -> {
            RescheduleProposal p = i.getArgument(0);
            stored.put(p.getId(), p);
            return p;
        });
        when(proposals.findByIdAndFactoryIdAndCaseId(anyString(), eq("F1"), eq("C1")))
                .thenAnswer(i -> Optional.ofNullable(stored.get((String) i.getArgument(0))));
        when(actions.saveAndFlush(any())).thenAnswer(i -> {
            savedActions.add(i.getArgument(0));
            return i.getArgument(0);
        });
    }

    private static ProductionLine line(String id, String name) {
        ProductionLine l = new ProductionLine();
        l.setId(id);
        l.setName(name);
        l.setStatus(ProductionLine.LineStatus.active);
        return l;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> candidate(RescheduleProposal p, String key) {
        return p.getCandidates().stream().filter(c -> key.equals(c.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMove(Map<String, Object> candidate) {
        return ((List<Map<String, Object>>) candidate.get("moves")).get(0);
    }

    @Test
    void theSameLineCandidateMovesTheTaskToTheDayAfterArrivalKeepingItsLength() {
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);

        Map<String, Object> same = candidate(p, ShortageRescheduleService.SAME_LINE);
        assertThat(same.get("applicable")).isEqualTo(true);
        Map<String, Object> m = firstMove(same);
        assertThat(m.get("toLineId")).isEqualTo("L1");
        assertThat(m.get("toStart")).isEqualTo(TODAY.plusDays(4).atTime(8, 0).toString());
        assertThat(m.get("toEnd")).isEqualTo(TODAY.plusDays(4).atTime(16, 0).toString());
        assertThat(p.getBaseline()).singleElement().satisfies(b -> assertThat(b.get("scheduleId")).isEqualTo("S1"));
        assertThat(task.getPlannedStartTime()).isEqualTo(TODAY.plusDays(2).atTime(8, 0));   // 预览不改排程
        assertThat(candidate(p, ShortageRescheduleService.MANUAL).get("applicable")).isEqualTo(false);
    }

    @Test
    void applyingWritesTheTaskRecordsTheLedgerAndMovesTheCaseToPlanned() {
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);

        service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER);

        assertThat(task.getProductionLineId()).isEqualTo("L1");
        assertThat(task.getPlannedStartTime()).isEqualTo(TODAY.plusDays(4).atTime(8, 0));
        assertThat(task.getPlannedEndTime()).isEqualTo(TODAY.plusDays(4).atTime(16, 0));
        assertThat(task.getAdjustmentCount()).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(RescheduleProposal.Status.APPLIED);
        assertThat(savedActions).singleElement().satisfies(a -> {
            assertThat(a.getActionType()).isEqualTo(ShortageCaseAction.ActionType.RESCHEDULE_APPLIED);
            assertThat(a.getResultRef()).isEqualTo(p.getId());
            assertThat(a.getActorId()).isEqualTo(7L);
        });
        assertThat(theCase.getStage()).isEqualTo(Stage.PLANNED);
        assertThatThrownBy(() -> service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("应用过");
    }

    @Test
    void aTaskChangedAfterTheProposalMakesTheWholeApplyStaleWithNothingWritten() {
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);
        task.setUpdatedAt(LocalDateTime.now());                 // 别人改过这条排程

        assertThatThrownBy(() -> service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("方案已过期");

        assertThat(task.getPlannedStartTime()).isEqualTo(TODAY.plusDays(2).atTime(8, 0));
        assertThat(task.getAdjustmentCount()).isZero();
        assertThat(p.getStatus()).isEqualTo(RescheduleProposal.Status.OPEN);
        verify(actions, never()).saveAndFlush(any());
        assertThat(theCase.getStage()).isEqualTo(Stage.DETECTED);
    }

    @Test
    void arrivalOrSlotChangesAfterTheProposalAreCaughtByTheRecheck() {
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);
        arrival[0] = TODAY.plusDays(5);                          // 供应商改了到货日

        assertThatThrownBy(() -> service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("方案已过期");

        arrival[0] = TODAY.plusDays(3);
        busyLines.add("L1");                                     // 那个时段被别的任务占了
        assertThatThrownBy(() -> service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("方案已过期");

        assertThat(task.getAdjustmentCount()).isZero();
        verify(actions, never()).saveAndFlush(any());
    }

    @Test
    void whenTheLineIsBusyTheOtherLineCandidateMovesToAFreeLineWithCapacity() {
        busyLines.add("L1");

        RescheduleProposal p = service.propose("F1", "C1", PLANNER);

        assertThat(candidate(p, ShortageRescheduleService.SAME_LINE).get("applicable")).isEqualTo(false);
        Map<String, Object> other = candidate(p, ShortageRescheduleService.OTHER_LINE);
        assertThat(other.get("applicable")).isEqualTo(true);
        assertThat(firstMove(other).get("toLineId")).isEqualTo("L2");

        service.apply("F1", "C1", p.getId(), ShortageRescheduleService.OTHER_LINE, PLANNER);
        assertThat(task.getProductionLineId()).isEqualTo("L2");
    }

    @Test
    void anUnknownArrivalDateGivesNoPostponeCandidate() {
        arrival = new Object[]{TODAY.plusDays(3), 1L, 2L};       // 两张在走的单, 一张没填到货日

        RescheduleProposal p = service.propose("F1", "C1", PLANNER);

        assertThat(p.getCandidates()).allSatisfy(c -> assertThat(c.get("applicable")).isEqualTo(false));
        assertThat(candidate(p, ShortageRescheduleService.SAME_LINE).get("notes").toString())
                .contains("到货日未知").contains("没填预计到货日");
    }

    @Test
    void sameLineCandidateRequiresAFactoryScopedProductionPlanAndFormalSnapshots() {
        when(plans.findByIdAndFactoryId("PP-1", "F1")).thenReturn(Optional.empty());
        RescheduleProposal missingPlan = service.propose("F1", "C1", PLANNER);
        assertThat(candidate(missingPlan, ShortageRescheduleService.SAME_LINE).get("applicable")).isEqualTo(false);
        assertThat(candidate(missingPlan, ShortageRescheduleService.SAME_LINE).get("notes").toString())
                .contains("生产计划不存在");

        when(plans.findByIdAndFactoryId("PP-1", "F1")).thenReturn(Optional.of(pp));
        pp.setSelectedWorkflowRevisionHash(null);
        RescheduleProposal missingWorkflow = service.propose("F1", "C1", PLANNER);
        assertThat(candidate(missingWorkflow, ShortageRescheduleService.SAME_LINE).get("applicable")).isEqualTo(false);
        assertThat(candidate(missingWorkflow, ShortageRescheduleService.SAME_LINE).get("notes").toString())
                .contains("Workflow revision");
    }

    @Test
    void sameLineCandidateBlocksMissingOrMismatchedDailyCapacityWithoutUnitGuessing() {
        task.setDailyAllocations(null);
        RescheduleProposal missing = service.propose("F1", "C1", PLANNER);
        assertThat(candidate(missing, ShortageRescheduleService.SAME_LINE).get("applicable")).isEqualTo(false);
        assertThat(candidate(missing, ShortageRescheduleService.SAME_LINE).get("notes").toString())
                .contains("APS 日产能依据");

        task.setDailyAllocations(new LinkedHashMap<>(Map.of(
                "unit", "袋", "capacityPerDay", new BigDecimal("100"), "capacitySource", "MANUAL",
                "days", List.of(Map.of("date", TODAY.plusDays(2).toString(), "quantity", 100, "shareOfDay", 1)))));
        RescheduleProposal mismatched = service.propose("F1", "C1", PLANNER);
        assertThat(candidate(mismatched, ShortageRescheduleService.SAME_LINE).get("applicable")).isEqualTo(false);
        assertThat(candidate(mismatched, ShortageRescheduleService.SAME_LINE).get("notes").toString())
                .contains("不能猜测袋/盒换算");
    }

    @Test
    void crossFactoryPlanIsNotAcceptedAsRescheduleBasis() {
        pp.setFactoryId("F2");
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);
        Map<String, Object> same = candidate(p, ShortageRescheduleService.SAME_LINE);
        assertThat(same.get("applicable")).isEqualTo(false);
        assertThat(same.get("notes").toString()).contains("不属于本厂");
    }

    @Test
    void basisDriftAfterProposalMakesApplyStaleBeforeAnyWrite() {
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);
        task.getDailyAllocations().put("capacityPerDay", new BigDecimal("101"));

        assertThatThrownBy(() -> service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("排程依据");
        assertThat(task.getAdjustmentCount()).isZero();
        verify(actions, never()).saveAndFlush(any());
    }

    @Test
    void invalidApsDayDoesNotExposeAConcreteMove() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) task.getDailyAllocations().get("days");
        days.get(0).put("quantity", new BigDecimal("101"));

        Map<String, Object> same = candidate(service.propose("F1", "C1", PLANNER), ShortageRescheduleService.SAME_LINE);

        assertThat(same.get("applicable")).isEqualTo(false);
        assertThat(same.get("moves")).isEqualTo(List.of());
        assertThat(same.get("notes").toString()).contains("日量或占比");
    }

    @Test
    void refreshAfterThePlanLockDetectsAConcurrentChangeHiddenByTheManagedInstance() {
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);
        LocalDateTime start = task.getPlannedStartTime();
        LocalDateTime end = task.getPlannedEndTime();
        clearInvocations(proposals, actions, events);
        // Both repository reads return the same managed instance. Only a refresh
        // exposes the value committed between the scoped read and lock acquisition.
        doAnswer(i -> {
            pp.setPlannedQuantity(new BigDecimal("101"));
            return null;
        }).when(entityManager).refresh(same(pp), eq(LockModeType.PESSIMISTIC_WRITE));

        assertThatThrownBy(() -> service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("排程依据");

        verify(entityManager).refresh(same(pp), eq(LockModeType.PESSIMISTIC_WRITE));
        assertThat(task.getPlannedStartTime()).isEqualTo(start);
        assertThat(task.getPlannedEndTime()).isEqualTo(end);
        assertThat(task.getAdjustmentCount()).isZero();
        assertThat(p.getAppliedAt()).isNull();
        verify(proposals, never()).saveAndFlush(any());
        verify(actions, never()).saveAndFlush(any());
        verify(events, never()).save(any());
    }

    @Test
    void refreshedPlanWithoutTheExactFactoryIsRejectedBeforeAnyWrite() {
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);
        clearInvocations(proposals, actions, events);
        doAnswer(i -> {
            pp.setFactoryId(null);
            return null;
        }).when(entityManager).refresh(same(pp), eq(LockModeType.PESSIMISTIC_WRITE));

        assertThatThrownBy(() -> service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不属于本厂");

        assertThat(task.getAdjustmentCount()).isZero();
        assertThat(p.getAppliedAt()).isNull();
        verify(proposals, never()).saveAndFlush(any());
        verify(actions, never()).saveAndFlush(any());
        verify(events, never()).save(any());
    }

    @Test
    void allocatedRawMaterialsAreNotSubtractedAgainFromPartiallyReportedOutput() {
        pp.setAllocatedQuantity(new BigDecimal("30"));
        assertThat(pp.getRemainingQuantity()).isEqualByComparingTo("70");
        task.setPlannedQuantity(80);
        task.getDailyAllocations().put("reportedQuantity", new BigDecimal("20"));
        task.getDailyAllocations().put("remainingQuantity", new BigDecimal("80"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) task.getDailyAllocations().get("days");
        days.get(0).put("quantity", new BigDecimal("80"));
        days.get(0).put("shareOfDay", new BigDecimal("0.8"));

        RescheduleProposal p = service.propose("F1", "C1", PLANNER);
        assertThat(candidate(p, ShortageRescheduleService.SAME_LINE).get("applicable")).isEqualTo(true);
        service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER);
        assertThat(task.getAdjustmentCount()).isEqualTo(1);
    }

    @Test
    void invalidScheduleWindowDoesNotExposeAConcreteMove() {
        task.setPlannedEndTime(task.getPlannedStartTime());

        Map<String, Object> same = candidate(service.propose("F1", "C1", PLANNER), ShortageRescheduleService.SAME_LINE);

        assertThat(same.get("applicable")).isEqualTo(false);
        assertThat(same.get("moves")).isEqualTo(List.of());
        assertThat(same.get("notes").toString()).contains("日期窗口");
    }

    @Test
    void familyBasisChangeMakesApplyStaleWithoutWriting() {
        pp.setSelectedBomFamilyId("FAMILY-395");
        pp.setSelectedBomRecipeIdsByProduct(new LinkedHashMap<>(Map.of("P1", "BOM-395")));
        pp.setSelectedBomVersionsByProduct(new LinkedHashMap<>(Map.of("P1", 1)));
        RescheduleProposal proposal = service.propose("F1", "C1", PLANNER);
        pp.getSelectedBomRecipeIdsByProduct().put("P1", "BOM-396");

        assertThatThrownBy(() -> service.apply("F1", "C1", proposal.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("排程依据");
        assertThat(task.getAdjustmentCount()).isZero();
        verify(actions, never()).saveAndFlush(any());
    }

    @Test
    void aLateFinishAgainstTheRequiredDeliveryDateIsSpelledOut() {
        order.setRequiredDeliveryDate(TODAY.plusDays(3));

        RescheduleProposal p = service.propose("F1", "C1", PLANNER);

        assertThat(candidate(p, ShortageRescheduleService.SAME_LINE).get("notes").toString())
                .contains("会延误 1 天");
    }

    @Test
    void anExpiredProposalOrACancelledOrderIsRefused() {
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);
        p.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("过期");

        order.setStatus(SalesOrderStatus.CANCELLED);
        assertThatThrownBy(() -> service.propose("F1", "C1", PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已取消");
        assertThat(task.getAdjustmentCount()).isZero();
    }

    @Test
    void applyShiftsTheApsDailyAllocationAndTimelineByTheSameDelta() {
        Map<String, Object> shiftedDaily = new LinkedHashMap<>();
        shiftedDaily.put("unit", "KG");
        shiftedDaily.put("capacityPerDay", new BigDecimal("100"));
        shiftedDaily.put("capacitySource", "MANUAL");
        shiftedDaily.put("plannedQuantity", new BigDecimal("100"));
        shiftedDaily.put("reportedQuantity", new BigDecimal("0"));
        shiftedDaily.put("remainingQuantity", new BigDecimal("100"));
        Map<String, Object> dayOne = new LinkedHashMap<>();
        dayOne.put("date", TODAY.plusDays(2).toString());
        dayOne.put("quantity", new BigDecimal("60"));
        dayOne.put("shareOfDay", new BigDecimal("0.6"));
        Map<String, Object> dayTwo = new LinkedHashMap<>();
        dayTwo.put("date", TODAY.plusDays(3).toString());
        dayTwo.put("quantity", new BigDecimal("40"));
        dayTwo.put("shareOfDay", new BigDecimal("0.4"));
        shiftedDaily.put("days", List.of(dayOne, dayTwo));
        task.setDailyAllocations(shiftedDaily);
        task.setPlannedEndTime(TODAY.plusDays(3).atTime(16, 0));
        task.setTaskTimeline(List.of(new LinkedHashMap<>(Map.of("taskName", "解冻",
                "start", TODAY.plusDays(2).atTime(8, 0).toString(), "end", TODAY.plusDays(2).atTime(10, 0).toString()))));
        SchedulingPlan sp = new SchedulingPlan();
        sp.setPlanDate(TODAY.plusDays(2));
        task.setPlan(sp);
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);

        service.apply("F1", "C1", p.getId(), ShortageRescheduleService.SAME_LINE, PLANNER);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) task.getDailyAllocations().get("days");
        assertThat(days).extracting(d -> d.get("date"))
                .containsExactly(TODAY.plusDays(4).toString(), TODAY.plusDays(5).toString());
        assertThat(days).extracting(d -> d.get("quantity"))
                .containsExactly(new BigDecimal("60"), new BigDecimal("40"));
        assertThat(task.getTaskTimeline().get(0).get("start")).isEqualTo(TODAY.plusDays(4).atTime(8, 0).toString());
        assertThat(task.getTaskTimeline().get(0).get("end")).isEqualTo(TODAY.plusDays(4).atTime(10, 0).toString());
        assertThat(sp.getPlanDate()).isEqualTo(TODAY.plusDays(2));                    // 排程计划日期不改
        assertThat(savedActions.get(0).getReason()).contains("移出了所属排程计划的日期");
    }

    @Test
    void twoMovedTasksAreNeverPutIntoTheSameSlotOnOneLine() {
        LineSchedule second = new LineSchedule();
        second.setId("S2");
        second.setProductionLineId("L3");
        second.setProductionPlanId("PP-1");
        second.setPlannedStartTime(TODAY.plusDays(2).atTime(8, 0));
        second.setPlannedEndTime(TODAY.plusDays(2).atTime(16, 0));
        second.setStatus(LineSchedule.ScheduleStatus.pending);
        second.setUpdatedAt(LocalDateTime.now().minusHours(1));
        second.setDailyAllocations(new LinkedHashMap<>(task.getDailyAllocations()));
        when(schedules.findNotStartedForSalesOrder("F1", "SO-1")).thenAnswer(i -> List.of(task, second));
        when(capacity.checkCapacity(eq("F1"), eq("P1"), any(), eq("KG"), anyString(), any(), any()))
                .thenReturn(ResourceCheckResult.available("CAPACITY", new BigDecimal("200"), new BigDecimal("100"), "够"));
        busyLines.add("L1");                                     // 两个任务原来的线都被占了, 只剩二号线空着
        busyLines.add("L3");

        RescheduleProposal p = service.propose("F1", "C1", PLANNER);

        Map<String, Object> other = candidate(p, ShortageRescheduleService.OTHER_LINE);
        assertThat(other.get("applicable")).isEqualTo(false);   // 第二个任务不能再塞进二号线同一时段
        assertThat(other.get("notes").toString()).contains("二号线: 该时段已有任务");
    }

    @Test
    void sourceAndTargetLinesAreLockedSortedBeforeTheTasksAreReread() {
        busyLines.add("L1");
        RescheduleProposal p = service.propose("F1", "C1", PLANNER);

        service.apply("F1", "C1", p.getId(), ShortageRescheduleService.OTHER_LINE, PLANNER);

        InOrder order = inOrder(lineLocks, schedules);
        order.verify(lineLocks).lock("F1", new java.util.TreeSet<>(Set.of("L1", "L2")));
        order.verify(schedules).lockByIds(eq("F1"), anyCollection());
    }

    @Test
    void theCapacityCheckUsesTheTasksOwnQuantityAndMapsTheStoredUnit() {
        busyLines.add("L1");
        task.setPlannedQuantity(40);
        pp.setPlannedUnit("公斤");
        task.getDailyAllocations().put("unit", "公斤");
        when(units.areEquivalent("F1", "公斤", "kg")).thenReturn(true);
        // 原样「公斤」也给一个结果: 若没映射成 KG, 流程照常跑完, 由下面的 verify 抓出 (而不是 NPE)
        when(capacity.checkCapacity(eq("F1"), eq("P1"), any(), eq("公斤"), anyString(), any(), any()))
                .thenReturn(ResourceCheckResult.unverified("CAPACITY", "产能未验证", new BigDecimal("40")));

        service.propose("F1", "C1", PLANNER);
        verify(capacity).checkCapacity(eq("F1"), eq("P1"), argThat(q -> q.compareTo(new BigDecimal("40")) == 0),
                eq("KG"), eq("L2"), any(), any());

        pp.setPlannedUnit("盒");                                  // 包装单位: 不猜, 原样交给产能核对判未验证
        task.getDailyAllocations().put("unit", "盒");
        when(capacity.checkCapacity(eq("F1"), eq("P1"), any(), eq("盒"), anyString(), any(), any()))
                .thenReturn(ResourceCheckResult.unverified("CAPACITY", "产能未验证", new BigDecimal("40")));
        service.propose("F1", "C1", PLANNER);
        verify(capacity).checkCapacity(eq("F1"), eq("P1"), any(), eq("盒"), eq("L2"), any(), any());
    }

    @Test
    void aCaseThatDoesNotComeFromASalesOrderIsRefusedBeforeAnythingIsRead() {
        theCase.setSourceType(null);   // 今天只有 SALES_ORDER 一种来源; 任何别的 (含未填) 都拒

        assertThatThrownBy(() -> service.propose("F1", "C1", PLANNER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只有来自销售订单");
        verify(orders, never()).findById(anyString());
        verify(proposals, never()).saveAndFlush(any());
    }
}
