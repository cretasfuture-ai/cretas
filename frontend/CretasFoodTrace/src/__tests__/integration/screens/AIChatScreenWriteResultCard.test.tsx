// @ts-nocheck
/**
 * AIChatScreen 写操作成功后的结果卡 (2026-10-07)。
 *
 * prod 实测: 领用原料确认执行成功, 结果卡显示 Remaining Quantity / Consumed Quantity / Material Type Id /
 * Batch Id / AVAILABLE。本测试走真实链路: 确认卡「确认执行」→ POST /confirm 回 resultData →
 * AIChatScreen 的 detectRichData → 真实 RichContentRenderer (⛔ 不 mock 它; 另一份
 * AIChatScreenWriteConfirm 把它 mock 成 null, 所以够不着这张卡)。
 *
 * 对照: 同一屏幕里已有中文标签的字段 (批次号) 照常显示; 内部编号 / 英文标签 / 英文状态码不在。
 */
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';

let mockUser: any;
let mockRole: string | null;
const mockExecuteIntentStream = jest.fn();
const mockPreviewIntent = jest.fn();
const mockConfirmIntent = jest.fn();

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
jest.mock('../../../components/ai/RestaurantGrossMarginRunCard', () => ({
  RestaurantGrossMarginRunCard: () => null,
}));
jest.mock('../../../components/common/VoiceMicButton', () => ({ VoiceMicButton: () => null }));
jest.mock('../../../services/audio/feedbackSounds', () => ({
  feedbackSounds: {
    onWriteSuccess: jest.fn(),
    onNeedMoreInfo: jest.fn(),
    onError: jest.fn(),
  },
}));

const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

const TOKEN = 'opaque-confirm-token-xyz';

// MaterialBatchUseTool.doExecute 的返回 (键名一字不差)
const BATCH_USE_RESULT = {
  batchId: 'b3f1c2d4-1111-4222-8333-444455556666',
  batchNumber: 'MT-20260829-9687',
  materialTypeId: 'a9f1c2d4-1111-4222-8333-444455556666',
  consumedQuantity: 0.1,
  remainingQuantity: 0.9,
  unit: 'kg',
  status: 'AVAILABLE',
  productionPlanId: null,
};

async function send(screen: ReturnType<typeof render>, text: string) {
  if (!screen.queryByTestId('ai-chat-input')) fireEvent.press(screen.getByTestId('keyboard-toggle-btn'));
  fireEvent.changeText(screen.getByTestId('ai-chat-input'), text);
  await act(async () => { fireEvent.press(screen.getByTestId('ai-chat-send-btn')); });
}

beforeEach(() => {
  mockUser = {
    id: 7,
    userType: 'factory',
    factoryUser: { factoryId: 'F006', factoryType: 'FACTORY', role: 'warehouse_manager', permissions: [] },
  };
  mockRole = 'warehouse_manager';
  mockExecuteIntentStream.mockReset();
  mockPreviewIntent.mockReset();
  mockConfirmIntent.mockReset();
});

describe('AIChatScreen 写操作成功后的结果卡', () => {
  it('领用成功: 结果卡是中文标签 + 数量带单位 + 中文状态, 没有内部编号和英文标签', async () => {
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onSessionId?.('sess-use-1');
      callbacks.onResult?.({
        intentRecognized: true, intentCode: 'MATERIAL_BATCH_USE', status: 'WRITE_CONFIRM_REQUIRED',
        message: '该操作会写入/修改数据，执行前需要确认。', aiMode: 'WRITE',
      });
      callbacks.onComplete?.({ status: 'WRITE_CONFIRM_REQUIRED', cacheHit: false, totalLatencyMs: 300 });
    });
    mockPreviewIntent.mockResolvedValue({
      intentCode: 'MATERIAL_BATCH_USE', intentName: '使用原料', status: 'PREVIEW',
      confirmableAction: {
        confirmToken: TOKEN, commandDigest: 'a'.repeat(64), expiresAt: '2099-01-01T00:05:00Z',
        expiresInSeconds: 300, description: '确认后将实际执行「使用原料」',
        previewData: { success: true, data: { batchNumber: 'MT-20260829-9687', canDo: true } },
        currentValues: {}, newValues: {}, impactSummary: '此操作将写入数据。', actionType: 'WRITE', riskLevel: 'MEDIUM',
      },
    });
    mockConfirmIntent.mockResolvedValue({
      status: 'COMPLETED',
      intentCode: 'MATERIAL_BATCH_USE',
      message: '成功消耗原料批次 MT-20260829-9687，消耗数量 0.1 kg，剩余 0.9 kg',
      resultData: { ...BATCH_USE_RESULT },
    });

    const screen = render(<AIChatScreen />);
    await send(screen, '领用 MT-20260829-9687 0.1 公斤');
    await waitFor(() => expect(screen.getByTestId('ai-confirm-execute')).toBeTruthy());
    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-execute')); });

    await waitFor(() => expect(screen.getByTestId('ai-detail-card')).toBeTruthy());
    expect(mockConfirmIntent).toHaveBeenCalledTimes(1);

    // 正向: 中文标签 + 带单位的数 + 中文状态
    expect(screen.getByText('剩余数量')).toBeTruthy();
    expect(screen.getByText('0.9 kg')).toBeTruthy();
    expect(screen.getByText('已领用数量')).toBeTruthy();
    expect(screen.getByText('0.1 kg')).toBeTruthy();
    expect(screen.getByText('批次号')).toBeTruthy();
    expect(screen.getByText('MT-20260829-9687')).toBeTruthy();
    expect(screen.getByText('可用')).toBeTruthy();

    // 阴性: 英文标签 / 内部编号 / 英文状态码
    for (const leaked of ['Remaining Quantity', 'Consumed Quantity', 'Batch Id', 'Material Type Id', 'AVAILABLE']) {
      expect(screen.queryByText(leaked)).toBeNull();
    }
    expect(screen.queryByText(/b3f1c2d4/)).toBeNull();
    expect(screen.queryByText(/a9f1c2d4/)).toBeNull();
  });
});
