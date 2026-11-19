import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import ElementPlus from 'element-plus';

/**
 * PROTO-YDWM-B4B8 · B4/B5 —— 味美导入批次: 「计划数量」其实是理论产能; 完成比例说明与数值口径不一致。
 *
 * 真实入口: 批次详情 detail.vue / 批次列表 list.vue 挂载渲染。
 * 数据取自味美 261015-210061 的实测形状: 理论产能 8680 kg、良品 6592、不良 103、
 * 导入写进 efficiency 列的是良品÷计划 = 75.95 (含不良应为 77.1%)。
 */
const state = vi.hoisted(() => ({ get: vi.fn(), push: vi.fn() }));
const auth = reactive({ factoryId: 'FOOD_X' });
const route = reactive({ params: { id: '15' }, query: {} });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: state.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({
  canViewPrice: false, canWrite: () => false, canAccess: () => true,
}) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => state.get(...args), post: vi.fn() }));
vi.mock('@/api/printApi', () => ({ safePrint: vi.fn() }));
vi.mock('@/api/processProduction', () => ({
  getBatchWip: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getOrderYieldSummary: vi.fn(),
  getBatchWorkProcessTasks: vi.fn().mockResolvedValue({ success: true, data: [] }),
  updateWorkProcessTaskPlan: vi.fn(),
}));
vi.mock('@/api/factory', () => ({ getOperatorUsers: vi.fn() }));
import Detail from '../detail.vue';
import List from '../list.vue';

const importedBatch = {
  id: 15, factoryId: 'FOOD_X', batchNumber: '261015-210061', status: 'COMPLETED',
  unit: 'kg', plannedUnit: 'kg', plannedQuantity: 8680, actualQuantity: 6695, goodQuantity: 6592,
  defectQuantity: 103, yieldRate: 98.46, efficiency: 75.95,
  customFields: { source: 'YDWM-OCT2026', theoryKg: 8680 },
};
const planBatch = {
  id: 16, factoryId: 'FOOD_X', batchNumber: 'PLAN-16', status: 'COMPLETED',
  unit: 'kg', plannedUnit: 'kg', plannedQuantity: 1000, actualQuantity: 900, goodQuantity: 900,
  defectQuantity: 0, yieldRate: 100, efficiency: 90, customFields: {},
};
const noPlanBatch = { ...planBatch, id: 17, batchNumber: 'NOPLAN-17', plannedQuantity: null, efficiency: null };

let detailBatch: Record<string, unknown>;
beforeEach(() => {
  vi.clearAllMocks();
  detailBatch = importedBatch;
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/processing/batches/15')) return Promise.resolve({ success: true, data: detailBatch });
    if (url.endsWith('/timeline')) return Promise.resolve({ success: true, data: [] });
    if (url.endsWith('/processing/batches-with-clerk-wip')) {
      return Promise.resolve({ success: true, data: { content: listRows, totalElements: listRows.length } });
    }
    return Promise.resolve({ success: true, data: null });
  });
});
let listRows: Record<string, unknown>[] = [];

async function openDetail() {
  const wrapper = mount(Detail, { global: { plugins: [ElementPlus], stubs: {
    AttachmentList: true, AttachmentUploadButton: true, SupplementalCostsDialog: true,
  } } });
  await flushPromises();
  return wrapper;
}
async function openList() {
  const wrapper = mount(List, { global: { plugins: [ElementPlus], stubs: {
    teleport: true, transition: false, ConceptDisambiguationAlert: true, AiEntryDrawer: true, RowActionMenu: true,
  } } });
  await flushPromises();
  return wrapper;
}

describe('B4 批次详情: 计划数量列按数据真实含义标注', () => {
  it('导入批次 (planned=theoryKg): KPI 标「理论产能」, 不叫计划数量', async () => {
    const wrapper = await openDetail();
    expect(wrapper.find('[data-testid="batch-planned-label"]').text()).toBe('理论产能');
    expect(wrapper.find('[data-testid="batch-planned-value"]').text()).toBe('8,680');
  });

  it('阴性对照: 正常计划批次仍叫「计划数量」', async () => {
    detailBatch = planBatch; route.params.id = '15';
    state.get.mockImplementation((url: string) => Promise.resolve({ success: true,
      data: url.endsWith('/processing/batches/15') ? planBatch : url.endsWith('/timeline') ? [] : null }));
    const wrapper = await openDetail();
    expect(wrapper.find('[data-testid="batch-planned-label"]').text()).toBe('计划数量');
  });

  it('无计划数量: 值位显示 —, 不显示 0', async () => {
    state.get.mockImplementation((url: string) => Promise.resolve({ success: true,
      data: url.endsWith('/processing/batches/15') ? { ...noPlanBatch, id: 15 } : url.endsWith('/timeline') ? [] : null }));
    const wrapper = await openDetail();
    expect(wrapper.find('[data-testid="batch-planned-value"]').text()).toBe('—');
  });
});

describe('B5 批次详情: 总产量完成比例 说明与数值同一口径', () => {
  it('说明写含不良总产量 ÷ 理论产能, 数值就是 6695/8680 = 77.1% (不是库里的良品÷计划 76.0%)', async () => {
    const wrapper = await openDetail();
    const text = wrapper.text();
    expect(text).toContain('含不良总产量 ÷ 理论产能');
    expect(text).toContain('77.1%（含不良总产量 ÷ 理论产能');
    expect(text).not.toContain('76.0%');
    expect(text).not.toContain('75.9%');
  });
});

describe('B4 批次列表: 列头与单元格', () => {
  it('全是理论产能: 列头「理论产能」; 无计划数量的行单元格空着', async () => {
    listRows = [importedBatch, { ...importedBatch, id: 18, batchNumber: 'X-18', plannedQuantity: null, customFields: {} }];
    const wrapper = await openList();
    const headers = wrapper.findAll('th').map((n) => n.text());
    expect(headers.some((h) => h.includes('理论产能'))).toBe(true);
    expect(headers.some((h) => h.includes('计划数量'))).toBe(false);
  });

  it('混合: 列头两个名字都写, 理论产能的行带标注, 正常计划的行不带', async () => {
    listRows = [importedBatch, planBatch];
    const wrapper = await openList();
    expect(wrapper.findAll('th').some((n) => n.text().includes('计划数量 / 理论产能'))).toBe(true);
    expect(wrapper.findAll('[data-testid="planned-basis-note"]')).toHaveLength(1);
  });

  it('阴性对照: 只有正常计划批次时列头仍是「计划数量」', async () => {
    listRows = [planBatch];
    const wrapper = await openList();
    expect(wrapper.findAll('th').some((n) => n.text().includes('计划数量'))).toBe(true);
    expect(wrapper.findAll('th').some((n) => n.text().includes('理论产能'))).toBe(false);
  });
});
