package com.cretas.aims.service.yield.costview;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CostViewPureLogicContractTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static LaborProcessAllocator.ProcessHours ph(int o, String h) {
        return new LaborProcessAllocator.ProcessHours(o, "P" + o, h == null ? null : bd(h));
    }

    // ---- 人工分摊 ----
    @Test
    void allocation_tailGoesToLastProcessWithHours_andSumsToTotal() {
        var r = LaborProcessAllocator.allocate(bd("100.00"), List.of(ph(1, "1"), ph(2, "1"), ph(3, "1"), ph(4, "0")));
        assertEquals(LaborProcessAllocator.Status.ALLOCATED, r.status());
        assertEquals(bd("33.33"), r.shares().get(0).allocatedCost());
        assertEquals(bd("33.33"), r.shares().get(1).allocatedCost());
        assertEquals(bd("33.34"), r.shares().get(2).allocatedCost()); // 尾差归第 3 道 (最后一道有人时)
        assertEquals(0, r.shares().get(3).allocatedCost().signum());
        BigDecimal sum = r.shares().stream().map(LaborProcessAllocator.ProcessShare::allocatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, bd("100.00").compareTo(sum));
    }

    @Test
    void allocation_totalWithMoreThanTwoDecimals_stillSumsExactlyToRoundedTotal() {
        var r = LaborProcessAllocator.allocate(bd("10.0049"), List.of(ph(1, "1"), ph(2, "2")));
        BigDecimal sum = r.shares().stream().map(LaborProcessAllocator.ProcessShare::allocatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, bd("10.00").compareTo(sum));
    }

    @Test
    void allocation_unknownOrNegativeHours_yieldsNullNotZero() {
        var missing = LaborProcessAllocator.allocate(bd("10"), List.of(ph(1, "1"), ph(2, null)));
        assertEquals(LaborProcessAllocator.Status.HOURS_UNKNOWN, missing.status());
        assertTrue(missing.shares().stream().allMatch(s -> s.allocatedCost() == null));
        assertEquals(LaborProcessAllocator.Status.HOURS_NEGATIVE,
                LaborProcessAllocator.allocate(bd("10"), List.of(ph(1, "2"), ph(2, "-1"))).status());
        assertEquals(LaborProcessAllocator.Status.NO_POSITIVE_HOURS,
                LaborProcessAllocator.allocate(bd("10"), List.of(ph(1, "0"))).status());
        assertEquals(LaborProcessAllocator.Status.LABOR_TOTAL_UNKNOWN,
                LaborProcessAllocator.allocate(null, List.of(ph(1, "1"))).status());
    }

    // ---- 聚合 ----
    private static ProductUnitCostAggregator.OrderPoint op(String no, String unit, String m, String l, String g) {
        return pt(no, "P1", unit, m, l, g, List.of(), true, false, false);
    }

    private static ProductUnitCostAggregator.OrderPoint pt(String no, String product, String unit, String m, String l,
                                                           String g, List<String> missing, boolean hasLabor,
                                                           boolean customer, boolean masked) {
        return new ProductUnitCostAggregator.OrderPoint(no, no, product, "产品", unit,
                m == null ? null : bd(m), l == null ? null : bd(l), g == null ? null : bd(g),
                missing, hasLabor, customer, masked, false, false);
    }

    @Test
    void weighted_isSumOverSum_notAverageOfUnitCosts() {
        var g = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "60", "40", "10"), op("B", "kg", "100", "80", "90"))).get(0);
        assertEquals(ProductUnitCostAggregator.BASIS, g.basis());
        assertEquals("工单材料人工口径", g.basisLabel());
        assertEquals(bd("2.8000"), g.weightedUnitCost()); // 280/100, 而非 (10+2)/2=6
        assertEquals(2, g.eligibleCount());
    }

    @Test
    void unitsNormalisedThroughAuthoritativeTable_kgAndKGShareAGroup_differentUnitsDoNot() {
        var groups = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "10", "10", "10"), op("B", "KG", "10", "10", "10"), op("C", "箱", "10", "10", "10")));
        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).eligibleCount());
    }

    @Test
    void H1_missingPriceOrUnknownLaborCodes_makeOrderIneligible_notZero() {
        var g = ProductUnitCostAggregator.aggregate(List.of(
                op("OK", "kg", "50", "50", "100"),
                pt("NOPRICE", "P1", "kg", "10", "50", "100", List.of("RAW_MATERIAL_PRICE:b1"), true, false, false),
                pt("NOLABOR", "P1", "kg", "50", "0", "100", List.of("LABOR_COST_UNKNOWN:process-2"), true, false, false),
                pt("NODETAIL", "P1", "kg", "50", "0", "100", List.of(), false, false, false))).get(0);
        assertEquals(1, g.eligibleCount());
        assertEquals(bd("1.0000"), g.weightedUnitCost()); // 被缺项压低的单没拉低加权
        assertEquals("MATERIAL_PRICE_MISSING", g.orders().get(1).ineligibleReason());
        assertEquals("LABOR_UNKNOWN", g.orders().get(2).ineligibleReason());
        assertEquals("LABOR_UNKNOWN", g.orders().get(3).ineligibleReason());
    }

    @Test
    void H3_noEligibleOrders_allTotalsAreNullNotZero() {
        var g = ProductUnitCostAggregator.aggregate(List.of(op("A", "kg", "10", null, "10"))).get(0);
        assertNull(g.weightedUnitCost());
        assertNull(g.sumMaterialCost());
        assertNull(g.sumLaborCost());
        assertNull(g.sumOutputQuantity());
    }

    @Test
    void M2_mixedOrUnknownProductIsIneligibleAndNeverPooledWithARealProduct() {
        var groups = ProductUnitCostAggregator.aggregate(List.of(
                pt("MIX1", null, "kg", "50", "50", "100", List.of(), true, false, false),
                op("A", "kg", "50", "50", "100")));
        assertEquals(2, groups.size());
        var mixed = groups.stream().filter(x -> x.productTypeId() == null).findFirst().get();
        assertEquals("PRODUCT_MIXED_OR_UNKNOWN", mixed.orders().get(0).ineligibleReason());
        assertNull(mixed.weightedUnitCost());
    }

    @Test
    void L3_customerSuppliedOrderIsExcludedFromWeightingAndSignals() {
        var g = ProductUnitCostAggregator.aggregate(List.of(
                pt("CS", "P1", "kg", "0", "50", "100", List.of(), true, true, false))).get(0);
        assertEquals("CUSTOMER_SUPPLIED_MATERIAL", g.orders().get(0).ineligibleReason());
        assertTrue(g.signals().isEmpty());
    }

    @Test
    void M4_priceMaskedIsReportedAsMaskedNotAsMissingData() {
        var g = ProductUnitCostAggregator.aggregate(List.of(
                pt("A", "P1", "kg", null, null, "100", List.of(), false, false, true))).get(0);
        assertEquals("PRICE_MASKED", g.orders().get(0).ineligibleReason());
        assertTrue(g.priceMasked());
    }

    // ---- 信号 ----
    @Test
    void spread_needsThreeSamples_andBoundaryIsInclusive() {
        var three = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "50", "50", "100"), op("B", "kg", "60", "60", "100"), op("C", "kg", "55", "55", "100")))
                .get(0);
        assertTrue(three.signals().stream().anyMatch(s -> s.code().equals("UNIT_COST_SPREAD")));
        var two = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "50", "50", "100"), op("B", "kg", "100", "100", "100"))).get(0);
        assertTrue(two.signals().stream().noneMatch(s -> s.code().equals("UNIT_COST_SPREAD")));
        var below = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "50", "50", "100"), op("B", "kg", "59.99", "59.99", "100"), op("C", "kg", "55", "55", "100")))
                .get(0);
        assertTrue(below.signals().stream().noneMatch(s -> s.code().equals("UNIT_COST_SPREAD")));
    }

    @Test
    void H2_signalDetailCarriesNoAmounts() {
        var g = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "50", "50", "100"), op("B", "kg", "60", "60", "100"), op("C", "kg", "55", "55", "100")))
                .get(0);
        var spread = g.signals().stream().filter(s -> s.code().equals("UNIT_COST_SPREAD")).findFirst().get();
        assertFalse(spread.detail().matches(".*\\d+\\.\\d+.*"), spread.detail());
    }

    @Test
    void shareSignals_boundaries() {
        assertTrue(shares("10", "90").stream().noneMatch(s -> s.code().equals("MATERIAL_SHARE_LOW")));
        assertTrue(shares("9", "91").stream().anyMatch(s -> s.code().equals("MATERIAL_SHARE_LOW")));
        assertTrue(shares("35", "65").stream().noneMatch(s -> s.code().equals("LABOR_SHARE_HIGH")));
        assertTrue(shares("34", "66").stream().anyMatch(s -> s.code().equals("LABOR_SHARE_HIGH")));
        assertTrue(shares("50", "0").stream().anyMatch(s -> s.code().equals("LABOR_ZERO")));
    }

    private static List<CostSignalEvaluator.Signal> shares(String m, String l) {
        return CostSignalEvaluator.evaluate(List.of(
                new CostSignalEvaluator.Sample("1", "1", bd(m), bd(l), bd("1"))));
    }

    // ---- 人时汇总 ----
    @Test
    void processHours_nullMinutesMakesThatProcessUnknown() {
        var details = List.of(
                OrderCostBreakdownDTO.LaborDetail.builder().processOrder(1).processName("切").laborMinutes(120).build(),
                OrderCostBreakdownDTO.LaborDetail.builder().processOrder(1).processName("切").laborMinutes(60).build(),
                OrderCostBreakdownDTO.LaborDetail.builder().processOrder(2).processName("炒").laborMinutes(null).build());
        var hours = ProductCostViewService.processHours(details);
        assertEquals(0, bd("3").compareTo(hours.get(0).hours()));
        assertNull(hours.get(1).hours());
        assertEquals(LaborProcessAllocator.Status.HOURS_UNKNOWN,
                LaborProcessAllocator.allocate(bd("30"), hours).status());
    }

    // ---- M6: 服务入口 (compute → 聚合 → 保密遮蔽 → JSON) ----
    private static OrderCostBreakdownDTO cb(String id, String product, String m, String l) {
        return OrderCostBreakdownDTO.builder().hasData(true).orderId(id).orderNumber(id).productTypeId(product)
                .productName("产品").outputUnit("kg").outputQuantity(bd("100")).rawMaterialCost(bd(m))
                .laborCost(bd(l)).missingCostItems(new ArrayList<>())
                .laborDetails(List.of(OrderCostBreakdownDTO.LaborDetail.builder().processOrder(1).processName("切")
                        .laborMinutes(60).amount(bd(l)).build()))
                .customerSuppliedItemCount(0).build();
    }

    private static SalesOrder so(String id) {
        SalesOrder s = new SalesOrder();
        s.setId(id);
        return s;
    }

    private ProductCostViewService service(List<SalesOrder> orders, OrderCostBreakdownDTO... dtos) {
        SalesOrderRepository repo = mock(SalesOrderRepository.class);
        when(repo.findByFactoryIdAndDateRange(anyString(), any(), any())).thenReturn(orders);
        OrderCostBreakdownService bs = mock(OrderCostBreakdownService.class);
        for (OrderCostBreakdownDTO d : dtos) {
            when(bs.compute(anyString(), eq(d.getOrderId()), anyBoolean())).thenReturn(d);
        }
        ProcessSecretCostMask mask = mock(ProcessSecretCostMask.class);
        when(mask.hiddenFor(anyString(), any(Collection.class), anyString())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(1);
            return ids.contains("SECRET") ? Set.of("SECRET") : Set.of();
        });
        return new ProductCostViewService(repo, bs, mask);
    }

    @Test
    void service_secretProductIsMaskedForUnauthorisedCaller_andJsonHasNoAmounts() throws Exception {
        var svc = service(List.of(so("o1"), so("o2")), cb("o1", "SECRET", "50", "50"), cb("o2", "PLAIN", "50", "50"));
        var res = svc.byProduct("F", LocalDate.now().minusDays(1), LocalDate.now(), false);
        var secret = res.groups().stream().filter(g -> "SECRET".equals(g.productTypeId())).findFirst().get();
        var plain = res.groups().stream().filter(g -> "PLAIN".equals(g.productTypeId())).findFirst().get();
        assertTrue(secret.secretMasked());
        assertNull(secret.weightedUnitCost());
        assertNull(secret.orders().get(0).materialCost());
        assertEquals(bd("1.0000"), plain.weightedUnitCost());
        JsonNode json = new ObjectMapper().valueToTree(secret);
        assertTrue(json.get("sumMaterialCost").isNull());
        assertTrue(json.get("orders").get(0).get("unitCost").isNull());
        assertEquals("SECRET", json.get("productTypeId").asText()); // 供响应裁剪器认归属
    }

    @Test
    void service_truncationIsReportedNotSilent() {
        List<SalesOrder> many = new ArrayList<>();
        for (int i = 0; i < ProductCostViewService.MAX_ORDERS + 5; i++) many.add(so("o" + i));
        var svc = service(many);
        var res = svc.byProduct("F", LocalDate.now().minusDays(1), LocalDate.now(), false);
        assertTrue(res.truncated());
        assertEquals(ProductCostViewService.MAX_ORDERS + 5, res.totalOrders());
        assertEquals(ProductCostViewService.MAX_ORDERS, res.includedOrders());
    }

    @Test
    void service_maskedCallerGetsMaskedFlagAndNoFakeZeroTotals() {
        // 生产形状: 脱敏时上游把金额置 null
        OrderCostBreakdownDTO masked = cb("o1", "PLAIN", "50", "50");
        masked.setRawMaterialCost(null);
        masked.setLaborCost(null);
        masked.setLaborDetails(null);
        var svc = service(List.of(so("o1")), masked);
        var res = svc.byProduct("F", LocalDate.now().minusDays(1), LocalDate.now(), true);
        assertTrue(res.priceMasked());
        var g = res.groups().get(0);
        assertEquals("PRICE_MASKED", g.orders().get(0).ineligibleReason());
        assertNull(g.orders().get(0).materialCost());
        assertNull(g.orders().get(0).laborCost());
        assertNull(g.sumMaterialCost());
    }

    @Test
    void L1_pricemaskedRowBlanksAmountsEvenIfUpstreamLeaksThem() {
        var g = ProductUnitCostAggregator.aggregate(List.of(
                pt("A", "P1", "kg", "50", "50", "100", List.of(), true, false, true))).get(0);
        assertNull(g.orders().get(0).materialCost());
        assertNull(g.orders().get(0).laborCost());
        assertNull(g.orders().get(0).outputQuantity());
    }

    @Test
    void NM1_ordinaryPlanMissingPriceOnlyNullsDetailAmount_stillIneligible() {
        // 生产形状: 非正式计划缺价 → missingCostItems 为空, 只有明细 amount=null / MISSING_PRICE
        OrderCostBreakdownDTO noPrice = cb("o1", "P1", "10", "50");
        noPrice.setRawMaterialDetails(List.of(OrderCostBreakdownDTO.RawMaterialDetail.builder()
                .batchNumber("b1").amount(null).collectionStatus("MISSING_PRICE").build()));
        OrderCostBreakdownDTO noLabor = cb("o2", "P1", "50", "10");
        noLabor.setLaborDetails(List.of(OrderCostBreakdownDTO.LaborDetail.builder().processOrder(1)
                .laborMinutes(60).amount(null).build()));
        OrderCostBreakdownDTO ok = cb("o3", "P1", "50", "50");
        ok.setRawMaterialDetails(List.of(OrderCostBreakdownDTO.RawMaterialDetail.builder()
                .batchNumber("b2").amount(bd("50")).collectionStatus("COLLECTED").build()));
        OrderCostBreakdownDTO customer = cb("o4", "P1", "0", "50");
        customer.setCustomerSuppliedItemCount(0);
        customer.setRawMaterialDetails(List.of(OrderCostBreakdownDTO.RawMaterialDetail.builder()
                .batchNumber("b3").amount(BigDecimal.ZERO).collectionStatus("CUSTOMER_SUPPLIED")
                .priceSource("CUSTOMER_SUPPLIED").build()));
        var svc = service(List.of(so("o1"), so("o2"), so("o3"), so("o4")), noPrice, noLabor, ok, customer);
        var g = svc.byProduct("F", LocalDate.now().minusDays(1), LocalDate.now(), false).groups().get(0);
        assertEquals("MATERIAL_PRICE_MISSING", g.orders().get(0).ineligibleReason());
        assertEquals("LABOR_UNKNOWN", g.orders().get(1).ineligibleReason());
        assertNull(g.orders().get(2).ineligibleReason()); // 正常单可核算
        assertNull(g.orders().get(3).ineligibleReason()); // 客供行 amount=0 不算缺口
        assertEquals(2, g.eligibleCount());
    }

    @Test
    void NM2_anyHiddenIsOnlyAskedWhenThereIsAnUnknownProductGroup() {
        SalesOrderRepository repo = mock(SalesOrderRepository.class);
        when(repo.findByFactoryIdAndDateRange(anyString(), any(), any())).thenReturn(List.of(so("o1")));
        OrderCostBreakdownService bs = mock(OrderCostBreakdownService.class);
        when(bs.compute(anyString(), eq("o1"), anyBoolean())).thenReturn(cb("o1", "PLAIN", "50", "50"));
        ProcessSecretCostMask mask = mock(ProcessSecretCostMask.class);
        when(mask.hiddenFor(anyString(), any(Collection.class), anyString())).thenReturn(Set.of());
        new ProductCostViewService(repo, bs, mask).byProduct("F", LocalDate.now().minusDays(1), LocalDate.now(), false);
        org.mockito.Mockito.verify(mask, org.mockito.Mockito.never()).anyHidden(anyString(), anyString());

        // 有「产品未知」的组 (多计划单) 时才问
        when(bs.compute(anyString(), eq("o1"), anyBoolean())).thenReturn(cb("o1", null, "50", "50"));
        new ProductCostViewService(repo, bs, mask).byProduct("F", LocalDate.now().minusDays(1), LocalDate.now(), false);
        org.mockito.Mockito.verify(mask, org.mockito.Mockito.times(1)).anyHidden(anyString(), anyString());
    }

    @Test
    void NL2_secretMaskedGroupCarriesNoSignalCodes() {
        var g = ProductUnitCostAggregator.aggregate(List.of(
                op("A", "kg", "5", "95", "100"), op("B", "kg", "60", "60", "100"), op("C", "kg", "55", "55", "100")))
                .get(0);
        assertFalse(g.signals().isEmpty()); // 前提: 原本有信号
        assertTrue(g.maskedForSecret().signals().isEmpty());
    }

    @Test
    void service_laborAllocation_usesOwnProcessAmounts_andListsUpstreamSeparately() {
        OrderCostBreakdownDTO d = cb("o1", "PLAIN", "50", "130"); // 订单人工 130, 本单工序实际 130
        d.setLaborDetails(List.of(
                OrderCostBreakdownDTO.LaborDetail.builder().processOrder(1).processName("切").laborMinutes(60)
                        .amount(bd("40")).build(),
                OrderCostBreakdownDTO.LaborDetail.builder().processOrder(2).processName("炒").laborMinutes(180)
                        .amount(bd("60")).build()));
        var svc = service(List.of(), d);
        var r = svc.laborAllocation("F", "o1", false);
        assertEquals(LaborProcessAllocator.Status.ALLOCATED, r.status());
        assertEquals(0, bd("100").compareTo(r.laborCostTotal()));
        assertEquals(0, bd("30").compareTo(r.upstreamLaborCost())); // 130 − 100 = 上游转入
        assertEquals(LaborProcessAllocator.Status.PRICE_MASKED, svc.laborAllocation("F", "o1", true).status());
    }

    @Test
    void service_laborAllocation_secretProductHidden() {
        var svc = service(List.of(), cb("o1", "SECRET", "50", "50"));
        assertEquals(LaborProcessAllocator.Status.SECRET_HIDDEN, svc.laborAllocation("F", "o1", false).status());
    }
}
