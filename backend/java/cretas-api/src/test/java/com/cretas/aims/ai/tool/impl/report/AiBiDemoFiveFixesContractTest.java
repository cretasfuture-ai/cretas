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
            "最近 30 天各批次的出成率排序, 30",
            "最近30天出成率, 30",
            "近三十天成本, 30",
            "过去 7 天产量, 7",
            "近 3 个月成本趋势, 92",
            "近三个月成本趋势, 92",
            "最近两周报工, 14",
            "最近的批次, 7",
            "近二十五天, 25"})
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
    void headNounOnly_beefSauceIsNotBeef() {
        // 「牛肉酱」不以「牛肉」结尾; 「酱卤牛肉250g」以「牛肉」结尾 → 只有一个在售成品命中
        ReportToolSchemas.ProductResolution r = ReportToolSchemas.resolveProduct(demoCatalog(), "牛肉成本");
        assertEquals("演示·酱卤牛肉250g", r.describe());
        List<ProductType> onlySauce = List.of(p("牛肉酱", "FINISHED_PRODUCT"));
        assertEquals("未找到:牛肉", ReportToolSchemas.resolveProduct(onlySauce, "牛肉成本").describe());
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

    @Test
    void costVarianceFallbackStatesItsCreatedDateBasis() {
        assertTrue(ReportCostVarianceTool.CREATED_DATE_BASIS.contains("按批次创建日期")
                && ReportCostVarianceTool.CREATED_DATE_BASIS.contains("不能直接对比"));
    }
}
