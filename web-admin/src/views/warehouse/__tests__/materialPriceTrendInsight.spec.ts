/** 物料价格趋势：展开一行后，单价折线下的一图一分析按该物料的批次现算（问题 40） */
import { describe, it, expect, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const getMock = vi.hoisted(() => vi.fn());
const perm = vi.hoisted(() => ({ canViewPrice: true }));
vi.mock('@/api/request', () => ({ get: getMock }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => perm }));
vi.mock('@/utils/echarts', () => ({ default: { init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), isDisposed: () => false }) } }));

import { ElTable, ElTableColumn, ElCard, ElTag, ElInput, ElButton, ElEmpty } from 'element-plus';
import View from '../MaterialPriceTrendView.vue';

const batches = [ // 接口不保证顺序：故意倒序给，页面要自己按入库日期排
  
  { id: 'b1', batchNumber: 'A', unitPrice: 10, receiptDate: '2026-10-01T08:00:00', quantity: 5 },
  { id: 'b2', batchNumber: 'B', unitPrice: null, receiptDate: '2026-10-05T08:00:00', quantity: 5 },
  { id: 'b3', batchNumber: 'C', unitPrice: 13, receiptDate: '2026-10-09T08:00:00', quantity: 5 },
].reverse();

describe('物料价格趋势 一图一分析', () => {
  it('展开后分析用图上那几批（无单价的批次不算），价格带 ¥', async () => {
    getMock.mockImplementation(async (url: string) =>
      url.includes('raw-material-types')
        ? { success: true, data: [{ id: 'm1', name: '面粉', unit: 'kg' }] }
        : { success: true, data: batches });
    const w = mount(View, { attachTo: document.body, global: { directives: { loading: () => undefined }, components: { ElTable, ElTableColumn, ElCard, ElTag, ElInput, ElButton, ElEmpty } } });
    await flushPromises();
    // 展开按钮
    await w.get('.el-table__expand-icon').trigger('click');
    await flushPromises();
    // 调的是按物料类型的接口（不是会忽略 materialTypeId 的列表接口）
    expect(getMock.mock.calls.map((c) => c[0])).toContain('/F006/material-batches/material-type/m1');
    expect(getMock.mock.calls.some((c) => c[0] === '/F006/material-batches')).toBe(false);
    const t = w.get('[data-testid="price-insight-m1"]').text();
    expect(t).toContain('图中 2 批单价从 2026-10-01 的 ¥10.00/kg 上涨到 2026-10-09 的 ¥13.00/kg（30%）');
    w.unmount();
  });

  it('无价格权限：没有展开列，也就没有图和分析', async () => {
    perm.canViewPrice = false;
    getMock.mockResolvedValue({ success: true, data: [{ id: 'm1', name: '面粉', unit: 'kg' }] });
    const w = mount(View, { global: { directives: { loading: () => undefined }, components: { ElTable, ElTableColumn, ElCard, ElTag, ElInput, ElButton, ElEmpty } } });
    await flushPromises();
    expect(w.find('.el-table__expand-icon').exists()).toBe(false);
    expect(w.html()).not.toContain('price-insight');
    expect(w.text()).not.toContain('¥');
    w.unmount();
    perm.canViewPrice = true;
  });
});
