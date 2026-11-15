package com.cretas.aims.ai.tool;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 成本 / 毛利 / 出成率这类工具只有本厂自己的数据。用户问「竞争对手的卤牛肉成本」时, 工具不认识「竞争对手」,
 * 会照样答本厂的数 —— 把本厂数当成竞品数 (2026-11-15 财务复跑 Q18)。命中时整轮拒答并说明, 不执行查询。
 *
 * <p>覆盖范围: 目前只接在 ToolDispatchService.prepareReadTurn (只读轮的调度入口)。Skill 编排 (SkillExecutorImpl)、
 * 自动规划 (DynamicToolSelectionService) 与 MCP 适配器直接调 tool.execute, 不经过这里 — 已知缺口, 待统一挪到共用执行入口。
 * 「行业平均 / 市场均价」会整轮拒答, 包括问题里本厂的那一半 (宁可拒也不拿本厂数冒充)。
 */
public final class OwnFactoryDataOnlyGuard {

    public static final String MESSAGE = "只能查询本厂自己的数据，没有竞争对手、同行或市场上其他厂家的成本、价格、毛利数据，"
            + "也不会拿本厂的数字代替；未执行查询。";

    /** 只读、数据全部来自本厂批次 / 订单 / BOM 的工具。 */
    private static final Set<String> OWN_DATA_TOOLS = Set.of(
            "report_cost_query", "report_sku_gross_margin", "report_batch_yield_rank", "report_cost_variance",
            "report_bom_cost", "report_bom_cost_rank", "report_labor_cost_share", "report_sales_margin_summary",
            "report_cost_trend_analysis", "report_finance", "report_finance_ratio", "report_sales", "efficiency_comparison");

    /** 清单是否都是已注册工具由 AiBiFinanceFollowupContractTest.everyGuardedToolNameIsARealRegisteredTool 钉住 (扫工具源码里的 getToolName 返回值, 工具改名时清单不会悄悄失效)。 */
    public static Set<String> ownDataToolNames() {
        return OWN_DATA_TOOLS;
    }

    private static final Pattern EXTERNAL = Pattern.compile(
            "竞争对手|竞争者|竞品|竞对|同行|友商|(?<!竞争)对手(?!机)|别家|别人家|其他厂家|其他工厂|其他公司|别的厂|别的工厂|别的公司|行业平均|行业均值|市场均价|市场平均");

    private OwnFactoryDataOnlyGuard() {}

    public static boolean appliesTo(String toolName) {
        return toolName != null && OWN_DATA_TOOLS.contains(toolName);
    }

    public static boolean asksForExternalData(String userInput) {
        return userInput != null && EXTERNAL.matcher(userInput).find();
    }

    /** 该工具 + 这句话 = 要拒答。 */
    public static boolean refuses(String toolName, String userInput) {
        return appliesTo(toolName) && asksForExternalData(userInput);
    }
}
