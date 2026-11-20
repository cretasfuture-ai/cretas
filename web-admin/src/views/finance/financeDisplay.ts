/**
 * 财务页共用的展示小工具: 备注清洗、徽标色、金额遮蔽。
 * 状态/类型中文一律走 utils/enumDisplay.ts, 这里不另写一套。
 */
import type { CsBadgeColor } from '@/utils/csBadge';

/**
 * 备注里系统写入的内部键值 (`SALES_ORDER_ID=<uuid>;`、`RECALL_EVENT_ID=2`) 不给用户看:
 * 只留用户自己写的部分; 没有备注或整条都是内部键值时显示「—」。
 * 判据: 键名以 _ID / _NO 结尾的大写键, 或任意大写键配 uuid 值。
 */
const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const INTERNAL_KV = new RegExp(
  `\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*_(?:ID|NO)=[^;；\\s]*[;；]?|\\b[A-Z][A-Z0-9_]*=${UUID}[;；]?`,
  'g',
);
export function cleanFinanceRemark(raw: unknown): string {
  const text = String(raw ?? '');
  if (!text.trim()) return '—';
  const rest = text.replace(INTERNAL_KV, '').replace(/^[\s;；,，]+|[\s;；,，]+$/g, '');
  return rest || '—';
}

/** 通用状态码 → 徽标色: 完成/通过/已核实=绿, 待处理=橙, 驳回=红, 其余=灰。 */
const SUCCESS = new Set(['ISSUED', 'VERIFIED', 'APPROVED', 'COMPLETED', 'POSTED', 'CONFIRMED']);
const WARNING = new Set(['REQUESTED', 'PENDING', 'DRAFT']);
const DANGER = new Set(['REJECTED', 'VOID']);
export function financeStatusColor(code: unknown): CsBadgeColor {
  const c = String(code ?? '');
  if (SUCCESS.has(c)) return 'success';
  if (WARNING.has(c)) return 'warning';
  if (DANGER.has(c)) return 'error';
  return 'neutral';
}

/** 金额遮蔽: null/undefined = 无权限或后端未给 → 「—」, 0 仍显示 0.00。 */
export function maskedAmount(v: number | null | undefined, digits = 2): string {
  if (v === null || v === undefined) return '—';
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(digits) : '—';
}
