import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';

/**
 * PROTO-YDWM-B4B8 · B6 —— 「BOM 用量对比」顶部 KPI 写「基于全部 N 个批次」, 实际只算当前页。
 * 味美 96 个批次, 页面每页 10 条。真实入口: BomAchievementView 挂载 + 真实分页接口替身。
 */
const apiGet = vi.fn();
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'FOOD_X' }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => apiGet(...args) }));
import View from '../BomAchievementView.vue';

/** 25 个批次: 前 10 个用量比 100%, 后 15 个 50% (planned 100, actual 100/50)。 */
const BATCH_COUNT = 25;
function batch(i: number) { return { id: i, batchNumber: `B-${i}`, status: 'COMPLETED', plannedQuantity: 1, actualQuantity: 1 }; }
function summaryFor(id: number) {
  return { items: [{ materialName: 'm', plannedQty: 100, actualQty: id <= 10 ? 100 : 50, unit: 'kg' }] };
}

beforeEach(() => {
  apiGet.mockReset();
  apiGet.mockImplementation((url: string, opts?: { params?: { page: number; size: number } }) => {
    if (url.endsWith('/processing/batches')) {
      const { page = 1, size = 20 } = opts?.params ?? {};
      const all = Array.from({ length: BATCH_COUNT }, (_, k) => batch(k + 1));
      return Promise.resolve({ success: true, data: { content: all.slice((page - 1) * size, page * size), totalElements: BATCH_COUNT } });
    }
    const m = url.match(/batch\/(\d+)\/summary$/);
    if (m) return Promise.resolve({ success: true, data: summaryFor(Number(m[1])) });
    return Promise.resolve({ success: true, data: null });
  });
});

async function open() {
  const wrapper = mount(View, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}
function kpi(wrapper: Awaited<ReturnType<typeof open>>, label: string) {
  const card = wrapper.findAll('.kpi-card').find((c) => c.text().includes(label));
  expect(card, `应有「${label}」KPI 卡`).toBeDefined();
  return card!.text();
}

describe('B6 BOM 用量对比 KPI 基于全部批次', () => {
  it('表格只显示 10 行, 但 KPI 的总数/平均/最低来自全部 25 个批次', async () => {
    const wrapper = await open();
    expect(wrapper.findAll('tbody tr.el-table__row')).toHaveLength(10);
    expect(kpi(wrapper, '总批次数')).toContain('25');
    // 全部: (10×100 + 15×50)/25 = 70; 只算当前页(前 10 条)会是 100
    expect(kpi(wrapper, '平均用量比')).toContain('70%');
    expect(kpi(wrapper, '平均用量比')).toContain('基于全部 25 个批次');
    expect(kpi(wrapper, '最低用量比')).toContain('50%');
    // 用量偏差批次数: 15 个批次偏离 >5%, 只算当前页会是 0
    expect(kpi(wrapper, '用量偏差批次数')).toContain('15');
  });

  it('翻页: KPI 不变, 不重新请求批次列表', async () => {
    const wrapper = await open();
    const batchListCalls = () => apiGet.mock.calls.filter((c) => String(c[0]).endsWith('/processing/batches')).length;
    const before = batchListCalls();
    await wrapper.find('.el-pager li:nth-child(2)').trigger('click');
    await flushPromises();
    expect(batchListCalls()).toBe(before);
    expect(kpi(wrapper, '平均用量比')).toContain('70%');
    expect(wrapper.text()).toContain('B-11');
  });

  it('读取失败 (某页 success=false) 时不拿已读到的页冒充全部', async () => {
    apiGet.mockImplementation((url: string, opts?: { params?: { page: number; size: number } }) => {
      if (url.endsWith('/processing/batches')) {
        const page = opts?.params?.page ?? 1;
        if (page === 2) return Promise.resolve({ success: false, data: null });
        return Promise.resolve({ success: true, data: { content: Array.from({ length: 100 }, (_, k) => batch(k + 1)), totalElements: 250 } });
      }
      return Promise.resolve({ success: true, data: summaryFor(1) });
    });
    const wrapper = await open();
    expect(wrapper.text()).not.toContain('基于全部');
    expect(wrapper.findAll('tbody tr.el-table__row')).toHaveLength(0);
  });
});
