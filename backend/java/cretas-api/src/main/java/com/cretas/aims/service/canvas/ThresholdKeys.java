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

    /**
     * 超信用额度检查模式 (2026-11-10 Steve 裁定, 工厂纪律 A17)。字符串, 取值 OFF / WARN / BLOCK。
     * OFF (默认) = 整个检查不做; WARN = 只提示并留痕不拦; BLOCK = 拦截, 持「信用特批」权限者填原因可放行。
     * 挡得住下单 ⇒ 登记为闸门类 key (见 {@link #GATING_KEYS})。
     */
    public static final String CREDIT_CHECK_MODE = "credit.check.mode";

    // ==================== 可配置的权限默认角色 (2026-11-10 Steve 裁定, 工厂纪律 B7 / B9 / A17) ====================
    // 「谁能做这件事」不写死岗位: 值是逗号分隔的角色码, 存工厂阈值 (没有 per-factory 行就跟随全局默认)。
    // 厂长 (factory_super_admin) 恒有权限, 不受这里的值影响。读到旧值 = 放行 / 拦住用户动作 ⇒ 登记为闸门类 key。

    /** 原料类型维护 (新建/改/删/别名/合并) 的岗位 → 默认 warehouse_manager */
    public static final String INVENTORY_PERMISSION_MATERIAL_TYPE_MAINTAIN = "inventory.permission.material_type_maintain";
    /** 报损审批 (通过/驳回/看待审) 的岗位 → 默认只有厂长 (空) */
    public static final String INVENTORY_PERMISSION_WASTAGE_APPROVE = "inventory.permission.wastage_approve";
    /** 报废审批 (通过/驳回) 的岗位 → 默认 finance_manager (改动前写死的名单), 厂长恒有 */
    public static final String INVENTORY_PERMISSION_DISPOSAL_APPROVE = "inventory.permission.disposal_approve";
    /** 付款执行 (出纳点已付款, 「我的待办」按它聚合付款待办) 的岗位 → 默认 cashier, 厂长恒有 */
    public static final String FINANCE_PERMISSION_PAYMENT_DISBURSE = "finance.permission.payment_disburse";
    /** 信用特批 (超信用额度被拦截时放行) 的岗位 → 默认只有厂长 (空) */
    public static final String CREDIT_PERMISSION_OVERRIDE = "credit.permission.override";

    // ==================== 通知接收人 (2026-11-10 Steve 裁定, 工厂纪律 B12) ====================
    // 每类通知发给谁由工厂配置: 值是逗号分隔的角色码; 没配 / 配的角色全都没有人 ⇒ 默认发给厂长, 不丢。
    // 归类沿用现有分类 (PRODUCTION / INVENTORY), 不新增分类, 以免前端那份平行 TS 联合类型要跟着改。

    /** 新调度计划已创建 → 默认 dispatcher,factory_super_admin */
    public static final String PRODUCTION_NOTIFY_SCHEDULE_CREATED = "production.notify.schedule_created";
    /** 调度计划已确认 → 默认 workshop_supervisor */
    public static final String PRODUCTION_NOTIFY_SCHEDULE_CONFIRMED = "production.notify.schedule_confirmed";
    /** 调度计划延期预警 → 默认 dispatcher,factory_super_admin,workshop_supervisor */
    public static final String PRODUCTION_NOTIFY_SCHEDULE_DELAYED = "production.notify.schedule_delayed";
    /** 紧急插单 → 默认 dispatcher,factory_super_admin,workshop_supervisor */
    public static final String PRODUCTION_NOTIFY_URGENT_INSERT = "production.notify.urgent_insert";
    /** 生产进度严重落后预警 (效率检测) → 默认 dispatcher,workshop_supervisor */
    public static final String PRODUCTION_NOTIFY_EFFICIENCY_WARNING = "production.notify.efficiency_warning";
    /** AI 排产结果通知 (原来是全厂广播) → 默认 dispatcher,factory_super_admin */
    public static final String PRODUCTION_NOTIFY_AI_SCHEDULING = "production.notify.ai_scheduling";
    /** 低库存双向报警 → 默认 warehouse_manager,procurement_manager */
    public static final String INVENTORY_NOTIFY_LOW_STOCK = "inventory.notify.low_stock";
    /** 动态质检表有项目不合格但不判整张不合格 (交质量经理决定) → 默认 quality_manager (2026-11-11) */
    public static final String QUALITY_NOTIFY_INSPECTION_REVIEW = "quality.notify.inspection_review";

    // ==================== BOM (BOM) ====================

    /** BOM 递归展开最大深度. Hard-coded default = 10. */
    public static final String BOM_MAX_DEPTH = "bom.max_depth";

    // ==================== 缺料分析 (SHORTAGE) ====================

    /** 生产建议默认 lead time (天). Hard-coded default = 7. */
    public static final String SHORTAGE_DEFAULT_PRODUCTION_LEAD_DAYS = "shortage.production_lead_days";

    /**
     * 缺料实例缺口补到 0 之后怎么收尾 (2026-11-11 Steve 裁定: 做成工厂开关)。字符串, MANUAL (默认) / AUTO。
     * MANUAL = 停在「缺口已解决」, 由生产主管手工关闭 (= 原行为); AUTO = 系统直接关闭并留痕。认不出 = MANUAL。
     * 读到旧值不挡任何用户动作 (只是晚一点自动关) ⇒ 故意不登记为闸门类 key。
     */
    public static final String SHORTAGE_CASE_CLOSE_MODE = "shortage.case.close_mode";

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

    /**
     * 销售单财务批准后能不能取消 (2026-11-11 Steve 裁定: 做成工厂开关, 默认关)。整数, 1 = 开; 其它 (含 0、写错) = 关
     * (= 原行为: 只能驳回 / 退款)。开了以后有 {@link #SALES_PERMISSION_CANCEL_APPROVED_ORDER} 权限的人可带原因取消,
     * 并连带取消自动生成的生产计划和未开工排程。挡得住 / 放得过一个用户动作 ⇒ 闸门类。
     */
    public static final String SALES_ORDER_CANCEL_AFTER_APPROVAL = "sales.order.cancel_after_approval";

    /** 「取消已批准销售单」的岗位名单 (逗号分隔岗位码) → 默认只有厂长 (空)。 */
    public static final String SALES_PERMISSION_CANCEL_APPROVED_ORDER = "sales.permission.cancel_approved_order";

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

    // ==================== 工厂纪律口径 (2026-11-10 Steve: 是否拦、怎么拦由工厂配置) ====================
    // 全局默认行见 V20261111_81__factory_discipline_configs.sql; 解析与「认不出按最严」见
    // com.cretas.aims.service.discipline.FactoryDisciplinePolicy (唯一读取处)。

    /** A3 批次放行规则: QC_ONLY (默认) | QC_OR_MANUAL。认不出 = QC_ONLY。 */
    public static final String QUALITY_BATCH_RELEASE_MODE = "quality.batch_release.mode";
    /** A3 手动放行允许的角色代码, 逗号分隔; 空 = 不另限角色。 */
    public static final String QUALITY_BATCH_RELEASE_MANUAL_ROLES = "quality.batch_release.manual_roles";
    /** A3 手动放行是否必须填原因: 1 (默认) / 0。 */
    public static final String QUALITY_BATCH_RELEASE_REQUIRE_REASON = "quality.batch_release.require_reason";
    /** A7 完工必须先结单: 1 (默认) / 0。 */
    public static final String PRODUCTION_COMPLETION_REQUIRE_SETTLEMENT = "production.completion.require_settlement";
    /** A11 仓库多收: BLOCK (默认) | WITHIN_RATE | ALWAYS_WITH_REASON。认不出 = BLOCK。 */
    public static final String PRODUCTION_RECEIPT_OVER_RECEIVE_MODE = "production.receipt.over_receive_mode";
    /** A11 WITHIN_RATE 模式下的多收比例 (小数). Default = 0.05。 */
    public static final String PRODUCTION_RECEIPT_OVER_RECEIVE_RATE = "production.receipt.over_receive_rate";
    /** A13 解冻开始最多往前补登的分钟数. Default = 480; 0 = 不允许补登。 */
    public static final String PRODUCTION_THAW_BACKFILL_MAX_MINUTES = "production.time_limit.thaw_backfill_max_minutes";
    /** A14 补录窗口天数. Default = 2 (T-2)。 */
    public static final String PRODUCTION_BACKDATE_MAX_DAYS = "production.backdate.max_days";

    /**
     * 插单是否一律走审批 (1551-11, Steve 2026-11-11 拍板: 工厂开关, 默认关)。整数, 1 = 开; 其它 (含 0、写错) = 关。
     * 关 (默认) = 原行为: 只有预览判定需要审批 (影响高/严重) 的插单走强制插单审批, 其余直接排入。
     * 开 = 本厂有启用的强制插单 (FORCE_INSERT) 审批画布时, 所有插单 (含低影响的普通插单) 都先进审批, 批准后才排入;
     * 没有启用画布时开了也不生效 (没人来批)。挡得住一个用户动作 (直接排入) ⇒ 闸门类。
     */
    public static final String PRODUCTION_URGENT_INSERT_ALWAYS_APPROVAL = "production.urgent_insert.always_approval";

    /**
     * 客供料收货能不能超收 (A15, Steve 2026-11-11 拍板: 单独做成工厂开关, 默认关)。整数, 1 = 开; 其它 (含 0、写错) = 关。
     * 关 (默认) = 原行为: 客供料实收不能超过剩余待收, 多 1 克也拦。
     * 开 = 与采购收货同一个超收上限 (OverReceiveLimit: 工厂设置 purchaseOverReceiveRate > 全局属性 > 不限制)。
     * 挡得住一个现场动作 (多收) ⇒ 闸门类。
     */
    public static final String INVENTORY_CUSTOMER_SUPPLIED_ALLOW_OVER_RECEIVE =
            "inventory.customer_supplied.allow_over_receive";

    /**
     * 「袋」「盒」等包装单位的计划换线时怎么核对目标线产能 (2026-11-11 Steve 裁定: 做成工厂开关)。
     * NONE (默认) = 产线产能只认 件 / kg / 批, 包装单位计划判「产能未验证」只能同线顺延 (= 原行为);
     * NET_CONTENT = 按产品净含量把包装单位折成 kg 再核对 kg 产能 (净含量没填 / 不是质量单位 ⇒ 仍未验证, 不猜);
     * FACTORY_UNIT = 产线产能可以按工厂自己的包装单位配置 (如 袋/小时), 计划单位与之等价才核对。认不出 = NONE。
     */
    public static final String PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE = "production.capacity.package_unit_mode";

    /**
     * 动态质检表里哪些项不合格才判整张 FAIL (2026-11-11 Steve 裁定: 做成工厂开关, 默认 B)。字符串:
     * ANY_ITEM = 任一项不合格整张 FAIL (原行为); MINOR_FOR_REVIEW (默认) = 关键 / 主要项不合格整张 FAIL,
     * 只有次要项不合格时判「条件合格」并标出这些项, 交质量经理决定; CRITICAL_ONLY = 只有关键项不合格才整张 FAIL,
     * 主要 / 次要项交质量经理决定。认不出 = ANY_ITEM (最严)。没标严重程度的项按关键项算。
     * 读到旧值 = 该 FAIL 的判成条件合格 (或反过来) ⇒ 登记为闸门类 key。
     */
    public static final String QUALITY_INSPECTION_FAIL_SCOPE = "quality.inspection.fail_scope";

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
            SALES_DELIVERY_OVER_SHIP_RATE,
            // 纪律口径 (2026-11-10): 每一个都会挡住或放行一个现场动作
            QUALITY_BATCH_RELEASE_MODE,
            QUALITY_BATCH_RELEASE_MANUAL_ROLES,
            QUALITY_BATCH_RELEASE_REQUIRE_REASON,
            PRODUCTION_COMPLETION_REQUIRE_SETTLEMENT,
            PRODUCTION_RECEIPT_OVER_RECEIVE_MODE,
            PRODUCTION_RECEIPT_OVER_RECEIVE_RATE,
            PRODUCTION_THAW_BACKFILL_MAX_MINUTES,
            PRODUCTION_BACKDATE_MAX_DAYS,
            PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE,
            QUALITY_INSPECTION_FAIL_SCOPE,
            CREDIT_CHECK_MODE,
            INVENTORY_PERMISSION_MATERIAL_TYPE_MAINTAIN,
            INVENTORY_PERMISSION_WASTAGE_APPROVE,
            INVENTORY_PERMISSION_DISPOSAL_APPROVE,
            FINANCE_PERMISSION_PAYMENT_DISBURSE,
            CREDIT_PERMISSION_OVERRIDE,
            SALES_ORDER_CANCEL_AFTER_APPROVAL,
            SALES_PERMISSION_CANCEL_APPROVED_ORDER,
            PRODUCTION_URGENT_INSERT_ALWAYS_APPROVAL,
            INVENTORY_CUSTOMER_SUPPLIED_ALLOW_OVER_RECEIVE
    );

    /** 值是「逗号分隔岗位码」的 key (权限岗位名单 + 通知接收人)。保存时按 FactoryUserRole 校验, 打错字不再静默失效。 */
    public static final java.util.Set<String> ROLE_LIST_KEYS = java.util.Set.of(
            INVENTORY_PERMISSION_MATERIAL_TYPE_MAINTAIN,
            INVENTORY_PERMISSION_WASTAGE_APPROVE,
            INVENTORY_PERMISSION_DISPOSAL_APPROVE,
            FINANCE_PERMISSION_PAYMENT_DISBURSE,
            CREDIT_PERMISSION_OVERRIDE,
            SALES_PERMISSION_CANCEL_APPROVED_ORDER,
            PRODUCTION_NOTIFY_SCHEDULE_CREATED,
            PRODUCTION_NOTIFY_SCHEDULE_CONFIRMED,
            PRODUCTION_NOTIFY_SCHEDULE_DELAYED,
            PRODUCTION_NOTIFY_URGENT_INSERT,
            PRODUCTION_NOTIFY_EFFICIENCY_WARNING,
            PRODUCTION_NOTIFY_AI_SCHEDULING,
            INVENTORY_NOTIFY_LOW_STOCK,
            QUALITY_NOTIFY_INSPECTION_REVIEW);

    /**
     * 保存阈值前的按 key 校验: 岗位名单里每一项必须是真实存在的岗位码 (空串 = 只有厂长, 合法);
     * {@link #CREDIT_CHECK_MODE} 只能是 OFF / WARN / BLOCK。返回错误说明, 合法返回 null。
     * 其它 key 不管。
     */
    public static String validateSemantics(String thresholdKey, String value) {
        if (thresholdKey == null || value == null) {
            return null;
        }
        if (CREDIT_CHECK_MODE.equals(thresholdKey)) {
            String v = value.trim().toUpperCase();
            return (v.equals("OFF") || v.equals("WARN") || v.equals("BLOCK"))
                    ? null : "超信用额度检查模式只能是 OFF / WARN / BLOCK";
        }
        String enumError = validateEnumValue(thresholdKey, value);
        if (enumError != null) {
            return enumError;
        }
        if (ROLE_LIST_KEYS.contains(thresholdKey)) {
            for (String code : value.split(",")) {
                String c = code.trim();
                if (c.isEmpty()) {
                    continue;
                }
                boolean known = false;
                for (com.cretas.aims.entity.enums.FactoryUserRole r : com.cretas.aims.entity.enums.FactoryUserRole.values()) {
                    if (r.name().equals(c)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    return "岗位码不存在: " + c + " (请填 factory_super_admin、warehouse_manager 这类英文岗位码, 逗号分隔)";
                }
            }
        }
        return null;
    }

    /** 取值是固定几个代码的开关类 key → 允许的值 (保存时校验, 打错字不再静默按默认)。 */
    private static final java.util.Map<String, java.util.List<String>> ENUM_VALUES = java.util.Map.of(
            SHORTAGE_CASE_CLOSE_MODE, java.util.List.of("MANUAL", "AUTO"),
            PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE, java.util.List.of("NONE", "NET_CONTENT", "FACTORY_UNIT"),
            QUALITY_INSPECTION_FAIL_SCOPE, java.util.List.of("ANY_ITEM", "MINOR_FOR_REVIEW", "CRITICAL_ONLY"),
            PRODUCTION_RECEIPT_OVER_RECEIVE_MODE, java.util.List.of("BLOCK", "WITHIN_RATE", "ALWAYS_WITH_REASON"));

    private static String validateEnumValue(String thresholdKey, String value) {
        java.util.List<String> allowed = ENUM_VALUES.get(thresholdKey);
        if (allowed == null) {
            return null;
        }
        String v = value.trim().toUpperCase(java.util.Locale.ROOT);
        return allowed.contains(v) ? null : thresholdKey + " 只能是 " + String.join(" / ", allowed);
    }

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
