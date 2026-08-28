package com.cretas.aims.service.canvas;

/**
 * Canvas-Thresholds 阈值键常量 (Phase A P0-1).
 *
 * <p>类型安全的 key 引用 — service 调用 {@code thresholdResolverService.getXxx(factoryId, KEY, default)}
 * 必须使用此处声明的常量, 避免 typo 导致默认值静默 fallback。
 *
 * <p>命名规则: {category}.{subdomain}.{name} (lowercase + underscore).
 *
 * @since Canvas Phase A (2026-05-21)
 */
public final class ThresholdKeys {

    private ThresholdKeys() {
        // utility class
    }

    // ==================== 库存健康 (INVENTORY) ====================

    /** 周转率红色预警 (次/年). Hard-coded default = 6. */
    public static final String INVENTORY_TURNOVER_RED = "inventory.turnover.red";
    /** 周转率黄色预警 (次/年). Hard-coded default = 12. */
    public static final String INVENTORY_TURNOVER_YELLOW = "inventory.turnover.yellow";

    /** 临期风险率红色预警 (%). Hard-coded default = 15. */
    public static final String INVENTORY_EXPIRY_RED = "inventory.expiry.red";
    /** 临期风险率黄色预警 (%). Hard-coded default = 10. */
    public static final String INVENTORY_EXPIRY_YELLOW = "inventory.expiry.yellow";

    /** 损耗率红色预警 (%). Hard-coded default = 5. */
    public static final String INVENTORY_LOSS_RED = "inventory.loss.red";
    /** 损耗率黄色预警 (%). Hard-coded default = 2. */
    public static final String INVENTORY_LOSS_YELLOW = "inventory.loss.yellow";

    /** 库龄新鲜段 (天). Hard-coded default = 30. */
    public static final String INVENTORY_AGING_FRESH = "inventory.aging.fresh_days";
    /** 库龄正常段 (天). Hard-coded default = 60. */
    public static final String INVENTORY_AGING_NORMAL = "inventory.aging.normal_days";
    /** 库龄预警段 (天). Hard-coded default = 90. */
    public static final String INVENTORY_AGING_WARNING = "inventory.aging.warning_days";

    /** 临期预警天数 (天). Hard-coded default = 30. */
    public static final String INVENTORY_EXPIRY_WARNING_DAYS = "inventory.expiry.warning_days";
    /** 高风险临期阈值 (天). Hard-coded default = 7. */
    public static final String INVENTORY_HIGH_RISK_EXPIRY_DAYS = "inventory.expiry.high_risk_days";

    // ==================== IoT 冷链 / 环境 (IOT) ====================

    /** 冷链温度上限 (°C). Hard-coded default = -18.0. */
    public static final String IOT_COLD_CHAIN_TEMP_MAX = "iot.cold_chain.temp_max";
    /** 常温温度下限 (°C). Hard-coded default = 0.0. */
    public static final String IOT_NORMAL_TEMP_MIN = "iot.normal.temp_min";
    /** 常温温度上限 (°C). Hard-coded default = 25.0. */
    public static final String IOT_NORMAL_TEMP_MAX = "iot.normal.temp_max";
    /** 湿度下限 (%). Hard-coded default = 40.0. */
    public static final String IOT_HUMIDITY_MIN = "iot.humidity.min";
    /** 湿度上限 (%). Hard-coded default = 70.0. */
    public static final String IOT_HUMIDITY_MAX = "iot.humidity.max";

    // ==================== 客户信用 (CREDIT) ====================

    /** 信用额度接近预警比 (used / limit). Hard-coded default = 0.80. */
    public static final String CREDIT_WARNING_RATIO = "credit.warning_ratio";

    // ==================== BOM (BOM) ====================

    /** BOM 递归展开最大深度. Hard-coded default = 10. */
    public static final String BOM_MAX_DEPTH = "bom.max_depth";

    // ==================== 缺料分析 (SHORTAGE) ====================

    /** 生产建议默认 lead time (天). Hard-coded default = 7. */
    public static final String SHORTAGE_DEFAULT_PRODUCTION_LEAD_DAYS = "shortage.production_lead_days";

    // ==================== AI 复杂度路由 (AI) ====================

    /** Fast mode 阈值. Hard-coded default = 0.3. */
    public static final String AI_COMPLEXITY_FAST_THRESHOLD = "ai.complexity.fast_threshold";
    /** Analysis mode 阈值. Hard-coded default = 0.6. */
    public static final String AI_COMPLEXITY_ANALYSIS_THRESHOLD = "ai.complexity.analysis_threshold";
    /** Multi-agent mode 阈值. Hard-coded default = 0.8. */
    public static final String AI_COMPLEXITY_MULTI_AGENT_THRESHOLD = "ai.complexity.multi_agent_threshold";

    // ==================== 价格策略 (PRICING) ====================

    /** Fool-proof rule warning threshold: totalDiscount / originalPrice. Hard-coded default = 0.50 (50%). */
    public static final String PRICING_DEEP_DISCOUNT_RATIO = "pricing.warning.deep_discount_ratio";

    // ==================== 生产报工 (PRODUCTION) ====================

    /** Quantity overshoot tolerance: 累计完工 / plannedQuantity 上限. Hard-coded default = 1.10 (110%). */
    public static final String PRODUCTION_OVERSHOOT_TOLERANCE = "production.quantity.overshoot_tolerance";

    // ==================== 采购订单审批 (PURCHASE) ====================

    /** Purchase price variance alert threshold (%). Hard-coded default = 10. Per-factory override via PurchaseOrderApprovalRule.priceVarianceThreshold takes precedence; this is final fallback. */
    public static final String PURCHASE_PRICE_ALERT_RATIO = "purchase.price_alert.ratio";

    // ==================== 采购分析 (PROCUREMENT) ====================

    /** 质量合格率红色预警阈值 (%). Hard-coded default = 90. */
    public static final String PROCUREMENT_QUALITY_RED = "procurement.quality.red";
    /** 质量合格率黄色预警阈值 (%). Hard-coded default = 95. */
    public static final String PROCUREMENT_QUALITY_YELLOW = "procurement.quality.yellow";
    /** 供应商集中度红色预警阈值 (%). Hard-coded default = 60. */
    public static final String PROCUREMENT_CONCENTRATION_RED = "procurement.concentration.red";
    /** 供应商集中度黄色预警阈值 (%). Hard-coded default = 40. */
    public static final String PROCUREMENT_CONCENTRATION_YELLOW = "procurement.concentration.yellow";

    // ==================== 生产分析 (PRODUCTION_ANALYSIS) ====================

    /** OEE 红色预警阈值 (%). Hard-coded default = 65. */
    public static final String PRODUCTION_OEE_RED = "production.oee.red";
    /** OEE 黄色预警阈值 (%). Hard-coded default = 85. */
    public static final String PRODUCTION_OEE_YELLOW = "production.oee.yellow";
    /** 可用性红色预警阈值 (%). Hard-coded default = 80. */
    public static final String PRODUCTION_AVAILABILITY_RED = "production.availability.red";
    /** 可用性黄色预警阈值 (%). Hard-coded default = 90. */
    public static final String PRODUCTION_AVAILABILITY_YELLOW = "production.availability.yellow";
    /** 性能红色预警阈值 (%). Hard-coded default = 75. */
    public static final String PRODUCTION_PERFORMANCE_RED = "production.performance.red";
    /** 性能黄色预警阈值 (%). Hard-coded default = 90. */
    public static final String PRODUCTION_PERFORMANCE_YELLOW = "production.performance.yellow";
    /** 生产质量红色预警阈值 (%). Hard-coded default = 95. */
    public static final String PRODUCTION_QUALITY_RED = "production.quality.red";
    /** 生产质量黄色预警阈值 (%). Hard-coded default = 98. */
    public static final String PRODUCTION_QUALITY_YELLOW = "production.quality.yellow";

    // ==================== 质量分析 (QUALITY_ANALYSIS) ====================

    /** FPY (First Pass Yield) 红色预警阈值 (%). Hard-coded default = 95. */
    public static final String QUALITY_FPY_RED = "quality.fpy.red";
    /** FPY 黄色预警阈值 (%). Hard-coded default = 98. */
    public static final String QUALITY_FPY_YELLOW = "quality.fpy.yellow";
    /** 缺陷率红色预警阈值 (%). Hard-coded default = 5. */
    public static final String QUALITY_DEFECT_RATE_RED = "quality.defect_rate.red";
    /** 缺陷率黄色预警阈值 (%). Hard-coded default = 2. */
    public static final String QUALITY_DEFECT_RATE_YELLOW = "quality.defect_rate.yellow";
    /** 质量成本占比红色预警阈值 (%). Hard-coded default = 3. */
    public static final String QUALITY_COST_RED = "quality.cost.red";
    /** 质量成本占比黄色预警阈值 (%). Hard-coded default = 1.5. */
    public static final String QUALITY_COST_YELLOW = "quality.cost.yellow";
    /** 返工率红色预警阈值 (%). Hard-coded default = 20. */
    public static final String QUALITY_REWORK_RATE_RED = "quality.rework_rate.red";
    /** 返工率黄色预警阈值 (%). Hard-coded default = 10. */
    public static final String QUALITY_REWORK_RATE_YELLOW = "quality.rework_rate.yellow";

    // ==================== 销售分析 (SALES) ====================

    /** 销售目标达成率红色预警阈值 (%). Hard-coded default = 60. */
    public static final String SALES_TARGET_RED = "sales.target.red";
    /** 销售目标达成率黄色预警阈值 (%). Hard-coded default = 85. */
    public static final String SALES_TARGET_YELLOW = "sales.target.yellow";
    /** 销售毛利率红色预警阈值 (%). Hard-coded default = 15. */
    public static final String SALES_MARGIN_RED = "sales.margin.red";
    /** 销售毛利率黄色预警阈值 (%). Hard-coded default = 25. */
    public static final String SALES_MARGIN_YELLOW = "sales.margin.yellow";
    /** 销售增长率红色预警阈值 (%). Hard-coded default = -20. */
    public static final String SALES_GROWTH_RED = "sales.growth.red";
    /** 销售增长率黄色预警阈值 (%). Hard-coded default = -5. */
    public static final String SALES_GROWTH_YELLOW = "sales.growth.yellow";

    /**
     * 发货【超发上限比率】—— 仓管确认实发数量时，实发相对计划发货量的最大溢出。
     *
     * <p>值是<b>小数比率</b>（{@code 0.10} = 10%），与采购侧
     * {@code cretas.purchase.over-receive-rate} 同一约定。⛔ 不是百分数。
     *
     * <p>语义（与 {@link com.cretas.aims.service.inventory.OverReceiveLimit} 一致，
     * 这两个值必须读成同一套语义，否则同一个 {@code 0} 在两条链上会有相反含义）：
     * <ul>
     *   <li><b>没有这一行 / {@code enabled=false}</b> → 不限制（不做上限校验）</li>
     *   <li>{@code 0} → 一件都不许超发（<b>这是一个真实的限制，不是"没配"</b>）</li>
     *   <li>{@code 0.10} → 实发最多是计划的 110%</li>
     * </ul>
     *
     * <p>全局默认见迁移 {@code V20261101_03__sales_over_ship_threshold.sql}。
     * 执行侧唯一入口：{@code SalesServiceImpl.resolveOverShipLimit}。
     *
     * @since 2026-08-28
     */
    public static final String SALES_DELIVERY_OVER_SHIP_RATE = "sales.delivery.over_ship_rate";

    // ==================== 财务分析 (FINANCE) ====================

    /** 账龄 90+ 天占比红色预警阈值 (%). Hard-coded default = 20. */
    public static final String FINANCE_AGING_90_RED = "finance.aging_90.red";
    /** 账龄 90+ 天占比黄色预警阈值 (%). Hard-coded default = 10. */
    public static final String FINANCE_AGING_90_YELLOW = "finance.aging_90.yellow";
    /** 预算执行率红色预警阈值 (%). Hard-coded default = 120. */
    public static final String FINANCE_BUDGET_EXECUTION_RED = "finance.budget_execution.red";
    /** 预算执行率黄色预警阈值 (%). Hard-coded default = 100. */
    public static final String FINANCE_BUDGET_EXECUTION_YELLOW = "finance.budget_execution.yellow";

    // ==================== 加工分析 (PROCESSING) ====================

    /** 加工合格率 PASS 阈值 (%). Hard-coded default = 95. */
    public static final String PROCESSING_PASS_THRESHOLD = "processing.quality.pass_threshold";
    /** 加工合格率 FAIL 阈值 (%). Hard-coded default = 70. */
    public static final String PROCESSING_FAIL_THRESHOLD = "processing.quality.fail_threshold";

    // ==================== 个人效率 (EFFICIENCY) ====================

    /** 个人效率默认值. Hard-coded default = 1.0. */
    public static final String EFFICIENCY_DEFAULT = "efficiency.individual.default";
    /** 个人效率下限. Hard-coded default = 0.1. */
    public static final String EFFICIENCY_MIN = "efficiency.individual.min";
    /** 个人效率上限. Hard-coded default = 2.0. */
    public static final String EFFICIENCY_MAX = "efficiency.individual.max";

    // ==================== 闸门类阈值登记 (GATING) ====================

    /**
     * <b>闸门类</b>阈值 —— 超过它<b>会挡住一个用户动作</b>（抛异常 / 返回 4xx），
     * 而不是只染个红黄色。
     *
     * <h3>为什么要把这一类单独登记出来</h3>
     * {@link com.cretas.aims.service.canvas.impl.ThresholdResolverServiceImpl} 的
     * 本地缓存 TTL 是 5 分钟，写入后只失效<b>本节点</b>（跨节点失效未实现）。
     * 对「预警红黄线」这没关系 —— 晚 5 分钟变色没人受伤。
     * 对闸门类是另一回事：多实例部署时管理员在 A 节点把
     * {@link #SALES_DELIVERY_OVER_SHIP_RATE} 调宽了，B 节点最长 5 分钟内<b>仍按旧值 409 拦货</b>，
     * 现场看到的是「我刚调宽了怎么还是发不了」，而<b>每一层都不报错</b>。
     * ⇒ 登记在这里的 key 走一条 TTL 短得多的缓存（见 resolver 的 {@code GATING_CACHE_TTL}）。
     *
     * <h3>⛔ 为什么是「显式登记」而不是「按命名约定推导」</h3>
     * 推导需要 key 名字本身能区分闸门与预警，而现有 key 里
     * {@code sales.delivery.over_ship_rate}（闸门）和 {@code sales.margin.red}（预警）
     * 在命名上没有任何可区分的成分。要靠约定就得<b>改 key 名</b>，
     * 而 key 是<b>落在库里的数据</b>（{@code factory_thresholds.threshold_key}）——
     * 那是一次迁移，且任何不守约定的新 key 都会被<b>静默</b>分错类（分错的症状正是本次要修的那个）。
     * 显式登记是<b>一处</b>定义、加一个 key 是一次看得见的动作。
     *
     * <p>⚠️ 这是这个概念的<b>唯一</b>定义处。⛔ 不要在 resolver / controller / 前端再写第二份
     * 「哪些算闸门」的清单 —— 同一个东西有两份，它一定会漂。
     *
     * <h3>登记判据（加 key 前对着念一遍）</h3>
     * 「读到旧值时，用户的动作是<b>被挡下来</b>，还是<b>照常完成但显示得不对</b>？」
     * 被挡下来 ⇒ 登记；显示得不对 ⇒ 不登记。
     *
     * <h3>⚠️ 显式登记「故意不登记」的近似项（不登记 ≠ 漏了）</h3>
     * <ul>
     *   <li>{@link #PURCHASE_PRICE_ALERT_RATIO} —— {@code PurchaseServiceImpl.resolvePriceAlertThreshold}
     *       只用它<b>触发审批/告警</b>，不拒绝请求</li>
     *   <li>{@link #PRICING_DEEP_DISCOUNT_RATIO} —— 防呆<b>警告</b>文案，不拦下单</li>
     *   <li>{@link #BOM_MAX_DEPTH} —— 递归安全上限，是程序自保不是业务闸；
     *       调它的人是开发不是现场管理员，5 分钟窗口不产生「我刚调宽了怎么还是不行」</li>
     *   <li>{@link #PRODUCTION_OVERSHOOT_TOLERANCE} —— javadoc 写得像闸门，但
     *       <b>全仓没有任何调用点</b>（2026-08-29 实测：除本文件外 0 处引用）。
     *       等它真被接上执行链的那天再登记；现在登记只会让人以为它生效了。</li>
     *   <li>所有 {@code *.red} / {@code *.yellow} —— 按定义就是染色，不挡动作</li>
     * </ul>
     *
     * @since 2026-08-29
     */
    public static final java.util.Set<String> GATING_KEYS = java.util.Set.of(
            SALES_DELIVERY_OVER_SHIP_RATE
    );

    /**
     * 这个 key 是不是闸门类（读到旧值会挡住用户动作）。
     *
     * <p>{@code null} 一律返回 {@code false} —— 「不知道」按<b>非</b>闸门处理，
     * 保守方向是走标准缓存，不会因为一个空 key 把所有读取都打到 DB。
     */
    public static boolean isGating(String thresholdKey) {
        return thresholdKey != null && GATING_KEYS.contains(thresholdKey);
    }
}
