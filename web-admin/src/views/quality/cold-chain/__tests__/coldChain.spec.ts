import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 冷链超温批次页 (缺口 15): 告警 → 受影响批次表 → 勾选转质检。
 * 断言打在真正发出去的请求体、行内的暴露时长和不可转批次的禁选上。
 */
const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), confirm: vi.fn() }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: state.push }), useRoute: () => ({ query: {} }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-CC' }) }));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => state.get(...args),
  post: (...args: unknown[]) => state.post(...args),
  default: { get: (...args: unknown[]) => state.get(...args) },
}));
vi.mock('element-plus', async (orig) => {
  const mod = await orig<typeof import('element-plus')>();
  return { ...mod, ElMessageBox: { ...mod.ElMessageBox, confirm: (...a: unknown[]) => state.confirm(...a) } };
});
import Page from '../index.vue';

const ok = (data: unknown) => ({ success: true, data });
const alert = {
  id: 7, equipmentId: 1, equipmentCode: 'COLD-01', equipmentName: '1号冷库', alertTime: '2026-10-20T02:15:00',
  severity: 'CRITICAL', minTemp: 12, maxTemp: 12, durationMinutes: 180, status: 'OPEN', heldBatchCount: 0,
};
const batch = (over: Record<string, unknown>) => ({
  targetType: 'RAW_MATERIAL_BATCH', formLabel: '原料', targetId: 'b', batchNumber: 'BN-B', itemName: '猪蹄',
  warehouseId: 'W', warehouseName: '冷库', quantityNow: 10, unit: 'kg', status: 'AVAILABLE', verdict: 'AFFECTED',
  exposureMinutes: 180, maxExposureMinutes: 180, firstExposedAt: null, lastExposedAt: null,
  inStockFrom: '2026-10-19 10:00', inStockUntil: '仍在库', uncertainReason: null, holdable: true,
  holdBlockedReason: null, alreadyHeld: false, ...over,
});
const report = {
  alert,
  excursion: { start: '2026-10-20T02:00:00', end: '2026-10-20T05:00:00', minutes: 180, minTemp: 12, maxTemp: 12,
    targetTempMin: 0, targetTempMax: 8, readingCount: 37, ongoing: false },
  segments: [{ warehouseId: 'W', warehouseName: '冷库', start: '2026-10-20T02:00:00', end: '2026-10-20T05:00:00', minutes: 180 }],
  unlocatedPeriods: [],
  batches: [
    batch({}),
    batch({ targetId: 'c', batchNumber: 'BN-C', exposureMinutes: 30, maxExposureMinutes: 30, inStockFrom: '2026-10-20 04:30' }),
    batch({ targetId: 'u', batchNumber: 'BN-U', verdict: 'UNCERTAIN', exposureMinutes: null, uncertainReason: '没有找到出库流水',
      holdable: false, holdBlockedReason: '已经全部出库，库里没有货可扣' }),
  ],
  affectedCount: 2, uncertainCount: 1, notAffectedCount: 0, note: null,
};

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/alerts')) return Promise.resolve(ok([alert]));
    if (url.endsWith('/affected-batches')) return Promise.resolve(ok(report));
    return Promise.resolve(ok([]));
  });
  state.post.mockResolvedValue(ok([{ targetType: 'RAW_MATERIAL_BATCH', targetId: 'b', batchNumber: 'BN-B', outcome: 'HELD', message: '' }]));
  state.confirm.mockResolvedValue('confirm');
});

async function openDrawer() {
  const wrapper = mount(Page, { global: { plugins: [ElementPlus] }, attachTo: document.body });
  await flushPromises();
  await wrapper.find('[data-testid="cc-open-7"]').trigger('click');
  await flushPromises();
  return wrapper;
}

describe('冷链超温批次页', () => {
  it('点告警 → 请求该告警的受影响批次, 表里写出确定暴露时长和「在库时间不确定」', async () => {
    await openDrawer();
    expect(state.get).toHaveBeenCalledWith('/F-CC/cold-chain/alerts/7/affected-batches');
    const table = document.querySelector('[data-testid="cc-affected-table"]')!;
    expect(table.textContent).toContain('BN-B');
    expect(table.textContent).toContain('180 分钟');
    expect(table.textContent).toContain('在库时间不确定 · 最多 180 分钟');
    expect(table.textContent).toContain('已经全部出库');
  });

  it('不能转的批次禁选; 勾选可转的批次后转质检, 请求体只带选中的批次', async () => {
    await openDrawer();
    const boxes = Array.from(document.querySelectorAll('[data-testid="cc-affected-table"] tbody .el-checkbox__original')) as HTMLInputElement[];
    expect(boxes).toHaveLength(3);
    expect(boxes[2].disabled).toBe(true);
    const holdBtn = document.querySelector('[data-testid="cc-hold"]') as HTMLButtonElement;
    expect(holdBtn.disabled).toBe(true);

    boxes[0].click();
    await flushPromises();
    expect(holdBtn.disabled).toBe(false);
    holdBtn.click();
    await flushPromises();

    expect(state.confirm).toHaveBeenCalled();
    expect(String(state.confirm.mock.calls[0][0])).toContain('BN-B');
    expect(state.post).toHaveBeenCalledWith('/F-CC/cold-chain/alerts/7/holds', {
      targets: [{ targetType: 'RAW_MATERIAL_BATCH', targetId: 'b' }],
    });
  });

  it('取消确认框 → 不发请求', async () => {
    state.confirm.mockRejectedValue('cancel');
    await openDrawer();
    const boxes = Array.from(document.querySelectorAll('[data-testid="cc-affected-table"] tbody .el-checkbox__original')) as HTMLInputElement[];
    boxes[0].click();
    await flushPromises();
    (document.querySelector('[data-testid="cc-hold"]') as HTMLButtonElement).click();
    await flushPromises();
    expect(state.post).not.toHaveBeenCalled();
  });
});
