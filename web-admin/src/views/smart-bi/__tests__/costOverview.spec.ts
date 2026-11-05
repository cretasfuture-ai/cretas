import { describe, expect, it } from 'vitest';
import type { CostProject } from '@/api/smartbi/blacklake';
import { buildCostOverview, buildUnitCostChart, groupEligibleCostProjects } from '../costOverview';

const project = (overrides: Partial<CostProject> = {}): CostProject => ({
  projectId: 1,
  projectCode: 'P1',
  product: '黑椒鸡胸',
  productCode: 'SKU-1',
  unit: 'kg',
  status: '20',
  planned: 10,
  actual: 10,
  finishedGood: 10,
  materialCost: 50,
  laborCost: 20,
  identifiedDirectCost: 70,
  unitIdentifiedDirectCost: 7,
  materialLines: 1,
  materialCostKnownLines: 1,
  sourceZeroCostLines: 0,
  taskCount: 1,
  laborCostKnownTasks: 1,
  materialLinesDetail: [],
  processes: [],
  issues: [],
  unitCostAvailable: true,
  ...overrides,
});

describe('Blacklake cost overview model', () => {
  it('uses sum of component costs divided by sum of finished good for weighted groups', () => {
    const groups = groupEligibleCostProjects([
      project(),
      project({ projectId: 2, projectCode: 'P2', finishedGood: 30, materialCost: 120, laborCost: 30 }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({ sampleCount: 2, finishedGood: 40, materialCost: 170, laborCost: 50 });
    expect(groups[0].weightedMaterialUnitCost).toBeCloseTo(4.25);
    expect(groups[0].weightedLaborUnitCost).toBeCloseTo(1.25);
    expect(groups[0].weightedUnitCost).toBeCloseTo(5.5);
  });

  it('does not merge conflicting product names or source units', () => {
    const groups = groupEligibleCostProjects([
      project(),
      project({ projectId: 2, projectCode: 'P2', product: '黑椒鸡腿' }),
      project({ projectId: 3, projectCode: 'P3', unit: '箱' }),
    ]);
    expect(groups).toHaveLength(3);
    expect(groups.map(group => `${group.product}/${group.unit}`)).toEqual(expect.arrayContaining(['黑椒鸡腿/kg', '黑椒鸡胸/kg', '黑椒鸡胸/箱']));
  });

  it('keeps the chart stacked by material and labour on one unit axis', () => {
    const chart = buildUnitCostChart(groupEligibleCostProjects([project()]));
    expect(chart.series?.map(series => [series.name, series.stack])).toEqual([
      ['材料', 'unit-cost'],
      ['人工', 'unit-cost'],
    ]);
    expect(chart.yAxis?.[0]?.name).toContain('成品单位');
    expect(chart).not.toHaveProperty('totalCost');
  });

  it('does not infer shared product identity from matching names or trimmed codes', () => {
    expect(groupEligibleCostProjects([project({productCode:null}),project({projectId:2,productCode:null})])).toHaveLength(2);
    expect(groupEligibleCostProjects([project({productCode:'SKU-1'}),project({projectId:2,productCode:' SKU-1'})])).toHaveLength(2);
    const chart=buildUnitCostChart(groupEligibleCostProjects([project({productCode:null,projectCode:'ORDER-A'}),project({projectId:2,productCode:null,projectCode:'ORDER-B'})]));
    expect(new Set(chart.xAxis?.data).size).toBe(2);
    expect(chart.xAxis?.data?.join(' ')).toContain('编码缺失 · ORDER-A');
  });

  it('reports order coverage separately from money coverage and preserves signal counts', () => {
    const metrics = buildCostOverview({
      production: { projectCount: 109 },
      cost: { projects: [], unitCostAvailableProjects: 16, linkedMaterialLines: 32, unlinkedMaterialLines: 4, sourceZeroCostLines: 74, knownMaterialCostLines: 18 },
      costAlerts: { summary: { byFamily: { business_signal: 5, data_quality: 7, readiness: 3 } } },
    });
    expect(metrics).toMatchObject({ eligibleProjects: 16, blockedProjects: 93, sourceZeroCostLines: 74, businessSignalCount: 5, dataQualityAlertCount: 7, readinessAlertCount: 3 });
    expect(metrics.orderCoveragePercent).toBeCloseTo(16 / 109 * 100);
  });
});
