/**
 * canvas-editor 的 AIChatPanel (300px 侧栏) 按 AI 对话标准重构后的行为测试。
 * 真实挂载 AIChatPanel, 只 mock 网络 (@/api/request) 与 Element Plus 弹层/按钮替身。
 *
 * jsdom 不计算 scoped CSS, 所以样式判据读 SFC 的 <style> 源文本 (postcss 解析), 扫描器自身的阴性对照在
 * src/utils/__tests__/aiConfigAssistant.spec.ts。
 *
 * 🔴 「应用」这一步 (H2 / H3 / H4):
 *   面板的「应用」只做一件事: emit('applyDiff', [diff]) 把那一条交给页面暂存, 真正写入在 canvas-editor 页的「应用变更」按钮。
 *   所以守的是: 没点之前 applyDiff 0 次、点了恰好 1 次、payload 逐字段与改前一致、diff 条目一条不少。
 *   「改前读数」是在 origin/main 的旧面板上跑同一份 fixture 得到的, 写成字面量 (BASELINE_*)。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { resolve } from 'node:path';
import AIChatPanel from '../AIChatPanel.vue';
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

const sfc = readSfc(resolve(__dirname, '..', 'AIChatPanel.vue'));

const DIFFS = [
  { type: 'TOOL_TOGGLE', tool: 'canvas_tool_toggle', params: { toolCode: 'X', enabled: false }, description: '停用 X 工具' },
  { type: 'FIELD_CHANGE', tool: 'canvas_field', params: { field: 'qty', required: true }, description: '数量设为必填' },
  { type: 'SOME_FUTURE_TYPE', tool: '', params: {}, description: '一条没登记过类型的说明' },
];
const REPLY = { data: { reply: '已生成 3 项变更方案，请逐项审核后应用。', diffs: DIFFS } };

/** 改前读数: 旧面板上同一份 fixture、点第 2 条「应用」后 emit('applyDiff') 的参数 */
const BASELINE_DIFF_ITEMS = 3;
const BASELINE_EMIT_SECOND = [[
  [{ type: 'FIELD_CHANGE', path: 'canvas_field', before: null, after: { field: 'qty', required: true }, description: '数量设为必填' }],
]];

let wrappers: VueWrapper[] = [];
function mountPanel() {
  const w = mount(AIChatPanel, {
    props: { factoryId: 'F001', selectedModule: 'tools' },
    global: { stubs: ELEMENT_STUBS },
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
function applyButtons(w: VueWrapper) {
  return w.findAll('.diff-item button').filter((b) => b.text().trim() === '应用');
}

beforeEach(() => {
  h.post = vi.fn();
  Element.prototype.scrollTo = vi.fn() as unknown as typeof Element.prototype.scrollTo;
});
afterEach(() => {
  vi.useRealTimers();
  wrappers.forEach((w) => w.unmount());
  wrappers = [];
  document.body.innerHTML = '';
});

describe('版式 (L1 / L2 / L4 / T1-T3)', () => {
  it('L1: 助手消息没有头像节点 (阳性: 助手消息确实渲染了)', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '关掉 X');
    expect(w.findAll('[data-testid="ai-chat-assistant-body"]').length).toBeGreaterThanOrEqual(2); // 欢迎语 + 回答
    expect(w.findAll('[data-testid="ai-chat-user-bubble"]')).toHaveLength(1);
    expect(avatarNodes(w.element)).toHaveLength(0);
  });

  it('L1 / L2 / T 源文本: 助手无背景边框阴影, 用户气泡圆角 12 + 10px 12px + min(78%, 320px), 正文 16px/1.6, 系统字体栈, 无 10-11px', () => {
    const user = declsFor(sfc.css, '.message.user');
    expect(user).not.toBeNull();
    expect(user!['border-radius']).toBe('12px');
    expect(user!.padding).toBe('10px 12px');
    expect(user!['max-width']).toBe('min(78%, 320px)');
    expect(user!.background).toBeTruthy(); // 阳性: 用户气泡确实有底色, 扫描器读得到「有背景」

    for (const sel of ['.message.assistant', '.message.system']) {
      const d = declsFor(sfc.css, sel);
      expect(d, sel).not.toBeNull();
      const bubbleProps = Object.keys(d!).filter((k) => /^(background|border|box-shadow|padding)/.test(k));
      expect(bubbleProps, `${sel} 不应有气泡属性`).toEqual([]);
      expect(d!['align-self']).toBe('stretch');
    }

    const message = declsFor(sfc.css, '.message')!;
    expect(message['font-size']).toBe('16px');
    expect(message['line-height']).toBe('1.6');
    expect(declsFor(sfc.css, '.ai-chat-panel')!['font-family']).toBe(AI_FONT_STACK_SNIPPET);
    expect(tinyFontSizes(sfc.css)).toEqual([]);
    expect(declsFor(sfc.css, '.mode-btn')!['min-height']).toBe('32px');
    expect(declsFor(sfc.css, '.mode-btn')!['border-radius']).toBe('8px');
  });

  it('L4: 消息流里没有 HH:MM 时间文本', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '关掉 X');
    expect(hasClockText(w.find('[data-testid="ai-chat-thread"]').text())).toBe(false);
  });
});

describe('W1 骨架屏 / W2 超时与失败', () => {
  it('W1: 请求在途时 3 条骨架 + 文字; 发之前和回来之后都是 0 (阳性 + 阴性)', async () => {
    let resolveIt!: (v: unknown) => void;
    h.post.mockReturnValue(new Promise((r) => { resolveIt = r; }));
    const w = mountPanel();
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
    await send(w, '关掉 X');
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(3);
    expect(w.find('[data-testid="ai-chat-loading-skeleton"]').text()).toContain('正在');
    resolveIt(REPLY);
    await flushPromises();
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
  });

  it('W2: 30 秒超时 —— 29.999 秒还在等, 30 秒说「没有任何改动」, signal 被 abort, 不出应用按钮', async () => {
    vi.useFakeTimers();
    const pending = pendingUntilAborted<unknown>();
    h.post.mockImplementation(pending.call);
    const w = mountPanel();
    await send(w, '关掉 X');
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
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
    expect(applyButtons(w)).toHaveLength(0);
    expect(w.emitted('applyDiff')).toBeUndefined();
  });

  it('W2: 请求失败 —— 说人话 + 没有任何改动, 不显示 error.message / 状态码 / 英文', async () => {
    h.post.mockRejectedValue(Object.assign(new Error('Request failed with status code 500'), { code: 'ERR_BAD_RESPONSE' }));
    const w = mountPanel();
    await send(w, '关掉 X');
    const text = w.find('[data-testid="ai-chat-thread"]').text();
    expect(text).toContain('没有任何改动');
    expect(text).not.toContain('500');
    expect(text).not.toContain('Request failed');
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
  });

  it('H4: 请求参数不变 (URL / message / mode / moduleCode), 只多一个 signal', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '关掉 X');
    expect(h.post).toHaveBeenCalledTimes(1);
    expect(h.post).toHaveBeenCalledWith(
      '/F001/config/v2/ai/chat',
      { message: '关掉 X', mode: 'plan', moduleCode: 'tools' },
      { signal: expect.any(AbortSignal) },
    );
  });
});

describe('V3 中文名: 模式名与 diff 类型', () => {
  it('模式按钮显示中文, data-mode 仍是接口枚举; 面板全文没有 Autopilot / Plan / Action / 类型码', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    const btns = w.findAll('button.mode-btn');
    expect(btns.map((b) => b.attributes('data-mode'))).toEqual(['autopilot', 'plan', 'action']);
    expect(btns.map((b) => b.text())).toEqual(['自动配置', '先出方案', '影响分析']);
    await send(w, '关掉 X');
    const diffTypes = w.findAll('[data-testid="ai-diff-type"]').map((n) => n.text());
    expect(diffTypes).toEqual(['开关切换', '字段修改', '配置变更']);
    expect(englishLeaks(w.text())).toEqual([]);
  });

  it('H4: 切到「自动配置」后发出去的 mode 仍是 autopilot (中文名不进接口参数)', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await w.find('button[data-mode="autopilot"]').trigger('click');
    await send(w, '关掉 X');
    expect((h.post.mock.calls[0][1] as { mode: string }).mode).toBe('autopilot');
  });
});

describe('🔴 H2 / H3: 应用这一步', () => {
  it('H3: 方案里的条目一条不少 (改前 3 条, 改后 3 条), 描述全部可见', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '关掉 X');
    expect(w.findAll('.diff-item')).toHaveLength(BASELINE_DIFF_ITEMS);
    expect(applyButtons(w)).toHaveLength(BASELINE_DIFF_ITEMS);
    for (const d of DIFFS) expect(w.text()).toContain(d.description);
  });

  it('H2: 没点「应用」applyDiff 是 0 次; 点第 2 条恰好 1 次, 参数逐字段与改前一致; 再点另一条各自 1 次', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '关掉 X');
    expect(w.emitted('applyDiff')).toBeUndefined();

    await applyButtons(w)[1].trigger('click');
    expect(w.emitted('applyDiff')).toEqual(BASELINE_EMIT_SECOND);

    await applyButtons(w)[0].trigger('click');
    expect(w.emitted('applyDiff')).toHaveLength(2);
    expect((w.emitted('applyDiff')![1] as unknown[][])[0]).toEqual([
      { type: 'TOOL_TOGGLE', path: 'canvas_tool_toggle', before: null, after: { toolCode: 'X', enabled: false }, description: '停用 X 工具' },
    ]);
  });
});

describe('N1 条件滚动', () => {
  async function scrollCallsAfterReply(metrics: { scrollHeight: number; clientHeight: number; scrollTop: number }) {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    const thread = w.find('[data-testid="ai-chat-thread"]').element as HTMLElement;
    const spy = vi.fn();
    thread.scrollTo = spy as unknown as typeof thread.scrollTo;
    fakeScrollMetrics(thread, metrics);
    await send(w, '关掉 X');
    return spy.mock.calls.length;
  }

  it('在底部附近: 用户消息 + 助手回答各滚一次 (阳性对照); 往上翻 300px: 只有用户自己发的那条滚, 回答不拽回去', async () => {
    const near = await scrollCallsAfterReply({ scrollHeight: 1000, clientHeight: 300, scrollTop: 700 });
    const far = await scrollCallsAfterReply({ scrollHeight: 1000, clientHeight: 300, scrollTop: 400 });
    expect(near).toBe(2);
    expect(far).toBe(1);
  });
});
