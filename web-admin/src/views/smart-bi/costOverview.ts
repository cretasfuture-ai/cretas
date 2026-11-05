import type { CostProject } from '@/api/smartbi/blacklake';
import type { DynamicChartConfig } from '@/types/smartbi';

/** A cost row that has enough evidence for a per-unit material/labour view. */
export interface EligibleCostProject extends CostProject {
  unit: string;
  finishedGood: number;
  materialCost: number;
  laborCost: number;
}

export interface ProductUnitCostSummary {
  key: string;
  product: string;
  productCode: string | null;
  displayIdentity: string;
  unit: string;
  sampleCount: number;
  finishedGood: number;
  materialCost: number;
  laborCost: number;
  weightedMaterialUnitCost: number;
  weightedLaborUnitCost: number;
  weightedUnitCost: number;
  minimumUnitCost: number;
  maximumUnitCost: number;
}

export interface CostOverviewMetrics {
  projectCount: number;
  eligibleProjects: number;
  blockedProjects: number;
  orderCoveragePercent: number | null;
  linkedMaterialLines: number;
  unlinkedMaterialLines: number;
  sourceZeroCostLines: number;
  knownMaterialCostLines: number;
  businessSignalCount: number;
  dataQualityAlertCount: number;
  readinessAlertCount: number;
}

export interface CostOverviewInput {
  production: { projectCount: number };
  cost: Pick<CostOverviewMetrics, 'linkedMaterialLines' | 'unlinkedMaterialLines' | 'sourceZeroCostLines' | 'knownMaterialCostLines'> & {
    projects: CostProject[];
    unitCostAvailableProjects: number;
  };
  costAlerts?: {
    summary?: { byFamily?: Record<string, number> };
  };
}

function isFinitePositive(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function isFiniteNumber(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/**
 * Keep cost groups on one product identity and one source unit. Product codes
 * are part of the identity, while a missing code never merges different names.
 */
export function groupEligibleCostProjects(projects: readonly CostProject[], unit?: string): ProductUnitCostSummary[] {
  const groups = new Map<string, { rows: EligibleCostProject[]; product: string; productCode: string | null; unit: string }>();
  for (const project of projects) {
    if (!project.unitCostAvailable || !project.unit || (unit && project.unit !== unit)) continue;
    if (!isFinitePositive(project.finishedGood) || !isFiniteNumber(project.materialCost) || !isFiniteNumber(project.laborCost)) continue;
    const product = project.product.trim() || '未命名产品';
    const productCode = project.productCode?.trim() ? project.productCode : null;
    // A display name alone cannot establish that two rows are the same SKU.
    const identity = productCode ?? `missing-code-project:${project.projectId}`;
    const key = `${identity}\u0000${product}\u0000${project.unit}`;
    const existing = groups.get(key);
    const row = project as EligibleCostProject;
    if (existing) existing.rows.push(row);
    else groups.set(key, { rows: [row], product, productCode, unit: project.unit });
  }

  return [...groups.entries()].map(([key, group]) => {
    const finishedGood = group.rows.reduce((sum, row) => sum + row.finishedGood, 0);
    const materialCost = group.rows.reduce((sum, row) => sum + row.materialCost, 0);
    const laborCost = group.rows.reduce((sum, row) => sum + row.laborCost, 0);
    const perProject = group.rows.map(row => (row.materialCost + row.laborCost) / row.finishedGood);
    return {
      key,
      product: group.product,
      productCode: group.productCode,
      displayIdentity: group.productCode ? (group.productCode===group.productCode.trim()?group.productCode:`编码含首尾空格 ${JSON.stringify(group.productCode)}`) : `编码缺失 · ${group.rows.map(p=>p.projectCode).join('、')}`,
      unit: group.unit,
      sampleCount: group.rows.length,
      finishedGood,
      materialCost,
      laborCost,
      weightedMaterialUnitCost: materialCost / finishedGood,
      weightedLaborUnitCost: laborCost / finishedGood,
      weightedUnitCost: (materialCost + laborCost) / finishedGood,
      minimumUnitCost: Math.min(...perProject),
      maximumUnitCost: Math.max(...perProject),
    };
  }).sort((a, b) => a.product.localeCompare(b.product, 'zh-CN') || a.unit.localeCompare(b.unit));
}

/** Alias kept explicit for callers that describe the operation as grouping. */
export const groupEligibleProjectsByProduct = groupEligibleCostProjects;

export function buildUnitCostChart(groups: readonly ProductUnitCostSummary[]): DynamicChartConfig {
  return {
    chartType: 'bar',
    title: '产品单位直接成本 · 同单位比较',
    subTitle: '材料与人工分别堆叠；不同单位不混合',
    xAxis: { type: 'category', data: groups.map(group => `${group.product}\n${group.displayIdentity}`) },
    yAxis: [{ type: 'value', name: '来源金额 / 成品单位' }],
    legend: { show: true, data: ['材料', '人工'], position: 'bottom' },
    tooltip: { trigger: 'axis' },
    series: [
      { name: '材料', type: 'bar', stack: 'unit-cost', data: groups.map(group => group.weightedMaterialUnitCost), yAxisIndex: 0, itemStyle:{color:'#1b65a8'} },
      { name: '人工', type: 'bar', stack: 'unit-cost', data: groups.map(group => group.weightedLaborUnitCost), yAxisIndex: 0, itemStyle:{color:'#6ca5d7'} },
    ],
  };
}

/** Alias for templates that use the shorter chart terminology. */
export const createUnitCostChart = buildUnitCostChart;

export function buildCostOverview(input: CostOverviewInput): CostOverviewMetrics {
  const projectCount = Math.max(0, input.production.projectCount || 0);
  const eligibleProjects = Math.max(0, Math.min(projectCount, input.cost.unitCostAvailableProjects || 0));
  const businessSignalCount = input.costAlerts?.summary?.byFamily?.business_signal || 0;
  const dataQualityAlertCount = input.costAlerts?.summary?.byFamily?.data_quality || 0;
  const readinessAlertCount = input.costAlerts?.summary?.byFamily?.readiness || 0;
  return {
    projectCount,
    eligibleProjects,
    blockedProjects: Math.max(0, projectCount - eligibleProjects),
    orderCoveragePercent: projectCount ? eligibleProjects / projectCount * 100 : null,
    linkedMaterialLines: input.cost.linkedMaterialLines,
    unlinkedMaterialLines: input.cost.unlinkedMaterialLines,
    sourceZeroCostLines: input.cost.sourceZeroCostLines,
    knownMaterialCostLines: input.cost.knownMaterialCostLines,
    businessSignalCount,
    dataQualityAlertCount,
    readinessAlertCount,
  };
}
