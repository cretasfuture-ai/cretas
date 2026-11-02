package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.blueprint.draftentry.CapabilityDraftProposer.Option;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 建厂草稿里枚举下拉的中文名。存进库、发给命令的值永远是枚举常量名，这里只决定页面上看到的字。
 *
 * <p>查找顺序：{@code 枚举类名.常量名}（同名常量在不同枚举里意思不同时用）→ {@code 常量名}。
 * 找不到就退回常量名；{@code DraftEnumLabelsTest} 逐个枚举核对，页面上不应再看到英文代码。</p>
 */
final class DraftEnumLabels {
    private DraftEnumLabels() {}

    /** 类名限定的覆盖：同一个常量名在不同枚举里含义不同。 */
    private static final Map<String, String> BY_ENUM = Map.ofEntries(
            Map.entry("RiskBand.CRITICAL", "极高"), Map.entry("RiskBand.HIGH", "高"),
            Map.entry("RiskBand.MEDIUM", "中"), Map.entry("RiskBand.LOW", "低"),
            Map.entry("HygieneLevel.HIGH_RISK", "高风险区"),
            Map.entry("Severity.MINOR", "轻微"),
            Map.entry("Likelihood.RARE", "极少发生"),
            Map.entry("Frequency.DAILY", "每天"), Map.entry("Frequency.WEEKLY", "每周"),
            Map.entry("SourceType.CRITICAL_CONTROL", "关键控制点"),
            Map.entry("SourceType.QUALITY_RELEASE_SPEC", "质量放行规格"),
            Map.entry("EntryPolicy.OPEN", "自由进入"),
            Map.entry("SubjectKind.MATERIAL", "物料"), Map.entry("SubjectType.MATERIAL", "物料"));

    private static final Map<String, String> BY_NAME = Map.ofEntries(
            // 过敏原
            Map.entry("MATERIAL", "物料"), Map.entry("MATERIAL_DRAFT", "草稿物料"), Map.entry("PRODUCT", "产品"),
            Map.entry("PRODUCT_DRAFT", "草稿产品"),
            Map.entry("CEREALS_CONTAINING_GLUTEN", "含麸质谷物"), Map.entry("CRUSTACEAN", "甲壳类"),
            Map.entry("EGG", "蛋"), Map.entry("FISH", "鱼"), Map.entry("PEANUT", "花生"),
            Map.entry("SOYBEAN", "大豆"), Map.entry("MILK", "乳"), Map.entry("TREE_NUT", "坚果"),
            Map.entry("SESAME", "芝麻"), Map.entry("SULPHITES", "亚硫酸盐"),
            Map.entry("CONTAINS", "含有"), Map.entry("MAY_CONTAIN_CROSS_CONTACT", "可能交叉接触"),
            Map.entry("SUPPLIER_SPECIFICATION", "供应商规格书"), Map.entry("RECIPE_COMPOSITION", "配方成分"),
            Map.entry("SITE_ASSESSMENT", "现场评估"),
            Map.entry("DECLARED_IN_INGREDIENTS", "在配料表中声明"), Map.entry("PRECAUTIONARY_MAY_CONTAIN", "提示「可能含有」"),
            Map.entry("MATERIAL_CONTAINS", "物料含有"), Map.entry("MATERIAL_CROSS_CONTACT", "物料有交叉接触"),
            Map.entry("DEDICATED_LINE", "专用生产线"), Map.entry("FULL_WET_CLEAN", "全面湿洗"),
            Map.entry("DRY_CLEAN_AND_FLUSH", "干清并冲洗"), Map.entry("SEQUENCED_PRODUCTION", "按顺序排产"),
            Map.entry("VISUAL", "目视检查"), Map.entry("ALLERGEN_SWAB_RAPID_TEST", "过敏原擦拭快检"),
            Map.entry("PROTEIN_SWAB", "蛋白擦拭"), Map.entry("RINSE_WATER_TEST", "冲洗水检测"),
            Map.entry("LINE_CLEARANCE_CHECKLIST", "清线检查表"),
            // 责任角色
            Map.entry("QUALITY_INSPECTOR", "质检员"), Map.entry("SANITATION_OPERATOR", "清洁员"),
            Map.entry("PRODUCTION_SUPERVISOR", "生产主管"), Map.entry("LABELLING_OPERATOR", "贴标员"),
            Map.entry("WAREHOUSE_SUPERVISOR", "仓库主管"), Map.entry("FINANCE_CONTROLLER", "财务负责人"),
            Map.entry("FACTORY_DIRECTOR", "厂长"), Map.entry("QUALITY_MANAGER", "质量经理"),
            Map.entry("MAINTENANCE_OWNER", "设备维护负责人"), Map.entry("SECURITY_OWNER", "安保负责人"),
            Map.entry("LAB_TECHNICIAN", "化验员"), Map.entry("SANITATION_SUPERVISOR", "清洁主管"),
            Map.entry("QUALITY_SUPERVISOR", "质量主管"), Map.entry("QUALITY_OWNER", "质量负责人"),
            Map.entry("FACTORY_OWNER", "工厂负责人"), Map.entry("PRODUCTION_OWNER", "生产负责人"),
            Map.entry("WAREHOUSE_OWNER", "仓库负责人"),
            // 证据
            Map.entry("CHECKLIST", "检查表"), Map.entry("PHOTO", "照片"), Map.entry("LAB_REPORT", "化验报告"),
            Map.entry("QUALITY_SIGNOFF", "质量签字"), Map.entry("CUSTOMER_APPROVAL", "客户确认"),
            Map.entry("WEIGHT_RECORD", "称重记录"), Map.entry("DRILL_REPORT", "演练报告"),
            Map.entry("UTILITY_LOG", "水电气记录"), Map.entry("METER_READING", "仪表读数"),
            // 客供料
            Map.entry("CUSTOMER_OWNED", "客户自有"), Map.entry("FACTORY_OWNED_FOR_CONTRACT", "工厂为合同采购"),
            Map.entry("DEDICATED_WAREHOUSE", "专用仓库"), Map.entry("DEDICATED_LOCATION", "专用库位"),
            Map.entry("LABELLED_SHARED_LOCATION", "共用库位加标识"),
            Map.entry("CONTRACT_ORDER_ONLY", "仅限合同订单领用"), Map.entry("CONTRACT_SCOPE_ONLY", "仅限合同范围领用"),
            Map.entry("RETURN_TO_CUSTOMER", "退还客户"), Map.entry("SCRAP_WITH_CUSTOMER_APPROVAL", "客户同意后报废"),
            Map.entry("RETAIN_FOR_NEXT_CONTRACT_ORDER", "留给下一个合同订单"),
            Map.entry("NO_SETTLEMENT_CUSTOMER_OWNED", "客户自有，不结算"), Map.entry("PROCESSING_FEE_ONLY", "只结加工费"),
            Map.entry("FULL_MATERIAL_AND_PROCESSING", "物料和加工费都结算"),
            Map.entry("CUSTOMER_ASSET_OFF_BOOK", "客户资产，不入账"), Map.entry("FACTORY_ASSET_ON_BOOK", "工厂资产，入账"),
            // 偏差与纠正预防
            Map.entry("SINGLE_EVENT", "单次事件"), Map.entry("REPEAT_EVENT", "重复事件"),
            Map.entry("CRITICAL", "严重"), Map.entry("MAJOR", "主要"), Map.entry("MINOR", "次要"),
            Map.entry("DEVIATION", "偏差"), Map.entry("NON_CONFORMANCE", "不合格"), Map.entry("CAPA", "纠正预防措施"),
            Map.entry("HOLD_AFFECTED_PRODUCT", "扣留受影响产品"), Map.entry("ESCALATE_AND_ASSESS", "上报并评估"),
            Map.entry("QUALITY_THEN_PRODUCTION_OWNER", "质量负责人，再生产负责人"),
            Map.entry("QUALITY_THEN_FACTORY_OWNER", "质量负责人，再工厂负责人"),
            Map.entry("FIVE_WHYS", "五个为什么"), Map.entry("FISHBONE", "鱼骨图"), Map.entry("BOTH", "两种都用"),
            Map.entry("STOP_AND_CONTAIN", "停止并隔离"), Map.entry("SEGREGATE_AND_ASSESS", "隔离并评估"),
            Map.entry("RESTORE_CONTROL", "恢复控制"), Map.entry("REVISE_PROCESS_CONTROL", "修订过程控制"),
            Map.entry("SYSTEMIC_CONTROL_REDESIGN", "系统性重新设计控制"), Map.entry("AUDIT_EXPANSION", "扩大审核范围"),
            Map.entry("CONTROL_COMPLIANCE", "控制符合率"), Map.entry("DEFECT_RATE_REDUCTION", "不良率下降"),
            // 应急
            Map.entry("POWER_FAILURE", "停电"), Map.entry("WATER_FAILURE", "停水"), Map.entry("COLD_CHAIN_FAILURE", "冷链失效"),
            Map.entry("FIRE", "火灾"), Map.entry("FLOOD", "水灾"), Map.entry("INTENTIONAL_ADULTERATION", "蓄意污染"),
            Map.entry("SUPPLY_INTERRUPTION", "供应中断"), Map.entry("RECALL_DRILL", "召回演练"),
            Map.entry("LOCALISED", "局部"), Map.entry("LINE_WIDE", "整条线"), Map.entry("SITE_WIDE", "全厂"),
            Map.entry("ISOLATE_AFFECTED_LOT", "隔离受影响批次"), Map.entry("STOP_AFFECTED_LINE", "停止受影响产线"),
            Map.entry("STOP_SITE", "全厂停产"), Map.entry("SWITCH_TO_BACKUP_UTILITY", "切换备用水电"),
            Map.entry("SECURE_ACCESS_POINTS", "封控出入口"), Map.entry("QUARANTINE_INCOMING", "隔离来料"),
            Map.entry("INTERNAL_ONLY", "仅内部通知"), Map.entry("INTERNAL_AND_CUSTOMER", "内部并通知客户"),
            Map.entry("INTERNAL_AND_REGULATOR", "内部并通知监管部门"),
            // 环境监测
            Map.entry("FOOD_CONTACT", "直接接触食品"), Map.entry("NON_FOOD_CONTACT_ADJACENT", "不接触食品·邻近"),
            Map.entry("NON_FOOD_CONTACT_REMOTE", "不接触食品·较远"), Map.entry("OUTSIDE_PROCESSING_AREA", "加工区外"),
            Map.entry("SURFACE_SWAB", "表面涂抹"), Map.entry("SPONGE_SWAB", "海绵擦拭"),
            Map.entry("AIR_SETTLE_PLATE", "空气沉降板"), Map.entry("AIR_ACTIVE_SAMPLER", "空气采样器"),
            Map.entry("WATER", "水"), Map.entry("CONDENSATE", "冷凝水"),
            Map.entry("LISTERIA_MONOCYTOGENES", "单增李斯特菌"), Map.entry("LISTERIA_SPP", "李斯特菌属"),
            Map.entry("SALMONELLA", "沙门氏菌"), Map.entry("ENTEROBACTERIACEAE", "肠杆菌科"),
            Map.entry("COLIFORM", "大肠菌群"), Map.entry("TOTAL_PLATE_COUNT", "菌落总数"),
            Map.entry("YEAST_MOLD", "酵母和霉菌"), Map.entry("ATP_RESIDUE", "ATP 残留"),
            Map.entry("PRE_OPERATIONAL", "开工前"), Map.entry("DURING_OPERATION", "生产中"),
            Map.entry("POST_SANITATION", "清洁后"),
            Map.entry("PER_SHIFT", "每班"), Map.entry("DAILY", "每天"), Map.entry("WEEKLY", "每周"),
            Map.entry("BIWEEKLY", "每两周"), Map.entry("MONTHLY", "每月"), Map.entry("QUARTERLY", "每季度"),
            Map.entry("CFU_PER_SWAB", "菌落数/擦拭"), Map.entry("CFU_PER_CM2", "菌落数/平方厘米"),
            Map.entry("CFU_PER_ML", "菌落数/毫升"), Map.entry("CFU_PER_PLATE", "菌落数/平板"),
            Map.entry("RLU", "相对光单位"), Map.entry("PRESENCE_ABSENCE", "有/无"),
            Map.entry("TREND_AND_MONITOR", "记录趋势并继续监测"), Map.entry("RECLEAN_AND_RESAMPLE", "重新清洁并复采"),
            Map.entry("INVESTIGATE_AND_VECTOR_SAMPLE", "调查并追踪采样"),
            Map.entry("CORRECTIVE_ACTION_WITH_PRODUCT_ASSESSMENT", "纠正措施并评估产品"),
            // 设备计划
            Map.entry("MAINTENANCE", "保养"), Map.entry("CALIBRATION", "校准"),
            Map.entry("RUNNING_HOURS", "运行小时"), Map.entry("CALENDAR_DAYS", "日历天数"),
            Map.entry("CALENDAR_MONTHS", "日历月数"), Map.entry("EVENT", "按事件"),
            Map.entry("NEXT_DUE_FROM_LAST_COMPLETION", "从上次完成日起算"),
            Map.entry("NEXT_DUE_FROM_PLAN_EFFECTIVE_DATE", "从计划生效日起算"),
            // 危害分析
            Map.entry("BIOLOGICAL", "生物性"), Map.entry("CHEMICAL", "化学性"), Map.entry("PHYSICAL", "物理性"),
            Map.entry("NEGLIGIBLE", "可忽略"), Map.entry("MODERATE", "中等"),
            Map.entry("RARE", "罕见"), Map.entry("UNLIKELY", "不太可能"), Map.entry("POSSIBLE", "可能"),
            Map.entry("LIKELY", "很可能"), Map.entry("ALMOST_CERTAIN", "几乎肯定"),
            Map.entry("LOW", "低"), Map.entry("MEDIUM", "中"), Map.entry("HIGH", "高"),
            Map.entry("PREREQUISITE_PROGRAM", "前提方案"), Map.entry("PROCESS_CONTROL", "过程控制"),
            Map.entry("SUPPLIER_CONTROL", "供应商控制"), Map.entry("SANITATION_CONTROL", "清洁控制"),
            Map.entry("ALLERGEN_CONTROL", "过敏原控制"), Map.entry("TEMPERATURE_CONTROL", "温度控制"),
            // 卫生分区
            Map.entry("LOW_CARE", "低度关注区"), Map.entry("HIGH_CARE", "高度关注区"), Map.entry("HIGH_RISK", "高风险"),
            Map.entry("PERSONNEL_HYGIENE", "人员卫生区"), Map.entry("WASTE", "废弃物区"),
            Map.entry("AMBIENT", "常温"), Map.entry("CHILLED", "冷藏"), Map.entry("FROZEN", "冷冻"),
            Map.entry("PROCESS_DEFINED", "按工艺定义"),
            Map.entry("OPEN", "开放"), Map.entry("GOWNING_REQUIRED", "须更衣"), Map.entry("HANDWASH_REQUIRED", "须洗手"),
            Map.entry("SANITIZE_REQUIRED", "须消毒"),
            Map.entry("PERSONNEL", "人员"), Map.entry("AIR", "空气"),
            Map.entry("ONE_WAY", "单向"), Map.entry("BIDIRECTIONAL", "双向"),
            Map.entry("ALLOWED", "允许"), Map.entry("CONTROLLED", "受控"), Map.entry("FORBIDDEN", "禁止"),
            Map.entry("NONE", "无"), Map.entry("AIRLOCK", "气闸室"), Map.entry("PASS_THROUGH", "传递窗"),
            Map.entry("SANITIZATION", "消毒"), Map.entry("GOWNING", "更衣"),
            // 质量放行
            Map.entry("FINISHED_PRODUCT", "成品"),
            Map.entry("CRITICAL_CONTROL", "关键控制点"), Map.entry("FORMAL_PRODUCT", "正式产品"), Map.entry("FORMAL_MATERIAL", "正式物料"),
            Map.entry("SENSORY", "感官"), Map.entry("MICROBIOLOGICAL", "微生物"), Map.entry("PACKAGING", "包装"),
            Map.entry("CONTROL_VERIFICATION", "控制验证"),
            Map.entry("NUMERIC", "数值"), Map.entry("RANGE", "范围"), Map.entry("TEXT", "文字"), Map.entry("BOOLEAN", "是/否"),
            Map.entry("FULL_INSPECTION", "全检"), Map.entry("RANDOM", "随机抽检"), Map.entry("PERIODIC", "定期抽检"),
            Map.entry("EVERY_BATCH", "每批检验"), Map.entry("AQL", "按 AQL 抽检"),
            Map.entry("BLOCK_RELEASE", "阻止放行"), Map.entry("REQUIRE_QUALITY_REVIEW", "须质量复核"),
            Map.entry("ADVISORY", "仅提示"),
            // 清洁/CIP
            Map.entry("ROUTE_PROCESS", "工艺路线工序"), Map.entry("EQUIPMENT_CIP", "设备原位清洗"), Map.entry("TOOLS", "工器具"),
            Map.entry("ENVIRONMENT", "环境"), Map.entry("EMPLOYEE_HYGIENE", "员工卫生"),
            Map.entry("MANUAL_CLEAN", "人工清洁"), Map.entry("FOAM_CLEAN", "泡沫清洁"),
            Map.entry("CLEAN_OUT_OF_PLACE", "拆卸清洗"), Map.entry("CLEAN_IN_PLACE", "原位清洗"),
            Map.entry("ALKALINE", "碱性"), Map.entry("ACID", "酸性"), Map.entry("CHLORINE", "含氯"),
            Map.entry("PERACETIC_ACID", "过氧乙酸"), Map.entry("QUATERNARY_AMMONIUM", "季铵盐"),
            Map.entry("HOT_WATER", "热水"), Map.entry("STEAM", "蒸汽"),
            Map.entry("PPM", "ppm（百万分之一）"), Map.entry("PERCENT", "百分比"),
            Map.entry("BEFORE_STARTUP", "开机前"), Map.entry("AFTER_SHIFT", "每班后"),
            Map.entry("AFTER_PRODUCT_CHANGEOVER", "换产品后"), Map.entry("AFTER_ALLERGEN_CHANGEOVER", "换过敏原产品后"),
            Map.entry("ATP", "ATP 检测"), Map.entry("CONDUCTIVITY", "电导率"), Map.entry("PH", "pH 值"),
            Map.entry("TEMPERATURE", "温度"), Map.entry("MICROBIOLOGICAL_SWAB", "微生物涂抹"),
            // 供应商准入
            Map.entry("LIVE_MATERIAL", "正式物料"),
            Map.entry("STANDARD", "标准验收"), Map.entry("NEW_SUPPLIER", "新供应商"),
            Map.entry("VALID_ONLY", "仅限在有效期内"), Map.entry("VALID_OR_EXPIRING_WITH_HOLD", "有效或即将到期（到期则暂扣）"),
            Map.entry("BUSINESS_LICENSE", "营业执照"), Map.entry("SC_LICENSE", "食品生产许可证（SC）"),
            Map.entry("IMPORT_CERT", "进口证明"), Map.entry("HACCP_CERT", "HACCP 认证"),
            Map.entry("ISO22000", "ISO 22000 认证"), Map.entry("FSSC22000", "FSSC 22000 认证"),
            Map.entry("THIRD_PARTY_TEST", "第三方检测报告"), Map.entry("OTHER", "其他"),
            // 追溯召回
            Map.entry("UPSTREAM", "向上游追溯"), Map.entry("DOWNSTREAM", "向下游追溯"),
            Map.entry("ROUTE_NODE", "工艺节点"), Map.entry("BOM_ITEM", "配方物料"), Map.entry("WAREHOUSE", "仓库"),
            Map.entry("DEVIATION_RULE", "偏差规则"),
            Map.entry("SUPPLIER_RECEIPT", "供应商收货"), Map.entry("MATERIAL_STORAGE", "物料存储"),
            Map.entry("PRODUCTION_TRANSFORMATION", "生产加工"), Map.entry("FINISHED_GOODS_STORAGE", "成品存储"),
            Map.entry("CUSTOMER_DISPATCH", "客户发货"), Map.entry("QUALITY_DISPOSITION", "质量处置"),
            Map.entry("SUPPLIER_LOT", "供应商批号"), Map.entry("MATERIAL_BATCH", "物料批次"),
            Map.entry("PRODUCTION_BATCH", "生产批次"), Map.entry("FINISHED_LOT", "成品批号"),
            Map.entry("SHIPMENT_REFERENCE", "发货单号"), Map.entry("QUALITY_DECISION_REFERENCE", "质量决定单号"));

    /** 页面上显示的中文；没有登记时退回常量名（测试会抓出来）。 */
    static String label(Enum<?> constant) {
        String qualified = BY_ENUM.get(constant.getDeclaringClass().getSimpleName() + "." + constant.name());
        if (qualified != null) return qualified;
        return BY_NAME.getOrDefault(constant.name(), constant.name());
    }

    /** 已登记过中文名吗（给测试用）。 */
    static boolean hasLabel(Enum<?> constant) {
        return BY_ENUM.containsKey(constant.getDeclaringClass().getSimpleName() + "." + constant.name())
                || BY_NAME.containsKey(constant.name());
    }

    static <E extends Enum<?>> List<Option> options(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(value -> new Option(value.name(), label(value))).toList();
    }

    /** 「A、B、C 之一」，写中文名，不写英文代码。 */
    static <E extends Enum<?>> String oneOf(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(DraftEnumLabels::label).collect(Collectors.joining("、")) + " 之一";
    }
}
