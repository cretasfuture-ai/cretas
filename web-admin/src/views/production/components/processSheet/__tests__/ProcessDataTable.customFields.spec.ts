import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';

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

const customFieldSchema = [
  { key: 'smartbi_oee_equipment_id', label: 'OEE设备ID', type: 'text' as const, enabled: true },
  { key: 'planned_minutes', label: '计划分钟', type: 'number' as const, enabled: true },
];

const workflowContext = {
  workflowNodeId: 'PROC-FINAL',
  workProcessId: 'WP-FINAL',
  processName: '冷冻',
  processCategory: 'PACKAGING',
  defaultCostCategory: null,
  processOrder: 2,
  plannedUnit: '盒',
  allowMultipleUpstreamSources: false,
  allowFinishedGoodsSource: false,
  customFieldSchema: null,
  inputs: [{
    workflowPortId: 'IN-1', materialNodeId: 'SEMI-NODE-1', materialKind: 'SEMI_FINISHED',
    skuId: 'SEMI-1', materialName: '羊排熟制半成品', unit: 'kg', required: true,
    skuResolved: true, finished: false,
  }],
  output: {
    workflowPortId: 'OUT-1', materialNodeId: 'SKU-NODE-1', materialKind: 'FINISHED_GOOD',
    skuId: 'SKU-1', materialName: '香辣孜然羊排', unit: '盒', gramsPerUnit: 200,
    required: true, skuResolved: true, finished: true,
  },
  outputs: [{
    workflowPortId: 'OUT-1', materialNodeId: 'SKU-NODE-1', materialKind: 'FINISHED_GOOD',
    skuId: 'SKU-1', materialName: '香辣孜然羊排', unit: '盒', gramsPerUnit: 200,
    required: true, skuResolved: true, finished: true,
  }],
};

const availableUpstream = [{
  batchNumber: 'WIP-AVAILABLE-1', produced: 12, used: 0, remaining: 12,
  status: 'ACTIVE', unit: 'kg', productTypeName: '羊排熟制半成品',
}];

function mountTable(
  viewMode: 'card' | 'grid' = 'card',
  initialRows: unknown[] = [],
) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1', processCode: 'qidiao', processOrder: 2,
      processLabel: '冷冻', productTypeId: 'SKU-1', inputUnit: 'kg', outputUnit: '盒',
      isFirstProcess: false, upstreamItems: availableUpstream, ownInventoryItems: [], initialRows,
      viewMode, workflowContext, customFieldSchema,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addRow(wrapper: ReturnType<typeof mountTable>) {
  const button = wrapper.findAll('button').find((item) => item.text().includes('新增行'));
  if (!button) throw new Error('找不到新增行按钮');
  await button.trigger('click');
  await flushPromises();
}

async function setCustomFields(wrapper: ReturnType<typeof mountTable>, equipmentId: string, plannedMinutes: number) {
  await wrapper.find('[data-testid="custom-field-smartbi_oee_equipment_id"]').setValue(equipmentId);
  await wrapper.find('[data-testid="custom-field-planned_minutes"] input').setValue(String(plannedMinutes));
}

async function setPrimaryOutput(wrapper: ReturnType<typeof mountTable>, quantity: number) {
  await wrapper.find('[data-testid="workflow-output-line"] [data-testid="output-quantity"] input')
    .setValue(String(quantity));
}

describe('ProcessDataTable custom field editors', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    saveDraftRow.mockReset();
    submitRow.mockReset();
    saveDraftRow.mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT', materialized: false } });
    submitRow.mockResolvedValue({ success: true, data: { batchNumber: 'FG-1', submissionStatus: 'SUBMITTED', materialized: true } });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  it('edits text and number custom fields in card mode and sends them to saveDraftRow', async () => {
    const wrapper = mountTable('card');
    await flushPromises();
    await addRow(wrapper);

    await setCustomFields(wrapper, 'OEE-DEVICE-01', 42);
    await wrapper.findAll('button').find((item) => item.text().includes('保存草稿'))!.trigger('click');
    await flushPromises();

    expect(saveDraftRow).toHaveBeenCalledTimes(1);
    expect(saveDraftRow.mock.calls[0][2].customFields).toEqual({
      smartbi_oee_equipment_id: 'OEE-DEVICE-01',
      planned_minutes: 42,
    });
  });

  it('edits the same fields in grid mode and sends them to submitRow', async () => {
    const wrapper = mountTable('grid');
    await flushPromises();
    await addRow(wrapper);

    await setCustomFields(wrapper, 'OEE-DEVICE-02', 55);
    await setPrimaryOutput(wrapper, 50);
    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    expect(submitRow).toHaveBeenCalledTimes(1);
    expect(submitRow.mock.calls[0][2].customFields).toEqual({
      smartbi_oee_equipment_id: 'OEE-DEVICE-02',
      planned_minutes: 55,
    });
  });

  it('keeps a formally submitted row read-only for text custom fields', async () => {
    const wrapper = mountTable('card', [{
      clientRowId: 'submitted-row', batchNumber: 'WIP-SUBMITTED-1', batchId: 1,
      rowStatus: 'SAVED', submissionStatus: 'SUBMITTED', materialized: false,
      payload: {
        clientRowId: 'submitted-row', processCode: 'qidiao', processOrder: 2,
        processDate: '2026-10-26', inputQuantity: 12, outputQuantity: 50,
        outputUnit: '盒', unit: '盒',
        upstreamSources: [{ sourceBatchNumber: 'WIP-AVAILABLE-1', feedQuantityKg: 12 }],
        customFields: { smartbi_oee_equipment_id: 'OEE-LOCKED', planned_minutes: 60 },
      },
    }]);
    await flushPromises();

    const textInput = wrapper.find('[data-testid="custom-field-smartbi_oee_equipment_id"]');
    expect(textInput.attributes('disabled')).toBeDefined();
    expect(textInput.element.value).toBe('OEE-LOCKED');
  });
});

describe('ProcessDataTable process standards on custom number fields', () => {
  const standardSchema = [
    { key: 'oil_temp', label: '油温', type: 'number' as const, enabled: true, unit: '℃', standardValue: 155, min: 150, max: 160 },
  ];

  function mountWithStandards(viewMode: 'card' | 'grid') {
    return mount(ProcessDataTable, {
      props: {
        factoryId: 'F006', planId: 'PLAN-1', processCode: 'qidiao', processOrder: 2,
        processLabel: '冷冻', productTypeId: 'SKU-1', inputUnit: 'kg', outputUnit: '盒',
        isFirstProcess: false, upstreamItems: availableUpstream, ownInventoryItems: [], initialRows: [],
        viewMode, workflowContext, customFieldSchema: standardSchema,
      },
      global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
    });
  }

  beforeEach(() => {
    vi.restoreAllMocks();
    submitRow.mockReset();
    submitRow.mockResolvedValue({ success: true, data: { batchNumber: 'FG-1', submissionStatus: 'SUBMITTED', materialized: true } });
  });

  it('shows the standard under the input and marks an out-of-range value', async () => {
    for (const mode of ['card', 'grid'] as const) {
      const wrapper = mountWithStandards(mode);
      await flushPromises();
      await addRow(wrapper as ReturnType<typeof mountTable>);
      const hint = () => wrapper.find('[data-testid="custom-field-standard-oil_temp"]');
      expect(hint().text()).toBe('标准 155℃，范围 150–160℃');
      expect(hint().classes()).not.toContain('sp-param-hint-bad');

      await wrapper.find('[data-testid="custom-field-oil_temp"] input').setValue('165');
      await flushPromises();
      expect(hint().text()).toContain('偏高');
      expect(hint().classes()).toContain('sp-param-hint-bad');
    }
  });

  it('asks for confirmation before a formal submit with an out-of-range value, and cancel keeps it unsubmitted', async () => {
    const titles: string[] = [];
    let rejectStandards = true;
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(((message: string, title: string) => {
      titles.push(title);
      if (title === '工艺参数超出标准' && rejectStandards) {
        expect(message).toContain('油温 165℃，高于标准 155℃，范围 150–160℃');
        return Promise.reject('cancel');
      }
      return Promise.resolve('confirm');
    }) as unknown as typeof ElMessageBox.confirm);

    const wrapper = mountWithStandards('grid');
    await flushPromises();
    await addRow(wrapper as ReturnType<typeof mountTable>);
    await wrapper.find('[data-testid="custom-field-oil_temp"] input').setValue('165');
    await setPrimaryOutput(wrapper as ReturnType<typeof mountTable>, 50);
    const submit = () => wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');

    await submit();
    await flushPromises();
    expect(titles).toContain('工艺参数超出标准');
    expect(submitRow).not.toHaveBeenCalled();

    rejectStandards = false;
    await submit();
    await flushPromises();
    expect(submitRow).toHaveBeenCalledTimes(1);
    expect(submitRow.mock.calls[0][2].customFields).toEqual({ oil_temp: 165 });
  });

  it('does not ask when every value is within range', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const wrapper = mountWithStandards('grid');
    await flushPromises();
    await addRow(wrapper as ReturnType<typeof mountTable>);
    await wrapper.find('[data-testid="custom-field-oil_temp"] input').setValue('155');
    await setPrimaryOutput(wrapper as ReturnType<typeof mountTable>, 50);
    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();
    expect(confirm.mock.calls.map((call) => call[1])).not.toContain('工艺参数超出标准');
    expect(submitRow).toHaveBeenCalledTimes(1);
  });
});
