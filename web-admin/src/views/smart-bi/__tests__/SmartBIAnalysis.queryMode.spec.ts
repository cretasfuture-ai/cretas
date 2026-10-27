import { reactive } from 'vue';
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({
  route: null as { query: Record<string, unknown> } | null,
  auth: { factoryId: 'F006', factoryType: 'FACTORY', businessDomain: 'FACTORY' },
  history: vi.fn(), enrich: vi.fn(), health: vi.fn(),
}));
vi.mock('vue-router', () => ({ useRoute: () => h.route }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => h.auth }));
vi.mock('@/store/modules/app', () => ({ useAppStore: () => ({ theme: 'light' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }),
}));
vi.mock('@/api/smartbi', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/smartbi')>(),
  getUploadHistory: h.history, enrichSheetAnalysis: h.enrich, checkPythonHealth: h.health,
}));
vi.mock('@/utils/demo-cache', () => ({ saveDemoCache: vi.fn(), loadDemoCache: () => null }));
vi.mock('@/utils/echarts', () => ({
  default: { getInstanceByDom: vi.fn(), connect: vi.fn(), disconnect: vi.fn(), dispose: vi.fn(), init: vi.fn() },
}));
vi.mock('../AIQuery.vue', () => ({ default: { name: 'AIQuery', template: '<div />' } }));
vi.mock('@/components/smartbi/ShortcutsHelpOverlay.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/components/smartbi/DemoTour.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/components/smartbi/YoYMoMComparisonChart.vue', () => ({ default: { template: '<div />' } }));

import SmartBIAnalysis from '../SmartBIAnalysis.vue';

let page: VueWrapper | undefined;
beforeEach(() => {
  h.auth.factoryType = 'FACTORY';
  h.auth.businessDomain = 'FACTORY';
  h.route = reactive({ query: { tab: 'query', mode: 'factory' } });
  h.history.mockReset().mockResolvedValue({ success: true, data: [] });
  h.enrich.mockReset().mockResolvedValue({ success: true });
  h.health.mockReset().mockResolvedValue({ success: true, data: { available: true } });
});
afterEach(() => { page?.unmount(); page = undefined; vi.restoreAllMocks(); });

async function mountParent() {
  page = shallowMount(SmartBIAnalysis, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return page;
}

describe('factory question mode in the real SmartBIAnalysis parent', () => {
  it.each(['FACTORY', 'RESTAURANT'])('does not start hidden upload workspace for explicit question view in %s', async factoryType => {
    h.auth.factoryType = factoryType;
    h.auth.businessDomain = factoryType;
    await mountParent();
    expect(h.history).not.toHaveBeenCalled();
    expect(h.health).not.toHaveBeenCalled();
    expect(h.enrich).not.toHaveBeenCalled();
  });

  it('keeps ordinary query mode initialization working', async () => {
    h.route!.query = { tab: 'query' };
    await mountParent();
    expect(h.history).toHaveBeenCalledTimes(1);
    expect(h.health).toHaveBeenCalledTimes(1);
  });

  it('initializes Excel analysis once when the user leaves factory questions', async () => {
    await mountParent();
    page!.findComponent({ name: 'ElTabs' }).vm.$emit('update:modelValue', 'analysis');
    await flushPromises();
    expect(h.history).toHaveBeenCalledTimes(1);
    page!.findComponent({ name: 'ElTabs' }).vm.$emit('update:modelValue', 'query');
    await flushPromises();
    page!.findComponent({ name: 'ElTabs' }).vm.$emit('update:modelValue', 'analysis');
    await flushPromises();
    expect(h.history).toHaveBeenCalledTimes(1);
  });

  it('does not select and enrich a delayed upload after entering factory questions', async () => {
    let resolveHistory!: (value: unknown) => void;
    h.route!.query = { tab: 'analysis' };
    h.history.mockImplementationOnce(() => new Promise(resolve => { resolveHistory = resolve; }));
    await mountParent();
    h.route!.query = { tab: 'query', mode: 'factory' };
    await flushPromises();
    resolveHistory({ success: true, data: [{ id: 5274, fileName: '历史报表.xlsx', sheetName: '生产', createdAt: '2026-10-27T00:00:00Z', status: 'COMPLETED' }] });
    await flushPromises();
    expect(h.enrich).not.toHaveBeenCalled();
    expect((page!.vm as unknown as { uploadBatches: unknown[] }).uploadBatches).toEqual([]);
    h.route!.query = { tab: 'analysis' };
    await flushPromises();
    expect(h.history).toHaveBeenCalledTimes(2);
  });

  it('withdraws publication from an in-flight enrichment when switching into factory questions', async () => {
    h.route!.query = { tab: 'query' };
    let mayPublish!: () => boolean;
    let resolveEnrichment!: (value: unknown) => void;
    h.enrich.mockImplementationOnce((_id, _force, _progress, guard) => {
      mayPublish = guard;
      return new Promise(resolve => { resolveEnrichment = resolve; });
    });
    await mountParent();
    const pending = (page!.vm as unknown as { enrichActiveSheetAnalysis: (id: number) => Promise<{ success: boolean }> }).enrichActiveSheetAnalysis(5274);
    expect(mayPublish()).toBe(true);
    h.route!.query = { tab: 'query', mode: 'factory' };
    await flushPromises();
    expect(mayPublish()).toBe(false);
    h.route!.query = { tab: 'analysis' };
    await flushPromises();
    expect(mayPublish()).toBe(false);
    resolveEnrichment({ success: true, aiAnalysis: '过期的旧分析' });
    expect((await pending).success).toBe(false);
  });
});
