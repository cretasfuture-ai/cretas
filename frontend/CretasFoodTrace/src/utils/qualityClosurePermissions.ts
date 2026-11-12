/**
 * 质检闭环 (不良品处置 / 批次召回) 在 App 上的入口与按钮判定。
 *
 * 纪律排查 B14 (Steve 2026-11-10/12): 谁能做按权限, 不按岗位名; 拦不拦由工厂配置。
 * ⛔ 这里不写任何岗位名单 —— 全部读登录响应 `modulePermissions` (后端 L2 工厂覆盖 → L1 平台 → 兜底
 * 逐格解析, 与 `@RequirePermission` 同一条链), 旧会话缺这份快照时由 hasModulePermission 退到 L1 快照。
 *
 * 与后端门槛一一对应:
 *   - 入口 (列表 / 详情只读)      quality:read 或 quality:read_write   (网页 B14 同口径: module 'quality')
 *   - 提交处置 / 发起召回 / 记录   quality:read_write
 *   - 批准 / 驳回处置             approval_quality_disposition:approve (= 该码 read_write)
 */
import type { User } from '../types/auth';
import { hasModulePermission } from './permissionHelper';

export const QUALITY_MODULE = 'quality';
export const QUALITY_DISPOSITION_APPROVAL_MODULE = 'approval_quality_disposition';

/** 读写 (rw) 才算: 后端 checkAction("read_write" / "approve") 只认 read_write。 */
function hasReadWrite(user: User | null | undefined, module: string): boolean {
  return hasModulePermission(user, module, 'read') && hasModulePermission(user, module, 'write');
}

/** 首页「不良品处置」「批次召回」入口是否出现。 */
export function canOpenQualityClosure(user: User | null | undefined): boolean {
  return hasModulePermission(user, QUALITY_MODULE, 'read');
}

/** 能否提交处置 / 发起召回 / 记录客户联系 / 登记退货 / 结案。 */
export function canWriteQualityClosure(user: User | null | undefined): boolean {
  return hasReadWrite(user, QUALITY_MODULE);
}

/** 能否批准 / 驳回质检处置 (含报废)。质检员默认没有 ⇒ 不显示审批按钮。 */
export function canApproveQualityDisposition(user: User | null | undefined): boolean {
  return hasReadWrite(user, QUALITY_DISPOSITION_APPROVAL_MODULE);
}
