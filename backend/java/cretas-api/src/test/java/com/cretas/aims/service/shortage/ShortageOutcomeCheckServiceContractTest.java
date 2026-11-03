package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck;
import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck.Trigger;
import com.cretas.aims.entity.shortage.ShortageCaseOutcomeCheck.Verdict;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseOutcomeCheckRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.repository.shortage.ShortageScheduleQueryRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺料处理执行后核对: 只有缺口关闭且排程都在交期内才算达成; 晚于交期 / 只有草稿没下单 / 到货赶不上开工 = 未达成;
 * 交期或到货日缺失 = 无法判定 —— 都写回处理实例, 不默认成功。
 */
class ShortageOutcomeCheckServiceContractTest {

    private static final String F = "F006";
    private static final ActorRef PLANNER = new ActorRef(AuthenticatedActorType.FACTORY_USER, 7L);
    private static final LocalDate DUE = LocalDate.now().plusDays(10);

    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseLineRepository lines = mock(ShortageCaseLineRepository.class);
    private final ShortageScheduleQueryRepository schedules = mock(ShortageScheduleQueryRepository.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private final ShortageCaseOutcomeCheckRepository checks = mock(ShortageCaseOutcomeCheckRepository.class);
    private final ShortageOutcomeCheckService service =
            new ShortageOutcomeCheckService(cases, lines, schedules, orders, checks);

    private SalesOrder order;
    private final List<ShortageCaseLine> caseLines = new ArrayList<>();
    private final List<LineSchedule> pending = new ArrayList<>();
    private Object[] arrival;

    @BeforeEach
    void setUp() {
        ShortageCase c = new ShortageCase();
        c.setId("case-1");
        c.setFactoryId(F);
        c.setSourceType(ShortageCase.SourceType.SALES_ORDER);
        c.setSourceId("so-1");
        c.setLastRefreshedAt(LocalDateTime.now());
        when(cases.findByIdAndFactoryId("case-1", F)).thenReturn(Optional.of(c));
        order = new SalesOrder();
        order.setFactoryId(F);
        order.setRequiredDeliveryDate(DUE);
        when(orders.findById("so-1")).thenReturn(Optional.of(order));
        when(lines.findByCaseIdOrderByIdAsc("case-1")).thenReturn(caseLines);
        when(schedules.findNotStartedForSalesOrder(F, "so-1")).thenReturn(pending);
        when(schedules.findLiveOrderArrival(any(), any())).thenAnswer(inv ->
                arrival == null ? List.of() : List.<Object[]>of(arrival));
        when(checks.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        pending.add(schedule(LocalDate.now().plusDays(5), DUE.minusDays(1)));
    }

    private static LineSchedule schedule(LocalDate start, LocalDate end) {
        LineSchedule ls = new LineSchedule();
        ls.setPlannedStartTime(start.atTime(8, 0));
        ls.setPlannedEndTime(end.atTime(17, 0));
        return ls;
    }

    private void gap(String qty) {
        ShortageCaseLine l = new ShortageCaseLine();
        l.setMaterialTypeId("MT-1");
        l.setMaterialName("面粉");
        l.setGapQty(new BigDecimal(qty));
        caseLines.add(l);
    }

    private ShortageCaseOutcomeCheck run() {
        return service.check(F, "case-1", Trigger.RESCHEDULE_APPLIED, 11L, PLANNER);
    }

    @Test
    void gapClosedAndAllSchedulesBeforeDueIsAchieved() {
        gap("0");
        ShortageCaseOutcomeCheck chk = run();
        assertThat(chk.getVerdict()).isEqualTo(Verdict.ACHIEVED);
        assertThat(chk.isGapClosed()).isTrue();
        assertThat(chk.getActionId()).isEqualTo(11L);
    }

    @Test
    void aScheduleEndingAfterDueIsNotAchievedEvenWithGapClosed() {
        pending.add(schedule(DUE.minusDays(1), DUE.plusDays(2)));
        ShortageCaseOutcomeCheck chk = run();
        assertThat(chk.getVerdict()).isEqualTo(Verdict.NOT_ACHIEVED);
        assertThat(chk.getLateScheduleCount()).isEqualTo(1);
    }

    @Test
    void openGapWithOnlyADraftIsNotAchieved() {
        gap("5");
        arrival = null;                       // 草稿不算在走的采购单
        ShortageCaseOutcomeCheck chk = run();
        assertThat(chk.getVerdict()).isEqualTo(Verdict.NOT_ACHIEVED);
        assertThat(chk.isGapClosed()).isFalse();
        assertThat(chk.getDetail()).contains("面粉").contains("草稿不算");
    }

    @Test
    void openGapWithArrivalBeforeFirstStartIsPendingSupply() {
        gap("5");
        arrival = new Object[]{LocalDate.now().plusDays(2), 0L, 1L};
        ShortageCaseOutcomeCheck chk = run();
        assertThat(chk.getVerdict()).isEqualTo(Verdict.PENDING_SUPPLY);
        assertThat(chk.getSupplyReadyDate()).isEqualTo(LocalDate.now().plusDays(2));
    }

    @Test
    void arrivalTooLateForTheFirstStartIsNotAchieved() {
        gap("5");
        arrival = new Object[]{LocalDate.now().plusDays(5), 0L, 1L};   // 到货当天开工不行, 要次日
        assertThat(run().getVerdict()).isEqualTo(Verdict.NOT_ACHIEVED);
    }

    @Test
    void undatedPurchaseOrderIsUnknownNotSuccess() {
        gap("5");
        arrival = new Object[]{null, 1L, 1L};
        assertThat(run().getVerdict()).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    void missingDueDateIsUnknownNotSuccess() {
        gap("0");
        order.setRequiredDeliveryDate(null);
        assertThat(run().getVerdict()).isEqualTo(Verdict.UNKNOWN);
    }
}
