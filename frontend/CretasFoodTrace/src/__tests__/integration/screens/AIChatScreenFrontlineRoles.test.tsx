// @ts-nocheck
/**
 * AIChatScreen 在一线角色下（Line B 2026-10-05, spec docs/specs/rn-frontline-ai-entry-2026-10-05.md）。
 *
 * 入口只是把人送进来；这里守「进来之后能用」：
 *   F3 欢迎区：一线角色有自己的卡组（owner 2026-10-05 追加）⇒「我可以帮您：」+ 卡组
 *        ↔ 对照：没有卡组的角色（dispatcher）不显示悬空的「我可以帮您：」，改显示点麦克风说话
 *   F6 澄清按钮 / 追问确认按钮 / 写操作确认卡在四个一线角色下都渲染（客户端没有按角色藏东西）
 *        ↔ 对照：带 entityType 进来时场景过滤**确实会**滤掉不在白名单的建议 —— 证明过滤器活着，
 *          而一线入口不带 entityType，所以不受它影响
 *   权限：请求里不带角色（角色由服务端从 JWT 取），客户端无从绕过
 */
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';

let mockUser: any;
let mockRole: string | null;
let mockRouteParams: Record<string, unknown> | undefined;
const mockExecuteIntentStream = jest.fn();
const mockPreviewIntent = jest.fn();

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
jest.mock('../../../services/api/intentConfigApiClient', () => {
  const actual = jest.requireActual('../../../services/api/intentConfigApiClient');
  return {
    ...actual,
    intentConfigApiClient: {
      previewIntent: (...args: unknown[]) => mockPreviewIntent(...args),
      confirmIntent: jest.fn(),
    },
  };
});
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ dispatch: jest.fn(), getParent: () => null }),
  useRoute: () => ({ params: mockRouteParams }),
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
jest.mock('../../../components/ai/AIModeIndicator', () => ({ AIModeIndicator: () => null }));
jest.mock('../../../components/ai/FeedbackWidget', () => ({ FeedbackWidget: () => null }));
jest.mock('../../../components/ai/RichContentRenderer', () => ({
  RichContentRenderer: () => null,
  detectRichData: () => null,
}));
jest.mock('../../../components/ai/RestaurantGrossMarginRunCard', () => ({
  RestaurantGrossMarginRunCard: () => null,
}));
jest.mock('../../../components/common/VoiceMicButton', () => ({
  VoiceMicButton: (props: any) => {
    const { Text } = require('react-native');
    return <Text testID="voice-mic" {...props}>VOICE</Text>;
  },
}));
jest.mock('../../../services/audio/feedbackSounds', () => ({
  feedbackSounds: { onWriteSuccess: jest.fn(), onNeedMoreInfo: jest.fn(), onError: jest.fn() },
}));

const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

const FRONTLINE_ROLES = ['operator', 'yield_operator', 'warehouse_worker', 'quality_inspector'];

function factoryUser(role: string) {
  return {
    id: 31,
    userType: 'factory',
    factoryUser: { factoryId: 'F006', factoryType: 'FACTORY', role, permissions: [] },
  };
}

function flattenText(node: any): string {
  if (node == null) return '';
  if (typeof node === 'string') return node;
  if (Array.isArray(node)) return node.map(flattenText).join('');
  return flattenText(node.children);
}

async function send(screen: ReturnType<typeof render>, text: string) {
  if (!screen.queryByTestId('ai-chat-input')) fireEvent.press(screen.getByTestId('keyboard-toggle-btn'));
  fireEvent.changeText(screen.getByTestId('ai-chat-input'), text);
  await act(async () => { fireEvent.press(screen.getByTestId('ai-chat-send-btn')); });
}

function asRole(role: string) {
  mockUser = factoryUser(role);
  mockRole = role;
}

beforeEach(() => {
  mockRouteParams = undefined;
  mockExecuteIntentStream.mockReset();
  mockPreviewIntent.mockReset();
});

describe('AIChatScreen 一线角色 · 欢迎区 (F3)', () => {
  it('参数化的那一维真的不同', () => {
    expect(new Set(FRONTLINE_ROLES).size).toBe(4);
  });

  it.each(FRONTLINE_ROLES)('%s: 有自己的卡组 ⇒「我可以帮您：」+ 四张卡, 麦克风仍在', (role) => {
    asRole(role);
    const screen = render(<AIChatScreen />);
    expect(screen.getByText(zhHome.aiChat.canHelp)).toBeTruthy();
    expect(screen.getByTestId('quick-action-card-grid')).toBeTruthy();
    expect(screen.getByTestId('quick-action-card-3')).toBeTruthy();
    expect(screen.queryByTestId('quick-action-card-4')).toBeNull();
    expect(screen.queryByText(zhHome.aiChat.voiceHint)).toBeNull();
    // 语音优先：麦克风就在
    expect(screen.getByTestId('voice-mic')).toBeTruthy();
  });

  it('对照: 没有卡组的角色 (dispatcher) 不出现悬空的「我可以帮您：」, 改为告诉他点麦克风说话', () => {
    asRole('dispatcher');
    const screen = render(<AIChatScreen />);
    expect(flattenText(screen.getByTestId('ai-chat-welcome-next-step').props.children)).toBe(zhHome.aiChat.voiceHint);
    expect(screen.queryByText(zhHome.aiChat.canHelp)).toBeNull();
    expect(screen.queryByTestId('quick-action-card-grid')).toBeNull();
  });
});

const CLARIFY_RESULT = {
  status: 'NEED_CLARIFICATION',
  message: '我不太确定您想执行什么操作。',
  suggestedActions: [
    { actionCode: 'SELECT_INTENT', actionName: '完成工序', parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE' } },
    { actionCode: 'SELECT_INTENT', actionName: '查询原材料库存', parameters: { intentCode: 'MATERIAL_BATCH_QUERY' } },
    { actionCode: 'REPHRASE', actionName: '重新描述' },
  ],
};

describe('AIChatScreen 一线角色 · 澄清 / 追问确认 / 写确认卡 (F6)', () => {
  it.each(FRONTLINE_ROLES)('%s: 澄清按钮全部渲染, 点选带着意图码回服务端; 请求里不带角色', async (role) => {
    asRole(role);
    mockExecuteIntentStream.mockImplementationOnce(async (_m: string, callbacks: any) => {
      callbacks.onResult?.(CLARIFY_RESULT);
      callbacks.onComplete?.({ status: 'NEED_CLARIFICATION', cacheHit: false, totalLatencyMs: 1 });
    });
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '好的', intentCode: 'WORK_PROCESS_TASK_COMPLETE' });
      callbacks.onComplete?.({ status: 'COMPLETED', cacheHit: false, totalLatencyMs: 1 });
    });
    const screen = render(<AIChatScreen />);
    await send(screen, '包装好了');

    await waitFor(() => expect(screen.getByTestId('suggested-action-2')).toBeTruthy());
    expect(screen.getByTestId('suggested-action-0')).toBeTruthy();
    expect(screen.getByTestId('suggested-action-1')).toBeTruthy();

    await act(async () => { fireEvent.press(screen.getByTestId('suggested-action-0')); });
    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(2));
    const [text, , factoryId, entityType, options] = mockExecuteIntentStream.mock.calls[1];
    expect(text).toBe('包装好了');
    expect(factoryId).toBe('F006');
    expect(entityType).toBeUndefined();
    expect(options.intentCode).toBe('WORK_PROCESS_TASK_COMPLETE');
    // 角色只在服务端由 JWT 决定：客户端请求的任何参数里都没有角色码
    expect(JSON.stringify(mockExecuteIntentStream.mock.calls.map((c) => [c[0], c[2], c[3], c[4]]))).not.toContain(role);
  });

  it.each(FRONTLINE_ROLES)('%s: 追问确认按钮（服务端只回一个）渲染并能点', async (role) => {
    asRole(role);
    mockExecuteIntentStream.mockImplementationOnce(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({
        status: 'NEED_CLARIFICATION', message: '您是要「完成工序」吗？（任务 12）',
        intentCode: 'WORK_PROCESS_TASK_COMPLETE',
        suggestedActions: [{
          actionCode: 'SELECT_INTENT', actionName: '完成工序（任务 12）',
          parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE', taskId: 12, followUpRule: 'wp-done-after-start' },
        }],
      });
      callbacks.onComplete?.({ status: 'NEED_CLARIFICATION', cacheHit: false, totalLatencyMs: 1 });
    });
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'SUCCESS', message: '已完成', intentCode: 'WORK_PROCESS_TASK_COMPLETE' });
      callbacks.onComplete?.({ status: 'SUCCESS', cacheHit: false, totalLatencyMs: 1 });
    });
    const screen = render(<AIChatScreen />);
    await send(screen, '好了');
    await waitFor(() => expect(screen.getByTestId('suggested-action-0')).toBeTruthy());
    expect(flattenText(screen.getByTestId('suggested-action-0'))).toContain('完成工序（任务 12）');
    await act(async () => { fireEvent.press(screen.getByTestId('suggested-action-0')); });
    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(2));
    expect(mockExecuteIntentStream.mock.calls[1][4].context).toMatchObject({ taskId: 12, followUpRule: 'wp-done-after-start' });
  });

  it.each(FRONTLINE_ROLES)('%s: 写操作被拦 ⇒ 出确认卡（客户端不按角色藏）', async (role) => {
    asRole(role);
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onSessionId?.('sess-frontline');
      callbacks.onResult?.({
        intentRecognized: true, intentCode: 'WORK_PROCESS_TASK_START', status: 'WRITE_CONFIRM_REQUIRED',
        message: '该操作会写入/修改数据，执行前需要确认。', requiresApproval: true, aiMode: 'WRITE',
      });
      callbacks.onComplete?.({ status: 'WRITE_CONFIRM_REQUIRED', cacheHit: false, totalLatencyMs: 1 });
    });
    mockPreviewIntent.mockResolvedValue({
      intentRecognized: true, intentCode: 'WORK_PROCESS_TASK_START', intentName: '开始工序',
      status: 'PREVIEW', message: '{"success":true}', aiMode: 'WRITE',
      confirmableAction: {
        confirmToken: 'tok', commandDigest: 'b'.repeat(64), expiresAt: '2099-01-01T00:05:00Z',
        expiresInSeconds: 300, description: '确认后将实际执行「开始工序」',
        previewData: { success: true, data: { batchNumber: 'PB-001', productName: '卤猪蹄', status: 'PREVIEW', canDo: true } },
        currentValues: {}, newValues: {}, impactSummary: '确认后立即生效。', actionType: 'WRITE', riskLevel: 'MEDIUM',
      },
    });
    const screen = render(<AIChatScreen />);
    await send(screen, '开始包装工序');
    await waitFor(() => expect(screen.getByTestId('ai-confirm-card')).toBeTruthy());
    expect(screen.getByTestId('ai-confirm-execute')).toBeTruthy();
    expect(mockPreviewIntent).toHaveBeenCalledTimes(1);
  });
});

describe('AIChatScreen 场景过滤 (F6 对照: 过滤器活着, 一线入口不触发它)', () => {
  const MIXED_RESULT = {
    status: 'NEED_CLARIFICATION',
    message: '您想做哪一个？',
    suggestedActions: [
      { actionCode: 'SELECT_INTENT', actionName: '完成工序', parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE' } },
      { actionCode: 'NOT_IN_ANY_SCENE_WHITELIST', actionName: '场景外的建议' },
    ],
  };
  function installMixed() {
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onResult?.(MIXED_RESULT);
      callbacks.onComplete?.({ status: 'NEED_CLARIFICATION', cacheHit: false, totalLatencyMs: 1 });
    });
  }

  it('一线入口路径（不带 entityType）: 两个建议都在', async () => {
    asRole('operator');
    installMixed();
    const screen = render(<AIChatScreen />);
    await send(screen, '好了');
    await waitFor(() => expect(screen.getByTestId('suggested-action-1')).toBeTruthy());
    expect(flattenText(screen.getByTestId('suggested-action-1'))).toContain('场景外的建议');
  });

  it('对照: 带 entityType=WORK_REPORT 进来, 场景外的建议被滤掉, 澄清按钮保留', async () => {
    asRole('operator');
    mockRouteParams = { entityType: 'WORK_REPORT' };
    installMixed();
    const screen = render(<AIChatScreen />);
    await send(screen, '好了');
    await waitFor(() => expect(screen.getByTestId('suggested-action-0')).toBeTruthy());
    expect(flattenText(screen.getByTestId('suggested-action-0'))).toContain('完成工序');
    expect(screen.queryByTestId('suggested-action-1')).toBeNull();
  });
});
