/**
 * Q3 (2026-11-09): 子发运单创建时按先进先出预填批次, 有的行没能自动分配 (例如只剩规格和本行不一致的批次,
 * 要人确认换算) —— 子发运单照样建成, 后端在响应的 allocationNotices 里逐行说明原因。
 * 这里只负责把它读出来、拼成给人看的话; 去哪里处理由调用方决定 (详情页直接打开「分配批次」)。
 */
export interface ShipmentAllocationNotice {
  deliveryItemId: string;
  productName?: string | null;
  code: string;
  message: string;
  hint?: string | null;
}

export const PACKAGING_MISMATCH_NOTICE_CODE = 'BATCH_PACKAGING_SPEC_MISMATCH';

function isNotice(value: unknown): value is ShipmentAllocationNotice {
  if (!value || typeof value !== 'object') return false;
  const row = value as Record<string, unknown>;
  return typeof row.code === 'string' && typeof row.message === 'string' && row.deliveryItemId != null;
}

/** 从子发运单创建响应 (data) 里取出逐行说明; 没有就是空数组。 */
export function allocationNoticesOf(data: unknown): ShipmentAllocationNotice[] {
  if (!data || typeof data !== 'object') return [];
  const raw = (data as { allocationNotices?: unknown }).allocationNotices;
  if (!Array.isArray(raw)) return [];
  return raw.filter(isNotice).map((row) => ({ ...row, deliveryItemId: String(row.deliveryItemId) }));
}

/** 一行一条: 「品名：原因（怎么办）」。 */
export function allocationNoticeLines(notices: ShipmentAllocationNotice[]): string[] {
  return notices.map((notice) => {
    const who = notice.productName ? `${notice.productName}：` : '';
    const hint = notice.hint ? `（${notice.hint}）` : '';
    return `${who}${notice.message}${hint}`;
  });
}

/** 有没有要人确认换算的行 —— 有的话详情页提供「去分配批次」直达确认入口。 */
export function needsPackagingConfirmation(notices: ShipmentAllocationNotice[]): boolean {
  return notices.some((notice) => notice.code === PACKAGING_MISMATCH_NOTICE_CODE);
}
