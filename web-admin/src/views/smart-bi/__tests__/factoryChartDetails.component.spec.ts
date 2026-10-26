import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, beforeEach, vi } from 'vitest';
import ElementPlus from 'element-plus';

const h = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/api/smartbi/factory-charts', async () => {
  const actual = await vi.importActual<typeof import('@/api/smartbi/factory-charts')>('@/api/smartbi/factory-charts');
  return { ...actual, getFactoryChartDetails: h.get };
});
import FactoryChartDetails from '../FactoryChartDetails.vue';

const candidate = { id: 'cost', title: '单位成本', metric: 'cost', domain: '成本', priority: 'required', reason: '规则', source: '业务系统', unit: '元/kg', findings: { good: [], attention: [], suggestions: [] }, chart: { chartType: 'bar', echartsOption: {} }, kpis: [], tableData: null, detailContext: { id: 'ctx-1', expiresAt: '2099-01-01T00:00:00Z' } } as const;
const page = (pageNumber: number, rows: Record<string, unknown>[], hasMore: boolean, columns = ['批次', '金额'], total = 2) => ({ success: true, data: { factoryId: 'F006', candidateId: 'cost', title: '单位成本', source: '业务系统', period: '2026年3月', columns, rows, total, page: pageNumber, pageSize: 1, hasMore, appliedFilters: [], availableFilters: [], snapshotAt: '2026-10-26T00:00:00Z' } });

describe('FactoryChartDetails mounted workflow', () => {
  beforeEach(() => h.get.mockReset());
  it('loads point details, supports retrying the same failed next page, and keeps duplicate rows', async () => {
    h.get.mockResolvedValueOnce(page(1, [{ 批次: 'B1', 金额: 2.64 }], true)).mockRejectedValueOnce(new Error('网络失败')).mockResolvedValueOnce(page(2, [{ 批次: 'B1', 金额: 2.64 }], false));
    const wrapper = mount(FactoryChartDetails, { props: { modelValue: true, candidate, point: { name: '3月', dataIndex: 0 } }, global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(h.get).toHaveBeenCalledWith(expect.objectContaining({ context_id: 'ctx-1', page: 1, point: { name: '3月', dataIndex: 0 } }));
    expect(wrapper.text()).toContain('3月');
    await wrapper.get('.details-actions .el-button').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('网络失败');
    await wrapper.get('.details-actions .el-button').trigger('click');
    await flushPromises();
    expect(h.get).toHaveBeenLastCalledWith(expect.objectContaining({ context_id: 'ctx-1', page: 2 }));
    expect(wrapper.text()).toContain('已加载全部 2 行');
  });

  it('drops late response after close and reloads when same candidate receives a new context', async () => {
    let resolve!: (value: unknown) => void;
    h.get.mockReturnValueOnce(new Promise((r) => { resolve = r; }));
    const wrapper = mount(FactoryChartDetails, { props: { modelValue: true, candidate, point: { name: '3月' } }, global: { plugins: [ElementPlus] } });
    await wrapper.setProps({ modelValue: false });
    resolve(page(1, [{ 批次: '旧', 金额: 1 }], false));
    await flushPromises();
    expect(wrapper.text()).not.toContain('旧');
    const nextCandidate = { ...candidate, detailContext: { id: 'ctx-2', expiresAt: '2099-01-01T00:00:00Z' } };
    h.get.mockResolvedValueOnce(page(1, [{ 批次: '新', 金额: 2 }], false));
    await wrapper.setProps({ modelValue: true, candidate: nextCandidate });
    await flushPromises();
    expect(h.get).toHaveBeenLastCalledWith(expect.objectContaining({ context_id: 'ctx-2' }));
    expect(wrapper.text()).toContain('已加载全部 1 行');
  });

  it('renders mounted virtual-table cells from row values including zero, null, and structured Chinese data', async () => {
    const columns = ['批次', '数量', '备注', '空值'];
    h.get.mockResolvedValueOnce(page(1, [{ 批次: 'd273577c-b6f8-4d29-9a99-eff93c012e23', 数量: 0, 备注: { 来源: '业务系统' }, 空值: null }], false, columns, 1));
    const wrapper = mount(FactoryChartDetails, { props: { modelValue: true, candidate }, global: { plugins: [ElementPlus] } });
    await flushPromises();
    const tableText = wrapper.find('.el-table-v2').text();
    expect(tableText).toContain('d273577c-b6f8-4d29-9a99-eff93c012e23');
    expect(wrapper.findAll('.el-table-v2__cell-text').map((cell) => cell.attributes('title'))).toContain('d273577c-b6f8-4d29-9a99-eff93c012e23');
    expect(tableText).toContain('0');
    expect(tableText).toContain('{"来源":"业务系统"}');
    expect(tableText).toContain('—');
  });
});
