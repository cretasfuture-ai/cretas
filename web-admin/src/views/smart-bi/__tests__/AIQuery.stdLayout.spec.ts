/**
 * AIQuery.vue 按 AI 对话标准 (§3 web-admin) 重构后的行为测试, 真实挂载 AIQuery + AiOperationCard, 只 mock API。
 *
 * jsdom 不计算 scoped CSS, 所以样式判据分两路:
 *   - DOM: 没有 .message-avatar / 时间节点、存在 data-testid、按钮 size、`.ai-std` class 真的挂在对话容器上 (接上没有);
 *   - 源文本: 读 AIQuery.vue 的 <style> 断言声明, 并配阴性对照 (餐饮用的旧选择器保持原值、全局字体变量没被改)。
 *
 * 每个「X 没出现」的断言都配了一个「X 在另一种条件下出现」的对照 (硬约束 9)。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const h = vi.hoisted(() => ({
  routeMock: { query: {} as Record<string, string> },
  authState: { factoryId: 'F001', businessDomain: 'FACTORY', user: { id: 1 } } as Record<string, unknown>,
  clipboardWrite: undefined as unknown as ReturnType<typeof import('vitest').vi.fn>,
  messageSuccess: undefined as unknown as ReturnType<typeof import('vitest').vi.fn>,
  messageError: undefined as unknown as ReturnType<typeof import('vitest').vi.fn>,
}));

const executeIntentMock = vi.fn();
const chatAnalysisStreamMock = vi.fn(() => ({ abort: vi.fn() }));
const chatAnalysisMock = vi.fn(async () => ({ success: false }));
const nl2sqlMock = vi.fn();
const listFactoryTemplatesMock = vi.fn(async () => ({ success: true, templates: [] as unknown[] }));

vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: (...a: unknown[]) => executeIntentMock(...a),
  confirmIntentAction: vi.fn(),
  fetchCachedXlsx: vi.fn(),
  submitIntentFeedback: vi.fn(async () => true),
}));
vi.mock('@/api/smartbi', () => ({
  chatAnalysis: (...a: unknown[]) => chatAnalysisMock(...a),
  chatAnalysisStream: (...a: unknown[]) => chatAnalysisStreamMock(...(a as [])),
  getUploadHistory: vi.fn(async () => []),
  deduplicateUploads: vi.fn(async () => ({})),
  nl2sql: (...a: unknown[]) => nl2sqlMock(...a),
  logFeedback: vi.fn(async () => ({})),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => h.authState }));
vi.mock('vue-router', () => ({
  useRoute: () => h.routeMock,
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>();
  const success = vi.fn();
  const error = vi.fn();
  h.messageSuccess = success;
  h.messageError = error;
  return { ...actual, ElMessage: Object.assign(vi.fn(), actual.ElMessage, { success, error }) };
});
vi.mock('@/utils/echarts', () => ({
  default: { init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }) },
}));
vi.mock('@/utils/echarts-fmt', () => ({ processEChartsOptions: (o: unknown) => o }));
vi.mock('@/composables/useChartResize', () => ({
  useChartResize: () => ({ observe: vi.fn(), disconnect: vi.fn() }),
}));
vi.mock('marked', () => ({ marked: (s: string) => s }));
vi.mock('dompurify', () => ({ default: { sanitize: (s: string) => s } }));
vi.mock('@/components/smartbi', () => ({ AIInsightPanel: { template: '<div />' } }));
vi.mock('@/components/smartbi/SmartBIEmptyState.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/components/smart-bi/MaterializedAnalysisPanel.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/api/smartbi/materialized', () => ({
  listFactoryTemplates: (...a: unknown[]) => listFactoryTemplatesMock(...(a as [])),
}));

import AIQuery from '../AIQuery.vue';

const globalStubs = {
  'el-button': {
    props: ['disabled', 'loading', 'size'],
    emits: ['click'],
    template: '<button class="el-button" :data-size="size" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-link': { emits: ['click'], template: '<a class="el-link" @click="$emit(\'click\')"><slot /></a>' },
  'el-icon': { template: '<i class="el-icon"><slot /></i>' },
  'el-input': { template: '<input />' },
  'el-autocomplete': {
    props: ['size'],
    template: '<div class="el-autocomplete" :data-size="size"><slot :item="{ value: \'x\' }" /></div>',
  },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-table': { template: '<table class="el-table"><slot /></table>' },
  'el-table-column': {
    props: ['label', 'prop', 'minWidth'],
    template: '<th class="col" :data-prop="prop" :data-min-width="minWidth">{{ label }}</th>',
  },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-switch': { template: '<input type="checkbox" />' },
  'el-empty': { template: '<div><slot /></div>' },
  'el-alert': { props: ['title'], template: '<div class="el-alert">{{ title }}<slot /></div>' },
  'el-segmented': { template: '<div />' },
};

interface Vm {
  inputQuery: string;
  nl2sqlMode: boolean;
  selectedUploadId: number | null;
  handleSendMessage: () => Promise<void>;
}

async function mountPage(domain: 'FACTORY' | 'RESTAURANT' = 'FACTORY', query: Record<string, string> = {}) {
  h.authState.businessDomain = domain;
  h.authState.factoryId = domain === 'RESTAURANT' ? 'RES_3101_009' : 'F001';
  h.routeMock.query = query;
  const wrapper = mount(AIQuery, { global: { stubs: globalStubs }, attachTo: document.body });
  await flushPromises();
  return wrapper;
}

async function send(wrapper: VueWrapper, question: string) {
  const vm = wrapper.vm as unknown as Vm;
  vm.inputQuery = question;
  await vm.handleSendMessage();
  await flushPromises();
}

const successResponse = {
  status: 'SUCCESS',
  intentRecognized: true,
  intentCode: 'MATERIAL_BATCH_QUERY',
  intentName: '原料批次查询',
  message: '这是答案正文',
  formattedText: '这是答案正文',
  resultData: null,
  sessionId: 's1',
};

const SQL_RESULT = {
  success: true,
  sql: 'SELECT revenue FROM t',
  explanation: '各产品营收',
  intent: 'AGGREGATE_QUERY',
  confidence: 0.91,
  executionTimeMs: 1234,
  rowCount: 3,
  warnings: ['结果被截断到 200 行'],
  result: [
    { revenue: 100, 产品: '甲', weird_col_x: 'a' },
    { revenue: 200, 产品: '乙', weird_col_x: 'b' },
    { revenue: 300, 产品: '丙', weird_col_x: 'c' },
  ],
};

async function askSql(wrapper: VueWrapper, response: unknown) {
  nl2sqlMock.mockResolvedValue(response);
  const vm = wrapper.vm as unknown as Vm;
  vm.nl2sqlMode = true;
  vm.selectedUploadId = 1;
  await flushPromises();
  await send(wrapper, '各产品营收');
}

const AIQUERY_SRC = readFileSync(resolve(__dirname, '../AIQuery.vue'), 'utf8');

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

beforeEach(() => {
  setActivePinia(createPinia());
  sessionStorage.clear();
  executeIntentMock.mockReset();
  chatAnalysisStreamMock.mockReset();
  chatAnalysisStreamMock.mockImplementation(() => ({ abort: vi.fn() }));
  chatAnalysisMock.mockReset();
  chatAnalysisMock.mockImplementation(async () => ({ success: false }));
  nl2sqlMock.mockReset();
  listFactoryTemplatesMock.mockReset();
  listFactoryTemplatesMock.mockImplementation(async () => ({ success: true, templates: [] }));
  h.messageSuccess.mockClear();
  h.messageError.mockClear();
  h.clipboardWrite = vi.fn(async () => undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: h.clipboardWrite }, configurable: true });
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.useRealTimers();
});

describe('L1 / L2 / L4: 版式', () => {
  it('非餐饮租户: 无头像、无时间、无角色名行; 用户消息和助手正文仍在 (阴性对照)', async () => {
    executeIntentMock.mockResolvedValue(successResponse);
    const w = await mountPage('FACTORY');
    await send(w, '查一下今天的库存');

    expect(w.find('[data-testid="aiq-thread"]').exists()).toBe(true);
    expect(w.find('.chat-history').classes()).toContain('ai-std');
    expect(w.findAll('.message-avatar')).toHaveLength(0);
    expect(w.findAll('.message-time')).toHaveLength(0);
    expect(w.findAll('.role-name')).toHaveLength(0);
    expect(w.text()).not.toMatch(/\b\d{1,2}:\d{2}\b/);

    // 对照: 消息本身没丢
    const user = w.findAll('[data-testid="aiq-user-bubble"]');
    expect(user).toHaveLength(1);
    expect(user[0].text()).toContain('查一下今天的库存');
    const assistant = w.findAll('[data-testid="aiq-assistant-body"]');
    expect(assistant.length).toBeGreaterThanOrEqual(2); // 欢迎语 + 回答
    expect(assistant[assistant.length - 1].text()).toContain('这是答案正文');
  });

  it('阴性对照: 餐饮租户版式不变 (头像 / 角色名 / 时间都还在, 没有 ai-std)', async () => {
    executeIntentMock.mockResolvedValue(successResponse);
    const w = await mountPage('RESTAURANT');
    await send(w, '查一下今天的库存');
    expect(w.find('.chat-history').classes()).not.toContain('ai-std');
    expect(w.findAll('.message-avatar').length).toBeGreaterThan(0);
    expect(w.findAll('.role-name').length).toBeGreaterThan(0);
    expect(w.findAll('.message-time').length).toBeGreaterThan(0);
  });

  it('样式源文本: 768 居中 / 用户气泡 / 16px / 字体栈 / 无 10-11px; 阴性对照: 餐饮旧样式和全局字体没被改', () => {
    const style = AIQUERY_SRC.slice(AIQUERY_SRC.indexOf('<style lang="scss" scoped>'));
    const std = cssBlock(style, '.chat-history.ai-std');

    expect(std).toContain(
      'font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;',
    );
    const thread = cssBlock(std, '.chat-thread');
    expect(thread).toContain('max-width: 768px;');
    expect(thread).toContain('margin: 0 auto;');

    const user = cssBlock(std, '&.user');
    expect(user).toContain('max-width: min(70%, 560px);');
    expect(user).toContain('border-radius: 12px;');
    expect(user).toContain('padding: 10px 12px;');

    const assistant = cssBlock(std, '&.assistant');
    expect(assistant).toContain('background: transparent;');
    expect(assistant).toContain('box-shadow: none;');
    expect(assistant).toContain('border: 0;');

    const text = cssBlock(std, '.message-text', std.indexOf('// T2 / T4'));
    expect(text).toContain('font-size: 16px;');
    expect(text).toContain('line-height: 1.6;');
    expect(text).toContain('letter-spacing: 0;');

    // T3: 整个文件里 10 / 11px 只允许出现在餐饮专用的 .restaurant-ai-contract 一条规则里
    const small = [...AIQUERY_SRC.matchAll(/font-size:\s*1[01]px/g)];
    expect(small).toHaveLength(1);
    expect(cssBlock(style, '.restaurant-ai-contract')).toMatch(/font-size:\s*11px/);

    // 阴性对照: 餐饮仍在用的旧规则保持原值
    const oldMessage = cssBlock(style, '.chat-message');
    expect(oldMessage).toContain('width: 40px;'); // .message-avatar
    expect(oldMessage).toContain('border-radius: 0 12px 12px 12px;'); // 旧助手气泡圆角
    // 阴性对照: 没有改全局 --font-family
    const globalCss = readFileSync(resolve(__dirname, '../../../style.css'), 'utf8');
    const fam = globalCss.match(/--font-family:\s*([^;]+);/);
    expect(fam).not.toBeNull();
    expect(fam![1]).toMatch(/^\s*['"]?Noto Sans SC/);
  });

  it('S2/S3/S4: 非餐饮按钮、芯片是 default(32px) 档, 输入框 large; 餐饮保持 small (阴性对照)', async () => {
    const w = await mountPage('FACTORY');
    const chips = w.findAll('.quick-questions .el-button');
    expect(chips.length).toBeGreaterThan(0);
    expect(chips.every((b) => b.attributes('data-size') === 'default')).toBe(true);
    expect(w.find('.el-autocomplete').attributes('data-size')).toBe('large');
    expect(w.find('[data-testid="aiq-input-area"]').classes()).toContain('ai-std-input');

    const style = AIQUERY_SRC.slice(AIQUERY_SRC.indexOf('<style lang="scss" scoped>'));
    const input = cssBlock(style, '.input-area.ai-std-input');
    expect(input).toContain('min-height: 40px;');
    expect(input).toContain('max-height: 200px;');
    expect(input).toContain('padding: 1px 12px;');
    expect(input).toContain('border-radius: 8px;');

    const rw = await mountPage('RESTAURANT');
    const rwChips = rw.findAll('.restaurant-primary-questions .el-button');
    expect(rwChips.length).toBeGreaterThan(0);
    expect(rwChips.every((b) => b.attributes('data-size') === 'small')).toBe(true);
    expect(rw.find('.el-autocomplete').attributes('data-size')).toBe('default');
    expect(rw.find('[data-testid="aiq-input-area"]').classes()).not.toContain('ai-std-input');
  });
});

describe('E1 / E2: 遥测默认不显示, ?aiDebug=1 才显示', () => {
  it('默认: 意图码 / 置信度 / 毫秒 / SQL / warnings 都不渲染, 「共 N 行」仍在', async () => {
    const w = await mountPage('FACTORY');
    await askSql(w, SQL_RESULT);

    expect(nl2sqlMock).toHaveBeenCalledTimes(1); // 阳性对照: 查询真的发生并渲染了结果
    expect(w.find('[data-testid="aiq-table"]').exists()).toBe(true);

    expect(w.find('[data-testid="aiq-sql-rows"]').text()).toBe('共 3 行');
    for (const id of ['aiq-sql-intent', 'aiq-sql-confidence', 'aiq-sql-time', 'aiq-sql-code', 'aiq-sql-warnings']) {
      expect(w.find(`[data-testid="${id}"]`).exists()).toBe(false);
    }
    const text = w.text();
    for (const leaked of ['AGGREGATE_QUERY', '置信度', '91%', '1234ms', 'ms', 'SELECT revenue', '结果被截断']) {
      expect(text).not.toContain(leaked);
    }
  });

  it('?aiDebug=1: 同一份结果, 这些都显示 (对照)', async () => {
    const w = await mountPage('FACTORY', { aiDebug: '1' });
    await askSql(w, SQL_RESULT);
    expect(w.find('[data-testid="aiq-sql-intent"]').text()).toBe('AGGREGATE_QUERY');
    expect(w.find('[data-testid="aiq-sql-confidence"]').text()).toContain('91%');
    expect(w.find('[data-testid="aiq-sql-time"]').text()).toBe('1234ms');
    expect(w.find('[data-testid="aiq-sql-code"]').text()).toContain('SELECT revenue');
    expect(w.find('[data-testid="aiq-sql-warnings"]').text()).toContain('结果被截断');
    expect(w.find('[data-testid="aiq-sql-rows"]').text()).toBe('共 3 行');
  });

  it('aiDebug 只认 "1" (aiDebug=0 / true 不算)', async () => {
    const w = await mountPage('FACTORY', { aiDebug: 'true' });
    await askSql(w, SQL_RESULT);
    expect(w.find('[data-testid="aiq-sql-code"]').exists()).toBe(false);
  });

  it('「秒回」和「已物化 N」: 默认不显示, ?aiDebug=1 才显示; 餐饮保持原样', async () => {
    listFactoryTemplatesMock.mockImplementation(async () => ({
      success: true,
      templates: [{ code: 'sales_summary', title: '销售汇总', isRecommended: true, materializationCount: 5 }],
    }));
    const plain = await mountPage('FACTORY');
    expect(plain.findAll('.recommend-chip').length).toBeGreaterThan(0); // 阳性对照: 推荐芯片在
    expect(plain.findAll('.suggestion-tag')).toHaveLength(0);
    expect(plain.text()).not.toContain('已物化');
    expect(plain.text()).not.toContain('秒回');

    const dbg = await mountPage('FACTORY', { aiDebug: '1' });
    expect(dbg.findAll('.suggestion-tag').length).toBeGreaterThan(0);
    expect(dbg.text()).toContain('已物化 5');

    const rw = await mountPage('RESTAURANT');
    expect(rw.findAll('.suggestion-tag').length).toBeGreaterThan(0);
  });
});

describe('W1: 骨架屏', () => {
  it('加载中: 3 条骨架 (92/78/62%) + 一行文字, 没有转圈; 结束后消失', async () => {
    let resolveIntent: (v: unknown) => void = () => undefined;
    executeIntentMock.mockImplementation(() => new Promise((r) => { resolveIntent = r; }));
    const w = await mountPage('FACTORY');
    const vm = w.vm as unknown as Vm;
    vm.inputQuery = '查一下今天的库存';
    const pending = vm.handleSendMessage();
    await flushPromises();

    const sk = w.find('[data-testid="aiq-loading-skeleton"]');
    expect(sk.exists()).toBe(true);
    const bars = sk.findAll('[data-testid="aiq-skeleton-bar"]');
    expect(bars).toHaveLength(3);
    expect(bars.map((b) => (b.element as HTMLElement).style.width)).toEqual(['92%', '78%', '62%']);
    expect(sk.text()).toContain('正在思考...');
    expect(sk.find('.el-icon').exists()).toBe(false); // 不是转圈
    expect(w.text().split('正在思考...').length - 1).toBe(1); // 文字只出现一次

    resolveIntent(successResponse);
    await pending;
    await flushPromises();
    expect(w.find('[data-testid="aiq-loading-skeleton"]').exists()).toBe(false);
    expect(w.text()).toContain('这是答案正文'); // 阳性对照: 加载确实结束并出了答案
  });

  it('阴性对照: 餐饮租户加载中仍是转圈, 不出骨架', async () => {
    executeIntentMock.mockImplementation(() => new Promise(() => undefined));
    const w = await mountPage('RESTAURANT');
    const vm = w.vm as unknown as Vm;
    vm.inputQuery = '今天营业额';
    void vm.handleSendMessage();
    await flushPromises();
    expect(w.find('[data-testid="aiq-loading-skeleton"]').exists()).toBe(false);
    expect(w.find('.loading-indicator').exists()).toBe(true);
  });

  it('样式源文本: 高 14 / 圆角 5 / 1.5s 闪烁; 减少动态效果时静止', () => {
    const style = AIQUERY_SRC.slice(AIQUERY_SRC.indexOf('<style lang="scss" scoped>'));
    const bar = cssBlock(cssBlock(style, '.chat-history.ai-std'), '.skeleton-bar');
    expect(bar).toContain('height: 14px;');
    expect(bar).toContain('border-radius: 5px;');
    expect(bar).toMatch(/animation:\s*aiq-skeleton-shimmer 1\.5s/);
    const reduced = cssBlock(style, '@media (prefers-reduced-motion: reduce)');
    expect(reduced).toMatch(/\.chat-history\.ai-std \.skeleton-bar\s*\{\s*animation:\s*none;/);
  });
});

describe('W2: 超时和失败说「没有执行任何写操作」', () => {
  async function startStream(domain: 'FACTORY' | 'RESTAURANT') {
    executeIntentMock.mockResolvedValue({ status: 'NOT_RECOGNIZED', intentRecognized: false, message: '' });
    const w = await mountPage(domain);
    (w.vm as unknown as Vm).selectedUploadId = 1;
    await flushPromises();
    await send(w, '分析一下销售');
    expect(chatAnalysisStreamMock).toHaveBeenCalledTimes(1); // 阳性对照: 真的走到了流式路径
    const callbacks = (chatAnalysisStreamMock.mock.calls[0] as unknown[])[1] as {
      onStatus: (s: string) => void;
      onError: (e: string) => Promise<void>;
    };
    return { w, callbacks };
  }

  it('60 秒无首包: 超时文案含「没有执行任何写操作」, 保留「重新提问」按钮', async () => {
    const { w, callbacks } = await startStream('FACTORY');
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    callbacks.onStatus('正在分析');
    expect(w.find('[data-testid="aiq-timeout"]').exists()).toBe(false); // 对照: 超时前没有
    vi.advanceTimersByTime(60_000);
    await flushPromises();

    const box = w.find('[data-testid="aiq-timeout"]');
    expect(box.exists()).toBe(true);
    expect(box.text()).toContain('没有执行任何写操作');
    const retry = box.findAll('button').find((b) => b.text().includes('重新提问'));
    expect(retry).toBeDefined();
    expect(retry!.attributes('data-size')).toBe('default');
  });

  it('阴性对照: 餐饮租户超时文案保持旧句, 不带这句话', async () => {
    const { w, callbacks } = await startStream('RESTAURANT');
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    callbacks.onStatus('正在分析');
    vi.advanceTimersByTime(60_000);
    await flushPromises();
    const area = w.find('.sse-retry-area');
    expect(area.exists()).toBe(true);
    expect(area.text()).toContain('连接超时，服务响应较慢。');
    expect(area.text()).not.toContain('没有执行任何写操作');
  });

  it('流式报错 (500): 最终失败文案含「没有执行任何写操作」', async () => {
    const { w, callbacks } = await startStream('FACTORY');
    await callbacks.onError('500 Internal Server Error');
    await flushPromises();
    const bodies = w.findAll('[data-testid="aiq-assistant-body"]');
    const last = bodies[bodies.length - 1].text();
    expect(last).toContain('分析服务暂时不可用'); // 对照: 原有的人话还在
    expect(last).toContain('没有执行任何写操作');
  });

  it('Java 返回 FAILED: 文案含这句话; 返回 SUCCESS 的正常答案不带 (阴性对照)', async () => {
    executeIntentMock.mockResolvedValue({ status: 'FAILED', intentRecognized: true, message: '暂时查不了' });
    const w = await mountPage('FACTORY');
    await send(w, '查库存');
    let bodies = w.findAll('[data-testid="aiq-assistant-body"]');
    expect(bodies[bodies.length - 1].text()).toContain('暂时查不了');
    expect(bodies[bodies.length - 1].text()).toContain('没有执行任何写操作');

    executeIntentMock.mockResolvedValue(successResponse);
    await send(w, '再查一次');
    bodies = w.findAll('[data-testid="aiq-assistant-body"]');
    expect(bodies[bodies.length - 1].text()).toContain('这是答案正文');
    expect(bodies[bodies.length - 1].text()).not.toContain('没有执行任何写操作');
  });

  it('NL2SQL 失败 (抛错 / success=false) 文案含这句话', async () => {
    const w = await mountPage('FACTORY');
    nl2sqlMock.mockRejectedValueOnce(new Error('boom'));
    const vm = w.vm as unknown as Vm;
    vm.nl2sqlMode = true;
    vm.selectedUploadId = 1;
    await flushPromises();
    await send(w, 'q1');
    let bodies = w.findAll('[data-testid="aiq-assistant-body"]');
    expect(bodies[bodies.length - 1].text()).toContain('没有执行任何写操作');

    nl2sqlMock.mockResolvedValueOnce({ success: false, message: '无法生成 SQL' });
    await send(w, 'q2');
    bodies = w.findAll('[data-testid="aiq-assistant-body"]');
    expect(bodies[bodies.length - 1].text()).toContain('无法生成 SQL');
    expect(bodies[bodies.length - 1].text()).toContain('没有执行任何写操作');
  });
});

describe('V3 / G1: 表头与表格列', () => {
  const headers = (w: VueWrapper) => w.findAll('[data-testid="aiq-table"] th.col');

  it('已知列显示中文, 中文列原样, 未知英文列保留并照原样显示 (列数不变)', async () => {
    const w = await mountPage('FACTORY');
    await askSql(w, SQL_RESULT);
    const cols = headers(w);
    // 阴性: 一列都没少 (原始结果有 3 个字段)
    expect(cols).toHaveLength(Object.keys(SQL_RESULT.result[0]).length);
    expect(cols.map((c) => c.attributes('data-prop'))).toEqual(['revenue', '产品', 'weird_col_x']);
    expect(cols.map((c) => c.text())).toEqual(['营业收入', '产品', 'weird_col_x']);
  });

  it('后端 fields 给了中文 label 就用它 (对照: 没给的列仍照原样)', async () => {
    const w = await mountPage('FACTORY');
    await askSql(w, { ...SQL_RESULT, fields: [{ name: 'weird_col_x', label: '神秘列' }] });
    expect(headers(w).map((c) => c.text())).toEqual(['营业收入', '产品', '神秘列']);
    expect(headers(w)).toHaveLength(3);
  });

  it('G1: ≤ 8 列不设固定 min-width; > 8 列维持 100 (对照)', async () => {
    const mk = (n: number) => {
      const row: Record<string, unknown> = {};
      for (let i = 0; i < n; i++) row[`c${i}`] = i;
      return { ...SQL_RESULT, result: [row] };
    };
    const w8 = await mountPage('FACTORY');
    await askSql(w8, mk(8));
    expect(headers(w8)).toHaveLength(8);
    expect(headers(w8).every((c) => c.attributes('data-min-width') === undefined)).toBe(true);

    const w9 = await mountPage('FACTORY');
    await askSql(w9, mk(9));
    expect(headers(w9)).toHaveLength(9);
    expect(headers(w9).every((c) => c.attributes('data-min-width') === '100')).toBe(true);
  });
});

describe('N2: 悬停复制', () => {
  it('助手和用户消息都有「复制」, 点了写剪贴板并提示; 加载中没有 (对照); 餐饮没有 (对照)', async () => {
    executeIntentMock.mockResolvedValue(successResponse);
    const w = await mountPage('FACTORY');
    await send(w, '查一下今天的库存');

    const copies = w.findAll('[data-testid="aiq-copy"]');
    // 欢迎语 + 用户消息 + 答案
    expect(copies.length).toBe(3);
    expect(copies.every((c) => c.attributes('data-size') === 'default')).toBe(true);

    await copies[copies.length - 1].trigger('click');
    await flushPromises();
    expect(h.clipboardWrite).toHaveBeenCalledTimes(1);
    expect(h.clipboardWrite).toHaveBeenCalledWith('这是答案正文');
    expect(h.messageSuccess).toHaveBeenCalledTimes(1);

    await copies[1].trigger('click');
    await flushPromises();
    expect(h.clipboardWrite).toHaveBeenLastCalledWith('查一下今天的库存');

    const rw = await mountPage('RESTAURANT');
    expect(rw.findAll('[data-testid="aiq-copy"]')).toHaveLength(0);
  });

  it('剪贴板写失败: 提示失败, 不提示成功', async () => {
    h.clipboardWrite = vi.fn(async () => { throw new Error('denied'); });
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: h.clipboardWrite }, configurable: true });
    const w = await mountPage('FACTORY');
    await w.findAll('[data-testid="aiq-copy"]')[0].trigger('click');
    await flushPromises();
    expect(h.messageError).toHaveBeenCalledTimes(1);
    expect(h.messageSuccess).not.toHaveBeenCalled();
  });

  it('样式源文本: 复制按钮默认透明, hover / focus-within 时显示, 高度 ≥ 32', () => {
    const style = AIQUERY_SRC.slice(AIQUERY_SRC.indexOf('<style lang="scss" scoped>'));
    const std = cssBlock(style, '.chat-history.ai-std');
    const actions = cssBlock(std, '.message-actions');
    expect(actions).toContain('opacity: 0;');
    expect(actions).toContain('min-height: 32px;');
    expect(std).toMatch(/&:hover \.message-actions,\s*&:focus-within \.message-actions\s*\{\s*opacity: 1;/);
  });
});

describe('AiOperationCard 仍由 AIQuery 挂载, 确认接口不会被 AIQuery 自己触发 (H2)', () => {
  it('操作 tab 返回 PREVIEW: 卡片渲染, 在用户点确认之前 confirmIntentAction 0 次', async () => {
    const confirmMod = await import('@/api/smartbi/intent-chat');
    const confirmSpy = confirmMod.confirmIntentAction as unknown as ReturnType<typeof vi.fn>;
    confirmSpy.mockClear();
    executeIntentMock.mockResolvedValue({
      status: 'PREVIEW',
      intentCode: 'MATERIAL_INBOUND',
      intentName: '原料入库',
      message: '',
      confirmableAction: {
        confirmToken: 't', commandDigest: 'd', expiresAt: '2026-07-24T00:05:00Z', expiresInSeconds: 300,
        previewData: { quantity: 200 },
      },
    });
    const w = await mountPage('FACTORY');
    await send(w, '给五花肉入库 200kg');
    expect(w.text()).toContain('原料入库 — 操作预览'); // 对照: 卡片真的渲染了
    expect(confirmSpy).toHaveBeenCalledTimes(0);
  });
});
