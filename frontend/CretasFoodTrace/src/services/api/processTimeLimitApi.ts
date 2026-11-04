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

class ProcessTimeLimitApi {
  /** 这道工序可选来源的时限状态。 */
  async getTaskStatuses(taskId: number, factoryId?: string): Promise<ApiResponse<ProcessTimeLimitStatus[]>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<ProcessTimeLimitStatus[]>>(
      `/api/mobile/${fid}/process-time-limits/tasks/${taskId}`,
    );
  }
}

export const processTimeLimitApi = new ProcessTimeLimitApi();
