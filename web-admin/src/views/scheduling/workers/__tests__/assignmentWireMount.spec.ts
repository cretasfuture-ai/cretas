import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Assignment from '../assignment.vue';

const box = { template: '<div><slot name="header"/><slot/></div>' };
const button = { template: '<button><slot/></button>' };

const api = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(),
}));
vi.mock('@/api/request', () => ({ get: api.get, post: api.post, put: vi.fn(), del: vi.fn() }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-REAL' }) }));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() }, ElMessageBox: { confirm: vi.fn() } }));

describe('worker assignment real wire mount', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    const plan = { id: 'PLAN-1', planDate: '2026-10-19', totalBatches: 1, lineSchedules: [{ id: 'S-1', planId: 'PLAN-1', productionLineId: 'LINE-1', productionLineName: '一号线', batchId: 9, batchNumber: 'B-1', sequenceOrder: 1, plannedStartTime: '2026-10-19T08:00:00', plannedEndTime: '2026-10-19T09:00:00', plannedQuantity: 120, assignedWorkers: 3, completedQuantity: 20, status: 'pending', workerAssignments: [] }] };
    api.get.mockImplementation((url: string) => {
      if (url.includes('/scheduling/capabilities')) return Promise.resolve({ success: true, data: { workerOptimization: { status: 'UNAVAILABLE', reason: '自动人员优化暂不可用，请手动指派。' } } });
      if (url.includes('/users/directory')) return Promise.resolve({ success: true, data: [{ id: 88, fullName: '真实工人甲', department: '包装组', roleCode: 'operator' }] });
      return Promise.resolve({ success: true, data: url.endsWith('/plans') ? { content: [plan] } : plan });
    });
    api.post.mockResolvedValue({ success: true, data: [{ id: 'A-1', scheduleId: 'S-1', userId: 88, status: 'assigned' }] });
  });

  it('renders real target/count and calls manual assignment with workerIds', async () => {
    const wrapper = mount(Assignment, { global: { directives: { loading: () => undefined }, stubs: { ElCard: box, ElButton: button, ElSelect: box, ElOption: box, ElAlert: box, ElIcon: box, ElTag: box, ElEmpty: box, ElCheckbox: box, ElCheckboxGroup: box, ElDialog: box } } });
    await flushPromises();
    expect(wrapper.text()).toContain('真实工人甲');
    expect(wrapper.text()).toContain('目标数量：120');
    expect(wrapper.text()).toContain('已分配人数：3');
    const state = wrapper.vm as unknown as { selectedScheduleId: string; selectedWorkerIds: number[]; handleAssign: () => Promise<void> };
    state.selectedScheduleId = 'S-1';
    state.selectedWorkerIds = [88];
    await state.handleAssign();
    expect(api.post).toHaveBeenCalledWith('/F-REAL/scheduling/workers/assign', { scheduleId: 'S-1', workerIds: [88] });
  });
});
