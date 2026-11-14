/**
 * 14c 回料验收缺陷 (2026-11-14 F006 PLAN-1794591097738-82F4762E, QA-3MIX 画布 v3):
 *
 * 腌制道有主产出「QA-3MIX-半成品」和回料产出「QA-3MIX-回料」, 两者同属「至少选一个」组。
 * 逐道录入的回料条目 (渲染在主产出行的「不良品/副产」区) 没有「选用」复选框, 默认 selected=false;
 * 填了回料 3kg 也被提交过滤掉 —— 报工成功、确认框只列主产出、不产生 is_rework 半成品库存。
 *
 * 守的行为:
 *   ① 回料条目有「选用」复选框;
 *   ② 填了回料数量 ⇒ 自动选用, 提交载荷 outputs 里带回料行, 确认框也列出回料;
 *   ③ 填了数量却没选用 (互相替代组不自动选) ⇒ 提交被拦并说清原因, 不静默丢。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { ProcessSheetRowRequest, WorkflowPortDescriptor, WorkflowProcessDescriptor } from '@/api/processSheet';

const saveDraftRow = vi.fn();
const submitRow = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: (...args: unknown[]) => saveDraftRow(...args),
    submitRow: (...args: unknown[]) => submitRow(...args),
    deleteRow: vi.fn(),
    getRowHistory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getAvailableRawBatches: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getSemiFinishedInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getFinishedGoodsInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
  };
});

vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));

import ProcessDataTable from '../ProcessDataTable.vue';

type GroupMode = 'AT_LEAST_ONE' | 'EXACTLY_ONE';

function group(mode: GroupMode): Partial<WorkflowPortDescriptor> {
  return {
    selectionGroupId: 'marinate-outputs', selectionGroupLabel: '腌制产出', selectionGroupMode: mode,
    selectionGroupMinSelections: 1, selectionGroupMaxSelections: mode === 'EXACTLY_ONE' ? 1 : 2,
  };
}

const REWORK_PORT_ID = 'output:1794590685163';

function ports(mode: GroupMode): WorkflowPortDescriptor[] {
  return [
    {
      workflowPortId: 'output:main', materialNodeId: 'material:semi', materialKind: 'SEMI_FINISHED',
      skuId: 'PT-3MIX-SEMI', materialName: 'QA-3MIX-半成品', unit: 'kg', required: false,
      skuResolved: true, finished: false, ...group(mode),
    },
    {
      workflowPortId: REWORK_PORT_ID, materialNodeId: 'material:output:1794590685163',
      materialKind: 'SEMI_FINISHED', skuId: 'PTF0060187', materialName: 'QA-3MIX-回料', unit: 'kg',
      required: false, skuResolved: true, finished: false, rework: true, ...group(mode),
    },
  ];
}

function context(mode: GroupMode): WorkflowProcessDescriptor {
  const outputs = ports(mode);
  return {
    workflowNodeId: 'process:marinate', workProcessId: 'WP-MARINATE', processName: '腌制',
    processCategory: null, defaultCostCategory: null, processOrder: 1, plannedUnit: 'kg',
    allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false, customFieldSchema: null,
    inputs: [{
      workflowPortId: 'input:raw', materialNodeId: 'material:raw', materialKind: 'RAW_MATERIAL',
      skuId: 'RM-1', materialName: 'QA-3MIX-物料1', unit: 'kg', required: true,
      skuResolved: true, finished: false,
    }],
    output: outputs[0],
    outputs,
  };
}

function mountTable(mode: GroupMode) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1794591097738-82F4762E', processCode: 'xiuyou', processOrder: 1,
      processLabel: '腌制', productTypeId: 'PT-3MIX-SEMI', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: true, initialRows: [], upstreamItems: [], ownInventoryItems: [],
      viewMode: 'card', workflowContext: context(mode),
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function fillReport(wrapper: ReturnType<typeof mountTable>, reworkKg: number) {
  await flushPromises();
  await wrapper.findAll('button').find((button) => button.text().includes('新增行'))!.trigger('click');
  await flushPromises();
  wrapper.find('[data-testid="material-input-total"]')
    .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 10);
  wrapper.find('[data-testid="production-date"]')
    .findComponent({ name: 'ElDatePicker' }).vm.$emit('update:model-value', '2026-11-14');
  const mainLine = wrapper.find('[data-testid="workflow-output-line"]');
  mainLine.find('[data-testid="port-selected"]').findComponent({ name: 'ElCheckbox' }).vm.$emit('change', true);
  await flushPromises();
  mainLine.find('[data-testid="output-quantity"]')
    .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 7);
  wrapper.find(`[data-testid="rework-inline-quantity-${REWORK_PORT_ID}"]`)
    .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', reworkKg);
  await flushPromises();
}

function submitButton(wrapper: ReturnType<typeof mountTable>) {
  return wrapper.findAll('button').find((button) => button.text().includes('正式报工'))!;
}

describe('逐道录入: 多产出工序的回料产出要能选用并随报工入账 (14c)', () => {
  let confirm: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    saveDraftRow.mockReset();
    submitRow.mockReset();
    vi.restoreAllMocks();
    confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    submitRow.mockResolvedValue({
      success: true, data: { submissionStatus: 'SUBMITTED', materialized: true, outputs: [] },
    });
  });

  it('① 回料条目渲染「选用」复选框', async () => {
    const wrapper = mountTable('AT_LEAST_ONE');
    await flushPromises();
    await wrapper.findAll('button').find((button) => button.text().includes('新增行'))!.trigger('click');
    await flushPromises();

    const item = wrapper.find('[data-testid="rework-inline-item"]');
    expect(item.exists()).toBe(true);
    const selector = item.find(`[data-testid="rework-inline-selected-${REWORK_PORT_ID}"]`);
    expect(selector.exists()).toBe(true);
    expect(selector.findComponent({ name: 'ElCheckbox' }).props('modelValue')).toBe(false);
  });

  it('🔴 ② 填了回料 3kg ⇒ 自动选用, 提交载荷带回料行, 确认框列出回料', async () => {
    const wrapper = mountTable('AT_LEAST_ONE');
    await fillReport(wrapper, 3);

    expect(wrapper.find(`[data-testid="rework-inline-selected-${REWORK_PORT_ID}"]`)
      .findComponent({ name: 'ElCheckbox' }).props('modelValue')).toBe(true);

    await submitButton(wrapper).trigger('click');
    await flushPromises();

    expect(submitRow).toHaveBeenCalledTimes(1);
    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.outputs).toEqual(expect.arrayContaining([
      expect.objectContaining({ workflowPortId: 'output:main', productTypeId: 'PT-3MIX-SEMI', quantity: 7 }),
      expect.objectContaining({ workflowPortId: REWORK_PORT_ID, productTypeId: 'PTF0060187', quantity: 3 }),
    ]));
    expect(request.outputs).toHaveLength(2);
    const confirmText = confirm.mock.calls.map((call) => String(call[0])).join('\n');
    expect(confirmText).toContain('QA-3MIX-回料 3kg');
  });

  it('阴性对照: 回料没填数量 ⇒ 不自动选用, 载荷只有主产出', async () => {
    const wrapper = mountTable('AT_LEAST_ONE');
    await fillReport(wrapper, 0);

    await submitButton(wrapper).trigger('click');
    await flushPromises();

    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.outputs?.map((output) => output.workflowPortId)).toEqual(['output:main']);
  });

  it('🔴 ③ 互相替代组里填了回料却没选用 ⇒ 正式报工被拦, 原因写明不会入账', async () => {
    const wrapper = mountTable('EXACTLY_ONE');
    await fillReport(wrapper, 3);

    // 互相替代组不自动选 (否则会顺手取消主产出)
    expect(wrapper.find(`[data-testid="rework-inline-selected-${REWORK_PORT_ID}"]`)
      .findComponent({ name: 'ElCheckbox' }).props('modelValue')).toBe(false);
    expect(submitButton(wrapper).attributes('disabled')).toBeDefined();
    const reason = wrapper.findAllComponents({ name: 'ElTooltip' })
      .map((component) => String(component.props('content') ?? ''))
      .find((content) => content.includes('QA-3MIX-回料'));
    expect(reason).toContain('不会入账');

    await submitButton(wrapper).trigger('click');
    await flushPromises();
    expect(submitRow).not.toHaveBeenCalled();
  });
});
