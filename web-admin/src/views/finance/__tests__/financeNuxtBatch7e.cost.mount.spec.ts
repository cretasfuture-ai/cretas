/**
 * 第7e批: 成本分析页迁到 Nuxt UI 后的真实挂载断言。
 * 有价格权限显示三张成本卡, 无权限不渲染任何数值 (不显示 0), 数据缺失显示「—」。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  auth: { factoryId: 'F006' },
  perm: { canViewPrice: true },
}));
vi.mock('@/api/request', () => ({ get: mocks.get, post: vi.fn(), default: { get: vi.fn() } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mocks.auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => mocks.perm }));

import CostAnalysis from '../cost/analysis.vue';

const stubs = { ...nuxtUiStubs, UIcon: { template: '<i />' } };

beforeEach(() => {
  mocks.get.mockReset();
  mocks.auth = reactive({ factoryId: 'F006' });
  mocks.perm = reactive({ canViewPrice: true });
});

describe('cost/analysis 成本分析', () => {
  it('permitted: loads overview and shows three cost cards', async () => {
    mocks.get.mockResolvedValue({ success: true, data: { totalCost: 12345, materialCost: 8000, laborCost: 4345 } });
    const w = mount(CostAnalysis, { global: { stubs } });
    await flushPromises();
    expect(mocks.get).toHaveBeenCalledWith('/F006/reports/dashboard/overview');
    expect(w.get('[data-testid="cost-card-total"]').text()).toContain('12,345');
    expect(w.get('[data-testid="cost-card-material"]').text()).toContain('8,000');
    expect(w.get('[data-testid="cost-card-labor"]').text()).toContain('4,345');
    expect(w.text()).toContain('成本趋势分析');
    expect(w.find('[data-testid="cost-no-permission"]').exists()).toBe(false);
  });
  it('price-masked: no cards, no numbers, no 0', async () => {
    mocks.perm.canViewPrice = false;
    mocks.get.mockResolvedValue({ success: true, data: { totalCost: 12345, materialCost: 8000, laborCost: 4345 } });
    const w = mount(CostAnalysis, { global: { stubs } });
    await flushPromises();
    expect(w.find('[data-testid="cost-stat-cards"]').exists()).toBe(false);
    expect(w.text()).toContain('您没有查看价格/成本数据的权限');
    expect(w.text()).not.toContain('12,345');
    expect(w.text()).not.toMatch(/\b0\b/);
  });
  it('overview missing: cards show a dash, not 0', async () => {
    mocks.get.mockResolvedValue({ success: false, data: null });
    const w = mount(CostAnalysis, { global: { stubs } });
    await flushPromises();
    expect(w.get('[data-testid="cost-card-total"]').text()).toContain('—');
    expect(w.get('[data-testid="cost-card-total"]').text()).not.toContain('0');
  });
});
