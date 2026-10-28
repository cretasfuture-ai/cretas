package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCase.Stage;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.entity.shortage.ShortageCaseLine;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseLineRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.repository.shortage.ShortageSupplyQueryRepository;
import com.cretas.aims.service.shortage.dto.ShortageReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 一个缺料事件一个实例, 跟着事实走 (S4-01 / S4-07 / S4-08 的事实部分)。
 * 仓库用有状态的内存桩: 建 → 再来同一事件 → 入库 / 放行, 按真实顺序跑。
 */
class ShortageCaseServiceContractTest {

    private final ShortageAnalysisService analysis = mock(ShortageAnalysisService.class);
    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ShortageCaseLineRepository lines = mock(ShortageCaseLineRepository.class);
    private final ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
    private final ShortageSupplyQueryRepository supply = mock(ShortageSupplyQueryRepository.class);
    private final SalesOrderRepository orders = mock(SalesOrderRepository.class);
    private ShortageCaseService service;

    private final Map<String, ShortageCase> caseStore = new HashMap<>();
    private final List<ShortageCaseLine> lineStore = new ArrayList<>();
    private final List<ShortageCaseEvent> eventStore = new ArrayList<>();
    private long lineSeq = 1;

    // 当前「事实」, 每个测试改它们模拟入库 / 放行
    private BigDecimal available = new BigDecimal("4");
    private BigDecimal inspecting = BigDecimal.ZERO;
    private final List<Object[]> openOrders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ShortageCaseService(analysis, cases, lines, events, supply, orders);
        SalesOrder so = new SalesOrder();
        so.setId("SO-1");
        so.setFactoryId("F1");
        so.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        when(orders.findById("SO-1")).thenReturn(Optional.of(so));

        when(analysis.analyzeForSalesOrder("F1", "SO-1")).thenAnswer(i -> report());
        when(supply.sumInspectingQuantity(eq("F1"), anyString())).thenAnswer(i -> inspecting);
        when(supply.findOpenOrderRemaining(eq("F1"), anyString())).thenAnswer(i -> new ArrayList<>(openOrders));

        when(cases.findOpenForUpdate(eq("F1"), eq(ShortageCase.SourceType.SALES_ORDER), eq("SO-1")))
                .thenAnswer(i -> caseStore.values().stream().filter(c -> c.getClosedAt() == null).findFirst());
        when(cases.saveAndFlush(any())).thenAnswer(i -> {
            ShortageCase c = i.getArgument(0);
            if (c.getId() == null) c.setId("CASE-" + (caseStore.size() + 1));
            caseStore.put(c.getId(), c);
            return c;
        });
        when(cases.findOpenByFactoryIdAndMaterial("F1", "M1"))
                .thenAnswer(i -> caseStore.values().stream().filter(c -> c.getClosedAt() == null).toList());
        when(lines.save(any())).thenAnswer(i -> {
            ShortageCaseLine l = i.getArgument(0);
            if (l.getId() == null) { l.setId(lineSeq++); lineStore.add(l); }
            return l;
        });
        when(lines.findByCaseIdOrderByIdAsc(anyString()))
                .thenAnswer(i -> lineStore.stream().filter(l -> l.getCaseId().equals(i.getArgument(0))).toList());
        when(events.save(any())).thenAnswer(i -> { eventStore.add(i.getArgument(0)); return i.getArgument(0); });
    }

    /** 需要 M1 10 kg; 可用 = available (缺口 = 10 − available)。 */
    private ShortageReport report() {
        MaterialRequirement r = new MaterialRequirement();
        r.setMaterialTypeId("M1");
        r.setMaterialTypeName("猪大骨");
        r.setRequiredQuantity(new BigDecimal("10"));
        r.setSourceUnit("kg");
        List<MaterialShortfall> shorts = new ArrayList<>();
        if (available.compareTo(new BigDecimal("10")) < 0) {
            MaterialShortfall s = new MaterialShortfall();
            s.setMaterialTypeId("M1");
            s.setMaterialTypeName("猪大骨");
            s.setRequiredQuantity(new BigDecimal("10"));
            s.setAvailableQuantity(available);
            s.setShortfallQuantity(new BigDecimal("10").subtract(available));
            shorts.add(s);
        }
        return ShortageReport.builder().factoryId("F1").salesOrderId("SO-1")
                .totalRequired(List.of(r)).materialShortages(shorts).build();
    }

    private ShortageCaseLine onlyLine() {
        assertThat(lineStore).hasSize(1);
        return lineStore.get(0);
    }

    @Test
    void aGapCreatesOneCaseAndTheSameEventAgainChangesNothing() {
        ShortageCase c = service.refreshForSalesOrder("F1", "SO-1", "SO_FINANCE_APPROVED").orElseThrow();

        assertThat(c.getStage()).isEqualTo(Stage.DETECTED);
        assertThat(c.getOwnerRole()).isEqualTo(ShortageCaseService.ROLE_PROCUREMENT);
        assertThat(onlyLine().getGapQty()).isEqualByComparingTo("6");
        assertThat(onlyLine().getUnit()).isEqualTo("kg");
        assertThat(eventStore).extracting(ShortageCaseEvent::getEventType).containsExactly(ShortageCaseEvent.EventType.CREATED);

        service.refreshForSalesOrder("F1", "SO-1", "SO_FINANCE_APPROVED");
        service.refreshForSalesOrder("F1", "SO-1", "SO_FINANCE_APPROVED");

        assertThat(caseStore).hasSize(1);
        assertThat(lineStore).hasSize(1);
        assertThat(eventStore).hasSize(1); // 输入没变 ⇒ 不写留痕
    }

    @Test
    void noGapNoCase() {
        available = new BigDecimal("12");
        assertThat(service.refreshForSalesOrder("F1", "SO-1", "SO_FINANCE_APPROVED")).isEmpty();
        assertThat(caseStore).isEmpty();
    }

    @Test
    void customerSuppliedAndForeignOrdersAreSkipped() {
        SalesOrder customer = new SalesOrder();
        customer.setId("SO-C"); customer.setFactoryId("F1");
        customer.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        when(orders.findById("SO-C")).thenReturn(Optional.of(customer));
        SalesOrder foreign = new SalesOrder();
        foreign.setId("SO-X"); foreign.setFactoryId("F2");
        when(orders.findById("SO-X")).thenReturn(Optional.of(foreign));

        assertThat(service.refreshForSalesOrder("F1", "SO-C", "SO_FINANCE_APPROVED")).isEmpty();
        assertThat(service.refreshForSalesOrder("F1", "SO-X", "SO_FINANCE_APPROVED")).isEmpty();
        assertThat(caseStore).isEmpty();
    }

    @Test
    void anOrderIsNotASolutionInspectionIsNotASolutionOnlyReleaseIs() {
        service.refreshForSalesOrder("F1", "SO-1", "SO_FINANCE_APPROVED");

        // 下了采购单 (同单位) ⇒ 等供应, 缺口不变, 该仓库收货
        openOrders.add(new Object[] {"kg", new BigDecimal("6")});
        service.refreshForMaterial("F1", "M1", "PO_SUBMITTED");
        ShortageCase c = caseStore.values().iterator().next();
        assertThat(c.getStage()).isEqualTo(Stage.WAITING_SUPPLY);
        assertThat(c.getOwnerRole()).isEqualTo(ShortageCaseService.ROLE_WAREHOUSE);
        assertThat(onlyLine().getGapQty()).isEqualByComparingTo("6");
        assertThat(onlyLine().getOnOrderQty()).isEqualByComparingTo("6");

        // 到货但在质检 ⇒ 仍有缺口, 该质检放行
        openOrders.clear();
        inspecting = new BigDecimal("6");
        service.refreshForMaterial("F1", "M1", "MATERIAL_RECEIVED");
        assertThat(c.getStage()).isEqualTo(Stage.WAITING_SUPPLY);
        assertThat(c.getOwnerRole()).isEqualTo(ShortageCaseService.ROLE_QUALITY);
        assertThat(onlyLine().getGapQty()).isEqualByComparingTo("6");
        assertThat(c.getGapResolvedAt()).isNull();

        // 放行 4 ⇒ 部分解决 (还缺 2, 没人管 ⇒ 回到采购)
        inspecting = new BigDecimal("2");
        available = new BigDecimal("8");
        inspecting = BigDecimal.ZERO;
        service.refreshForMaterial("F1", "M1", "MATERIAL_BATCH_RELEASED");
        assertThat(c.getStage()).isEqualTo(Stage.PARTIALLY_RESOLVED);
        assertThat(onlyLine().getGapQty()).isEqualByComparingTo("2");

        // 全部放行 ⇒ 缺口解决, 轮到生产; 解决时间只记一次
        available = new BigDecimal("10");
        service.refreshForMaterial("F1", "M1", "MATERIAL_BATCH_RELEASED");
        assertThat(c.getStage()).isEqualTo(Stage.GAP_RESOLVED);
        assertThat(c.getOwnerRole()).isEqualTo(ShortageCaseService.ROLE_PRODUCTION);
        assertThat(c.getGapResolvedAt()).isNotNull();
        assertThat(onlyLine().getGapQty()).isEqualByComparingTo("0");
        assertThat(caseStore).hasSize(1);
    }

    @Test
    void openOrdersInADifferentUnitAreCountedNotConverted() {
        openOrders.add(new Object[] {"箱", new BigDecimal("3")});
        service.refreshForSalesOrder("F1", "SO-1", "SO_FINANCE_APPROVED");

        assertThat(onlyLine().getOnOrderQty()).isEqualByComparingTo("0");
        assertThat(onlyLine().getOnOrderUnitMismatchLines()).isEqualTo(1);
        assertThat(caseStore.values().iterator().next().getStage()).isEqualTo(Stage.DETECTED);
    }

    @Test
    void aTerminalCaseIsNotTouchedByRefresh() {
        ShortageCase c = service.refreshForSalesOrder("F1", "SO-1", "SO_FINANCE_APPROVED").orElseThrow();
        c.setStage(Stage.MANUAL);
        available = new BigDecimal("10");

        service.refreshForSalesOrder("F1", "SO-1", "MATERIAL_RECEIVED");

        assertThat(c.getStage()).isEqualTo(Stage.MANUAL);
        assertThat(eventStore).hasSize(1);
    }
}
