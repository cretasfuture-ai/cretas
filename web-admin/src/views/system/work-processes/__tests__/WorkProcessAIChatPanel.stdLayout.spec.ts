/**
 * system/work-processes/WorkProcessAIChatPanel (工序管理页底部的 AI 工序助手卡片) 按 AI 对话标准重构后的行为测试。
 * 真实挂载, 只 mock 网络 (@/api/canvasApi 的 aiChat / aiApplyDiffs) 与 Element Plus 替身。
 *
 * 🔴 「应用 / 确认并执行」这一步 (H2 / H3 / H4):
 *   这个面板自己调写接口 aiApplyDiffs。守的是: 没点之前 aiApplyDiffs 0 次、点了恰好 1 次、
 *   参数 (factoryId, [diff 原样]) 与改前一致、diff 条目一条不少; 「建议」模式下根本没有执行入口。
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

const api = vi.hoisted(() => ({ aiChat: vi.fn(), aiApplyDiffs: vi.fn() }));
vi.mock('@/api/canvasApi', () => ({ aiChat: (...a: unknown[]) => api.aiChat(...a), aiApplyDiffs: (...a: unknown[]) => api.aiApplyDiffs(...a) }));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }));

const sfc = readSfc(resolve(__dirname, '..', 'WorkProcessAIChatPanel.vue'));

const WP_DIFF = {
  type: 'WORK_PROCESS_CATALOG',
  tool: 'canvas_work_process_catalog',
  params: { action: 'create', name: '焯水', processCategory: '加工', standardHourlyRate: 25 },
  description: '新建工序：焯水',
};
const OTHER_DIFF = { tool: 'some_other_tool', params: { k: 'v' }, description: '其它说明' };
const REPLY = { data: { reply: '将创建工序: 焯水', diffs: [WP_DIFF, OTHER_DIFF], applied: false } };
const RECEIPT_OK = { success: true, data: { operationId: 'op-1', total: 1, applied: 1, reused: 0, items: [{ index: 0, status: 'SUCCESS', message: '已创建' }] } };

/** 改前读数 (旧面板, 同一份 fixture): 2 条 diff 项, 其中 1 条 (工序目录) 有执行按钮 */
const BASELINE = { diffItems: 2, applyButtonsPlan: 1 };

let wrappers: VueWrapper[] = [];
function mountPanel() {
  const w = mount(WorkProcessAIChatPanel, {
    props: { factoryId: 'F006' },
    global: { stubs: ELEMENT_STUBS },
    attachTo: document.body,
  });
  wrappers.push(w);
  return w;
}
async function pickMode(w: VueWrapper, value: string) {
  await w.find(`.el-segmented-stub button[data-value="${value}"]`).trigger('click');
}
async function send(w: VueWrapper, text: string) {
  await w.find('textarea.stub-input').setValue(text);
  const btn = w.findAll('button').find((b) => b.text().trim() === '发送');
  if (!btn) throw new Error('找不到发送按钮');
  await btn.trigger('click');
  await flushPromises();
}
function applyButtons(w: VueWrapper) {
  return w.findAll('.diff-item button').filter((b) => /^(应用|确认并执行)$/.test(b.text().trim()));
}

beforeEach(() => {
  api.aiChat = vi.fn();
  api.aiApplyDiffs = vi.fn();
  Element.prototype.scrollTo = vi.fn() as unknown as typeof Element.prototype.scrollTo;
});
afterEach(() => {
  vi.useRealTimers();
  wrappers.forEach((w) => w.unmount());
  wrappers = [];
  document.body.innerHTML = '';
});

describe('版式 (L1 / L2 / L4 / T1-T3 / S2-S4)', () => {
  it('L1: 助手消息没有头像节点 (阳性: 助手消息确实渲染了)', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '新建焯水工序');
    expect(w.findAll('[data-testid="ai-chat-assistant-body"]').length).toBeGreaterThanOrEqual(2);
    expect(w.findAll('[data-testid="ai-chat-user-bubble"]')).toHaveLength(1);
    expect(avatarNodes(w.element)).toHaveLength(0);
  });

  it('源文本: 助手无气泡属性, 用户气泡圆角 12 + 10px 12px + min(78%, 320px), 正文 16px/1.6, 系统字体栈, 无 10-11px, 应用按钮 ≥ 32px', () => {
    const user = declsFor(sfc.css, '.chat-message.user')!;
    expect(user['border-radius']).toBe('12px');
    expect(user.padding).toBe('10px 12px');
    expect(user['max-width']).toBe('min(78%, 320px)');
    expect(user.background).toBeTruthy();
    for (const sel of ['.chat-message.assistant', '.chat-message.system']) {
      const d = declsFor(sfc.css, sel);
      expect(d, sel).not.toBeNull();
      expect(Object.keys(d!).filter((k) => /^(background|border|box-shadow|padding)/.test(k)), sel).toEqual([]);
    }
    const message = declsFor(sfc.css, '.chat-message')!;
    expect(message['font-size']).toBe('16px');
    expect(message['line-height']).toBe('1.6');
    expect(declsFor(sfc.css, '.work-process-ai-card')!['font-family']).toBe(AI_FONT_STACK_SNIPPET);
    expect(tinyFontSizes(sfc.css)).toEqual([]);
    expect(declsFor(sfc.css, '.diff-apply')!['min-height']).toBe('32px');
  });

  it('S3: 执行按钮不再是 size="small" (24px 高), 分段选择器也不是', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '新建焯水工序');
    const btn = applyButtons(w)[0];
    expect(btn).toBeDefined();
    expect(btn.attributes('data-size')).toBeUndefined();
    expect(sfc.template).not.toMatch(/<el-segmented[^>]*size="small"/);
  });

  it('L4: 对话里没有 HH:MM 时间文本', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '新建焯水工序');
    expect(hasClockText(w.find('[data-testid="ai-chat-thread"]').text())).toBe(false);
  });
});

describe('W1 骨架屏 / W2 超时与失败', () => {
  it('W1: 在途时 3 条骨架 + 文字; 发之前和回来之后都是 0', async () => {
    let resolveIt!: (v: unknown) => void;
    api.aiChat.mockReturnValue(new Promise((r) => { resolveIt = r; }));
    const w = mountPanel();
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
    await send(w, '新建焯水工序');
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(3);
    expect(w.find('[data-testid="ai-chat-loading-skeleton"]').text()).toContain('正在');
    resolveIt(REPLY);
    await flushPromises();
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
  });

  it('W2: 30 秒超时 —— 29.999 秒还在等, 30 秒说「没有任何改动」, signal 被 abort, 写接口 0 次', async () => {
    vi.useFakeTimers();
    const pending = pendingUntilAborted<unknown>();
    api.aiChat.mockImplementation(pending.call);
    const w = mountPanel();
    await send(w, '新建焯水工序');
    expect(pending.seen.signal).toBeInstanceOf(AbortSignal);

    await vi.advanceTimersByTimeAsync(29_999);
    expect(pending.seen.signal!.aborted).toBe(false);
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(3);
    expect(w.find('[data-testid="ai-chat-thread"]').text()).not.toContain('没有任何改动');

    await vi.advanceTimersByTimeAsync(1);
    expect(pending.seen.signal!.aborted).toBe(true);
    const bodies = w.findAll('[data-testid="ai-chat-assistant-body"]').map((n) => n.text());
    expect(bodies[bodies.length - 1]).toContain('没有任何改动');
    expect(bodies[bodies.length - 1]).toContain('超过 30 秒');
    expect(w.findAll('[data-testid="ai-chat-skeleton-bar"]')).toHaveLength(0);
    expect(applyButtons(w)).toHaveLength(0);
    expect(api.aiApplyDiffs).not.toHaveBeenCalled();
    expect(w.emitted('applied')).toBeUndefined();
  });

  it('W2: 失败 —— 英文错误 / 状态码不上屏, 兜底文案说没有任何改动', async () => {
    api.aiChat.mockRejectedValue(new Error('Request failed with status code 500'));
    const w = mountPanel();
    await send(w, '新建焯水工序');
    const text = w.find('[data-testid="ai-chat-thread"]').text();
    expect(text).toContain('没有任何改动');
    expect(text).not.toContain('500');
    expect(text).not.toContain('Request failed');
  });

  it('H4: aiChat 参数不变 (factoryId / message / mode / moduleCode), 只多一个 signal', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '  新建焯水工序  ');
    expect(api.aiChat).toHaveBeenCalledTimes(1);
    expect(api.aiChat).toHaveBeenCalledWith(
      'F006',
      { message: '新建焯水工序', mode: 'plan', moduleCode: 'work_process_catalog' },
      { signal: expect.any(AbortSignal) },
    );
  });
});

describe('V3 中文名', () => {
  it('模式名是中文 (计划 / 自动 / 建议), diff 类型是中文 (工序目录变更 / 字段修改), 全文没有英文码', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    const w = mountPanel();
    expect(w.findAll('.el-segmented-stub button').map((b) => b.text())).toEqual(['计划', '自动', '建议']);
    await send(w, '新建焯水工序');
    expect(w.findAll('[data-testid="ai-diff-type"]').map((n) => n.text())).toEqual(['工序目录变更', '字段修改']);
    expect(englishLeaks(w.text())).toEqual([]);
  });
});

describe('🔴 H2 / H3: 应用 / 确认并执行 这一步', () => {
  it('H3: diff 条目一条不少 (改前 2 条, 改后 2 条), 描述全部可见; 计划模式只有工序目录那条有执行按钮', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    const w = mountPanel();
    await send(w, '新建焯水工序');
    expect(w.findAll('.diff-item')).toHaveLength(BASELINE.diffItems);
    expect(applyButtons(w)).toHaveLength(BASELINE.applyButtonsPlan);
    expect(w.text()).toContain('新建工序：焯水');
    expect(w.text()).toContain('其它说明');
  });

  it('H2: 没点之前 aiApplyDiffs 0 次; 点「应用」恰好 1 次, 参数 = (factoryId, [diff 原样]) 与改前一致', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    api.aiApplyDiffs.mockResolvedValue(RECEIPT_OK);
    const w = mountPanel();
    await send(w, '新建焯水工序');
    expect(api.aiApplyDiffs).not.toHaveBeenCalled();

    await applyButtons(w)[0].trigger('click');
    await flushPromises();
    expect(api.aiApplyDiffs).toHaveBeenCalledTimes(1);
    expect(api.aiApplyDiffs).toHaveBeenCalledWith('F006', [WP_DIFF]);
    expect(w.emitted('applied')).toHaveLength(1);
  });

  it('H2: 「自动」模式的按钮叫「确认并执行」, 同样 0 次 -> 1 次, 参数同上', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    api.aiApplyDiffs.mockResolvedValue(RECEIPT_OK);
    const w = mountPanel();
    await pickMode(w, 'autopilot');
    await send(w, '新建焯水工序');
    expect(api.aiApplyDiffs).not.toHaveBeenCalled();
    const btn = applyButtons(w)[0];
    expect(btn.text().trim()).toBe('确认并执行');
    await btn.trigger('click');
    await flushPromises();
    expect(api.aiApplyDiffs).toHaveBeenCalledTimes(1);
    expect(api.aiApplyDiffs).toHaveBeenCalledWith('F006', [WP_DIFF]);
  });

  it('H2 阴性对照: 「建议」模式没有任何执行入口 (diff 仍然全部显示)', async () => {
    api.aiChat.mockResolvedValue(REPLY);
    const w = mountPanel();
    await pickMode(w, 'action');
    await send(w, '新建焯水工序');
    expect(w.findAll('.diff-item')).toHaveLength(BASELINE.diffItems);
    expect(applyButtons(w)).toHaveLength(0);
    expect(api.aiApplyDiffs).not.toHaveBeenCalled();
  });
});

describe('N1 条件滚动', () => {
  async function scrollCallsAfterReply(metrics: { scrollHeight: number; clientHeight: number; scrollTop: number }) {
    api.aiChat.mockResolvedValue(REPLY);
    const w = mountPanel();
    const thread = w.find('[data-testid="ai-chat-thread"]').element as HTMLElement;
    const spy = vi.fn();
    thread.scrollTo = spy as unknown as typeof thread.scrollTo;
    fakeScrollMetrics(thread, metrics);
    await send(w, '新建焯水工序');
    return spy.mock.calls.length;
  }

  it('在底部附近: 用户消息 + 回答各滚一次; 往上翻 300px: 只有用户自己发的那条滚', async () => {
    const near = await scrollCallsAfterReply({ scrollHeight: 1000, clientHeight: 300, scrollTop: 700 });
    const far = await scrollCallsAfterReply({ scrollHeight: 1000, clientHeight: 300, scrollTop: 400 });
    expect(near).toBe(2);
    expect(far).toBe(1);
  });
});
