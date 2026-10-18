import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from '@/store/modules/permission';
function setAccess(granted: boolean) {
  usePermissionStore().$patch({ dbPermissions: { production: granted ? 'r' : '-', analytics: 'rw' } });
}
import Results from '../ProductProductionResults.vue';
import { getProductProductionMetrics, type ProductProductionMetrics } from '@/api/productProductionMetrics';

vi.mock('@/api/productProductionMetrics', () => ({ getProductProductionMetrics: vi.fn() }));
const push = vi.fn();
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }));
const fixture = (): ProductProductionMetrics => ({
  periodStart: '2026-10-16', periodEnd: '2026-10-16', sourceVersion: 'actual-flow-v2', products: [
    { productTypeId: 'sku-a', productName: '散装成品', batchCount: 1, batchNumbers: ['A-001'], yieldRate: 80,
      goodOutputRate: 88.8889, totalCost: 200, unitCost: 12.5, costMasked: false },
    { productTypeId: 'sku-b', productName: '盒装成品', batchCount: 1, batchNumbers: ['B-001'], yieldRate: 100,
      goodOutputRate: 90.9091, totalCost: 300, unitCost: 15, costMasked: false },
  ],
});
const wrappers: VueWrapper[] = [];
function render(canRead = true) {
  setAccess(canRead);
  const wrapper = mount(Results, { props: { factoryId: 'F-TEST', actorKey: 'actor-1', period: ['2026-10-16', '2026-10-16'] }, global: { plugins: [ElementPlus] } });
  wrappers.push(wrapper); return wrapper;
}
beforeEach(() => {
  setActivePinia(createPinia()); vi.clearAllMocks(); vi.mocked(getProductProductionMetrics).mockResolvedValue(fixture()); });
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); });

it('shows distinct product results using the requested period and opens the exact boxed batch trace', async () => {
  const wrapper = render(); await flushPromises();
  expect(getProductProductionMetrics).toHaveBeenCalledWith('F-TEST', ['2026-10-16', '2026-10-16']);
  expect(wrapper.text()).toContain('散装成品'); expect(wrapper.text()).toContain('盒装成品');
  expect(wrapper.text()).toContain('88.89%'); expect(wrapper.text()).toContain('90.91%');
  const view = wrapper.findAll('button').filter(b => b.text() === '查看批次');
  await view[1]!.trigger('click'); await flushPromises();
  const link = wrapper.findAll('button').find(b => b.text().includes('B-001'));
  expect(link).toBeDefined(); await link!.trigger('click');
  expect(push).toHaveBeenCalledWith({ path: '/production/traceability', query: { batchNumber: 'B-001' } });
});
it('keeps zero values, missing-data values and price masking distinct', async () => {
  const data = fixture();
  Object.assign(data.products[0]!, { yieldRate: 0, goodOutputRate: null, totalCost: 0, unitCost: null });
  Object.assign(data.products[1]!, { totalCost: 987654, unitCost: 123456, costMasked: true });
  vi.mocked(getProductProductionMetrics).mockResolvedValue(data);
  const wrapper = render(); await flushPromises();
  expect(wrapper.text()).toContain('0%'); expect(wrapper.text()).toContain('待补数据');
  expect(wrapper.text()).toContain('权限隐藏');
  expect(wrapper.text()).not.toContain('987,654'); expect(wrapper.text()).not.toContain('123,456');
});
it('distinguishes a successful empty period from a failed query', async () => {
  vi.mocked(getProductProductionMetrics).mockResolvedValue({ ...fixture(), products: [] });
  const wrapper = render(); await flushPromises();
  expect(wrapper.text()).toContain('该期间没有已完工且已结单');
  vi.mocked(getProductProductionMetrics).mockRejectedValue(new Error('接口不可用'));
  await wrapper.findAll('button').find(b => b.text() === '刷新成品结果')!.trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('接口不可用'); expect(wrapper.text()).not.toContain('该期间没有已完工');
});
it.each(['period', 'duplicate-product', 'duplicate-batch', 'count', 'source', 'bad-number'])('rejects ambiguous or incompatible source data: %s', async mode => {
  const data = fixture();
  if (mode === 'period') data.periodStart = '2026-10-01';
  if (mode === 'source') data.sourceVersion = 'legacy';
  if (mode === 'duplicate-product') data.products[1]!.productTypeId = data.products[0]!.productTypeId;
  if (mode === 'duplicate-batch') data.products[1]!.batchNumbers = ['A-001'];
  if (mode === 'count') data.products[0]!.batchCount = 2;
  if (mode === 'bad-number') data.products[0]!.totalCost = -1;
  vi.mocked(getProductProductionMetrics).mockResolvedValue(data);
  const wrapper = render(); await flushPromises();
  expect(wrapper.text()).toContain('读取失败'); expect(wrapper.text()).not.toContain('散装成品');
});
it('clears data on permission revocation and never queries without production access', async () => {
  const wrapper = render(false); await flushPromises(); expect(getProductProductionMetrics).not.toHaveBeenCalled();
  setAccess(true); await flushPromises(); expect(wrapper.text()).toContain('盒装成品');
  setAccess(false); await flushPromises();
  expect(wrapper.text()).not.toContain('盒装成品'); expect(getProductProductionMetrics).toHaveBeenCalledTimes(1);
});
it.each(['actorKey', 'factoryId', 'period'] as const)('ignores an old asynchronous response after %s changes', async field => {
  let old!: (data: ProductProductionMetrics) => void;
  vi.mocked(getProductProductionMetrics).mockReturnValueOnce(new Promise(resolve => { old = resolve; }));
  const wrapper = render();
  const next = fixture();
  if (field === 'period') next.periodStart = next.periodEnd = '2026-10-17';
  vi.mocked(getProductProductionMetrics).mockResolvedValueOnce(next);
  await wrapper.setProps(field === 'period' ? { period: ['2026-10-17', '2026-10-17'] } : { [field]: 'new-context' });
  const stale = fixture(); stale.products[0]!.productName = '旧工厂成品';
  old(stale); await flushPromises(); expect(wrapper.text()).not.toContain('旧工厂成品');
  expect(wrapper.text()).toContain('盒装成品'); expect(getProductProductionMetrics).toHaveBeenCalledTimes(2);
});
it('does not query invalid, incomplete or more-than-90-day periods', async () => {
  const wrapper = render(false);
  setAccess(true); await wrapper.setProps({ period: null }); await flushPromises();
  await wrapper.setProps({ period: ['2026-01-01', '2026-10-16'] }); await flushPromises();
  expect(getProductProductionMetrics).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('不超过90天');
});

it('rejects a late response after authoritative production access is revoked', async () => {
  let resolve!: (value: ProductProductionMetrics) => void;
  vi.mocked(getProductProductionMetrics).mockReturnValueOnce(new Promise(done => { resolve = done; }));
  const wrapper = render(); await flushPromises();
  expect(getProductProductionMetrics).toHaveBeenCalledTimes(1);
  setAccess(false); await flushPromises();
  resolve(fixture()); await flushPromises();
  expect(wrapper.text()).toContain('没有生产查看权限');
  expect(wrapper.text()).not.toContain('盒装成品');
  expect(getProductProductionMetrics).toHaveBeenCalledTimes(1);
});
