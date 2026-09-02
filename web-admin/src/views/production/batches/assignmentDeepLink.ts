/**
 * 缺陷 51: 生产计划列表「分配工序操作员」深链到本页 —— 判据从
 * `plans/assignProcessOperatorEntry.ts` 的 `ASSIGN_OPERATOR_FOCUS_QUERY_KEY` 复用同一个 key,
 * 两边任一改名另一边必须跟着改, 否则深链悄悄失效(点了但没滚动/没高亮, 且不报错)。
 */
export const ASSIGN_OPERATOR_FOCUS_QUERY_KEY = 'focusAssignment';

/** `route.query` 里带上这个 key = 从计划列表深链而来，需要滚动定位并高亮工序明细卡片。 */
export function shouldFocusAssignmentSection(
  query: Record<string, unknown> | null | undefined,
): boolean {
  const value = query?.[ASSIGN_OPERATOR_FOCUS_QUERY_KEY];
  return value === '1' || (Array.isArray(value) && value.includes('1'));
}
