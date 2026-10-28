// 缺口 04「按工艺图开工」—— Steve 2026-10-25 裁定: 上游产出的料够了就解锁。
//
// Workflow 计划的「这一道能不能正式报」以后端 workflow-config 里每道的 unlocked 为准 (与正式录入时
// 后端拦截同一份判定)。原来是「上一道已正式报工」的线性规则 —— 并行分支的第二道永远要等第一道。
// 本 spec 守三件事: 并行分支不再互等 / 等料的那道被拦并说清等谁 / 上游报完后刷新结论。
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const getInventory = vi.fn();
const getRows = vi.fn();
const getWorkflowSheetConfig = vi.fn();

vi.mock('@/api/processSheet', () => ({
  getInventory: (...args: unknown[]) => getInventory(...args),
  getRows: (...args: unknown[]) => getRows(...args),
  getWorkflowSheetConfig: (...args: unknown[]) => getWorkflowSheetConfig(...args),
}));

const getProductWorkProcesses = vi.fn();
vi.mock('@/api/processProduction', () => ({
  getProductWorkProcesses: (...args: unknown[]) => getProductWorkProcesses(...args),
}));

const getSeasoningByProduct = vi.fn();
vi.mock('@/api/bom', () => ({
  bomSeasoningApi: {
    getProcessFlagsByProduct: (...args: unknown[]) => getSeasoningByProduct(...args),
  },
}));

import ProcessSheet from '../ProcessSheet.vue';

const ProcessDataTableStub = {
  name: 'ProcessDataTable',
  props: [
    'factoryId', 'planId', 'processCode', 'processOrder', 'productTypeId', 'processLabel',
    'allowSemiFinishedInjection', 'allowMultipleUpstreamSources', 'isFirstProcess',
    'customFieldSchema', 'allowFinishedGoodsSource', 'workflowContext', 'inputUnit', 'outputUnit',
    'upstreamProcessLabel', 'upstreamItems', 'ownInventoryItems', 'initialRows', 'viewMode',
    'seasoningPotEnabled', 'seasoningConfigured',
    'upstreamSubmissionReady', 'upstreamSubmissionMessage',
  ],
  emits: ['row-saved'],
  template: '<div class="stub-process-data-table" />',
};

const port = (id: string, kind: string, sku: string, name: string) => ({
  workflowPortId: id, materialKind: kind, skuId: sku, materialName: name,
  unit: 'kg', required: true, skuResolved: true, finished: false,
});

const portWithNode = (id: string, nodeId: string) => ({
  ...port(id, 'SEMI_FINISHED', `SKU-${nodeId}`, nodeId),
  materialNodeId: nodeId,
});

function step(order: number, name: string, inputKind: string, unlocked: boolean | null, blockedReason: string | null = null) {
  const out = port(`OUT${order}`, 'SEMI_FINISHED', `SEMI-${order}`, `${name}半成品`);
  return {
    workflowNodeId: `N${order}`,
    workProcessId: `WP${order}`,
    processName: name,
    defaultCostCategory: null,
    processOrder: order,
    plannedUnit: null,
    allowMultipleUpstreamSources: true,
    allowFinishedGoodsSource: false,
    customFieldSchema: null,
    inputs: [port(`IN${order}`, inputKind, `IN-${order}`, '料')],
    output: out,
    outputs: [out],
    unlocked,
    blockedReason,
  };
}

function config(braiseUnlocked: boolean) {
  return {
    success: true,
    data: {
      workflowBatchId: 100,
      workflowInstanceId: 200,
      productTypeId: 'PT-1',
      processes: [
        step(1, '解冻', 'RAW_MATERIAL', true),
        step(2, '配辅料', 'RAW_MATERIAL', true),
        braiseUnlocked
          ? step(3, '卤制', 'SEMI_FINISHED', true)
          : step(3, '卤制', 'SEMI_FINISHED', false, '上游工序「解冻」还没有产出，本道要用它的产出，暂时不能报工'),
      ],
    },
  };
}

function mountSheet() {
  return mount(ProcessSheet, {
    props: { factoryId: 'F006', planId: 'PLAN-WF-1', productTypeId: 'PT-1', productName: '猪蹄' },
    global: {
      plugins: [ElementPlus],
      stubs: {
        ProcessDataTable: ProcessDataTableStub,
        InventoryTable: true,
        YieldCardTable: true,
        teleport: true,
        transition: false,
      },
    },
  });
}

describe('ProcessSheet.vue 按工艺图解锁 (缺口 04)', () => {
  beforeEach(() => {
    getInventory.mockReset();
    getRows.mockReset();
    getWorkflowSheetConfig.mockReset();
    getProductWorkProcesses.mockReset();
    getSeasoningByProduct.mockReset();
    getInventory.mockResolvedValue({ success: true, data: [] });
    // 没有任何一道正式报过 —— 旧规则下第 2、3 道都会被拦
    getRows.mockResolvedValue({ success: true, data: [] });
    getSeasoningByProduct.mockResolvedValue({ success: true, data: { processes: [] } });
  });

  it('parallel branch is open although the previous tab has no submitted row; the starved step is blocked with the backend reason', async () => {
    getWorkflowSheetConfig.mockResolvedValue(config(false));
    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables).toHaveLength(3);
    expect(tables[0].props('upstreamSubmissionReady')).toBe(true);
    // 旧规则: 第 1 道没正式报 → 第 2 道被拦。现在以后端结论为准, 并行分支直接能报
    expect(tables[1].props('upstreamSubmissionReady')).toBe(true);
    expect(tables[1].props('upstreamSubmissionMessage')).toBeUndefined();
    expect(tables[2].props('upstreamSubmissionReady')).toBe(false);
    expect(tables[2].props('upstreamSubmissionMessage')).toContain('解冻');
    expect(tables[2].props('upstreamSubmissionMessage')).toContain('可先保存草稿');
  });

  it('re-reads the verdict after a row is saved, so the downstream step unlocks without reopening the sheet', async () => {
    getWorkflowSheetConfig.mockResolvedValueOnce(config(false)).mockResolvedValue(config(true));
    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();
    let tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables[2].props('upstreamSubmissionReady')).toBe(false);

    tables[0].vm.$emit('row-saved', 'SUBMITTED');
    await flushPromises();
    await flushPromises();

    tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(getWorkflowSheetConfig).toHaveBeenCalledTimes(2);
    expect(tables[2].props('upstreamSubmissionReady')).toBe(true);
  });

  it('without a backend verdict (unlocked null) falls back to the old previous-tab-submitted rule', async () => {
    const cfg = config(false);
    cfg.data.processes = cfg.data.processes.map((p) => ({ ...p, unlocked: null, blockedReason: null }));
    getWorkflowSheetConfig.mockResolvedValue(cfg);
    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables[0].props('upstreamSubmissionReady')).toBe(true);
    expect(tables[1].props('upstreamSubmissionReady')).toBe(false);
    expect(tables[1].props('upstreamSubmissionMessage')).toContain('解冻');
  });

  it('parallel branch takes its source batches from the step that actually produces its input, not the previous tab', async () => {
    // 分割 一出三 (鸡胸 sB / 鸡腿 sL); 熟制 吃 sB, 滚揉 吃 sL —— 滚揉的上游是 分割, 不是前一道 熟制
    const node = (port: ReturnType<typeof portWithNode>) => port;
    getWorkflowSheetConfig.mockResolvedValue({
      success: true,
      data: {
        workflowBatchId: 100, workflowInstanceId: 200, productTypeId: 'PT-1',
        processes: [
          { ...step(1, '分割', 'RAW_MATERIAL', true),
            outputs: [node(portWithNode('O1a', 'sB')), node(portWithNode('O1b', 'sL'))] },
          { ...step(2, '熟制', 'SEMI_FINISHED', true), inputs: [portWithNode('I2', 'sB')] },
          { ...step(3, '滚揉', 'SEMI_FINISHED', true), inputs: [portWithNode('I3', 'sL')] },
        ],
      },
    });
    getInventory.mockImplementation((_f: string, _p: string, _code: string, order: number) =>
      Promise.resolve({ success: true, data: order === 1 ? [{ batchNumber: 'CLK-SPLIT-1' }] : [] }));

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables[2].props('upstreamProcessLabel')).toBe('分割');
    expect(tables[2].props('upstreamItems')).toEqual([{ batchNumber: 'CLK-SPLIT-1' }]);
    // 熟制 本来就挨着 分割, 两种算法一致
    expect(tables[1].props('upstreamProcessLabel')).toBe('分割');
  });
});
