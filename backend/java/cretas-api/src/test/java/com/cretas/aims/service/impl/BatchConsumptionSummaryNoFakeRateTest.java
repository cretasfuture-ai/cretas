package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.orchestration.BomExpansionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 没有计划数据时，达成率必须是 {@code null} —— 不是 100，也不是 0。
 *
 * <h2>🔴 2026-08-28 逐屏视觉走查抓到</h2>
 *
 * <p>f006 →「生产管理 / BOM达成率分析」，8 个批次里 <b>7 个</b>：</p>
 *
 * <pre>
 *   计划总量  0        实际总量  19.75 / 1.98 / 8.7 / 79 / 152.13
 *   达成率    100%     状态      已完成
 * </pre>
 *
 * <p>而顶部四张 KPI 卡（平均达成率 100% / 超耗批次数 0 / 最低达成率 100%）
 * <b>全部由这些 100 推出来</b>。用户读到的是「8 个批次执行完美、零超耗」，
 * 真相是 7 个批次<b>根本没有计划数据</b>。</p>
 *
 * <p>真因就在本类被测的那一段：</p>
 *
 * <pre>
 *   } else {
 *       achievementRate = BigDecimal.valueOf(100);   // totalPlannedCost == 0
 *   }
 * </pre>
 *
 * <h2>为什么 100 比 0 更糟</h2>
 *
 * <p>兜底成 0 至少会引人去查；兜底成 100 是<b>满分</b>，没有人会去查一个满分。
 * 「我不知道」被翻译成了「一切完美」。</p>
 *
 * <p>⚠️ 同一个代码库里已经有正确写法：web-admin 的「SKU毛利率分析」屏在数据没接入时
 * 明说「<b>为避免误导，毛利率明细暂不展示估算数据</b>」并指向数据真正在哪。
 * 这道闸把同一条纪律钉在产出端。</p>
 *
 * <p>⚠️ 单测用 mock repository，所以判据是「这个方法返回什么」，
 * 不是「屏上显示什么」——屏那一侧由 web-admin / RN 各自的用例守。</p>
 */
class BatchConsumptionSummaryNoFakeRateTest {

    private static final String FACTORY = "F006";
    private static final Long BATCH_ID = 42L;
    private static final String TYPE_ID = "RMT-salt";

    private MaterialConsumptionRepository consumptionRepository;
    private RawMaterialTypeRepository rawMaterialTypeRepository;
    private BatchConsumptionServiceImpl service;

    @BeforeEach
    void setUp() {
        consumptionRepository = mock(MaterialConsumptionRepository.class);
        rawMaterialTypeRepository = mock(RawMaterialTypeRepository.class);
        when(rawMaterialTypeRepository.findById(anyString())).thenReturn(java.util.Optional.empty());
        service = new BatchConsumptionServiceImpl(
                mock(BomExpansionService.class),
                mock(MaterialBatchService.class),
                mock(MaterialBatchRepository.class),
                consumptionRepository,
                mock(ProductionBatchRepository.class),
                rawMaterialTypeRepository,
                mock(WarehouseResolver.class));
    }

    /**
     * @param plannedQty 计划量；null 表示这条消耗记录压根没有计划（就是线上那 7 个批次的形状）
     */
    private MaterialConsumption consumption(BigDecimal plannedQty, BigDecimal actualQty, BigDecimal totalCost) {
        MaterialConsumption c = new MaterialConsumption();
        c.setMaterialTypeId(TYPE_ID);
        c.setPlannedQuantity(plannedQty);
        c.setQuantity(actualQty);
        c.setTotalCost(totalCost);
        c.setUnitPrice(totalCost != null && actualQty != null && actualQty.signum() > 0
                ? totalCost.divide(actualQty, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        return c;
    }

    @Test
    @DisplayName("工艺保密: 含 BOM 自动扣料 / 报工自动带出的物料行标 automatic, 人工投料不标")
    @SuppressWarnings("unchecked")
    void marksMaterialsWhoseActualQuantityWasDerivedFromTheRecipe() {
        MaterialConsumption seasoning = consumption(null, new BigDecimal("0.1725"), new BigDecimal("1"));
        seasoning.setSourceType("SEASONING");
        MaterialConsumption beef = consumption(null, new BigDecimal("115"), new BigDecimal("100"));
        beef.setMaterialTypeId("RMT-beef");
        beef.setSourceType("RAW_MATERIAL");
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(List.of(seasoning, beef));

        List<Map<String, Object>> items =
                (List<Map<String, Object>>) service.getConsumptionSummary(FACTORY, BATCH_ID).get("items");

        Map<String, Boolean> automaticByType = new java.util.HashMap<>();
        items.forEach(item -> automaticByType.put((String) item.get("materialTypeId"), (Boolean) item.get("automatic")));
        assertEquals(Map.of(TYPE_ID, true, "RMT-beef", false), automaticByType);
    }

    @Test
    @DisplayName("阳性对照: 有计划成本时算得出来 —— 否则下面那条「为 null」的断言是恒真式")
    void computesRateWhenPlanExists() {
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(List.of(consumption(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("100"))));

        Map<String, Object> result = service.getConsumptionSummary(FACTORY, BATCH_ID);

        assertNotNull(result.get("overallAchievementRate"), "有计划成本却算不出达成率");
        assertEquals(0, new BigDecimal("100.0").compareTo((BigDecimal) result.get("overallAchievementRate")),
                "计划=实际 时达成率应为 100%");
        assertTrue(((List<?>) result.get("items")).size() == 1, "阳性对照: 至少要真的走到聚合分支");
    }

    @Test
    @DisplayName("⛔ 没有计划成本时 overallAchievementRate 为 null —— 不是 100")
    void doesNotFabricateFullMarksWhenNoPlan() {
        // 线上那 7 个批次的形状: 有实际消耗, 没有任何计划量
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(List.of(consumption(null, new BigDecimal("19.75"), new BigDecimal("0"))));

        Map<String, Object> result = service.getConsumptionSummary(FACTORY, BATCH_ID);

        assertNull(result.get("overallAchievementRate"),
                "没有计划成本时达成率没有定义。返回 100 会让「8 个批次执行完美、零超耗」这句话"
                        + "出现在一个 7/8 批次根本没有计划数据的工厂里。");
    }

    @Test
    @DisplayName("⛔ 没有计划量时 variancePercent 为 null —— 不是 0(「正好按计划」)")
    void doesNotFabricateZeroVarianceWhenNoPlan() {
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(List.of(consumption(null, new BigDecimal("19.75"), new BigDecimal("0"))));

        Map<String, Object> result = service.getConsumptionSummary(FACTORY, BATCH_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, items.size(), "阳性对照: 没有 item 的话下一条断言是恒真式");
        assertNull(items.get(0).get("variancePercent"),
                "0% 会被读成「正好按计划」—— 恰恰是最让人放心的那个值");
    }

    @Test
    @DisplayName("阴性对照: 有计划量时 variancePercent 照常算得出来")
    void computesVarianceWhenPlanExists() {
        when(consumptionRepository.findByProductionBatchIdAndFactoryId(BATCH_ID, FACTORY))
                .thenReturn(List.of(consumption(new BigDecimal("10"), new BigDecimal("12"), new BigDecimal("120"))));

        Map<String, Object> result = service.getConsumptionSummary(FACTORY, BATCH_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertNotNull(items.get(0).get("variancePercent"), "有计划量却算不出偏差");
        assertEquals(0, new BigDecimal("20.0").compareTo((BigDecimal) items.get(0).get("variancePercent")),
                "12 比 10 多 20%");
    }
}
