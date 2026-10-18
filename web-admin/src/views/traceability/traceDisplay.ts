import type { Component } from 'vue';
import dayjs from 'dayjs';
import { Box, Goods, Operation, QuestionFilled, Van } from '@element-plus/icons-vue';

/**
 * 溯源页与个体抽屉共用的**显示**映射 —— 文案与 el-tag 色型的唯一出处。
 *
 * 语义对齐手机端 `screens/traceability/components/dnaStatus.tsx` 与
 * `TraceabilityDetailScreen.tsx` 里的 QUALITY_STATUS_LABEL / qualityStatusTone。
 *
 * ⚠️ 未知状态码**原样显示那个码**、用中性色，⛔ 不翻译成「未知」之类看起来正常的值 ——
 * 把不认识的输入翻译成一个合法值，正是本仓踩过多次的那个坑。
 */

export type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info';

// ── DNA ────────────────────────────────────────────────────────────────────

export const DNA_STATUS_LABEL: Record<string, string> = {
  UNSAMPLED: '未采样',
  SAMPLED: '已采样',
  MATCHED: 'DNA 已比中',
  MISMATCH: 'DNA 不匹配',
};

/**
 * MATCHED 成功(绿) / SAMPLED 信息(蓝) / UNSAMPLED 中性(灰) / MISMATCH 错误(红)。
 * Element Plus 里「蓝」是 `primary`、「灰」是 `info` —— 手机端的 info/neutral 两档
 * 在这里落到这两个型上。认不出来的码走 `info`。
 */
export const DNA_STATUS_TAG: Record<string, TagType> = {
  MATCHED: 'success',
  SAMPLED: 'primary',
  UNSAMPLED: 'info',
  MISMATCH: 'danger',
};

export function dnaStatusLabel(status?: string | null): string | null {
  if (!status) return null;
  return DNA_STATUS_LABEL[status] ?? status;
}

export function dnaStatusTag(status?: string | null): TagType {
  if (!status) return 'info';
  return DNA_STATUS_TAG[status] ?? 'info';
}

// ── 质量状态 ────────────────────────────────────────────────────────────────

export const QUALITY_STATUS_LABEL: Record<string, string> = {
  PENDING: '待检验',
  PENDING_INSPECTION: '待质检',
  INSPECTING: '质检中',
  NEEDS_REVIEW: '待审核',
  PASSED: '已通过',
  PASS: '合格',
  QUALIFIED: '合格',
  FAILED: '未通过',
  FAIL: '不合格',
  CONDITIONAL: '有条件放行',
  PARTIAL_PASS: '部分通过',
  REWORK_REQUIRED: '需返工',
  REWORKING: '返工中',
  REWORK_COMPLETED: '返工完成',
  SCRAPPED: '已报废',
};

export function qualityStatusLabel(status?: string | null): string | null {
  if (status == null || status === '') return null;
  const key = status.toUpperCase();
  return Object.prototype.hasOwnProperty.call(QUALITY_STATUS_LABEL, key)
    ? QUALITY_STATUS_LABEL[key]
    : status;
}

/** 待检验类=warning、合格类=success、不合格类=danger、认不出来=info。 */
export function qualityStatusTag(status?: string | null): TagType {
  switch (status?.toUpperCase()) {
    case 'PASSED':
    case 'PASS':
    case 'QUALIFIED':
    case 'REWORK_COMPLETED':
      return 'success';
    case 'FAILED':
    case 'FAIL':
    case 'SCRAPPED':
      return 'danger';
    case 'PENDING':
    case 'PENDING_INSPECTION':
    case 'INSPECTING':
    case 'NEEDS_REVIEW':
    case 'CONDITIONAL':
    case 'PARTIAL_PASS':
    case 'REWORK_REQUIRED':
    case 'REWORKING':
      return 'warning';
    default:
      return 'info';
  }
}

// ── 出货状态 ────────────────────────────────────────────────────────────────

export function shipmentStatusLabel(status?: string | null): string {
  switch (status?.toLowerCase()) {
    case 'delivered':
      return '已送达';
    case 'shipped':
      return '已发货';
    case 'pending':
      return '待发货';
    case 'returned':
      return '已退货';
    default:
      // 认不出来就原样显示; 空的说「未知」是手机端既有口径, 这里沿用。
      return status || '未知';
  }
}

export function shipmentStatusTag(status?: string | null): TagType {
  switch (status?.toLowerCase()) {
    case 'delivered':
      return 'success';
    case 'shipped':
      return 'primary';
    case 'pending':
      return 'warning';
    case 'returned':
      return 'danger';
    default:
      return 'info';
  }
}

// ── 血缘节点 ────────────────────────────────────────────────────────────────

export const NODE_TYPE_LABEL: Record<string, string> = {
  MATERIAL_BATCH: '原料批次',
  PRODUCTION_BATCH: '生产批次',
  FINISHED_BATCH: '成品批次',
  SHIPMENT_RECORD: '出货单',
};

/** 原料 / 生产 / 成品 / 出货 四种节点的图标 —— 与侧栏「仓储=Box」「生产=Operation」同一族。 */
export const NODE_TYPE_ICON: Record<string, Component> = {
  MATERIAL_BATCH: Box,
  PRODUCTION_BATCH: Operation,
  FINISHED_BATCH: Goods,
  SHIPMENT_RECORD: Van,
};

export function nodeTypeLabel(nodeType: string): string {
  return NODE_TYPE_LABEL[nodeType] ?? nodeType;
}

export function nodeTypeIcon(nodeType: string): Component {
  return NODE_TYPE_ICON[nodeType] ?? QuestionFilled;
}

/** 既没有批次号、也不许回显内部主键时的诚实说法。⛔ 不拿 nodeId 当兜底。 */
export const UNNAMED_NODE_TEXT = '未命名批次';

/** 「直接上游」「上游第 2 层」…… */
export function depthLabel(depth: number): string {
  return depth === 1 ? '直接上游' : `上游第 ${depth} 层`;
}

// ── 空段折叠 ────────────────────────────────────────────────────────────────

export const EMPTY_SECTION_LABEL = {
  processingStages: '加工',
  qualityInspections: '质检',
  shipments: '出货',
} as const;

export type EmptySectionKey = keyof typeof EMPTY_SECTION_LABEL;

/** 三段里真正为空的那些合成一行灰字; 一段都不空时返回 null (那一行不出现)。 */
export function emptySectionsLine(empties: ReadonlyArray<EmptySectionKey>): string | null {
  if (empties.length === 0) return null;
  return `暂无${empties.map((k) => EMPTY_SECTION_LABEL[k]).join('/')}记录`;
}

// ── 时间 ────────────────────────────────────────────────────────────────────

/**
 * 后端 LocalDateTime(`2026-09-15T18:46:38`) → `2026-09-15 18:46`。溯源页「查询时间」「工序起止」
 * 与抽屉共用这一个出口。
 * ⛔ 不用 `toLocaleString()` —— 它跟浏览器语言走, 投屏机上实测出来的是 `9/15/2026, 6:46:38 PM`。
 * 解析不了的输入返回空串(调用方按「没有」处理, 那一行不渲染), 不抛、⛔ 不显示 `Invalid Date`。
 * ⚠️ 空值必须先挡: `dayjs(undefined)` 是「现在」, 不是无效。
 */
export function formatQueryTime(value?: string | null): string {
  if (value == null || `${value}`.trim() === '') return '';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : '';
}

/**
 * 「起 ~ 止」: 同一天只念一次日期 (`2026-09-10 08:00 ~ 08:40`), 跨天两头都带日期。
 * 起点解析不了整段为空; 止点解析不了只显示起点。
 */
export function formatTimeRange(start?: string | null, end?: string | null): string {
  const s = formatQueryTime(start);
  if (!s) return '';
  const e = formatQueryTime(end);
  if (!e) return s;
  const sameDay = s.slice(0, 10) === e.slice(0, 10);
  return `${s} ~ ${sameDay ? e.slice(11) : e}`;
}

// ── 小工具 ──────────────────────────────────────────────────────────────────

export function isBlank(value: unknown): boolean {
  return value == null || value === '' || value === '-';
}

/**
 * 「品种 · 来源场 · 性别」这类拼接行: 缺哪一项就不显示哪一项。
 * ⛔ 不填「未知」占位 —— 占位会让「没登记」长得和「就叫未知」一样。
 */
export function joinPresent(parts: Array<string | null | undefined>): string | null {
  const present = parts.filter((p): p is string => !!p && `${p}`.trim().length > 0);
  return present.length === 0 ? null : present.join(' · ');
}
