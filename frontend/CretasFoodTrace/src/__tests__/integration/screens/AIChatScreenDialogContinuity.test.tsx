// @ts-nocheck
/**
 * AIChatScreen 对话连续性 (prod e2e 2026-10-06, F006 operator)。
 *
 * 跑真的 AIChatScreen; 流式客户端换成一个<b>按真客户端契约回调</b>的桩 (start/result 里的 sessionId →
 * onSessionId, 再 onResult —— 契约本身由 unit/api/AIChatSessionBindingContract.test.ts 用真客户端钉住;
 * 这里不能 mock react-native-sse: jest.config 的宽泛 'react-native' 映射让它和 safe-area-context
 * 抢同一个模块槽, 最后注册的赢)。/preview /confirm 两个网络端也是桩。
 *
 *   缺参追问: 结果里的 sessionId (参数收集会话) 下一句原样带回   ↔ 对照: 结果不带就沿用开始事件的会话
 *   「或点击下方选项」只在真有选项时出现                         ↔ 对照: 有选项时照旧出现
 *   确认卡执行成功 → 记 lastAction, 下一句带回                    ↔ 对照: 取消 / 演示环境未执行 都不记
 *   确认卡的预览请求带上流式给的 previewParams                     ↔ 对照: 没给就不带 context
 */
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';

let mockUser: any;
let mockRole: string | null;
const mockPreviewIntent = jest.fn();
const mockConfirmIntent = jest.fn();
/** 每次 executeIntentStream 调用一条: 请求体 (与真客户端拼 body 的规则一致) + 回调 + 结束开关。 */
const mockStreams: any[] = [];
jest.mock('../../../services/api/aiApiClient', () => ({
  aiApiClient: {
    executeIntentStream: (userInput: string, callbacks: any, _factoryId: string, entityType?: string, options?: any) =>
      new Promise<void>((resolve) => {
        mockStreams.push({
          body: {
            userInput,
            ...(options?.sessionId && { sessionId: options.sessionId }),
            ...(entityType && { entityType }),
            ...(options?.intentCode && { intentCode: options.intentCode }),
            ...(options?.context && { context: options.context }),
          },
          callbacks,
          resolve,
        });
      }),
  },
}));

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
jest.mock('../../../services/api/intentConfigApiClient', () => {
  const actual = jest.requireActual('../../../services/api/intentConfigApiClient');
  return {
    ...actual,
    intentConfigApiClient: {
      previewIntent: (...args: unknown[]) => mockPreviewIntent(...args),
      confirmIntent: (...args: unknown[]) => mockConfirmIntent(...args),
    },
  };
});
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ dispatch: jest.fn(), getParent: () => null }),
  useRoute: () => ({ params: undefined }),
  CommonActions: { navigate: jest.fn((name, params) => ({ type: 'NAVIGATE', name, params })) },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => lookup(zhHome, key) ?? key, i18n: { language: 'zh-CN' } }),
}));
jest.mock('i18next', () => ({ __esModule: true, default: { language: 'zh-CN' } }));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: ({ children, ...props }: any) => {
    const { View } = require('react-native');
    return <View {...props}>{children}</View>;
  },
}));
jest.mock('../../../services/ai', () => ({ aiService: {}, detectAnalysisMode: () => ({ mode: 'quick' }) }));
jest.mock('../../../components/ai/AIModeIndicator', () => ({ AIModeIndicator: () => null }));
jest.mock('../../../components/ai/FeedbackWidget', () => ({ FeedbackWidget: () => null }));
jest.mock('../../../components/ai/RichContentRenderer', () => ({
  RichContentRenderer: () => null,
  detectRichData: () => null,
}));
jest.mock('../../../components/ai/RestaurantGrossMarginRunCard', () => ({ RestaurantGrossMarginRunCard: () => null }));
jest.mock('../../../components/common/VoiceMicButton', () => ({ VoiceMicButton: () => null }));
jest.mock('../../../services/audio/feedbackSounds', () => ({
  feedbackSounds: { onWriteSuccess: jest.fn(), onNeedMoreInfo: jest.fn(), onError: jest.fn() },
}));

const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

const DIGEST = 'c'.repeat(64);

async function send(screen: ReturnType<typeof render>, text: string) {
  const before = mockStreams.length;
  if (!screen.queryByTestId('ai-chat-input')) fireEvent.press(screen.getByTestId('keyboard-toggle-btn'));
  fireEvent.changeText(screen.getByTestId('ai-chat-input'), text);
  await act(async () => { fireEvent.press(screen.getByTestId('ai-chat-send-btn')); });
  await waitFor(() => expect(mockStreams.length).toBe(before + 1));
  return mockStreams[before];
}

function body(stream: any) {
  return stream.body;
}

/**
 * 按真客户端的事件 → 回调映射回一轮 (aiApiClient.executeIntentStream):
 * start: onSessionId(start.sessionId) + onStart; result: onSessionId(result.sessionId) 再 onResult; complete。
 */
async function reply(stream: any, result: Record<string, unknown>, startSessionId = 'mem-1') {
  await act(async () => {
    const cb = stream.callbacks;
    cb.onSessionId?.(startSessionId);
    cb.onStart?.('开始处理...');
    if (typeof result.sessionId === 'string' && result.sessionId) cb.onSessionId?.(result.sessionId);
    cb.onResult?.(result);
    cb.onComplete?.({ status: result.status, cacheHit: false, totalLatencyMs: 5 });
    stream.resolve();
  });
}

const SLOT_ASK = {
  intentRecognized: true,
  intentCode: 'WORK_PROCESS_TASK_COMPLETE',
  intentName: '完成工序',
  status: 'NEED_MORE_INFO',
  message: '好的，我来帮您执行「完成工序」。',
  clarificationQuestions: ['请提供工序任务ID (Long)'],
  sessionId: 'slot-1',
};

function startBlocked(previewParams?: Record<string, unknown>) {
  return {
    intentRecognized: true,
    intentCode: 'WORK_PROCESS_TASK_START',
    status: 'WRITE_CONFIRM_REQUIRED',
    message: '该操作会写入/修改数据，执行前需要确认。',
    ...(previewParams ? { metadata: { previewParams } } : {}),
  };
}

function previewOk() {
  return {
    intentRecognized: true,
    intentCode: 'WORK_PROCESS_TASK_START',
    intentName: '开始工序',
    status: 'PREVIEW',
    confirmableAction: {
      confirmToken: 'tok-1',
      commandDigest: DIGEST,
      expiresAt: '2099-01-01T00:05:00Z',
      expiresInSeconds: 300,
      description: '确认后将实际执行「开始工序」',
      previewData: { success: true, data: { taskId: 1827, canDo: true } },
      currentValues: {},
      newValues: {},
      actionType: 'UPDATE',
      riskLevel: 'LOW',
    },
  };
}

const START_LAST_ACTION = {
  intentCode: 'WORK_PROCESS_TASK_START',
  intentName: '开始工序',
  toolName: 'work_process_task_start',
  params: { taskId: 1827 },
  at: Date.now(),
};

async function startAndConfirm(screen: ReturnType<typeof render>, confirmResponse: Record<string, unknown>) {
  mockPreviewIntent.mockResolvedValue(previewOk());
  mockConfirmIntent.mockResolvedValue(confirmResponse);
  const first = await send(screen, '开始1827号工序任务');
  await reply(first, startBlocked({ taskId: 1827 }));
  await waitFor(() => expect(screen.getByTestId('ai-confirm-execute')).toBeTruthy());
  await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-execute')); });
  await waitFor(() => expect(screen.queryByTestId('ai-confirm-execute')).toBeNull());
}

beforeEach(() => {
  mockStreams.length = 0;
  mockUser = { id: 1686, userType: 'factory', factoryUser: { factoryId: 'F006', factoryType: 'FACTORY', role: 'operator', permissions: [] } };
  mockRole = 'operator';
  mockPreviewIntent.mockReset();
  mockConfirmIntent.mockReset();
});

describe('缺参追问: 会话 id 带回 + 没选项不说「点击下方选项」', () => {
  it('结果里的参数收集会话 id, 下一句「1827」原样带回去', async () => {
    const screen = render(<AIChatScreen />);
    const first = await send(screen, '做完了，产量0');
    await reply(first, SLOT_ASK);

    const second = await send(screen, '1827');

    expect(body(second)).toEqual(expect.objectContaining({ userInput: '1827', sessionId: 'slot-1' }));
  });

  it('对照: 结果不带会话 id 时, 下一句带的是开始事件里的会话', async () => {
    const screen = render(<AIChatScreen />);
    const first = await send(screen, '做完了，产量0');
    const { sessionId, ...withoutSession } = SLOT_ASK;
    await reply(first, withoutSession, 'mem-7');

    const second = await send(screen, '1827');

    expect(body(second).sessionId).toBe('mem-7');
  });

  it('没有选项时不说「或点击下方选项」', async () => {
    const screen = render(<AIChatScreen />);
    const first = await send(screen, '做完了，产量0');
    await reply(first, SLOT_ASK);

    await waitFor(() => expect(screen.getAllByText(/请提供工序任务ID/).length).toBeGreaterThan(0));
    expect(screen.queryAllByText(/点击下方选项/)).toHaveLength(0);
    expect(screen.getAllByText(/请直接告诉我。/).length).toBeGreaterThan(0);
  });

  it('对照: 真有选项 (产品列表) 时照旧提示可以点', async () => {
    const screen = render(<AIChatScreen />);
    const first = await send(screen, '创建生产计划');
    await reply(first, {
      ...SLOT_ASK,
      clarificationQuestions: ['请问是哪个产品？说名称就行'],
      suggestedActions: [{ actionCode: 'SELECT_PARAM_productId_P1', actionName: '牛腩排' }],
    });

    await waitFor(() => expect(screen.getAllByText(/点击下方选项/).length).toBeGreaterThan(0));
    expect(screen.getAllByText('牛腩排').length).toBeGreaterThan(0);
  });
});

describe('确认卡: 预览带上流式参数, 成功后记 lastAction', () => {
  it('预览请求带上 metadata.previewParams 作为 context', async () => {
    const screen = render(<AIChatScreen />);
    mockPreviewIntent.mockResolvedValue(previewOk());
    const first = await send(screen, '开始1827号工序任务');
    await reply(first, startBlocked({ taskId: 1827 }));

    await waitFor(() => expect(mockPreviewIntent).toHaveBeenCalledTimes(1));
    expect(mockPreviewIntent.mock.calls[0][0]).toEqual({
      userInput: '开始1827号工序任务',
      intentCode: 'WORK_PROCESS_TASK_START',
      sessionId: 'mem-1',
      context: { taskId: 1827 },
    });
  });

  it('对照: 流式没给 previewParams 时预览不带 context', async () => {
    const screen = render(<AIChatScreen />);
    mockPreviewIntent.mockResolvedValue(previewOk());
    const first = await send(screen, '开始1827号工序任务');
    await reply(first, startBlocked());

    await waitFor(() => expect(mockPreviewIntent).toHaveBeenCalledTimes(1));
    expect(mockPreviewIntent.mock.calls[0][0]).not.toHaveProperty('context');
  });

  it('确认执行成功 → 下一句「做完了，产量0」带着「开始 1827」这一步', async () => {
    const screen = render(<AIChatScreen />);
    await startAndConfirm(screen, {
      status: 'SUCCESS',
      intentCode: 'WORK_PROCESS_TASK_START',
      message: '工序任务 1827 已开始',
      metadata: { toolName: 'work_process_task_start', lastAction: START_LAST_ACTION },
    });

    const next = await send(screen, '做完了，产量0');

    expect(body(next).context?.lastAction).toEqual(START_LAST_ACTION);
  });

  it('对照: 取消确认卡, 下一句不带 lastAction', async () => {
    const screen = render(<AIChatScreen />);
    mockPreviewIntent.mockResolvedValue(previewOk());
    const first = await send(screen, '开始1827号工序任务');
    await reply(first, startBlocked({ taskId: 1827 }));
    await waitFor(() => expect(screen.getByTestId('ai-confirm-cancel')).toBeTruthy());
    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-cancel')); });

    const next = await send(screen, '做完了，产量0');

    expect(body(next).context?.lastAction).toBeUndefined();
    expect(mockConfirmIntent).not.toHaveBeenCalled();
  });

  it('对照: 演示环境没执行 (DEMO_WRITE_BLOCKED) 时即使响应里有 lastAction 也不记', async () => {
    const screen = render(<AIChatScreen />);
    await startAndConfirm(screen, {
      status: 'DEMO_WRITE_BLOCKED',
      intentCode: 'WORK_PROCESS_TASK_START',
      message: '演示环境不执行真实写入。',
      metadata: { lastAction: START_LAST_ACTION },
    });

    const next = await send(screen, '做完了，产量0');

    expect(mockConfirmIntent).toHaveBeenCalledTimes(1);
    expect(body(next).context?.lastAction).toBeUndefined();
  });
});
