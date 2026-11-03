// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { reactive } from 'vue';
import { shallowMount, flushPromises } from '@vue/test-utils';
import DataAlertsPanel from '../DataAlertsPanel.vue';
import type { DatasetAlertReport } from '@/api/smartbi/data-alerts';
const get = vi.hoisted(() => vi.fn());
vi.mock('@/api/smartbi/data-alerts', () => ({ getDatasetAlerts: get, reviewDatasetAlerts: vi.fn() }));
const auth = reactive({ factoryId: 'A', user: { id: 1 }, currentRole: 'factory_super_admin' });
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
function report(id: string, factory = 'A'): DatasetAlertReport {
  return { schema: 'smartbi.dataset-alerts/1', runId: id, scope: { factoryId: factory }, summary: { rows: 3, scannedRows: 3, alerts: 1, byType: { MISSING: 1 }, byFamily: { data_quality: 1 } },
    items: [{ id, type: 'MISSING', family: 'data_quality', severity: 'warning', title: id, message: '缺失不是零', owner: '财务', action: '回查源数据', fields: ['收入'], evidence: {}, blocksUnitCost: false, sourceRefs: [{ sourceType: 'excel', sourceId: id, rowNumber: 2 }] }], ai: { status: 'not_run', message: '规则已完成' } };
}
const options = { global: { renderStubDefaultSlot: true, directives: { loading: () => {} }, stubs: { ...Object.fromEntries(['ElButton', 'ElCard', 'ElInput', 'ElSelect', 'ElOption', 'ElPagination', 'ElEmpty'].map(name => [name, true])), ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' }, ElTable: { name: 'ElTable', props: ['data'], template: '<div><slot /></div>' }, ElTableColumn: { template: '<span />' } } } };
beforeEach(() => { get.mockReset();auth.factoryId = 'A';auth.user.id = 1;auth.currentRole = 'factory_super_admin'; });
describe('shared source and session ownership', () => {
  it('renders supplied rules without starting AI', () => {
    const wrapper = shallowMount(DataAlertsPanel, { ...options, props: { report: report('rules') } });
    expect(wrapper.text()).toContain('规则已完成');expect(get).not.toHaveBeenCalled();
  });
  it('does not publish a late response from the previous upload', async () => {
    let old: (value: DatasetAlertReport) => void = () => {};
    get.mockImplementationOnce(() => new Promise(resolve => { old = resolve; })).mockResolvedValueOnce(report('current'));
    const wrapper = shallowMount(DataAlertsPanel, { ...options, props: { uploadId: 1 } });
    await wrapper.setProps({ uploadId: 2 });await flushPromises();old(report('old'));await flushPromises();
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')[0].title).toBe('current');
  });
  it('clears supplied results when the factory or role changes', async () => {
    const wrapper = shallowMount(DataAlertsPanel, { ...options, props: { report: report('private') } });
    auth.factoryId = 'B';await flushPromises();
    expect(wrapper.findComponent({ name: 'ElTable' }).exists()).toBe(false);expect(wrapper.text()).toContain('会话或数据来源已变化');
  });
  it('rejects a response belonging to another factory', async () => {
    get.mockResolvedValueOnce(report('foreign', 'B'));
    const wrapper = shallowMount(DataAlertsPanel, { ...options, props: { uploadId: 1 } });await flushPromises();
    expect(wrapper.findComponent({ name: 'ElTable' }).exists()).toBe(false);expect(wrapper.text()).toContain('报告工厂与当前会话不一致');
  });
});
