import {
  User,
  FACTORY_ROLES,
  PLATFORM_ROLES,
  ROLE_METADATA,
  RoleMetadata,
  ModulePermissions,
  ModulePermissionLevel,
} from '../types/auth';
// 离线兜底快照 (生成物, 见下方「模块权限检查」一节与 scripts/gen-permission-l1-snapshot.js)
import l1Snapshot from './permissionMatrix.l1Snapshot.json';

/**
 * 权限检查工具函数
 * 用于统一管理屏幕的权限验证逻辑
 *
 * 权限格式: module:action (如 production:read, quality:write)
 * 模块: dashboard, production, warehouse, quality, procurement, sales, hr, equipment, finance, system
 * 动作: read, write, read_write
 *
 * @version 2.0.0 - 支持14角色系统
 */

// ==================== 角色元数据访问 ====================

/**
 * 获取角色元数据
 */
export function getRoleMetadata(roleCode: string): RoleMetadata | undefined {
  return ROLE_METADATA[roleCode];
}

/**
 * 获取角色等级
 */
export function getRoleLevel(roleCode: string): number {
  return ROLE_METADATA[roleCode]?.level ?? 99;
}

/**
 * 获取角色部门
 */
export function getRoleDepartment(roleCode: string): string {
  return ROLE_METADATA[roleCode]?.department ?? 'none';
}

// ==================== 用户角色获取 ====================

/**
 * 获取用户的标准化角色代码
 */
export function getRoleCode(user: User | null | undefined): string {
  if (!user) return 'viewer';

  // 平台管理员
  if (user.userType === 'platform') {
    return user.platformUser?.role || PLATFORM_ROLES.PLATFORM_ADMIN;
  }

  // 工厂用户
  const rawRole = user.factoryUser?.role;
  const position = user.factoryUser?.position;

  // 向后兼容：将旧的position值映射到新角色
  if (position === 'proc_admin') {
    return FACTORY_ROLES.PRODUCTION_MANAGER;
  }

  return rawRole || FACTORY_ROLES.VIEWER;
}

/**
 * 获取用户权限等级
 */
export function getUserLevel(user: User | null | undefined): number {
  if (!user) return 99;
  const roleCode = getRoleCode(user);
  return getRoleLevel(roleCode);
}

// ==================== 角色类型检查 ====================

/**
 * 检查用户是否为平台管理员
 */
export function isPlatformAdmin(user: User | null | undefined): boolean {
  if (!user) return false;
  return user.userType === 'platform';
}

/**
 * 检查用户是否为工厂超级管理员
 */
export function isSuperAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.FACTORY_SUPER_ADMIN;
}

/**
 * 检查用户是否为管理层 (Level <= 10)
 */
export function isManager(user: User | null | undefined): boolean {
  if (!user) return false;
  const level = getUserLevel(user);
  return level <= 10;
}

/**
 * 检查用户是否为一线员工 (Level 30)
 */
export function isWorker(user: User | null | undefined): boolean {
  if (!user) return false;
  const level = getUserLevel(user);
  return level === 30;
}

/**
 * 检查用户是否可以管理目标角色
 */
export function canManageRole(user: User | null | undefined, targetRoleCode: string): boolean {
  if (!user) return false;
  const userLevel = getUserLevel(user);
  const targetLevel = getRoleLevel(targetRoleCode);
  return userLevel < targetLevel;
}

// ==================== 新角色检查函数 ====================

/**
 * 检查用户是否为生产经理
 */
export function isProductionManager(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.PRODUCTION_MANAGER;
}

/**
 * 检查用户是否为质量经理
 */
export function isQualityManager(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.QUALITY_MANAGER;
}

/**
 * 检查用户是否为车间主任
 */
export function isWorkshopSupervisor(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.WORKSHOP_SUPERVISOR;
}

/**
 * 检查用户是否为质检员
 */
export function isQualityInspector(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.QUALITY_INSPECTOR;
}

/**
 * 检查用户是否为操作员
 */
export function isOperator(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.OPERATOR;
}

/**
 * 检查用户是否为仓储相关角色 (仓储主管 或 仓库员)
 */
export function isWarehouseRole(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.WAREHOUSE_MANAGER ||
         roleCode === FACTORY_ROLES.WAREHOUSE_WORKER;
}

/**
 * 检查用户是否为HR管理员
 */
export function isHrAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.HR_ADMIN;
}

/**
 * 检查用户是否为设备管理员
 */
export function isEquipmentAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.EQUIPMENT_ADMIN;
}

// ==================== 向后兼容函数 (已废弃角色) ====================

/**
 * 检查用户是否为权限管理员 (已废弃，保留向后兼容)
 * @deprecated 使用 isSuperAdmin 或 isManager 替代
 */
export function isPermissionAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.PERMISSION_ADMIN ||
         roleCode === FACTORY_ROLES.FACTORY_SUPER_ADMIN;
}

/**
 * 检查用户是否为部门管理员 (已废弃，保留向后兼容)
 * @deprecated 使用具体的经理角色检查函数替代
 */
export function isDepartmentAdmin(user: User | null | undefined): boolean {
  if (!user || user.userType !== 'factory') return false;
  const roleCode = getRoleCode(user);
  return roleCode === FACTORY_ROLES.DEPARTMENT_ADMIN ||
         roleCode === FACTORY_ROLES.PRODUCTION_MANAGER;
}

// ==================== 模块权限检查 ====================

/**
 * 离线兜底: prod L1 `platform_role_permissions` 的只读快照 (生成物, ⛔ 不要手改)。
 *
 * BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906: 这里以前是一份手写矩阵, 与 prod L1
 * 量出 29 格漂移 (permission_admin.sales RN rw / L1 -, operator.production RN w / L1 rw, …)。
 * 现在真相来自**登录响应** `factoryUser.modulePermissions` (后端 L2→L1→硬编码 逐格解析,
 * 与 `@RequirePermission` 判定一致); 快照只给「OTA 之前登录、还没重新登录」的旧会话兜底。
 * 刷新方式见 `scripts/gen-permission-l1-snapshot.js` 头部。(JSON 在文件顶部 import。)
 */

/** 超管通配键, 与后端 PermissionServiceImpl.MODULE_LEVEL_WILDCARD 同一个字面量。 */
export const MODULE_LEVEL_WILDCARD = '*';

const VALID_LEVELS: ReadonlySet<string> = new Set(['rw', 'r', 'w', '-']);

export const L1_SNAPSHOT_EXPORTED_AT: string = l1Snapshot.exportedAt;

const L1_SNAPSHOT_LEVELS: Record<string, Record<string, string>> = l1Snapshot.levels;

/**
 * 把登录响应里的 `modulePermissions` 收成 {@link ModulePermissions}。
 * 不是对象 / 为 null ⇒ undefined (旧后端或平台管理员); 值不合法的格**丢弃并告警**, 不猜。
 * ⚠️ 返回 undefined 与返回 {} 语义不同: 前者「没带」→ 退到快照, 后者「带了但一格都没有」→ 全 deny。
 */
export function parseModulePermissions(raw: unknown): ModulePermissions | undefined {
  if (raw === null || raw === undefined) return undefined;
  if (typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  const out: ModulePermissions = {};
  for (const [module, level] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof level === 'string' && VALID_LEVELS.has(level)) {
      out[module] = level as ModulePermissionLevel;
    } else if (__DEV__) {
      console.warn(`[permissionHelper] 丢弃非法模块权限格 ${module}=${String(level)}`);
    }
  }
  return out;
}

/** 级别 → 动作: 与后端 PermissionServiceImpl.checkAction 的 read/write 分支同口径。 */
export function levelAllows(level: string | undefined, action: 'read' | 'write'): boolean {
  if (!level) return false;
  if (action === 'read') return level === 'rw' || level === 'r';
  return level === 'rw' || level === 'w';
}

/**
 * 检查用户是否有指定模块的权限
 * @param user 用户对象
 * @param module 模块名称 (dashboard, production, warehouse, quality, etc.)
 * @param action 动作类型 (read, write)
 *
 * 判定顺序:
 *   1. 平台管理员 → true (与后端 platform_admin 超管分支同口径)
 *   2. 登录响应 `factoryUser.modulePermissions` 存在 → 只看它 (缺格看 "*" 通配, 再缺 = 无权限)
 *   3. 否则 (旧会话) → 本地 L1 快照
 */
export function hasModulePermission(
  user: User | null | undefined,
  module: string,
  action: 'read' | 'write' = 'read'
): boolean {
  if (!user) return false;

  // 平台管理员拥有所有权限
  if (isPlatformAdmin(user)) return true;

  const fromLogin = user.userType === 'factory' ? user.factoryUser?.modulePermissions : undefined;
  if (fromLogin) {
    return levelAllows(fromLogin[module] ?? fromLogin[MODULE_LEVEL_WILDCARD], action);
  }

  const roleCode = getRoleCode(user);
  const rolePerms = L1_SNAPSHOT_LEVELS[roleCode];
  if (!rolePerms) return false;
  return levelAllows(rolePerms[module], action);
}

/**
 * 检查用户是否可以访问指定模块
 */
export function canAccessModule(user: User | null | undefined, module: string): boolean {
  return hasModulePermission(user, module, 'read') || hasModulePermission(user, module, 'write');
}

// ==================== 功能权限检查 ====================

/**
 * 检查用户是否可以管理基础数据
 * 权限：平台管理员、工厂超管、管理层 (Level <= 10)
 */
export function canManageBasicData(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user) || isManager(user);
}

/**
 * 检查用户是否可以管理用户
 * 权限：平台管理员、工厂超管、HR管理员
 */
export function canManageUsers(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user) || isHrAdmin(user);
}

/**
 * 检查用户是否可以管理部门
 * 权限：平台管理员、工厂超管
 */
export function canManageDepartments(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user);
}

/**
 * 检查用户是否可以管理权限
 * 权限：平台管理员、工厂超管
 */
export function canManagePermissions(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user);
}

/**
 * 检查用户是否可以查看报表
 * 权限：平台管理员、工厂超管、所有管理层
 */
export function canViewReports(user: User | null | undefined): boolean {
  if (!user) return false;
  return isPlatformAdmin(user) || isSuperAdmin(user) || isManager(user);
}

/**
 * 检查用户是否可以管理生产
 */
export function canManageProduction(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'production', 'write');
}

/**
 * 检查用户是否可以管理仓库
 */
export function canManageWarehouse(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'warehouse', 'write');
}

/**
 * 检查用户是否可以管理质量
 */
export function canManageQuality(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'quality', 'write');
}

/**
 * 检查用户是否可以管理设备
 */
export function canManageEquipment(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'equipment', 'write');
}

/**
 * 检查用户是否可以管理HR
 */
export function canManageHR(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'hr', 'write');
}

/**
 * 员工注册白名单的准入角色 (APP-RBAC-003)。
 *
 * 逐字对齐后端 WhitelistController 上的
 * @RequireRole({"factory_super_admin","permission_admin","hr_admin"}) —
 * 该注解挂在白名单的**每一个**接口上, 包括 GET /whitelist 与 GET /whitelist/stats,
 * 所以不在此列的角色连读都是 403。前端任何白名单入口都必须用这个函数判定,
 * 否则会出现"入口可见 → 点进去 403 → 被 catch 掉降级成 0/空列表"的假数据。
 *
 * 2026-08-02 Steve 拍板放开给 hr_admin: 这个 Tab 只挂在 HR 导航下, 放开前
 * **唯一能看见它的角色必然 403**, 从来没有人用成功过。后端同时把写接口的
 * @RequirePermission 从 system:read_write 改成 hr:read_write (hr_admin 已有),
 * 所以 HR 拿到的是**完整读写**, 不会出现"列表能看、新增点了 403"的半开状态。
 *
 * ⚠️ 改这里之前先改后端注解: 这是后端授权的镜像, 不是独立的前端策略。
 */
export const WHITELIST_ACCESS_ROLES = [
  'factory_super_admin',
  'permission_admin',
  'hr_admin',
] as const;

export function canAccessWhitelist(user: User | null | undefined): boolean {
  if (!user) return false;
  if (isPlatformAdmin(user)) return true;
  return (WHITELIST_ACCESS_ROLES as readonly string[]).includes(getRoleCode(user));
}

// ==================== 工具函数 ====================

/**
 * 获取工厂ID（如果是工厂用户）
 */
export function getFactoryId(user: User | null | undefined): string | undefined {
  if (!user || user.userType !== 'factory') return undefined;
  return user.factoryUser?.factoryId;
}

/**
 * 获取用户角色的中文名称
 */
export function getRoleName(user: User | null | undefined): string {
  if (!user) return '访客';

  if (user.userType === 'platform') {
    return '平台管理员';
  }

  const roleCode = getRoleCode(user);
  const metadata = ROLE_METADATA[roleCode];

  return metadata?.displayName || '未知角色';
}

/**
 * 获取用户可访问的模块列表
 */
export function getAccessibleModules(user: User | null | undefined): string[] {
  if (!user) return [];

  if (isPlatformAdmin(user)) {
    return ['dashboard', 'production', 'warehouse', 'quality', 'procurement',
            'sales', 'hr', 'equipment', 'finance', 'system'];
  }

  // 与 hasModulePermission 同一优先级: 登录响应 → 本地 L1 快照
  const fromLogin = user.userType === 'factory' ? user.factoryUser?.modulePermissions : undefined;
  const rolePerms: Record<string, string> | undefined = fromLogin ?? L1_SNAPSHOT_LEVELS[getRoleCode(user)];

  if (!rolePerms) return [];

  return Object.entries(rolePerms)
    .filter(([module, level]) => module !== MODULE_LEVEL_WILDCARD && level !== '-')
    .map(([module]) => module);
}

/**
 * 权限日志记录辅助函数
 * 用于调试权限问题
 */
export function getPermissionDebugInfo(user: User | null | undefined) {
  if (!user) {
    return {
      userType: 'none',
      roleCode: 'viewer',
      roleLevel: 99,
      permissions: {
        canManageBasicData: false,
        canManageUsers: false,
        canManageDepartments: false,
        canManagePermissions: false,
        canViewReports: false,
        canManageProduction: false,
        canManageWarehouse: false,
        canManageQuality: false,
      },
      accessibleModules: [],
    };
  }

  const roleCode = getRoleCode(user);

  return {
    userType: user.userType,
    roleCode,
    roleName: getRoleName(user),
    roleLevel: getUserLevel(user),
    roleDepartment: getRoleDepartment(roleCode),
    factoryId: getFactoryId(user),
    rawRole: user.userType === 'factory' ? user.factoryUser?.role : user.platformUser?.role,
    position: user.userType === 'factory' ? user.factoryUser?.position : undefined,
    permissions: {
      canManageBasicData: canManageBasicData(user),
      canManageUsers: canManageUsers(user),
      canManageDepartments: canManageDepartments(user),
      canManagePermissions: canManagePermissions(user),
      canViewReports: canViewReports(user),
      canManageProduction: canManageProduction(user),
      canManageWarehouse: canManageWarehouse(user),
      canManageQuality: canManageQuality(user),
    },
    accessibleModules: getAccessibleModules(user),
  };
}

// ==================== SmartBI 权限检查 ====================

/**
 * 检查用户是否可以访问 SmartBI 模块
 */
export function canAccessSmartBI(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'read') ||
         hasModulePermission(user, 'sales', 'read') ||
         hasModulePermission(user, 'finance', 'read');
}

/**
 * 检查用户是否可以上传 Excel
 */
export function canUploadExcel(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'write');
}

/**
 * 检查用户是否可以访问销售分析
 */
export function canAccessSalesAnalysis(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'sales', 'read') ||
         hasModulePermission(user, 'analytics', 'read');
}

/**
 * 检查用户是否可以访问财务分析
 */
export function canAccessFinanceAnalysis(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'finance', 'read') ||
         hasModulePermission(user, 'analytics', 'read');
}

/**
 * 检查用户是否可以访问经营驾驶舱
 */
export function canAccessExecutiveDashboard(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'read');
}

/**
 * 检查用户是否可以使用 AI 问答
 */
export function canUseAIQuery(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'read');
}

/**
 * 检查用户是否可以查看预警和建议
 */
export function canViewAlerts(user: User | null): boolean {
  if (!user) return false;
  return hasModulePermission(user, 'analytics', 'read') ||
         hasModulePermission(user, 'sales', 'read') ||
         hasModulePermission(user, 'finance', 'read');
}
