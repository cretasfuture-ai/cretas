/**
 * system/components/WorkProcessAIChatPanel (Workflow 编辑器底部的 AI 作曲栏 + 对话记录弹层) 按 AI 对话标准重构后的行为测试。
 * 真实挂载, 只 mock 网络 (@/api/request) 与 Element Plus 弹层/按钮替身。
 *
 * 🔴 「应用到草稿」这一步 (H2 / H3 / H4):
 *   点「应用 N 道工序到草稿」/「应用到草稿」才 emit('applyDraft', draft, sourceIdentity); 之后由编辑器套用到本地草稿, 再由用户保存。
 *   所以守的是: 没点之前 applyDraft 0 次、点了恰好 1 次、参数 (draft 原对象内容 + sourceIdentity) 与改前一致、预览条目一条不少。
 *   BASELINE_* 是在 origin/main 的旧面板上跑同一份 fixture 得到的字面量。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { resolve } from 'node:path';
import WorkProcessAIChatPanel from '../WorkProcessAIChatPanel.vue';
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

const sfc = readSfc(resolve(__dirname, '..', 'WorkProcessAIChatPanel.vue'));

const DRAFT_PARAMS = {
  draft: [
    { operation: 'create', productWorkProcessId: null, workProcessId: 'WP-1', processName: '解冻', processCategory: '预处理', unit: 'kg', processOrder: 1, responsibleWorkerId: 11, responsibleWorkerName: '张三' },
    { operation: 'update', productWorkProcessId: 77, workProcessId: 'WP-2', processName: '切片', processCategory: null, unit: 'kg', processOrder: 2, responsibleWorkerId: null, responsibleWorkerName: null },
    { operation: 'create', productWorkProcessId: null, workProcessId: 'WP-3', processName: '包装', processCategory: '包装', unit: '个', processOrder: 3, responsibleWorkerId: 12, responsibleWorkerName: '李四' },
  ],
  missingProcesses: [{ name: '杀菌', reason: '工序目录里没有' }],
};
const PATCH_PARAMS = { patches: [{ op: 'REMOVE_NODE', nodeId: 'material:raw' }] };
const REPLY = {
  data: {
    reply: '已生成 2 项草稿，请审核后应用。',
    diffs: [
      { type: 'PRODUCT_WORK_PROCESS_DRAFT', tool: 'draft', params: DRAFT_PARAMS, description: '工序草稿' },
      { type: 'PRODUCT_PROCESS_WORKFLOW_PATCH', tool: 'workflow', params: PATCH_PARAMS, description: '删除原料节点' },
    ],
  },
};

/** 改前读数 (旧面板, 同一份 fixture) */
const BASELINE = { stepCards: 3, diffItems: 1, missingAlerts: 1, applyButtons: 2 };
const IDENTITY = { factoryId: 'F006', productTypeId: 'PT-A' };

let wrappers: VueWrapper[] = [];
function mountPanel() {
  const w = mount(WorkProcessAIChatPanel, {
    props: { factoryId: 'F006', productTypeId: 'PT-A', endpoint: '/workflow-ai', moduleCode: 'product_process_workflow_config', context: { selectedNodeIds: ['n1'] } },
    global: { stubs: ELEMENT_STUBS },
    attachTo: document.body,
  });
  wrappers.push(w);
  return w;
}
async function send(w: VueWrapper, text: string) {
  await w.find('textarea.stub-input').setValue(text);
  await w.find('button[aria-label="发送给 Workflow AI"]').trigger('click');
  await flushPromises();
}
function applyButtons(w: VueWrapper) {
  return w.findAll('.diff-preview button').filter((b) => /^应用/.test(b.text().trim()));
}

beforeEach(() => {
  h.post = vi.fn();
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
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '加一道杀菌');
    expect(w.findAll('[data-testid="ai-chat-assistant-body"]').length).toBeGreaterThanOrEqual(2);
    expect(w.findAll('[data-testid="ai-chat-user-bubble"]')).toHaveLength(1);
    expect(avatarNodes(w.element)).toHaveLength(0);
  });

  it('源文本: 助手无气泡属性, 用户气泡圆角 12 + 10px 12px + min(78%, 320px), 正文 16px/1.6, 系统字体栈, 无 10-11px, 按钮 ≥ 32px', () => {
    const user = declsFor(sfc.css, '.message.user')!;
    expect(user['border-radius']).toBe('12px');
    expect(user.padding).toBe('10px 12px');
    expect(user['max-width']).toBe('min(78%, 320px)');
    expect(user.background).toBeTruthy();
    for (const sel of ['.message.assistant', '.message.system']) {
      const d = declsFor(sfc.css, sel);
      expect(d, sel).not.toBeNull();
      expect(Object.keys(d!).filter((k) => /^(background|border|box-shadow|padding)/.test(k)), sel).toEqual([]);
    }
    const message = declsFor(sfc.css, '.message')!;
    expect(message['font-size']).toBe('16px');
    expect(message['line-height']).toBe('1.6');
    expect(declsFor(sfc.css, '.ai-history')!['font-family']).toBe(AI_FONT_STACK_SNIPPET);
    expect(tinyFontSizes(sfc.css)).toEqual([]);
    for (const sel of ['.ai-history-button', '.quick-prompt-button', '.diff-apply']) {
      expect(declsFor(sfc.css, sel)!['min-height'], sel).toBe('32px');
    }
    expect(declsFor(sfc.css, '.work-process-ai-chat-panel')!['border-radius']).toBe('8px');
  });

  it('L4: 对话记录里没有 HH:MM 时间文本', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '加一道杀菌');
    expect(hasClockText(w.find('[data-testid="ai-chat-thread"]').text())).toBe(false);
  });
});

describe('W1 骨架屏 / W2 超时与失败', () => {
  it('W1: 在途时 3 条骨架 + 文字; 发之前和回来之后都是 0', async () => {
    let resolveIt!: (v: unknown) => void;
    h.post.mockReturnValue(new Promise((r) => { resolveIt = r; }));
    const w = mountPanel();
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
    await send(w, '加一道杀菌');
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(3);
    expect(w.find('[data-testid="ai-chat-loading-skeleton"]').text()).toContain('正在');
    resolveIt(REPLY);
    await flushPromises();
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
  });

  it('W2: 30 秒超时 —— 29.999 秒还在等, 30 秒说「没有任何改动」, signal 被 abort, applyDraft 0 次', async () => {
    vi.useFakeTimers();
    const pending = pendingUntilAborted<unknown>();
    h.post.mockImplementation(pending.call);
    const w = mountPanel();
    await send(w, '加一道杀菌');
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
    expect(w.emitted('applyDraft')).toBeUndefined();
  });

  it('W2: 失败 —— 说人话 + 没有任何改动, 不显示 error.message / 状态码', async () => {
    h.post.mockRejectedValue(new Error('Request failed with status code 502'));
    const w = mountPanel();
    await send(w, '加一道杀菌');
    const text = w.find('[data-testid="ai-chat-thread"]').text();
    expect(text).toContain('没有任何改动');
    expect(text).not.toContain('502');
    expect(text).not.toContain('Request failed');
  });

  it('H4: 请求参数不变 (endpoint / message / mode / moduleCode / params), 只多一个 signal', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '加一道杀菌');
    expect(h.post).toHaveBeenCalledTimes(1);
    expect(h.post).toHaveBeenCalledWith(
      '/workflow-ai',
      {
        message: '加一道杀菌',
        mode: 'plan',
        moduleCode: 'product_process_workflow_config',
        params: { productTypeId: 'PT-A', context: { selectedNodeIds: ['n1'] } },
      },
      { signal: expect.any(AbortSignal) },
    );
  });
});

describe('V3 中文名', () => {
  it('模式标签是「先出方案」, 面板全文没有 Plan / 英文类型码; 非草稿 diff 的类型显示中文', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '加一道杀菌');
    expect(w.find('[data-testid="ai-mode-label"]').text()).toBe('先出方案');
    expect(w.findAll('[data-testid="ai-diff-type"]').map((n) => n.text())).toEqual(['工序流程调整']);
    // 预览里 REMOVE_NODE 是 patch 内容, 不渲染; 渲染出来的文字里不许有英文码
    expect(englishLeaks(w.text())).toEqual([]);
  });
});

describe('🔴 H2 / H3: 应用到草稿这一步', () => {
  it('H3: 预览条目一条不少 —— 工序卡 3 + 其它 diff 1 + 未匹配提示 1 + 应用按钮 2', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '加一道杀菌');
    expect(w.findAll('.draft-step-card')).toHaveLength(BASELINE.stepCards);
    expect(w.findAll('.diff-item')).toHaveLength(BASELINE.diffItems);
    expect(w.findAll('.el-alert')).toHaveLength(BASELINE.missingAlerts);
    expect(w.find('.el-alert').text()).toContain('1 个工序未匹配');
    expect(applyButtons(w)).toHaveLength(BASELINE.applyButtons);
    for (const name of ['解冻', '切片', '包装', '张三', '李四', '删除原料节点']) expect(w.text()).toContain(name);
  });

  it('H2: 没点之前 applyDraft 0 次; 点「应用 3 道工序到草稿」恰好 1 次, 参数 = (draft, 来源身份) 与改前一致; 再点另一个各自 1 次', async () => {
    h.post.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '加一道杀菌');
    expect(w.emitted('applyDraft')).toBeUndefined();

    const buttons = applyButtons(w);
    expect(buttons.map((b) => b.text().replace(/\s+/g, ' ').trim())).toEqual(['应用 3 道工序到草稿', '应用到草稿']);
    await buttons[0].trigger('click');
    expect(w.emitted('applyDraft')).toEqual([[DRAFT_PARAMS, IDENTITY]]);

    await buttons[1].trigger('click');
    expect(w.emitted('applyDraft')).toEqual([[DRAFT_PARAMS, IDENTITY], [PATCH_PARAMS, IDENTITY]]);
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
    await send(w, '加一道杀菌');
    return spy.mock.calls.length;
  }

  it('在底部附近: 用户消息 + 回答各滚一次; 往上翻 300px: 只有用户自己发的那条滚', async () => {
    const near = await scrollCallsAfterReply({ scrollHeight: 1000, clientHeight: 300, scrollTop: 700 });
    const far = await scrollCallsAfterReply({ scrollHeight: 1000, clientHeight: 300, scrollTop: 400 });
    expect(near).toBe(2);
    expect(far).toBe(1);
  });
});
