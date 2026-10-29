import { describe, expect, it } from 'vitest';
import { FMT_REGISTRY, processEChartsOptions } from '../echarts-fmt';

describe('generic Sankey formatter registry', () => {
  it('resolves node and edge callbacks for quantities without currency labels', () => {
    const resolved = processEChartsOptions({
      series: [{ label: { formatter: '__FMT__sankey_node_label' }, tooltip: { formatter: '__FMT__sankey_tooltip' } }],
    });
    const series = (resolved.series as Array<Record<string, unknown>>)[0]!;
    const label = (series.label as Record<string, unknown>).formatter as (value: unknown) => string;
    const tooltip = (series.tooltip as Record<string, unknown>).formatter as (value: unknown) => string;

    expect(label({ name: '库存', value: 1234 })).toBe('库存\n1,234');
    expect(label({ name: '无数量', value: undefined })).toBe('无数量');
    expect(tooltip({ name: '<节点>', value: 0 })).toContain('&lt;节点&gt;');
    expect(tooltip({ name: '<节点>', value: 0 })).toContain('数量: 0');
    const edge = tooltip({ dataType: 'edge', data: { source: '<来源>', target: '<去向>', value: undefined } });
    expect(edge).toContain('&lt;来源&gt; → &lt;去向&gt;');
    expect(edge).toContain('数量: -');
    expect(tooltip({ dataType: 'edge', data: { source: '<来源>', target: '去向', value: 12.5 } })).toContain('数量: 12.5');
    expect(tooltip({ dataType: 'edge', data: { source: '<来源>', target: '去向', value: 12.5 } })).not.toContain('元');
  });

  it('retains financial semantics and preserves unknown marker behavior', () => {
    expect(FMT_REGISTRY.sankey_financial_tooltip({ dataType: 'edge', data: { source: '收入', target: '利润', value: 12345 } })).toContain('金额: 1.23万元');
    const unresolved = processEChartsOptions({ formatter: '__FMT__not_registered' });
    expect(unresolved.formatter).toBe('__FMT__not_registered');
  });
});
