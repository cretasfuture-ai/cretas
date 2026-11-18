/**
 * 列表页 0 条时的「说明式空状态」判定（LSMDEMO 演示：不能只显示裸的「暂无数据」）。
 *
 * 三态，⛔ 不许塌成一个：
 * - `error`：接口失败。禁止降级——不能把「没加载出来」说成「没有数据」，要显示错误并给重试。
 * - `filtered`：带着筛选条件查不到，提示调整/清空筛选条件。
 * - `empty`：本来就没有数据，说清楚为什么为空、下一步怎么做。
 * 判定顺序固定：先 error，再 filtered，最后 empty。
 */

export type ListEmptyKind = 'error' | 'filtered' | 'empty';

export interface ListEmptyInput {
  /** 加载失败的错误信息；非空即视为失败（优先级最高）。 */
  loadError?: string | null;
  /** 当前是否带了筛选条件（状态/关键词/日期等）。 */
  filtered?: boolean;
  /** 本来就没有数据时的标题，如「还没有销售退货单」。 */
  emptyTitle: string;
  /** 本来就没有数据时的下一步指引。 */
  emptyHint?: string;
}

export interface ListEmptyPresentation {
  kind: ListEmptyKind;
  title: string;
  hint: string;
}

export function resolveListEmpty(input: ListEmptyInput): ListEmptyPresentation {
  const err = (input.loadError ?? '').trim();
  if (err) {
    return { kind: 'error', title: `加载失败：${err}`, hint: '这不代表没有数据，请点「重试」，仍失败请联系管理员。' };
  }
  if (input.filtered) {
    return { kind: 'filtered', title: '没有符合当前筛选条件的记录', hint: '请调整或清空筛选条件后重新查询。' };
  }
  return { kind: 'empty', title: input.emptyTitle, hint: input.emptyHint ?? '' };
}

/** 取接口失败的可读原因（Error / 带 message 的对象 / 其它）。 */
export function loadErrorText(e: unknown, fallback = '请稍后重试'): string {
  if (e instanceof Error && e.message) return e.message;
  if (typeof e === 'string' && e) return e;
  if (e && typeof e === 'object' && 'message' in e) {
    const m = (e as { message?: unknown }).message;
    if (typeof m === 'string' && m) return m;
  }
  return fallback;
}
