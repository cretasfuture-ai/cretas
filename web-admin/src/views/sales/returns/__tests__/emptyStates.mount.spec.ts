/**
 * 销售退货列表：0 条时的三态必须走真实页面入口——
 * 本来没有数据（说明为什么为空）/ 筛选后为空 / 接口报错（不能说成没数据）。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';
import { nuxtPilotStubs } from '@/components/compat/testStubs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ReturnsList from '../list.vue';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/api/request', () => ({ get: (...a: unknown[]) => api.get(...a) }));
vi.mock('@/api/returnOrder', () => ({ submitReturnOrder: vi.fn(), approveReturnOrder: vi.fn(), rejectReturnOrder: vi.fn() }));
vi.mock('@/api/printApi', () => ({ safePrint: vi.fn() }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'LSMDEMO' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: true, canWrite: () => true }) }));
vi.mock('@/composables/useListSummary', () => ({ useListSummary: () => ({ summary: ref({ stats: [] }), loading: ref(false) }) }));
vi.mock('element-plus', async (orig) => ({ ...(await orig<Record<string, unknown>>()), ElMessage: { error: vi.fn(), info: vi.fn(), success: vi.fn() } }));
vi.mock('@/components/compat/messages', async () => {
  const { ElMessage, ElMessageBox } = await import('element-plus');
  return { useCompatMessages: () => ({ ElMessage, ElMessageBox }) };
});

// 表格桩：行数 + #empty 插槽，其余子列不渲染
const stubs = {
  ...nuxtPilotStubs,
  'el-table': { props: ['data'], template: '<div class="tbl"><div v-for="(r, i) in data" :key="i" class="row">{{ r.returnNumber }}</div><slot v-if="!data.length" name="empty" /></div>' },
  'el-table-column': true, 'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-select': true, 'el-option': true, 'el-button': true, 'el-pagination': true, 'el-tag': true,
  TableFooter: true, RowActionMenu: true, ListEmptyExplain: false,
};

async function mountPage() {
  const w = mount(ReturnsList, { global: { stubs, directives: { loading: () => undefined } } });
  await flushPromises();
  return w;
}

describe('销售退货空状态', () => {
  beforeEach(() => api.get.mockReset());

  it('本来没有数据：说明如何产生退货单', async () => {
    api.get.mockResolvedValue({ success: true, data: { content: [], totalElements: 0 } });
    const w = await mountPage();
    const box = w.get('[data-testid="list-empty-explain"]');
    expect(box.attributes('data-kind')).toBe('empty');
    expect(box.text()).toContain('还没有销售退货单');
    expect(box.text()).toContain('销售订单');
  });

  it('接口失败：显示加载失败，不出现「还没有销售退货单」', async () => {
    api.get.mockResolvedValue({ success: false, message: '服务异常' });
    const w = await mountPage();
    const box = w.get('[data-testid="list-empty-explain"]');
    expect(box.attributes('data-kind')).toBe('error');
    expect(box.text()).toContain('服务异常');
    expect(box.text()).not.toContain('还没有销售退货单');
  });

  it('接口抛异常：同样是错误态，点重试会再请求一次', async () => {
    api.get.mockRejectedValue(new Error('网络异常'));
    const w = await mountPage();
    expect(w.get('[data-testid="list-empty-explain"]').attributes('data-kind')).toBe('error');
    api.get.mockResolvedValue({ success: true, data: { content: [{ id: '1', returnNumber: 'R-1' }], totalElements: 1 } });
    await w.get('[data-testid="list-empty-retry"]').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('R-1');
    expect(w.find('[data-testid="list-empty-explain"]').exists()).toBe(false);
  });
});
