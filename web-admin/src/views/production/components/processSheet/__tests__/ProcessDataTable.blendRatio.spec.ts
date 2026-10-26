/**
 * 缺口 06 配比核对 (Steve 2026-10-25: 多主料偏离配方份额超 ±2 个百分点必须填原因, 填了就放行)。
 *
 * 两层: ① blendRatio.ts 判定 (与后端 BlendRatioCheck 同口径); ② ProcessDataTable 真挂载:
 * 边填边显示配方 vs 实际、超差标红、没选原因正式报工被拦 (草稿不拦)、选了原因请求带 blendRatio。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { ProcessSheetRowRequest, WorkflowPortDescriptor, WorkflowProcessDescriptor } from '@/api/processSheet';
import { blendReasonBlockedReason, evaluateBlend, BLEND_REASONS } from '../blendRatio';

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
import BlendRatioPanel from '../BlendRatioPanel.vue';

describe('evaluateBlend (与后端 BlendRatioCheck 同口径)', () => {
  const recipe = (q: [number, number, number], unit = 'kg') => [
    { id: 'beef', name: '牛肉', share: 30, quantity: q[0], unit },
    { id: 'pork', name: '猪肉', share: 30, quantity: q[1], unit },
    { id: 'fat', name: '脂肪', share: 40, quantity: q[2], unit },
  ];

  it('Steve 的例子 25/41.7/33.3 超差, 要原因', () => {
    const ev = evaluateBlend(recipe([25, 41.7, 33.3]));
    expect(ev.status).toBe('OVER');
    if (ev.status !== 'OVER') return;
    expect(ev.lines.map((l) => [l.name, l.actualPercent, l.over])).toEqual([
      ['牛肉', 25, true], ['猪肉', 41.7, true], ['脂肪', 33.3, true],
    ]);
    expect(blendReasonBlockedReason(ev, '', '')).toContain('偏差原因');
    expect(blendReasonBlockedReason(ev, '原料不足', '')).toBeNull();
    expect(blendReasonBlockedReason(ev, '其他', ' ')).toContain('写明');
    expect(blendReasonBlockedReason(ev, '其他', '客户要求')).toBeNull();
  });

  it('边界: 恰好 2 个百分点放行, 2.5 超差', () => {
    expect(evaluateBlend(recipe([32, 28, 40])).status).toBe('WITHIN');
    expect(evaluateBlend(recipe([32.5, 27.5, 40])).status).toBe('OVER');
  });

  it('不足两种带份额的料 / 无份额 → 不适用', () => {
    expect(evaluateBlend([{ id: 'a', name: 'A', share: 30, quantity: 10, unit: 'kg' }]).status).toBe('NOT_APPLICABLE');
    expect(evaluateBlend([
      { id: 'a', name: 'A', share: null, quantity: 10, unit: 'kg' },
      { id: 'b', name: 'B', share: null, quantity: 90, unit: 'kg' },
    ]).status).toBe('NOT_APPLICABLE');
  });

  it('非 kg: 全「个」按个数比; kg+g 折成克; 个+kg 无克重 → 跳过不拦', () => {
    expect(evaluateBlend([
      { id: 'egg', name: '蛋', share: 30, quantity: 40, unit: '个' },
      { id: 'bun', name: '饼', share: 70, quantity: 60, unit: '个' },
    ]).status).toBe('OVER');
    expect(evaluateBlend([
      { id: 'a', name: 'A', share: 30, quantity: 0.3, unit: 'kg' },
      { id: 'b', name: 'B', share: 70, quantity: 700, unit: 'g' },
    ]).status).toBe('WITHIN');
    const skipped = evaluateBlend([
      { id: 'egg', name: '蛋', share: 30, quantity: 999, unit: '个' },
      { id: 'meat', name: '肉', share: 70, quantity: 1, unit: 'kg' },
    ]);
    expect(skipped.status).toBe('SKIPPED');
    expect(blendReasonBlockedReason(skipped, '', '')).toBeNull();
  });
});

const outputPort: WorkflowPortDescriptor = {
  workflowPortId: 'OUT-1', materialNodeId: 'SEMI-OUT', materialKind: 'SEMI_FINISHED',
  skuId: 'SEMI-MIX', materialName: '肉馅', unit: 'kg', required: true, skuResolved: true, finished: false,
};

function sharePort(id: string, name: string, share: number): WorkflowPortDescriptor {
  return {
    workflowPortId: id, materialNodeId: `N-${id}`, materialKind: 'RAW_MATERIAL', skuId: `RM-${id}`,
    materialName: name, unit: 'kg', required: true, skuResolved: true, finished: false, inputShareRatio: share,
  };
}

function context(): WorkflowProcessDescriptor {
  return {
    workflowNodeId: 'PROC-MIX', workProcessId: 'WP-MIX', processName: '绞肉搅拌', processCategory: 'MIX',
    defaultCostCategory: null, processOrder: 1, plannedUnit: 'kg', allowMultipleUpstreamSources: false,
    allowFinishedGoodsSource: false, customFieldSchema: null,
    inputs: [sharePort('beef', '牛肉', 30), sharePort('pork', '猪肉', 30), sharePort('fat', '脂肪', 40)],
    output: outputPort, outputs: [outputPort],
  };
}

function mountTable(viewMode: 'card' | 'grid' = 'card') {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-BLEND', processCode: 'xiuyou', processOrder: 1,
      processLabel: '绞肉搅拌', productTypeId: 'SEMI-MIX', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: true, initialRows: [], upstreamItems: [], ownInventoryItems: [],
      viewMode, workflowContext: context(),
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addRow(wrapper: ReturnType<typeof mountTable>) {
  await flushPromises();
  await wrapper.findAll('button').find((button) => button.text().includes('新增行'))!.trigger('click');
  await flushPromises();
}

async function fillInputs(wrapper: ReturnType<typeof mountTable>, quantities: number[]) {
  const lines = wrapper.findAll('[data-testid="material-input-total"]');
  quantities.forEach((q, i) => fillQuantity(lines[i], q));
  wrapper.find('[data-testid="workflow-output-line"] [data-testid="output-quantity"]')
    .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 95);
  await flushPromises();
}

function fillQuantity(line: DOMWrapper<Element>, value: number) {
  line.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', value);
}

function button(wrapper: ReturnType<typeof mountTable>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))!;
}

describe('ProcessDataTable 配比核对', () => {
  beforeEach(() => {
    saveDraftRow.mockReset();
    submitRow.mockReset();
    vi.restoreAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    saveDraftRow.mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT', materialized: false } });
    submitRow.mockResolvedValue({ success: true, data: { submissionStatus: 'SUBMITTED', materialized: true, outputs: [] } });
  });

  it('超差: 边填边显示配方/实际/偏差并标红; 没选原因正式报工被拦, 草稿照存; 选了原因请求带 blendRatio', async () => {
    const wrapper = mountTable();
    await addRow(wrapper);
    await fillInputs(wrapper, [25, 41.7, 33.3]);

    const panel = wrapper.find('[data-testid="blend-ratio-panel"]');
    expect(panel.exists()).toBe(true);
    const lines = panel.findAll('[data-testid="blend-ratio-line"]');
    expect(lines.map((l) => l.findAll('td').map((td) => td.text()))).toEqual([
      ['牛肉', '30%', '25%', '-5 个百分点'],
      ['猪肉', '30%', '41.7%', '+11.7 个百分点'],
      ['脂肪', '40%', '33.3%', '-6.7 个百分点'],
    ]);
    expect(lines[1].classes()).toContain('blend-line--over');
    expect(panel.find('[data-testid="blend-ratio-reason-area"]').exists()).toBe(true);

    // 正式报工: 按钮置灰, 原因写在 tooltip 里
    expect(button(wrapper, '正式报工').attributes('disabled')).toBeDefined();
    const tooltip = wrapper.findAllComponents({ name: 'ElTooltip' })
      .find((c) => String(c.props('content') ?? '').includes('配比'));
    expect(tooltip, '拦截原因要说清楚是配比').toBeTruthy();

    // 草稿不拦
    await button(wrapper, '保存草稿').trigger('click');
    await flushPromises();
    expect(saveDraftRow).toHaveBeenCalledTimes(1);
    expect((saveDraftRow.mock.calls[0][2] as ProcessSheetRowRequest).blendRatio).toBeUndefined();

    // 选「其他」不写说明仍拦; 写了说明放行
    panel.findComponent({ name: 'ElSelect' }).vm.$emit('update:model-value', '其他');
    await flushPromises();
    expect(button(wrapper, '正式报工').attributes('disabled')).toBeDefined();
    const note = wrapper.find('[data-testid="blend-ratio-panel"]').findComponent({ name: 'ElInput' });
    expect(note.find('input').attributes('data-testid')).toBe('blend-ratio-note');
    note.vm.$emit('update:model-value', '客户要求多加猪肉');
    await flushPromises();
    expect(button(wrapper, '正式报工').attributes('disabled')).toBeUndefined();

    await button(wrapper, '正式报工').trigger('click');
    await flushPromises();
    expect(submitRow).toHaveBeenCalledTimes(1);
    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.blendRatio).toEqual({ reason: '其他', note: '客户要求多加猪肉' });
  });

  it('下拉就是那 5 个标准原因 (选项挂在弹层里, 单独挂载面板并把下拉换成直出)', () => {
    const ev = evaluateBlend([
      { id: 'beef', name: '牛肉', share: 30, quantity: 25, unit: 'kg' },
      { id: 'pork', name: '猪肉', share: 70, quantity: 75, unit: 'kg' },
    ]);
    const panel = mount(BlendRatioPanel, {
      props: { evaluation: ev, reason: '', note: '' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          ElSelect: { template: '<div class="stub-select"><slot /></div>' },
          ElOption: { props: ['label', 'value'], template: '<span class="stub-option">{{ value }}</span>' },
        },
      },
    });
    expect(panel.findAll('.stub-option').map((o) => o.text())).toEqual([...BLEND_REASONS]);
  });

  it('容差内: 显示核对表但不要原因, 正式报工不带 blendRatio', async () => {
    const wrapper = mountTable();
    await addRow(wrapper);
    await fillInputs(wrapper, [31, 29.5, 39.5]);
    const panel = wrapper.find('[data-testid="blend-ratio-panel"]');
    expect(panel.exists()).toBe(true);
    expect(panel.find('[data-testid="blend-ratio-reason-area"]').exists()).toBe(false);
    expect(button(wrapper, '正式报工').attributes('disabled')).toBeUndefined();
    await button(wrapper, '正式报工').trigger('click');
    await flushPromises();
    expect((submitRow.mock.calls[0][2] as ProcessSheetRowRequest).blendRatio).toBeUndefined();
  });

  it('表格模式同样显示配比核对 (两套模板同步)', async () => {
    const wrapper = mountTable('grid');
    await addRow(wrapper);
    await fillInputs(wrapper, [25, 41.7, 33.3]);
    expect(wrapper.find('[data-testid="blend-ratio-panel"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid="blend-ratio-line"]')).toHaveLength(3);
  });
});
