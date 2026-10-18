import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import ElementPlus from 'element-plus';

const state = vi.hoisted(() => ({ get: vi.fn(), push: vi.fn(), qualityAccess: true }));
const auth = reactive({ factoryId: 'NEW-FACTORY' });
const route = reactive({ params: { id: '15' }, query: {} });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: state.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({
  canViewPrice: true, canWrite: () => false,
  canAccess: (module: string) => module !== 'quality' || state.qualityAccess,
}) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => state.get(...args), post: vi.fn() }));
vi.mock('@/api/processProduction', () => ({
  getBatchWip: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getOrderYieldSummary: vi.fn(),
  getBatchWorkProcessTasks: vi.fn().mockResolvedValue({ success: true, data: [] }),
  updateWorkProcessTaskPlan: vi.fn(),
}));
vi.mock('@/api/factory', () => ({ getOperatorUsers: vi.fn() }));
import Page from '../detail.vue';

let batch: Record<string, unknown>;
beforeEach(() => {
  vi.clearAllMocks();
  auth.factoryId = 'NEW-FACTORY'; route.params.id = '15'; state.qualityAccess = true;
  batch = { id: 15, factoryId: 'NEW-FACTORY', batchNumber: 'BOX-15',
    status: 'COMPLETED', unit: '盒', goodQuantity: 20, defectQuantity: 2, qualityStatus: null };
  state.get.mockImplementation((url: string) => Promise.resolve({ success: true,
    data: url.endsWith('/processing/batches/15') ? batch : url.endsWith('/timeline') ? [] : null }));
});
async function openPage() {
  const wrapper = mount(Page, { global: { plugins: [ElementPlus], stubs: {
    AttachmentList: true, AttachmentUploadButton: true, SupplementalCostsDialog: true,
  } } });
  await flushPromises();
  return wrapper;
}

describe('批次详情复用既有质检入口', () => {
  it('未登记结论时不暗示通过，并用真实批次进入原质检页面', async () => {
    const wrapper = await openPage();
    expect(wrapper.text()).toContain('尚未登记质检结论');
    expect(wrapper.text()).toContain('生产清点的合格数量不等于质检放行');
    const button = wrapper.findAll('button').find(b => b.text() === '查看本批次质检');
    expect(button).toBeDefined(); await button!.trigger('click');
    expect(state.push).toHaveBeenCalledWith({ path: '/quality/inspections', query: { batchId: '15', batchNumber: 'BOX-15' } });
  });
  it.each([['PENDING_INSPECTION', '待检验'], ['NEEDS_REVIEW', '待审核'],
    ['PASSED', '已通过'], ['QUALIFIED', '合格'], ['FAILED', '不合格'],
    ['REWORKING', '返工中'], ['REWORK_COMPLETED', '返工完成，待复检'], ['SCRAPPED', '已报废']])(
    '将服务端状态 %s 显示为中文 %s', async (status, label) => {
      batch.qualityStatus = status;
      const wrapper = await openPage();
      const quality = wrapper.find('[data-testid="batch-quality-evidence"]');
      expect(quality.text()).toContain(label);
      expect(quality.text()).not.toContain(status);
    });
  it('没有质量查看权限时不提供入口', async () => {
    state.qualityAccess = false;
    const wrapper = await openPage();
    expect(wrapper.findAll('button').some(b => b.text() === '查看本批次质检')).toBe(false);
  });
  it.each([['factoryId', 'OTHER-FACTORY'], ['id', 99]])('拒绝把不匹配的 %s 带进质检页面', async (key, value) => {
    batch[key] = value;
    const wrapper = await openPage();
    expect(wrapper.findAll('button').some(b => b.text() === '查看本批次质检')).toBe(false);
    expect(state.push).not.toHaveBeenCalled();
  });
});
