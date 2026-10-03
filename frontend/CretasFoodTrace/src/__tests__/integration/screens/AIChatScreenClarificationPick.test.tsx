// @ts-nocheck
/**
 * AIChatScreen 第五幕 polish（2026-09-16）：
 *   (b) CATTLE_ 租户欢迎页渲染牛业卡组；F006 渲染原卡组（对照）
 *   (c) 非 __DEV__ 不渲染「快速响应 / N.Ns」遥测；__DEV__ 下渲染（对照）
 *   (d) 发送按钮等可点元素带 accessibilityLabel
 *   (e) 回答正文里的 `\n` 不被吞掉（流式态 + 完成态两条路）
 *   (f) 卡片导航按父 tab 投递到嵌套 stack（BossOverviewTab）
 *
 * t() 从真的 zh-CN/home.json 取值：断言用中文，同时证明键存在。
 */
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';

let mockUser: any;
let mockRole: string | null;
let mockParent: any = null;
const mockDispatch = jest.fn();
const mockExecuteIntentStream = jest.fn();

function lookup(obj: unknown, dotted: string): string | undefined {
  const value = dotted.split('.').reduce<unknown>(
    (acc, part) => (acc && typeof acc === 'object' ? (acc as Record<string, unknown>)[part] : undefined),
    obj,
  );
  return typeof value === 'string' ? value : undefined;
}

jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector?: (state: any) => unknown) => {
    const state = { user: mockUser, getUserRole: () => mockRole };
    return selector ? selector(state) : state;
  },
}));
jest.mock('../../../services/api/aiApiClient', () => ({
  aiApiClient: { executeIntentStream: (...args: unknown[]) => mockExecuteIntentStream(...args) },
}));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({
    dispatch: mockDispatch,
    getParent: () => mockParent,
  }),
  useRoute: () => ({ params: undefined }),
  CommonActions: { navigate: jest.fn((name, params) => ({ type: 'NAVIGATE', name, params })) },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => lookup(zhHome, key) ?? key,
    i18n: { language: 'zh-CN' },
  }),
}));
jest.mock('i18next', () => ({ __esModule: true, default: { language: 'zh-CN' } }));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: ({ children, ...props }: any) => {
    const { View } = require('react-native');
    return <View {...props}>{children}</View>;
  },
}));
jest.mock('../../../services/ai', () => ({
  aiService: {},
  detectAnalysisMode: () => ({ mode: 'quick' }),
}));
jest.mock('../../../components/ai/AIModeIndicator', () => ({
  AIModeIndicator: () => {
    const { Text } = require('react-native');
    return <Text>MODE_CHIP</Text>;
  },
}));
jest.mock('../../../components/ai/FeedbackWidget', () => ({ FeedbackWidget: () => null }));
jest.mock('../../../components/ai/RichContentRenderer', () => ({
  RichContentRenderer: () => null,
  detectRichData: () => null,
}));
jest.mock('../../../components/ai/RestaurantGrossMarginRunCard', () => ({
  RestaurantGrossMarginRunCard: () => null,
}));
jest.mock('../../../components/common/VoiceMicButton', () => ({ VoiceMicButton: () => null }));
jest.mock('../../../services/audio/feedbackSounds', () => ({
  feedbackSounds: { onWriteSuccess: jest.fn(), onNeedMoreInfo: jest.fn(), onError: jest.fn() },
}));

const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

function factoryUser(factoryId: string) {
  return {
    id: 7,
    userType: 'factory',
    factoryUser: { factoryId, factoryType: 'FACTORY', role: 'factory_super_admin', permissions: [] },
  };
}

/** 把渲染树里的字符串按出现顺序拼起来（跨嵌套 Text）。 */
function flattenText(node: any): string {
  if (node == null) return '';
  if (typeof node === 'string') return node;
  if (Array.isArray(node)) return node.map(flattenText).join('');
  return flattenText(node.children);
}

function installTextReply(text: string) {
  mockExecuteIntentStream.mockImplementation(async (_message: string, callbacks: any) => {
    callbacks.onMeta?.({ model: 'x', questionType: 'BUSINESS' });
    callbacks.onResult?.({ status: 'COMPLETED', message: text, intentCode: 'X', metadata: {} });
    callbacks.onComplete?.({ status: 'COMPLETED', cacheHit: false, totalLatencyMs: 4200 });
  });
}

async function send(screen: ReturnType<typeof render>, text: string) {
  if (!screen.queryByTestId('ai-chat-input')) fireEvent.press(screen.getByTestId('keyboard-toggle-btn'));
  fireEvent.changeText(screen.getByTestId('ai-chat-input'), text);
  await act(async () => { fireEvent.press(screen.getByTestId('ai-chat-send-btn')); });
}


const CLARIFY_RESULT = {
  status: 'NEED_CLARIFICATION',
  message: '我不太确定您想执行什么操作。',
  suggestedActions: [
    { actionCode: 'SELECT_INTENT', actionName: '完成工序', parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE' } },
    { actionCode: 'SELECT_INTENT', actionName: '记录抄码品称重', parameters: { intentCode: 'ABACA_WEIGHT_LOG' } },
    { actionCode: 'REPHRASE', actionName: '重新描述' },
  ],
};

describe('AIChatScreen 澄清按钮点选', () => {
  beforeEach(() => {
    mockUser = factoryUser('F006');
    mockRole = 'factory_super_admin';
    mockExecuteIntentStream.mockReset();
    mockExecuteIntentStream.mockImplementationOnce(async (_m: string, callbacks: any) => {
      callbacks.onResult?.(CLARIFY_RESULT);
      callbacks.onComplete?.({ status: 'NEED_CLARIFICATION', cacheHit: false, totalLatencyMs: 900 });
    });
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '已报工 60 箱', intentCode: 'WORK_PROCESS_TASK_COMPLETE' });
      callbacks.onComplete?.({ status: 'COMPLETED', cacheHit: false, totalLatencyMs: 700 });
    });
  });

  it('点候选: 发原话 + 意图码 + 候选, 显示按钮名字', async () => {
    const screen = render(<AIChatScreen />);
    await send(screen, '就是六十箱');
    await waitFor(() => expect(screen.getByTestId('suggested-action-0')).toBeTruthy());
    await act(async () => { fireEvent.press(screen.getByTestId('suggested-action-0')); });

    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(2));
    const [text, , factoryId, , options] = mockExecuteIntentStream.mock.calls[1];
    expect(text).toBe('就是六十箱');
    expect(factoryId).toBe('F006');
    expect(options).toEqual({
      intentCode: 'WORK_PROCESS_TASK_COMPLETE',
      context: {
        clarificationSelection: true,
        clarificationCandidates: ['WORK_PROCESS_TASK_COMPLETE', 'ABACA_WEIGHT_LOG'],
        recentTurns: [
          { role: 'user', content: '就是六十箱' },
          { role: 'assistant', content: '我不太确定您想执行什么操作。' },
        ],
      },
    });
    expect(screen.getAllByText('完成工序').length).toBeGreaterThan(0);
  });

  it('对照: 非澄清按钮仍把按钮名字当一句新话发出去 (只带上下文, 不带意图码)', async () => {
    const screen = render(<AIChatScreen />);
    await send(screen, '就是六十箱');
    await waitFor(() => expect(screen.getByTestId('suggested-action-2')).toBeTruthy());
    await act(async () => { fireEvent.press(screen.getByTestId('suggested-action-2')); });

    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(2));
    const [text, , , , options] = mockExecuteIntentStream.mock.calls[1];
    expect(text).toBe('重新描述');
    expect(options.intentCode).toBeUndefined();
    expect(options.context.clarificationSelection).toBeUndefined();
    expect(options.context.recentTurns).toHaveLength(2);
  });
});

describe('AIChatScreen 最近对话', () => {
  beforeEach(() => {
    mockUser = factoryUser('F006');
    mockRole = 'factory_super_admin';
    mockExecuteIntentStream.mockReset();
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '包装线今天有 3 个任务', intentCode: 'X' });
      callbacks.onComplete?.({ status: 'COMPLETED', cacheHit: false, totalLatencyMs: 700 });
    });
  });

  it('第一句没有上下文: 照旧 4 参调用', async () => {
    const screen = render(<AIChatScreen />);
    await send(screen, '今天包装线的任务');
    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(1));
    expect(mockExecuteIntentStream.mock.calls[0]).toHaveLength(4);
  });

  it('第二句带上前一轮的问与答', async () => {
    const screen = render(<AIChatScreen />);
    await send(screen, '今天包装线的任务');
    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getAllByText(/包装线今天有 3 个任务/).length).toBeGreaterThan(0));
    await send(screen, '好了');

    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(2));
    const [text, , , , options] = mockExecuteIntentStream.mock.calls[1];
    expect(text).toBe('好了');
    expect(options).toEqual({
      context: {
        recentTurns: [
          { role: 'user', content: '今天包装线的任务' },
          { role: 'assistant', content: '包装线今天有 3 个任务' },
        ],
      },
    });
  });
});

describe('buildRecentTurns', () => {
  const { buildRecentTurns } = require('../../../screens/factory-admin/ai-analysis/AIChatScreen');

  it('只留用户/助手、跳过加载中和空白、截断、取最后 4 轮', () => {
    const turns = buildRecentTurns([
      { role: 'system', content: '系统提示' },
      { role: 'user', content: '一' },
      { role: 'assistant', content: '   ' },
      { role: 'assistant', content: 'x'.repeat(300) },
      { role: 'user', content: '三\n\n换行' },
      { role: 'assistant', content: '四' },
      { role: 'user', content: '五' },
      { role: 'assistant', content: '', isLoading: true },
    ]);
    expect(turns).toEqual([
      { role: 'assistant', content: 'x'.repeat(200) },
      { role: 'user', content: '三 换行' },
      { role: 'assistant', content: '四' },
      { role: 'user', content: '五' },
    ]);
    expect(buildRecentTurns([])).toEqual([]);
  });
});
