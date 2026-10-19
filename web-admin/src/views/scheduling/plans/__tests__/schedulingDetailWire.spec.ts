import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Detail from '../detail.vue';
import { ElTable, ElTableColumn } from 'element-plus';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/api/request', () => ({ get, post: vi.fn(), put: vi.fn(), del: vi.fn() }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-REAL' }) }));
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: 'PLAN-1' } }), useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/utils/echarts', () => ({ default: { init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), on: vi.fn() })) } }));

describe('scheduling detail real wire mount', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockImplementation((url: string) => url.includes('/probabilities')
      ? Promise.resolve({ success: true, data: [] })
      : Promise.resolve({ success: true, data: {
        id: 'PLAN-1', planDate: '2026-10-19', status: 'DRAFT', lineSchedules: [{
          id: 'S-1', planId: 'PLAN-1', productionLineId: 'LINE-1', productionLineName: '一号线', batchId: 9,
          batchNumber: 'B-1', sequenceOrder: 1, plannedStartTime: '2026-10-19T08:00:00', plannedEndTime: '2026-10-19T09:00:00',
          plannedQuantity: 120, assignedWorkers: 3, completedQuantity: 20, status: 'pending', workerAssignments: []
        }]
      } }));
  });

  it('mounts the real API mapper and displays DTO target quantity and worker count', async () => {
    const box = { template: '<div><slot name="header"/><slot/></div>' };
    const wrapper = mount(Detail, { global: {
      directives: { loading: () => undefined },
      stubs: { ElCard: box, ElTable, ElTableColumn, ElProgress: box, ElTag: box, ElButton: box, ElDialog: box, ElForm: box, ElFormItem: box, ElInputNumber: box, ElEmpty: box, ElIcon: box }
    } });
    await flushPromises();
    const tableText = wrapper.find('.el-table').text();
    expect(tableText).toContain('20/120');
    expect(tableText).toContain('3');
  });
});
