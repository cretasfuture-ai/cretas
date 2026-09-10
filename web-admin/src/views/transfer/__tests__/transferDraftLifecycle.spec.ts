import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h, KeepAlive, reactive } from 'vue';
import { createRouter, createMemoryHistory, RouterView } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Transfer from '../list.vue';
import type { TransferCreateRow } from '../transferCreate';

const mocks = vi.hoisted(() => ({
  auth: { factoryId: 'LOCAL_A' }, get: vi.fn(), post: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mocks.auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }) }));
vi.mock('@/composables/useBusinessMode', () => ({ useBusinessMode: () => ({ label: (key: string) => key }) }));
vi.mock('@/composables/useCreateAndReturn', () => ({ useCreateAndReturn: () => ({ goCreate: vi.fn() }) }));
vi.mock('@/composables/useListSummary', () => ({ useListSummary: () => ({ summary: null, loading: false }) }));
vi.mock('@/api/request', () => ({ get: mocks.get, post: mocks.post }));
vi.mock('element-plus', () => ({ ElMessage: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }), ElMessageBox: { confirm: vi.fn() } }));

interface State {
  form: { transferType: string; targetFactoryId: string; sourceWarehouseId: string; targetWarehouseId: string; transferDate: string; expectedArrivalDate: string; remark: string; items: TransferCreateRow[] };
  factoryNetworkOptions: { factoryId: string }[];
  sourceWarehouseOptions: { id: string }[];
  submitCreate: () => Promise<void>;
  openCreateWorkspace: () => void;
  handleTransferTypeChange: (value: string) => Promise<void>;
}
const Form = defineComponent({ setup(_, { expose, slots }) { expose({ validate: async () => true, clearValidate: vi.fn() }); return () => h('form', slots.default?.()); } });
const stubs = Object.fromEntries(['ElRow','ElCol','ElFormItem','ElSelect','ElOption','ElDatePicker','ElInput','ElDivider','ElAlert','ElTable','ElTableColumn','ElInputNumber','ElButton','ElTag','ElPagination','ElSteps','ElStep','ElTooltip','ConceptDisambiguationAlert','UpstreamMissingHint','TableFooter'].map(key => [key, true]));
beforeEach(() => {
  mocks.auth = reactive({ factoryId: 'LOCAL_A' });
  mocks.get.mockReset().mockImplementation(async (url: string) => ({ success: true, data: url.endsWith('/transfers') ? { content: [], totalElements: 0 } : [] }));
  mocks.post.mockReset();
});
async function app() {
  const blank = defineComponent({ render: () => h('div', 'other') });
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/transfer/new', name: 'TransferCreate', component: Transfer },
    { path: '/transfer/list', name: 'TransferList', component: Transfer },
    { path: '/transfer/:id', name: 'TransferDetail', component: blank },
    { path: '/other', component: blank },
  ] });
  await router.push('/transfer/new'); await router.isReady();
  const host = mount(defineComponent({ render: () => h(RouterView, null, {
    default: ({ Component, route }: { Component: ReturnType<typeof h>; route: { fullPath: string } }) => h(KeepAlive, null, [h(Component, { key: route.fullPath })]),
  }) }), { global: { plugins: [router], directives: { loading: () => {} }, stubs: { ...stubs, ElCard: true, ElForm: Form, ElTable: defineComponent({ render: () => h('div') }) }, renderStubDefaultSlot: true } });
  await flushPromises();
  const state = () => host.findComponent(Transfer).vm.$.setupState as unknown as State;
  return { router, state };
}
function fill(s: State) {
  Object.assign(s.form, { transferType: 'WAREHOUSE_TO_WAREHOUSE', targetFactoryId: 'LOCAL_A', sourceWarehouseId: 'S', targetWarehouseId: 'T', transferDate: '2026-09-11', expectedArrivalDate: '2026-09-12', remark: 'retain me', items: [{ itemType: 'RAW_MATERIAL', selectionCategory: 'MATERIAL:原料', selectedItemId: 'M', materialTypeId: 'M', itemName: '原料', quantity: 3, unit: 'kg' }] });
}
describe('cached transfer draft lifecycle', () => {
  it('retains every field across three route returns without reloading draft options', async () => {
    const { router, state } = await app(); const s = state(); fill(s);
    const before = JSON.stringify(s.form); const count = mocks.get.mock.calls.length;
    for (let i=0;i<3;i++) { await router.push('/other'); await flushPromises(); await router.push('/transfer/new'); await flushPromises(); }
    expect(JSON.stringify(state().form)).toBe(before);
    expect(mocks.get).toHaveBeenCalledTimes(count);
  });
  it('isolates _task copies and makes explicit new a distinct blank task', async () => {
    const { router, state } = await app(); fill(state());
    await router.push('/transfer/new?_task=two'); await flushPromises();
    expect(state().form.remark).toBe(''); state().form.remark = 'second';
    await router.push('/transfer/new'); await flushPromises(); expect(state().form.remark).toBe('retain me');
    await router.push('/transfer/list'); await flushPromises(); state().openCreateWorkspace(); await flushPromises();
    expect(router.currentRoute.value.query._task).toBeTruthy(); expect(state().form.remark).toBe('');
  });
  it('clears cached tenant drafts and ignores an old tenant network response', async () => {
    let finish!: (value: unknown) => void;
    mocks.get.mockImplementation((url: string) => url === '/LOCAL_A/factories/network'
      ? new Promise(resolve => { finish = resolve; }) : Promise.resolve({ success: true, data: [] }));
    const { router, state } = await app(); const first = state(); fill(first);
    await router.push('/other'); await flushPromises(); mocks.auth.factoryId = 'LOCAL_B'; await flushPromises();
    expect(first.form.remark).toBe(''); expect(first.form.items).toEqual([]);
    finish({ success: true, data: [{ factoryId: 'LOCAL_A' }] }); await flushPromises();
    expect(first.factoryNetworkOptions).toEqual([]); expect(first.form.targetFactoryId).toBe('');
    await router.push('/transfer/new'); await flushPromises(); expect(state().form.sourceWarehouseId).toBe('');
    expect(mocks.get.mock.calls.some(([url]) => url === '/LOCAL_B/factories/network')).toBe(true);
  });
  it('retains failed drafts, prevents duplicate submit, and clears a successful draft', async () => {
    const { router, state } = await app(); const s = state(); fill(s); const before = JSON.stringify(s.form);
    mocks.post.mockRejectedValueOnce(new Error('fixture failure')); await s.submitCreate();
    expect(JSON.stringify(s.form)).toBe(before);
    mocks.post.mockResolvedValue({ success: true, data: { id: 'created' } });
    await Promise.all([s.submitCreate(), s.submitCreate()]); await flushPromises();
    expect(mocks.post).toHaveBeenCalledTimes(2); expect(router.currentRoute.value.name).toBe('TransferDetail');
    await router.push('/transfer/new'); await flushPromises(); expect(state().form.remark).toBe(''); expect(state().form.items).toEqual([]);
  });
  it('clears type-dependent target warehouse and never redirects another tab after a late success', async () => {
    const { router, state } = await app(); const s = state(); fill(s);
    await s.handleTransferTypeChange('WAREHOUSE_TO_WAREHOUSE'); expect(s.form.targetWarehouseId).toBe(''); expect(s.form.targetFactoryId).toBe('LOCAL_A');
    s.form.targetWarehouseId='T'; let finish!: (value: unknown) => void;
    mocks.post.mockReturnValue(new Promise(resolve => { finish=resolve; })); const pending=s.submitCreate(); await flushPromises();
    await router.push('/other'); await flushPromises(); finish({ success: true, data: { id: 'late' } }); await pending;
    expect(router.currentRoute.value.path).toBe('/other'); expect(s.form.items).toEqual([]);
  });
});
