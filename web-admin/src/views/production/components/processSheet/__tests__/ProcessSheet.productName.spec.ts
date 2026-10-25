// 2026-10-25 线上实拍 (F006【演示L4】香酥炸鸡腿等多产品计划):「逐工序报工」后面显示
// `__RAW_WORKFLOW_OWNER__RMT_F006_LSM_…` —— 多产出计划的 productTypeId 是内部原料锚点。
// 这里守的是: 抬头显示调用方给的人话产品名; 万一传进来的是锚点名, 也不露内部编码。
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

vi.mock('@/api/processSheet', () => ({
  getInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getRows: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getWorkflowSheetConfig: vi.fn().mockResolvedValue({ success: true, data: null }),
}));
vi.mock('@/api/processProduction', () => ({
  getProductWorkProcesses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));
vi.mock('@/api/bom', () => ({
  bomSeasoningApi: {
    getByProduct: vi.fn().mockResolvedValue({ success: true, data: { injectionConfigs: [], seasoningItems: [] } }),
  },
}));

import ProcessSheet from '../ProcessSheet.vue';

const ANCHOR = '__RAW_WORKFLOW_OWNER__RMT_F006_LSM_2bce8bda718ed689';

function mountSheet(productName?: string) {
  return mount(ProcessSheet, {
    props: {
      factoryId: 'F006',
      planId: 'dd3f33d0-0d0a-40ef-92fa-8c86fa68eb31',
      productTypeId: ANCHOR,
      productName,
    },
    global: {
      plugins: [ElementPlus],
      stubs: { ProcessDataTable: true, InventoryTable: true, YieldCardTable: true, teleport: true, transition: false },
    },
  });
}

describe('ProcessSheet header product name', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('shows the multi-product label with the real product names passed by the plan list', async () => {
    const wrapper = mountSheet('多产出计划：【演示L4】香酥炸鸡腿、【演示L4】盐酥鸡块、【演示L4】鸡骨高汤');
    await flushPromises();
    const name = wrapper.find('[data-testid="process-sheet-product-name"]');
    expect(name.text()).toBe('多产出计划：【演示L4】香酥炸鸡腿、【演示L4】盐酥鸡块、【演示L4】鸡骨高汤');
    expect(wrapper.text()).not.toContain('__RAW_WORKFLOW_OWNER__');
  });

  it('never renders the internal anchor name even if a caller passes it', async () => {
    const wrapper = mountSheet(ANCHOR);
    await flushPromises();
    expect(wrapper.find('[data-testid="process-sheet-product-name"]').text()).toBe('多产出计划');
    expect(wrapper.text()).not.toContain('__RAW_WORKFLOW_OWNER__');
  });

  it('keeps an ordinary single product name unchanged', async () => {
    const wrapper = mountSheet('猪蹄');
    await flushPromises();
    expect(wrapper.find('[data-testid="process-sheet-product-name"]').text()).toBe('猪蹄');
  });
});
