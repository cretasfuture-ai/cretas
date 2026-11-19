import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WorkProcessAIChatPanel from '../WorkProcessAIChatPanel.vue';
import { previewWorkflowAiSpec } from '../../product-processes/workflow/workflowAiPreview';
import { EMPTY_CANVAS, LARGE_SPEC, LARGE_PATHS } from '../../product-processes/workflow/__tests__/workflowAiPreview.fixture';

const io = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('@/api/request', () => ({ default: { post: io.post } }));
vi.mock('gsap', () => ({ gsap: { from: vi.fn(), fromTo: vi.fn(), killTweensOf: vi.fn() } }));

function panel(options: { applySpec?: (draft: Record<string, unknown>) => void | Promise<{ status: 'applied' | 'aborted' }> } = {}) {
  const graph = structuredClone(EMPTY_CANVAS);
  const wrapper = mount(WorkProcessAIChatPanel, {
    props: { factoryId: 'F006', productTypeId: 'PT-PIG-400', endpoint: '/workflow-ai', moduleCode: 'product_process_workflow_config',
      previewSpec: spec => previewWorkflowAiSpec(graph, spec),
      applySpec: options.applySpec },
    global: { stubs: {
      ElPopover: { template: '<div><slot name="reference"/><slot/></div>' },
      ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"/>' },
      ElButton: { template: '<button><slot/></button>' }, ElIcon: true, ElTag: true, ElDropdown: true,
    } },
  });
  return { wrapper, graph };
}
async function send(wrapper: ReturnType<typeof panel>['wrapper']) {
  await wrapper.get('textarea').setValue('生成新的切分和装盒流程，列出所有改动');
  await wrapper.get('[aria-label="发送给 Workflow AI"]').trigger('click');
  await flushPromises();
}

describe('workflow spec preview at the actual chat entry', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() });
    io.post.mockResolvedValue({ data: { reply: '已生成可审核的 Workflow 本地补丁', diffs: [{
      type: 'PRODUCT_PROCESS_WORKFLOW_SPEC', params: { spec: LARGE_SPEC }, description: '已生成可审核的 Workflow 本地补丁',
    }] } });
  });

  it('renders all 29 real differences before applying, and emits exactly once only after clicking apply', async () => {
    const { wrapper } = panel();
    await send(wrapper);
    expect(io.post).toHaveBeenCalledOnce();
    const rows = wrapper.findAll('[data-testid="workflow-change-row"]');
    expect(rows).toHaveLength(LARGE_PATHS.length);
    expect(rows.map(row => row.attributes('data-change-path'))).toEqual(LARGE_PATHS);
    expect(wrapper.emitted('applyDraft')).toBeUndefined();
    await wrapper.get('[data-testid="workflow-spec-apply"]').trigger('click');
    await flushPromises();
    expect(wrapper.emitted('applyDraft')).toEqual([[{ spec: LARGE_SPEC }, { factoryId: 'F006', productTypeId: 'PT-PIG-400' }]]);
    expect(wrapper.find('[data-testid="workflow-spec-apply"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('cancel never emits a write and keeps the complete preview visible', async () => {
    const { wrapper } = panel();
    await send(wrapper);
    expect(wrapper.findAll('[data-testid="workflow-change-row"]')).toHaveLength(29);
    await wrapper.get('[data-testid="workflow-spec-cancel"]').trigger('click');
    expect(wrapper.text()).toContain('已取消，画布没有改动');
    expect(wrapper.emitted('applyDraft')).toBeUndefined();
    expect(wrapper.find('[data-testid="workflow-spec-apply"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('refreshes the preview if the canvas changes and requires a second explicit apply', async () => {
    const { wrapper, graph } = panel();
    await send(wrapper);
    graph.version = 2;
    await wrapper.get('[data-testid="workflow-spec-apply"]').trigger('click');
    expect(wrapper.emitted('applyDraft')).toBeUndefined();
    expect(wrapper.text()).toContain('画布已变化');
    await wrapper.get('[data-testid="workflow-spec-apply"]').trigger('click');
    expect(wrapper.emitted('applyDraft')).toHaveLength(1);
    wrapper.unmount();
  });

  it('invalidates old proposals on a new request or product switch', async () => {
    const { wrapper } = panel();
    await send(wrapper);
    await send(wrapper);
    expect(wrapper.findAll('[data-testid="workflow-spec-apply"]')).toHaveLength(1);
    await wrapper.setProps({ productTypeId: 'PT-OTHER' });
    expect(wrapper.findAll('[data-testid="workflow-spec-apply"]')).toHaveLength(0);
    expect(wrapper.emitted('applyDraft')).toBeUndefined();
    wrapper.unmount();
  });

  it('BP-38: rows that clear existing config are red and block apply until each one is confirmed', async () => {
    const rows = [
      { path: 'canvas.nodes.PROCESS:卤制#0.inputUnit', text: '工序「卤制」 · 投入单位：kg → 未设置', clears: true as const },
      { path: 'canvas.edges.removed[0]', text: '删除连接：原料「卤汁」 → 工序「卤制」', clears: true as const },
      { path: 'canvas.nodes.PROCESS:卤制#0.reportingRequired', text: '工序「卤制」 · 是否报工：免报 → 报工' },
    ];
    const applySpec = vi.fn(async () => ({ status: 'applied' as const }));
    const wrapper = mount(WorkProcessAIChatPanel, {
      props: { factoryId: 'F006', productTypeId: 'PT-PIG-400', endpoint: '/workflow-ai', moduleCode: 'product_process_workflow_config',
        previewSpec: () => ({ ok: true as const, rows, revision: 'r1' }), applySpec },
      global: { stubs: {
        ElPopover: { template: '<div><slot name="reference"/><slot/></div>' },
        ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"/>' },
        ElButton: { template: '<button><slot/></button>' }, ElIcon: true, ElTag: true, ElDropdown: true,
      } },
    });
    await send(wrapper);
    const red = wrapper.findAll('[data-testid="workflow-change-row"][data-clears="true"]');
    expect(red).toHaveLength(2);
    expect(red.every(row => row.classes('workflow-change-row--clears'))).toBe(true);
    expect(wrapper.get('[data-testid="workflow-clears-hint"]').text()).toContain('还剩 2 项未确认');
    const boxes = wrapper.findAll('[data-testid="workflow-clear-confirm"]');
    expect(boxes).toHaveLength(2);

    // 一项都没确认: 直接调用应用也拦下
    await (wrapper.vm as unknown as { applySpecProposal: (diff: unknown, id: unknown) => Promise<void> })
      .applySpecProposal((wrapper.vm as unknown as { messages: Array<{ diffPreview?: unknown[] }> }).messages.at(-1)!.diffPreview![0],
        { factoryId: 'F006', productTypeId: 'PT-PIG-400' });
    expect(applySpec).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('尚未逐项确认');

    await boxes[0].setValue(true);
    await wrapper.get('[data-testid="workflow-spec-apply"]').trigger('click');
    await flushPromises();
    expect(applySpec).not.toHaveBeenCalled(); // 只确认了一项
    expect(wrapper.get('[data-testid="workflow-clears-hint"]').text()).toContain('还剩 1 项未确认');

    await boxes[1].setValue(true);
    await wrapper.get('[data-testid="workflow-spec-apply"]').trigger('click');
    await flushPromises();
    expect(applySpec).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('uses the real apply callback once, blocks double-send while awaiting, and aborts on late permission loss', async () => {
    let resolveApply!: (result: { status: 'applied' | 'aborted' }) => void;
    const applySpec = vi.fn(() => new Promise<{ status: 'applied' | 'aborted' }>(resolve => { resolveApply = resolve; }));
    const { wrapper } = panel({ applySpec });
    await send(wrapper);
    const apply = wrapper.get('[data-testid="workflow-spec-apply"]');
    await apply.trigger('click');
    await apply.trigger('click');
    expect(applySpec).toHaveBeenCalledTimes(1);
    await wrapper.get('[data-testid="workflow-spec-cancel"]').trigger('click');
    expect(wrapper.find('[data-testid="workflow-spec-apply"]').exists()).toBe(true);
    await wrapper.get('textarea').setValue('新的请求');
    await wrapper.get('[aria-label="发送给 Workflow AI"]').trigger('click');
    expect(io.post).toHaveBeenCalledTimes(1);
    await wrapper.setProps({ disabled: true });
    resolveApply({ status: 'aborted' });
    await flushPromises();
    expect(wrapper.emitted('applyDraft')).toBeUndefined();
    expect(wrapper.find('[data-testid="workflow-spec-apply"]').exists()).toBe(true);
    wrapper.unmount();
  });
});
