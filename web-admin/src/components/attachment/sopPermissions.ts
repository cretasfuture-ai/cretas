/**
 * 工序 SOP 的写入口角色白名单 —— 与后端 AttachmentPermissionResolver.WORK_PROCESS_SOP_WRITE_ROLES 同一份口径。
 * 只决定「要不要显示上传 / 删除入口」；真正的权限由后端判，两边漂开时后端为准（多显示一个按钮会被 403 并给出原因）。
 *
 * 为什么不只看 production:read_write：该权限 9 个角色都有（含 operator / yield_operator / 组长），
 * 而 SOP 是操作员要照着做的标准，谁都能换等于没有标准。读口不受此限。
 */
export const WORK_PROCESS_SOP_WRITE_ROLES: readonly string[] = [
  'factory_super_admin',
  'platform_admin',
  'production_manager',
  'workshop_supervisor',
];

/** 工序 SOP: 生产模块可写 且 角色在白名单内。 */
export function canManageProcessSop(role: string, canWriteProduction: boolean): boolean {
  return canWriteProduction && WORK_PROCESS_SOP_WRITE_ROLES.includes(role);
}
