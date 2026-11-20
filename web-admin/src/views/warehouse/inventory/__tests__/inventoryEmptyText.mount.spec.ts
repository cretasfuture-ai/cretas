/**
 * 库存批次页：「暂无数据」只能出现在真的没数据时。
 * 来源有两处：UTable 的 #empty 插槽（列表无行）与 TableFooter 的汇总区（汇总无统计项）。
 * 有行 + 有汇总 → 页面不出现；无行 + 无汇总 → 出现（阴性对照）。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import InventoryPage from '../index.vue';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const summary = vi.hoisted(() => ({ stats: [] as unknown[] }));

vi.mock('@/api/request', () => ({ get: (...a: unknown[]) => api.get(...a), post: (...a: unknown[]) => api.post(...a) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true, canViewPurchasePrice: true }),
}));
vi.mock('@/composables/useListSummary', () => ({
  useListSummary: () => ({ summary: ref({ stats: summary.stats }), loading: ref(false) }),
}));
vi.mock('@/composables/useWorkflowStats', () => ({ useWorkflowStats: () => ({ stats: ref(null), loading: ref(false) }) }));
vi.mock('@/composables/useLinkChipCounts', () => ({
  useLinkChipCounts: () => ({ fetchLinkChipCounts: vi.fn(), countsFor: () => ({}) }),
}));

const stubs = {
  // Nuxt UI 没装进 vitest：表格桩把行数与 #empty 插槽暴露出来
  UTable: {
    props: ['data'],
    template: '<div class="tbl"><div v-for="(r, i) in data" :key="i" class="tbl-row">{{ r.batchNumber }}</div><slot v-if="!data.length" name="empty" /></div>',
  },
  WorkflowBar: true,
  AiEntryDrawer: true,
  LinkChipCell: true,
  UModal: true,
  UPagination: true,
  USelectMenu: true,
};

function mockRows(rows: unknown[]) {
  api.get.mockImplementation(async (url?: string) => (String(url).endsWith('/material-batches')
    ? { success: true, data: { content: rows, totalElements: rows.length } }
    : { success: true, data: { totalBatches: 0, totalQuantity: 0, lowStockCount: 0, expiringCount: 0 } }));
}
async function mountPage() {
  const w = mount(InventoryPage, { global: { stubs } });
  await flushPromises();
  return w;
}

describe('库存批次页「暂无数据」', () => {
  beforeEach(() => api.get.mockReset());

  it('有行数据且汇总有统计项：整页不出现「暂无数据」', async () => {
    summary.stats = [{ label: '共', value: 3, format: 'number' }];
    mockRows([{ id: 'b1', batchNumber: 'RM-1', status: 'AVAILABLE' }]);
    const w = await mountPage();
    expect(w.text()).toContain('RM-1'); // 阳性对照：行真的渲染了
    expect(w.text()).not.toContain('暂无数据');
  });

  it('阴性对照：无行、无汇总：「暂无数据」出现', async () => {
    summary.stats = [];
    mockRows([]);
    const w = await mountPage();
    expect(w.text()).toContain('暂无数据');
  });
});
