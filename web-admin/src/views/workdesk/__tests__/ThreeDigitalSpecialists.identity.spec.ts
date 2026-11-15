import { defineComponent, reactive } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Sales from '../SalesOwnerWorkdesk.vue';
import Purchase from '../PurchaserWorkdesk.vue';
import Inventory from '../WarehouseKeeperWorkdesk.vue';
import Finance from '../FinanceManagerWorkdesk.vue';
import Production from '../ProductionManagerWorkdesk.vue';
import QualityManager from '../QualityManagerWorkdesk.vue';
import QualityChief from '../QualityChiefWorkdesk.vue';

const api = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn() }));
const auth = reactive({ factoryId: 'FACTORY_A', factoryName: '测试企业', user: { id: 43 } });
vi.mock('@/api/request', () => ({ default: api }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
const Frame = defineComponent({ name: 'DigitalEmployeeFrame', template: '<div><slot /></div>' });
const Conversation = defineComponent({ name: 'DigitalEmployeeConversation', props: ['response'], emits: ['send', 'retry', 'reset'], template: '<div data-conversation>{{ response }}<slot name="action" /></div>' });
const mountPage = (component: typeof Sales, extra = {}) => shallowMount(component, {
  props: { digitalEmployee: true, employeeNavigationPending: true, ...extra },
  global: { stubs: { DigitalEmployeeFrame: Frame, DigitalEmployeeConversation: Conversation, ElTable: defineComponent({ template: '<div />' }), ElTableColumn: defineComponent({ template: '<div />' }) } },
});

describe('sales/purchase/inventory digital specialist identities', () => {
  it.each([
    [Sales, ['DAILY_CUSTOMER_FOLLOWUP', 'SPRINT10_SHIPMENT_PENDING_TODAY']],
    [Purchase, ['PURCHASER_WEEKLY_PLAN', 'SALES_FORECAST_7DAY']],
    [Inventory, ['MATERIAL_TODAY_RECEIVING_QUERY', 'MATERIAL_DISPOSAL_RECOMMENDATION', 'RECEIVE_QUALITY_CHECK_TODAY']],
  ] as const)('retains the ordinary factory native daily queries in the digital presentation', async (component, intents) => {
    const wrapper = mountPage(component, { employeeNavigationPending: false, wholesaleMode: false }); await flushPromises();
    for (const intentCode of intents) expect(api.post.mock.calls.some(([, body]) => body.intentCode === intentCode && body.mode === 'READ')).toBe(true);
    wrapper.unmount();
  });
  it('retains ordinary factory purchase forecasts and material price history values', async () => {
    api.post.mockImplementation((_path, body) => Promise.resolve({ data: { status: 'SUCCESS', resultData:
      body.intentCode === 'PURCHASER_WEEKLY_PLAN' ? { warnings: [{ materialTypeId: 'MAT', materialName: '物料', currentStock: 2 }] }
        : body.intentCode === 'SALES_FORECAST_7DAY' ? { forecasts: [{ productTypeId: 'MAT', predicted7DayDemand: 5 }] }
          : body.intentCode === 'PRICE_HISTORY_QUERY' ? { monthlyPrices: [{ yearMonth: '2026-11', weightedAvgPrice: 8 }] } : {},
    } }));
    const wrapper = mountPage(Purchase, { employeeNavigationPending: false, wholesaleMode: false }); await flushPromises();
    const vm = wrapper.vm as unknown as { forecastByMaterial: Record<string, number>; priceTrendByMaterial: Record<string, unknown[]> };
    expect(vm.forecastByMaterial.MAT).toBe(5);
    expect(vm.priceTrendByMaterial.MAT).toEqual([{ yearMonth: '2026-11', weightedAvgPrice: 8 }]);
    wrapper.unmount();
  });
  it.each([Sales, Purchase, Inventory, Finance, Production, QualityManager, QualityChief])('does not dispatch a question while navigation identity is pending', async (component) => {
    const wrapper = mountPage(component);
    await flushPromises();
    wrapper.findComponent(Conversation).vm.$emit('send', '查询当前业务');
    await flushPromises();
    expect(api.post).not.toHaveBeenCalled();
    expect(api.get).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it.each([
    [Sales, ['previewWechatCreate', 'executeWechatCreate', 'previewOpportunityTransition', 'executeOpportunityTransition', 'submitShipment']],
    [Inventory, ['previewReceive', 'executeReceive', 'previewConfirmReceive', 'executeConfirmReceive', 'generateScanTask']],
  ] as const)('does not dispatch native writes from readonly handlers', async (component, methods) => {
    const wrapper = mountPage(component, { employeeCanWrite: false, employeeNavigationPending: false }); await flushPromises();
    api.post.mockClear();
    for (const method of methods) await (wrapper.vm as unknown as Record<string, () => Promise<void>>)[method]();
    expect(api.post).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  beforeEach(() => {
    auth.factoryId = 'FACTORY_A'; auth.user = { id: 43 };
    api.post.mockReset().mockResolvedValue({ data: { status: 'SUCCESS', formattedText: '当前企业结果', resultData: { warnings: [] } } });
    api.get.mockReset().mockResolvedValue({ data: [{ id: 'MAT', name: '物料', unit: 'kg', minStock: 10 }] });
  });
  it.each([['sales', Sales], ['purchase', Purchase], ['inventory', Inventory]] as const)('%s does not publish a delayed response after factory/actor switch', async (_role, component) => {
    const wrapper = mountPage(component);
    await flushPromises();
    expect(api.post).not.toHaveBeenCalled();
    await wrapper.setProps({ employeeNavigationPending: false });
    await flushPromises();
    let resolveOld!: (value: unknown) => void;
    api.post.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    wrapper.findComponent(Conversation).vm.$emit('send', '查询当前业务记录');
    await flushPromises();
    await wrapper.setProps({ employeeNavigationPending: true });
    auth.factoryId = 'FACTORY_B'; auth.user = { id: 44 };
    await flushPromises();
    expect(wrapper.findComponent(Conversation).props('response')).toBe('');
    resolveOld({ data: { status: 'SUCCESS', formattedText: '旧企业敏感结果', resultData: {} } });
    await flushPromises();
    expect(wrapper.findComponent(Conversation).props('response')).toBe('');
    await wrapper.setProps({ employeeNavigationPending: false });
    await flushPromises();
    expect(api.post.mock.calls.some(([path, body]) => path === '/FACTORY_B/ai-intents/execute' && body.mode === 'READ')).toBe(true);
    expect(wrapper.text()).not.toContain('旧企业敏感结果');
    wrapper.unmount();
  });

  it('retains the ordinary factory native procurement preview contract without forcing READ', async () => {
    const wrapper = mountPage(Purchase, { employeeCanWrite: true, employeeNavigationPending: false });
    await flushPromises();
    const vm = wrapper.vm as unknown as { openRequisitionDialog(row: Record<string, unknown>): void; previewRequisition(): Promise<void>; requisitionDialog: { visible: boolean } };
    const row = { materialTypeId: 'MAT', materialName: '物料', unit: 'kg', gap: 2, preferredSupplier: { id: 'SUP', name: '供应商' } };
    vm.openRequisitionDialog(row);
    expect(vm.requisitionDialog.visible).toBe(true);
    api.post.mockResolvedValueOnce({ data: { status: 'PREVIEW', resultData: { canDo: true } } });
    await vm.previewRequisition();
    const write = api.post.mock.calls.find(([, body]) => body.intentCode === 'REQUISITION_CREATE');
    expect(write?.[1]).toMatchObject({ intentCode: 'REQUISITION_CREATE', preview: true, parameters: { materialId: 'MAT', quantity: 2 } });
    expect(write?.[1]).not.toHaveProperty('mode', 'READ');
    wrapper.unmount();
    const wholesale = mountPage(Purchase, { employeeCanWrite: true, wholesaleMode: true, employeeNavigationPending: false });
    (wholesale.vm as unknown as typeof vm).openRequisitionDialog(row);
    expect((wholesale.vm as unknown as typeof vm).requisitionDialog.visible).toBe(false);
    wholesale.unmount();
  });
  it.each(['employeeCanWrite', 'employeeNavigationPending', 'wholesaleMode'] as const)('closes an open native dialog and prevents preview after %s changes', async (capability) => {
    const wrapper = mountPage(Purchase, { employeeCanWrite: true, employeeNavigationPending: false }); await flushPromises();
    const vm = wrapper.vm as unknown as { openRequisitionDialog(row: Record<string, unknown>): void; previewRequisition(): Promise<void>; executeRequisition(): Promise<void>; requisitionDialog: { visible: boolean; canSubmit: boolean } };
    vm.openRequisitionDialog({ materialTypeId: 'MAT', materialName: '物料', unit: 'kg', gap: 2 });
    expect(vm.requisitionDialog.visible).toBe(true);
    await wrapper.setProps({ [capability]: capability !== 'employeeCanWrite' });
    await flushPromises(); api.post.mockClear();
    expect(vm.requisitionDialog.visible).toBe(false);
    expect(vm.requisitionDialog.canSubmit).toBe(false);
    await vm.previewRequisition(); await vm.executeRequisition();
    expect(api.post).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
