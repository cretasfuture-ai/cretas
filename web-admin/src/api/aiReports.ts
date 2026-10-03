import { get, post } from '@/api/request';

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
