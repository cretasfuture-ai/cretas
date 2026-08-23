import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WorkProcessAIChatPanel from '../WorkProcessAIChatPanel.vue';

const canvasApiMocks = vi.hoisted(() => ({
  aiChat: vi.fn(),
  aiApplyDiffs: vi.fn(),
}));

vi.mock('@/api/canvasApi', () => ({
  aiChat: canvasApiMocks.aiChat,
  aiApplyDiffs: canvasApiMocks.aiApplyDiffs,
}));

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}));

interface PanelVm {
  input: string;
  mode: string;
  send: () => Promise<void>;
}

function mountPanel() {
  return shallowMount(WorkProcessAIChatPanel, {
    props: { factoryId: 'F006' },
  });
}

async function sendAs(mode: string, text: string) {
  canvasApiMocks.aiChat.mockResolvedValue({
    data: { reply: '将创建工序: 焯水', diffs: [], applied: false },
  });
  const wrapper = mountPanel();
  const vm = wrapper.vm as unknown as PanelVm;
  vm.mode = mode;
  vm.input = text;
  await vm.send();
  await flushPromises();
  return canvasApiMocks.aiChat.mock.calls[0][1] as Record<string, unknown>;
}

/**
 * The panel used to build an LLM instruction prompt in the browser and post it as
 * `message`. Nothing ever read it: the work_process_catalog route hands `message`
 * straight to the tool instead of to a model. So the prompt was dead weight, and it
 * was also a second copy of the tool's parameter schema sitting in the frontend,
 * free to drift away from the real one.
 */
describe('WorkProcessAIChatPanel sends the operator’s own words', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
      configurable: true,
      value: vi.fn(),
    });
  });

  it('posts the raw user text as message, with no prompt scaffolding', async () => {
    const text = '新建焯水工序，加工类，标准时薪25';
    const payload = await sendAs('plan', text);

    expect(payload.message).toBe(text);
    expect(payload.moduleCode).toBe('work_process_catalog');
  });

  it('does not ship a copy of the tool parameter schema to the server', async () => {
    const payload = await sendAs('plan', '新建焯水工序');
    const message = String(payload.message);

    // Each of these appeared in the old browser-side prompt. They are the tool's
    // contract; it lives on the server now, so none of them may travel from here.
    for (const leak of [
      'canvas_work_process_catalog',
      'standardYieldMin',
      'processCategory',
      'FIELD_CHANGE',
      '必须使用工具',
    ]) {
      expect(message).not.toContain(leak);
    }
  });

  it.each(['plan', 'autopilot', 'action'])(
    'sends the selected mode %s unchanged alongside the raw text',
    async (mode) => {
      const payload = await sendAs(mode, '新建焯水工序');

      // The three segmented entries differ only by this field — they are one path.
      expect(payload.mode).toBe(mode);
      expect(payload.message).toBe('新建焯水工序');
    },
  );

  it('trims the input but otherwise sends it verbatim', async () => {
    const payload = await sendAs('plan', '  新建焯水工序  ');
    expect(payload.message).toBe('新建焯水工序');
  });
});
