/**
 * AnalysisChatPanel (分析页右侧 AI 对话栏) 按 AI 对话标准重构后的行为测试。
 * 真实挂载 AnalysisChatPanel + 真实 ChatBubble + 真实 Element Plus, 只 mock:
 *   - 接口 askRestaurantIntent (外部 IO)
 *   - 租户来源 useAuthStore (businessDomain, 与 AIQuery.vue 的 isRestaurantTenant 同源)
 *   - ElMessage (只加 spy, 其余 Element Plus 照用)
 *   - RestaurantChartBlock (echarts 在 jsdom 里没有意义)
 * 每条工厂侧的断言都配一条餐饮租户的阴性对照。
 * 样式类断言读 .vue 源文本 (vitest 不处理 SFC <style>), 与 AIQuery.stdLayout.spec.ts 同一做法。
 */
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({
  askIntent: vi.fn(),
  auth: { factoryId: 'F001', businessDomain: 'FACTORY' } as { factoryId: string; businessDomain: string },
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
  clipboardWrite: vi.fn(),
}));

vi.mock('@/api/smartbi/restaurant-synthesis', () => ({
  askRestaurantIntent: (...args: unknown[]) => h.askIntent(...args),
  sendRestaurantAnswerFeedback: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => h.auth,
}));
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>();
  return {
    ...actual,
    ElMessage: Object.assign(
      (...args: unknown[]) => args,
      { error: (...a: unknown[]) => h.messageError(...a), success: (...a: unknown[]) => h.messageSuccess(...a) },
    ),
  };
});
vi.mock('../../chat/RestaurantChartBlock.vue', () => ({
  default: { name: 'RestaurantChartBlock', props: ['chart'], template: '<div class="stub-chart">{{ chart.title }}</div>' },
}));

import AnalysisChatPanel from '../AnalysisChatPanel.vue';
import ChatBubble from '../../chat/ChatBubble.vue';

const PANEL_SRC = readFileSync(resolve(__dirname, '../AnalysisChatPanel.vue'), 'utf8');
const BUBBLE_SRC = readFileSync(resolve(__dirname, '../../chat/ChatBubble.vue'), 'utf8');

/** 取 `<selector> {` 开始的整个花括号块的文本 (含嵌套)。 */
function cssBlock(src: string, selector: string, from = 0): string {
  const start = src.indexOf(`${selector} {`, from);
  if (start === -1) throw new Error(`selector not found: ${selector}`);
  let depth = 0;
  for (let i = src.indexOf('{', start); i < src.length; i++) {
    if (src[i] === '{') depth++;
    if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  throw new Error('unbalanced');
}

const FONT_STACK =
  '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif';

type Vm = { sendMessage: (t?: string) => Promise<void> };
const FRIENDLY = '这次没分析完，请再问一次';

function mountPanel(domain: 'FACTORY' | 'RESTAURANT'): VueWrapper {
  h.auth.businessDomain = domain;
  return mount(AnalysisChatPanel, {
    props: {
      factoryId: 'F001',
      pageTitle: '运营分析',
      contexts: [{ key: 'sales', title: '销售', dataSummary: '营收 10 万', dataScope: '2026-07', unavailableMetrics: [] }],
    },
    attachTo: document.body,
  });
}

const okAnswer = (over: Record<string, unknown> = {}) => ({
  success: true,
  answer: '这是答案正文',
  charts: [],
  alerts: [],
  ...over,
});

async function send(w: VueWrapper, text: string) {
  await (w.vm as unknown as Vm).sendMessage(text);
  await flushPromises();
}

/** 给 .acp-body 装一套可控的滚动几何 (jsdom 里 scrollHeight / clientHeight 恒为 0)。 */
function installScrollGeometry(w: VueWrapper, scrollHeight = 1000, clientHeight = 300) {
  const el = w.find('.acp-body').element as HTMLElement;
  let top = 0;
  Object.defineProperty(el, 'scrollHeight', { get: () => scrollHeight, configurable: true });
  Object.defineProperty(el, 'clientHeight', { get: () => clientHeight, configurable: true });
  Object.defineProperty(el, 'scrollTop', { get: () => top, set: (v: number) => { top = v; }, configurable: true });
  return {
    get top() { return top; },
    set top(v: number) { top = v; },
    bottom: scrollHeight - clientHeight,
  };
}

beforeEach(() => {
  h.askIntent.mockReset();
  h.messageError.mockClear();
  h.messageSuccess.mockClear();
  h.clipboardWrite = vi.fn(async () => undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: h.clipboardWrite }, configurable: true });
  if (typeof globalThis.crypto?.randomUUID !== 'function') {
    Object.defineProperty(globalThis, 'crypto', {
      value: { randomUUID: () => Math.random().toString(36).slice(2) },
      configurable: true,
    });
  }
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('L1 / L2: 版式', () => {
  it('工厂: 助手无头像, 气泡根带 bubble-std; 用户消息和助手正文仍在 (对照)', async () => {
    h.askIntent.mockResolvedValue(okAnswer());
    const w = mountPanel('FACTORY');
    await send(w, '看看销售');

    const ai = w.find('.chat-bubble.bubble-ai');
    const user = w.find('.chat-bubble.bubble-user');
    expect(ai.exists() && user.exists()).toBe(true);
    expect(ai.classes()).toContain('bubble-std');
    expect(user.classes()).toContain('bubble-std');
    expect(w.findAll('.bubble-avatar')).toHaveLength(0);
    expect(w.find('.acp-panel').classes()).toContain('acp-std');
    // 对照: 消息本身没丢
    expect(ai.text()).toContain('这是答案正文');
    expect(user.text()).toContain('看看销售');
  });

  it('餐饮阴性对照: 头像在, 没有 bubble-std / acp-std', async () => {
    h.askIntent.mockResolvedValue(okAnswer());
    const w = mountPanel('RESTAURANT');
    await send(w, '看看销售');
    expect(w.findAll('.bubble-avatar')).toHaveLength(1);
    expect(w.find('.chat-bubble.bubble-ai').classes()).not.toContain('bubble-std');
    expect(w.find('.acp-panel').classes()).not.toContain('acp-std');
  });

  it('ChatBubble 不传 variant = 旧版式 (默认值保持旧样子)', () => {
    const w = mount(ChatBubble, { props: { turn: { id: 'a', role: 'ai', content: 'hi', timestamp: 1 } } });
    expect(w.find('.bubble-avatar').exists()).toBe(true);
    expect(w.classes()).not.toContain('bubble-std');
    const std = mount(ChatBubble, { props: { variant: 'standard', turn: { id: 'a', role: 'ai', content: 'hi', timestamp: 1 } } });
    expect(std.find('.bubble-avatar').exists()).toBe(false);
    expect(std.classes()).toContain('bubble-std');
  });
});

describe('L2 / T1-T3 / S4: 样式源文本 (+ 旧样式阴性对照)', () => {
  it('ChatBubble: 用户气泡 min(78%, 320px)/圆角 12/10px 12px; 助手透明无框; 16px/1.6; 字体栈', () => {
    const user = cssBlock(BUBBLE_SRC, '.bubble-std.bubble-user .bubble-body');
    expect(user).toContain('max-width: min(78%, 320px);');
    expect(user).toContain('padding: 10px 12px;');
    expect(user).toContain('border-radius: 12px;');

    const ai = cssBlock(BUBBLE_SRC, '.bubble-std.bubble-ai .bubble-body');
    expect(ai).toContain('background: transparent;');
    expect(ai).toContain('border: 0;');
    expect(ai).toContain('box-shadow: none;');
    expect(ai).toContain('padding: 0;');
    expect(ai).toContain('width: 100%;');

    const content = cssBlock(BUBBLE_SRC, '.bubble-std .bubble-content');
    expect(content).toContain('font-size: 16px;');
    expect(content).toContain('line-height: 1.6;');
    expect(content).toContain('letter-spacing: 0;');

    const root = cssBlock(BUBBLE_SRC, '.bubble-std');
    expect(root).toContain(FONT_STACK);
  });

  it('标准版式区段内没有 Noto Serif SC / Playfair Display / 10-11px', () => {
    const bubbleStd = BUBBLE_SRC.slice(BUBBLE_SRC.indexOf('/* ───────── variant="standard" (工厂租户'));
    const panelStd = PANEL_SRC.slice(PANEL_SRC.indexOf('/* ───────── 标准版式 (.acp-std'));
    for (const withComments of [bubbleStd, panelStd]) {
      const seg = withComments.replace(/\/\*[\s\S]*?\*\//g, ''); // 注释里会写「⛔ 不写 Noto Serif SC」, 只查声明
      expect(seg.length).toBeGreaterThan(500); // 防止切片落空
      expect(seg).not.toMatch(/Noto Serif|Playfair/);
      expect(seg).not.toMatch(/font-size:\s*1[01]px/);
    }
    expect(PANEL_SRC).toContain(FONT_STACK);
  });

  it('阴性对照: 旧版式规则原值还在 (餐饮仍在用), 全局 --font-family 没被改', () => {
    const oldUser = cssBlock(BUBBLE_SRC, '.bubble-user .bubble-body');
    expect(oldUser).toContain('max-width: 72%;');
    expect(oldUser).toContain('padding: 12px 16px;');
    expect(oldUser).toContain('border-radius: 14px 14px 4px 14px;');
    expect(oldUser).toContain("'Noto Serif SC'");
    expect(cssBlock(BUBBLE_SRC, '.bubble-ai .bubble-avatar')).toContain("'Playfair Display'");
    const globalCss = readFileSync(resolve(__dirname, '../../../../../style.css'), 'utf8');
    const fam = globalCss.match(/--font-family:\s*([^;]+);/);
    expect(fam).not.toBeNull();
    expect(fam![1]).toMatch(/^\s*['"]?Noto Sans SC/);
  });

  it('S4: 工厂输入框 / 发送是 large(40px), 圆角 8 / 内边距 12 / 16px; 餐饮保持默认档', () => {
    const f = mountPanel('FACTORY');
    expect(f.find('.acp-input .el-input').classes()).toContain('el-input--large');
    expect(f.find('.acp-input .el-button').classes()).toContain('el-button--large');
    const wrapper = cssBlock(PANEL_SRC, '.acp-std .acp-input :deep(.el-input__wrapper)');
    expect(wrapper).toContain('min-height: 40px;');
    expect(wrapper).toContain('border-radius: 8px;');
    expect(wrapper).toContain('padding: 1px 12px;');
    expect(cssBlock(PANEL_SRC, '.acp-std .acp-input :deep(.el-input__inner)')).toContain('font-size: 16px;');

    const r = mountPanel('RESTAURANT');
    expect(r.find('.acp-input .el-input').classes()).not.toContain('el-input--large');
    expect(r.find('.acp-input .el-button').classes()).not.toContain('el-button--large');
  });

  it('按钮尺寸: 工厂 清空对话 / 聚焦 / 追问芯片都是 default(32px) 档; 餐饮保持 small', async () => {
    h.askIntent.mockResolvedValue(okAnswer({ followUpActions: [{ label: '最近30天', question: '最近30天怎样' }] }));
    const f = mountPanel('FACTORY');
    await send(f, '问');
    const clearF = f.findAll('.acp-header .el-button')[0];
    expect(clearF.classes()).not.toContain('el-button--small');
    expect(f.find('.el-radio-group .el-radio-button').classes()).not.toContain('el-radio-button--small');
    const chipF = f.findAll('.acp-followups .el-button');
    expect(chipF).toHaveLength(1);
    expect(chipF[0].classes()).not.toContain('el-button--small');

    h.askIntent.mockResolvedValue(okAnswer({ followUpActions: [{ label: '最近30天', question: '最近30天怎样' }] }));
    const r = mountPanel('RESTAURANT');
    await send(r, '问');
    expect(r.findAll('.acp-header .el-button')[0].classes()).toContain('el-button--small');
    expect(r.find('.el-radio-group .el-radio-button').classes()).toContain('el-radio-button--small');
    expect(r.find('.acp-followups .el-button').classes()).toContain('el-button--small');
  });
});

describe('G1: markdown 表格 → 记录卡 (窄面板)', () => {
  const table = '| 门店 | 营收 |\n| --- | --- |\n| 一店 | 100 |\n| 二店 | 200 |\n';
  it('standard: 没有 <table>, 每行一张卡, 表头做标签', () => {
    const w = mount(ChatBubble, { props: { variant: 'standard', turn: { id: 'a', role: 'ai', content: table, timestamp: 1 } } });
    expect(w.find('table').exists()).toBe(false);
    const cards = w.findAll('.bubble-record');
    expect(cards).toHaveLength(2);
    expect(cards[0].text().replace(/\s+/g, '')).toBe('门店一店营收100');
    expect(cards[1].findAll('.bubble-record-key').map((n) => n.text())).toEqual(['门店', '营收']);
  });
  it('阴性对照: 默认(legacy)仍是 <table>, 没有记录卡', () => {
    const w = mount(ChatBubble, { props: { turn: { id: 'a', role: 'ai', content: table, timestamp: 1 } } });
    expect(w.find('table').exists()).toBe(true);
    expect(w.findAll('.bubble-record')).toHaveLength(0);
  });
});

describe('W1: 骨架屏', () => {
  it('工厂: 等待中出现 3 条骨架 + 文字, 没有三点动画; 到达后消失', async () => {
    let release: (v: unknown) => void = () => {};
    h.askIntent.mockReturnValue(new Promise((r) => { release = r; }));
    const w = mountPanel('FACTORY');
    const pending = (w.vm as unknown as Vm).sendMessage('看看销售');
    await flushPromises();
    expect(w.find('[data-testid="acp-loading-skeleton"]').exists()).toBe(true);
    expect(w.findAll('[data-testid="acp-skeleton-bar"]')).toHaveLength(3);
    expect(w.find('[data-testid="acp-loading-skeleton"]').text()).toContain('正在分析');
    expect(w.find('.typing-wrap').exists()).toBe(false);

    release(okAnswer());
    await pending;
    await flushPromises();
    expect(w.find('[data-testid="acp-loading-skeleton"]').exists()).toBe(false);
    expect(w.find('.bubble-ai').text()).toContain('这是答案正文');
  });

  it('餐饮阴性对照: 仍是三点动画, 没有骨架', async () => {
    h.askIntent.mockReturnValue(new Promise(() => {}));
    const w = mountPanel('RESTAURANT');
    void (w.vm as unknown as Vm).sendMessage('看看销售');
    await flushPromises();
    expect(w.find('.typing-wrap').exists()).toBe(true);
    expect(w.find('[data-testid="acp-loading-skeleton"]').exists()).toBe(false);
  });
});

describe('W2: 30 秒客户端超时 + 失败文案说人话', () => {
  it('工厂: 29.9s 还在等, 30s 中断并给出人话文案; 迟到的答案不再落进对话', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const abortSpy = vi.spyOn(AbortController.prototype, 'abort');
    let release: (v: unknown) => void = () => {};
    h.askIntent.mockReturnValue(new Promise((r) => { release = r; }));
    const w = mountPanel('FACTORY');
    const pending = (w.vm as unknown as Vm).sendMessage('看看销售');
    await vi.advanceTimersByTimeAsync(29_900);
    expect(abortSpy).not.toHaveBeenCalled();
    expect(w.find('[data-testid="acp-loading-skeleton"]').exists()).toBe(true);
    expect(w.findAll('.bubble-ai')).toHaveLength(0);

    await vi.advanceTimersByTimeAsync(200); // 越过 30s
    await pending;
    await flushPromises();
    expect(abortSpy).toHaveBeenCalledTimes(1); // 可观测量: AbortController 真的被中断了
    expect(w.find('[data-testid="acp-loading-skeleton"]').exists()).toBe(false);
    const ai = w.findAll('.bubble-ai');
    expect(ai).toHaveLength(1);
    expect(ai[0].text()).toContain(FRIENDLY);
    expect(h.messageError).toHaveBeenCalledWith(expect.stringContaining(FRIENDLY));
    expect(w.find('.acp-input .el-input').classes()).not.toContain('is-disabled'); // 能再问

    // 迟到的答案: 被中断的请求不许再往对话里塞东西
    release(okAnswer({ answer: '迟到的答案' }));
    await flushPromises();
    expect(w.text()).not.toContain('迟到的答案');
    expect(w.findAll('.bubble-ai')).toHaveLength(1);
  });

  it('餐饮阴性对照: 过了 60 秒仍在等 (旧行为没有超时), 也没有 abort', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const abortSpy = vi.spyOn(AbortController.prototype, 'abort');
    h.askIntent.mockReturnValue(new Promise(() => {}));
    const w = mountPanel('RESTAURANT');
    void (w.vm as unknown as Vm).sendMessage('看看销售');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(abortSpy).not.toHaveBeenCalled();
    expect(w.find('.typing-wrap').exists()).toBe(true);
    expect(w.findAll('.bubble-ai')).toHaveLength(0);
  });

  it('工厂: 接口返回失败 / 抛异常, 文案说人话, 不露后端原文; 餐饮仍露原文 (阴性对照)', async () => {
    h.askIntent.mockResolvedValueOnce({ success: false, answer: '', charts: [], alerts: [], error: 'Java intent service unavailable' });
    const f = mountPanel('FACTORY');
    await send(f, '问一');
    expect(f.find('.bubble-ai').text()).toContain(FRIENDLY);
    expect(f.text()).not.toContain('Java intent service unavailable');
    expect(f.text()).not.toContain('请求失败');
    expect(f.find('.bubble-error').exists()).toBe(false);
    expect(h.messageError).toHaveBeenLastCalledWith(expect.stringContaining(FRIENDLY));

    h.askIntent.mockRejectedValueOnce(new Error('Network Error 502'));
    await send(f, '问二');
    expect(f.findAll('.bubble-ai')[1].text()).toContain(FRIENDLY);
    expect(f.text()).not.toContain('502');

    h.askIntent.mockResolvedValueOnce({ success: false, answer: '', charts: [], alerts: [], error: 'Java intent service unavailable' });
    const r = mountPanel('RESTAURANT');
    await send(r, '问一');
    expect(r.find('.bubble-ai').text()).toContain('请求失败');
    expect(r.find('.bubble-ai').text()).toContain('Java intent service unavailable');
    expect(r.text()).not.toContain(FRIENDLY);
  });

  it('工厂: 清空对话会中断进行中的请求, 迟到的答案不落进新对话', async () => {
    const abortSpy = vi.spyOn(AbortController.prototype, 'abort');
    let release: (v: unknown) => void = () => {};
    h.askIntent.mockReturnValue(new Promise((r) => { release = r; }));
    const w = mountPanel('FACTORY');
    const pending = (w.vm as unknown as Vm).sendMessage('看看销售');
    await flushPromises();
    expect(w.find('[data-testid="acp-loading-skeleton"]').exists()).toBe(true);

    await w.findAll('.acp-header .el-button')[0].trigger('click'); // 清空对话 (N3 保留)
    await flushPromises();
    expect(abortSpy).toHaveBeenCalledTimes(1);
    expect(w.find('[data-testid="acp-loading-skeleton"]').exists()).toBe(false);
    expect(w.findAll('.chat-bubble')).toHaveLength(0);

    release(okAnswer({ answer: '迟到的答案' }));
    await pending;
    await flushPromises();
    expect(w.text()).not.toContain('迟到的答案');
    expect(w.findAll('.chat-bubble')).toHaveLength(0);
    // 对照: 清空后可以正常再问一次
    h.askIntent.mockResolvedValue(okAnswer({ answer: '新答案' }));
    await send(w, '再问');
    expect(w.find('.bubble-ai').text()).toContain('新答案');
  });
});

describe('N1: 只有离底部不超过 120px 才自动滚到底', () => {
  /** offsetFromBottom: 等待期间用户把滚动条停在离底部多少 px (null = 没动, 仍在底部)。返回答案到达后的 scrollTop。 */
  async function scenario(domain: 'FACTORY' | 'RESTAURANT', offsetFromBottom: number | null) {
    let release: (v: unknown) => void = () => {};
    h.askIntent.mockReturnValue(new Promise((r) => { release = r; }));
    const w = mountPanel(domain);
    const geo = installScrollGeometry(w);
    const pending = (w.vm as unknown as Vm).sendMessage('看看销售');
    await flushPromises();
    expect(geo.top).toBe(1000); // 用户自己发送时一定滚到底 (scrollTop = scrollHeight)
    if (offsetFromBottom !== null) geo.top = geo.bottom - offsetFromBottom;
    const parked = geo.top;
    release(okAnswer());
    await pending;
    await flushPromises();
    w.unmount();
    return { after: geo.top, parked };
  }

  it('工厂: 用户在底部 → 答案到达后滚到底 (阳性对照)', async () => {
    const r = await scenario('FACTORY', null);
    expect(r.after).toBe(1000);
  });

  it('工厂: 用户往上翻了 400px → 答案到达后 scrollTop 不动', async () => {
    const r = await scenario('FACTORY', 400);
    expect(r.parked).toBe(300);
    expect(r.after).toBe(300);
  });

  it('工厂: 离底部 120px 仍然跟随, 121px 不跟随 (边界)', async () => {
    const at120 = await scenario('FACTORY', 120);
    expect(at120.parked).toBe(580);
    expect(at120.after).toBe(1000);
    const at121 = await scenario('FACTORY', 121);
    expect(at121.parked).toBe(579);
    expect(at121.after).toBe(579);
  });

  it('餐饮阴性对照: 同样往上翻 400px, 旧行为仍然无条件滚到底', async () => {
    const r = await scenario('RESTAURANT', 400);
    expect(r.parked).toBe(300);
    expect(r.after).toBe(1000);
  });
});

describe('F1: 示例问题只在空对话出现', () => {
  it('工厂: 空对话 3 个示例芯片 (32px 档), 点一个就发这个问题, 发出后芯片消失', async () => {
    h.askIntent.mockResolvedValue(okAnswer());
    const w = mountPanel('FACTORY');
    const chips = w.findAll('[data-testid="acp-example"]');
    expect(chips).toHaveLength(3);
    expect(chips[0].classes()).not.toContain('el-button--small');
    const label = chips[1].text();
    await chips[1].trigger('click');
    await flushPromises();
    expect(h.askIntent).toHaveBeenCalledTimes(1);
    expect(h.askIntent.mock.calls[0][1]).toBe(label);
    expect(w.findAll('[data-testid="acp-example"]')).toHaveLength(0);
    expect(w.find('.acp-empty').exists()).toBe(false);
  });

  it('工厂: 清空对话后示例问题回来; 餐饮阴性对照: 没有芯片, 保留写死的示例文字', async () => {
    h.askIntent.mockResolvedValue(okAnswer());
    const f = mountPanel('FACTORY');
    await send(f, '问');
    expect(f.findAll('[data-testid="acp-example"]')).toHaveLength(0);
    await f.findAll('.acp-header .el-button')[0].trigger('click');
    await flushPromises();
    expect(f.findAll('[data-testid="acp-example"]')).toHaveLength(3);

    const r = mountPanel('RESTAURANT');
    expect(r.findAll('[data-testid="acp-example"]')).toHaveLength(0);
    expect(r.find('.acp-empty-text').text()).toContain('用3条bullet解读并给营销建议');
  });
});

describe('N2: 悬停出现的「复制」', () => {
  it('工厂: 每条消息一个复制按钮 (32px 档), 点了复制该条正文; 样式默认隐藏、悬停显示', async () => {
    h.askIntent.mockResolvedValue(okAnswer());
    const w = mountPanel('FACTORY');
    await send(w, '看看销售');
    const copies = w.findAll('[data-testid="acp-copy"]');
    expect(copies).toHaveLength(2); // 用户 + 助手
    expect(copies[1].classes()).not.toContain('el-button--small');
    await copies[1].trigger('click');
    await flushPromises();
    expect(h.clipboardWrite).toHaveBeenCalledWith('这是答案正文');
    expect(h.messageSuccess).toHaveBeenCalledWith('已复制');
    await copies[0].trigger('click');
    expect(h.clipboardWrite).toHaveBeenLastCalledWith('看看销售');

    expect(cssBlock(PANEL_SRC, '.acp-std .acp-actions')).toContain('opacity: 0;');
    expect(PANEL_SRC).toMatch(/\.chat-bubble:hover\) \.acp-actions[\s\S]*?\{\s*opacity: 1;/);
  });

  it('餐饮阴性对照: 没有复制按钮', async () => {
    h.askIntent.mockResolvedValue(okAnswer());
    const w = mountPanel('RESTAURANT');
    await send(w, '看看销售');
    expect(w.findAll('[data-testid="acp-copy"]')).toHaveLength(0);
    expect(w.text()).not.toContain('复制');
  });
});

describe('E1 / 其余保留项', () => {
  it('工厂: 不显示遥测 (来源 / 耗时 / token / 置信度 / 毫秒); 降级说明保留 (阳性对照)', async () => {
    h.askIntent.mockResolvedValue(okAnswer({ source: 'restaurant_ops_gold', tokens: 321 }));
    const w = mountPanel('FACTORY');
    await send(w, '看看销售');
    expect(w.text()).not.toMatch(/restaurant_ops_gold|快速响应|置信度|token|\d+\s?ms\b|321/i);

    h.askIntent.mockResolvedValue(okAnswer({ source: 'degraded', answer: '系统提示' }));
    await send(w, '再问');
    expect(w.find('.acp-source-note').exists()).toBe(true);
    expect(w.find('.acp-source-note').text()).toContain('降级');
  });

  it('工厂: 请求参数不变 (factoryId / 原始问题 / 会话 / pageContext+dimensionHints)', async () => {
    h.askIntent.mockResolvedValue(okAnswer());
    const w = mountPanel('FACTORY');
    await send(w, '看看销售');
    const [factoryId, question, sessionId, context] = h.askIntent.mock.calls[0];
    expect(factoryId).toBe('F001');
    expect(question).toBe('看看销售');
    expect(sessionId).toEqual(expect.any(String));
    expect(context.pageContext).toContain('营收 10 万');
    expect(context.dimensionHints).toEqual(['sales']);
  });
});
