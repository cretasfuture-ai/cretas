import { pythonFetch } from './common';
export interface DataAlert {
  id: string; type: string; family: string; severity: string; title: string; message: string;
  owner: string; action: string; fields: string[]; evidence: Record<string, unknown>; blocksUnitCost: boolean;
  sourceRefs?: { sourceType: string; sourceId: string; rowNumber: number; recordId?: number; sourceRowIndex?: number }[];
  entityRefs?: { projectCode: string }[] | null;
}
export interface DatasetAlertReport {
  scope?: string | { factoryId: string; sourceType?: string; sourceId?: string };
  analysisStatus?: string;
  schema: string; runId: string; source?: { type: string; id: string; uploadId?: number };
  summary: { rows?: number; scannedRows?: number; projects?: number; alerts: number; truncated?: boolean; byType: Record<string, number>; byFamily: Record<string, number> };
  capabilities?: { quality: { status: string }; cost: { status: string; reason?: string; eligibleRows?: number; scannedRows?: number }; costMissingFields: string[] };
  items: DataAlert[];
  ai: { status: string; message?: string; model?: string; host?: string; analysisProfileVersion?: string; analysisSections?: import('./local-analysis').LocalAnalysisSection[]; reviews?: { alertId: string; check: string; priority: string; suggestion: string }[] };
}
interface Envelope { success: boolean; data: DatasetAlertReport; message: string }
export async function getDatasetAlerts(uploadId: number): Promise<DatasetAlertReport> {
  const value = await pythonFetch<Envelope>(`/api/smartbi/data-alerts/uploads/${uploadId}`, { preserveResponseKeys: true });
  if (!value.success) throw new Error(value.message);return value.data;
}
export async function reviewDatasetAlerts(uploadId: number): Promise<DatasetAlertReport> {
  const value = await pythonFetch<Envelope>(`/api/smartbi/data-alerts/uploads/${uploadId}/local-review`, { method: 'POST', timeoutMs: 170000, preserveResponseKeys: true });
  if (!value.success) throw new Error(value.message);return value.data;
}
