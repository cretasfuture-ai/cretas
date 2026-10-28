import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { reactive } from 'vue';
import ElementPlus from 'element-plus';
const state = vi.hoisted(() => ({ get: vi.fn(), push: vi.fn() }));
const auth = reactive({ factoryId: 'NEW-FACTORY', user: { id: 2 }, currentRole: 'factory_super_admin' });
const route = reactive({ params: { id: '17' }, query: {} });
const access = reactive({ canViewPrice: true, canWrite: false });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: state.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({
  get canViewPrice() { return access.canViewPrice; }, canWrite: () => access.canWrite, canAccess: () => true,
}) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => state.get(...args), post: vi.fn() }));
vi.mock('@/api/processProduction', () => ({
  getBatchWip: vi.fn().mockResolvedValue({ success: true, data: [] }), getOrderYieldSummary: vi.fn(),
  getBatchWorkProcessTasks: vi.fn().mockResolvedValue({ success: true, data: [] }), updateWorkProcessTaskPlan: vi.fn(),
}));
vi.mock('@/api/factory', () => ({ getOperatorUsers: vi.fn() }));
import Page from '../detail.vue';
import SupplementalCostsDialog from '../SupplementalCostsDialog.vue';

const legacy = { id: 17, factoryId: 'NEW-FACTORY', batchNumber: 'BOX-17', productionPlanId: 'PLAN-17', status: 'COMPLETED', unit: '盒',
  goodQuantity: 20, defectQuantity: 2, totalCost: 999999, laborCost: 888888, equipmentCost: 777777, unitCost: 666666 };
const complete = () => ({ finishedBatchNumber: 'BOX-17', hasData: true, priceMasked: false,
  costComplete: true, calculationStatus: 'COMPLETE', totalCost: 128.33, perBoxCost: 6.4165,
  rawMaterialCost: 100, laborCost: 20, packagingCost: 8.33, equipmentCost: null, otherCost: null,
  costDenominatorUnit: '盒', outputUnit: '盒', missingCostItems: [] as string[] });
let costResult: unknown;
let costFailure: Error | null;
const wrappers: VueWrapper[] = [];
beforeEach(() => {
  vi.clearAllMocks(); auth.factoryId = 'NEW-FACTORY'; auth.user.id = 2; auth.currentRole = 'factory_super_admin';
  route.params.id = '17'; access.canViewPrice = true; access.canWrite = false; costResult = complete(); costFailure = null;
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/cost-breakdown')) return costFailure ? Promise.reject(costFailure) : Promise.resolve({ success: true, data: costResult });
    return Promise.resolve({ success: true, data: url.endsWith('/processing/batches/17') ? { ...legacy } : url.endsWith('/timeline') ? [] : null });
  });
});
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); });
async function openPage() {
  const wrapper = mount(Page, { global: { plugins: [ElementPlus], stubs: { AttachmentList: true, AttachmentUploadButton: true, SupplementalCostsDialog: true } } });
  wrappers.push(wrapper); await flushPromises(); return wrapper;
}
function card(wrapper: VueWrapper) { return wrapper.findAll('.el-card').find(c => c.text().includes('成本明细'))!; }

it('passes batch and actor context to the existing cost dialog and removes it when write permission is revoked', async () => {
  access.canWrite = true; const wrapper = await openPage();
  const dialog = wrapper.findComponent(SupplementalCostsDialog);
  expect(dialog.props('batchNumber')).toBe('BOX-17');
  expect(dialog.props('productionPlanId')).toBe('PLAN-17');
  const initialContext = dialog.props('contextKey');
  await wrapper.findAll('button').find(button => button.text() === '补录设备和其他成本')!.trigger('click');
  expect(dialog.props('modelValue')).toBe(true);
  auth.currentRole = 'quality_manager'; await flushPromises();
  expect(wrapper.findComponent(SupplementalCostsDialog).props('contextKey')).not.toBe(initialContext);
  expect(wrapper.findComponent(SupplementalCostsDialog).props('modelValue')).toBe(false);
  await wrapper.findAll('button').find(button => button.text() === '补录设备和其他成本')!.trigger('click');
  expect(wrapper.findComponent(SupplementalCostsDialog).props('modelValue')).toBe(true);
  access.canWrite = false; await flushPromises();
  expect(wrapper.findComponent(SupplementalCostsDialog).exists()).toBe(false);
  access.canWrite = true; await flushPromises();
  expect(wrapper.findComponent(SupplementalCostsDialog).props('modelValue')).toBe(false);
});

it('shows the authoritative complete cost without borrowing null components from batch snapshots', async () => {
  const wrapper = await openPage(); const text = card(wrapper).text();
  expect(text).toContain('128.33'); expect(text).toContain('6.42');
  expect(text).not.toContain('777,777'); expect(text).not.toContain('999,999');
});
it('keeps a failed cost request visible instead of publishing legacy batch costs', async () => {
  costFailure = new Error('成本服务不可用'); const wrapper = await openPage();
  expect(card(wrapper).text()).toContain('成本服务不可用');
  expect(wrapper.text()).not.toContain('999,999'); expect(wrapper.text()).not.toContain('666,666');
});
it('does not promote no-data to a complete legacy total', async () => {
  costResult = { hasData: false, calculationStatus: 'NO_DATA', costComplete: false };
  const wrapper = await openPage(); expect(card(wrapper).text()).toContain('暂无可核对的成本数据');
  expect(wrapper.text()).not.toContain('999,999'); expect(wrapper.text()).not.toContain('666,666');
});
it('keeps a partial known subtotal separate and translates missing evidence into Chinese', async () => {
  costResult = { ...complete(), calculationStatus: 'PARTIAL', costComplete: false, totalCost: null, perBoxCost: null,
    knownCostSubtotal: 30, knownPerBoxCost: 1.5, missingCostItems: ['RAW_MATERIAL_PRICE:MT-001', 'SOME_NEW_CODE:source-1'] };
  const wrapper = await openPage(); const text = card(wrapper).text();
  expect(text).toContain('已归集成本（未完整）'); expect(text).toContain('30.00'); expect(text).toContain('原料单价');
  expect(text).not.toContain('SOME_NEW_CODE'); expect(wrapper.text()).not.toContain('666,666');
});

it('retries a failed read and replaces the error with the current authoritative evidence', async () => {
  costFailure = new Error('读取失败'); const wrapper = await openPage();
  expect(card(wrapper).text()).toContain('读取失败'); costFailure = null;
  await wrapper.findAll('button').find(b => b.text() === '重试读取成本')!.trigger('click'); await flushPromises();
  expect(card(wrapper).text()).toContain('128.33'); expect(card(wrapper).text()).not.toContain('读取失败');
  expect(state.get.mock.calls.filter(([url]) => String(url).endsWith('/cost-breakdown'))).toHaveLength(2);
});
it('shows confirmed zero and the server cost unit without borrowing the batch unit', async () => {
  costResult = { ...complete(), totalCost: 0, perBoxCost: 0, costDenominatorUnit: 'kg', outputUnit: 'kg', semiFeedCost: 0 };
  const wrapper = await openPage(); const text = card(wrapper).text();
  expect(text).toContain('¥0.00/kg'); expect(text).toContain('半成品/成品投料成本');
  expect(wrapper.text()).not.toContain('666,666');
});
it('honors server price masking even when the response contains unexpected amounts', async () => {
  costResult = { ...complete(), priceMasked: true, totalCost: 987654 };
  const wrapper = await openPage(); expect(card(wrapper).text()).toContain('当前账号无权查看成本金额');
  expect(wrapper.text()).toContain('权限隐藏'); expect(wrapper.text()).not.toContain('987,654');
});
it('shows process secrecy instead of "pending verification" when a protected product hides its cost totals', async () => {
  costResult = { ...complete(), totalCost: null, perBoxCost: null, rawMaterialCost: null, packagingCost: null,
    processSecret: { visibility: 'TOTALS_ONLY', hiddenFields: ['totalCost', 'perBoxCost', 'rawMaterialCost', 'packagingCost'] } };
  const wrapper = await openPage();
  expect(card(wrapper).text()).toContain('该产品工艺已设为保密，成本合计已隐藏');
  expect(card(wrapper).text()).not.toContain('成本口径待核对');
  expect(wrapper.text()).toContain('已保密');
});
it.each([
  { finishedBatchNumber: 'OTHER-BATCH' }, { totalCost: '123.00' }, { hasData: 'true' },
])('rejects malformed or different-batch evidence %s', async change => {
  costResult = { ...complete(), ...change }; const wrapper = await openPage();
  expect(card(wrapper).text()).toContain('成本读取失败'); expect(wrapper.text()).not.toContain('128.33');
  expect(wrapper.text()).not.toContain('999,999');
});
it.each([{ calculationStatus: 'COMPLETE', costComplete: false }, { priceMasked: undefined }, { totalCost: -1 }])('does not publish an unverified complete total %s', async change => {
  costResult = { ...complete(), ...change }; const wrapper = await openPage();
  expect(card(wrapper).text()).toContain('成本口径待核对'); expect(wrapper.text()).not.toContain('128.33');
});
it('does not request prices without price access and discards a pending result when access is revoked', async () => {
  access.canViewPrice = false; const wrapper = await openPage();
  expect(state.get.mock.calls.some(([url]) => String(url).endsWith('/cost-breakdown'))).toBe(false);
  let resolveOld!: (value: unknown) => void;
  state.get.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
  access.canViewPrice = true; await flushPromises();
  expect(resolveOld).toBeTypeOf('function'); access.canViewPrice = false; await flushPromises();
  resolveOld({ success: true, data: complete() }); await flushPromises();
  expect(wrapper.find('[data-testid="batch-cost-evidence"]').exists()).toBe(false);
  expect(wrapper.text()).not.toContain('128.33');
});
it('ignores an older actor response while showing the newly requested result', async () => {
  let resolveOld!: (value: unknown) => void;
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/cost-breakdown')) return new Promise(resolve => { resolveOld = resolve; });
    return Promise.resolve({ success: true, data: url.endsWith('/processing/batches/17') ? { ...legacy } : [] });
  });
  const wrapper = await openPage(); expect(resolveOld).toBeTypeOf('function');
  state.get.mockImplementation(() => Promise.resolve({ success: true, data: complete() }));
  auth.user.id = 5; await flushPromises(); expect(card(wrapper).text()).toContain('128.33');
  resolveOld({ success: true, data: { ...complete(), totalCost: 987654 } }); await flushPromises();
  expect(card(wrapper).text()).toContain('128.33'); expect(wrapper.text()).not.toContain('987,654');
});
it('clears current costs when the route batch changes and reads again only for the matching identity', async () => {
  const wrapper = await openPage(); expect(card(wrapper).text()).toContain('128.33');
  route.params.id = '18'; await flushPromises(); expect(wrapper.text()).not.toContain('128.33');
  expect(state.get.mock.calls.filter(([url]) => String(url).endsWith('/cost-breakdown'))).toHaveLength(1);
  route.params.id = '17'; await flushPromises(); expect(card(wrapper).text()).toContain('128.33');
});
