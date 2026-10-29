package com.cretas.aims.service;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.exception.BusinessException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限服务接口
 *
 * 提供用户权限检查、权限列表获取等功能
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-12-27
 */
public interface PermissionService {

    /**
     * 「只读本人工资」权限码 (2026-08-25, RN「我的工资」屏)。
     *
     * <p>⚠️ <b>三段式, 不是 {@code module:action}</b> —— 与 {@code procurement:price:view} /
     * {@code procurement:finance_review:view} 同族, 在 {@code hasPermission} 里
     * <b>split(":") 之前</b>被单独分支处理, 不走 role×module 矩阵。
     *
     * <p><b>为什么必须是独立码而不是 {@code hr:read}</b>:
     * {@code hr:read} 是「读人事模块」= 读<b>全厂</b>工资 (WageController 的
     * {@code /wage/payroll} 列表、{@code /wage/efficiency/daily/{workerId}} 都吃这个码,
     * 且 workerId 是<b>路径参数</b>)。给车间工人挂 {@code hr:read} 等于让任何工人读任何人的工资。
     * 本码只挂在 {@link com.cretas.aims.controller.MyWageController} 上, 而那个 controller
     * <b>不接受任何 workerId 入参</b>——身份只从 token 推导。所以「有这个码」的最大能力就是
     * 「读自己的」。
     *
     * <p>持有者: 任何已激活的工厂账号 (见 {@code PermissionServiceImpl.hasPermission} 的分支)。
     * 每个人都该看得到<b>自己</b>的工资, 这不是分级信息。
     */
    String SELF_WAGE_VIEW_PERMISSION = "hr:self_wage:view";

    /**
     * 检查用户是否拥有指定权限
     *
     * @param user 用户对象
     * @param permissionCode 权限代码 (格式: module:action, 如 production:read)
     * @return 是否拥有权限
     */
    boolean hasPermission(User user, String permissionCode);

    /**
     * 只看<b>配置</b>的模块权限 (L2 工厂覆盖 → L1 平台默认), 不落到硬编码兜底矩阵 (S5-12)。
     * 两层都没有这一格时, 只有 {@code fallbackRoleCodes} 里的角色算有权限。
     * 超管照常全通。默认实现退回 {@link #hasPermission} (测试替身用)。
     *
     * @param permissionCode    module:action
     * @param fallbackRoleCodes 未配置时允许的角色码
     */
    default boolean hasConfiguredPermission(User user, String permissionCode,
                                            java.util.Set<String> fallbackRoleCodes) {
        return hasPermission(user, permissionCode);
    }

    /**
     * 检查用户是否拥有多个权限中的任意一个
     *
     * @param user 用户对象
     * @param permissionCodes 权限代码列表
     * @return 是否拥有任一权限
     */
    boolean hasAnyPermission(User user, String... permissionCodes);

    /**
     * 检查用户是否拥有所有指定权限
     *
     * @param user 用户对象
     * @param permissionCodes 权限代码列表
     * @return 是否拥有所有权限
     */
    boolean hasAllPermissions(User user, String... permissionCodes);

    /**
     * 获取用户所有权限列表
     *
     * <p>⚠️ 只读硬编码矩阵 (不看 L1 平台默认 / L2 工厂覆盖), 只产出 {@code module:read} /
     * {@code module:write}, 产不出 {@code module:read_write} 等码 —— 它<b>不是</b>
     * {@link #hasAnyPermission} 的全集。判「这个人能不能用某个码」请逐码问 {@link #hasAnyPermission}。
     *
     * @param user 用户对象
     * @return 权限代码集合
     */
    Set<String> getUserPermissions(User user);

    /**
     * 获取用户可访问的模块列表
     *
     * @param user 用户对象
     * @return 模块名称列表
     */
    List<String> getAccessibleModules(User user);

    /**
     * 检查用户是否可以访问指定模块
     *
     * @param user 用户对象
     * @param module 模块名称
     * @return 是否可访问
     */
    boolean canAccessModule(User user, String module);

    /**
     * 检查用户是否可以管理目标用户
     *
     * @param manager 管理者
     * @param target 目标用户
     * @return 是否可管理
     */
    boolean canManageUser(User manager, User target);

    /**
     * 检查用户是否可以访问指定工厂的数据
     *
     * @param user 用户对象
     * @param factoryId 工厂ID
     * @return 是否可访问
     */
    boolean canAccessFactory(User user, String factoryId);

    /**
     * 匹配权限 (支持通配符)
     *
     * @param pattern 权限模式 (如 production:*, *:read)
     * @param permission 实际权限
     * @return 是否匹配
     */
    boolean matchPermission(String pattern, String permission);

    /**
     * 获取角色的默认权限列表
     *
     * @param role 角色枚举
     * @return 权限代码集合
     */
    Set<String> getRolePermissions(FactoryUserRole role);

    /**
     * 解析该用户在每个已知模块上的<b>有效</b>权限级别 ({@code rw} / {@code r} / {@code w} / {@code -})。
     *
     * <p>解析链与 {@link #hasPermission} <b>逐格一致</b>: 超管 ({@code factory_super_admin} /
     * {@code platform_admin}) 全 {@code rw} 并带一个 {@code "*"} 键; 其余角色按
     * L2 工厂覆盖 → L1 {@code platform_role_permissions} → 硬编码兜底 取第一个命中。
     * 模块全集 = 三层里任一层为该角色登记过的模块的并集 (不挑「哪一份清单是真的」)。
     *
     * <p>用途: 登录响应 {@code modulePermissions}。RN 端 {@code hasModulePermission} 读它,
     * 不再自带一份会与 L1 漂移的矩阵 (BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906)。
     * 契约: 对返回 map 里的每一格, {@code hasPermission(user, module + ":read")} 与
     * {@code hasPermission(user, module + ":write")} 的答案必须与该格级别一致 —— 见
     * {@code PermissionServiceImplResolveModuleLevelsContractTest}。
     *
     * @param user 用户 (null / 未激活 → 空 map)
     * @return module → rw|r|w|-; 永不为 null
     */
    Map<String, String> resolveModuleLevels(User user);

    /**
     * Canonical authorization snapshot. Known empty successful queries may use the existing
     * fallback; unavailable authorization repositories must throw rather than restore grants.
     * Implementations without this strict capability are rejected by authorization callers.
     */
    default Map<String, String> resolveModuleLevelsForAuthorization(User user) {
        throw new BusinessException(503, "权限配置暂时无法读取，请稍后重试");
    }


    /**
     * 同一条解析链的<b>角色级</b>入口: 不需要具体用户, 只要「哪个工厂 + 哪个角色」。
     *
     * <p>{@link #resolveModuleLevels(User)} 就是它加一层「从 user 取 factoryId/role」的壳 ——
     * 两条路径共用同一份链、同一个模块全集口径, ⛔ 不是第二份实现。
     *
     * <p>用途: 「角色管理 → 查看权限」页 ({@code RoleController})。它此前读的是
     * {@link #getRolePermissions(FactoryUserRole)} —— 那个方法<b>只读硬编码兜底矩阵,
     * 一格 L1 都不查</b>, 所以页面显示的不是系统实际在执行的权限。2026-09-07 实测:
     * 30 个角色 × 11 个模块共 294 格里 <b>30 格</b>与 L1 不一致 (含 permission_admin 10 格
     * 全是「页面说 rw / L1 说 -」)。
     *
     * @param factoryId L2 工厂覆盖所属工厂; null = 无工厂上下文, 从 L1 起算
     * @param role      角色 (null / unactivated → 空 map)
     * @return module → rw|r|w|-; 超管额外带一个 {@code "*"} 键; 永不为 null
     */
    Map<String, String> resolveRoleModuleLevels(String factoryId, FactoryUserRole role);

    /**
     * 系统已知的模块码全集 = L1 {@code platform_role_permissions} 里出现过的 ∪ 硬编码兜底声明的。
     *
     * <p>用途: 给「角色 × 模块」这类表格提供一条<b>与角色无关的稳定列轴</b>。
     * {@link #resolveRoleModuleLevels} 返回的是<b>某个角色</b>登记过的模块, 逐角色不同 ——
     * 拿它直接当列会让每个角色的表格列数都不一样 (实测 3 ~ 23 列), 而且
     * 「这个角色没有这一行」与「这个模块不存在」在页面上分不开。
     *
     * <p>⚠️ 轴上有而该角色没有登记的格, 有效值就是 {@code -}: 三层全没命中时
     * {@code resolveEffectiveLevel} 返回 null, {@code checkAction(null, …)} 一律 false。
     * ⛔ 这不是兜底默认值, 是可以被 {@code hasPermission} 证伪的真实读数。
     *
     * <p>L1 读失败时退回「只有硬编码那份」并记 WARN。实践上不改变结果 ——
     * 硬编码 {@code ALL_MODULES} 是 L1 非餐饮码的超集 —— 但这一点是<b>实测</b>而非设计保证。
     *
     * @return 模块码, 字典序; 永不为空 (至少是硬编码那份)
     */
    List<String> knownModuleAxis();
}
