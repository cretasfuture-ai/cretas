import type { PeriodSelection } from '@/components/smartbi/PeriodSelector.vue';
import { periodSelectionToRequest, type PeriodRequest } from './financialDashboardState';
import type { FactoryChartSource, FactoryRecommendationRequest } from '@/api/smartbi/factory-charts';

export interface FactoryChartSelectionQuery {
  factoryId: string;
  source: FactoryChartSource;
  uploadId: number | null;
  period: PeriodSelection;
  includeOptional: boolean;
  useAi: boolean;
}
export type FactoryChartQueryResult = FactoryRecommendationRequest | { error: string };
export const DEFAULT_VISIBLE_FACTORY_CHARTS = 6;

export function factoryChartStorageKey(factoryId: string, source: FactoryChartSource): string {
  return `smartbi.factory-chart-selection:${factoryId}:${source}`;
}

export function loadFactoryChartSelection(
  factoryId: string,
  source: FactoryChartSource,
  eligibleIds: readonly string[],
  defaultVisible = DEFAULT_VISIBLE_FACTORY_CHARTS,
): string[] {
  if (!factoryId) return [];
  try {
    const raw = localStorage.getItem(factoryChartStorageKey(factoryId, source));
    const stored = raw ? JSON.parse(raw) : null;
    if (!Array.isArray(stored)) return [...eligibleIds].slice(0, defaultVisible);
    const eligible = new Set(eligibleIds);
    const selected = stored.filter((id): id is string => typeof id === 'string' && eligible.has(id));
    return stored.length > 0 && selected.length === 0 ? [...eligibleIds].slice(0, defaultVisible) : selected;
  } catch { return [...eligibleIds].slice(0, defaultVisible); }
}

export function saveFactoryChartSelection(factoryId: string, source: FactoryChartSource, selectedIds: readonly string[]): void {
  if (!factoryId) return;
  try { localStorage.setItem(factoryChartStorageKey(factoryId, source), JSON.stringify([...selectedIds])); } catch { /* storage is optional */ }
}

export function eligibleFactoryChartIds(ids: readonly string[], selected: readonly string[]): string[] {
  const allowed = new Set(ids);
  return selected.filter(id => allowed.has(id));
}

export function factoryChartRequestFromSelection(selection: FactoryChartSelectionQuery): FactoryChartQueryResult {
  if (!selection.factoryId) return { error: '当前账号没有所属工厂，无法加载工厂图表' };
  if (selection.source === 'upload' && !selection.uploadId) return { error: '请选择一份已上传的报表' };
  const period = periodSelectionToRequest(selection.period);
  if ('error' in period) return period;
  return {
    source: selection.source,
    ...(selection.source === 'upload' && selection.uploadId ? { upload_id: selection.uploadId } : {}),
    factory_id: selection.factoryId,
    year: period.year,
    period_type: period.periodType,
    start_month: period.startMonth,
    end_month: period.endMonth,
    include_optional: selection.includeOptional,
    use_ai: selection.useAi,
  };
}

export function factoryChartPeriodLabel(period: PeriodRequest): string {
  if (period.periodType === 'all') return '全部历史';
  if (period.periodType === 'year') return `${period.year}年`;
  return period.startMonth === period.endMonth ? `${period.year}年${period.startMonth}月` : `${period.year}年${period.startMonth}–${period.endMonth}月`;
}
