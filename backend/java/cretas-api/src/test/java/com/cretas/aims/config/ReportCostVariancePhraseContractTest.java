package com.cretas.aims.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本差异的问题走只读的成本差异意图 (S6 复测 #1394, acc-s6, F006): 以前「成本差异」「成本差异分析」
 * 「实际成本和计划成本差异」「成本率分析」都落到 COST_QUERY —— 它绑的 report_cost_query 生成并保存 AI 成本分析报告 (写),
 * 咨询页里被写闸拦成「这是操作类请求」; 「成本差异报表」落到看板概览。
 */
class ReportCostVariancePhraseContractTest {
    private static IntentKnowledgeBase knowledge;

    @BeforeAll
    static void initializeRealKnowledgeBase() {
        knowledge = new IntentKnowledgeBase();
        knowledge.initDefaults();
    }

    @ParameterizedTest
    @ValueSource(strings = {"成本差异", "成本差异分析", "实际成本和计划成本差异", "成本差异报表",
            "看一下本月的成本差异", "上个月实际成本和计划成本差异多少"})
    void costVarianceQuestionsRouteToTheReadOnlyCostVarianceIntent(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("REPORT_COST_VARIANCE");
    }

    /**
     * 成本率 = 成本 / 售价 = 1 - 毛利率。成本差异工具不算成本率 (成本结构占比字段恒为空), 按产品给收入、成本、
     * 毛利率的是 SKU 毛利率 (只读工具 report_sku_gross_margin)。
     */
    @ParameterizedTest
    @ValueSource(strings = {"成本率分析", "看看成本率"})
    void costRateQuestionsRouteToSkuGrossMargin(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("SKU_GROSS_MARGIN");
    }

    /** 阴性对照: 明确在问成本支出、要生成分析的说法照旧走 COST_QUERY (那是写, 在操作页走)。 */
    @ParameterizedTest
    @ValueSource(strings = {"本月成本花了多少", "成本这块控制得怎么样"})
    void costSpendQuestionsKeepCostQuery(String input) {
        assertThat(knowledge.matchPhrase(input, "FACTORY")).contains("COST_QUERY");
    }
}
