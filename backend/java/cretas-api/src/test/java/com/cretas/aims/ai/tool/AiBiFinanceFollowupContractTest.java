package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.tool.impl.report.ReportBatchYieldRankTool;
import com.cretas.aims.ai.tool.impl.report.ReportCostQueryTool;
import com.cretas.aims.ai.tool.impl.report.SkuGrossMarginTool;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.service.execution.ToolDispatchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-15 财务账号复跑题单暴露的口径问题: 每项一条回归断言 (跑在真实规则 / 真实工具声明上)。 */
class AiBiFinanceFollowupContractTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);
    private final TimeNormalizationRules rules = new TimeNormalizationRules();

    // ---- 2. 「明年一月」 ----
    @Test
    void nextYearJanuary_isThatMonthOnly_notTheWholeYear() {
        var r = rules.extractAndReplace("明年一月卤牛肉的单位成本是多少", NOW).getFirstRange().orElseThrow();
        assertEquals(LocalDate.of(2027, 1, 1), r.getStart().toLocalDate());
        assertEquals(LocalDate.of(2027, 1, 31), r.getEnd().toLocalDate()); // = 半开区间 [2027-01-01, 2027-02-01)
        assertEquals(1, rules.extractAndReplace("明年一月卤牛肉的单位成本是多少", NOW).getExtractedRanges().size());
    }

    @Test
    void yearPrefixedMonthVariants_andPlainYearUnchanged() {
        var feb = rules.extractAndReplace("去年2月成本", NOW).getFirstRange().orElseThrow();
        assertEquals(LocalDate.of(2025, 2, 1), feb.getStart().toLocalDate());
        assertEquals(LocalDate.of(2025, 2, 28), feb.getEnd().toLocalDate());
        var dec = rules.extractAndReplace("今年十二月销售额", NOW).getFirstRange().orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 1), dec.getStart().toLocalDate());
        var year = rules.extractAndReplace("明年的成本", NOW).getFirstRange().orElseThrow();
        assertEquals(LocalDate.of(2027, 12, 31), year.getEnd().toLocalDate());
    }

    // PR #1739 M6/N1: 「年 + 月」规则不得把区间 / 以来 / 起 / 具体日期吞成单月 —— 精确区间断言 (assertEquals), 不是「不等于某值」
    private void assertRange(String text, LocalDate start, LocalDate end) {
        var res = rules.extractAndReplace(text, NOW);
        assertEquals(1, res.getExtractedRanges().size(), text + " -> " + res.getExtractedRanges());
        var r = res.getFirstRange().orElseThrow();
        assertEquals(start, r.getStart().toLocalDate(), text + " 起");
        assertEquals(end, r.getEnd().toLocalDate(), text + " 止");
    }

    @Test
    void yearPrefixedMonthRanges_areRecognisedAsAWholeRange() {
        LocalDate marStart = LocalDate.of(2026, 3, 1);
        assertRange("今年3月到5月的成本", marStart, LocalDate.of(2026, 5, 31));
        assertRange("今年3月份到5月份的成本", marStart, LocalDate.of(2026, 5, 31));
        assertRange("今年三月份到五月份的成本", marStart, LocalDate.of(2026, 5, 31));
        assertRange("去年12月到今年2月的成本", LocalDate.of(2025, 12, 1), LocalDate.of(2026, 2, 28));
        assertRange("今年3月份以来的成本", marStart, NOW.toLocalDate());
        assertRange("今年3月起的成本", marStart, NOW.toLocalDate());
        assertRange("今年3月开始的成本", marStart, NOW.toLocalDate());
    }

    // R3 New-1: 以后/之后 = 「以来」(起点=该月1日, 止=今天); 以前/之前/前/和/、 不是单月, 回落整年 (偏宽不丢数据)
    @Test
    void afterBeforeAndConjunctionAfterAMonth_areNotCollapsedToThatSingleMonth() {
        LocalDate marStart = LocalDate.of(2026, 3, 1);
        LocalDate yearEnd = LocalDate.of(2026, 12, 31);
        LocalDate yearStart = LocalDate.of(2026, 1, 1);
        assertRange("今年3月以后的成本", marStart, NOW.toLocalDate());
        assertRange("今年3月之后的成本", marStart, NOW.toLocalDate());
        assertRange("今年3月之前的成本", yearStart, yearEnd);
        assertRange("今年3月以前的成本", yearStart, yearEnd);
        assertRange("今年3月前的成本", yearStart, yearEnd);
        assertRange("今年3月和4月的成本", yearStart, yearEnd);
        assertRange("今年3月、4月的成本", yearStart, yearEnd);
    }

    // R3 New-2: 第二个月没写年份且比起始月小 = 跨年
    @Test
    void rangeWhoseSecondMonthHasNoYear_crossesTheYearWhenItIsEarlierThanTheFirst() {
        assertRange("去年12月到2月的成本", LocalDate.of(2025, 12, 1), LocalDate.of(2026, 2, 28));
        assertRange("今年3月到5月的成本", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31));
    }

    @Test
    void yearPrefixedMonthWithADayIsNotASingleMonth_andPlainSingleMonthsStillWork() {
        // 「今年10月1日」: 不是单月, 落整年 (偏宽但不丢数据)
        assertRange("今年10月1日的成本", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        // 阴性对照: 单月照常
        assertRange("今年3月份的成本", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertRange("去年2月成本", LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28));
    }

    // ---- 3. 竞品拒答 ----
    @Test
    void competitorQuestionIsRefusedForOwnDataToolsOnly() {
        assertTrue(OwnFactoryDataOnlyGuard.refuses("report_cost_query", "竞争对手的卤牛肉成本是多少"));
        assertTrue(OwnFactoryDataOnlyGuard.refuses("report_sku_gross_margin", "同行的毛利率一般多少"));
        assertFalse(OwnFactoryDataOnlyGuard.refuses("report_cost_query", "本月卤牛肉的总成本是多少"));
        assertFalse(OwnFactoryDataOnlyGuard.refuses("some_other_tool", "竞争对手的卤牛肉成本是多少"));
        assertTrue(OwnFactoryDataOnlyGuard.MESSAGE.contains("本厂"));
        // 阴性对照 (L1): 这些都是本厂问法, 不拒
        for (String ok : new String[]{"对比上月的卤牛肉成本", "供应商报价和卤牛肉成本", "客户A的毛利率", "目录价和成本对比", "手机端对手机型号成本"}) {
            assertFalse(OwnFactoryDataOnlyGuard.refuses("report_cost_query", ok), ok);
        }
        assertTrue(OwnFactoryDataOnlyGuard.refuses("report_cost_query", "对手的毛利率多少"));
        assertTrue(OwnFactoryDataOnlyGuard.refuses("report_cost_trend_analysis", "竞品的成本趋势"));
    }

    // ---- 1. 产品名要被抽出来: 只有可选参数的工具必须声明 EXTRACT_OPTIONAL_FLAG ----
    @Test
    void costMarginYieldToolsOptInToOptionalParameterExtraction() {
        for (ToolExecutor t : new ToolExecutor[]{new ReportCostQueryTool(), new SkuGrossMarginTool(), new ReportBatchYieldRankTool()}) {
            Map<String, Object> schema = t.getParametersSchema();
            assertEquals(Boolean.TRUE, schema.get(ToolDispatchService.EXTRACT_OPTIONAL_FLAG), t.getToolName());
            assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("productName"), t.getToolName());
            // 日期键带 format:date, 让 LlmParameterGrounding 丢掉原话里没说过的日期 (H2)
            Map<?, ?> props = (Map<?, ?>) schema.get("properties");
            for (String k : new String[]{"startDate", "endDate"}) {
                if (props.containsKey(k)) assertEquals("date", ((Map<?, ?>) props.get(k)).get("format"), t.getToolName() + "." + k);
            }
        }
    }

    // #1735 的两个带产品名工具 + 成本差异: 同样声明抽取, 日期带 format:date (M7 成本差异可按产品名)
    @Test
    void newBiToolsAndCostVarianceOptInAndGroundDates() {
        for (ToolExecutor t : new ToolExecutor[]{new com.cretas.aims.ai.tool.impl.report.ReportBomCostRankTool(),
                new com.cretas.aims.ai.tool.impl.report.ReportLaborCostShareTool(),
                new com.cretas.aims.ai.tool.impl.report.ReportCostVarianceTool()}) {
            Map<String, Object> schema = t.getParametersSchema();
            assertEquals(Boolean.TRUE, schema.get(ToolDispatchService.EXTRACT_OPTIONAL_FLAG), t.getToolName());
            Map<?, ?> props = (Map<?, ?>) schema.get("properties");
            assertTrue(props.containsKey("productName"), t.getToolName());
            for (String k : new String[]{"startDate", "endDate"}) {
                if (props.containsKey(k)) assertEquals("date", ((Map<?, ?>) props.get(k)).get("format"), t.getToolName() + "." + k);
            }
        }
    }

    // M2: 拒答清单里的每个名字都必须是真实存在的工具 (改名后清单不会悄悄失效)
    @Test
    void everyGuardedToolNameIsARealRegisteredTool() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/cretas/aims/ai/tool/impl");
        StringBuilder all = new StringBuilder();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                all.append(java.nio.file.Files.readString(f));
            }
        }
        for (String name : OwnFactoryDataOnlyGuard.ownDataToolNames()) {
            assertTrue(all.toString().contains("return \"" + name + "\";"), "拒答清单里的工具不存在: " + name);
        }
    }

    // ---- 4 / 5. 短语路由 ----
    @Test
    void standardCostAndMonthOverMonthPhrasesRouteToTheRightIntents() {
        IntentKnowledgeBase kb = new IntentKnowledgeBase();
        kb.initDefaults();
        Map<String, String> phrases = kb.getPhraseToIntentMapping();
        assertEquals("REPORT_COST_VARIANCE", phrases.get("实际成本和标准成本差多少"));
        assertEquals("COST_QUERY", phrases.get("和上月比哪个成本涨了"));
        // 短语是向量相似匹配, 优先级无法静态断言; 能守的是「不收太宽的短语」: 没有「上月」限定的「哪个成本涨了」不进 COST_QUERY
        assertNull(phrases.get("哪个成本涨了"));
    }
}
