import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

const mocks = vi.hoisted(() => ({ get: vi.fn(), evaluate: vi.fn(), execute: vi.fn() }));
const route = reactive({ query: { batchId: '7', inspectionId: 'qi-7' } });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'VISION-ENTRY-A', user: { id: 93001, username: 'daily_factory_a' } }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => mocks.get(...args) }));
vi.mock('@/api/quality-disposition', () => ({
  getAvailableActions: vi.fn().mockResolvedValue([{ actionCode: 'HOLD', actionName: '隔离待定' }]),
  getPendingDispositions: vi.fn().mockResolvedValue([]),
  evaluateDisposition: (...args: unknown[]) => mocks.evaluate(...args),
  executeDisposition: (...args: unknown[]) => mocks.execute(...args),
  approveDisposition: vi.fn(),
}));
import Page from '../list.vue';

const inspection = (id: number) => ({ id: `qi-${id}`, productionBatchId: id, inspectorId: 93001,
  inspectionDate: '2026-10-16', sampleSize: 2, passCount: 0, failCount: 2, result: 'FAIL', passRate: 0 });
beforeEach(() => {
  vi.clearAllMocks();
  route.query.batchId = '7';
  route.query.inspectionId = 'qi-7';
  mocks.get.mockImplementation((path: string, options?: { params?: { batchId?: string } }) => Promise.resolve({ success: true,
    data: path.includes('/processing/batches/')
      ? { id: Number(path.split('/').pop()), batchNumber: `PB-${path.split('/').pop()}`, productName: '合成产品' }
      : { content: [inspection(Number(options?.params?.batchId))] } }));
  mocks.evaluate.mockResolvedValue({ recommendedAction: 'HOLD', requiresApproval: true, confidence: 0.85,
    reason: '继续隔离并复核', alternativeActions: [], inspectionSummary: {} });
  mocks.execute.mockResolvedValue({ approvalInitiated: true, message: '已提交审批' });
});

describe('异常复核真实组件批次初始化', () => {
  it('首次从批次入口挂载即加载当前质检并自动打开，提交使用当前登录人', async () => {
    const wrapper = mount(Page, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(mocks.get).toHaveBeenCalledWith('/VISION-ENTRY-A/processing/batches/7');
    expect(mocks.evaluate).toHaveBeenCalledWith('VISION-ENTRY-A', expect.objectContaining({ inspectionId: 'qi-7', productionBatchId: 7 }));
    expect(wrapper.text()).toContain('当前批次：PB-7');
    const reason = wrapper.find('textarea');
    await reason.setValue('本地复核，继续隔离');
    await wrapper.findAll('button').find(button => button.text() === '确认处置')!.trigger('click');
    await flushPromises();
    expect(mocks.execute).toHaveBeenCalledWith('VISION-ENTRY-A', expect.objectContaining({ batchId: 7, inspectionId: 'qi-7', executorId: 93001 }));
    wrapper.unmount();
  });

  it('切到另一批后旧质检列表晚到，不替换新批列表或打开旧质检', async () => {
    const regularGet = mocks.get.getMockImplementation()!;
    let resolveOld!: (value: unknown) => void;
    mocks.get.mockImplementation((path: string, options?: { params?: { batchId?: string } }) => {
      if (path.endsWith('/quality/inspections') && options?.params?.batchId === '7') {
        return new Promise(resolve => { resolveOld = resolve; });
      }
      return regularGet(path, options);
    });
    const wrapper = mount(Page, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    route.query.batchId = '8';
    route.query.inspectionId = 'qi-8';
    await flushPromises();
    resolveOld({ success: true, data: { content: [inspection(7)] } });
    await flushPromises();
    expect(wrapper.text()).toContain('当前批次：PB-8');
    expect(mocks.evaluate.mock.calls.every(call => call[1].inspectionId === 'qi-8')).toBe(true);
    expect(mocks.evaluate).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });
});
