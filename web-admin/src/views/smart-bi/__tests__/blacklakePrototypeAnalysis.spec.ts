// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { shallowMount } from '@vue/test-utils';

vi.mock('@nuxt/ui/components/Card.vue', () => ({ default: { template: '<div><slot /></div>' } }));
vi.mock('@nuxt/ui/components/Button.vue', () => ({ default: { template: '<button><slot /></button>' } }));
vi.mock('@nuxt/ui/components/Badge.vue', () => ({ default: { template: '<span><slot /></span>' } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006', user: { id: 1 }, currentRole: 'factory_super_admin' }) }));
vi.mock('@/components/smartbi/DynamicChartRenderer.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../BlacklakePrototypeCostIntelligence.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/api/smartbi/blacklake', async () => {
  const actual = await vi.importActual<typeof import('@/api/smartbi/blacklake')>('@/api/smartbi/blacklake');
  return { ...actual, getBlacklakeAnalysis: vi.fn() };
});

import { getBlacklakeAnalysis } from '@/api/smartbi/blacklake';
import BlacklakePrototypeAnalysis from '../BlacklakePrototypeAnalysis.vue';
import type { BlacklakeAnalysis } from '@/api/smartbi/blacklake';

const project = {
  projectId: 2, projectCode: 'P2', product: '黑椒鸡胸', productCode: 'SKU-2', unit: 'kg', status: '20', planned: 10, actual: 10,
  finishedGood: 10, materialCost: 50, laborCost: 20, identifiedDirectCost: 70, unitIdentifiedDirectCost: 7,
  materialLines: 1, materialCostKnownLines: 1, sourceZeroCostLines: 0, taskCount: 1, laborCostKnownTasks: 1,
  materialLinesDetail: [], processes: [], issues: [], unitCostAvailable: true,
};
const analysis = {
  sectionInsights: [], costAlerts: { schema: 'test', ruleVersion: 'test', summary: { projects: 1, costEligible: 1, costBlocked: 0, alerts: 1, byFamily: { business_signal: 1 }, byType: {} }, items: [{ id: 'A1', type: 'missing', family: 'business_signal', severity: 'warning', blocksUnitCost: false, title: '缺少来源说明', message: '请回看来源记录', owner: '仓库', action: '核对领退料单', fields: ['materialCost'], entityRefs: [{ projectId: 2, projectCode: 'P2', productCode: 'SKU-2', unit: 'kg' }], evidence: {}, detectedBy: 'test', ruleVersion: 'test', scope: 'snapshot', runId: 'run-1', status: 'open' }], limitations: [], ai: { status: 'invalid', message: '规则分析' } },
  eligibleCostReview: { eligibleProjects: 1, products: 1, limitations: [], rankings: [], projects: [] },
  alertFollowups: {}, costIntelligence: { engine: 'test', recommendations: [], comparisons: [], answer: '', limitations: [], worklist: [], verifiedMaterialReferences: {}, gapEvidence: {} },
  factoryName: 'F006', snapshotAt: '2026-11-04T00:00:00Z', runId: 'run-1', coverageComplete: true,
  catalog: [], findings: [], limitations: [], production: { projectCount: 1, taskCount: 1, outputCount: 1, completionRate: 1, statuses: { '20': 1 }, planActual: [], quality: [], processes: [] },
  inventory: { records: [], byWarehouseUnit: [], pairsNotReturned: 0, movementOrders: 0 },
  cost: { projects: [project], materials: [], linkedMaterialLines: 1, unlinkedMaterialLines: 0, excludedUnapprovedOrders: 0, knownMaterialCostLines: 1, sourceZeroCostLines: 0, knownLaborTasks: 1, unitCostAvailableProjects: 1, fullManufacturingCost: null, grossProfit: null },
} satisfies BlacklakeAnalysis;

describe('Blacklake prototype page', () => {
  it('keeps project-linked alerts visible in the cost table path', async () => {
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue(analysis);
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: {
      DynamicChartRenderer: true,
      PrototypeSectionInsight: true,
      BlacklakePrototypeCostAlerts: { template: '<div />' },
      BlacklakePrototypeCostIntelligence: true,
      'el-table': { props: ['data'], template: '<div><div v-for="row in data" :key="row.projectId">{{ row.product }}</div><slot /></div>' },
      'el-table-column': { template: '<span />' },
      'el-tag': { template: '<span><slot /></span>' },
    } } });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(wrapper.text()).toContain('黑椒鸡胸');
    expect(wrapper.find('[data-testid="cost-projects"]').exists()).toBe(true);
  });
});

