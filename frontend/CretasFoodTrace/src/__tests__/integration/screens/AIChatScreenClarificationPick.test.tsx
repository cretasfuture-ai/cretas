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
  useAuthStore: Object.assign(
    (selector?: (state: any) => unknown) => {
      const state = { user: mockUser, getUserRole: () => mockRole };
      return selector ? selector(state) : state;
    },
    { getState: () => ({ user: mockUser, getUserRole: () => mockRole }) },
  ),
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
jest.mock('../../../components/common/VoiceMicButton', () => ({
  VoiceMicButton: ({ onTranscript, ...props }: any) => {
    const { Text } = require('react-native');
    return <Text testID="voice-mic" onPress={() => onTranscript?.('迟到语音')} {...props}>VOICE</Text>;
  },
}));
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
    expect(mockExecuteIntentStream.mock.calls[1][0]).toBe('重新描述');
    const options = mockExecuteIntentStream.mock.calls[1][4];
    expect(options.intentCode).toBeUndefined();
    expect(options.context.clarificationSelection).toBeUndefined();
    expect(options.context.recentTurns).toHaveLength(2);
    expect(mockExecuteIntentStream.mock.calls[1]).toHaveLength(6);
    expect(mockExecuteIntentStream.mock.calls[1][5]).toBeInstanceOf(AbortSignal);
  });

  it('切换账号后忽略旧 SSE 的迟到 session、result 和 complete 回调', async () => {
    let oldCallbacks: any;
    let oldSignal: AbortSignal;
    let resolveOldStream!: () => void;
    const oldStream = new Promise<void>((resolve) => { resolveOldStream = resolve; });
    mockExecuteIntentStream.mockReset();
    mockExecuteIntentStream.mockImplementation(async (_message: string, callbacks: any, _factory: string, _entity: unknown, _options: unknown, signal: AbortSignal) => {
      oldCallbacks = callbacks;
      oldSignal = signal;
      await oldStream;
    });

    const screen = render(<AIChatScreen />);
    await send(screen, '旧账号问题');
    expect(oldCallbacks).toBeTruthy();

    mockUser = factoryUser('F006');
    mockUser.id = 8;
    await act(async () => {
      screen.rerender(<AIChatScreen />);
    });
    expect(oldSignal.aborted).toBe(true);
    expect(screen.queryByText('旧账号问题')).toBeNull();

    // ABA: returning to the original actor must still reject callbacks from generation 0.
    mockUser = factoryUser('F006');
    await act(async () => { screen.rerender(<AIChatScreen />); });

    await act(async () => {
      oldCallbacks.onSessionId?.('old-account-session');
      oldCallbacks.onResult?.({
        status: 'COMPLETED',
        message: '旧账号回答，不应出现',
        intentCode: 'OLD_ACCOUNT_RESULT',
      });
      oldCallbacks.onComplete?.({ status: 'COMPLETED', sessionId: 'old-account-session' });
    });

    expect(screen.queryByText('旧账号回答，不应出现')).toBeNull();
    resolveOldStream();
  });

  it('切换账号后清除语音预览并取消旧 timer', async () => {
    jest.useFakeTimers();
    try {
      const screen = render(<AIChatScreen />);
      fireEvent.press(screen.getByTestId('voice-mic'));
      expect(screen.getByText('迟到语音')).toBeTruthy();

      mockUser = factoryUser('F006');
      mockUser.id = 8;
      await act(async () => { screen.rerender(<AIChatScreen />); });
      await act(async () => { jest.runOnlyPendingTimers(); });

      expect(screen.queryByText('迟到语音')).toBeNull();
      expect(mockExecuteIntentStream).not.toHaveBeenCalled();
    } finally {
      jest.useRealTimers();
    }
  });

  it('同一用户同一角色切换工厂后首帧清除旧消息、输入和语音预览', async () => {
    jest.useFakeTimers();
    try {
      mockExecuteIntentStream.mockReset();
      mockExecuteIntentStream.mockImplementation(async (_message: string, callbacks: any) => {
        callbacks.onResult?.({ status: 'COMPLETED', message: 'F006旧消息', intentCode: 'OLD_FACTORY_RESULT' });
        callbacks.onComplete?.({ status: 'COMPLETED', cacheHit: false, totalLatencyMs: 1 });
      });

      const screen = render(<AIChatScreen />);
      await send(screen, 'F006问题');
      await waitFor(() => expect(screen.getByText('F006旧消息')).toBeTruthy());

      fireEvent.changeText(screen.getByTestId('ai-chat-input'), 'F006未发送输入');
      fireEvent.press(screen.getByTestId('voice-mic'));
      expect(screen.getByText('迟到语音')).toBeTruthy();

      mockUser = factoryUser('F007');
      await act(async () => { screen.rerender(<AIChatScreen />); });

      expect(screen.queryByText('F006旧消息')).toBeNull();
      expect(screen.queryByText('迟到语音')).toBeNull();
      expect(screen.queryByTestId('ai-chat-input')).toBeNull();
      fireEvent.press(screen.getByTestId('keyboard-toggle-btn'));
      expect(screen.getByTestId('ai-chat-input').props.value).toBe('');
      await act(async () => { jest.runOnlyPendingTimers(); });
      expect(mockExecuteIntentStream).toHaveBeenCalledTimes(1);
    } finally {
      jest.useRealTimers();
    }
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

  it('第一句没有历史且仍传入取消信号', async () => {
    const screen = render(<AIChatScreen />);
    await send(screen, '今天包装线的任务');
    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(1));
    expect(mockExecuteIntentStream.mock.calls[0]).toHaveLength(6);
    expect(mockExecuteIntentStream.mock.calls[0][4]).toBeUndefined();
    expect(mockExecuteIntentStream.mock.calls[0][5]).toBeInstanceOf(AbortSignal);
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

describe('AIChatScreen 追问确认 (lastAction)', () => {
  const START_LAST_ACTION = {
    intentCode: 'WORK_PROCESS_TASK_START', intentName: '开始工序', params: { taskId: 12 }, at: 1,
  };

  beforeEach(() => {
    mockUser = factoryUser('F006');
    mockRole = 'factory_super_admin';
    mockExecuteIntentStream.mockReset();
    // 1) 开始工序成功, 带回 lastAction
    mockExecuteIntentStream.mockImplementationOnce(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({
        status: 'SUCCESS', message: '已开始「包装」工序', intentCode: 'WORK_PROCESS_TASK_START',
        metadata: { toolName: 'work_process_task_start', lastAction: START_LAST_ACTION },
      });
      callbacks.onComplete?.({ status: 'SUCCESS', cacheHit: false, totalLatencyMs: 500 });
    });
    // 2) 「好了」→ 服务端只回一个确认按钮
    mockExecuteIntentStream.mockImplementationOnce(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({
        status: 'NEED_CLARIFICATION', message: '您是要「完成工序」吗？（任务 12）',
        intentCode: 'WORK_PROCESS_TASK_COMPLETE', metadata: { followUpRule: 'wp-done-after-start' },
        suggestedActions: [{
          actionCode: 'SELECT_INTENT', actionName: '完成工序（任务 12）',
          parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE', taskId: 12, followUpRule: 'wp-done-after-start' },
        }],
      });
      callbacks.onComplete?.({ status: 'NEED_CLARIFICATION', cacheHit: false, totalLatencyMs: 300 });
    });
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'SUCCESS', message: '已完成', intentCode: 'WORK_PROCESS_TASK_COMPLETE' });
      callbacks.onComplete?.({ status: 'SUCCESS', cacheHit: false, totalLatencyMs: 300 });
    });
  });

  it('成功结果里的 lastAction 随下一句带回去; 第一句不带', async () => {
    const screen = render(<AIChatScreen />);
    await send(screen, '开始包装工序');
    await waitFor(() => expect(screen.getAllByText(/已开始「包装」工序/).length).toBeGreaterThan(0));
    await send(screen, '好了');

    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(2));
    expect(mockExecuteIntentStream.mock.calls[0][4]).toBeUndefined();
    const [text, , , , options] = mockExecuteIntentStream.mock.calls[1];
    expect(text).toBe('好了');
    expect(options.intentCode).toBeUndefined();
    expect(options.context.lastAction).toEqual(START_LAST_ACTION);
    expect(options.context.recentTurns).toHaveLength(2);
  });

  it('点确认按钮: 原话 + 意图码 + 按钮上的其余参数一起带回', async () => {
    const screen = render(<AIChatScreen />);
    await send(screen, '开始包装工序');
    await waitFor(() => expect(screen.getAllByText(/已开始「包装」工序/).length).toBeGreaterThan(0));
    await send(screen, '好了');
    await waitFor(() => expect(screen.getByTestId('suggested-action-0')).toBeTruthy());
    expect(screen.queryByTestId('suggested-action-1')).toBeNull();
    await act(async () => { fireEvent.press(screen.getByTestId('suggested-action-0')); });

    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(3));
    const [text, , , , options] = mockExecuteIntentStream.mock.calls[2];
    expect(text).toBe('好了');
    expect(options.intentCode).toBe('WORK_PROCESS_TASK_COMPLETE');
    expect(options.context).toMatchObject({
      taskId: 12,
      followUpRule: 'wp-done-after-start',
      clarificationSelection: true,
      clarificationCandidates: ['WORK_PROCESS_TASK_COMPLETE'],
      lastAction: START_LAST_ACTION,
    });
    expect(options.context.intentCode).toBeUndefined();
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
