// @ts-nocheck
/** B1-B9 专项：真实挂载 AIChatScreen，只有 SSE / HTTP / native 边界被桩。 */
import React from 'react';
import { StyleSheet } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';

let mockUser: any;
let mockRole = 'warehouse_worker';
let mockRouteParams: any;
let mockExecuteIntentStream: jest.Mock;
let navigationOptions: any;

function lookup(obj: unknown, dotted: string): string | undefined {
  const value = dotted.split('.').reduce<unknown>((acc, part) => (
    acc && typeof acc === 'object' ? (acc as Record<string, unknown>)[part] : undefined
  ), obj);
  return typeof value === 'string' ? value : undefined;
}

jest.mock('../../../store/authStore', () => ({
  useAuthStore: Object.assign((selector?: (state: any) => unknown) => {
    const state = { user: mockUser, getUserRole: () => mockRole, tokens: { accessToken: 'test' }, getFactoryId: () => 'F006' };
    return selector ? selector(state) : state;
  }, { getState: () => ({ user: mockUser, getUserRole: () => mockRole }) }),
}));
jest.mock('../../../services/api/aiApiClient', () => ({
  aiApiClient: { executeIntentStream: (...args: unknown[]) => mockExecuteIntentStream(...args) },
}));
jest.mock('../../../services/api/intentConfigApiClient', () => ({
  intentConfigApiClient: { previewIntent: jest.fn(), confirmIntent: jest.fn() },
  buildConfirmationProof: jest.fn((value: unknown) => value),
}));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ setOptions: (value: any) => { navigationOptions = value; }, dispatch: jest.fn(), getParent: () => null }),
  useRoute: () => ({ params: mockRouteParams }),
  CommonActions: { navigate: jest.fn() },
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => lookup(zhHome, key) ?? key, i18n: { language: 'zh-CN' } }) }));
jest.mock('i18next', () => ({ __esModule: true, default: { language: 'zh-CN' } }));
jest.mock('react-native-safe-area-context', () => ({ SafeAreaView: ({ children, ...props }: any) => { const { View } = require('react-native'); return <View {...props}>{children}</View>; } }));
jest.mock('../../../services/ai', () => ({ aiService: {}, detectAnalysisMode: () => ({ mode: 'quick' }) }));
jest.mock('../../../components/ai/AIModeIndicator', () => ({ AIModeIndicator: () => null }));
jest.mock('../../../components/common/VoiceMicButton', () => ({ VoiceMicButton: () => null }));
jest.mock('../../../components/ai/RestaurantGrossMarginRunCard', () => ({ RestaurantGrossMarginRunCard: () => null }));
jest.mock('../../../services/audio/feedbackSounds', () => ({ feedbackSounds: { onWriteSuccess: jest.fn(), onNeedMoreInfo: jest.fn(), onError: jest.fn() } }));
jest.mock('../../../services/api/apiClient', () => ({ apiClient: { post: jest.fn(async () => ({ success: true, data: { success: true } })) } }));

const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

function factoryUser() {
  return { id: 31, userType: 'factory', factoryUser: { factoryId: 'F006', factoryType: 'FACTORY', role: mockRole, permissions: [] } };
}

async function send(view: ReturnType<typeof render>, text: string) {
  if (!view.queryByTestId('ai-chat-input')) fireEvent.press(view.getByTestId('keyboard-toggle-btn'));
  fireEvent.changeText(view.getByTestId('ai-chat-input'), text);
  await act(async () => { fireEvent.press(view.getByTestId('ai-chat-send-btn')); });
}

beforeEach(() => {
  mockUser = factoryUser();
  mockRouteParams = undefined;
  navigationOptions = undefined;
  mockExecuteIntentStream = jest.fn(async (_input: string, callbacks: any) => {
    callbacks.onResult?.({ status: 'COMPLETED', message: '已完成', intentCode: 'MATERIAL_BATCH_QUERY', intentCategory: 'QUERY', metadata: {} });
    callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 100 });
  });
});
afterEach(() => { jest.useRealTimers(); });

describe('AIChatScreen B1/B9 real mount', () => {
  it('B1 renders only a real follow-up question and sends the complete question', async () => {
    mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '库存已查完', intentCode: 'MATERIAL_BATCH_QUERY', followUps: [{ label: '看低库存', question: '请查看当前低库存物料' }] });
      callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 100 });
    });
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    expect(view.getByTestId('ai-chat-followup-0')).toBeTruthy();
    expect(view.queryByText('请查看当前低库存物料')).toBeNull();
    fireEvent.press(view.getByTestId('ai-chat-followup-0'));
    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(2));
    expect(mockExecuteIntentStream.mock.calls[1][0]).toBe('请查看当前低库存物料');
  });

  it('B1/B9 has no fake follow-up chips and hides empty cards after a message', async () => {
    const view = render(<AIChatScreen />);
    expect(view.queryByTestId('ai-chat-followups')).toBeNull();
    expect(view.queryByTestId('ai-chat-welcome-next-step')).toBeTruthy();
    expect(StyleSheet.flatten(view.getByTestId('quick-action-card-0').props.style).height).toBeLessThanOrEqual(112);
    await send(view, '查库存');
    expect(view.queryByTestId('ai-chat-followups')).toBeNull();
    expect(view.queryByTestId('quick-action-card-grid')).toBeNull();
  });
});

describe('AIChatScreen B2/B5 real mount', () => {
  it('B2 shows slow processing then a human timeout and leaves the stream retryable', async () => {
    jest.useFakeTimers();
    mockExecuteIntentStream.mockImplementationOnce((_input: string, callbacks: any) => {
      callbacks.onStart?.('cache_hit SQL trace');
      return new Promise(() => {});
    });
    const view = render(<AIChatScreen />);
    await send(view, '查询生产情况');
    expect(view.getByText('正在理解你的话…')).toBeTruthy();
    expect(view.queryByText('cache_hit SQL trace')).toBeNull();
    await act(async () => { jest.advanceTimersByTime(3000); });
    expect(view.getByText('正在处理')).toBeTruthy();
    await act(async () => { jest.advanceTimersByTime(30_000); });
    expect(view.getByText(/这次没处理完/)).toBeTruthy();
    expect(view.getByText(/没有执行任何写操作/)).toBeTruthy();
    expect(view.getByTestId('ai-chat-error-banner')).toBeTruthy();
    expect(view.getByTestId('ai-chat-retry')).toBeTruthy();
    const { intentConfigApiClient } = require('../../../services/api/intentConfigApiClient');
    expect(intentConfigApiClient.previewIntent).not.toHaveBeenCalled();
    expect(intentConfigApiClient.confirmIntent).not.toHaveBeenCalled();
    mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '重试成功', intentCode: 'MATERIAL_BATCH_QUERY' });
      callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 100 });
    });
    await send(view, '再查一次');
    await waitFor(() => expect(view.getByText('重试成功')).toBeTruthy());
    jest.useRealTimers();
  });

  it('B5 installs a new conversation action and resets a normal conversation', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    expect(navigationOptions.headerRight).toBeDefined();
    const header = render(navigationOptions.headerRight());
    fireEvent.press(header.getByTestId('ai-chat-new-conversation'));
    expect(view.getByTestId('ai-chat-welcome-next-step')).toBeTruthy();
  });
});

describe('AIChatScreen B3/B4/B8 real result components', () => {
  it('B3 distinguishes missing values from zero and hides unknown English keys in the screen', async () => {
    mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '库存已查完', intentCode: 'MATERIAL_BATCH_QUERY', resultData: {
        content: [{ batchNumber: 'MT-1', currentQuantity: null, unexplainedInternalFlag: 7 }, { batchNumber: 'MT-2', currentQuantity: 0 }, { batchNumber: '000123', currentQuantity: '0.800000' }],
      } });
      callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 100 });
    });
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    expect(view.getByText('暂无数据')).toBeTruthy();
    expect(view.getByText('0')).toBeTruthy();
    expect(view.getByText('0.8')).toBeTruthy();
    expect(view.getByText('000123')).toBeTruthy();
    expect(view.queryByText('Unexplained Internal Flag')).toBeNull();
    expect(view.queryByText('unexplainedInternalFlag')).toBeNull();
  });

  it('B4 renders readable text, vertical cells and usable follow-up and feedback targets', async () => {
    mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '库存已查完', intentCode: 'MATERIAL_BATCH_QUERY',
        followUps: [{ label: '查低库存', question: '查询所有低库存物料' }],
        resultData: { content: [{ batchNumber: 'MT-1', currentQuantity: 0.8 }] } });
      callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 100 });
    });
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    const bodyStyle = StyleSheet.flatten(view.getByText('库存已查完').props.style);
    expect(bodyStyle.fontSize).toBeGreaterThanOrEqual(16);
    expect(StyleSheet.flatten(view.getByText('0.8').props.style).fontSize).toBeGreaterThanOrEqual(13);
    const chip = StyleSheet.flatten(view.getByTestId('ai-chat-followup-0').props.style);
    expect(chip.minHeight).toBeGreaterThanOrEqual(40);
    expect(chip.maxWidth).toBeLessThanOrEqual(220);
    // 性质是「触摸区至少 44」: 标准 F6 之后 👍/👎 写成 minWidth/minHeight (原来是固定 48×48), 两种写法都认
    const thumbUp = StyleSheet.flatten(view.getByLabelText('这个回答有帮助').props.style);
    expect(thumbUp.height ?? thumbUp.minHeight).toBeGreaterThanOrEqual(44);
    expect(thumbUp.width ?? thumbUp.minWidth).toBeGreaterThanOrEqual(44);
    fireEvent.press(view.getByLabelText('这个回答没有帮助'));
    expect(StyleSheet.flatten(view.getByLabelText('反馈原因：不准确').props.style).minHeight).toBeGreaterThanOrEqual(44);
  });

  it('B8 offers feedback for a completed non-knowledge answer but not an error', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    expect(view.getByLabelText('这个回答有帮助')).toBeTruthy();
    view.unmount();
    mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => {
      callbacks.onError?.(new Error('technical transport error'));
    });
    const failed = render(<AIChatScreen />);
    await send(failed, '再查库存');
    expect(failed.queryByLabelText('这个回答有帮助')).toBeNull();
    expect(failed.queryByText('technical transport error')).toBeNull();
    expect(failed.getByTestId('ai-chat-error-banner')).toBeTruthy();
  });
});
