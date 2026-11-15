import type { RowAction } from '@/types/rowActions';

const PRIMARY_ACTION_IDS = new Set(['view-detail', 'print-pdf', 'submit']);
const FORBIDDEN_BUSINESS_PAGE_IDS = new Set(['approve', 'reject', 'audit', 'delete']);

export function purchaseOrderMoreActions(actions: RowAction[]): RowAction[] {
  const seen = new Set<string>();
  return actions.filter((action) => {
    if (PRIMARY_ACTION_IDS.has(action.id) || FORBIDDEN_BUSINESS_PAGE_IDS.has(action.id)) return false;
    if (seen.has(action.id)) return false;
    seen.add(action.id);
    return true;
  });
}

export function canSubmitPurchaseOrder(status: unknown, canWrite: boolean): boolean {
  return canWrite && String(status) === 'DRAFT';
}

/**
 * M-3 (2026-08-20 手动测试台账): DRAFT 单还没提交/审批, 可能被改数量或驳回,
 * 「对外供货单」是发给供应商备货用的, 不能在这一步就能下载。
 * 只卡「提交」这一步, ⛔ 不卡「审核通过」—— 小额单会被阈值免审直通 (M-4)。
 */
export function canDownloadSupplyOrder(status: unknown): boolean {
  return String(status) !== 'DRAFT';
}
