import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';

/** 缺口 14: 时限面板真的接在逐道录入上 (workflow 计划才读, 保存后跟着刷新)。 */
const getPlanTimeLimits = vi.fn();
vi.mock('@/api/processTimeLimit', async () => {
  const actual = await vi.importActual<typeof import('@/api/processTimeLimit')>('@/api/processTimeLimit');
  return { ...actual, getPlanTimeLimits: (...args: unknown[]) => getPlanTimeLimits(...args) };
});
vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    getSemiFinishedInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getFinishedGoodsInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getAvailableRawBatches: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getInputAvailability: vi.fn().mockResolvedValue({ success: true, data: [] }),
  };
});
vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));
vi.mock('@/api/qcRelease', async () => {
  const actual = await vi.importActual<typeof import('@/api/qcRelease')>('@/api/qcRelease');
  return { ...actual, loadHeldBatchNumbers: vi.fn().mockResolvedValue({ ok: true, held: new Set() }) };
});

import ProcessDataTable from '../ProcessDataTable.vue';

const port = (workflowPortId: string, materialKind: string) => ({
  workflowPortId, materialKind, skuId: 'SKU-1', materialName: '焯水猪蹄', unit: 'kg', required: true,
  skuResolved: true, finished: false,
});

const workflowContext = {
  workflowNodeId: 'p1', workProcessId: 'WP-1', processName: '卤制', processCategory: null, defaultCostCategory: null,
  processOrder: 2, plannedUnit: 'kg', allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
  customFieldSchema: null, inputs: [port('in-1', 'SEMI_FINISHED')], output: port('out-1', 'SEMI_FINISHED'),
  outputs: [port('out-1', 'SEMI_FINISHED')],
};

function mountTable(context: unknown) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1', processCode: 'shuzhi', processOrder: 2, processLabel: '卤制',
      productTypeId: 'PT-1', upstreamItems: [], ownInventoryItems: [], initialRows: [], viewMode: 'card',
      workflowContext: context as never, inputUnit: 'kg', outputUnit: 'kg',
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

const status = {
  targetType: 'SEMI_FINISHED', targetId: '1', batchNumber: 'SEMI-1', workflowPortId: 'in-1', processName: '卤制',
  limitMinutes: 120, startEvent: 'OUTPUT_POSTED', state: 'OK', startAt: null, deadlineAt: null, remainingMinutes: 80,
  reason: null, balance: 50, expiredQuantity: 0, drawableQuantity: 50, unit: 'kg', paused: false, perLot: false,
};

describe('ProcessDataTable 工序时限面板', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPlanTimeLimits.mockResolvedValue({ success: true, data: [status] });
  });

  it('workflow 计划: 按计划和工序序读状态并显示', async () => {
    const wrapper = mountTable(workflowContext);
    await flushPromises();
    expect(getPlanTimeLimits).toHaveBeenCalledWith('F006', 'PLAN-1', 2);
    expect(wrapper.get('[data-testid="process-time-limit-panel"]').text()).toContain('SEMI-1');
  });

  it('别的工序保存后 (refreshSharedInventories) 面板跟着刷新', async () => {
    const wrapper = mountTable(workflowContext);
    await flushPromises();
    (wrapper.vm as unknown as { refreshSharedInventories: () => void }).refreshSharedInventories();
    await flushPromises();
    expect(getPlanTimeLimits).toHaveBeenCalledTimes(2);
  });

  it('legacy 计划: 不读', async () => {
    const wrapper = mountTable(null);
    await flushPromises();
    expect(getPlanTimeLimits).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="process-time-limit-panel"]').exists()).toBe(false);
  });
});
