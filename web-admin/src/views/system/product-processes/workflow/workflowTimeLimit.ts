/**
 * 投入端口时限配置的口径 (食品 DAG 缺口 14)。校验与后端 ProcessTimeLimitConfig 同一套规则:
 * 抽屉用它拦保存, validateWorkflow 用它拦发布 (料换了种类后起算点可能对不上)。
 */
import {
  PROCESS_TIME_START_LABELS,
  type ProcessTimeStartEvent,
  type RawStartEvent,
  type SemiStartEvent,
  type WorkflowPortTimeLimit,
} from '@/api/processTimeLimit';
import { formatDuration } from '@/utils/processTimeLimit';

const RAW_STARTS: RawStartEvent[] = ['RAW_RECEIVED', 'RAW_OUTBOUND', 'THAW_MARKED'];
const SEMI_STARTS: SemiStartEvent[] = ['OUTPUT_POSTED', 'OUTPUT_REPORTED', 'UPSTREAM_COMPLETED'];

export function startEventOptions(raw: boolean): Array<{ value: ProcessTimeStartEvent; label: string }> {
  return (raw ? RAW_STARTS : SEMI_STARTS).map((value) => ({ value, label: PROCESS_TIME_START_LABELS[value] }));
}

export const MIX_RULE_OPTIONS = [
  { value: 'EARLIEST', label: '整批按最早一笔' },
  { value: 'PER_LOT', label: '按笔分别算' },
] as const;
export const SPLIT_RULE_OPTIONS = [
  { value: 'INHERIT', label: '继承' },
  { value: 'RESTART_AT_SPLIT', label: '重新起算' },
] as const;
export const REWORK_RULE_OPTIONS = [
  { value: 'INHERIT_BATCH_START', label: '继承原批次开工' },
  { value: 'FROM_REWORK_OUTPUT', label: '从回料产出起算' },
] as const;

/** 抽屉里列出的一个投入。 */
export interface TimeLimitPortRow {
  portId: string;
  materialName: string;
  /** 原料投入: 起算点选项和上道半成品不一样。 */
  raw: boolean;
  limit: WorkflowPortTimeLimit | null;
}

/** 抽屉里一个投入的编辑态。数字可清空, 所以都允许 undefined。 */
export interface TimeLimitDraft {
  enabled: boolean;
  maxMinutes: number | undefined;
  warnMinutes: number | undefined;
  startEvent: ProcessTimeStartEvent;
  pauseAllowed: boolean;
  maxPauseMinutes: number | undefined;
  mixRule: NonNullable<WorkflowPortTimeLimit['mixRule']>;
  splitRule: NonNullable<WorkflowPortTimeLimit['splitRule']>;
  reworkRule: NonNullable<WorkflowPortTimeLimit['reworkRule']>;
  reworkMaxMinutes: number | undefined;
  recheckValidMinutes: number | undefined;
}

export function draftFromLimit(limit: WorkflowPortTimeLimit | null | undefined, raw: boolean): TimeLimitDraft {
  return {
    enabled: limit != null,
    maxMinutes: limit?.maxMinutes,
    warnMinutes: limit?.warnMinutes ?? undefined,
    startEvent: limit?.startEvent ?? (raw ? 'RAW_OUTBOUND' : 'OUTPUT_POSTED'),
    pauseAllowed: (limit?.pauseCodes?.length ?? 0) > 0,
    maxPauseMinutes: limit?.maxPauseMinutes ?? undefined,
    mixRule: limit?.mixRule ?? 'EARLIEST',
    splitRule: limit?.splitRule ?? 'INHERIT',
    reworkRule: limit?.reworkRule ?? 'INHERIT_BATCH_START',
    reworkMaxMinutes: limit?.reworkMaxMinutes ?? undefined,
    recheckValidMinutes: limit?.recheckValidMinutes ?? undefined,
  };
}

/** 关着 = null = 端口上不留这个键 (没有配置就是不限时)。 */
export function limitFromDraft(draft: TimeLimitDraft): WorkflowPortTimeLimit | null {
  if (!draft.enabled || draft.maxMinutes == null) return null;
  return {
    maxMinutes: draft.maxMinutes,
    warnMinutes: draft.warnMinutes ?? null,
    startEvent: draft.startEvent,
    pauseCodes: draft.pauseAllowed ? ['COLD_STORAGE'] : [],
    maxPauseMinutes: draft.pauseAllowed ? draft.maxPauseMinutes ?? null : null,
    mixRule: draft.mixRule,
    splitRule: draft.splitRule,
    reworkRule: draft.reworkRule,
    reworkMaxMinutes: draft.reworkMaxMinutes ?? null,
    recheckValidMinutes: draft.recheckValidMinutes ?? null,
  };
}

const isPositiveWhole = (value: unknown): value is number =>
  typeof value === 'number' && Number.isInteger(value) && value > 0;

/** 这份配置的问题; null = 没问题。 */
export function timeLimitProblem(limit: Partial<WorkflowPortTimeLimit>, raw: boolean): string | null {
  if (!isPositiveWhole(limit.maxMinutes)) return '请填时限（分钟），要大于 0 的整数';
  if (limit.warnMinutes != null) {
    if (!isPositiveWhole(limit.warnMinutes)) return '临近提醒要大于 0 的整数分钟';
    if (limit.warnMinutes >= limit.maxMinutes) return '临近提醒要小于时限';
  }
  const starts: ProcessTimeStartEvent[] = raw ? RAW_STARTS : SEMI_STARTS;
  if (!limit.startEvent || !starts.includes(limit.startEvent)) {
    return `起算点要选${raw ? '原料' : '上道工序'}的：${startEventOptions(raw).map((o) => o.label).join(' / ')}`;
  }
  const optional: Array<[number | null | undefined, string]> = [
    [limit.maxPauseMinutes, '最多暂停'],
    [limit.reworkMaxMinutes, '回料时限'],
    [limit.recheckValidMinutes, '复检放行有效期'],
  ];
  const bad = optional.find(([value]) => value != null && !isPositiveWhole(value));
  return bad ? `${bad[1]}要大于 0 的整数分钟` : null;
}

const TIME_LIMIT_KEYS = new Set([
  'maxMinutes', 'warnMinutes', 'startEvent', 'pauseCodes', 'maxPauseMinutes',
  'mixRule', 'splitRule', 'reworkRule', 'reworkMaxMinutes', 'recheckValidMinutes',
]);

/** 端口上的 timeLimit 形状对不对 (AI 补丁校验用; 取值是否合规由 {@link timeLimitProblem} 在发布时管)。 */
export function isTimeLimitShape(value: unknown): boolean {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  return Object.keys(value).every((key) => TIME_LIMIT_KEYS.has(key));
}

export function timeLimitBadge(limit: WorkflowPortTimeLimit): string {
  return `限时 ${formatDuration(limit.maxMinutes)}`;
}
