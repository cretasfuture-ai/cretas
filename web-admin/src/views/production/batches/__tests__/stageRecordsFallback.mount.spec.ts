import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import ElementPlus from 'element-plus';

/**
 * PROTO-YDWM-B4B8 · B7 (批次详情「工序明细」): 味美导入写了 495 条 processing_stage_records,
 * 页面只读 App 逐道报工任务, 一律显示「暂无工序任务」。
 * 约定: 有逐道报工任务用报工; 没有才兼读加工环节记录, 并标明来源。
 */
const state = vi.hoisted(() => ({ get: vi.fn(), tasks: vi.fn() }));
const auth = reactive({ factoryId: 'FOOD_X' });
const route = reactive({ params: { id: '15' }, query: {} });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({
  canViewPrice: false, canWrite: () => false, canAccess: () => true,
}) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => state.get(...args), post: vi.fn() }));
vi.mock('@/api/processProduction', () => ({
  getBatchWip: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getOrderYieldSummary: vi.fn(),
  getBatchWorkProcessTasks: (...args: unknown[]) => state.tasks(...args),
  updateWorkProcessTaskPlan: vi.fn(),
}));
vi.mock('@/api/factory', () => ({ getOperatorUsers: vi.fn() }));
import Detail from '../detail.vue';

const STAGES = [
  { id: 2, stageName: '拌馅', stageOrder: 2, outputWeight: null, inputWeight: null, notes: '[YDWM] 拌馅 9 趟', endTime: '2026-10-01T13:00:00' },
  { id: 1, stageName: '和面（打皮）', stageOrder: 1, inputWeight: 2024.1, outputWeight: null, notes: '[YDWM] 和面 20 趟', endTime: '2026-10-01T12:55:00' },
];

let stages: unknown;
beforeEach(() => {
  vi.clearAllMocks();
  stages = STAGES;
  state.tasks.mockResolvedValue({ success: true, data: [] });
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/processing/batches/15')) {
      return Promise.resolve({ success: true, data: { id: 15, factoryId: 'FOOD_X', batchNumber: 'B-15', status: 'COMPLETED', unit: 'kg' } });
    }
    if (url.endsWith('/processing/batches/15/stages')) return Promise.resolve({ success: true, data: stages });
    if (url.endsWith('/timeline')) return Promise.resolve({ success: true, data: [] });
    return Promise.resolve({ success: true, data: null });
  });
});

async function openPage() {
  const wrapper = mount(Detail, { global: { plugins: [ElementPlus], stubs: {
    AttachmentList: true, AttachmentUploadButton: true, SupplementalCostsDialog: true,
  } } });
  await flushPromises();
  return wrapper;
}

describe('B7 批次详情工序明细兼读加工环节记录', () => {
  it('没有逐道报工任务但有工序记录: 显示记录, 按道序排, 标明来源, 不再显示「暂无工序任务」', async () => {
    const wrapper = await openPage();
    const card = wrapper.get('[data-testid="process-tasks-card"]');
    const fallback = card.get('[data-testid="stage-records-fallback"]');
    expect(fallback.text()).toContain('来源：加工环节记录（非 App 逐道报工）');
    const rows = fallback.findAll('tbody tr').map((r) => r.text());
    expect(rows).toHaveLength(2);
    expect(rows[0]).toContain('和面（打皮）');
    expect(rows[0]).toContain('2,024.1 kg');
    expect(rows[1]).toContain('拌馅');
    expect(rows[1]).toContain('—');
    expect(card.text()).not.toContain('暂无工序任务');
  });

  it('阴性对照: 有逐道报工任务时用报工, 不叠加工序记录', async () => {
    state.tasks.mockResolvedValue({ success: true, data: [
      { id: 1, processOrder: 1, processName: '和面', status: 'COMPLETED' },
    ] });
    const wrapper = await openPage();
    const card = wrapper.get('[data-testid="process-tasks-card"]');
    expect(card.find('[data-testid="stage-records-fallback"]').exists()).toBe(false);
    expect(card.text()).toContain('和面');
  });

  it('阴性对照: 两边都没有 ⇒ 仍是原来的诚实空态', async () => {
    stages = [];
    const wrapper = await openPage();
    const card = wrapper.get('[data-testid="process-tasks-card"]');
    expect(card.find('[data-testid="stage-records-fallback"]').exists()).toBe(false);
    expect(card.text()).toContain('暂无工序任务');
  });
});
