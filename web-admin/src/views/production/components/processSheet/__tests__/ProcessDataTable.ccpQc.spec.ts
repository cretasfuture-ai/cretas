/**
 * 缺口 11/13 审查 —— 逐工序表 (ProcessDataTable) 真挂载:
 *  - 中 7: 挂了关键控制点的工序, 正式报工把 CCP 实测值放进 submitRow 请求 (后端同一事务落库),
 *          不再「报工成功后单独补交 CCP」; 取消录入 = 不报工; 没挂 CCP 的工序请求里不带 ccpReadings;
 *  - 低 11: 待检清单没取到 → 写明「待检状态未知」并给「重新加载」(不能当成都不待检);
 *  - 低 11 / 高 2: 半成品一行里只有部分待检 → 可选, 余量按「余额 − 待检」, 标签写明另有多少待检;
 *          整行待检的照样列出但不可选。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { WorkflowPortDescriptor, WorkflowProcessDescriptor } from '@/api/processSheet';
import type { CcpStepCheckpoint } from '@/api/ccp';

const submitRow = vi.fn();
const getSemiFinishedInventory = vi.fn();
const loadHeldBatchNumbers = vi.fn();
const recordCcp = vi.fn();
const collect = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: vi.fn().mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT' } }),
    submitRow: (...args: unknown[]) => submitRow(...args),
    deleteRow: vi.fn(),
    getRowHistory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getAvailableRawBatches: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getSemiFinishedInventory: (...args: unknown[]) => getSemiFinishedInventory(...args),
    getFinishedGoodsInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
  };
});
vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));
vi.mock('@/api/qcRelease', async () => {
  const actual = await vi.importActual<typeof import('@/api/qcRelease')>('@/api/qcRelease');
  return { ...actual, loadHeldBatchNumbers: (...args: unknown[]) => loadHeldBatchNumbers(...args) };
});
vi.mock('@/api/ccp', async () => {
  const actual = await vi.importActual<typeof import('@/api/ccp')>('@/api/ccp');
  return { ...actual, recordCcp: (...args: unknown[]) => recordCcp(...args) };
});

import ProcessDataTable from '../ProcessDataTable.vue';

/** CCP 录入弹窗桩: 只替换「弹窗收集实测值」这一步, ProcessDataTable 自己的提交流程照常跑。 */
const CcpEntryDialogStub = defineComponent({
  name: 'CcpEntryDialog',
  setup(_, { expose }) {
    expose({ collect: (...args: unknown[]) => collect(...args) });
    return () => null;
  },
});

const OUTPUT_PORT: WorkflowProcessDescriptor['outputs'][number] = {
  workflowPortId: 'OUT-1', materialNodeId: 'OUT-NODE-1', materialKind: 'SEMI_FINISHED',
  skuId: 'OUT-1', materialName: '卤制半成品', unit: 'kg', required: true, skuResolved: true, finished: false,
};
const IN_PORT: WorkflowPortDescriptor = {
  workflowPortId: 'IN-1', materialNodeId: 'NODE-1', materialKind: 'SEMI_FINISHED',
  skuId: 'SEMI-1', materialName: '上游半成品', unit: 'kg', required: true, skuResolved: true, finished: false,
};
const CP: CcpStepCheckpoint = {
  checkpointId: 71, checkpointCode: 'CCP-T', name: '中心温度', hazardType: 'BIOLOGICAL',
  criticalLimitMin: 75, criticalLimitMax: 100, unit: '℃', monitoringProcedure: null, correctiveAction: null,
};

function context(ccp: CcpStepCheckpoint[]): WorkflowProcessDescriptor {
  return {
    workflowNodeId: 'PROC-1', workProcessId: 'WP-COOK', processName: '卤制', processCategory: 'COOK',
    defaultCostCategory: null, processOrder: 2, plannedUnit: 'kg', allowMultipleUpstreamSources: false,
    allowFinishedGoodsSource: false, customFieldSchema: null,
    inputs: [IN_PORT], output: OUTPUT_PORT, outputs: [OUTPUT_PORT], ccpCheckpoints: ccp,
  };
}

function wip(batchNumber: string, remaining = 10) {
  return {
    batchNumber, productTypeId: 'SEMI-1', productTypeName: '上游半成品',
    produced: remaining, used: 0, remaining, status: 'ACTIVE' as const, unit: 'kg',
  };
}

/** 选项挂在下拉弹层里 (未打开时不渲染); 看选项时把下拉换成直出 (同 blendRatio.spec 的做法)。 */
const INLINE_SELECT_STUBS = {
  ElSelect: { template: '<div class="stub-select"><slot /></div>' },
  ElOptionGroup: { props: ['label'], template: '<div class="stub-group"><slot /></div>' },
  ElOption: {
    name: 'ElOption',
    props: ['label', 'value', 'disabled'],
    template: '<span class="stub-option">{{ label }}</span>',
  },
};

function mountTable(ccp: CcpStepCheckpoint[], upstreamItems = [wip('UP-ONLY')],
  extraStubs: Record<string, unknown> = {}) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-CCP', processCode: 'custom_cook', processOrder: 2,
      processLabel: '卤制', productTypeId: 'OUT-1', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: false, initialRows: [], ownInventoryItems: [], upstreamItems,
      viewMode: 'card', workflowContext: context(ccp), allowMultipleUpstreamSources: false,
    },
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, transition: false, CcpEntryDialog: CcpEntryDialogStub, ...extraStubs },
    },
  });
}

async function addFilledRow(wrapper: ReturnType<typeof mountTable>) {
  await flushPromises();
  await wrapper.findAll('button').find((b) => b.text().includes('新增行'))!.trigger('click');
  await flushPromises();
  wrapper.findAll('.sp-card-field').find((f) => f.text().includes('投入(kg)'))!
    .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 8);
  wrapper.find('[data-testid="output-quantity"]')
    .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 7);
  await flushPromises();
}

async function submit(wrapper: ReturnType<typeof mountTable>) {
  await wrapper.findAll('button').find((b) => b.text().includes('正式报工'))!.trigger('click');
  await flushPromises();
}

beforeEach(() => {
  vi.restoreAllMocks();
  submitRow.mockReset();
  recordCcp.mockReset();
  collect.mockReset();
  getSemiFinishedInventory.mockReset();
  getSemiFinishedInventory.mockResolvedValue({ success: true, data: [] });
  loadHeldBatchNumbers.mockReset();
  loadHeldBatchNumbers.mockResolvedValue({ ok: true, held: new Set() });
  submitRow.mockResolvedValue({
    success: true, data: { submissionStatus: 'SUBMITTED', materialized: true, batchNumber: 'WIP-OUT-1', warnings: [] },
  });
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
});

describe('审查中 7: CCP 实测值随正式报工一起提交', () => {
  it('挂了 CCP: 弹窗录的值进 submitRow 请求的 ccpReadings, 不再单独调 CCP 记录接口', async () => {
    collect.mockResolvedValue([{ checkpointId: 71, measuredValue: 70 }]);
    const wrapper = mountTable([CP]);
    await addFilledRow(wrapper);
    await submit(wrapper);

    expect(collect).toHaveBeenCalledTimes(1);
    expect(submitRow).toHaveBeenCalledTimes(1);
    const req = submitRow.mock.calls[0][2] as Record<string, unknown>;
    expect(req.ccpReadings).toEqual([{ checkpointId: 71, measuredValue: 70 }]);
    expect(recordCcp).not.toHaveBeenCalled();
  });

  it('取消 CCP 录入 = 不报工 (一个请求都不发)', async () => {
    collect.mockResolvedValue(null);
    const wrapper = mountTable([CP]);
    await addFilledRow(wrapper);
    await submit(wrapper);
    expect(submitRow).not.toHaveBeenCalled();
  });

  it('阴性对照: 没挂 CCP 的工序 → 不弹录入, 请求里不带 ccpReadings', async () => {
    const wrapper = mountTable([]);
    await addFilledRow(wrapper);
    await submit(wrapper);
    expect(collect).not.toHaveBeenCalled();
    expect(submitRow).toHaveBeenCalledTimes(1);
    expect(submitRow.mock.calls[0][2]).not.toHaveProperty('ccpReadings');
  });
});

describe('审查低 11: 待检状态', () => {
  it('待检清单没取到 → 写明「待检状态未知」, 重新加载成功后提示消失', async () => {
    loadHeldBatchNumbers.mockResolvedValueOnce({ ok: false, held: new Set() });
    const wrapper = mountTable([]);
    await flushPromises();
    const banner = wrapper.find('[data-testid="qc-held-status-unknown"]');
    expect(banner.exists()).toBe(true);
    expect(banner.text()).toContain('待检状态未知');

    await wrapper.find('[data-testid="qc-held-reload"]').trigger('click');
    await flushPromises();
    expect(loadHeldBatchNumbers.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(wrapper.find('[data-testid="qc-held-status-unknown"]').exists()).toBe(false);
  });

  it('阴性对照: 取到了 (哪怕一条待检都没有) → 不显示状态未知', async () => {
    const wrapper = mountTable([]);
    await flushPromises();
    expect(loadHeldBatchNumbers).toHaveBeenCalled();
    expect(wrapper.find('[data-testid="qc-held-status-unknown"]').exists()).toBe(false);
  });

  it('半成品部分待检: 可选, 余量按余额 − 待检, 标签写另有多少待检; 整行待检的列出但不可选', async () => {
    getSemiFinishedInventory.mockResolvedValue({
      success: true,
      data: [
        { intermediateBatchNo: 'SFI-PART', productTypeId: 'SEMI-1', productTypeName: '上游半成品',
          availableQuantity: 50, unit: 'kg', status: 'AVAILABLE',
          inspectionStatus: 'PENDING', inspectionPendingQuantity: 20 },
        { intermediateBatchNo: 'SFI-ALL', productTypeId: 'SEMI-1', productTypeName: '上游半成品',
          availableQuantity: 30, unit: 'kg', status: 'AVAILABLE', inspectionStatus: 'PENDING' },
      ],
    });
    const wrapper = mountTable([], [wip('UP-A'), wip('UP-B')], INLINE_SELECT_STUBS);
    await flushPromises();
    await wrapper.findAll('button').find((b) => b.text().includes('新增行'))!.trigger('click');
    await flushPromises();

    const options = wrapper.findAllComponents({ name: 'ElOption' });
    const byBatch = (no: string) => options.find((o) => String(o.props('label') ?? '').includes(no));
    const part = byBatch('SFI-PART');
    const all = byBatch('SFI-ALL');
    expect(part, '部分待检的半成品要出现在下拉里').toBeTruthy();
    expect(all, '整行待检的也要列出 (藏起来会以为系统坏了)').toBeTruthy();
    expect(String(part!.props('label'))).toContain('余30kg');
    expect(String(part!.props('label'))).toContain('另有20kg待检');
    expect(part!.props('disabled')).toBeFalsy();
    expect(String(all!.props('label'))).toContain('待检·质检放行后可选');
    expect(all!.props('disabled')).toBe(true);
  });
});
