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

    public static final String STATUS = "EXTERNAL_DATA_REFUSED";

    /**
     * 强主语: 明确的他厂 —— 竞争对手 / 竞品 / 同行 / 友商 / 其他厂家 / 隔壁厂 / 行业或市场均值。出现时<b>不受</b>本厂往来豁免:
     * 「竞争对手的进价」「同行的采购成本」「竞品的报价」仍拒 (#1788 增量复审: 豁免曾让它们落到采购价类工具, 拿本厂数答竞品)。
     * ⛔ 不含「其他工厂 / 其他公司 / 别的厂」: 集团账号问的其他工厂、本厂卖给的其他公司都是本厂往来 (#1788 审查 M1)。
     * 「市场价」不在这里: 可能是本厂采购口径 (市场价采购), 不处理。
     */
    private static final Pattern STRONG_EXTERNAL_SUBJECT = Pattern.compile(
            "竞争对手|竞争者|竞品|竞对|同行|友商|(?<!竞争)对手(?!机)|其他厂家|别的厂家|同类厂家|隔壁厂|隔壁工厂|行业平均|行业均值|市场均价|市场平均");

    /** 弱主语: 「别家 / 别人家」—— 可能是别家供应商 / 卖给别家的客户, 只在没有本厂往来语境时才算他厂。 */
    private static final Pattern WEAK_EXTERNAL_SUBJECT = Pattern.compile("别家|别人家");

    /** Q18 那类要的指标: 成本 / 价格 / 毛利 / 进价 / 报价 (不含产量、合格率等 —— 那些若落到本厂数据工具, 由 {@link #refuses} 兜)。 */
    private static final Pattern COST_PRICE_MARGIN = Pattern.compile("成本|价格|价钱|单价|售价|定价|进价|报价|多少钱|毛利|利润|盈利|赚多少");

    /** 本厂与客户 / 供应商往来的语境: 别家供应商的报价、卖给别家的价格、供应商比价 —— 都是本厂数据, 照常放行 (只对弱主语生效)。 */
    private static final Pattern OWN_TRADE_CONTEXT = Pattern.compile("供应商|供货|客户|卖给|卖到|买家|采购|进货|进价|报价|比价|询价");

    /**
     * 识别之前 (普通 / 流式 / 多意图 / 显式意图入口) 用: 问他厂的成本、价格、毛利 ⇒ 整轮拒答, 不看落到哪个意图、
     * 也不看调用者有没有权限 (2026-11-16 复跑 Q18: viewer 先被成本权限拒, sales_manager 落到「我没有理解您的意图」)。
     * 只拒答、不执行任何查询, 不扩大可见数据。强主语优先: 有强主语就拒, 不看本厂往来语境; 只有弱主语 (别家) 时,
     * 句子里有本厂往来语境才放行。更宽的问法仍由工具级 {@link #refuses} 在本厂数据工具上兜。
     */
    public static boolean refusesBeforeRouting(String userInput) {
        if (userInput == null || !COST_PRICE_MARGIN.matcher(userInput).find()) {
            return false;
        }
        if (STRONG_EXTERNAL_SUBJECT.matcher(userInput).find()) {
            return true;
        }
        return WEAK_EXTERNAL_SUBJECT.matcher(userInput).find() && !OWN_TRADE_CONTEXT.matcher(userInput).find();
    }
}
