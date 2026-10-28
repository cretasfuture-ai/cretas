/**
 * 工艺保密 (V2) 的字段级隐藏标记。
 *
 * 后端对保密产品、且当前账号没有完整查看授权时, 把配比 / 单位用量 / 转换率 / 标准出成率字段置 null,
 * 并在同一对象上带 `processSecret: { visibility, message, hiddenFields }`。
 * 页面据此显示「已保密」—— ⛔ 不能把 null 当成「没填」或 0, 也不能让用户在这一行上编辑后把 null / 0 存回去。
 */

export interface ProcessSecretFieldMarker {
  visibility: 'TOTALS_ONLY' | 'RESTRICTED';
  message?: string;
  hiddenFields?: string[];
}

export const PROCESS_SECRET_LABEL = '已保密';

function markerOf(row: unknown): ProcessSecretFieldMarker | null {
  if (!row || typeof row !== 'object') return null;
  const marker = (row as { processSecret?: unknown }).processSecret;
  if (!marker || typeof marker !== 'object') return null;
  return marker as ProcessSecretFieldMarker;
}

/** 这一行是否带保密隐藏标记 (任一字段被隐藏)。 */
export function isProcessSecretRow(row: unknown): boolean {
  return markerOf(row) !== null;
}

/** 这个字段是否因工艺保密被隐藏。 */
export function isProcessSecretHidden(row: unknown, field: string): boolean {
  const marker = markerOf(row);
  if (!marker) return false;
  // V1 整块摘要没有 hiddenFields: 视为全部受限
  return marker.hiddenFields ? marker.hiddenFields.includes(field) : true;
}

/**
 * 金额 / 数值单元格: 被工艺保密隐藏时显示「已保密」, 否则按 render 格式化。
 * 保密产品的成本合计对无授权账号是 null, ⛔ 不能和「没价格权限 / 没采集」的「—」混成一个样子。
 */
export function secretAwareCell(row: unknown, field: string, render: (value: unknown) => string): string {
  if (isProcessSecretHidden(row, field)) return PROCESS_SECRET_LABEL;
  return render((row as Record<string, unknown> | null | undefined)?.[field]);
}

/**
 * 一组同义字段 (完整成本 / 已知合计) 里任一个被隐藏就算隐藏: 后端只隐藏非空字段, 成本没采集齐时完整成本本来就是
 * null, 只有已知合计会出现在 hiddenFields 里。
 */
export function isAnyProcessSecretHidden(row: unknown, fields: string[]): boolean {
  return fields.some((field) => isProcessSecretHidden(row, field));
}

/** 给 tooltip 用的说明; 没有标记时为空串。 */
export function processSecretMessage(row: unknown): string {
  return markerOf(row)?.message ?? '';
}
