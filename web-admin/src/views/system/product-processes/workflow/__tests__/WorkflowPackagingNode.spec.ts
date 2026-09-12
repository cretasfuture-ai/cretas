import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import WorkflowPackagingNode from '../WorkflowPackagingNode.vue';
import type { PackagingCellData } from '../WorkflowPackagingNode.vue';

// WorkflowPackagingNode 用 @vue-flow/core 的 <Handle>，真实实现要求组件挂在 <VueFlow>
// provider 下才能拿到画布上下文；单测只关心包材 cell 本身的展示/交互逻辑，跟连线
// handle 无关，桩掉即可（同 WorkflowMaterialNode.spec.ts 的做法）。
const HandleStub = defineComponent({
  name: 'Handle',
  inheritAttrs: false,
  props: { id: String, type: String, position: String },
  setup(props) {
    return () => h('span', {
      'data-testid': 'handle-stub',
      'data-handle-id': props.id,
      'data-handle-type': props.type,
      'data-position': props.position,
    });
  },
});

const data: PackagingCellData = {
  outputName: '鸭油',
  outputNodeId: 'o1',
  baseUnit: 'kg',
  rows: [{
    id: 'r1', materialName: '塑料桶 20L', dosageText: '0.05 个/kg',
    naturalHint: '= 1 个 / 20 kg', markers: [],
  }],
};

describe('包材 cell', () => {
  it('renders the exact bottom source handle used by the derived edge', () => {
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data },
    });
    const handle = w.get('[data-testid="handle-stub"]');
    expect(handle.attributes('data-handle-id')).toBe('bom-pack-out');
    expect(handle.attributes('data-handle-type')).toBe('source');
    expect(handle.attributes('data-position')).toBe('bottom');
  });

  it('副标题带出分母', () => {
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data } });
    expect(w.text(), '「1 个/盒」和「1 个/kg」不是一回事, 分母必须写出来').toContain('每 1 kg');
  });

  it('换算过的用量保留原始表达', () => {
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data } });
    const cell = w.find('[data-testid="pack-qty-r1"]');
    expect(cell.attributes('title'), '「0.05 个」对仓管毫无意义, 「1 桶装 20kg」才是他认识的').toContain('20 kg');
  });

  it('没有 naturalHint 时不设置 title 属性(而不是空串)', () => {
    const noHint: PackagingCellData = {
      ...data,
      rows: [{ id: 'r2', materialName: '标签纸', dosageText: '1 张/kg', markers: [] }],
    };
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: noHint } });
    const cell = w.find('[data-testid="pack-qty-r2"]');
    expect(cell.attributes('title')).toBeUndefined();
  });

  it('多层包装副标题说层数', () => {
    const layered: PackagingCellData = { ...data, rows: [
      { id: 'a', materialName: '内袋', dosageText: '1 个/盒', markers: [{ glyph: '▤', kind: 'lvl', title: '内袋' }] },
      { id: 'b', materialName: '外箱', dosageText: '0.125 个/盒', markers: [{ glyph: '▤', kind: 'lvl', title: '1 箱 8 盒' }] },
    ] };
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: layered } });
    expect(w.text()).toContain('分 2 层');
    expect(w.find('[data-testid="pack-marker-lvl"]').exists()).toBe(true);
  });

  it('空态如实说明当前状态, 不编造系统不会执行的发布后果', () => {
    // must-fix #4: 查证 validateWorkflow('publish') 与后端 BOM 激活校验都不存在
    // "缺包材不能发布"这条规则(BOM 只要整体 ≥1 条明细即可, 不专门要求包材), 旧文案
    // 「缺包材，本条工艺发布不了」是一个代码给不出证据的具体诊断, 已改为如实描述。
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: { ...data, rows: [] } } });
    expect(w.text()).toContain('未配');
    expect(w.text(), '不能再断言一条系统实际不执行的发布后果').not.toContain('发布不了');
    expect(w.find('[data-testid="pack-empty"]').exists()).toBe(true);
  });

  it('pack-add 触发 add-row 事件', async () => {
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data } });
    await w.find('[data-testid="pack-add"]').trigger('click');
    expect(w.emitted('add-row')).toBeTruthy();
  });

  it('点击行触发 open-row-detail 事件, 携带 rowId', async () => {
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data } });
    await w.find('[data-testid="pack-qty-r1"]').trigger('click');
    expect(w.emitted('open-row-detail')?.[0]).toEqual(['r1']);
  });

  it('行可键盘激活: Enter / Space 与点击等价(无障碍)', async () => {
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data } });
    const row = w.find('.packaging-row');
    expect(row.attributes('role')).toBe('button');
    expect(row.attributes('tabindex')).toBe('0');
    expect(row.attributes('aria-label')).toContain('塑料桶 20L');
    await row.trigger('keydown.enter');
    await row.trigger('keydown.space');
    expect(w.emitted('open-row-detail')?.length).toBe(2);
  });

  it('缺失用量渲染显式占位, 不降级成 0 或空白', () => {
    const missingQty: PackagingCellData = {
      ...data,
      rows: [{ id: 'r3', materialName: '内衬纸', dosageText: '', markers: [] }],
    };
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: true, data: missingQty } });
    const cell = w.find('[data-testid="pack-qty-r3"]');
    expect(cell.text()).not.toBe('0');
    expect(cell.text()).not.toBe('');
    expect(cell.text()).toContain('待补全');
  });

  it('只读用户(canWrite=false)不给添加包材入口', () => {
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: false, data },
    });
    expect(w.find('[data-testid="pack-add"]').exists()).toBe(false);
  });

  // 判据换代同 WorkflowAuxiliaryNode.spec.ts —— 见那里的长注释。
  // ⚠️ 这条只管「事件发出来了」这一半; 「发出来之后没走到写入口」由
  //    bomReadonlyDetail.behavior.spec.ts 在父组件那一层守, ⛔ 两条缺一不可。
  it('只读用户点击行照样发出 open-row-detail(通向只读明细, 不是编辑)', async () => {
    const w = mount(WorkflowPackagingNode, {
      global: { stubs: { Handle: HandleStub } },
      props: { id: 'x', canWrite: false, data },
    });
    await w.find('[data-testid="pack-qty-r1"]').trigger('click');
    expect(w.emitted('open-row-detail')?.[0]).toEqual(['r1']);
    expect(w.emitted('add-row')).toBeFalsy();
  });
});
