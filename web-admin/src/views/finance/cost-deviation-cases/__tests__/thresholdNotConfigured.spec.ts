/** 未配置阈值要明说不告警; 只配一类时另一类仍显示未配置。 */
import { describe, it, expect, vi } from 'vitest';
import { flushPromises, shallowMount } from '@vue/test-utils';

let thresholds: { variancePct: number | null; balancePct: number | null } = { variancePct: null, balancePct: null };

vi.mock('@/api/costDeviationCases', () => ({
  getCostDeviationThresholds: vi.fn().mockImplementation(() => Promise.resolve({ success: true, data: thresholds })),
  listCostDeviationCases: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));
vi.mock('@/api/request', () => ({ get: vi.fn() }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F1' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));

import Page from '../index.vue';

const stubs = {
  ElAlert: { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
  ElTable: { template: '<div />' },
  ElDialog: { template: '<div />' },
  ElDrawer: { template: '<div />' },
};

describe('成本损耗异常页', () => {
  it('未配置时明说不告警', async () => {
    thresholds = { variancePct: null, balancePct: null };
    const w = shallowMount(Page, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('未配置告警阈值');
    expect(w.find('[data-test="variance-threshold"]').text()).toBe('未配置');
  });

  it('配置后不再提示, 只配一类时另一类仍是未配置', async () => {
    thresholds = { variancePct: 5, balancePct: null };
    const w = shallowMount(Page, { global: { stubs } });
    await flushPromises();
    expect(w.text()).not.toContain('未配置告警阈值');
    expect(w.find('[data-test="variance-threshold"]').text()).toBe('5%');
    expect(w.find('[data-test="balance-threshold"]').text()).toBe('未配置');
  });
});
