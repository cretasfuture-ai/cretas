package com.cretas.aims.blueprint.interview;

import com.cretas.aims.repository.AlertThresholdRepository;
import java.util.stream.Collectors;
import com.cretas.aims.entity.material.MaterialCodeSegment;
import com.cretas.aims.repository.material.MaterialCodeSegmentRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.alerts.AlertRuleRepository;
import com.cretas.aims.entity.alerts.AlertRule;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访谈里 TABLE / MATRIX 题型的「现值 + 默认值」来源。
 *
 * <h2>这个类真正在守的一条线</h2>
 *
 * 表格题的问法是「只改跟你们不一样的格子」—— 前提是<b>那张表里预填的东西有出处</b>。
 * 没有出处却预填, 客户会照单全收, 然后系统按编出来的值跑。
 *
 * 所以每一段都带一个 {@code defaultsFrom}, 三种取值:
 * <ul>
 *   <li>{@code GLOBAL_ROW} —— 来自 {@code factory_id='*'} 的通用默认行(阈值有这套)</li>
 *   <li>{@code ENTITY_DEFAULT} —— 来自实体字段的默认值(模块开关是这种)</li>
 *   <li>{@code NONE} —— <b>没有默认可给</b>, 这一段必须从零问</li>
 * </ul>
 *
 * ⛔ {@code NONE} 的时候不许退回「找一家像的厂抄一份」。
 *    实测 indicators 只有 F006 一家有 21 条、没有 '*' 通用行;
 *    拿那 21 条当推荐, 等于把一个租户的配置当成系统建议发给另一个租户,
 *    而客户看不出这是抄来的。
 *
 * <h2>⚠️ 数字不写死</h2>
 *
 * 访谈规格里原先写「已有 66 条种子」, 那个 66 是 <b>3 个工厂 × 22 个 key</b>
 * 的行数 —— 按它做界面会给客户看 66 行, 而实际只有 22 个可调项。
 * 本类一律现查现给, 不缓存条数。
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class InterviewDefaults {

    /** 默认值出处。前端据此决定「这格能不能预填」。 */
    public enum DefaultsFrom {
        /** factory_id='*' 的通用默认行 */
        GLOBAL_ROW,
        /** 实体字段自带的默认值 */
        ENTITY_DEFAULT,
        /**
         * 平台自己的角色-模块权限表(platform_role_permissions)。
         *
         * ⚠️ 这张表**没有 factory_id** —— 它是产品的角色模型, 不是任何租户的配置。
         * 所以拿它当推荐是合法的, 与「拿 F006 的 indicators 当推荐」有本质区别:
         * 后者是把一个客户的业务选择发给另一个客户, 前者是产品自带的定义。
         */
        PLATFORM_ROLE_TABLE,
        /**
         * 产品自带的目录(写在代码里的定义, 如 InterviewAlertCatalog)。
         *
         * 与 PLATFORM_ROLE_TABLE 同类 —— 都是产品定义而非某个租户的配置,
         * 所以拿来当默认是合法的; 与「拿 F006 的 indicators 当推荐」有本质区别。
         */
        PRODUCT_CATALOG,
        /** 没有默认可给 —— 必须从零问, ⛔ 不许找别家抄 */
        NONE
    }

    @Autowired
    private FactoryThresholdRepository thresholdRepository;

    @Autowired
    private FactorySettingsRepository factorySettingsRepository;

    @Autowired
    private IndicatorRepository indicatorRepository;

    @Autowired
    private PlatformRolePermissionRepository rolePermissionRepository;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private AlertThresholdRepository alertThresholdRepository;

    @Autowired
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Autowired
    private MaterialCodeSegmentRepository materialCodeSegmentRepository;

    @Data
    public static class Section {
        private String key;
        private String title;
        /** table / matrix */
        private String mode;
        private DefaultsFrom defaultsFrom;
        /** defaultsFrom=NONE 时说明为什么没有 —— 让人看得见, 而不是空着 */
        private String defaultsNote;
        /** 本厂当前已有几行(0 = 还没配) */
        private int currentRows;
        private List<Map<String, Object>> rows = new ArrayList<>();
    }

    /** 模块开关 —— 6 个, 列名取自 factory_settings 实测。 */
    private static final List<String[]> MODULE_SWITCHES = List.of(
            new String[]{"enableQrCode", "二维码/条码"},
            new String[]{"enableBatchManagement", "批次管理"},
            new String[]{"enableQualityCheck", "质检"},
            new String[]{"enableCostCalculation", "成本核算"},
            new String[]{"enableEquipmentManagement", "设备管理"},
            new String[]{"enableAttendance", "考勤"});

    public List<Section> forFactory(String factoryId) {
        List<Section> out = new ArrayList<>();
        out.add(modules(factoryId));
        out.add(thresholds(factoryId));
        out.add(indicators(factoryId));
        out.add(permissions());
        out.add(alerts(factoryId));
        out.add(metricWatches(factoryId));
        out.add(materialClassification(factoryId));
        return out;
    }

    /**
     * 要盯哪些生产指标 —— 落 {@code alert_thresholds}(异常检测那套)。
     *
     * <p>⛔ 与「预警阈值」段不是一回事: 那段喂 {@code factory_thresholds}(画布/定价),
     * 两张表<b>不互通</b>。这一段的消费方是 {@code AnomalyDetectionScheduler},
     * 它按 {@code production_batches} 最近若干批的均值判 —— 实测那张表全库 0 行,
     * 所以定时任务一直在遍历空表。
     *
     * <p>⛔ 目录由代码给(能盯哪些由评估器的 switch 定死), 不走 LLM:
     * 名字不在里面的话评估器只记一行 warn 就跳过, 配置看着写进去了却永远不触发。
     */
    private Section metricWatches(String factoryId) {
        Section sec = new Section();
        sec.setKey("metricWatches");
        sec.setTitle("要盯哪些生产指标");
        sec.setMode("table");
        sec.setDefaultsFrom(DefaultsFrom.PRODUCT_CATALOG);
        sec.setDefaultsNote("按最近几批的平均值判; 每项都要填阈值 —— "
                + "⛔ 没有阈值的「盯着」不是配置, 是空转");
        int existing = 0;
        for (InterviewAnomalyMetricCatalog.Entry e : InterviewAnomalyMetricCatalog.all()) {
            boolean have = !alertThresholdRepository
                    .findByFactoryIdAndMetricName(factoryId, e.metricName()).isEmpty();
            if (have) {
                existing++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricName", e.metricName());
            row.put("label", e.label());
            row.put("detail", e.detail());
            row.put("unit", e.unit());
            // 方向要显示出来: 良品率是「低于」提醒, 不良品数是「高于」提醒,
            // 不写清楚客户会把阈值填反 —— 而填反了系统不会报错, 只是永远不触发。
            row.put("direction", InterviewAnomalyMetricCatalog.LESS_THAN.equals(e.comparison())
                    ? "低于" : "高于");
            row.put("alreadyConfigured", have);
            sec.getRows().add(row);
        }
        sec.setCurrentRows(existing);
        return sec;
    }

    /**
     * 没有分类的物料 —— 把一个<b>静默</b>的缺口摆到配厂的人眼前。
     *
     * <p>2026-08-11 实测: 库里 17 条物料的 {@code category}/{@code primary_code} 全为空
     * (绕过 RawMaterialTypeService 直写造成的, 写入侧已在同日修好)。存量的坏法是静默的:
     * 库存台账按类别筛选时 {@code equalsIgnoreCase(null)} 永远 false, 那批物料一条都不出现;
     * 调味台账 {@code "003".equals(primaryCode)} 同样永远 false。没人会收到报错。
     *
     * <h3>⛔ 为什么不写回填迁移</h3>
     *
     * 实测那两家有真实物料的工厂(FOOD_3101_001 / AQUA_3302_001)<b>根本没有建过 L1 分段树</b> ——
     * 回填按名字猜也没有地方可写。真正的缺口不是「分类填错了」, 是「这家厂还没定义分类」。
     * 所以这里给的是<b>去路</b>: 先看见有几条没分类、本厂有没有 L1, 再决定建哪几类。
     */
    private Section materialClassification(String factoryId) {
        List<MaterialCodeSegment> l1 = materialCodeSegmentRepository
                .findByFactoryIdAndLevelOrderBySortOrderAscIdAsc(factoryId, (short) 1);
        List<RawMaterialType> unclassified = rawMaterialTypeRepository.findByFactoryId(factoryId).stream()
                .filter(m -> m.getCategory() == null || m.getCategory().isBlank())
                .toList();

        Section sec = new Section();
        sec.setKey("materialClassification");
        sec.setTitle("没有分类的物料");
        sec.setMode("table");
        sec.setDefaultsFrom(DefaultsFrom.NONE);
        sec.setCurrentRows(unclassified.size());
        sec.setDefaultsNote(l1.isEmpty()
                // ⛔ 先说前提再说数量: 没有 L1 时「补分类」这个动作根本做不了,
                //    只说「有 N 条没分类」会让人去找一个不存在的下拉框。
                ? "本厂还没有建任何 L1 分类 —— 得先定义分类(如 原料/辅料/包材, 名字你自己定), "
                  + "这些物料才有地方归。共 " + unclassified.size() + " 条待归类"
                : unclassified.isEmpty()
                        ? "本厂物料都有分类"
                        : "共 " + unclassified.size() + " 条没有分类; 本厂可选的 L1: "
                          + l1.stream().map(MaterialCodeSegment::getSegmentLabel)
                                  .collect(Collectors.joining("/"))
                          + "。⚠️ 没分类的物料在按类别筛选的库存台账里一条都不会出现");

        for (RawMaterialType m : unclassified) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", m.getCode());
            row.put("name", m.getName());
            row.put("unit", m.getUnit());
            sec.getRows().add(row);
        }
        return sec;
    }

    /**
     * 要开哪些提醒。
     *
     * <p>⛔ 这一段<b>不走 LLM</b>: 告警类型是固定可枚举的, 交给模型生成只会多一处幻觉
     * (编出一个 AlertType 里没有的类型, 落库时才发现)。前端按这份目录渲染勾选框,
     * 勾中的原样回传 —— 与「能勾选就别问答」是同一条。
     *
     * <p>2026-08-11 实测 alert_rules 全局 0 行, 所以 currentRows 基本都是 0;
     * 已经配过的工厂(或餐饮体检自建过规则的)会显示已有几条, 那几条不会被重建。
     */
    private Section alerts(String factoryId) {
        Section sec = new Section();
        sec.setKey("alerts");
        sec.setTitle("要开哪些提醒");
        sec.setMode("table");
        sec.setDefaultsFrom(DefaultsFrom.PRODUCT_CATALOG);
        sec.setDefaultsNote("产品自带的告警目录; 金额类必须填阈值才允许开 —— "
                + "不填阈值就开会让每一张单子都触发一条告警");
        int existing = 0;
        for (InterviewAlertCatalog.Entry e : InterviewAlertCatalog.all()) {
            List<AlertRule> have = alertRuleRepository.findByFactoryId(factoryId).stream().filter(rule -> rule.getAlertType() == e.type()).toList();
            if (!have.isEmpty()) {
                existing++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("alertType", e.type().name());
            row.put("label", e.label());
            row.put("detail", e.detail());
            row.put("needsAmount", e.needsAmount());
            row.put("severity", e.severity().name());
            row.put("notifyRoles", e.notifyRoleCodes());
            // 已有的显示出来并标成不可再建 —— 而不是让人勾了才发现被跳过
            row.put("alreadyConfigured", !have.isEmpty());
            row.put("currentSpel", have.isEmpty() ? null : have.get(0).getTriggerConditionSpel());
            sec.getRows().add(row);
        }
        sec.setCurrentRows(existing);
        return sec;
    }

    /**
     * 模块开关。本厂还没有 factory_settings 行时, 用实体自带的默认值 ——
     * 那是真实的落库默认(不配置就是这个值), 不是编的。
     */
    private Section modules(String factoryId) {
        Section s = new Section();
        s.setKey("modules");
        s.setTitle("模块启用");
        s.setMode("table");

        FactorySettings existing = factorySettingsRepository.findByFactoryId(factoryId).orElse(null);
        FactorySettings source = existing != null ? existing : new FactorySettings();
        s.setCurrentRows(existing != null ? 1 : 0);
        s.setDefaultsFrom(existing != null ? DefaultsFrom.GLOBAL_ROW : DefaultsFrom.ENTITY_DEFAULT);
        if (existing == null) {
            s.setDefaultsNote("本厂尚未配置, 预填的是不配置时系统实际会用的值");
        }
        for (String[] sw : MODULE_SWITCHES) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", sw[0]);
            row.put("label", sw[1]);
            row.put("current", readSwitch(source, sw[0]));
            s.getRows().add(row);
        }
        return s;
    }

    private Boolean readSwitch(FactorySettings fs, String name) {
        try {
            return (Boolean) FactorySettings.class
                    .getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1))
                    .invoke(fs);
        } catch (ReflectiveOperationException | ClassCastException e) {
            // ⛔ 读不到不猜一个 false —— 那会让「关着」和「读失败」长得一模一样
            log.warn("模块开关读不到: {} ({})", name, e.toString());
            return null;
        }
    }

    /**
     * 预警阈值。默认来自 {@code factory_id='*'} 那套通用行,
     * 本厂已有的值覆盖在上面。
     */
    private Section thresholds(String factoryId) {
        Section s = new Section();
        s.setKey("thresholds");
        s.setTitle("预警阈值");
        s.setMode("table");

        List<FactoryThreshold> globals = thresholdRepository
                .findByFactoryIdOrderByCategoryAscThresholdKeyAsc(
                        FactoryThreshold.GLOBAL_FALLBACK_FACTORY_ID);
        List<FactoryThreshold> mine = thresholdRepository
                .findByFactoryIdOrderByCategoryAscThresholdKeyAsc(factoryId);
        s.setCurrentRows(mine.size());

        if (globals.isEmpty()) {
            // 通用行被删了 —— 如实报告, 不拿某一家的配置顶上
            s.setDefaultsFrom(DefaultsFrom.NONE);
            s.setDefaultsNote("factory_id='*' 的通用默认行一条都没有 —— 只能从零配");
            return s;
        }
        s.setDefaultsFrom(DefaultsFrom.GLOBAL_ROW);

        Map<String, FactoryThreshold> mineByKey = new LinkedHashMap<>();
        for (FactoryThreshold t : mine) {
            mineByKey.put(t.getThresholdKey(), t);
        }
        for (FactoryThreshold g : globals) {
            FactoryThreshold own = mineByKey.get(g.getThresholdKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", g.getThresholdKey());
            row.put("category", g.getCategory() == null ? null : g.getCategory().name());
            row.put("label", g.getDescription());
            row.put("unit", g.getUnit());
            row.put("default", g.getThresholdValue());
            // current 为 null 表示「本厂还没配, 会按 default 跑」——
            // 与「配了个跟默认一样的值」是两回事, 所以不填成 default。
            row.put("current", own == null ? null : own.getThresholdValue());
            row.put("min", g.getMinValue());
            row.put("max", g.getMaxValue());
            s.getRows().add(row);
        }
        return s;
    }

    /**
     * 权限矩阵(角色 × 模块)。
     *
     * <p>推荐值来自 {@code platform_role_permissions} —— 那张表<b>没有 factory_id</b>,
     * 是产品自己的角色模型, 不是任何租户的配置。所以拿它当默认是合法的,
     * 与「拿 F006 的 indicators 当推荐」有本质区别。
     *
     * <p>⚠️ 访谈规格里原先写 {@code defaults: recommended-matrix/{industryHint}.yaml},
     * 而那个目录**根本不存在** —— 接口只能报 NONE。现在改成用这张真实存在的表,
     * 不再需要一份手写的推荐矩阵(手写的那份还得有人维护, 且没有任何东西保证它与
     * 系统真正的权限判定一致)。
     *
     * <p>⚠️ 另一条实测: {@code user_module_access} 全库 <b>0 行</b> ——
     * 规格里那两个 followUp(跨部门看价格 / 改别人部门的单子) 指向的就是它,
     * 也就是说那两问目前问了也没有任何代码会去读。记在这里, 未处理。
     */
    private Section permissions() {
        Section s = new Section();
        s.setKey("permissions");
        s.setTitle("权限矩阵");
        s.setMode("matrix");

        List<PlatformRolePermission> all = rolePermissionRepository.findByDeletedAtIsNull();
        s.setCurrentRows(all.size());
        if (all.isEmpty()) {
            s.setDefaultsFrom(DefaultsFrom.NONE);
            s.setDefaultsNote("platform_role_permissions 一行都没有 —— 没有推荐可给");
            return s;
        }
        s.setDefaultsFrom(DefaultsFrom.PLATFORM_ROLE_TABLE);

        // 角色 -> (模块 -> 权限档)。TreeMap 让输出稳定, 免得两次调用顺序不同看起来像变了。
        java.util.TreeMap<String, java.util.TreeMap<String, String>> byRole = new java.util.TreeMap<>();
        for (PlatformRolePermission p : all) {
            byRole.computeIfAbsent(p.getRoleCode(), k -> new java.util.TreeMap<>())
                    .put(p.getModuleCode(), p.getPermissionLevel());
        }
        for (Map.Entry<String, java.util.TreeMap<String, String>> e : byRole.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", e.getKey());
            row.put("label", e.getKey());
            // ⛔ 原样给出每个模块的档位, 不在这里折叠成「有/无」——
            //    r 与 rw 的区别正是客户要改的那个东西。
            row.put("current", e.getValue());
            s.getRows().add(row);
        }
        return s;
    }

    /**
     * 关注指标。⚠️ 没有 {@code '*'} 通用行 —— 实测只有 F006 一家有 21 条。
     *
     * <p>⛔ 不拿那 21 条当推荐: 那是另一个租户的配置, 客户看不出是抄来的,
     *    会当成系统建议照单全收。这一段如实报 NONE, 由访谈从零问。
     */
    private Section indicators(String factoryId) {
        Section s = new Section();
        s.setKey("indicators");
        s.setTitle("关注指标");
        s.setMode("table");

        List<Indicator> mine = indicatorRepository.findByFactoryIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(factoryId);
        s.setCurrentRows(mine.size());
        for (Indicator i : mine) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", i.getCode());
            row.put("label", i.getName());
            row.put("current", Boolean.TRUE.equals(i.getIsActive()));
            s.getRows().add(row);
        }
        if (mine.isEmpty()) {
            s.setDefaultsFrom(DefaultsFrom.NONE);
            s.setDefaultsNote("indicators 没有 factory_id='*' 的通用默认行, "
                    + "且本厂 0 条 —— ⛔ 不拿其它工厂的配置当推荐, 这一段从零问");
        } else {
            s.setDefaultsFrom(DefaultsFrom.GLOBAL_ROW);
        }
        return s;
    }
}
