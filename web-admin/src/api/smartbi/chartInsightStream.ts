import { getPythonAuthHeaders } from './common';
import { parseSseFrame, splitSseFrames } from './sse';
import {
  computeDataPattern,
  type ChartWithMeta,
  type InsightResult,
  type UserPermissions,
} from '@/views/smart-bi/components/chartInsight';

type ChartInsightAnswer = InsightResult & { cacheHit?: boolean };
type ChartInsightOutcome = ChartInsightAnswer | { reason: string };

interface ChartInsightEnvelope {
  success: boolean;
  data: ChartInsightAnswer | null;
  code?: string;
}

function outcome(payload: ChartInsightEnvelope): ChartInsightOutcome {
  return payload.success && payload.data
    ? payload.data
    : { reason: payload.code ?? 'RESULT_UNAVAILABLE' };
}

/** Blacklake line and bar charts have one numeric series aligned with the category axis. */
export async function fetchChartInsightStream(
  chart: ChartWithMeta,
  perms: UserPermissions,
  factoryId: string,
  dataVersion: string,
  onPreview: (result: InsightResult) => void,
  signal: AbortSignal,
): Promise<ChartInsightOutcome> {
  const meta = chart.meta;
  const chartType = chart.chartType?.toUpperCase();
  const labels = chart.config?.xAxis?.data;
  const values = chart.config?.series?.[0]?.data;
  if (!meta || meta.domain !== 'factory' || (chartType !== 'LINE' && chartType !== 'BAR')
      || !labels || !values || labels.length !== values.length
      || !values.every((value): value is number => typeof value === 'number' && Number.isFinite(value))) {
    return { reason: 'INSUFFICIENT_DATA' };
  }

  const authHeaders = await getPythonAuthHeaders();
  const url = import.meta.env.VITE_SMARTBI_URL ?? '/smartbi-api';
  const response = await fetch(`${url}/api/smartbi/chart-insight`, {
    method: 'POST',
    credentials: 'include',
    headers: { ...authHeaders, Accept: 'text/event-stream' },
    signal,
    body: JSON.stringify({
      chart_type: chartType,
      x_dim: meta.xDim,
      y_metric: meta.yMetric,
      aggregation: meta.aggregation,
      domain: meta.domain,
      data_pattern: computeDataPattern(meta, values, labels, chart.chartType),
      permission_tier: perms.canViewFinance ? 'finance_visible' : 'finance_hidden',
      factory_id: factoryId,
      data_version: dataVersion,
      series_values: values,
      series_labels: labels,
    }),
  });
  if (!response.ok) {
    if (response.status === 401) return { reason: 'UNAUTHORIZED' };
    if (response.status === 403) return { reason: 'PERMISSION_DENIED' };
    return { reason: 'SERVICE_UNAVAILABLE' };
  }
  // The previous backend version returns JSON from the same route.
  if (!response.headers?.get('content-type')?.includes('text/event-stream')) {
    return outcome(await response.json());
  }
  if (!response.body) return { reason: 'SERVICE_UNAVAILABLE' };

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return { reason: 'SERVICE_UNAVAILABLE' };
      const { frames, rest } = splitSseFrames(buffer + decoder.decode(value, { stream: true }));
      buffer = rest;
      for (const frame of frames) {
        const event = parseSseFrame(frame);
        if (event?.event === 'preview') {
          const preview = outcome(JSON.parse(event.data));
          if ('finding' in preview && preview.tier === 1) onPreview(preview);
        }
        if (event?.event === 'final') return outcome(JSON.parse(event.data));
      }
    }
  } finally {
    reader.releaseLock();
  }
}
