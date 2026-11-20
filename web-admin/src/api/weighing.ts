/**
 * 迁移 P5 称重报工 / 数据疑点。
 *
 * Backend:
 *   ProductionDataSuspectController @ /api/mobile/{factoryId}/production
 *     GET /data-suspects?days=14                       最近 N 天称重报工里的数据疑点（只读，不改原值、不进异常）
 *     GET /work-process-tasks/{taskId}/weighings      一道工序任务的逐笔称重（含皮重，每笔带疑点）
 *   YieldReportController @ /api/mobile/{factoryId}/production/batches/{batchId}
 *     POST /reports                                   写入：复用已有逐道报工；P5 加了 tareWeight，并在服务端校验 R36 / 皮重
 */
import { get, post } from './request';

export interface WeighingEntry {
  reportId: number;
  batchId: number | null;
  workProcessTaskId: number | null;
  processOrder: number | null;
  reportKind: string | null;
  reportDate: string | null;
  createdAt: string | null;
  inputQuantity: number | null;
  inputUnit: string | null;
  /** 良品净重（= 毛重 − 皮重） */
  outputQuantity: number | null;
  outputUnit: string | null;
  tareWeight: number | null;
  defectQuantity: number | null;
  laborSegments: Array<{ startTime?: string; endTime?: string }> | null;
  /** 数据疑点原因；空 = 没有疑点 */
  suspects: string[];
  whoToCheck: string | null;
}

export interface SuspectList {
  windowDays: number;
  from: string;
  to: string;
  scanned: number;
  truncated: boolean;
  items: WeighingEntry[];
}

export interface WeighingSubmitPayload {
  workProcessTaskId: number;
  clientRequestId: string;
  inputQuantity: number;
  inputUnit: string;
  outputQuantity: number;
  outputUnit: string;
  tareWeight: number | null;
  defectQuantity: number | null;
  laborSegments: Array<{ startTime: string; endTime: string }>;
  businessDate: string;
  /** 一项任务可多次报：每笔只累加，不把任务置完工 */
  markComplete: false;
}

export function listTaskWeighings(factoryId: string, taskId: number) {
  return get<WeighingEntry[]>(`/${factoryId}/production/work-process-tasks/${taskId}/weighings`);
}

export function listDataSuspects(factoryId: string, days = 14, config?: Record<string, unknown>) {
  return get<SuspectList>(`/${factoryId}/production/data-suspects`, { ...(config ?? {}), params: { days } });
}

export function submitWeighing(factoryId: string, batchId: number, payload: WeighingSubmitPayload) {
  return post<{ reportId?: number; idempotentReplay?: boolean }>(
    `/${factoryId}/production/batches/${batchId}/reports`, payload, { _silent: true },
  );
}
