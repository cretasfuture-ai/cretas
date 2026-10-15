package com.cretas.aims.blueprint.interview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建厂访谈里「每个模块怎么配」的目录 —— 落到 {@code factory_feature_config}。
 *
 * <h2>为什么需要它(⛔ 别把它读成「修一个正在流血的 bug」)</h2>
 *
 * {@code factory_feature_config} 2026-08-12 实测 <b>0 行</b>。但 0 行在这里
 * <b>不是</b>「没人用」, 而是「<b>所有工厂都在跑兜底默认值</b>」——
 * RN 侧 {@code factoryFeatureStore.isModuleEnabled()} 的分支是:
 * <pre>{@code
 * // If no config loaded, assume all modules enabled (backwards compatible)
 * if (!loaded || Object.keys(modules).length === 0) return true;
 * }</pre>
 * 0 行 → 全部模块视为启用 + {@code getEnabledDimensions()} 返回硬编码的默认 5 个维度。
 * 也就是说今天线上<b>没有任何东西是坏的</b>, 只是「按工厂裁剪模块」这个能力
 * 从来没有被任何入口产出过。
 *
 * <p>本目录 + apply 里那一段要做的事是: <b>让 ① 对话建厂能真正产出模块配置</b>,
 * 而不是修一个静默失败。⛔ 写 commit / 注释时不要把它描述成 bugfix。
 *
 * <h2>为什么这个入口必须先有, ② 才能删</h2>
 *
 * 迁移前应用层<b>唯一</b>能建这张表的行的地方是
 * {@code OnboardingController}(② {@code /api/internal/onboarding/create-factory})
 * 里的 {@code FactoryFeatureConfig.builder()}。而
 * {@code FeatureConfigController} 的 PUT <b>只能改已存在的行</b>
 * ({@code existing.isEmpty()} → 抛 400)。
 * 先删 ② 就会留下「3 个活读者 + 一张再也填不满的表」。
 *
 * <h2>⛔ module_id 现在有三种方言, 本目录只收「读者认得的」那两种</h2>
 *
 * 实测三个承载点各说各话:
 * <ul>
 *   <li>RN {@code factoryFeatureStore.SCREEN_TO_MODULE} 的取值(12 个) ——
 *       真正拿 moduleId 去<b>门禁屏幕</b>的地方</li>
 *   <li>web-admin {@code views/system/features/index.vue} 的
 *       {@code moduleDescriptions}(11 个) —— 「功能模块配置」页展示用</li>
 *   <li>历史迁移/种子 SQL(如 {@code V20260315_02__disable_coming_soon_modules.sql})
 *       用的却是 {@code inventory} / {@code purchasing} / {@code ai_analysis} /
 *       {@code ai_chat} —— <b>两个读者都不认得这几个名字</b></li>
 * </ul>
 *
 * 本目录 = 前两者的并集(14 个), <b>刻意不收</b>第三种。理由:
 * 第三种写进去之后 RN 永远查不到、web-admin 只能显示空描述, 属于「写了等于没写」。
 * ⚠️ 这个分歧是<b>迁移前就存在</b>的, 本次<b>不顺手改</b> —— 改名字要动历史数据,
 * 是另一个独立决定。
 *
 * <h2>写错 moduleId 的后果是「静默」的</h2>
 *
 * RN 那边是 {@code modules[moduleId]} 直接查表, 查不到就走
 * {@code mod ? mod.enabled : true} 的兜底 —— <b>不报错, 只是永远不生效</b>。
 * 与「盯指标」那次({@code InterviewAnomalyMetricCatalog})同一个形状:
 * 不在闭集里的名字会被静默忽略, 所以必须在<b>落库前</b>拦。
 *
 * <h2>⛔ 「关掉一个模块」其实要关两层, 而这两层原来没有绑定关系</h2>
 *
 * <pre>
 *   前端可见性:  factory_feature_config.module_id   ← 本目录的 moduleId(14 个)
 *                RN factoryFeatureStore 门禁**屏幕**, web-admin 那页展示
 *
 *   后端 API:    factory_module_configs.module_code ← {@code @RequireModule}(16 个码 / 386 处)
 *                ModuleEnabledInterceptor 拦**接口**
 * </pre>
 *
 * 2026-08-13 实测: 两套词汇表互不相同, 也没有任何代码把它们对起来。
 * 后果是<b>客户关掉一个模块, 屏幕藏了, 接口照通</b> —— 拿 token 直接打 API 就能绕过。
 *
 * <p>{@link Entry#gateCodes()} 就是这条缺失的绑定。有了它,
 * {@code FactoryBuildApplyService} 才能一次把两层都写上。
 *
 * <p><b>✅ 2026-08-13 起覆盖率 386/386 = 100%</b> —— 每一个 {@code @RequireModule}
 * 的码都能被客户在建厂访谈里关掉, 且两层一起关。由
 * {@code scripts/tests/check-module-gate-binding.py}(棘轮 0)钉住:
 * 新增门禁码而不绑到某个前端模块上, CI 当场红。
 *
 * <h3>⚠️ 后端那层要生效需要三样, 缺一不可</h3>
 *
 * <ol>
 *   <li>{@code module_schemas} 里有这个码 —— 否则 {@code getEffectiveConfig} 第一行
 *       就 {@code enabled=true} 短路返回(PR#82 补了 12 个, 物流 PR#85 再补 1 个, 共 16)</li>
 *   <li>该工厂有 <b>PUBLISHED</b> 的 {@code factory_configurations}</li>
 *   <li>{@code factory_module_configs} 行的 {@code config_version}
 *       <b>精确等于</b>那个 published 版本号</li>
 * </ol>
 *
 * ⛔ 少了第 2 样, 第 3 样写多少行都是死的 —— 实测本地 PUBLISHED 配置 <b>0 个</b>。
 * 所以 apply 必须**先确保有 v1 published 配置**再写开关。
 * (常量 {@code OnboardingModuleConfigProvisioner.ONBOARDING_CONFIG_VERSION = 1}
 *  与两个实体的 {@code configVersion} 默认值都是 1 —— 「建厂产出 v1」是原设计意图,
 *  只是那一半没写完。)
 */
public final class InterviewModuleConfigCatalog {

    private InterviewModuleConfigCatalog() {
    }

    /** 这个 moduleId 被哪些读者认得 —— 报错信息里要说清楚, 免得客户以为配了就生效。 */
    public static final String CARRIER_BOTH = "RN + web-admin";
    public static final String CARRIER_RN = "仅 RN";
    public static final String CARRIER_WEB_ADMIN = "仅 web-admin";

    /**
     * 一个可配置的模块。
     *
     * @param moduleId 落库值。⛔ 必须与读者侧逐字一致 —— 列长度只有 20
     * @param label    给人看的名字, 也是 {@code module_name} 的兜底值
     * @param detail   这个模块管什么(取自 web-admin 那页的描述, 没有的按 RN 的归属写)
     * @param carrier  哪个端认得它
     * @param gateCodes 关掉这个模块时, 后端要跟着关的 {@code @RequireModule} 码。
     *                 ⛔ **这是本目录里唯一「我定的」东西**, 其余四个字段都是抄读者侧的。
     *                 依据 = 本条的 label/detail + 实测的控制器分布(见类注释「两层」一节)。
     *                 空列表 = 这个前端模块背后没有任何后端门禁(不是漏了, 是真没有)。
     */
    public record Entry(String moduleId, String label, String detail, String carrier,
                        List<String> gateCodes) {
    }

    /**
     * ⚠️ 顺序即展示顺序。内容来源见类注释 —— <b>不是我编的</b>:
     * 逐条对应 RN {@code SCREEN_TO_MODULE} 的取值与 web-admin {@code moduleDescriptions} 的键。
     */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("production", "生产", "生产批次、生产计划、BOM管理", CARRIER_BOTH,
                    List.of("production", "production_plan", "production_report", "bom")),
            // ✅ 2026-08-13: 后端把 warehouse 里边界清楚的 39 处拆到已有的三个码上
            //    (inventory 16 / inbound 15 / transfer 8), 留 18 处仍是 warehouse:
            //      FactorySupplierDelivery 11 —— 算入库还是采购是业务边界判断, 待产品定
            //      ManufacturerRegistry 4 + FactoryWarehouseDefault 3 —— 主数据与配置
            //    ⛔ 客户看到的开关个数**不变**: 这一个「仓储」模块同时绑住四个码。
            //       拆的收益在后端: 业务类型包本来就能表达 inbound/inventory/transfer,
            //       而表达不了 warehouse —— 39 处因此从「模板够不着」变成「够得着」。
            //    📌 outbound 不在其中: 出库/发货挂在 sales_order(ShipmentController),
            //       归销售履约是合理归属, 不是漏了门禁。
            new Entry("warehouse", "仓储", "原材料管理、出货管理、库存盘点", CARRIER_BOTH,
                    List.of("warehouse", "inventory", "inbound", "transfer")),
            new Entry("quality", "质检", "质检记录、废弃处理", CARRIER_BOTH,
                    List.of("quality_inspection")),
            new Entry("scheduling", "调度", "调度中心、计划管理、人员分配", CARRIER_BOTH,
                    List.of("scheduling")),
            new Entry("hr", "人事", "员工档案、考勤、部门", CARRIER_BOTH,
                    List.of("hr_employee")),
            new Entry("equipment", "设备", "设备列表、维护记录、告警", CARRIER_BOTH,
                    List.of("equipment")),
            new Entry("finance", "财务", "成本分析、财务报表", CARRIER_BOTH,
                    List.of("finance", "finance_ar", "finance_ap")),
            // ⚠️ 智能BI / 溯源 / 系统 三个前端模块背后**没有**任何 @RequireModule。
            //    ⛔ 原注释在此写「traceability 有 module_schemas 定义但零门禁」——
            //       2026-08-14 实测**不成立**: module_schemas 恰好是那 19 个执行码, 里面没有
            //       traceability(也没有 smartbi / system)。已订正。
            //    这三个各自的判定与依据登记在 scripts/tests/module-vocab-exceptions.txt,
            //    由 check-module-vocab-matrix.py 钉住 —— 其中 smartbi 是 KNOWN_GAP
            //    (它有 4 个 factory 作用域 controller / 29 个端点), 另两个是 NOT_A_DEFECT。
            //    ⛔ 别把这三个笼统当成「后端没上闸所以都一样」, 它们不是同一回事。
            new Entry("smartbi", "智能BI", "智能BI分析、数据可视化", CARRIER_BOTH,
                    List.of("smartbi", "operating_reports")),
            new Entry("procurement", "采购", "供应商管理、采购订单", CARRIER_WEB_ADMIN,
                    List.of("purchase_order")),
            new Entry("sales", "销售", "客户管理、销售分析", CARRIER_WEB_ADMIN,
                    List.of("sales_order")),
            // ✅ 2026-08-13 已对齐: 那 4 个 Logistics* 控制器(17 处门禁)原先挂的是
            //    `@RequireModule("scheduling")` —— 于是**两个方向都错**:
            //      关「物流」-> RN 藏了屏幕, 后端接口照通
            //      关「调度」-> 后端拦了物流接口, 而 RN 还显示着物流入口
            //    17 处已改成 `logistics`, 定义见 V20261102_02__seed_logistics_module_schema.sql。
            //    ⚠️ 其余 8 个挂 scheduling 的类(APS/LinUCB/MixedBatch/UrgentInsert/
            //       Scheduling*/StrategyWeight)确实是调度域, **没动**。
            new Entry("logistics", "物流", "物流配送相关屏幕", CARRIER_RN,
                    List.of("logistics")),
            new Entry("trace", "溯源", "溯源查询相关屏幕", CARRIER_RN,
                    List.of()),
            new Entry("system", "系统", "通知设置等系统屏幕", CARRIER_RN,
                    List.of()),
            // ✅ 2026-08-13 新增: 餐饮是最后一个「后端有门禁、客户却关不掉」的域(46 处)。
            //    ⛔ 加它必须**先在两个读者侧加**, 否则写进库也永远不生效:
            //      RN  factoryFeatureStore.SCREEN_TO_MODULE  加了 18 个屏幕
            //      web-admin views/system/features 的 moduleDescriptions 加了描述
            //    (见类注释「写错 moduleId 的后果是静默的」——不报错, 只是永远不生效。)
            new Entry("restaurant", "餐饮", "菜谱、领料、盘点、报损、供应商送货", CARRIER_BOTH,
                    List.of("restaurant")));

    /** 本目录能关掉的后端门禁码全集 —— 闸拿它比对 {@code @RequireModule} 的码。 */
    public static java.util.Set<String> allGateCodes() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Entry e : ENTRIES) {
            out.addAll(e.gateCodes());
        }
        return Collections.unmodifiableSet(out);
    }

    private static final Map<String, Entry> BY_ID;

    static {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Entry e : ENTRIES) {
            m.put(e.moduleId(), e);
        }
        BY_ID = Collections.unmodifiableMap(m);
    }

    /** 按 moduleId 精确查(大小写与空白不敏感); 不在目录里返回 null。 */
    public static Entry find(String moduleId) {
        if (moduleId == null) {
            return null;
        }
        return BY_ID.get(moduleId.trim().toLowerCase());
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    /** 报错信息用: 合法 moduleId 的逐字清单。 */
    public static String legalIds() {
        return String.join("/", BY_ID.keySet());
    }
}
