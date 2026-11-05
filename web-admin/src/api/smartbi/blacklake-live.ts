import { pythonFetch } from './common';

export interface StockReconciliationRow {
  key: string; productId: string; productCode: string; product: string; warehouseKey: string; warehouse: string;
  unit: string | null; quantity: number | null; checkedAt: string;
  status: 'confirmed' | 'needs_review' | 'resolved'; conclusion: string; possibleReasons: string[];
  owner: string; action: string; afterProvided: string; openingQuantity: number | null; openingAt: string | null;
  closingQuantity: number | null; closingAt: string; inbound: number | null; outbound: number | null;
  expectedClosing: number | null; difference: number | null; unitIssues: string[]; movementCoverage: string;
  movements: { id: string; orderNo: string; direction: 'in' | 'out'; bizAt: string | null; approval: string;
    quantity: number | null; unit: string | null; counted: boolean; reason: string }[];
}

export interface LiveMonitorReport {
  gaps?:{from:string;to:string}[];
  dailyStats?:{date:string;cycles:number;successful:number;changed:number;requests:number}[];
  enabled: boolean; status: string; startedAt: string; endsAt: string; intervalSeconds: number;
  lastAttemptAt: string | null; lastSuccessAt: string | null; nextRunAt: string | null; cycles: number;
  successfulCycles: number; changedCycles: number; error: string | null;
  analysis: { status: string; reportId?: string | null; lastCompletedAt?: string | null; message: string; tokens?: number; summary?: string; findings?: string[]; sectionInsights?: unknown[] };
  historyOffset?: number; historyHasMore?: boolean;
  history: { id: string; startedAt: string; finishedAt: string | null; status: string; requests: number;
    changed: boolean; counts: Record<string, number>; totals?: Record<string, number>; error: string | null; analysisReportId?: string | null }[];
}

export async function getBlacklakeLiveMonitor(historyOffset = 0): Promise<LiveMonitorReport> {
  const response = await pythonFetch<{ success: boolean; data: LiveMonitorReport; message: string }>(
    `/api/smartbi/blacklake/live-monitor${historyOffset > 0 ? `?history_offset=${historyOffset}` : ''}`, { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}
