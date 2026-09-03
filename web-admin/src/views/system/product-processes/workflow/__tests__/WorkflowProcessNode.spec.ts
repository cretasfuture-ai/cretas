import ElementPlus, { ElOption } from 'element-plus';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { defineComponent, h } from 'vue';
import WorkflowProcessNode from '../WorkflowProcessNode.vue';
import WorkflowSkuPicker from '../WorkflowSkuPicker.vue';
import type { ProcessNodeData, ProcessPort } from '../types';

/**
 * 说明文案 2026-09-01 起收进 `?` 的 el-tooltip(owner: 工序 cell 太高)。
 * ⛔ 断言不能因此删掉 —— 它守的是「这里不配比例/主副角色」这个**意思**还在被告知，
 *    不是那段文字长在页面上。⇒ 改成读 tooltip 的 content，性质不变、载体变了。
 */
const tooltipCopy = (wrapper: { findAllComponents: (m: { name: string }) => Array<{ props: (k: string) => unknown }> }) =>
  wrapper.findAllComponents({ name: 'ElTooltip' }).map((t) => String(t.props('content') ?? '')).join(' | ');

const SEMI_OPTIONS = [{ id: 'SKU-SEMI-1', name: '半成品 A', unit: 'kg' }];
const FINISHED_OPTIONS = [{ id: 'SKU-FIN-1', name: '成品 A', unit: '盒' }];

const processData: ProcessNodeData = {
  workProcessId: 'WP-CUT',
  processName: 'Cutting',
  inputUnit: 'kg',
  outputUnit: 'kg',
  ports: [
    { id: 'input-1', direction: 'INPUT', unit: 'kg', ordinal: 0 },
    {
      id: 'output-1',
      direction: 'OUTPUT',
      materialNodeId: 'material-1',
      materialKind: 'SEMI_FINISHED',
      unit: 'kg',
      quantityMode: 'AUTO_CONVERT',
      ordinal: 0,
    },
  ],
  conversionRule: { mode: 'ACTUAL_WEIGHT' },
  reportingRequired: true,
};

const PositionedHandleStub = defineComponent({
  name: 'Handle',
  inheritAttrs: false,
  props: {
    id: { type: String, required: true },
    type: { type: String, required: true },
    position: { type: String, required: true },
    connectable: { type: Boolean, default: true },
    style: { type: Object, required: true },
  },
  setup(props) {
    return () => h('button', {
      type: 'button',
      'data-testid': 'workflow-handle',
      'data-handle-id': props.id,
      'data-handle-type': props.type,
      'data-position': props.position,
      'data-connectable': String(props.connectable),
      style: [props.style, { width: '10px', height: '10px' }],
    });
  },
});

function mountNode(selected = true, data: ProcessNodeData = processData) {
  return mount(WorkflowProcessNode, {
    props: {
      data,
      selected,
      canWrite: true,
      semiOptions: SEMI_OPTIONS,
      finishedOptions: FINISHED_OPTIONS,
      skuSpecifications: {
        'SKU-FIN-800': { unit: '盒', gramsPerUnit: 800 },
      },
    },
    global: {
      plugins: [ElementPlus],
      stubs: { Handle: PositionedHandleStub },
    },
  });
}

function withPorts(overrides: Partial<ProcessNodeData>): ProcessNodeData {
  return {
    ...processData,
    ...overrides,
  };
}

describe('WorkflowProcessNode output gestures', () => {
  it('uses one inline add-output action and emits addOutput once', async () => {
    const wrapper = mountNode(false);

    expect(wrapper.find('[data-testid="add-output-inline"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="add-output-edge"]').exists()).toBe(false);
    await wrapper.get('[data-testid="add-output-inline"]').trigger('click');
    expect(wrapper.emitted('addOutput')).toHaveLength(1);
  });

  /**
   * 🔴 2026-09-03 客户实拍:「点了加号之后这个就没途径删掉了」。
   *
   * 加号 (`add-output-inline`) 就在产出区里, 而删除入口只长在画布上 480px 外的
   * 那个物料节点上 —— 于是「能删」这件事在用户那里等于不存在。
   *
   * ⚠️ 这里断言的是**移除入口与加号同处一地**, 不是「产出删得掉」——
   * 后者本来就成立(选中物料节点删), 而客户报的正是前者。
   */
  it('每个产出行都有移除入口, 点击 emit removeOutput(portId)', async () => {
    const wrapper = mountNode();

    const removes = wrapper.findAll('[data-testid="remove-output-port"]');
    // 一个产出端口 ⇒ 一个移除按钮。数量对齐, 不是「页面上有个叉」。
    expect(removes).toHaveLength(1);

    await removes[0].trigger('click');
    // 带 portId ——画布层要靠它换成 materialNodeId 再走 removeNode。
    expect(wrapper.emitted('removeOutput')).toEqual([['output-1']]);
  });

  it('阴性对照: 只读态不给移除入口(与加号同一条 canWrite 闸)', () => {
    const wrapper = mount(WorkflowProcessNode, {
      props: {
        data: processData,
        selected: true,
        canWrite: false,
        semiOptions: SEMI_OPTIONS,
        finishedOptions: FINISHED_OPTIONS,
        skuSpecifications: {},
      },
      global: { plugins: [ElementPlus], stubs: { Handle: PositionedHandleStub } },
    });

    expect(wrapper.find('[data-testid="remove-output-port"]').exists()).toBe(false);
    // 阳性对照: 只读时加号也不在 ⇒ 上面那个 false 不是「按钮根本没做出来」。
    expect(wrapper.find('[data-testid="add-output-inline"]').exists()).toBe(false);
  });

  it('does not expose a manual output material kind selector', () => {
    const wrapper = mountNode();

    expect(wrapper.find('[data-testid="output-kind-select"]').exists()).toBe(false);
    expect(wrapper.find('.kind-select').exists()).toBe(false);
  });

  it('exposes a clear pencil action for editing the real process master data', async () => {
    const wrapper = mountNode();
    const button = wrapper.get('[data-testid="quick-edit-process"]');

    expect(button.attributes('title')).toBe('快捷编辑工序');
    expect(button.attributes('aria-label')).toBe('快捷编辑工序');
    await button.trigger('click');
    expect(wrapper.emitted('editProcess')).toHaveLength(1);
  });

  it('P3: exposes an editable output SKU picker on each output row and emits selectOutput(portId, skuId)', () => {
    const wrapper = mountNode();
    // 现在允许在工序 Cell 上直接选/改产出物料的 SKU (不再只能通过右侧物料 Cell)。
    const picker = wrapper.findComponent(WorkflowSkuPicker);
    expect(picker.exists()).toBe(true);
    expect(picker.props('semiOptions')).toEqual(SEMI_OPTIONS);
    expect(picker.props('finishedOptions')).toEqual(FINISHED_OPTIONS);

    picker.vm.$emit('change', 'SKU-FIN-1');

    expect(wrapper.emitted('selectOutput')).toEqual([['output-1', 'SKU-FIN-1']]);
  });

  it('renders investment units as a read-only chip and output units as a live chip (unit follows the picked SKU)', () => {
    const wrapper = mountNode();

    expect(wrapper.find('.unit-select').exists()).toBe(false);
    expect(wrapper.get('[data-testid="input-unit-chip"]').text()).toBe('kg');
    expect(wrapper.get('[data-testid="output-unit-chip"]').text()).toBe('kg');
  });

  it('renders the produced material as an editable SKU picker on the output row, not a readonly input', () => {
    const wrapper = mountNode();
    const outputRow = wrapper.get('.output-row');

    expect(outputRow.findComponent(WorkflowSkuPicker).exists()).toBe(true);
    expect(outputRow.find('input[readonly]').exists()).toBe(false);
  });

  it('keeps the investment row read-only (only the output row became editable)', () => {
    const wrapper = mountNode();
    const inputRow = wrapper.get('.port-section:not(.output-section) .port-row');

    expect(inputRow.find('input').attributes('readonly')).toBeDefined();
    expect(inputRow.findComponent(WorkflowSkuPicker).exists()).toBe(false);
  });

  it('does not emit addOutput when a workflow handle is clicked', async () => {
    const wrapper = mountNode();

    await wrapper.get('[data-testid="workflow-handle"]').trigger('click');

    expect(wrapper.emitted('addOutput')).toBeUndefined();
  });

  it('keeps the source handle for drawing edges without restoring the duplicate plus action', () => {
    const wrapper = mountNode();
    const sourceHandle = wrapper.get('[data-handle-type="source"]');

    expect(sourceHandle.attributes('data-position')).toBe('right');
    expect(wrapper.find('[data-testid="add-output-edge"]').exists()).toBe(false);
  });

  it('provides a non-connectable top anchor for the auxiliary BOM projection line', () => {
    const wrapper = mountNode();
    const handle = wrapper.get('[data-handle-id="bom-aux-in"]');

    expect(handle.attributes('data-handle-type')).toBe('target');
    expect(handle.attributes('data-position')).toBe('top');
    expect(handle.attributes('data-connectable')).toBe('false');
  });
});

describe('WorkflowProcessNode 系统研判 + 数量关系 (P2)', () => {


  it('shows unit flow without inventing an input-to-output quantity', () => {
    const wrapper = mountNode();

    expect(wrapper.get('[data-testid="unit-only-summary"]').text()).toContain('投入单位：kg · 产出单位：kg');
    expect(wrapper.get('[data-testid="unit-flow-input"]').text()).toContain('kg投入 → kg产出');
    expect(tooltipCopy(wrapper)).toContain('实际投入、实际产出和出成率以正式报工为准');
    expect(wrapper.findComponent({ name: 'ElInputNumber' }).exists()).toBe(false);
  });



  it('keeps input and output relations in formal reporting instead of Workflow', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '主料入口', unit: 'kg', ordinal: 0 },
      { id: 'in-2', direction: 'INPUT', materialName: '替代入口', unit: 'kg', ordinal: 1 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '400g 成品', unit: '袋', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', materialName: '300g 成品', unit: '袋', ordinal: 1 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.get('[data-testid="system-inference"]').text())
      .toContain('2 个投入 · 2 个产出');
    expect(tooltipCopy(wrapper)).toContain('投入和产出至少各一项');
    expect(tooltipCopy(wrapper)).toContain('主料和替代料由生产计划固定的 BOM 自动限定');
    expect(tooltipCopy(wrapper)).toContain('不配置产出关系、主副角色或固定比例');
    expect(wrapper.find('[data-testid="input-relation-select"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="output-port-relation"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid^="output-contract-"]').exists()).toBe(false);
  });

  it('does not offer a generic conversion mode selector', () => {
    const wrapper = mountNode();

    const modeOptionValues = wrapper.findAllComponents(ElOption)
      .map((option) => option.props('value'))
      .filter((value): value is string => (
        typeof value === 'string' && ['ACTUAL_WEIGHT', 'FIXED_RATIO', 'SUM_OUTPUTS', 'FORMULA'].includes(value)
      ));

    expect(modeOptionValues).toEqual([]);
    expect(wrapper.find('.conversion-select').exists()).toBe(false);
  });

  it('labels inputs by display order and gives every input its own unit flow', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '主料', unit: 'kg', quantityMode: 'FIXED_RATIO', standardQuantity: 1, ordinal: 0 },
      { id: 'in-2', direction: 'INPUT', materialName: '调味料', unit: 'g', quantityMode: 'FIXED_RATIO', standardQuantity: 25, ordinal: 1 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '分割肉', unit: 'kg', quantityMode: 'AUTO_CONVERT', ordinal: 0 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.text()).toContain('投入1');
    expect(wrapper.text()).toContain('投入2');
    expect(wrapper.text()).not.toContain('主投入');
    expect(wrapper.text()).not.toContain('追加投入');
    expect(wrapper.text()).not.toContain('端口');
    const quantityInputs = wrapper.findAllComponents({ name: 'ElInputNumber' });
    expect(quantityInputs).toHaveLength(0);
    expect(wrapper.findAll('[data-testid="unit-flow-input"]')).toHaveLength(2);
    expect(wrapper.findAll('[data-testid="unit-flow-input"]')[0].text()).toContain('kg投入 → kg产出');
    expect(wrapper.findAll('[data-testid="unit-flow-input"]')[1].text()).toContain('g投入 → kg产出');
    expect(wrapper.text()).toContain('调味料');
  });

  it('switches only an actually long material row to the stacked responsive layout', () => {
    const ports: ProcessPort[] = [
      { id: 'short', direction: 'INPUT', materialName: '原料A', unit: 'kg', standardQuantity: 1, ordinal: 0 },
      {
        id: 'long', direction: 'INPUT', materialName: 'E2E-替代链-处理后超长半成品原料名称',
        unit: 'kg', standardQuantity: 1, ordinal: 1,
      },
      { id: 'out', direction: 'OUTPUT', materialName: '袋装产出', unit: '袋', standardQuantity: 1, ordinal: 0 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));
    const rows = wrapper.findAll('.unit-relationship-row');

    expect(rows[0].classes()).not.toContain('is-long-name');
    expect(rows[1].classes()).toContain('is-long-name');
    expect(rows[1].text()).toContain('E2E-替代链-处理后超长半成品原料名称');
  });

  it('shows the finished SKU specification as downstream conversion truth without an editable ratio', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '包装前产品', unit: 'kg', ordinal: 0 },
      {
        id: 'out-1', direction: 'OUTPUT', skuId: 'SKU-FIN-800', materialName: '成品800g',
        unit: '盒', quantityMode: 'FIXED_RATIO', standardQuantity: 9, ordinal: 0,
      },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.get('[data-testid="unit-only-summary"]').text()).toContain('投入单位：kg · 产出单位：盒');
    expect(wrapper.get('[data-testid="unit-only-summary"]').text()).toContain('规格 1盒 = 800g');
    expect(wrapper.get('[data-testid="unit-only-summary"] small').attributes('title'))
      .toBe('成品 SKU 规格：1盒 = 800g，计划、报工与结单需要折算时自动使用。');
    expect(wrapper.get('[data-testid="unit-flow-input"]').text()).toContain('kg投入 → 盒产出');
    expect(wrapper.findComponent({ name: 'ElInputNumber' }).exists()).toBe(false);
  });

  it('does not emit a quantity patch for semantic units either', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '整猪', unit: '只', quantityMode: 'FIXED_RATIO', standardQuantity: 1, ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '半只分割', unit: '件', quantityMode: 'FIXED_RATIO', standardQuantity: 2, ordinal: 0 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.get('[data-testid="unit-flow-input"]').text()).toContain('只投入 → 件产出');
    expect(wrapper.findComponent({ name: 'ElInputNumber' }).exists()).toBe(false);
    expect(wrapper.emitted('update')).toBeUndefined();
  });

  it('#4: gracefully leaves the ratio unset (returns null, does not crash) for legacy free-text expressions that are not the canonical shape', () => {
    const wrapper = mountNode(true, withPorts({
      conversionRule: { mode: 'FIXED_RATIO', expression: '1 只 = 1 只 / 100:90 保水后' },
    }));

    // 旧自由文本里第一个 "1<unit>=<n><unit>" 片段仍然可能被解析出一个数字
    // (best-effort 前向兼容), 但绝不能抛错、也不会静默改写原始存量 expression
    // (只有用户真的动了数字输入框才会写回新的规范格式)。
    expect(wrapper.find('[data-testid="fixed-ratio-row"]').exists()).toBe(false);
    expect(wrapper.emitted('update')).toBeUndefined();
  });

  it('does not expose legacy FORMULA configuration', () => {
    const wrapper = mountNode(true, withPorts({
      conversionRule: { mode: 'FORMULA', expression: '(产出1 + 产出2) * 0.95' },
    }));

    expect(wrapper.find('[data-testid="legacy-mode-hint"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="fixed-ratio-row"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="conversion-expression-input"]').exists()).toBe(false);
  });

  it('does not expose legacy SUM_OUTPUTS configuration', () => {
    const wrapper = mountNode(true, withPorts({
      conversionRule: { mode: 'SUM_OUTPUTS', expression: null },
    }));

    expect(wrapper.find('[data-testid="legacy-mode-hint"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="conversion-expression-input"]').exists()).toBe(false);
  });

  it('no longer renders a separate 样例 example line (trimmed per config-driven UX simplification)', () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', unit: 'kg', ordinal: 0 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '瘦肉出品', unit: 'kg', ordinal: 0 },
      { id: 'out-2', direction: 'OUTPUT', materialName: '肥肉出品', unit: 'kg', ordinal: 1 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));

    expect(wrapper.find('[data-testid="conversion-sample"]').exists()).toBe(false);
    expect(wrapper.find('.conversion-example').exists()).toBe(false);
  });

  it('P3 (was "P1 guarantees"): output SKU picker now lives on the process Cell too; units stay read-only chips', () => {
    const wrapper = mountNode();

    // 反转过去 P1 的保证: SKU 选择器现在可以出现在工序 Cell 上 (产出行), 不再是
    // "只能在右侧物料 Cell 上选" —— 这是本轮改造的核心需求 #1。
    expect(wrapper.findComponent(WorkflowSkuPicker).exists()).toBe(true);
    // 单位仍然是自动跟随所选 SKU 的只读 chip, 不是手动可编辑的下拉/输入框。
    expect(wrapper.find('.unit-select').exists()).toBe(false);
    expect(wrapper.get('[data-testid="input-unit-chip"]').text()).toBe('kg');
    expect(wrapper.get('[data-testid="output-unit-chip"]').text()).toBe('kg');
  });


});

describe('WorkflowProcessNode 主原料份额 (owner 2026-09-02 走 (a))', () => {
  it('多于一个投入时每个 INPUT 行有份额输入; 改动通过 update 回写整份 ports', async () => {
    const ports: ProcessPort[] = [
      { id: 'in-1', direction: 'INPUT', materialName: '2082上脑', unit: 'kg', ordinal: 0 },
      { id: 'in-2', direction: 'INPUT', materialName: '1399上脑', unit: 'kg', ordinal: 1 },
      { id: 'out-1', direction: 'OUTPUT', materialName: '缓化肉块', unit: 'kg', ordinal: 0 },
    ];
    const wrapper = mountNode(true, withPorts({ ports }));
    const shares = wrapper.findAll('[data-testid="input-share"]');
    expect(shares.length).toBe(2);
    await shares[0].setValue('30');
    await shares[0].trigger('change');
    const updates = wrapper.emitted('update') ?? [];
    const last = updates[updates.length - 1]?.[0] as { ports?: ProcessPort[] } | undefined;
    expect(last?.ports?.find((p) => p.id === 'in-1')?.inputShareRatio).toBe(30);
    expect(last?.ports?.find((p) => p.id === 'in-2')?.inputShareRatio ?? null).toBeNull();
    expect(last?.ports?.length).toBe(3);
  });

  it('阴性对照: 只有一个投入 → 没有份额输入 (单投入无配比可言)', () => {
    const wrapper = mountNode();
    expect(wrapper.findAll('[data-testid="input-share"]').length).toBe(0);
  });
});
