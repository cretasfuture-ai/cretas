import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WorkProcessAIChatPanel from '../WorkProcessAIChatPanel.vue';
import { previewWorkflowAiSpec } from '../../product-processes/workflow/workflowAiPreview';
import { EMPTY_CANVAS, LARGE_SPEC, LARGE_PATHS } from '../../product-processes/workflow/__tests__/workflowAiPreview.fixture';

const io = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('@/api/request', () => ({ default: { post: io.post } }));
vi.mock('gsap', () => ({ gsap: { from: vi.fn(), fromTo: vi.fn(), killTweensOf: vi.fn() } }));

function panel() {
  const graph = structuredClone(EMPTY_CANVAS);
  const wrapper = mount(WorkProcessAIChatPanel, {
    props: { factoryId: 'F006', productTypeId: 'PT-PIG-400', endpoint: '/workflow-ai', moduleCode: 'product_process_workflow_config',
      previewSpec: spec => previewWorkflowAiSpec(graph, spec) },
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
    expect(rows.map(row => row.text()).join('\n')).toContain('<原样>');
    expect(rows.at(-1)?.text()).toContain('nullable：未设置 → null');
    const steps = wrapper.findAll('[data-testid="workflow-spec-step"]');
    expect(steps).toHaveLength(2);
    expect(steps[0].text()).toContain('类别：加工；负责人：张师傅');
    expect(steps[1].text()).toContain('负责人：1309');
    expect(steps[1].text()).toContain('"byproduct":true');
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
});
