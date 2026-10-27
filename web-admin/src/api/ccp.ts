/**
 * CCP 监控 API (缺口 13)。
 *
 * Backend: CcpMonitoringController @ /api/mobile/{factoryId}/ccp
 *   GET  /step-checkpoints?workProcessId=   本工序要录的 CCP (含限值)
 *   POST /records                           录入本次报工的实测值 (超限自动偏离 + 扣待检)
 *   GET  /records?batchNumber=              批次 CCP 记录 (批号 ∪ 同一生产计划)
 *   GET  /records/export?batchNumber=       导出 CSV
 *   GET  /deviations/open                   本厂未处理偏离
 *   POST /records/{id}/handle               处理偏离 (纠偏措施必填)
 */
import request, { get, post } from './request';

export interface CcpStepCheckpoint {
  checkpointId: number;
  checkpointCode: string;
  name: string;
  hazardType: string;
  criticalLimitMin: number;
  criticalLimitMax: number;
  unit: string;
  monitoringProcedure: string | null;
  correctiveAction: string | null;
}

export type CcpRecordResult = 'OK' | 'DEVIATION_OPEN' | 'DEVIATION_HANDLED';

export interface CcpRecordView {
  id: number;
  checkpointId: number;
  checkpointCode: string | null;
  checkpointName: string | null;
  batchNumber: string;
  processOrder: number | null;
  workProcessId: string | null;
  monitoringTime: string | null;
  measuredValue: number;
  criticalLimitMin: number | null;
  criticalLimitMax: number | null;
  unit: string | null;
  result: CcpRecordResult;
  operatorName: string | null;
  operatorUserId: number;
  source: string | null;
  notes: string | null;
  deviationAction: string | null;
  deviationHandledAt: string | null;
  deviationHandledByName: string | null;
  correctiveActionGuide: string | null;
}

export interface CcpReading {
  checkpointId: number;
  measuredValue: number;
  notes?: string | null;
}

export interface CcpRecordCommand {
  batchNumber: string | null;
  productionBatchId: number | null;
  planId: string | null;
  workProcessId: string;
  processOrder: number | null;
  source: 'APP_STEP_REPORT' | 'WEB_PROCESS_SHEET';
  readings: CcpReading[];
}

export interface CcpRecordResponse {
  records: CcpRecordView[];
  deviationCount: number;
  heldOutputs: number;
  message: string;
}

const base = (factoryId: string) => `/${factoryId}/ccp`;

export const listStepCheckpoints = (factoryId: string, workProcessId: string) =>
  get<CcpStepCheckpoint[]>(`${base(factoryId)}/step-checkpoints`, { params: { workProcessId } });

export const recordCcp = (factoryId: string, body: CcpRecordCommand) =>
  post<CcpRecordResponse>(`${base(factoryId)}/records`, body);

export const listCcpRecordsForBatch = (factoryId: string, batchNumber: string) =>
  get<CcpRecordView[]>(`${base(factoryId)}/records`, { params: { batchNumber } });

export const listOpenDeviations = (factoryId: string) =>
  get<CcpRecordView[]>(`${base(factoryId)}/deviations/open`);

export const handleCcpDeviation = (factoryId: string, recordId: number, action: string) =>
  post<CcpRecordView>(`${base(factoryId)}/records/${recordId}/handle`, { action });

/** 导出批次 CCP 记录 (CSV)。返回 Blob, 由调用方触发下载。 */
export async function exportCcpRecords(factoryId: string, batchNumber: string): Promise<Blob> {
  // _keepResponse: 绕过拦截器的 {success,data} 解包, 拿原始 blob (见 request.ts)
  const res = await request.get(`${base(factoryId)}/records/export`, {
    params: { batchNumber },
    responseType: 'blob',
    _keepResponse: true,
  } as object);
  const data = (res as unknown as { data: unknown }).data;
  return data instanceof Blob ? data : new Blob([data as BlobPart], { type: 'text/csv;charset=utf-8' });
}

/** 实测值是否超出临界限值 (与后端 CcpMonitoringService.isOutOfLimit 同口径: 闭区间)。 */
export function isOutOfLimit(value: number | null | undefined, min: number | null, max: number | null): boolean {
  if (value == null || Number.isNaN(value)) return false;
  return (min != null && value < Number(min)) || (max != null && value > Number(max));
}

export function formatLimit(cp: Pick<CcpStepCheckpoint, 'criticalLimitMin' | 'criticalLimitMax' | 'unit'>): string {
  return `${Number(cp.criticalLimitMin)} ~ ${Number(cp.criticalLimitMax)} ${cp.unit}`;
}
