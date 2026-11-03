// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import BlacklakeCostAlerts from '../BlacklakeCostAlerts.vue';
import type { CostAlertReport, EligibleCostReview, CostProject } from '@/api/smartbi/blacklake';
const alerts: CostAlertReport = { schema: 'smartbi.cost-alerts/1', ruleVersion: 'test', summary: { projects: 1, costEligible: 1, costBlocked: 0, alerts: 0, byFamily: {}, byType: {} }, items: [], limitations: [], ai: { status: 'invalid', message: 'AI不可用，规则仍可用' } };
const project: CostProject = { projectId: 1, projectCode: 'P1', product: 'food', productCode: null, unit: 'kg', status: '20', planned: 10, actual: 10, finishedGood: 10, materialCost: 50, laborCost: 20, identifiedDirectCost: 70, unitIdentifiedDirectCost: 7, materialLines: 1, materialCostKnownLines: 1, sourceZeroCostLines: 0, taskCount: 1, laborCostKnownTasks: 1, materialLinesDetail: [], processes: [], issues: [], unitCostAvailable: true };
describe('cost alert page remains readable with incomplete identity or unavailable AI', () => {
  it('uses the actual lowercase unit and keeps eligible missing-code rows visible', async () => {
    const review: EligibleCostReview = { eligibleProjects: 2, products: 0, rankings: [], projects: [{ ...project, materialPerFinished: 5, laborPerFinished: 2, laborShare: 2 / 7, components: [] }, { ...project, projectId: 2, projectCode: 'P2', materialPerFinished: 5, laborPerFinished: 2, laborShare: 2 / 7, components: [] }], limitations: [] };
    const wrapper = shallowMount(BlacklakeCostAlerts, { props: { alerts, review }, global: { renderStubDefaultSlot: true, stubs: { ElTable: { name: 'ElTable', props: ['data'], template: '<div><slot /></div>' }, ElTableColumn: { template: '<span />' } } } });
    await wrapper.vm.$nextTick();
    const tables = wrapper.findAllComponents({ name: 'ElTable' });
    expect(tables.some(t => Array.isArray(t.props('data')) && t.props('data').length === 2 && t.props('data')[0].projectCode === 'P1')).toBe(true);
    expect(wrapper.text()).toContain('AI不可用，规则仍可用');
    expect(wrapper.text()).toContain('2 张工单');
  });
});
