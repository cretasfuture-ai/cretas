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
 * 本文件是与 Java 源码比对的前端镜像 —— 两份一致由
 * materialTypeWriteRolesMatchBackend.spec.ts 从 Java 源码解析后钉住 (不手抄)：
 *
 *   ① RawMaterialTypeController#createMaterialType 的 @RequireRole
 *      (updateMaterialType / deleteMaterialType 与其逐字节相同, 由后端
 *       RawMaterialTypeManagerOnlyRoleGateTest 钉住)
 *   ② RequireRoleInterceptor.PLATFORM_ADMIN_ROLES (无条件放行)
 *
 * ⚠️ 别手工改这个数组而不管那道闸 —— 手抄的那份会跟着漂, 而漂了不会有人发现
 * (measurement-and-wiring 形态 D)。
 *
 * 2026-09-04 CI 追加: `list.vue` 不再 import 本文件 —— 它自己在 `<script setup>`
 * 里声明了一个同样内容的本地 `MATERIAL_TYPE_WRITE_ROLE_SET`，因为分支可达性静态
 * 分析器 (branchReachability.spec.ts) 只能看懂同文件内声明的 `Set([...字面量])`
 * + `.has()`，看不懂跨文件的函数调用 (实测：该分析器把 `canManageMaterialTypes(role)`
 * 当成 unknown，而 `core()` 会把 and 链里的 unknown 直接丢掉，把写门禁误判成一条
 * 恒假的空转分支)。本文件仍是与 Java 比对的那个标准答案，
 * `materialTypeWriteRolesMatchBackend.spec.ts` 另外钉住 `list.vue` 那个本地 Set
 * 字面量与本文件逐字节相同 —— 两处都不许直接漂。
 */
export const MATERIAL_TYPE_WRITE_ROLES: string[] = [
  'warehouse_manager', 'factory_super_admin',
  'super_admin', 'platform_admin', 'developer', 'platform_super_admin',
];

/** 当前角色能不能新建/编辑/删除原料类型 —— 与后端 @RequireRole 判定逐字节等价。 */
export function canManageMaterialTypes(role: string | null | undefined): boolean {
  return !!role && MATERIAL_TYPE_WRITE_ROLES.includes(role);
}
