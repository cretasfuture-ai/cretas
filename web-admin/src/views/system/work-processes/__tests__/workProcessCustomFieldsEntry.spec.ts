import { nextTick } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WorkProcessPage from '../index.vue';
import type { WorkProcessItem } from '@/api/processProduction';

const state = vi.hoisted(() => ({
  canWriteSystem: true,
  canWriteProduction: true,
  roleLevel: 10,
}));
const api = vi.hoisted(() => ({
  getWorkProcesses: vi.fn(),
  getWorkProcessCategories: vi.fn(),
  getWorkProcessDuplicates: vi.fn(),
  updateWorkProcess: vi.fn(),
  createWorkProcess: vi.fn(),
  deleteWorkProcess: vi.fn(),
  toggleWorkProcessStatus: vi.fn(),
  governWorkProcessDuplicates: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    factoryId: 'F006',
    hasMinLevel: (level: number) => state.roleLevel <= level,
  }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: (module: string) => module === 'production' ? state.canWriteProduction : state.canWriteSystem,
  }),
}));
vi.mock('@/api/processProduction', () => api);
vi.mock('../WorkProcessAIChatPanel.vue', () => ({ default: { template: '<div data-testid="ai-panel" />' } }));

const row: WorkProcessItem = {
  id: 'WP-42', processName: '灌装', processCategory: '包装', estimatedMinutes: 18,
  standardYieldMin: null, standardYieldMax: null, needsInput: true,
  defaultOutputMaterialKind: 'FINISHED_GOOD', semiFinishedOutputCode: null,
  standardHourlyRate: 35, isActive: true, createdAt: '2026-01-01', updatedAt: '2026-01-02',
  customFieldSchema: [],
};

beforeEach(() => {
  state.canWriteSystem = true;
  state.canWriteProduction = true;
  state.roleLevel = 10;
  vi.clearAllMocks();
  api.getWorkProcesses.mockResolvedValue({ success: true, data: { content: [row] } });
  api.getWorkProcessCategories.mockResolvedValue({ success: true, data: ['包装'] });
  api.getWorkProcessDuplicates.mockResolvedValue({ success: true, data: [] });
});

describe('work process custom fields entry', () => {
  it('shows the write-row entry on the mounted management table and opens the dialog', async () => {
    const wrapper = mount(WorkProcessPage, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    });
    await flushPromises();

    const button = wrapper.find('[data-testid="work-process-custom-fields-button"]');
    expect(button.exists()).toBe(true);
    await button.trigger('click');
    await nextTick();
    expect(document.body.querySelector('[data-testid="work-process-custom-fields-dialog"]')).not.toBeNull();
    wrapper.unmount();
  });

  it('does not expose the row write entry when system write permission is revoked', async () => {
    state.canWriteSystem = false;
    state.canWriteProduction = false;
    const wrapper = mount(WorkProcessPage, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    });
    await flushPromises();
    expect(wrapper.find('[data-testid="work-process-custom-fields-button"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('keeps the custom-field entry for a production manager even without system write', async () => {
    state.canWriteSystem = false;
    state.canWriteProduction = true;
    state.roleLevel = 10;
    const wrapper = mount(WorkProcessPage, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    });
    await flushPromises();
    expect(wrapper.find('[data-testid="work-process-custom-fields-button"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('hides the custom-field entry for system-only or ordinary operator access', async () => {
    state.canWriteSystem = true;
    state.canWriteProduction = false;
    state.roleLevel = 10;
    const systemOnly = mount(WorkProcessPage, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    });
    await flushPromises();
    expect(systemOnly.find('[data-testid="work-process-custom-fields-button"]').exists()).toBe(false);
    systemOnly.unmount();

    state.canWriteProduction = true;
    state.roleLevel = 30;
    const operator = mount(WorkProcessPage, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    });
    await flushPromises();
    expect(operator.find('[data-testid="work-process-custom-fields-button"]').exists()).toBe(false);
    operator.unmount();
  });
});
