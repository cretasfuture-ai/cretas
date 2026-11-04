/**
 * 工艺参数偏离汇总 API (只读)。
 *
 * Backend: ProcessParamDeviationController @ /api/mobile/{factoryId}/quality/param-deviation
 *   GET /summary   按「工序 + 参数」汇总: 填了该参数的报工次数 (分母) / 偏离次数 / 偏离率; 不受 status 影响
 *   GET /details   偏离明细 (分页, 新的在前); status = LOW 偏低 / HIGH 偏高
 *
 * 时间范围 (报工日期, 含两端) 不传时后端取最近 30 天, 最大跨度 92 天; 实际生效的范围在 window 里回显。
 * 数据是报工当时落库的判定快照, 不是拿现在的标准重判。
 */
import { get } from './request';

export type ParamDeviationStatus = 'LOW' | 'HIGH';

export interface ParamDeviationQuery {
  startDate?: string;
  endDate?: string;
  processName?: string;
  productTypeId?: string;
  batchNumber?: string;
}

export interface ParamDeviationDetailQuery extends ParamDeviationQuery {
  status?: ParamDeviationStatus;
  page?: number;
  size?: number;
}

export interface ParamDeviationWindow {
  startDate: string;
  endDate: string;
}

export interface ParamDeviationSummaryRow {
  /** 取不到工序名时为 null, 页面显示「—」 */
  processName: string | null;
  paramKey: string;
  paramLabel: string | null;
  unit: string | null;
  /** 填了该参数的报工次数 (分母) */
  reportCount: number;
  deviationCount: number;
  highCount: number;
  lowCount: number;
  /** 偏离率 (%), 1 位小数 */
  deviationRate: number;
}

export interface ParamDeviationSummary {
  window: ParamDeviationWindow;
  /** 窗口内填了参数、有判定快照的总条数; 0 = 这段时间没有任何可判定的参数数据 */
  totalChecked: number;
  totalDeviations: number;
  rows: ParamDeviationSummaryRow[];
}

export interface ParamDeviationDetail {
  reportId: number;
  reportDate: string | null;
  reportedAt: string | null;
  batchId: number | null;
  batchNumber: string | null;
  productTypeId: string | null;
  productName: string | null;
  processName: string | null;
  paramKey: string;
  paramLabel: string | null;
  measuredValue: number | null;
  unit: string | null;
  standardValue: number | null;
  rangeMin: number | null;
  rangeMax: number | null;
  status: ParamDeviationStatus;
  reporterId: number | null;
  reporterName: string | null;
}

export interface ParamDeviationPage {
  content: ParamDeviationDetail[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const BASE = (factoryId: string) => `/${factoryId}/quality/param-deviation`;

/** 去掉空值, 空串不发给后端 (后端对空串也当没传, 这里是少发无用参数) */
function clean<T extends object>(params: T): Partial<T> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') out[k] = v;
  }
  return out as Partial<T>;
}

export function getParamDeviationSummary(factoryId: string, query: ParamDeviationQuery) {
  return get<ParamDeviationSummary>(`${BASE(factoryId)}/summary`, { params: clean(query) });
}

export function getParamDeviationDetails(factoryId: string, query: ParamDeviationDetailQuery) {
  return get<ParamDeviationPage>(`${BASE(factoryId)}/details`, { params: clean(query) });
}
