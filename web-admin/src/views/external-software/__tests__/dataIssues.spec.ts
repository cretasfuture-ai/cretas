/**
 * 外部软件对照页「黑湖数据需修正」清单: 有 / 空 / 报错 三态 + 类型筛选。
 * 只桩掉 HTTP 层 (api 模块) 和两个 store, 页面本身真渲染; 断言的是车间 / 管理员看到的文案。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

const api = vi.hoisted(() => ({
  listFactoryIntegrations: vi.fn(),
  getReportComparison: vi.fn(),
  getDataIssues: vi.fn(),
}));

vi.mock('@/api/externalIntegrations', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'LIUSHANMEN' }) }));
vi.mock('@/store/modules/platformFactoryContext', () => ({ usePlatformFactoryContextStore: () => ({ factoryId: '' }) }));

import ExternalSoftwarePage from '../ExternalSoftwarePage.vue';

const OWNER = '车间（黑湖报工填报人）';

function issue(type: string, typeLabel: string, extra: Record<string, unknown> = {}) {
  return {
    reportDate: '2026-11-16', productName: '香嫩牛肉丝', processName: '砍排修油', qty: 1771.73, unit: 'kg/盒',
    type, typeLabel, suggestion: '改成「kg」', owner: OWNER, ...extra,
  };
}

function issues(over: Record<string, unknown>) {
  return {
    system: 'blacklake', label: '黑湖', available: true, reason: null, asOf: '2026-11-18 23:09', stale: false,
    from: '2026-11-05', to: '2026-11-18', total: 0,
    counts: [
      { type: 'COMPOUND_UNIT', label: '单位写成了复合单位', count: 0 },
      { type: 'MIXED_UNIT', label: '同一工序单位不一致', count: 0 },
      { type: 'ZERO_QTY', label: '数量为 0 或负数', count: 0 },
      { type: 'MISSING_QTY', label: '数量没填', count: 0 },
    ],
    issues: [],
    ...over,
  };
}

async function mountPage() {
  const wrapper = mount(ExternalSoftwarePage, { global: { stubs: nuxtUiStubs } });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.resetAllMocks();
  api.listFactoryIntegrations.mockResolvedValue({
    success: true,
    data: [{ system: 'blacklake', label: '黑湖', status: 'ACTIVE', authorizedAt: '2026-11-15 10:00',
      lastCollectedAt: '2026-11-18 23:09', stale: false, connected: true, reason: null, datasets: [] }],
  });
  api.getReportComparison.mockResolvedValue({
    success: true,
    data: { system: 'blacklake', label: '黑湖', available: true, reason: null, asOf: '2026-11-18 23:09', stale: false,
      from: '2026-11-05', to: '2026-11-18', cretasTruncated: false, rows: [] },
  });
});

describe('黑湖数据需修正清单', () => {
  it('有问题: 汇总按类型计数, 表里是原值 + 怎么改 + 找谁改, 用词不带开发口径', async () => {
    api.getDataIssues.mockResolvedValue({
      success: true,
      data: issues({
        total: 2,
        counts: [
          { type: 'COMPOUND_UNIT', label: '单位写成了复合单位', count: 1 },
          { type: 'MIXED_UNIT', label: '同一工序单位不一致', count: 0 },
          { type: 'ZERO_QTY', label: '数量为 0 或负数', count: 1 },
          { type: 'MISSING_QTY', label: '数量没填', count: 0 },
        ],
        issues: [
          issue('COMPOUND_UNIT', '单位写成了复合单位'),
          issue('ZERO_QTY', '数量为 0 或负数', { reportDate: '2026-11-07', productName: '虎皮鸡爪', processName: '熟制', qty: 0, unit: 'kg' }),
        ],
      }),
    });
    const w = await mountPage();
    const summary = w.get('[data-testid="issues-summary"]').text();
    expect(summary).toContain('共 2 条');
    expect(summary).toContain('单位写成了复合单位 1 条');
    expect(summary).toContain('数量为 0 或负数 1 条');
    expect(summary).not.toContain('同一工序单位不一致');
    const table = w.get('[data-testid="issues-table"]').text();
    expect(table).toContain('1,771.73 kg/盒');
    expect(table).toContain('0 kg');
    expect(table).toContain('改成「kg」');
    expect(table).toContain(OWNER);
    expect(w.text()).not.toMatch(/COMPOUND_UNIT|ZERO_QTY|goodQty/);
    expect(w.find('[data-testid="issues-empty"]').exists()).toBe(false);
  });

  it('类型筛选只留所选类型', async () => {
    api.getDataIssues.mockResolvedValue({
      success: true,
      data: issues({
        total: 2,
        counts: [
          { type: 'COMPOUND_UNIT', label: '单位写成了复合单位', count: 1 },
          { type: 'ZERO_QTY', label: '数量为 0 或负数', count: 1 },
        ],
        issues: [
          issue('COMPOUND_UNIT', '单位写成了复合单位'),
          issue('ZERO_QTY', '数量为 0 或负数', { productName: '虎皮鸡爪', qty: 0, unit: 'kg' }),
        ],
      }),
    });
    const w = await mountPage();
    await w.get('[data-testid="issue-filter-ZERO_QTY"]').trigger('click');
    const table = w.get('[data-testid="issues-table"]').text();
    expect(table).toContain('虎皮鸡爪');
    expect(table).not.toContain('香嫩牛肉丝');
  });

  it('没问题: 写「未发现需修正的数据」, 不出表', async () => {
    api.getDataIssues.mockResolvedValue({ success: true, data: issues({}) });
    const w = await mountPage();
    expect(w.get('[data-testid="issues-empty"]').text()).toBe('未发现需修正的数据');
    expect(w.find('[data-testid="issues-table"]').exists()).toBe(false);
  });

  it('读不到: 清单区块报错, 不显示「0 条」也不显示「未发现」', async () => {
    api.getDataIssues.mockRejectedValue(new Error('外部数据快照读取失败（快照 9）'));
    const w = await mountPage();
    expect(w.get('[data-testid="issues-error"]').text()).toContain('外部数据快照读取失败');
    expect(w.find('[data-testid="issues-empty"]').exists()).toBe(false);
    expect(w.find('[data-testid="issues-summary"]').exists()).toBe(false);
    expect(w.text()).not.toContain('共 0 条');
    // 对照表区块不受影响
    expect(w.find('[data-testid="comparison-card"]').exists()).toBe(true);
  });
});
