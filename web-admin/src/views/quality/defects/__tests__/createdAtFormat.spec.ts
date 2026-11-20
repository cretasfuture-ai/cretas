import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({ get: vi.fn() }));
const route = reactive<{ query: Record<string, string> }>({ query: {} });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/composables/useListSummary', () => ({ useListSummary: () => ({ summary: null, loading: false }) }));
vi.mock('@/composables/useCreateAndReturn', () => ({ useCreateAndReturn: () => ({ goCreate: vi.fn() }) }));
vi.mock('@/api/request', () => ({
  get: (...a: unknown[]) => state.get(...a), post: vi.fn(), put: vi.fn(),
}));
import DefectsPage from '../list.vue';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

beforeEach(() => {
  state.get.mockImplementation((url: string) => Promise.resolve({
    success: true,
    data: url.endsWith('/quality-defects')
      ? { content: [{ id: 'd1', status: 'OPEN', defectType: 'OTHER', quantity: 1, createdAt: '2026-11-18T16:11:47.553691' }], totalElements: 1 }
      : [],
  }));
});

describe('不良列表「登记时间」', () => {
  it('显示 YYYY-MM-DD HH:mm:ss, 不露 ISO 的 T 和微秒', async () => {
    const w = mount(DefectsPage, { global: { plugins: [ElementPlus], stubs: nuxtUiStubs } });
    await flushPromises();
    expect(w.text()).toContain('2026-11-18 16:11:47');
    expect(w.text()).not.toContain('2026-11-18T16:11:47');
    expect(w.text()).not.toContain('553691');
    w.unmount();
  });
});
