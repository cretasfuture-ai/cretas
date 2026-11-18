/**
 * PR2 (问题 51/52): 咨询页选数据源跳过 Java / 澄清按钮 / 文案清洗 / 页头数字专员。
 * 挂载与 mock 同 AIQuery.stdLayout.spec.ts; 每个「没发生」配一个「另一条件下发生」的对照。
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
  getPurchaseTaskGrantStatus: vi.fn(),
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


const AIQUERY_SRC = readFileSync(resolve(__dirname, '../AIQuery.vue'), 'utf8');

beforeEach(() => {
  setActivePinia(createPinia());
  sessionStorage.clear();
  executeIntentMock.mockReset();
  chatAnalysisStreamMock.mockReset();
  chatAnalysisStreamMock.mockImplementation(() => ({ abort: vi.fn() }));
  chatAnalysisMock.mockReset();
  chatAnalysisMock.mockImplementation(async () => ({ success: false }));
  listFactoryTemplatesMock.mockReset();
  listFactoryTemplatesMock.mockImplementation(async () => ({ success: true, templates: [] }));
});
afterEach(() => { document.body.innerHTML = ''; });

const clarifyResponse = {
  status: 'NEED_CLARIFICATION',
  intentRecognized: true,
  message: '您想看哪一类？',
  suggestedActions: [
    { actionCode: 'SELECT_INTENT', actionName: '各产品销售额占比' },
    { actionCode: 'SELECT_INTENT', actionName: '各客户采购排名' },
    { actionCode: 'OTHER', actionName: '不该出现' },
  ],
  sessionId: 's1',
};

describe('PR2 ①: 路由', () => {
  it('咨询页选了数据源: 不调 Java executeIntent, 直接走 Python 流式', async () => {
    const w = await mountPage('FACTORY');
    (w.vm as unknown as Vm).selectedUploadId = 1;
    await flushPromises();
    await send(w, '各产品的销售额占比是多少？用图表展示');
    expect(executeIntentMock).not.toHaveBeenCalled();
    expect(chatAnalysisStreamMock).toHaveBeenCalledTimes(1);
  });

  it('阴性对照: 没选数据源仍先问 Java 意图层', async () => {
    executeIntentMock.mockResolvedValue({ status: 'SUCCESS', intentRecognized: true, message: 'ok', sessionId: 's' });
    const w = await mountPage('FACTORY');
    await send(w, '查一下今天的库存');
    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(chatAnalysisStreamMock).not.toHaveBeenCalled();
  });
});

describe('PR2 ②: 文案清洗接上', () => {
  it('Java 回「还需要 indicator_code」时, 界面不出英文字段名', async () => {
    executeIntentMock.mockResolvedValue({
      status: 'NEED_MORE_INFO', intentRecognized: true,
      message: '还需要以下信息：indicator_code、indicator_codes', sessionId: 's',
    });
    const w = await mountPage('FACTORY');
    await send(w, '随便问问');
    const bodies = w.findAll('[data-testid="aiq-assistant-body"]');
    const last = bodies[bodies.length - 1].text();
    expect(last).toContain('指标');
    expect(last).not.toContain('indicator_code');
  });
});

describe('PR2 ③: 澄清按钮', () => {
  it('只取 SELECT_INTENT, data-testid=suggested-action-N, 点了按该问题发送, 澄清不出反馈', async () => {
    executeIntentMock.mockResolvedValueOnce(clarifyResponse);
    const w = await mountPage('FACTORY');
    await send(w, '看下占比');
    const btns = w.findAll('[data-testid^="suggested-action-"]');
    expect(btns.map((b) => b.text())).toEqual(['各产品销售额占比', '各客户采购排名']);
    expect(btns[0].attributes('data-testid')).toBe('suggested-action-0');
    expect(w.text()).not.toContain('这个回答有用吗');

    executeIntentMock.mockResolvedValueOnce({ status: 'SUCCESS', intentRecognized: true, message: '好的', sessionId: 's' });
    await btns[1].trigger('click');
    await flushPromises();
    expect(executeIntentMock).toHaveBeenCalledTimes(2);
    expect(executeIntentMock.mock.calls[1][1]).toBeDefined();
    expect(JSON.stringify(executeIntentMock.mock.calls[1])).toContain('各客户采购排名');
  });

  it('阴性对照: 无 SELECT_INTENT 时不出按钮', async () => {
    executeIntentMock.mockResolvedValueOnce({ ...clarifyResponse, suggestedActions: [] });
    const w = await mountPage('FACTORY');
    await send(w, '看下占比');
    expect(w.findAll('[data-testid^="suggested-action-"]')).toHaveLength(0);
  });

  it('样式: 竖排、占满列宽、高度不低于 48px', () => {
    const start = AIQUERY_SRC.indexOf('.message-clarify-actions {');
    expect(start).toBeGreaterThan(-1);
    const block = AIQUERY_SRC.slice(start, start + 700);
    expect(block).toContain('flex-direction: column');
    expect(block).toMatch(/\.message-clarify-actions \{[^]*?width: 100%/);
    expect(block).toMatch(/\.clarify-action-btn \{[^]*?width: 100%/);
    expect(block).toContain('min-height: 48px');
  });
});

describe('PR2 ④: 页头数字专员', () => {
  it('页头有头像和「数字专员 · 数据分析」, 消息里仍没有头像', async () => {
    executeIntentMock.mockResolvedValue({ status: 'SUCCESS', intentRecognized: true, message: 'ok', sessionId: 's' });
    const w = await mountPage('FACTORY');
    await send(w, '查一下今天的库存');
    const agent = w.find('[data-testid="header-agent"]');
    expect(agent.exists()).toBe(true);
    expect(agent.text()).toContain('数字专员 · 数据分析');
    expect(agent.find('img').attributes('src')).toMatch(/^\/digital-employees\/\w+\.png$/);
    expect(w.findAll('.message-avatar')).toHaveLength(0);
    expect(w.find('.chat-history').find('img').exists()).toBe(false);
  });
});
