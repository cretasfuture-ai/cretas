package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.ProductionLine;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

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
    private ShortageRescheduleService service;

    private ShortageCase theCase;
    private SalesOrder order;
    private LineSchedule task;
    private ProductionLine l1;
    private ProductionLine l2;
    private Object[] arrival;                         // [最晚到货日, 没填日期的单数, 单数]
    private final Set<String> busyLines = new HashSet<>();
    private final Map<String, RescheduleProposal> stored = new HashMap<>();
    private final List<ShortageCaseAction> savedActions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ShortageRescheduleService(cases, lines, events, actions, proposals, schedules, plans,
                productionLines, orders, capacity);

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
        when(schedules.findNotStartedForSalesOrder("F1", "SO-1")).thenAnswer(i -> List.of(task));
        when(schedules.lockByIds(eq("F1"), anyCollection())).thenAnswer(i -> List.of(task));
        when(schedules.findOverlapping(eq("F1"), anyString(), any(), any(), anyCollection()))
                .thenAnswer(i -> busyLines.contains((String) i.getArgument(1)) ? List.of(new LineSchedule()) : List.of());

        l1 = line("L1", "一号线");
        l2 = line("L2", "二号线");
        when(productionLines.findByFactoryIdAndDeletedAtIsNull("F1")).thenAnswer(i -> List.of(l1, l2));

        ProductionPlan pp = new ProductionPlan();
        pp.setId("PP-1");
        pp.setPlanNumber("PP-NO-1");
        pp.setProductTypeId("P1");
        pp.setPlannedQuantity(new BigDecimal("100"));
        pp.setPlannedUnit("KG");
        when(plans.findByIdAndFactoryId("PP-1", "F1")).thenReturn(Optional.of(pp));
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
}
