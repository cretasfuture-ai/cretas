package com.cretas.aims.ai.catalog;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.execution.IntentPermissionGate;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸: 启用的写意图 required_permission 不能为空 (数字班组「系统里没有登记谁有权确认，所以不交办」, 2026-11-17)。
 *
 * <p>两层都看: 目录层 ({@code ai/factory-intent-catalog.json} + 工具读写声明) 定「哪些是启用的写意图」,
 * 迁移层 (带 {@value WriteIntentPermissionSources#MARKER} 的 Flyway 迁移) 定「迁移跑完后每个意图的权限码」。
 * 新加的写意图 (目录里出现、工具声明 WRITE 或意图码带写后缀) 没在迁移里登记权限码 ⇒ 红。
 *
 * <p>不放宽: 工具自己声明了权限码 ({@link ToolExecutor#getRequiredPermissions()}, 与 web 端同功能接口
 * {@code @RequirePermission} 同源) 的, 意图上登记的码必须是它的子集 —— 不比 web 端宽。
 */
class WriteIntentRequiredPermissionContractTest {

    private static final java.util.regex.Pattern WRITE_CODE = java.util.regex.Pattern.compile("^[a-z_]+:(read_write|write)$");
    private static final java.util.regex.Pattern ANY_CODE = java.util.regex.Pattern.compile("^[a-z_]+:(read|write|read_write)$");

    /**
     * 故意不登记的启用写意图 (登记「故意不改」处, measurement-and-wiring 硬约束 8): web 端没有做同一件事的接口,
     * 或只按平台身份 / 审批节点岗位放行, 找不到不放宽的现成权限码。交 organizer 裁决; 这里每一条都必须仍是启用的写意图,
     * 且不能同时在迁移里登记 (不留陈旧豁免)。
     */
    static final Map<String, String> EXEMPT = Map.ofEntries(
            Map.entry("APPROVAL_ACTION_EXECUTE", "web 走 workflow 节点 approverRoles (canTransition), 无 @RequirePermission"),
            Map.entry("CLOCK_IN", "一线打卡默认开放 (FrontlineAiWriteGrantService.DEFAULT_OPEN / V20261101_55); web 打卡是 hr:read_write, 登记会让操作员在 AI 里打不了卡 (#1816 终审 H1)"),
            Map.entry("CLOCK_OUT", "同 CLOCK_IN (#1816 终审 H1)"),
            Map.entry("BATCH_CONSUMPTION_ADJUST", "web 只有 procurement:price:view (看价码), 工具另限超管/权限管理员"),
            Map.entry("CUSTOMER_NOTIFY_BATCH", "web 无召回客户通知接口; 工具限超管/权限管理员"),
            Map.entry("FACTORY_CONFIG_AGENT", "web 无 WorkflowTemplateService 保存接口"),
            Map.entry("FINANCE_PPT_EXPORT", "web 无对应接口 (只调 Python 生成, 无业务写)"),
            Map.entry("FINANCIAL_CHART_GENERATE", "web 无对应接口 (只调 Python 生成, 无业务写)"),
            Map.entry("GOVERNANCE_PATTERN_DISCOVERY", "只许平台身份 (PlatformGovernanceAccess), web 是 @RequireRole"),
            Map.entry("GOVERNANCE_SKILL_COMPOSE", "只许平台身份; web 的 hr:read_write 与技能治理无关, 照抄会放宽"),
            Map.entry("GOVERNANCE_SKILL_MANAGE", "只许平台身份; web 启停无权限码、删除是 hr:read_write"),
            Map.entry("MATERIAL_MARK_ABACA", "web 无改抄码标记的接口 (最近的是 material_type:maintain, 未证明同功能)"),
            Map.entry("PRINT_TEMPLATE_CREATE_FROM_AI", "web 无 createFromAI 接口 (最近的表单模板 system:read_write, 未证明同功能)"),
            Map.entry("PURCHASE_FINANCE_APPROVE", "工具恒抛 410 (改走 OA), 不写"),
            Map.entry("PURCHASE_ORDER_APPROVE", "审批走 OA 节点岗位, 无 @RequirePermission"),
            Map.entry("REGULATORY_REPORT", "web 无对应接口; 工具限超管/权限管理员"));

    @Test
    void everyEnabledWriteIntentInTheCatalogHasANonBlankPermissionAfterMigrations() {
        Map<String, WriteIntentPermissionSources.CatalogIntent> catalog = WriteIntentPermissionSources.catalogIntents();
        Map<String, ToolExecutor> tools = WriteIntentPermissionSources.tools();
        Map<String, String> writes = WriteIntentPermissionSources.enabledWriteIntents(catalog, tools);
        Map<String, String> migrated = WriteIntentPermissionSources.migratedPermissions();
        System.out.println("[WriteIntentRequiredPermission] catalog=" + catalog.size() + " enabledWrite=" + writes.size()
                + " migratedEntries=" + migrated.size());

        // 阳性对照: 已知是写的那几个必须被判成写 —— 判定器坏了 (全判读) 时这里先红, 而不是「0 个写意图 ⇒ 全过」
        assertThat(writes).containsKeys("RECEIVE_CONFIRM_CREATE", "QUALITY_CHECK_CREATE", "PRODUCTION_PLAN_CREATE",
                "SHIPMENT_CREATE", "FINANCE_PAYMENT_RECORD");
        assertThat(writes.size()).as("写意图数量级 (目录 270 个意图里约 1/3 是写)").isGreaterThan(50);

        List<String> missing = new ArrayList<>();
        List<String> staleExempt = new ArrayList<>();
        for (String code : EXEMPT.keySet()) {
            if (!writes.containsKey(code)) staleExempt.add(code + ": 已不是启用的写意图, 从 EXEMPT 删掉");
            if (migrated.containsKey(code)) staleExempt.add(code + ": 迁移里已登记, 从 EXEMPT 删掉");
        }
        assertThat(staleExempt).isEmpty();
        System.out.println("[WriteIntentRequiredPermission] registered=" + writes.keySet().stream()
                .filter(c -> migrated.get(c) != null && !migrated.get(c).isBlank()).count() + " exempt=" + EXEMPT.size());
        for (String code : writes.keySet()) {
            if (EXEMPT.containsKey(code)) continue;
            String p = migrated.get(code);
            if (p == null || p.isBlank()) missing.add(code + " (tool=" + writes.get(code) + ")");
        }
        assertThat(missing)
                .as("启用的写意图没有登记 required_permission。在带 %s 的迁移里加一行 ('意图码', '模块:read_write'),"
                        + " 码与 web 端同功能接口的 @RequirePermission 一致 (工具声明了 getRequiredPermissions 就取它的子集)",
                        WriteIntentPermissionSources.MARKER)
                .isEmpty();
    }

    @Test
    void everyRegisteredCodeIsAnExistingWriteLevelCodeAndNoWiderThanTheToolDeclares() {
        Map<String, WriteIntentPermissionSources.CatalogIntent> catalog = WriteIntentPermissionSources.catalogIntents();
        Map<String, ToolExecutor> tools = WriteIntentPermissionSources.tools();
        Set<String> modules = new HashSet<>(PermissionServiceImpl.hardcodedModules());
        modules.addAll(PermissionServiceImpl.APPROVAL_MODULES);

        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : WriteIntentPermissionSources.migratedPermissions().entrySet()) {
            String code = e.getKey();
            String perm = e.getValue();
            WriteIntentPermissionSources.CatalogIntent row = catalog.get(code);
            if (row == null) {
                bad.add(code + ": 不是目录里的意图 (拼错?)");
                continue;
            }
            if (perm.length() > 64) bad.add(code + ": 超过 required_permission 列宽 64");
            List<String> codes = IntentPermissionGate.splitCodes(perm);
            if (codes.isEmpty()) bad.add(code + ": 空");
            ToolExecutor tool = row.toolName() == null ? null : tools.get(row.toolName());
            Set<String> toolCodes = tool == null ? Set.of() : tool.getRequiredPermissions();
            if (toolCodes == null) toolCodes = Set.of();
            for (String c : codes) {
                // 读级别码只在「工具自己就这么声明」时允许 (如请假提交 hr:read), 否则必须是写级别
                if (!ANY_CODE.matcher(c).matches()) bad.add(code + ": " + c + " 不是 模块:read|write|read_write");
                else if (!WRITE_CODE.matcher(c).matches() && !toolCodes.contains(c)) bad.add(code + ": " + c + " 不是写级别码, 工具也没这么声明");
                else if (!modules.contains(c.substring(0, c.indexOf(':')))) bad.add(code + ": " + c + " 的模块不在权限矩阵里 (不许新造)");
            }
            if (!toolCodes.isEmpty() && !toolCodes.containsAll(codes)) {
                bad.add(code + ": " + codes + " 比工具 " + row.toolName() + " 声明的 " + toolCodes + " 宽");
            }
        }
        assertThat(bad).isEmpty();
    }

    /** 不算「普通角色」: 平台 / 工厂超管 / 权限管理员恒过工具门, 拿它们证明「有人能用这个码」是恒真式; 餐饮岗位已退役。 */
    private static final Set<String> NOT_ORDINARY = Set.of("platform_admin", "factory_super_admin", "permission_admin",
            "unactivated", "restaurant_manager", "restaurant_owner", "restaurant_chef", "restaurant_purchaser");

    /**
     * #1816 终审 M1 / 变异 W: 登记的每个「任一」码都必须有用 —— 有普通岗位持有它、且工具本身放普通岗位时, 至少一个持有人
     * 在执行时也过得了工具那几道门 (一线开放名单 / 旧角色串 / 工具权限表与 getRequiredRoles, 即生产同一个 {@link com.cretas.aims.service.execution.ToolPermissionChain})。
     * 一个码谁拿着都执行不了 = 意图层比执行门宽出来的一截 (例如质检记录多挂一个 production:read_write)。
     * 岗位矩阵取 PermissionServiceImpl 内置矩阵 (工厂没有覆盖时的真实默认)。
     */
    @Test
    void everyRegisteredCodeLetsSomeOrdinaryRolePassTheExecutionGate() {
        ExecutionChainHarness chain = ExecutionChainHarness.build(WriteIntentPermissionSources.tools());
        Map<String, WriteIntentPermissionSources.CatalogIntent> catalog = WriteIntentPermissionSources.catalogIntents();
        List<String> useless = new ArrayList<>();
        List<String> ordinaryRoles = new ArrayList<>();
        for (com.cretas.aims.entity.enums.FactoryUserRole role : com.cretas.aims.entity.enums.FactoryUserRole.values()) {
            if (!NOT_ORDINARY.contains(role.name())) ordinaryRoles.add(role.name());
        }
        int checked = 0, adminOnlyCode = 0, adminOnlyTool = 0, judged = 0;
        for (Map.Entry<String, String> e : WriteIntentPermissionSources.migratedPermissions().entrySet()) {
            WriteIntentPermissionSources.CatalogIntent row = catalog.get(e.getKey());
            if (row == null || row.toolName() == null) continue;
            // 工具自己只放管理员 (getRequiredRoles / 旧角色串 / 默认矩阵下没人有的「且」码): 登记码再宽, 普通岗位执行时也被工具拒,
            // 意图层放过去不等于能执行; 数字班组的「谁能确认」由 DigitalCrewExecutionGate 按同一条链收窄 (M1)。
            boolean toolAdmitsOrdinary = ordinaryRoles.stream()
                    .anyMatch(r -> chain.passes(r, e.getKey(), row.toolName(), e.getValue()));
            for (String code : IntentPermissionGate.splitCodes(e.getValue())) {
                checked++;
                List<String> holders = ordinaryRoles.stream().filter(r -> chain.holds(r, code)).toList();
                if (holders.isEmpty()) { adminOnlyCode++; continue; } // 只有管理员持有的码: 不比执行门宽
                if (!toolAdmitsOrdinary) { adminOnlyTool++; continue; }
                judged++;
                boolean someone = holders.stream().anyMatch(r -> chain.passes(r, e.getKey(), row.toolName(), e.getValue()));
                if (!someone) useless.add(e.getKey() + ": " + code + " (tool=" + row.toolName() + ", holders=" + holders + ")");
            }
        }
        System.out.println("[WriteIntentRequiredPermission] executionChainChecked=" + checked + " judged=" + judged
                + " adminOnlyCode=" + adminOnlyCode + " adminOnlyTool=" + adminOnlyTool + " useless=" + useless.size());
        assertThat(checked).as("阳性对照: 真的逐码查了").isGreaterThan(70);
        assertThat(judged).as("阳性对照: 大部分码真的拿持有人去过了执行门, 不是全被跳过").isGreaterThan(60);
        assertThat(useless).as("登记的码没有任何普通岗位能凭它执行 —— 比执行门宽; 去掉这个码, 或整条进 EXEMPT").isEmpty();
    }

    @Test
    void theMigrationsOnlyFillBlanksOnLiveRows() {
        // 迁移层的语义 = 「补空」: 已经登记过的值 (含 prod 上手工回填的) 不被覆盖, 已软删的行不动
        for (Resource r : WriteIntentPermissionSources.markedMigrations()) {
            String sql = WriteIntentPermissionSources.read(r).replaceAll("\\s+", " ");
            assertThat(sql).as(r.getFilename())
                    .contains("deleted_at IS NULL")
                    .contains("required_permission IS NULL OR btrim(c.required_permission) = ''");
        }
    }
}
