// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide, type Ref } from 'vue';
import type { CostTraceabilityReport, TraceableWorkOrder } from '@/api/smartbi/blacklake';

vi.mock('@nuxt/ui/components/Card.vue', () => ({ default: { template: '<div><slot name="header"/><slot/></div>' } }));
vi.mock('@nuxt/ui/components/Button.vue', () => ({ default: { template: '<button><slot/></button>' } }));
const auth = vi.hoisted(() => ({ store: { factoryId: 'T', user: { id: 1 }, currentRole: 'factory_super_admin' } }));
vi.mock('@/store/modules/auth', async () => {
  const { reactive } = await import('vue'); auth.store = reactive(auth.store); return { useAuthStore: () => auth.store };
});
vi.mock('@/api/smartbi/blacklake', () => ({ getBlacklakeCostTraceability: vi.fn() }));
import { getBlacklakeCostTraceability } from '@/api/smartbi/blacklake';
import Component from '../BlacklakeCostTraceability.vue';

function data(): CostTraceabilityReport {
  return { schema: 'smartbi-cost-traceability/1', checkedAt: '2026-11-04T10:50:00Z',
    summary: { workOrders: 1, ordersWithDirectCost: 0, materialOriginLinks: 1, materialProcessLinks: 0 },
    baselineComparison: { newlyFieldsEligibleOriginallyBlocked: 0, previouslyEligibleNowBlocked: 1, stillBlockedOriginalCohort: 1 },
    sourceProbe: { allRequiredQueriesComplete: true, newOrdersOutsideOriginalCohort: 14 },
    workOrders: [{ workOrderId: 1, workOrderCode: 'SYN-ORDER', product: '合成食品', productCode: 'FOOD', unit: '包',
      identifiedUnitCost: null, directCostAvailable: false, directCostBlockingReasons: ['单价负值'], businessLinks: [],
      providedClues: [{ label: '单片重量', value: '0.25', usage: '单位待核' }], baselineComparison: { directCostAvailable: true },
      needs: [{ id: 'A', code: 'MATERIAL_AMOUNT_CHECK', workOrderId: 1, workOrderCode: 'SYN-ORDER', missingData: '核对原领料单价和金额符号',
        responsibleRole: '财务/仓库负责人', afterProvided: '恢复可信的材料成本', sourceStatus: 'source_values_inconsistent', impactType: 'unit_cost', priority: 'high', sourceDocuments: ['OUT-SYN'] }] }] };
}
const table = defineComponent({ props: ['data'], setup(props, { slots }) { provide('trace-test-rows', computed(() => props.data)); return () => h('div', slots.default?.()); } });
const column = defineComponent({ setup(_, { slots }) { const rows = inject<Ref<TraceableWorkOrder[]>>('trace-test-rows'); return () => h('div', rows?.value.map(row => slots.default?.({ row }))); } });
function render() { return mount(Component, { global: { stubs: { 'el-table': table, 'el-table-column': column,
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-select': { template: '<div><slot/></div>' }, 'el-option': true, 'el-pagination': true,
  'el-alert': { props: ['title'], template: '<div role="alert">{{ title }}</div>' } } } }); }

beforeEach(() => { auth.store.factoryId = 'T'; vi.mocked(getBlacklakeCostTraceability).mockReset(); });
describe('cost traceability worklist', () => {
  it('shows concrete missing records, owner, benefit and existing clues without calling blocked cost zero', async () => {
    vi.mocked(getBlacklakeCostTraceability).mockResolvedValue(data()); const page = render(); await flushPromises();
    expect(page.text()).toContain('暂不可算');
    await page.findAll('button').find(b => b.text() === '查看待补资料')!.trigger('click');
    expect(page.text()).toContain('财务/仓库负责人'); expect(page.text()).toContain('恢复可信的材料成本');
    expect(page.text()).toContain('OUT-SYN'); expect(page.text()).toContain('单片重量：0.25');
    expect(page.text()).not.toContain('0.00 / 包'); page.unmount();
  });
  it('keeps unavailable authorization visible and does not fabricate empty successful results', async () => {
    vi.mocked(getBlacklakeCostTraceability).mockRejectedValue(new Error('成本授权未确认'));
    const page = render(); await flushPromises(); expect(page.text()).toContain('成本授权未确认');
    expect(page.text()).not.toContain('材料加人工字段具备'); page.unmount();
  });
  it('discards an old factory response after the authenticated identity changes', async () => {
    let finish: (value: CostTraceabilityReport) => void = () => undefined;
    vi.mocked(getBlacklakeCostTraceability).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }))
      .mockRejectedValueOnce(new Error('成本授权未确认'));
    const page = render(); auth.store.factoryId = 'OTHER'; await flushPromises(); finish(data()); await flushPromises();
    expect(page.text()).toContain('成本授权未确认'); expect(page.text()).not.toContain('合成食品'); page.unmount();
  });
});
