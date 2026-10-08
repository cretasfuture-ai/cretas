/**
 * ApprovalWorkflowAIComposer (审批画布底部的 AI 作曲栏 + 对话记录弹层) 按 AI 对话标准重构后的行为测试。
 * 真实挂载, 只 mock 网络 (@/api/request) 与 Element Plus 替身 (el-popover 替身尊重 visible, 以便验证「发送后弹层打开」)。
 *
 * 它是对话界面: 用户发一句话 -> 助手回一条, 历史在弹层里。判断见 PR 描述。
 *
 * 🔴 「套用到草稿」这一步 (H2 / H4):
 *   这个组件没有「应用」按钮 —— 回复里带 APPROVAL_WORKFLOW_SPEC 就直接调 props.applySpec(spec) 套到本地草稿 (可撤销, 不落库)。
 *   所以守的是: 没发之前 0 次; 回复里有 spec 恰好 1 次且参数就是那个 spec 对象; 回复里没有 spec / 超时 / 失败 0 次;
 *   有多个 spec 时只用第一个 (改前行为)。它也没有 diff 预览列表 (approvalWorkflowUx.source.spec 钉着), 所以没有「藏条目」这个维度。
 *   BASELINE 是在 origin/main 的旧组件上跑同一份 fixture 得到的。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { resolve } from 'node:path';
import ApprovalWorkflowAIComposer from '../ApprovalWorkflowAIComposer.vue';
import {
  AI_FONT_STACK_SNIPPET,
  ELEMENT_STUBS,
  avatarNodes,
  declsFor,
  englishLeaks,
  fakeScrollMetrics,
  hasClockText,
  pendingUntilAborted,
  readSfc,
  tinyFontSizes,
} from '@/utils/__tests__/aiPanelStdHelpers';

const h = vi.hoisted(() => ({ post: undefined as unknown as ReturnType<typeof import('vitest').vi.fn> }));
vi.mock('@/api/request', () => ({ default: { post: (...a: unknown[]) => h.post(...a) } }));

const sfc = readSfc(resolve(__dirname, '..', 'ApprovalWorkflowAIComposer.vue'));

const SPEC = { nodes: [{ id: 'start' }, { id: 'a1', type: 'approval' }, { id: 'end' }], edges: [{ source: 'start', target: 'a1' }, { source: 'a1', target: 'end' }] };
const SPEC_2 = { nodes: [{ id: 'other' }], edges: [] };
const WITH_SPEC = { data: { reply: '好的', diffs: [{ type: 'APPROVAL_WORKFLOW_SPEC', params: { spec: SPEC } }] } };
const NO_SPEC = { data: { reply: '这个需求我没理解，请换个说法。', diffs: [] } };
const CONTEXT = { selectedNodeIds: ['a1'] };

const POPOVER_STUB = {
  props: ['visible'],
  emits: ['update:visible'],
  template: '<div class="el-popover-stub" :data-open="visible ? 1 : 0"><slot name="reference" /><div v-if="visible" class="popover-body"><slot /></div></div>',
};

let wrappers: VueWrapper[] = [];
let applySpec: ReturnType<typeof vi.fn>;
function mountComposer() {
  const w = mount(ApprovalWorkflowAIComposer, {
    props: { factoryId: 'F001', context: CONTEXT, applySpec: (s: unknown) => applySpec(s) },
    global: { stubs: { ...ELEMENT_STUBS, 'el-popover': POPOVER_STUB } },
    attachTo: document.body,
  });
  wrappers.push(w);
  return w;
}
async function send(w: VueWrapper, text: string) {
  await w.find('textarea.stub-input').setValue(text);
  const btn = w.findAll('button').find((b) => b.text().trim() === '发送');
  if (!btn) throw new Error('找不到发送按钮');
  await btn.trigger('click');
  await flushPromises();
}

beforeEach(() => {
  h.post = vi.fn();
  applySpec = vi.fn().mockResolvedValue(true);
  Element.prototype.scrollTo = vi.fn() as unknown as typeof Element.prototype.scrollTo;
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  wrappers.forEach((w) => w.unmount());
  wrappers = [];
  document.body.innerHTML = '';
});

describe('版式 (L1 / L2 / L4 / T1-T3 / S2-S4)', () => {
  it('L1: 助手消息没有头像节点 (阳性: 助手消息确实渲染了)', async () => {
    h.post.mockResolvedValue(WITH_SPEC);
    const w = mountComposer();
    await send(w, '把节点改为采购主管审批');
    expect(w.findAll('[data-testid="ai-chat-assistant-body"]')).toHaveLength(1);
    expect(w.findAll('[data-testid="ai-chat-user-bubble"]')).toHaveLength(1);
    expect(avatarNodes(w.element)).toHaveLength(0);
  });

  it('源文本: 助手无气泡属性, 用户气泡圆角 12 + 10px 12px + min(78%, 320px), 正文 16px/1.6, 系统字体栈, 无 10-11px, 输入 16px', () => {
    const user = declsFor(sfc.css, '.message.user')!;
    expect(user['border-radius']).toBe('12px');
    expect(user.padding).toBe('10px 12px');
    expect(user['max-width']).toBe('min(78%, 320px)');
    expect(user.background).toBeTruthy();
    const assistant = declsFor(sfc.css, '.message.assistant');
    expect(assistant).not.toBeNull();
    expect(Object.keys(assistant!).filter((k) => /^(background|border|box-shadow|padding)/.test(k))).toEqual([]);
    const message = declsFor(sfc.css, '.message')!;
    expect(message['font-size']).toBe('16px');
    expect(message['line-height']).toBe('1.6');
    expect(declsFor(sfc.css, '.approval-ai-composer')!['font-family']).toBe(AI_FONT_STACK_SNIPPET);
    expect(declsFor(sfc.css, '.history-panel')!['font-family']).toBe(AI_FONT_STACK_SNIPPET);
    expect(declsFor(sfc.css, '.approval-ai-composer')!['border-radius']).toBe('8px');
    expect(declsFor(sfc.css, '.approval-ai-composer :deep(.el-textarea__inner)')!['font-size']).toBe('16px');
    expect(tinyFontSizes(sfc.css)).toEqual([]);
  });

  it('L4: 对话记录里没有 HH:MM 时间文本', async () => {
    h.post.mockResolvedValue(WITH_SPEC);
    const w = mountComposer();
    await send(w, '把节点改为采购主管审批');
    expect(hasClockText(w.find('[data-testid="ai-chat-thread"]').text())).toBe(false);
  });
});

describe('W1 骨架屏 / W2 超时与失败', () => {
  it('W1: 发送后弹层自动打开, 在途时 3 条骨架 + 文字; 发之前弹层关着、回来之后骨架为 0', async () => {
    let resolveIt!: (v: unknown) => void;
    h.post.mockReturnValue(new Promise((r) => { resolveIt = r; }));
    const w = mountComposer();
    expect(w.find('.el-popover-stub').attributes('data-open')).toBe('0');
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
    await send(w, '把节点改为采购主管审批');
    expect(w.find('.el-popover-stub').attributes('data-open')).toBe('1');
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(3);
    expect(w.find('[data-testid="ai-chat-loading-skeleton"]').text()).toContain('正在');
    resolveIt(WITH_SPEC);
    await flushPromises();
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
  });

  it('W2: 30 秒超时 —— 29.999 秒还在等, 30 秒说「没有任何改动」, signal 被 abort, applySpec 0 次', async () => {
    vi.useFakeTimers();
    const pending = pendingUntilAborted<unknown>();
    h.post.mockImplementation(pending.call);
    const w = mountComposer();
    await send(w, '把节点改为采购主管审批');
    expect(pending.seen.signal).toBeInstanceOf(AbortSignal);

    await vi.advanceTimersByTimeAsync(29_999);
    expect(pending.seen.signal!.aborted).toBe(false);
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(3);
    expect(w.text()).not.toContain('没有任何改动');

    await vi.advanceTimersByTimeAsync(1);
    expect(pending.seen.signal!.aborted).toBe(true);
    const bodies = w.findAll('[data-testid="ai-chat-assistant-body"]').map((n) => n.text());
    expect(bodies[bodies.length - 1]).toContain('没有任何改动');
    expect(bodies[bodies.length - 1]).toContain('超过 30 秒');
    expect(w.find('.last-status').text()).toContain('没有任何改动');
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
    expect(applySpec).not.toHaveBeenCalled();
  });

  it('W2: 回复已到手后不再受 30 秒限制 —— applySpec 慢于 30 秒也照常完成, 不出超时文案', async () => {
    vi.useFakeTimers();
    h.post.mockResolvedValue(WITH_SPEC);
    let finish!: (v: boolean) => void;
    applySpec.mockImplementation(() => new Promise<boolean>((r) => { finish = r; }));
    const w = mountComposer();
    await send(w, '把节点改为采购主管审批');
    expect(applySpec).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(45_000);
    finish(true);
    await flushPromises();
    expect(w.text()).not.toContain('超过 30 秒');
    expect(w.find('.last-status').text()).toContain('已完成');
  });

  it('W2: 失败 —— 英文错误 / 状态码不上屏, 说没有任何改动, applySpec 0 次', async () => {
    h.post.mockRejectedValue(new Error('Request failed with status code 503'));
    const w = mountComposer();
    await send(w, '把节点改为采购主管审批');
    const text = w.find('[data-testid="ai-chat-thread"]').text();
    expect(text).toContain('没有任何改动');
    expect(text).not.toContain('503');
    expect(text).not.toContain('Request failed');
    expect(applySpec).not.toHaveBeenCalled();
  });

  it('H4: 请求参数不变 (URL / message / mode / moduleCode / context), 只多一个 signal', async () => {
    h.post.mockResolvedValue(WITH_SPEC);
    const w = mountComposer();
    await send(w, '把节点改为采购主管审批');
    expect(h.post).toHaveBeenCalledTimes(1);
    expect(h.post).toHaveBeenCalledWith(
      '/F001/config/v2/ai/chat',
      { message: '把节点改为采购主管审批', mode: 'plan', moduleCode: 'approval_workflow_config', params: { context: CONTEXT } },
      { signal: expect.any(AbortSignal) },
    );
  });
});

describe('V3 中文', () => {
  it('弹层里没有英文模式名 / 类型码 (APPROVAL_WORKFLOW_SPEC 不上屏)', async () => {
    h.post.mockResolvedValue(WITH_SPEC);
    const w = mountComposer();
    await send(w, '把节点改为采购主管审批');
    expect(w.text()).not.toContain('APPROVAL_WORKFLOW_SPEC');
    expect(englishLeaks(w.text())).toEqual([]);
  });
});

describe('🔴 H2: 套用到草稿 (applySpec) 这一步', () => {
  it('没发之前 0 次; 回复带 spec 恰好 1 次, 参数就是那个 spec 对象; 成功文案不变', async () => {
    h.post.mockResolvedValue(WITH_SPEC);
    const w = mountComposer();
    expect(applySpec).not.toHaveBeenCalled();
    await send(w, '把节点改为采购主管审批');
    expect(applySpec).toHaveBeenCalledTimes(1);
    expect(applySpec.mock.calls[0]).toHaveLength(1);
    expect(applySpec.mock.calls[0][0]).toBe(SPEC);
    expect(w.find('.last-status').text()).toBe('已完成，当前草稿已更新，可用“撤销”恢复。');
  });

  it('阴性对照: 回复里没有 spec -> 0 次, 助手原话保留; 画布校验不通过 -> 文案不变', async () => {
    h.post.mockResolvedValue(NO_SPEC);
    const w = mountComposer();
    await send(w, '随便说点什么');
    expect(applySpec).not.toHaveBeenCalled();
    expect(w.find('[data-testid="ai-chat-thread"]').text()).toContain('这个需求我没理解');

    h.post.mockResolvedValue(WITH_SPEC);
    applySpec.mockResolvedValue(false);
    await send(w, '再来一次');
    expect(applySpec).toHaveBeenCalledTimes(1);
    expect(w.find('.last-status').text()).toBe('这次修改未通过画布校验，草稿保持不变。');
  });

  it('改前行为: 回复里有多个 spec 时只套第一个', async () => {
    h.post.mockResolvedValue({
      data: { reply: 'x', diffs: [
        { type: 'APPROVAL_WORKFLOW_SPEC', params: { spec: SPEC } },
        { type: 'APPROVAL_WORKFLOW_SPEC', params: { spec: SPEC_2 } },
      ] },
    });
    const w = mountComposer();
    await send(w, '两个方案');
    expect(applySpec).toHaveBeenCalledTimes(1);
    expect(applySpec.mock.calls[0][0]).toBe(SPEC);
  });
});

describe('N1 条件滚动', () => {
  async function scrollCallsOnSecondSend(metrics: { scrollHeight: number; clientHeight: number; scrollTop: number }) {
    h.post.mockResolvedValue(WITH_SPEC);
    const w = mountComposer();
    await send(w, '第一句'); // 弹层此时才打开, thread 才存在
    const thread = w.find('[data-testid="ai-chat-thread"]').element as HTMLElement;
    const spy = vi.fn();
    thread.scrollTo = spy as unknown as typeof thread.scrollTo;
    fakeScrollMetrics(thread, metrics);
    await send(w, '第二句');
    return spy.mock.calls.length;
  }

  it('在底部附近: 用户消息 + 回答各滚一次; 往上翻 300px: 只有用户自己发的那条滚', async () => {
    const near = await scrollCallsOnSecondSend({ scrollHeight: 1000, clientHeight: 300, scrollTop: 700 });
    const far = await scrollCallsOnSecondSend({ scrollHeight: 1000, clientHeight: 300, scrollTop: 400 });
    expect(near).toBe(2);
    expect(far).toBe(1);
  });
});
