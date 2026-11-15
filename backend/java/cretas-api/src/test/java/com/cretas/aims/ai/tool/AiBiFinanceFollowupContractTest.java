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

    // ---- 3. 竞品拒答 ----
    @Test
    void competitorQuestionIsRefusedForOwnDataToolsOnly() {
        assertTrue(OwnFactoryDataOnlyGuard.refuses("report_cost_query", "竞争对手的卤牛肉成本是多少"));
        assertTrue(OwnFactoryDataOnlyGuard.refuses("report_sku_gross_margin", "同行的毛利率一般多少"));
        assertFalse(OwnFactoryDataOnlyGuard.refuses("report_cost_query", "本月卤牛肉的总成本是多少"));
        assertFalse(OwnFactoryDataOnlyGuard.refuses("some_other_tool", "竞争对手的卤牛肉成本是多少"));
        assertTrue(OwnFactoryDataOnlyGuard.MESSAGE.contains("本厂"));
    }

    // ---- 1. 产品名要被抽出来: 只有可选参数的工具必须声明 EXTRACT_OPTIONAL_FLAG ----
    @Test
    void costMarginYieldToolsOptInToOptionalParameterExtraction() {
        for (ToolExecutor t : new ToolExecutor[]{new ReportCostQueryTool(), new SkuGrossMarginTool(), new ReportBatchYieldRankTool()}) {
            Map<String, Object> schema = t.getParametersSchema();
            assertEquals(Boolean.TRUE, schema.get(ToolDispatchService.EXTRACT_OPTIONAL_FLAG), t.getToolName());
            assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("productName"), t.getToolName());
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
    }
}
