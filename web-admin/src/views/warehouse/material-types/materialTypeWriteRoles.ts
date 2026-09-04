/**
 * 缺陷 46 follow-up (2026-09-04, owner 拍板): 「只让仓储主管改」物料字典。
 *
 * 后端在 create/update/delete 三个端点(RawMaterialTypeController)+
 * by-material PUT/DELETE 两个端点(MaterialPackagingHierarchyController)上
 * 新加了 {@code @RequireRole} 作为模块闸 (`canWrite('warehouse')`) 之外的第二道闸 ——
 * `warehouse_worker` 对 `warehouse` 模块的 platform_role_permissions 是 `'rw'`
 * (V20261024_22__warehouse_worker_read_warehouse_inventory.sql)，模块权限本身
 * 区分不了「仓管」和「仓储主管」，所以角色白名单必须单独维护。
 *
 * 本文件是**唯一来源**的前端镜像 —— 两份一致由
 * materialTypeWriteRolesMatchBackend.spec.ts 从 Java 源码解析后钉住 (不手抄)：
 *
 *   ① RawMaterialTypeController#createMaterialType 的 @RequireRole
 *      (updateMaterialType / deleteMaterialType 与其逐字节相同, 由后端
 *       RawMaterialTypeManagerOnlyRoleGateTest 钉住)
 *   ② RequireRoleInterceptor.PLATFORM_ADMIN_ROLES (无条件放行)
 *
 * ⚠️ 别手工改这个数组而不管那道闸 —— 手抄的那份会跟着漂, 而漂了不会有人发现
 * (measurement-and-wiring 形态 D)。
 */
export const MATERIAL_TYPE_WRITE_ROLES: string[] = [
  'warehouse_manager', 'factory_super_admin',
  'super_admin', 'platform_admin', 'developer', 'platform_super_admin',
];

/** 当前角色能不能新建/编辑/删除原料类型 —— 与后端 @RequireRole 判定逐字节等价。 */
export function canManageMaterialTypes(role: string | null | undefined): boolean {
  return !!role && MATERIAL_TYPE_WRITE_ROLES.includes(role);
}
