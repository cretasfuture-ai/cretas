import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  askIntent: vi.fn(),
}));

vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: (...args: unknown[]) => mocks.askIntent(...args),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F001', businessDomain: 'FACTORY' }),
}));

vi.mock('element-plus', () => ({
  ElInput: { name: 'ElInput', template: '<input />' },
  ElButton: { name: 'ElButton', template: '<button @click="$emit(\'click\')"><slot /></button>' },
  ElRadioButton: { name: 'ElRadioButton', template: '<button><slot /></button>' },
  ElRadioGroup: { name: 'ElRadioGroup', template: '<div><slot /></div>' },
  ElMessage: { error: vi.fn(), success: vi.fn() },
}));

vi.mock('../../chat/ChatBubble.vue', () => ({
  default: {
    name: 'ChatBubble',
    props: ['turn'],
    template: '<div class="stub-bubble" :data-role="turn.role">{{ turn.content }}<slot name="followups" /></div>',
  },
}));

import AnalysisChatPanel from '../AnalysisChatPanel.vue';

const mountPanel = () => mount(AnalysisChatPanel, {
  props: {
    factoryId: 'F001',
    pageTitle: '经营分析',
    contexts: [{
      key: 'void-audit',
      title: '撤单稽核',
      dataSummary: '撤单率 0.55%',
      dataScope: '2026-07-01 至 2026-07-27',
      unavailableMetrics: ['逐笔撤单原因'],
    }],
  },
  global: {
    stubs: {
      ChatTypingIndicator: { template: '<div class="stub-typing" />' },
    },
  },
});

describe('AnalysisChatPanel unified intent routing', () => {
  beforeEach(() => {
    mocks.askIntent.mockReset();
    if (typeof globalThis.crypto?.randomUUID !== 'function') {
      Object.defineProperty(globalThis, 'crypto', {
        value: { randomUUID: () => Math.random().toString(36).slice(2) },
        configurable: true,
      });
    }
  });

  it('routes a factory question through the Java READ orchestrator without polluting the question', async () => {
    mocks.askIntent.mockResolvedValue({
      status: 'SUCCESS',
      formattedText: '你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。',
      source: 'deterministic_fallback',
      followUpActions: [
        { label: '最近30天', question: '最近30天哪些生产线良率最低' },
      ],
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (text?: string) => Promise<void> };
    await vm.sendMessage('哪些工厂指标需要关注');
    await flushPromises();

    expect(mocks.askIntent).toHaveBeenCalledTimes(1);
    const [factoryId, question, options] = mocks.askIntent.mock.calls[0];
    expect(factoryId).toBe('F001');
    expect(question).toBe('哪些工厂指标需要关注');
    expect(options).toMatchObject({ sessionId: expect.any(String), mode: 'READ' });
    expect(options.context).toMatchObject({ dimensionHints: ['void-audit'] });
    expect(options.context.pageContext).toContain('撤单率 0.55%');
    expect(question).not.toContain('撤单率');

    const aiBubble = wrapper.find('.stub-bubble[data-role="ai"]');
    expect(aiBubble.text()).toContain('时间范围');

    const followUp = wrapper.findAll('button')
      .find((button) => button.text() === '最近30天');
    expect(followUp).toBeDefined();
    await followUp!.trigger('click');
    await flushPromises();
    expect(mocks.askIntent.mock.calls[1][1]).toBe('最近30天哪些生产线良率最低');
  });

  it('keeps comprehensive answers and charts returned by the same orchestrator', async () => {
    mocks.askIntent.mockResolvedValue({
      status: 'SUCCESS',
      formattedText: '### 本轮已纳入维度\n- 产量与质量\n### 尚缺但可补充的维度\n- 设备稼动',
      source: 'deterministic_fallback',
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (text?: string) => Promise<void> };
    await vm.sendMessage('请综合分析工厂生产与质量情况');
    await flushPromises();

    expect(mocks.askIntent).toHaveBeenCalledWith(
      'F001',
      '请综合分析工厂生产与质量情况',
      expect.objectContaining({ sessionId: expect.any(String), mode: 'READ' }),
    );
    expect(wrapper.find('.stub-bubble[data-role="ai"]').text()).toContain('尚缺但可补充的维度');
  });

  it('renders an honest failure instead of fabricating a fallback answer', async () => {
    mocks.askIntent.mockResolvedValue({
      status: 'FAILED',
      message: 'Java intent service unavailable',
    });

    const wrapper = mountPanel();
    const vm = wrapper.vm as unknown as { sendMessage: (text?: string) => Promise<void> };
    await vm.sendMessage('哪些工厂指标需要关注');
    await flushPromises();

    const aiBubble = wrapper.find('.stub-bubble[data-role="ai"]');
    expect(aiBubble.text()).toContain('这次没分析完');
    expect(aiBubble.text()).not.toContain('Java intent service unavailable');
  });
});
