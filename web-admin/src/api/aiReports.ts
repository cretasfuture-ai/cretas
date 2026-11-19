import request, { get, post } from '@/api/request';

export type OperatingReportType = 'daily' | 'weekly' | 'monthly';
export interface AIReportSummary {
  id: number;
  reportType: string;
  periodStart?: string;
  periodEnd?: string;
  createdAt: string;
  summaryText?: string;
  financialSummaryText?: string;
  generationStatus?: 'SUCCESS' | 'FAILED';
  failureCode?: string;
  failureReason?: string;
}
export interface AIReportDetail {
  reportId: number;
  success: boolean;
  analysis: string;
  reportAnalysis?: string;
  generationStatus?: 'SUCCESS' | 'FAILED';
  failureCode?: string;
  errorMessage?: string;
  generatedAt?: string;
}
export const listAIReports = (factoryId: string, reportType?: string) =>
  get<{ reports: AIReportSummary[]; total: number }>(`/${encodeURIComponent(factoryId)}/ai/reports`, { params: { reportType } });
export const getAIReport = (factoryId: string, reportId: number) =>
  get<AIReportDetail>(`/${encodeURIComponent(factoryId)}/ai/reports/${reportId}`);
export const generateAIReport = (factoryId: string, reportType: OperatingReportType, startDate: string, endDate: string) =>
  post<AIReportDetail>(`/${encodeURIComponent(factoryId)}/ai/reports/generate`, { reportType, startDate, endDate });

export type ReportExportFormat = 'pdf' | 'pptx';
/** 导出报告文件; 服务端用与详情同一份按权限裁剪的正文生成, 返回 Blob 由调用方触发保存。 */
export async function exportAIReport(factoryId: string, reportId: number, format: ReportExportFormat): Promise<Blob> {
  const res = await request.get(`/${encodeURIComponent(factoryId)}/ai/reports/${reportId}/export`, {
    params: { format },
    responseType: 'blob',
    _keepResponse: true,
  } as object);
  const data = (res as unknown as { data: unknown }).data;
  if (!(data instanceof Blob)) throw new Error('导出返回的不是文件。');
  return data;
}
