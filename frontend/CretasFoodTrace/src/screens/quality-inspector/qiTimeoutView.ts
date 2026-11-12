/**
 * 超时处置 (食品 DAG 缺口 14) 的显示口径 —— 与网页 ProcessTimeoutEventsTab.vue 同一套文字。
 * 能不能领、放行多久有效全部由后端算; 这里只把后端字段写成人话。
 */
import type { ProcessTimeLimitDecision, ProcessTimeLimitEvent } from '../../services/api/processTimeLimitApi';
import { PROCESS_TIME_START_LABELS } from '../../services/api/processTimeLimitApi';
import { displayUnit } from '../../utils/packagingUnitConversion';
import { formatDuration, parseBackendTime } from '../../utils/processTimeLimit';

/** 与网页同一份常用结论 (fool-proof Rule 3: 一点即填, 仍可改写)。 */
export const RELEASE_PRESETS = ['复检感官合格', '中心温度复测合格', '微生物复检合格'];
export const REJECT_PRESETS = ['感官异常（异味/变色）', '中心温度不达标', '微生物超标', '超时过久，不再使用'];

export function presetsFor(decision: ProcessTimeLimitDecision | null): string[] {
  if (decision === 'REJECTED') return REJECT_PRESETS;
  if (decision === 'RELEASED') return RELEASE_PRESETS;
  return [];
}

/** 追加一条常用结论; 已经写过的不重复追加。 */
export function appendPreset(current: string, preset: string): string {
  const cur = current.trim();
  if (cur.includes(preset)) return cur;
  return cur ? `${cur}；${preset}` : preset;
}

export function formatBackendTime(value: string | null | undefined): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—';
}

export function timeoutQuantity(item: Pick<ProcessTimeLimitEvent, 'expiredQuantity' | 'unit'>): string {
  if (item.expiredQuantity == null) return '—';
  return `${Number(item.expiredQuantity)} ${displayUnit(item.unit)}`.trim();
}

/** 已超时多久 (按读取时刻算); 截止时间缺失写「—」, 不猜。 */
export function overdueText(item: Pick<ProcessTimeLimitEvent, 'deadlineAt'>, nowMs: number): string {
  const deadline = parseBackendTime(item.deadlineAt);
  if (deadline == null) return '—';
  return formatDuration(Math.max(0, Math.ceil((nowMs - deadline) / 60000)));
}

export function startText(item: Pick<ProcessTimeLimitEvent, 'startAt' | 'startEvent'>): string {
  return `${formatBackendTime(item.startAt)}（${PROCESS_TIME_START_LABELS[item.startEvent] ?? item.startEvent}）`;
}

/** 选中一个处置结果之后, 立刻告诉他后果 (fool-proof Rule 1)。 */
export function decisionConsequence(
  item: Pick<ProcessTimeLimitEvent, 'recheckValidMinutes' | 'targetType' | 'expiredQuantity' | 'unit'>,
  decision: ProcessTimeLimitDecision | null,
): string {
  if (!decision) return '';
  if (decision === 'RELEASED') {
    return item.recheckValidMinutes == null
      ? '放行后这批料不再限时'
      : `放行后 ${formatDuration(item.recheckValidMinutes)} 内可以领用，过了还要再处置`;
  }
  return item.targetType === 'SEMI_FINISHED'
    ? `判不合格后，超时的 ${timeoutQuantity(item)} 从库存转出，不能再领用`
    : '判不合格后，整批标为不合格，不能再领用';
}

/** 提交按钮为什么是灰的 (null = 可以提交)。 */
export function submitBlockedReason(decision: ProcessTimeLimitDecision | null, conclusion: string): string | null {
  if (!decision) return '请先选「复检放行」或「判不合格」';
  if (!conclusion.trim()) return '请填写复检结论';
  return null;
}

export function decisionLabel(decision: ProcessTimeLimitDecision | 'OPEN' | string): string {
  if (decision === 'RELEASED') return '复检放行';
  if (decision === 'REJECTED') return '判不合格';
  return '待处置';
}
