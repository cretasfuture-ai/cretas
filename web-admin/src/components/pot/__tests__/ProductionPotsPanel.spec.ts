import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * S3-16 锅次面板: 列出本批次的锅 (时长只在时间已确认时显示, 否则「待确认」)、
 * 分摊进度 (未分完 = 召回按整批), 录入一锅时预填下一个锅号并显示每个用料节点还可分多少。
 */
const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }));
vi.mock('@/api/request', () => ({
  default: { get: vi.fn() },
  get: (...args: unknown[]) => state.get(...args),
  post: (...args: unknown[]) => state.post(...args),
  put: (...args: unknown[]) => state.put(...args),
}));
import Panel from '../ProductionPotsPanel.vue';

const ok = (data: unknown) => ({ success: true, data });
const pot = (n: number, extra: Record<string, unknown>) => ({
  id: n, productionBatchId: 7, productionPlanId: 'P', processSheetRowId: 1, processCode: 'lzhu', processName: '卤煮',
  potNumber: n, equipmentLabel: `${n} 号锅`, startedAt: '2026-10-28T09:00:00', endedAt: '2026-10-28T09:45:00',
  timeSource: 'MANUAL', timeConfirmed: true, durationMinutes: 45, bindingStatus: 'CONFIRMED', operatorId: 5,
  operatorName: '甲', outputQuantity: 50, outputUnit: 'kg', processParams: { temperatureC: 98 }, source: 'PROCESS_SHEET',
  status: 'RECORDED', voidReason: null, remark: null, inputs: [], outputs: [], corrections: [], processRedacted: false, ...extra,
});
const view = {
  productionBatchId: 7, batchNumber: 'CLK-W-7', potCount: 2, pendingCount: 1,
  pots: [
    pot(1, { inputs: [{ nodeType: 'MATERIAL_BATCH', nodeId: 'mb-a', batchNumber: 'RAW-A', quantity: 40, unit: 'kg' }] }),
    pot(2, { timeSource: 'CAMERA_CANDIDATE', timeConfirmed: false, durationMinutes: null, processRedacted: true, processParams: {} }),
  ],
  inputAllocation: [{ nodeType: 'MATERIAL_BATCH', nodeId: 'mb-a', batchNumber: 'RAW-A', batchQuantity: 60, allocatedQuantity: 40, unit: 'kg', complete: false }],
  outputAllocation: [],
};

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  state.get.mockResolvedValue(ok(view));
  state.post.mockResolvedValue(ok(view.pots[0]));
});

describe('锅次面板', () => {
  it('列出锅次: 已确认显示时长, 摄像候选显示待确认; 未分完提示召回按整批', async () => {
    const wrapper = mount(Panel, {
      props: { factoryId: 'F-POT', productionBatchId: 7, batchNumber: 'CLK-W-7', canWrite: true },
      global: { plugins: [ElementPlus] }, attachTo: document.body,
    });
    await flushPromises();
    expect(state.get).toHaveBeenCalledWith('/F-POT/production-pots', { params: { productionBatchId: 7 } });
    const text = wrapper.text();
    expect(text).toContain('45 分钟');
    expect(text).toContain('1 锅待确认');
    expect(text).toContain('摄像候选');
    expect(text).toContain('未分完，召回按整批');
    expect(text).toContain('工艺保密，需授权查看');
  });

  it('录入一锅: 预填下一个锅号, 显示用料还可分 20, 提交带上批次 id', async () => {
    const wrapper = mount(Panel, {
      props: { factoryId: 'F-POT', productionBatchId: 7, batchNumber: 'CLK-W-7', canWrite: true },
      global: { plugins: [ElementPlus] }, attachTo: document.body,
    });
    await flushPromises();
    await wrapper.find('[data-testid="pot-add"]').trigger('click');
    await flushPromises();
    expect(document.body.textContent).toContain('录入锅次 — 第 3 锅 (CLK-W-7)');
    expect(document.body.textContent).toContain('还可分 20');
    (document.querySelector('[data-testid="pot-submit"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(state.post).toHaveBeenCalledWith('/F-POT/production-pots',
      expect.objectContaining({ productionBatchId: 7, potNumber: 3, inputs: [] }));
  });
});
