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
}
