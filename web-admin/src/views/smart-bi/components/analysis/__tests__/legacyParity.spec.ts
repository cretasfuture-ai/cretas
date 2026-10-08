/**
 * 餐饮 / 旧版式阴性对照 (AI 对话标准 §3 末段: 共用组件只能「新增可选 prop、默认值保持旧样子」)。
 *
 * fixtures/legacy-render.json 是在【改动之前的 origin/main】上用同一份输入跑出来的 `wrapper.html()`:
 *   - ChatBubble 不传 variant (用户 / 助手 / 带告警 / 带错误 / 带 followups 插槽)
 *   - ChatTypingIndicator
 *   - RestaurantChatPanel (成功 + 失败两轮, 真实 ChatBubble)
 *   - AnalysisChatPanel 在餐饮租户下 (成功 + 降级 + 失败三轮, 加载中)
 * 本测试在当前树上再渲染一遍, 与 fixture 逐字符比对。
 * 重新采集 (只在确认旧版式本来就该变时): CAPTURE_PARITY=1 npx vitest run <本文件>
 */
import { flushPromises, mount } from '@vue/test-utils';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  askIntent: vi.fn(),
  auth: { factoryId: 'DEMO_REST', businessDomain: 'RESTAURANT' } as { factoryId: string; businessDomain: string },
}));

vi.mock('@/api/smartbi/restaurant-synthesis', () => ({
  askRestaurantIntent: (...args: unknown[]) => mocks.askIntent(...args),
  sendRestaurantAnswerFeedback: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => mocks.auth,
}));
vi.mock('../../chat/RestaurantChartBlock.vue', () => ({
  default: { name: 'RestaurantChartBlock', props: ['chart'], template: '<div class="stub-chart">{{ chart.title }}</div>' },
}));
vi.mock('../../chat/GrossMarginDeclineRun.vue', () => ({
  default: { name: 'GrossMarginDeclineRun', template: '<div class="stub-run" />' },
}));

import AnalysisChatPanel from '../AnalysisChatPanel.vue';
import ChatBubble from '../../chat/ChatBubble.vue';
import ChatTypingIndicator from '../../chat/ChatTypingIndicator.vue';
import RestaurantChatPanel from '../../chat/RestaurantChatPanel.vue';

const here = dirname(fileURLToPath(import.meta.url));
const FIXTURE = join(here, '__fixtures__', 'legacy-render.json');

const norm = (html: string) => html.replace(/el-id-\d+-\d+/g, 'el-id-X');

const aiTurn = {
  id: 'a1',
  role: 'ai' as const,
  content: '### 结论\n- 撤单率 **0.55%**\n\n普通一句话。',
  timestamp: 1,
  charts: [{ type: 'bar', title: '门店营收', option: { series: [] } }],
  alerts: [
    { type: 'x', level: 'high', title: '采购价异常', detail: '某供应商涨价 30%' },
    { type: 'y', level: 'medium', title: '成本率上升', detail: '近 7 天上升' },
  ],
};

function renderBubbles(): Record<string, string> {
  const out: Record<string, string> = {};
  out.bubbleUser = norm(mount(ChatBubble, { props: { turn: { id: 'u1', role: 'user', content: '哪家店业绩最好', timestamp: 1 } } }).html());
  out.bubbleAi = norm(mount(ChatBubble, { props: { turn: aiTurn } }).html());
  out.bubbleError = norm(mount(ChatBubble, {
    props: { turn: { id: 'e1', role: 'ai', content: '**请求失败**\n\nboom', timestamp: 1, error: 'boom' } },
  }).html());
  out.bubbleFollowups = norm(mount(ChatBubble, {
    props: { turn: aiTurn },
    slots: { followups: '<div class="slot-followups">追问</div>', sections: '<div class="slot-sections">段落</div>' },
  }).html());
  out.typing = norm(mount(ChatTypingIndicator).html());
  return out;
}

async function renderRestaurantPanel(): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  mocks.askIntent.mockReset();
  mocks.askIntent.mockResolvedValueOnce({
    success: true,
    answer: '请先选择时间范围',
    charts: [],
    alerts: [],
    followUpActions: [{ label: '本月', question: '本月哪家店业绩最好' }],
  });
  mocks.askIntent.mockResolvedValueOnce({ success: false, answer: '', charts: [], alerts: [], error: 'down' });
  const w = mount(RestaurantChatPanel, {
    props: { factoryId: 'DEMO_REST', agentRunEligible: false, startDate: '2026-07-01', endDate: '2026-07-24' },
  });
  out.restaurantPanelEmpty = norm(w.html());
  const vm = w.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
  await vm.sendMessage('哪家店业绩最好');
  await flushPromises();
  await vm.sendMessage('再问一个');
  await flushPromises();
  out.restaurantPanel = norm(w.html());
  return out;
}

async function renderAnalysisPanelRestaurant(): Promise<Record<string, string>> {
  const out: Record<string, string> = {};
  mocks.askIntent.mockReset();
  mocks.askIntent.mockResolvedValueOnce({
    success: true,
    answer: '你想看哪个时间范围？',
    charts: [],
    alerts: [],
    source: 'restaurant_ops_gold',
    followUpActions: [{ label: '最近30天', question: '最近30天哪家店业绩最好' }],
  });
  mocks.askIntent.mockResolvedValueOnce({ success: true, answer: '系统提示', charts: [], alerts: [], source: 'degraded' });
  mocks.askIntent.mockResolvedValueOnce({ success: false, answer: '', charts: [], alerts: [], error: 'Java intent service unavailable' });
  let release: (v: unknown) => void = () => {};
  mocks.askIntent.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));
  const w = mount(AnalysisChatPanel, {
    props: {
      factoryId: 'DEMO_REST',
      pageTitle: '运营分析',
      contexts: [
        { key: 'void-audit', title: '撤单稽核', dataSummary: '撤单率 0.55%', dataScope: '2026-07', unavailableMetrics: [] },
        { key: 'sales', title: '销售', dataSummary: '营收 10 万', dataScope: '', unavailableMetrics: [] },
      ],
    },
  });
  out.analysisEmpty = norm(w.html());
  const vm = w.vm as unknown as { sendMessage: (t?: string) => Promise<void> };
  await vm.sendMessage('哪家店业绩最好');
  await flushPromises();
  await vm.sendMessage('第二问');
  await flushPromises();
  await vm.sendMessage('第三问');
  await flushPromises();
  out.analysisThreeTurns = norm(w.html());
  const pending = vm.sendMessage('第四问');
  await flushPromises();
  out.analysisLoading = norm(w.html());
  release({ success: true, answer: 'ok', charts: [], alerts: [] });
  await pending;
  await flushPromises();
  return out;
}

async function renderAll(): Promise<Record<string, string>> {
  return {
    ...renderBubbles(),
    ...(await renderRestaurantPanel()),
    ...(await renderAnalysisPanelRestaurant()),
  };
}

describe('legacy / restaurant render parity (fixture taken on origin/main before the change)', () => {
  beforeEach(() => {
    mocks.auth.businessDomain = 'RESTAURANT';
    if (typeof globalThis.crypto?.randomUUID !== 'function') {
      Object.defineProperty(globalThis, 'crypto', {
        value: { randomUUID: () => Math.random().toString(36).slice(2) },
        configurable: true,
      });
    }
  });

  it('capture or compare', async () => {
    const actual = await renderAll();
    if (process.env.CAPTURE_PARITY === '1') {
      writeFileSync(FIXTURE, JSON.stringify(actual, null, 2), { encoding: 'utf-8' });
      console.log('PARITY_CAPTURED', Object.keys(actual).map((k) => `${k}=${actual[k].length}`).join(' '));
      return;
    }
    expect(existsSync(FIXTURE)).toBe(true);
    const expected = JSON.parse(readFileSync(FIXTURE, 'utf-8')) as Record<string, string>;
    expect(Object.keys(actual).sort()).toEqual(Object.keys(expected).sort());
    for (const key of Object.keys(expected)) {
      // 逐键比对, 失败时知道是哪一块漂了
      expect(actual[key], `fixture key ${key}`).toBe(expected[key]);
    }
    // 防止 fixture 是空壳: 旧版式的标志必须真的在里面 (阳性对照)
    expect(expected.bubbleAi).toContain('bubble-avatar');
    expect(expected.analysisThreeTurns).toContain('bubble-avatar');
    expect(expected.analysisThreeTurns).toContain('acp-source-note');
    expect(expected.analysisLoading).toContain('typing-wrap');
    expect(expected.restaurantPanel).toContain('bubble-avatar');
  });
});
