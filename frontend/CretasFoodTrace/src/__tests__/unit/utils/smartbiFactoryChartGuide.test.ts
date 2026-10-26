import {
  filterSubjects,
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

  it('bounds searchable subjects instead of rendering a hundred chips', () => {
    const subjects = Array.from({ length: 100 }, (_, index) => ({ id: String(index), label: `产品${index}` }));
    expect(filterSubjects(subjects, '').length).toBe(30);
    expect(filterSubjects(subjects, '产品99')).toEqual([{ id: '99', label: '产品99' }]);
  });
});
