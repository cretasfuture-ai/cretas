/**
 * 工序间与投料时限 API (食品 DAG 缺口 14)。
 *
 * Backend: ProcessTimeLimitController @ /api/mobile/{factoryId}/process-time-limits
 *   GET  /plans/{planId}?processOrder=  这道工序可选来源的时限状态 (逐道录入)
 *   POST /marks                         登记开始解冻 / 开始冷藏 (时刻取服务端当前时间)
 *   POST /marks/{id}/end                结束冷藏
 *   GET  /events                        超时待处置清单
 *   GET  /events/records                处置记录 (本厂最近 200 条)
 *   POST /events/{id}/decision          复检放行 / 判不合格 (结论必填)
 */
import { get, post } from './request';

export type ProcessTimeLimitState =
  | 'NOT_STARTED'
  | 'START_MISSING'
  | 'OK'
  | 'WARNING'
  | 'EXPIRED'
  | 'RELEASED';

export type RawStartEvent = 'RAW_RECEIVED' | 'RAW_OUTBOUND' | 'THAW_MARKED';
export type SemiStartEvent = 'OUTPUT_POSTED' | 'OUTPUT_REPORTED' | 'UPSTREAM_COMPLETED';
export type ProcessTimeStartEvent = RawStartEvent | SemiStartEvent;

export const PROCESS_TIME_START_LABELS: Record<ProcessTimeStartEvent, string> = {
  RAW_RECEIVED: '原料入库',
  RAW_OUTBOUND: '领到生产仓',
  THAW_MARKED: '登记解冻',
  OUTPUT_POSTED: '上道产出入账',
  OUTPUT_REPORTED: '上道报工',
  UPSTREAM_COMPLETED: '上道完工',
};

/** 投入端口上的时限配置 (`data.ports[i].timeLimit`), 字段与后端 ProcessTimeLimitConfig 一一对应。没有这份配置 = 不限时。 */
export interface WorkflowPortTimeLimit {
  maxMinutes: number;
  warnMinutes?: number | null;
  startEvent: ProcessTimeStartEvent;
  pauseCodes?: 'COLD_STORAGE'[] | null;
  maxPauseMinutes?: number | null;
  mixRule?: 'EARLIEST' | 'PER_LOT' | null;
  splitRule?: 'INHERIT' | 'RESTART_AT_SPLIT' | null;
  reworkRule?: 'INHERIT_BATCH_START' | 'FROM_REWORK_OUTPUT' | null;
  reworkMaxMinutes?: number | null;
  recheckValidMinutes?: number | null;
}

/** 一条来源当前的时限状态 (mirror ProcessTimeLimitService.SourceStatus)。 */
export interface ProcessTimeLimitStatus {
  targetType: string;
  targetId: string;
  batchNumber: string | null;
  workflowPortId: string | null;
  processName: string | null;
  limitMinutes: number;
  startEvent: ProcessTimeStartEvent;
  state: ProcessTimeLimitState;
  startAt: string | null;
  deadlineAt: string | null;
  /** 还剩多少分钟; 已超时为负; 未起算为 null。 */
  remainingMinutes: number | null;
  reason: string | null;
  balance: number | null;
  expiredQuantity: number | null;
  drawableQuantity: number | null;
  unit: string | null;
  paused: boolean;
  perLot: boolean;
  /** 进行中的暂停登记 id。 */
  pauseMarkId?: number | null;
}

export interface ProcessTimeMark {
  id: number;
  targetType: string;
  targetId: string;
  markKind: 'START' | 'PAUSE';
  markCode: string;
  startedAt: string;
  endedAt: string | null;
}

export type ProcessTimeLimitDecision = 'RELEASED' | 'REJECTED';

export interface ProcessTimeLimitEvent {
  id: number;
  targetType: string;
  targetId: string;
  batchNumber: string | null;
  processName: string | null;
  limitMinutes: number;
  startEvent: ProcessTimeStartEvent;
  mixRule: string;
  startAt: string;
  deadlineAt: string;
  blockedAt: string;
  expiredQuantity: number | null;
  unit: string | null;
  recheckValidMinutes: number | null;
  status: 'OPEN' | ProcessTimeLimitDecision;
  conclusion: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  validUntil: string | null;
}

const base = (factoryId: string) => `/${factoryId}/process-time-limits`;

/** `_silent`: 读失败由面板就地说明, 不再弹常驻提示 (每道工序各读一次, 弹出来会叠一屏)。 */
export const getPlanTimeLimits = (factoryId: string, planId: string, processOrder: number) =>
  get<ProcessTimeLimitStatus[]>(`${base(factoryId)}/plans/${planId}`, { params: { processOrder }, _silent: true });

export const startProcessTimeMark = (
  factoryId: string,
  body: {
    targetType: string;
    targetId: string;
    kind: 'START' | 'PAUSE';
    code: 'THAW' | 'COLD_STORAGE';
    /** 纪律排查 A13: 只对开始解冻有效 —— 补登实际开始时间 (本地时间 YYYY-MM-DDTHH:mm:ss); 不传 = 现在。 */
    startedAt?: string;
    /** 补登原因 (startedAt 早于现在时必填)。 */
    backfillReason?: string;
  },
) => post<ProcessTimeMark>(`${base(factoryId)}/marks`, body);

export const endProcessTimeMark = (factoryId: string, markId: number) =>
  post<ProcessTimeMark>(`${base(factoryId)}/marks/${markId}/end`);

export const listProcessTimeEvents = (factoryId: string) =>
  get<ProcessTimeLimitEvent[]>(`${base(factoryId)}/events`);

export const listProcessTimeEventRecords = (factoryId: string) =>
  get<ProcessTimeLimitEvent[]>(`${base(factoryId)}/events/records`);

export const decideProcessTimeEvent = (
  factoryId: string,
  eventId: number,
  body: { decision: ProcessTimeLimitDecision; conclusion: string },
) => post<ProcessTimeLimitEvent>(`${base(factoryId)}/events/${eventId}/decision`, body);
