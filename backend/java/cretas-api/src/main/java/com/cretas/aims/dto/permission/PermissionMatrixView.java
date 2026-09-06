package com.cretas.aims.dto.permission;

import java.util.List;
import java.util.Map;

/**
 * 「权限矩阵」只读视图 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906 step 1)。
 *
 * <p>把权威链上的三层并排摆出来, <b>不做任何写入</b>:
 * <ul>
 *   <li>{@link #l1} —— {@code platform_role_permissions} 表实际存在的行 (权威)。
 *       <b>只含存在的行</b>: 某个 (role, module) 不在这个 map 里, 表示 L1 对它
 *       <b>没有意见</b>, 于是兜底矩阵在那一格是<b>真的生效的</b>。
 *       ⛔ 不要把缺失的格子填成 {@code "-"} —— 那会把「L1 没管」和「L1 说不给」
 *       混成同一个读数, 而这两者的下游行为正好相反。</li>
 *   <li>{@link #hardcoded} —— {@code PermissionServiceImpl.PERMISSION_MATRIX} 兜底矩阵,
 *       归一化成 rw/r/w/-。缺失的键统一是 {@code "-"} (兜底矩阵里查不到 = 无权限,
 *       见 {@code checkAction(null, ...)} 返回 false)。</li>
 *   <li>{@link #diffs} —— 两者不一致的格子, 见 {@link Diff#kind()}。</li>
 * </ul>
 *
 * <p>L2 (工厂级) 只作为<b>表头提示</b>返回, 不参与 l1/hardcoded 的计算 ——
 * 本页回答的是「平台默认长什么样」, 不是「某个用户实际能干什么」。
 * 后者还要叠 L4 (per-account) 与超管短路, 那是 step 2。
 */
public record PermissionMatrixView(
        String factoryId,
        List<String> modules,
        List<RoleRef> roles,
        Map<String, Map<String, String>> l1,
        Map<String, Map<String, String>> hardcoded,
        List<Diff> diffs,
        List<ModuleToggle> l2Overrides,
        List<RoleOverride> l2RoleOverrides,
        List<String> modulesWithoutL1) {

    /** 角色行。{@code group} ∈ platform / factory / restaurant, 用于前端分组。 */
    public record RoleRef(String code, String displayName, String group, int level) {
    }

    /**
     * 一格分歧。
     *
     * @param l1         L1 表里的值 (rw/r/w/-); {@code null} = 该 (role, module) 在 L1 表里<b>没有行</b>
     * @param hardcoded  兜底矩阵归一化后的值 (rw/r/w/-), 查不到时是 {@code "-"}
     * @param kind       {@code "override"} = L1 有行且与兜底不同 ⇒ <b>L1 赢, 兜底那行是死的/误导的</b>;
     *                   {@code "fallback"} = L1 无行而兜底给了权限 ⇒ <b>兜底正在真的生效</b>, L1 没覆盖到
     */
    public record Diff(String role, String module, String l1, String hardcoded, String kind) {
    }

    /** L2 工厂模块开关 (factory_module_configs.enabled)。表头用它把关掉的模块置灰。 */
    public record ModuleToggle(String module, boolean enabled) {
    }

    /** L2 工厂级 role×module 覆盖 (factory_module_configs.role_module_override)。 */
    public record RoleOverride(String role, String module, String level) {
    }
}
