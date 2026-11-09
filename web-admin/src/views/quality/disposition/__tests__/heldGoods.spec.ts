/**
 * Owner 2026-11-09: 已放行批次的计划里还有成品在隔离 (当时分不清归属等) —— 只在这种批次上给「重新核对并放行成品」,
 * 一个人点了就让后端按现在的数据重跑计划级放行; 还有成品留下时提示不能一闪而过。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus, { ElMessageBox } from 'element-plus';

const mocks = vi.hoisted(() => ({ held: vi.fn(), release: vi.fn(), canWrite: { value: true } }));
const route = reactive({ query: {} as Record<string, string> });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-HELD', user: { id: 7, username: 'qc' } }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => mocks.canWrite.value }) }));
vi.mock('@/api/request', () => ({ get: vi.fn().mockResolvedValue({ success: true, data: { content: [] } }) }));
vi.mock('@/api/quality-disposition', () => ({
  getAvailableActions: vi.fn().mockResolvedValue([]),
  getPendingDispositions: vi.fn().mockResolvedValue([]),
  evaluateDisposition: vi.fn(),
  executeDisposition: vi.fn(),
  approveDisposition: vi.fn(),
  listHeldGoodsBatches: (...args: unknown[]) => mocks.held(...args),
  releaseHeldGoods: (...args: unknown[]) => mocks.release(...args),
}));
import Page from '../list.vue';

const row = { productionBatchId: 100, batchNumber: 'PB-100', productionPlanId: 'PLAN-1', productName: '卤猪蹄',
  qualityStatus: 'PASSED', heldGoods: 2 };

beforeEach(() => {
  vi.clearAllMocks();
  mocks.canWrite.value = true;
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never);
});

describe('成品仍在隔离的已放行批次', () => {
  it('没有这类批次时不出现这个页签', async () => {
    mocks.held.mockResolvedValue([]);
    const wrapper = mount(Page, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.text()).not.toContain('成品仍在隔离');
    expect(wrapper.find('[data-testid="disposition-release-held-goods"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('有这类批次时出现页签和按钮, 点了只带批次 id 调后端; 放完重新拉列表', async () => {
    mocks.held.mockResolvedValue([row]);
    mocks.release.mockResolvedValue({ status: 'EXECUTED', finishedGoodsHeld: 1,
      message: '已重新核对: 放行 1 个成品批次；仍有 1 个成品批次在隔离中，不可销售（其中报废批次的成品 1 个）' });
    const wrapper = mount(Page, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.text()).toContain('成品仍在隔离 (1)');
    const button = wrapper.find('[data-testid="disposition-release-held-goods"]');
    expect(button.exists()).toBe(true);

    await button.trigger('click');
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('PB-100'), '重新核对并放行成品',
      expect.anything());
    expect(mocks.release).toHaveBeenCalledWith('F-HELD', 100);
    expect(mocks.held).toHaveBeenCalledTimes(2);   // 放完重新拉一遍
    wrapper.unmount();
  });

  it('没有写权限只看得到, 没有按钮', async () => {
    mocks.canWrite.value = false;
    mocks.held.mockResolvedValue([row]);
    const wrapper = mount(Page, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.text()).toContain('成品仍在隔离 (1)');
    expect(wrapper.find('[data-testid="disposition-release-held-goods"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
