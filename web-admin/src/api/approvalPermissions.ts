/**
 * 本厂「谁能审批」(纪律排查 B1–B3 / B11 / B15, Steve 2026-11-10「审批人按权限」)。
 *
 * - GET  /{factoryId}/approval-permissions                         岗位 × 审批类型 的授权一览 (含来源)
 * - PUT  /{factoryId}/canvas/role-module-override/{role}/{code}?level=rw|-   授给 / 不授给 (本厂覆盖)
 *   不带 level = 恢复系统默认 (默认 = 改动前写死的名单; 改价 / 退款默认跟财务 / 销售读写权限走)
 */
import { get } from './request';
import { updateFactoryOverride } from './permissionApi';

export type ApprovalGrantSource = 'FACTORY' | 'PLATFORM' | 'DEFAULT' | 'ALWAYS';

export interface ApprovalGrantCell {
  granted: boolean;
  source: ApprovalGrantSource;
}

export interface ApprovalPermissionsView {
  approvals: { code: string; label: string }[];
  roles: { code: string; label: string }[];
  /** 审批码 → 岗位码 → 授权 */
  grants: Record<string, Record<string, ApprovalGrantCell>>;
}

export async function getApprovalPermissions(factoryId: string): Promise<ApprovalPermissionsView> {
  const res = await get<ApprovalPermissionsView>(`/${factoryId}/approval-permissions`);
  if (!res.success || !res.data) throw new Error(res.message || '读取审批权限失败');
  return res.data;
}

/** grant: true = 授给, false = 不授给, null = 恢复系统默认。 */
export function setApprovalGrant(factoryId: string, role: string, approvalCode: string, grant: boolean | null) {
  return updateFactoryOverride(factoryId, role, approvalCode, grant === null ? null : grant ? 'rw' : '-');
}
