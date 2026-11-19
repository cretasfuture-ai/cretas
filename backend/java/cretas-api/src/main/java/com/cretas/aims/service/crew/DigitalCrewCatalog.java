package com.cretas.aims.service.crew;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 五位数字员工 (原型 crew-config.ts 的 CREW, 正式端代码常量; 启用与岗位映射按厂存表)。
 *
 * <p>⛔ 不是另一套「数字员工」模型: 五位就是正式端已有的五个数字专员头像
 * ({@code web-admin/public/digital-employees/*.png}), 各自的工作台是 #1753 已有的数字专员工作台
 * ({@link Member#workdesks()}); 数字班组只加「交办 → 等我确认 → 额度四态」这一层。
 * 能力 = 一组权限模块: 能不能查 / 办由登录人权限决定, 员工只决定「这件事归不归我管」。
 */
public final class DigitalCrewCatalog {

    private DigitalCrewCatalog() {
    }

    public record Capability(String label, String kind, String module) {
    }

    public record Member(String key, String name, String shortName, String duty, List<String> modules,
                         List<Capability> capabilities, List<String> workdesks) {
    }

    public static final List<Member> MEMBERS = List.of(
            new Member("clerk", "数字文员", "文员", "销售、采购单据的查询与跟进",
                    List.of("sales", "procurement"),
                    List.of(new Capability("查销售订单、发运、客户", "query", "sales"),
                            new Capability("查采购订单、采购需求、供应商", "query", "procurement"),
                            new Capability("办理销售 / 采购单据（先预览，有权限的人确认）", "write", "sales")),
                    List.of("/workdesk/sales-owner", "/workdesk/purchaser")),
            new Member("keeper", "数字仓管", "仓管", "库存、入库批次、临期与盘点",
                    List.of("warehouse", "inventory"),
                    List.of(new Capability("查库存、临期批次、收货任务", "query", "warehouse"),
                            new Capability("办理入库 / 库存单据（先预览，有权限的人确认）", "write", "warehouse")),
                    List.of("/workdesk/warehouse-keeper")),
            new Member("planner", "数字计划员", "计划员", "工单进度、齐套与缺料、急单安排",
                    List.of("production", "scheduling", "work_report"),
                    List.of(new Capability("查工单进度、报工、在制品", "query", "production"),
                            new Capability("查排产与产能", "query", "scheduling"),
                            new Capability("办理生产计划 / 批次（先预览，有权限的人确认）", "write", "production")),
                    List.of("/workdesk/production-manager")),
            new Member("inspector", "数字质检员", "质检员", "检验单、检验标准、不良分析",
                    List.of("quality"),
                    List.of(new Capability("查检验单、不良分布、追溯", "query", "quality"),
                            new Capability("记检验 / 不良（先预览，有权限的人确认）", "write", "quality")),
                    List.of("/workdesk/quality-chief", "/workdesk/quality-manager")),
            new Member("accountant", "数字成本会计", "成本会计", "出成率、工单成本、对账差异",
                    List.of("finance", "analytics"),
                    List.of(new Capability("查成本、毛利、应收应付", "query", "finance"),
                            new Capability("查经营分析", "query", "analytics")),
                    List.of("/workdesk/finance-manager")));

    /** 页面上的推荐问题: 点它等于直接问「这个意图」。 */
    public record QuickQuestion(String text, String intentCode) {
    }

    /**
     * 各位数字员工页面自带的推荐问题 → 固定读意图。演示者会直接点这些话, 不能让识别层把
     * 「最近的采购订单」猜成销售订单列表、把「这个月的毛利情况」猜成利润表 (2026-11-18 LSMDEMO 实测)。
     * 只含读意图; 只有「整句与推荐问题一字不差」才直连意图, 用户自己改过的话照常走识别。
     * 前端 CrewMemberPage.vue 的推荐问题必须与这里逐字一致 (DigitalCrewQuickQuestionsContractTest 钉住)。
     */
    public static final Map<String, List<QuickQuestion>> QUICK_QUESTIONS = Map.of(
            "clerk", List.of(new QuickQuestion("最近的销售订单", "ORDER_LIST"),
                    new QuickQuestion("最近的采购订单", "PURCHASE_ORDER_LIST")),
            "keeper", List.of(new QuickQuestion("最近的原料批次", "MATERIAL_BATCH_QUERY"),
                    new QuickQuestion("待收货清单", "MATERIAL_TODAY_RECEIVING_QUERY")),
            "planner", List.of(new QuickQuestion("各产线的生产进度", "PRODUCTION_PROGRESS_DASHBOARD"),
                    new QuickQuestion("在制品批次有哪些", "MATERIAL_BATCH_WIP_QUERY")),
            "inspector", List.of(new QuickQuestion("最近的质量报表", "REPORT_QUALITY"),
                    new QuickQuestion("待质检批次", "RECEIVE_QUALITY_CHECK_TODAY")),
            "accountant", List.of(new QuickQuestion("本月成本最高的产品", "COST_QUERY"),
                    new QuickQuestion("这个月的毛利情况", "REPORT_SALES_MARGIN_SUMMARY")));

    /** 整句等于该员工某条推荐问题时返回它绑定的意图码, 否则 empty。 */
    public static Optional<String> quickIntentFor(String memberKey, String input) {
        if (memberKey == null || input == null) return Optional.empty();
        return QUICK_QUESTIONS.getOrDefault(memberKey, List.of()).stream()
                .filter(q -> q.text().equals(input.strip())).map(QuickQuestion::intentCode).findFirst();
    }

    /** 一线岗位 (手机端) → 默认数字员工; 按厂改了以表为准。与原型 DEMO_ROLES[*].mobile 相同。 */
    public static final Map<String, String> DEFAULT_ROLE_MAPPING;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("operator", "planner");
        m.put("warehouse_worker", "keeper");
        m.put("quality_inspector", "inspector");
        DEFAULT_ROLE_MAPPING = java.util.Collections.unmodifiableMap(m);
    }

    public static final Map<String, String> FRONTLINE_ROLE_LABELS = Map.of(
            "operator", "操作员", "warehouse_worker", "仓库员", "quality_inspector", "质检员");

    public static Optional<Member> member(String key) {
        return MEMBERS.stream().filter(m -> m.key().equals(key)).findFirst();
    }

    /** 这件事的模块归谁管 (按目录顺序第一位)。 */
    public static Optional<Member> ownerOf(String module) {
        return MEMBERS.stream().filter(m -> m.modules().contains(module)).findFirst();
    }

    /** 权限码 {@code module:action} 的模块段; 空 / 不合规返回 null。 */
    public static String moduleOf(String permission) {
        if (permission == null) return null;
        String p = permission.strip();
        int i = p.indexOf(':');
        if (i <= 0) return null;
        return p.substring(0, i);
    }

    /**
     * 逗号分隔的「任一」权限码 (如 {@code inventory:write,warehouse:read_write}) 归哪个模块:
     * 有这位员工管得着的就取它 (按登记顺序第一个), 都管不着取第一个码的模块 (⇒ 指给该管的人); 空 / 不合规返回 null。
     */
    public static String moduleOf(String permission, Member member) {
        List<String> modules = com.cretas.aims.service.execution.IntentPermissionGate.splitCodes(permission).stream()
                .map(DigitalCrewCatalog::moduleOf).toList();
        if (modules.isEmpty() || modules.contains(null)) return null;
        if (member != null) {
            for (String m : modules) {
                if (member.modules().contains(m)) return m;
            }
        }
        return modules.get(0);
    }
}
