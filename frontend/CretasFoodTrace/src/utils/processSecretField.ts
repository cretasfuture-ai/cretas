/**
 * 工艺保密 (V2) 的字段级隐藏标记 (与 web-admin/src/utils/processSecretField.ts 同一约定)。
 *
 * 后端对保密产品、且当前账号没有完整查看授权时, 把配比 / 转换率 / 标准出成率字段置 null,
 * 并在同一对象上带 `processSecret: { visibility, message, hiddenFields }`。
 * 页面据此显示「已保密」—— 不能把 null 当成「没填」或 0, 也不能让用户在这一行上编辑后把空值存回去。
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

export function isProcessSecretRow(row: unknown): boolean {
  return markerOf(row) !== null;
}

export function isProcessSecretHidden(row: unknown, field: string): boolean {
  const marker = markerOf(row);
  if (!marker) return false;
  return marker.hiddenFields ? marker.hiddenFields.includes(field) : true;
}
