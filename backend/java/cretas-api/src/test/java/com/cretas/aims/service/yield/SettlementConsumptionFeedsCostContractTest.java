package com.cretas.aims.service.yield;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionSettlementConsumptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 成本归集必须<b>也</b>看得到结单领用行，否则结单产出的成品单位成本恒为空。
 *
 * <h2>🔴 为什么有这道闸（2026-08-18 prod 实测，owner 拍板走「读侧接通」）</h2>
 *
 * 结单把领用事实写进 {@code production_settlement_consumptions}（note「自动带入自逐道报工」），
 * 而 {@code OrderCostBreakdownService} 一直按 {@code material_consumptions.production_batch_id}
 * 归集 —— <b>两张表不相交</b>：
 * <pre>
 * production_settlement_consumptions   4 行 /  1 个计划
 * material_consumptions               69 行 /  6 个计划
 *   其中 production_batch_id 有值      22 行, 为空 47 行   ← 归集按这个键查, 47 行看不见
 * 只出现在结单表、归集完全看不到的计划   1 个
 * 两表都有的 (计划, 批次) 组合          0 个                ← 所以并入不会重复计
 * 结单领用行能连回批次并取到单价        4/4
 * production_settlement_consumptions 有没有单价/成本列     没有 (0 列)
 * </pre>
 * 净效果：{@code totalRawInput=0} → {@code totalCost=0} → {@code resolvePlanTotalCost} 返回 null
 * → 成品 {@code unit_cost} 恒为空（prod 上 1/1 个成品批次单位成本为空）。
 *
 * <h2>这道闸钉四条</h2>
 * <ol>
 *   <li>结单领用行确实被并进来了（阳性对照：不并的话下面全是恒真）</li>
 *   <li>已经从 {@code material_consumptions} 取到的批次<b>不重复并入</b></li>
 *   <li>同一计划的<b>多个生产批次只并入一次</b> —— 否则原料成本翻倍</li>
 *   <li>取不到单价时 {@code totalCost} 留 {@code null}，<b>⛔ 不填 0</b> ——
 *       0 是个看起来很确定的假答案，而下游 {@code missingCostItems} 正是靠 null 才报得出「缺价」</li>
 * </ol>
 */
class SettlementConsumptionFeedsCostContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN = "ffc61a6f-f731-4401-95f7-a82891f8ad9d";
    private static final String MB_A = "a8ebb2ce-20c5-4181-bae5-fbf9097a839b";
    private static final String MB_B = "368db702-623e-4766-8b2f-40991e858cd9";

    private ProductionSettlementConsumptionRepository settleRepo;
    private MaterialBatchRepository batchRepo;
    private MaterialConsumptionRepository consumptionRepo;

    private OrderCostBreakdownService service(List<ProductionSettlementConsumption> rows,
                                              BigDecimal unitPriceForAll) {
        return service(rows, unitPriceForAll, List.of());
    }

    /**
     * @param planWideConsumptions 【计划全量、含 CLERK_WIP】已消耗的物料批次 —— 供
     *                              {@code planWideCoveredMaterialBatchIds} 读取 (2026-09-04 缺陷订正)。
     *                              空表示这道查询本轮什么都没找到 (existing 5 个测试的默认行为不变)。
     */
    private OrderCostBreakdownService service(List<ProductionSettlementConsumption> rows,
                                              BigDecimal unitPriceForAll,
                                              List<MaterialConsumption> planWideConsumptions) {
        OrderCostBreakdownService svc = mock(OrderCostBreakdownService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        settleRepo = mock(ProductionSettlementConsumptionRepository.class);
        when(settleRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(rows);
        batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.findByIdAndFactoryId(anyString(), anyString())).thenAnswer(i -> {
            MaterialBatch mb = new MaterialBatch();
            mb.setId(i.getArgument(0));
            mb.setFactoryId(FACTORY);
            mb.setUnitPrice(unitPriceForAll);
            mb.setQuantityUnit("kg");
            return Optional.of(mb);
        });
        consumptionRepo = mock(MaterialConsumptionRepository.class);
        when(consumptionRepo.findByFactoryIdAndPlanProductionBatchesIncludingWip(anyString(), anyString()))
                .thenReturn(planWideConsumptions);
        ReflectionTestUtils.setField(svc, "settlementConsumptionRepository", settleRepo);
        ReflectionTestUtils.setField(svc, "materialBatchRepository", batchRepo);
        ReflectionTestUtils.setField(svc, "consumptionRepository", consumptionRepo);
        return svc;
    }

    private static ProductionSettlementConsumption row(String materialBatchId, String qty) {
        ProductionSettlementConsumption r = new ProductionSettlementConsumption();
        r.setFactoryId(FACTORY);
        r.setProductionPlanId(PLAN);
        r.setMaterialBatchId(materialBatchId);
        r.setSourceType("RAW_MATERIAL");
        r.setQuantity(new BigDecimal(qty));
        r.setUnit("kg");
        return r;
    }

    private static ProductionBatch batch(Long id) {
        ProductionBatch b = new ProductionBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setProductionPlanId(PLAN);
        return b;
    }

    private static MaterialConsumption covered(String batchId) {
        MaterialConsumption c = new MaterialConsumption();
        c.setBatchId(batchId);
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<MaterialConsumption> merge(OrderCostBreakdownService svc, ProductionBatch b,
                                            List<MaterialConsumption> covered, Set<String> merged) {
        return (List<MaterialConsumption>) ReflectionTestUtils.invokeMethod(
                svc, "settlementConsumptionsFor", FACTORY, b, covered, merged);
    }

    @Test
    @DisplayName("阳性对照: 结单领用行真的被并进来了 (否则下面全是恒真)")
    void settlementRowsAreMergedIn() {
        OrderCostBreakdownService svc = service(
                List.of(row(MB_A, "20"), row(MB_B, "20")), new BigDecimal("2.50"));
        List<MaterialConsumption> got = merge(svc, batch(10761L), new ArrayList<>(), new HashSet<>());
        assertEquals(2, got.size(), "结单领用行没并进来: " + got.size());
        assertEquals(0, got.get(0).getTotalCost().compareTo(new BigDecimal("50.00")),
                "20kg × 2.50 应是 50, 实际 " + got.get(0).getTotalCost());
        assertEquals(PLAN, got.get(0).getProductionPlanId());
        assertEquals(Long.valueOf(10761L), got.get(0).getProductionBatchId(),
                "没带生产批次 ID, 归集按这个键分组");
    }

    @Test
    @DisplayName("🔴 已经从 material_consumptions 取到的批次不重复并入")
    void alreadyCoveredBatchesAreNotDoubleCounted() {
        OrderCostBreakdownService svc = service(
                List.of(row(MB_A, "20"), row(MB_B, "20")), new BigDecimal("2.50"));
        List<MaterialConsumption> got = merge(
                svc, batch(10761L), new ArrayList<>(List.of(covered(MB_A))), new HashSet<>());
        assertEquals(1, got.size(), "把已经算过的批次又并了一遍: " + got.size());
        assertEquals(MB_B, got.get(0).getBatchId());
    }

    @Test
    @DisplayName("🔴🔴 缺陷复现: 上游 CLERK_WIP 批次自己消耗过的物料批次, 结单并入不再重复计"
            + " (prod 实测 PLAN-1788305552728-D94F7E22 — 冰水批次在归集里出现两次)")
    void materialAlreadyConsumedByUpstreamWipBatchIsNotDoubleCounted() {
        // MB_A 已经被【上游 CLERK_WIP 批次自己的】material_consumptions 消耗过 —— traceCost
        // 沿谱系回溯能看到它, 但它不在【当前(成品)批次自己】的 covered 参数里 (旧代码只看这个维度)。
        // 只有「计划全量、含 CLERK_WIP」的查询才看得到, 即下面 planWideConsumptions。
        MaterialConsumption upstreamWipConsumption = covered(MB_A);
        OrderCostBreakdownService svc = service(
                List.of(row(MB_A, "20"), row(MB_B, "20")), new BigDecimal("2.50"),
                List.of(upstreamWipConsumption));

        // covered(当前批次自己的消耗行) 传空 —— 复现旧代码「看不到上游 WIP 批次消耗」的场景。
        List<MaterialConsumption> got = merge(svc, batch(10909L), new ArrayList<>(), new HashSet<>());

        assertEquals(1, got.size(),
                "MB_A 已经被上游 CLERK_WIP 批次消耗过, 结单又并入了一次 —— 这正是 prod 的双计缺陷: "
                        + got.stream().map(MaterialConsumption::getBatchId).toList());
        assertEquals(MB_B, got.get(0).getBatchId(), "唯一该并入的是从未被消耗过的 MB_B");
    }

    @Test
    @DisplayName("阴性对照: 物料批次不在计划任何一处消耗记录里时仍合法并入 (人工补录场景, 不能被误伤)")
    void materialNeverConsumedAnywhereInThePlanIsStillMerged() {
        OrderCostBreakdownService svc = service(
                List.of(row(MB_A, "20"), row(MB_B, "20")), new BigDecimal("2.50"),
                List.of(covered(MB_A)));   // 只有 MB_A 被计划内某批次消耗过, MB_B 从未出现

        List<MaterialConsumption> got = merge(svc, batch(10909L), new ArrayList<>(), new HashSet<>());

        assertEquals(1, got.size());
        assertEquals(MB_B, got.get(0).getBatchId(), "MB_B 从未被消耗过, 不该被计划全量去重误伤");
    }

    @Test
    @DisplayName("🔴 计划全量消耗行读挂了不让整个成本分解崩, 降级为仅按当前批次去重 (旧行为)")
    void planWideLookupFailureDegradesToBatchScopedDedup() {
        OrderCostBreakdownService svc = service(
                List.of(row(MB_A, "20"), row(MB_B, "20")), new BigDecimal("2.50"));
        when(consumptionRepo.findByFactoryIdAndPlanProductionBatchesIncludingWip(anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        // covered 参数(当前批次自己的消耗行)仍然生效 —— 只是计划全量那一层降级为「本轮没有额外去重」。
        List<MaterialConsumption> got = merge(
                svc, batch(10909L), new ArrayList<>(List.of(covered(MB_A))), new HashSet<>());

        assertEquals(1, got.size(), "计划全量查询读挂了应该降级, 而不是抛出去把整个成本分解带崩");
        assertEquals(MB_B, got.get(0).getBatchId());
    }

    @Test
    @DisplayName("🔴 同一计划的多个生产批次只并入一次 —— 否则原料成本翻倍")
    void onePlanIsMergedOnlyOnceAcrossItsBatches() {
        OrderCostBreakdownService svc = service(
                List.of(row(MB_A, "20"), row(MB_B, "20")), new BigDecimal("2.50"));
        Set<String> merged = new HashSet<>();
        List<MaterialConsumption> first = merge(svc, batch(10761L), new ArrayList<>(), merged);
        List<MaterialConsumption> second = merge(svc, batch(10999L), new ArrayList<>(), merged);
        assertEquals(2, first.size(), "第一个批次没并进来");
        assertTrue(second.isEmpty(), "同一计划并了第二遍 ⇒ 成本翻倍: " + second.size());
    }

    @Test
    @DisplayName("🔴 取不到单价时 totalCost 留 null, ⛔ 不填 0 (0 会让缺价报不出来)")
    void unknownPriceLeavesCostNullNotZero() {
        OrderCostBreakdownService svc = service(List.of(row(MB_A, "20")), null);
        List<MaterialConsumption> got = merge(svc, batch(10761L), new ArrayList<>(), new HashSet<>());
        assertEquals(1, got.size());
        assertNull(got.get(0).getUnitPrice(), "凭空造了单价");
        assertNull(got.get(0).getTotalCost(), "缺价却填了数 —— 下游就报不出「缺价」了");
    }

    @Test
    @DisplayName("阴性对照: 结单表没有该计划的行时不凭空造")
    void noRowsMeansNothingMerged() {
        OrderCostBreakdownService svc = service(List.of(), new BigDecimal("2.50"));
        assertTrue(merge(svc, batch(10761L), new ArrayList<>(), new HashSet<>()).isEmpty(),
                "凭空造了领用行");
    }

    @Test
    @DisplayName("仓库没装配 / 计划为空时不炸, 返回空")
    void missingRepositoryOrPlanDegradesSafely() {
        OrderCostBreakdownService svc = service(List.of(row(MB_A, "20")), new BigDecimal("2.50"));
        ReflectionTestUtils.setField(svc, "settlementConsumptionRepository", null);
        assertTrue(merge(svc, batch(10761L), new ArrayList<>(), new HashSet<>()).isEmpty());

        OrderCostBreakdownService svc2 = service(List.of(row(MB_A, "20")), new BigDecimal("2.50"));
        ProductionBatch noPlan = batch(10761L);
        noPlan.setProductionPlanId(null);
        assertTrue(merge(svc2, noPlan, new ArrayList<>(), new HashSet<>()).isEmpty());
    }

    @Test
    @DisplayName("结单表读挂了不让整个成本分解崩, 但要留痕")
    void repositoryFailureDoesNotBreakTheBreakdown() {
        OrderCostBreakdownService svc = service(List.of(), new BigDecimal("2.50"));
        when(settleRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"));
        assertTrue(merge(svc, batch(10761L), new ArrayList<>(), new HashSet<>()).isEmpty(),
                "读挂了应该降级返回空, 而不是抛出去");
    }

    @Test
    @DisplayName("🔴 接线闸: 归集循环真的调了它 —— helper 写对不等于接上了")
    void theAggregationLoopActuallyCallsIt() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/cretas/aims/service/yield/OrderCostBreakdownService.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
        int at = src.indexOf("for (ProductionBatch b : batches) {");
        assertTrue(at > 0, "找不到归集循环");
        int end = src.indexOf("return ", at);
        String loop = src.substring(at, end > at ? end : src.length());
        assertTrue(loop.contains("settlementConsumptionsFor("),
                "归集循环没有并入结单领用 —— 算得出不等于接上了");
        assertTrue(loop.contains("settlementPlansMerged"),
                "没有按计划去重, 多批次会把原料成本算两遍");
    }
}
