import {
  filterSubjects,
  chartVariantLabel,
  groupChartFamilies,
  selectionForFamilies,
  selectedFamilyIds,
} from '../../../utils/smartbiFactoryChartGuide';
import type { FactoryRecommendation } from '../../../types/factoryCharts';

const item = (id: string, familyId: string, familyLabel: string, unit: string): FactoryRecommendation => ({
  id, title: `${familyLabel}-${unit}`, metric: '指标', domain: '销售', priority: 'required', reason: '事实', source: 'business', unit,
  analysis: { familyId, familyLabel }, findings: { good: [], attention: [], suggestions: [] },
  chart: { chartType: 'bar', echartsOption: {} }, kpis: [], tableData: null,
});

describe('smartbiFactoryChartGuide', () => {
  it('groups variants by server family and keeps units distinct', () => {
    const items = [item('f1@kg', 'f1', '产量', 'kg'), item('f1@box', 'f1', '产量', '箱'), item('f2', 'f2', '收入', '元')];
    expect(groupChartFamilies(items)).toHaveLength(2);
    const ids = selectedFamilyIds(items, ['f1@box']);
    expect(selectionForFamilies(items, ids, new Map([['f1', 'f1@box']]))).toEqual(['f1@box']);
    expect(selectionForFamilies(items, new Set(['f1', 'f2']), new Map(), ['f1@box'])).toEqual(['f1@box', 'f2']);
  });

  it('labels source quantity units without converting them or inventing them for financial percent', () => {
    expect(chartVariantLabel(item('ratio@kg', 'ratio', '比率', '%'))).toBe('柱形 · %');
    expect(chartVariantLabel({ ...item('ratio@kg', 'ratio', '比率', '%'), analysis: { familyId: 'ratio', familyLabel: '比率', sourceQuantityUnit: 'kg' } })).toBe('柱形 · % · 口径：kg');
    expect(chartVariantLabel({ ...item('ratio@box', 'ratio', '比率', '%'), analysis: { familyId: 'ratio', familyLabel: '比率', sourceQuantityUnit: 'box' } })).toBe('柱形 · % · 口径：box');
    expect(chartVariantLabel({ ...item('ratio@盒', 'ratio', '比率', '%'), analysis: { familyId: 'ratio', familyLabel: '比率', sourceQuantityUnit: '盒' } })).toBe('柱形 · % · 口径：盒');
  });

  it('bounds searchable subjects instead of rendering a hundred chips', () => {
    const subjects = Array.from({ length: 100 }, (_, index) => ({ id: String(index), label: `产品${index}` }));
    expect(filterSubjects(subjects, '').length).toBe(8);
    expect(filterSubjects(subjects, '产品99')).toEqual([{ id: '99', label: '产品99' }]);
  });
});
