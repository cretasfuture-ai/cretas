package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.entity.ProductType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 六膳门演示题单在演示厂 LSMDEMO 复跑 (2026-11-17) 暴露的问题:
 * Q04「最近 30 天」带空格落到「最近」= 7 天; Q02/Q16/Q17 说「卤牛肉」认不出唯一在售成品「演示·酱卤牛肉250g」;
 * Q14 成本差异按创建日的批次数与成本查询按完工日的批次数对不上却不说口径。
 */
class AiBiDemoFiveFixesContractTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);

    @ParameterizedTest(name = "{0} => {1} 天")
    @CsvSource({
            // 「最近 N 天」含今天共 N 天, 起点 = 今天 - (N-1), 与页面默认窗口一致 (LSM-COSTBI 第 8 项)
            "最近 30 天各批次的出成率排序, 29",
            "最近30天出成率, 29",
            "近三十天成本, 29",
            "过去 7 天产量, 6",
            "近 3 个月成本趋势, 92",
            "近三个月成本趋势, 92",
            "最近两周报工, 13",
            "最近的批次, 6",
            "近期的批次, 13",
            "近二十五天, 24",
            "最近1天, 0",
            "查询7天内到期的原料, 7",
            "7天内到期的原料, 7"})
    void recentNDaysAllowsSpacesAndChineseNumerals(String text, long expectedDays) {
        TimeNormalizationRules.TimeRange r = new TimeNormalizationRules().normalize(text, NOW).orElseThrow();
        long days = ChronoUnit.DAYS.between(r.getStart().toLocalDate(), NOW.toLocalDate());
        assertEquals(expectedDays, days, text);
    }

    private static ProductType p(String name, String category) {
        ProductType t = new ProductType();
        t.setId("PT-" + name);
        t.setName(name);
        t.setProductCategory(category);
        return t;
    }

    private static List<ProductType> demoCatalog() {
        List<ProductType> l = new ArrayList<>();
        l.add(p("演示·酱卤牛肉250g", "FINISHED_PRODUCT"));
        l.add(p("演示·酱卤牛肉·定量包装半成品", "SEMI_FINISHED"));
        l.add(p("演示·酱卤牛肉·熟制杀菌半成品", "SEMI_FINISHED"));
        l.add(p("牛肉酱", "FINISHED_PRODUCT"));
        return l;
    }

    @Test
    void uniqueSellableProductWhoseNameEndsWithTheWordIsUsedAndSaidSo() {
        ReportToolSchemas.ProductResolution r = ReportToolSchemas.resolveProduct(demoCatalog(), "本月卤牛肉的总成本是多少");
        assertEquals("演示·酱卤牛肉250g", r.describe());
        String lead = r.leadIn(true);
        assertTrue(lead != null && lead.contains("「卤牛肉」按成品「演示·酱卤牛肉250g」统计"), String.valueOf(lead));
        assertEquals(null, r.refusalMessage("统计成本", true));
    }

    @Test
    void anotherSellableProductContainingTheWordMeansAskInsteadOfGuess() {
        // 「酱卤牛肉250g」以「牛肉」结尾, 但「牛肉酱」也含「牛肉」: 两个在售产品都沾边, 不猜 (#1810 独立审查 M2)
        ReportToolSchemas.ProductResolution r = ReportToolSchemas.resolveProduct(demoCatalog(), "牛肉成本");
        assertEquals("未找到:牛肉", r.describe());
        assertTrue(r.refusalMessage("统计成本", true).contains("牛肉酱"));
        // 「牛肉酱」不以「牛肉」结尾, 单独一个也不锁定
        List<ProductType> onlySauce = List.of(p("牛肉酱", "FINISHED_PRODUCT"));
        assertEquals("未找到:牛肉", ReportToolSchemas.resolveProduct(onlySauce, "牛肉成本").describe());
    }

    @Test
    void arabicDaysAgoAfterChineseTextStillWorks() {
        // 「查一下3天前」: 阿拉伯数字前面紧贴汉字照常认 (#1810 复审 High)
        TimeNormalizationRules.TimeRange r = new TimeNormalizationRules().normalize("查一下3天前的批次", NOW).orElseThrow();
        assertEquals(NOW.toLocalDate().minusDays(3), r.getStart().toLocalDate());
    }

    @ParameterizedTest(name = "{0} 不是时间")
    @CsvSource({"接近十天没出货", "附近三天的批次", "统一天前的批次", "第一天内完成的工单"})
    void wordsThatMerelyContainNearOrDayAreNotTimeRanges(String text) {
        assertTrue(new TimeNormalizationRules().normalize(text, NOW).isEmpty(), text);
    }

    @Test
    void bareUnitCostIsNotAPhraseSoVarianceAndTrendKeepTheirIntents() {
        com.cretas.aims.config.IntentKnowledgeBase kb = new com.cretas.aims.config.IntentKnowledgeBase();
        kb.initDefaults();
        assertEquals("COST_QUERY", kb.matchPhrase("上周卤牛肉单位成本是多少").orElse(null));
        assertEquals(null, kb.getPhraseToIntentMapping().get("单位成本"));
        assertTrue(!"COST_QUERY".equals(kb.matchPhrase("单位成本差异").orElse(null)), "单位成本差异");
        assertTrue(!"COST_QUERY".equals(kb.matchPhrase("修改产品单位成本").orElse(null)), "修改产品单位成本");
    }

    private static ProductionBatchLike batch(String product, String unit, String cost, String qty) {
        return new ProductionBatchLike(product, unit, cost, qty);
    }

    record ProductionBatchLike(String product, String unit, String cost, String qty) {}

    private static ReportCostQueryTool.Aggregate agg(ProductionBatchLike... bs) {
        ReportCostQueryTool.Aggregate a = new ReportCostQueryTool.Aggregate();
        for (ProductionBatchLike b : bs) {
            ReportCostQueryTool.ProductAgg p = a.byProduct.computeIfAbsent(b.product(), k -> {
                ReportCostQueryTool.ProductAgg x = new ReportCostQueryTool.ProductAgg();
                x.productTypeId = k;
                x.name = k;
                return x;
            });
            p.costByUnit.merge(b.unit(), new java.math.BigDecimal(b.cost()), java.math.BigDecimal::add);
            if (b.qty() != null) p.qtyByUnit.merge(b.unit(), new java.math.BigDecimal(b.qty()), java.math.BigDecimal::add);
            else p.batchesWithoutQuantity++;
        }
        return a;
    }

    @Test
    void productUnitCostRiseListsTheProductThatRose() {
        String text = ReportCostQueryTool.productUnitCostRiseText(
                agg(batch("卤牛肉", "盒", "1200", "100"), batch("酱鸭", "袋", "500", "100")),
                agg(batch("卤牛肉", "盒", "1000", "100"), batch("酱鸭", "袋", "500", "100")));
        assertTrue(text.contains("两期都有产出的 2 个产品") && text.contains("卤牛肉 ¥10.00 → ¥12.00/盒（+20.0%）")
                && !text.contains("酱鸭 ¥"), text);
    }

    @Test
    void productWithABatchMissingQuantityIsNotCompared() {
        // 本期一批缺产量: 成本含它、产量不含, 算出来会是「翻倍」—— 不比, 并说明 (#1810 独立审查 M3)
        String text = ReportCostQueryTool.productUnitCostRiseText(
                agg(batch("卤牛肉", "盒", "100", "10"), batch("卤牛肉", "盒", "100", null)),
                agg(batch("卤牛肉", "盒", "100", "10"), batch("卤牛肉", "盒", "100", "10")));
        assertTrue(!text.contains("+100") && text.contains("1 个产品有批次缺产量"), text);
    }

    @Test
    void twoSellableCandidatesStillAskInsteadOfGuessing() {
        List<ProductType> l = new ArrayList<>(demoCatalog());
        l.add(p("五香卤牛肉 500g", "FINISHED_PRODUCT"));
        ReportToolSchemas.ProductResolution r = ReportToolSchemas.resolveProduct(l, "卤牛肉成本");
        assertEquals("未找到:卤牛肉", r.describe());
        assertTrue(r.refusalMessage("统计成本", true).contains("请说清楚要看哪一个"));
    }

    @Test
    void semiFinishedAloneIsNeverPicked() {
        List<ProductType> l = List.of(p("演示·酱卤牛肉·定量包装半成品", "SEMI_FINISHED"));
        assertEquals("未找到:卤牛肉", ReportToolSchemas.resolveProduct(l, "卤牛肉成本").describe());
    }

}
