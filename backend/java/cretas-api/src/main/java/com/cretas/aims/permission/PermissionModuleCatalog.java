package com.cretas.aims.permission;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限模块码 → 中文名 的<b>唯一</b>来源, 以及它们在给人看的表格里的<b>列序</b>。
 *
 * <h3>为什么要有这个类 (2026-09-07 刀 D 实测)</h3>
 * 同一批模块码的中文名此前有<b>三份</b>, 其中两份已经漂了:
 * <table>
 *   <tr><th>码</th><th>侧边栏 (用户天天看到)</th><th>PermissionInterceptor.moduleLabel</th><th>RoleController.DISPLAY_MODULES</th></tr>
 *   <tr><td>dashboard</td><td>首页</td><td>首页</td><td>数据看板</td></tr>
 *   <tr><td>warehouse</td><td>仓储管理</td><td>仓储管理</td><td>仓库管理</td></tr>
 *   <tr><td>quality</td><td>质量管理</td><td>质量管理</td><td>质检管理</td></tr>
 *   <tr><td>system</td><td>系统管理</td><td>系统管理</td><td>系统设置</td></tr>
 *   <tr><td>report</td><td>(无顶层菜单)</td><td>报表</td><td>报表中心</td></tr>
 * </table>
 *
 * <p><b>措辞按侧边栏定, 不按谁先写</b>: 5 处冲突里侧边栏 (`menuConfig.ts` 的顶层 `title`)
 * 逐条站在 {@code moduleLabel} 一边。所以本表逐字采用 {@code moduleLabel} 的措辞 ⇒
 * 那 17 个既有 case 的 403 文案<b>逐字不变</b>, 变的只有角色权限页那 5 个标签,
 * 而且是<b>向用户已经在看的那份靠拢</b>。
 *
 * <h3>🔴 有<b>一处</b> 403 文案确实变了, 登记在此</h3>
 * {@code operations} 在原来那个 switch 里<b>没有 case</b>, 走 {@code default: return moduleCode}
 * ⇒ 403 body 里印的是 {@code [operations] 模块}。本表给了它中文名, 于是变成 {@code [运营协调] 模块}。
 * 它<b>不是死码</b>: {@code CustomerMaterialArrivalNoticeController} 有 3 处
 * {@code @RequirePermission({"operations:…"})} (:39/:49/:61), 这些 403 是活的。
 *
 * <p>⚠️ 这一处最初被我漏报成「一个字都没变」, 而漏报的成因有普遍性:
 * 钉住旧措辞的那张表<b>是从旧 switch 的 case 抄来的</b>, 所以它覆盖的恰好是
 * <b>不会变</b>的那些码 —— 对「原本走 default、现在有名字了」的码<b>按构造是盲的</b>。
 * ⇒ {@code PermissionModuleCatalogContractTest.catalogCodesAreEitherPinnedOrRegistered} 把这个洞补上:
 * 本表的码集必须恰好等于「17 个既有 case ∪ 显式登记的新增」, 加码不登记就红。
 *
 * <h3>⛔ 这里不是「第 N 份模块清单」</h3>
 * 本表<b>不决定</b>哪些模块存在 —— 那由 L1 {@code platform_role_permissions} ∪ 硬编码兜底 ∪ L2
 * 现算 (见 {@code PermissionServiceImpl#resolveRoleModuleLevels})。本表只回答两件事:
 * 「这个码的中文名叫什么」和「它排第几列」。查不到的码<b>原样返回码</b> ——
 * 与 {@code ProductionModuleRegistry.resolve} / {@code OaModuleLabelResolver} 同一条纪律:
 * 缺口要看得见, ⛔ 后端不编造。
 * {@code PermissionModuleCatalogCoversKnownModulesTest} 钉住「兜底矩阵里的码必须都有中文名」,
 * 加了模块忘了补名字会红。
 *
 * <h3>列序</h3>
 * 按侧边栏顶层菜单的出现顺序 (首页 → 研发 → 生产 → 仓储 → 质量 → 采购 → 销售 → 人事 →
 * 设备 → 财务 → 系统 → 智能调度), 侧边栏上没有顶层菜单的码 (report / work_report /
 * inventory / analytics / operations / restaurant) 排在其后。⛔ 不按字典序: 那会把
 * 「首页」排到中间, 与用户的心智模型不一致。
 */
public final class PermissionModuleCatalog {

    private static final Map<String, String> LABELS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // ↓ 侧边栏顶层菜单顺序 (menuConfig.ts)
        m.put("dashboard", "首页");
        m.put("rd", "研发管理");
        m.put("production", "生产管理");
        m.put("warehouse", "仓储管理");
        m.put("quality", "质量管理");
        m.put("procurement", "采购管理");
        m.put("sales", "销售管理");
        m.put("hr", "人事管理");
        m.put("equipment", "设备管理");
        m.put("finance", "财务管理");
        m.put("system", "系统管理");
        m.put("scheduling", "智能调度");
        // ↓ 侧边栏上没有顶层菜单的码
        m.put("report", "报表");
        m.put("work_report", "工作报告");
        m.put("inventory", "库存管理");
        m.put("analytics", "数据分析");
        // operations 此前**任何一份 label 表里都没有**。措辞取自同一个枚举里这个模块自己的角色
        // FactoryUserRole.operations_coordinator = "运营协调员" / "跨部门来源单、到料预告与履约协调"
        // —— 是引用而不是现编。
        m.put("operations", "运营协调");
        m.put("restaurant", "餐饮管理");
        LABELS = Collections.unmodifiableMap(m);
    }

    private PermissionModuleCatalog() {
    }

    /** 中文名; 表里没有的码<b>原样返回</b> (让缺口看得见, 不编造)。 */
    public static String label(String moduleCode) {
        if (moduleCode == null) {
            return null;
        }
        return LABELS.getOrDefault(moduleCode, moduleCode);
    }

    /** 表里有没有这个码的中文名 (给闸用: 区分「有名字」与「兜底成了码本身」)。 */
    public static boolean hasLabel(String moduleCode) {
        return moduleCode != null && LABELS.containsKey(moduleCode);
    }

    /** 列序: 已登记的码按本表顺序, 未登记的码排在最后 (返回 {@code Integer.MAX_VALUE})。 */
    public static int columnOrder(String moduleCode) {
        int i = 0;
        for (String code : LABELS.keySet()) {
            if (code.equals(moduleCode)) {
                return i;
            }
            i++;
        }
        return Integer.MAX_VALUE;
    }

    /** 已登记的模块码, 按列序。⛔ 不要拿它当「模块全集」—— 全集由三层现算。 */
    public static List<String> labelledCodes() {
        return List.copyOf(LABELS.keySet());
    }
}
