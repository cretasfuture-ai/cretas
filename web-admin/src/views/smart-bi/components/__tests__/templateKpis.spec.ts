import { describe, expect, it } from 'vitest';
import { visibleKpiEntries, KPI_SLOT_COUNT } from '../templateKpis';

// Key order exactly as the F006 经营驾驶舱 received it (jsonb: shortest key first).
const monthlyTrendFromJsonb = {
  unit: '元',
  peakValue: 15000,
  aggregation: 'sum',
  peakPeriod: '2025-03-01',
  periodCount: 3,
  troughValue: 10000,
  troughPeriod: '2025-01-01',
  totalRevenue: 37500,
};

describe('visibleKpiEntries', () => {
  it('never spends a KPI slot on unit / aggregation', () => {
    const keys = visibleKpiEntries(monthlyTrendFromJsonb).map(([k]) => k);
    expect(keys).toEqual(['peakValue', 'peakPeriod', 'periodCount', 'troughValue']);
    expect(keys).not.toContain('unit');
    expect(keys).not.toContain('aggregation');
  });

  it('still fills all slots from the remaining KPIs', () => {
    expect(visibleKpiEntries(monthlyTrendFromJsonb)).toHaveLength(KPI_SLOT_COUNT);
  });

  it('applies the caller hide rule (price-sensitive keys) after removing metadata', () => {
    const keys = visibleKpiEntries(monthlyTrendFromJsonb, (k) => /value|revenue/i.test(k)).map(([k]) => k);
    expect(keys).toEqual(['peakPeriod', 'periodCount', 'troughPeriod']);
  });

  it('handles missing kpiValues', () => {
    expect(visibleKpiEntries(null)).toEqual([]);
    expect(visibleKpiEntries(undefined)).toEqual([]);
  });
});
