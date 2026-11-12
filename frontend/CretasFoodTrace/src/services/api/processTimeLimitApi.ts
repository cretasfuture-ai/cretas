import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';
import type { ApiResponse } from './yieldReportApi';

export type ProcessTimeLimitState =
  | 'NOT_STARTED'
  | 'START_MISSING'
  | 'OK'
  | 'WARNING'
  | 'EXPIRED'
  | 'RELEASED';

/** 一条来源当前的时限状态 (mirror ProcessTimeLimitService.SourceStatus, 食品 DAG 缺口 14)。 */
export interface ProcessTimeLimitStatus {
  targetType: string;
  targetId: string;
  batchNumber: string | null;
  workflowPortId: string | null;
  processName: string | null;
  limitMinutes: number;
  startEvent: string;
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

export type ProcessTimeLimitDecision = 'RELEASED' | 'REJECTED';

/** 起算事件中文 (与网页 api/processTimeLimit.ts PROCESS_TIME_START_LABELS 同一份)。 */
export const PROCESS_TIME_START_LABELS: Record<string, string> = {
  RAW_RECEIVED: '原料入库',
  RAW_OUTBOUND: '领到生产仓',
  THAW_MARKED: '登记解冻',
  OUTPUT_POSTED: '上道产出入账',
  OUTPUT_REPORTED: '上道报工',
  UPSTREAM_COMPLETED: '上道完工',
};

/** 一条超时事件 (mirror 后端 ProcessTimeLimitEvent; 网页「超时处置」同一份)。 */
export interface ProcessTimeLimitEvent {
  id: number;
  targetType: string;
  targetId: string;
  batchNumber: string | null;
  processName: string | null;
  limitMinutes: number;
  startEvent: string;
  mixRule?: string | null;
  startAt: string | null;
  deadlineAt: string | null;
  blockedAt?: string | null;
  expiredQuantity: number | null;
  unit: string | null;
  recheckValidMinutes: number | null;
  status: 'OPEN' | ProcessTimeLimitDecision;
  conclusion: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  validUntil: string | null;
}

class ProcessTimeLimitApi {
  /** 这道工序可选来源的时限状态。 */
  async getTaskStatuses(taskId: number, factoryId?: string): Promise<ApiResponse<ProcessTimeLimitStatus[]>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<ProcessTimeLimitStatus[]>>(
      `/api/mobile/${fid}/process-time-limits/tasks/${taskId}`,
    );
  }

  /** 超时待处置清单 (读: quality / production 任一可读)。 */
  async listOpenEvents(factoryId?: string): Promise<ApiResponse<ProcessTimeLimitEvent[]>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<ProcessTimeLimitEvent[]>>(`/api/mobile/${fid}/process-time-limits/events`);
  }

  /** 处置记录 (本厂最近 200 条, 含仍 OPEN 的)。 */
  async listEventRecords(factoryId?: string): Promise<ApiResponse<ProcessTimeLimitEvent[]>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<ProcessTimeLimitEvent[]>>(`/api/mobile/${fid}/process-time-limits/events/records`);
  }

  /** 复检放行 / 判不合格, 结论必填 (后端 @RequirePermission quality:read_write)。 */
  async decide(
    eventId: number,
    body: { decision: ProcessTimeLimitDecision; conclusion: string },
    factoryId?: string,
  ): Promise<ApiResponse<ProcessTimeLimitEvent>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.post<ApiResponse<ProcessTimeLimitEvent>>(
      `/api/mobile/${fid}/process-time-limits/events/${eventId}/decision`,
      body,
    );
  }
}

export const processTimeLimitApi = new ProcessTimeLimitApi();
