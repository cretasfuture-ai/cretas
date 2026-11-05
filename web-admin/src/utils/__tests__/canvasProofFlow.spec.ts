import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import GeneralPanel from '@/views/platform/canvas-editor/components/AIChatPanel.vue';
import WorkProcessPanel from '@/views/system/work-processes/WorkProcessAIChatPanel.vue';
import type { CanvasDiffProof, ConfigDiff } from '@/types/canvas';
import { ELEMENT_STUBS } from './aiPanelStdHelpers';

const network = vi.hoisted(() => ({ post: vi.fn(), chat: vi.fn(), apply: vi.fn() }));
vi.mock('@/api/request', () => ({ default: { post: (...args: unknown[]) => network.post(...args) } }));
vi.mock('@/api/canvasApi', () => ({
  aiChat: (...args: unknown[]) => network.chat(...args),
  aiApplyDiffs: (...args: unknown[]) => network.apply(...args),
}));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }));

const proofA: CanvasDiffProof = {
  token: 'test-proof-first', commandDigest: 'test-digest-first', descriptorVersion: 'test-version',
};
const proofB: CanvasDiffProof = {
  token: 'test-proof-second', commandDigest: 'test-digest-second', descriptorVersion: 'test-version',
};
const wrappers: VueWrapper[] = [];

async function send(wrapper: VueWrapper) {
  await wrapper.find('textarea.stub-input').setValue('生成两项配置方案');
  const button = wrapper.findAll('button').find(item => item.text().trim() === '发送');
  if (!button) throw new Error('send button missing');
  await button.trigger('click');
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  Element.prototype.scrollTo = vi.fn();
});
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount());
  document.body.innerHTML = '';
});

describe('server proof survives the real Canvas panel flows', () => {
  it('general panel maps and emits the selected proof without mixing sibling commands', async () => {
    const firstParams = { toolCode: 'QA_FIRST', enabled: false };
    const secondParams = { field: 'quantity', required: true };
    network.post.mockResolvedValue({ data: { reply: '请逐项审核', diffs: [
      { type: 'TOOL_TOGGLE', tool: 'canvas_tool_toggle', params: firstParams, description: '第一项', proof: proofA },
      { type: 'FIELD_CHANGE', tool: 'canvas_field', params: secondParams, description: '第二项', proof: proofB },
    ] } });
    const wrapper = mount(GeneralPanel, {
      props: { factoryId: 'F006', selectedModule: 'tools' },
      global: { stubs: ELEMENT_STUBS }, attachTo: document.body,
    });
    wrappers.push(wrapper);
    await send(wrapper);
    expect(wrapper.emitted('applyDiff')).toBeUndefined();
    const buttons = wrapper.findAll('.diff-item button').filter(button => button.text().trim() === '应用');
    expect(buttons).toHaveLength(2);
    await buttons[1]!.trigger('click');
    const events = wrapper.emitted('applyDiff');
    expect(events).toHaveLength(1);
    const selected = events![0]![0] as ConfigDiff[];
    expect(selected).toHaveLength(1);
    expect(selected[0]!.path).toBe('canvas_field');
    expect(selected[0]!.after).toEqual(secondParams);
    expect(JSON.parse(JSON.stringify(selected[0]!.proof))).toEqual(proofB);
    expect(selected[0]!.proof).not.toEqual(proofA);
    // This panel stages a change; it must not submit a business write itself.
    expect(network.post).toHaveBeenCalledTimes(1);
    expect(network.apply).not.toHaveBeenCalled();
  });

  it('work-process panel submits the chosen server proof only after explicit application', async () => {
    const paramsA = { action: 'create', processName: 'QA第一道', processCategory: '加工' };
    const paramsB = { action: 'create', processName: 'QA第二道', processCategory: '加工', standardHourlyRate: 25 };
    network.chat.mockResolvedValue({ data: { reply: '请确认工序方案', applied: false, diffs: [
      { type: 'FIELD_CHANGE', tool: 'canvas_work_process_catalog', params: paramsA, description: '第一道', proof: proofA },
      { type: 'FIELD_CHANGE', tool: 'canvas_work_process_catalog', params: paramsB, description: '第二道', proof: proofB },
    ] } });
    network.apply.mockResolvedValue({ data: {
      operationId: 'test-canvas-operation', total: 1, applied: 1, reused: 0,
      items: [{ index: 0, status: 'SUCCESS', message: '测试回执' }],
    } });
    const wrapper = mount(WorkProcessPanel, {
      props: { factoryId: 'F006' }, global: { stubs: ELEMENT_STUBS }, attachTo: document.body,
    });
    wrappers.push(wrapper);
    await send(wrapper);
    expect(network.apply).not.toHaveBeenCalled();
    const buttons = wrapper.findAll('[data-testid="ai-diff-apply"]');
    expect(buttons).toHaveLength(2);
    await buttons[1]!.trigger('click');
    await flushPromises();
    expect(network.apply).toHaveBeenCalledTimes(1);
    const [factory, submitted] = network.apply.mock.calls[0]!;
    expect(factory).toBe('F006');
    expect(JSON.parse(JSON.stringify(submitted))).toEqual([{
      type: 'FIELD_CHANGE', tool: 'canvas_work_process_catalog', params: paramsB,
      description: '第二道', proof: proofB,
    }]);
  });
});
