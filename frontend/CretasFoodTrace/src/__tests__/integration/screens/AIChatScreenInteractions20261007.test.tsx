// @ts-nocheck
/**
 * B5/B6/B7: 真实挂载 AIChatScreen 的交互测试。
 * 只桩 SSE/确认 HTTP、导航、剪贴板与 appAlert 这几个边界；确认卡、富文本、反馈组件走真实实现。
 */
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { Pressable, ScrollView } from 'react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';

let mockUser: any;
let mockRole = 'warehouse_worker';
let mockRouteParams: any;
let mockExecuteIntentStream: jest.Mock;
let mockPreviewIntent: jest.Mock;
let mockConfirmIntent: jest.Mock;
let mockAppAlert: jest.Mock;
let mockSetStringAsync: jest.Mock;
let mockScrollToEnd: jest.Mock;
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
  intentConfigApiClient: {
    previewIntent: (...args: unknown[]) => mockPreviewIntent(...args),
    confirmIntent: (...args: unknown[]) => mockConfirmIntent(...args),
  },
  buildConfirmationProof: jest.fn((value: unknown) => value),
}));
jest.mock('../../../services/api/apiClient', () => ({
  apiClient: { post: jest.fn().mockResolvedValue({ success: true, data: { success: true } }) },
}));
jest.mock('../../../components/ui/AppDialog', () => ({ appAlert: (...args: unknown[]) => mockAppAlert(...args) }));
jest.mock('expo-clipboard', () => ({ setStringAsync: (...args: unknown[]) => mockSetStringAsync(...args) }));
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
jest.mock('../../../services/audio/feedbackSounds', () => ({ feedbackSounds: { onWriteSuccess: jest.fn(), onNeedMoreInfo: jest.fn(), onError: jest.fn(), onNeedMoreInfo: jest.fn() } }));
jest.mock('react-native', () => {
  const React = require('react');
  const C = (name: string) => { const X = ({ children, ...props }: any) => React.createElement(name, props, children); X.displayName = name; return X; };
  const base = {
    View: C('View'), Text: C('Text'), TouchableOpacity: C('TouchableOpacity'), TouchableHighlight: C('TouchableHighlight'),
    TouchableWithoutFeedback: C('TouchableWithoutFeedback'), Pressable: C('Pressable'), TextInput: C('TextInput'), Image: C('Image'),
    KeyboardAvoidingView: C('KeyboardAvoidingView'), ActivityIndicator: C('ActivityIndicator'), SafeAreaView: C('SafeAreaView'),
    Modal: C('Modal'), Switch: C('Switch'), StatusBar: C('StatusBar'), StyleSheet: { create: (x: any) => x, hairlineWidth: 0.5, flatten: (x: any) => x },
    Platform: { OS: 'ios', select: (x: any) => x.ios || x.default }, Dimensions: { get: () => ({ width: 375, height: 812, scale: 2, fontScale: 1 }) },
    Alert: { alert: jest.fn() }, Keyboard: { dismiss: jest.fn(), addListener: jest.fn(() => ({ remove: jest.fn() })) },
    InteractionManager: { runAfterInteractions: (fn: any) => { fn?.(); return { cancel: jest.fn() }; } },
    useWindowDimensions: () => ({ width: 375, height: 812, scale: 2, fontScale: 1 }), useColorScheme: () => 'light',
    PixelRatio: { get: () => 2, getFontScale: () => 1 }, NativeModules: {}, NativeEventEmitter: jest.fn(),
    Animated: { View: C('Animated.View'), Text: C('Animated.Text'), ScrollView: C('Animated.ScrollView') },
  };
  const ScrollView = React.forwardRef((props: any, ref: any) => {
    React.useImperativeHandle(ref, () => ({ scrollToEnd: (...args: unknown[]) => mockScrollToEnd(...args) }), []);
    return React.createElement('ScrollView', props, props.children);
  });
  ScrollView.displayName = 'ScrollView';
  return { ...base, ScrollView };
});

const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

function factoryUser() {
  return { id: 31, userType: 'factory', factoryUser: { factoryId: 'F006', factoryType: 'FACTORY', role: mockRole, permissions: [] } };
}

async function send(view: ReturnType<typeof render>, text: string) {
  if (!view.queryByTestId('ai-chat-input')) fireEvent.press(view.getByTestId('keyboard-toggle-btn'));
  fireEvent.changeText(view.getByTestId('ai-chat-input'), text);
  await act(async () => { fireEvent.press(view.getByTestId('ai-chat-send-btn')); });
}

const BLOCKED_WRITE = {
  intentRecognized: true,
  intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT',
  intentName: '仓管快速入库',
  status: 'WRITE_CONFIRM_REQUIRED',
  message: '该操作会写入/修改数据，执行前需要确认。',
  aiMode: 'WRITE',
};
const PREVIEW = {
  intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT', intentName: '仓管快速入库', status: 'PREVIEW',
  confirmableAction: {
    confirmToken: 'token', commandDigest: 'a'.repeat(64), expiresAt: '2099-01-01T00:05:00Z', expiresInSeconds: 300,
    description: '确认后将实际执行「仓管快速入库」', previewData: { success: true, data: { orderNumber: 'PO-1', yourProposedQty: 1, unit: 'kg', canDo: true } },
    currentValues: {}, newValues: {}, impactSummary: '确认后立即生效。', actionType: 'WRITE', riskLevel: 'MEDIUM',
  },
};

beforeEach(() => {
  mockUser = factoryUser();
  mockRouteParams = undefined;
  navigationOptions = undefined;
  mockExecuteIntentStream = jest.fn(async (_input: string, callbacks: any) => {
    callbacks.onResult?.({ status: 'COMPLETED', message: '库存已查完' });
    callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 100 });
  });
  mockPreviewIntent = jest.fn();
  mockConfirmIntent = jest.fn();
  mockAppAlert = jest.fn();
  mockSetStringAsync = jest.fn().mockResolvedValue(true);
  mockScrollToEnd = jest.fn();
});

async function openReadyCard() {
  mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => {
    callbacks.onSessionId?.('s1'); callbacks.onResult?.(BLOCKED_WRITE); callbacks.onComplete?.({ status: 'WRITE_CONFIRM_REQUIRED', totalLatencyMs: 100 });
  });
  mockPreviewIntent.mockResolvedValue(PREVIEW);
  const view = render(<AIChatScreen />);
  await send(view, '收货 PO-1 1 公斤');
  await waitFor(() => expect(view.getByTestId('ai-confirm-execute')).toBeTruthy());
  return view;
}

describe('AIChatScreen B5 new conversation and stream generation', () => {
  it('normal new conversation aborts the active request and resets session/messages', async () => {
    let resolveStream!: () => void;
    mockExecuteIntentStream.mockImplementationOnce((_input: string, _callbacks: any, _factory: string, _entity: any, _options: any, signal: AbortSignal) => {
      expect(signal).toBeTruthy();
      return new Promise<void>((resolve) => { resolveStream = resolve; });
    });
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    const signal = mockExecuteIntentStream.mock.calls[0][5] as AbortSignal;
    expect(signal.aborted).toBe(false);
    const header = render(navigationOptions.headerRight());
    fireEvent.press(header.getByTestId('ai-chat-new-conversation'));
    expect(signal.aborted).toBe(true);
    expect(view.getByTestId('ai-chat-welcome-next-step')).toBeTruthy();
    resolveStream();
  });

  it('ready card asks before discard; cancel keeps card and destructive action resets it', async () => {
    const view = await openReadyCard();
    const header = render(navigationOptions.headerRight());
    fireEvent.press(header.getByTestId('ai-chat-new-conversation'));
    expect(mockAppAlert).toHaveBeenCalledWith('确认开始新对话', expect.stringContaining('不会执行任何写入'), expect.any(Array));
    const buttons = mockAppAlert.mock.calls.at(-1)[2];
    buttons.find((b: any) => b.style === 'cancel').onPress?.();
    expect(view.getByTestId('ai-confirm-execute')).toBeTruthy();
    buttons.find((b: any) => b.style === 'destructive').onPress?.();
    await waitFor(() => expect(view.getByTestId('ai-chat-welcome-next-step')).toBeTruthy());
  });

  it('executing card cannot be discarded by new conversation', async () => {
    let resolveConfirm!: (value: unknown) => void;
    mockConfirmIntent.mockImplementationOnce(() => new Promise((resolve) => { resolveConfirm = resolve; }));
    const view = await openReadyCard();
    await act(async () => { fireEvent.press(view.getByTestId('ai-confirm-execute')); });
    const header = render(navigationOptions.headerRight());
    fireEvent.press(header.getByTestId('ai-chat-new-conversation'));
    expect(mockAppAlert).toHaveBeenCalledWith('操作正在执行', expect.stringContaining('等待当前操作完成'));
    expect(view.getByTestId('ai-confirm-execute')).toBeTruthy();
    resolveConfirm({ status: 'SUCCESS', message: '已入库' });
  });

  it('late callbacks from an aborted old stream cannot pollute the new welcome conversation', async () => {
    let callbacks!: any;
    mockExecuteIntentStream.mockImplementationOnce((_input: string, c: any) => { callbacks = c; return new Promise(() => {}); });
    const view = render(<AIChatScreen />);
    await send(view, '旧问题');
    const header = render(navigationOptions.headerRight());
    fireEvent.press(header.getByTestId('ai-chat-new-conversation'));
    await act(async () => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '旧流迟到内容' });
      callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 1 });
    });
    expect(view.queryByText('旧流迟到内容')).toBeNull();
    expect(view.getByTestId('ai-chat-welcome-next-step')).toBeTruthy();
  });

  it('late preview resolution after reset cannot recreate the old confirmation card', async () => {
    let resolvePreview!: (value: unknown) => void;
    mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => {
      callbacks.onResult?.(BLOCKED_WRITE); callbacks.onComplete?.({ status: 'WRITE_CONFIRM_REQUIRED', totalLatencyMs: 100 });
    });
    mockPreviewIntent.mockImplementationOnce(() => new Promise((resolve) => { resolvePreview = resolve; }));
    const view = render(<AIChatScreen />);
    await send(view, '收货 PO-1 1 公斤');
    await waitFor(() => expect(view.getByTestId('ai-confirm-preparing')).toBeTruthy());
    const header = render(navigationOptions.headerRight());
    fireEvent.press(header.getByTestId('ai-chat-new-conversation'));
    resolvePreview(PREVIEW);
    await act(async () => { await Promise.resolve(); });
    expect(view.queryByTestId('ai-confirm-card')).toBeNull();
    expect(view.getByTestId('ai-chat-welcome-next-step')).toBeTruthy();
  });
});

describe('AIChatScreen B6 scroll threshold', () => {
  it('scrolls when within 120px of bottom, but does not pull an upward reader down after the 100ms delay', async () => {
    jest.useFakeTimers();
    const scrollSpy = mockScrollToEnd;
    const view = render(<AIChatScreen />);
    const scroll = view.UNSAFE_getByType(ScrollView);
    fireEvent.scroll(scroll, { nativeEvent: { contentOffset: { y: 780 }, contentSize: { height: 1000 }, layoutMeasurement: { height: 200 } } });
    // The delayed scroll is queued while at bottom; the user moves upward before 100ms.
    scroll.props.onContentSizeChange?.(1000, 1000);
    fireEvent.scroll(scroll, { nativeEvent: { contentOffset: { y: 400 }, contentSize: { height: 1600 }, layoutMeasurement: { height: 200 } } });
    await act(async () => { jest.advanceTimersByTime(100); });
    expect(scrollSpy).not.toHaveBeenCalled();
    fireEvent.scroll(scroll, { nativeEvent: { contentOffset: { y: 780 }, contentSize: { height: 1000 }, layoutMeasurement: { height: 200 } } });
    await act(async () => { scroll.props.onContentSizeChange?.(1000, 1000); jest.advanceTimersByTime(100); });
    expect(scrollSpy).toHaveBeenCalled();
    scrollSpy.mockClear();
    fireEvent.scroll(scroll, { nativeEvent: { contentOffset: { y: 400 }, contentSize: { height: 1600 }, layoutMeasurement: { height: 200 } } });
    await act(async () => { jest.advanceTimersByTime(100); });
    scroll.props.onContentSizeChange?.(1600, 1600);
    await act(async () => { jest.advanceTimersByTime(100); });
    expect(scrollSpy).not.toHaveBeenCalled();
    jest.useRealTimers();
  });
});

describe('AIChatScreen B7 long press copy', () => {
  it('long press opens appAlert; cancel does not copy and confirm calls real clipboard IO', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    const message = view.UNSAFE_getAllByType(Pressable).find((node: any) => typeof node.props.onLongPress === 'function');
    expect(message).toBeTruthy();
    await act(async () => { message.props.onLongPress(); });
    expect(mockAppAlert).toHaveBeenCalledWith('复制消息', expect.any(String), expect.any(Array));
    const buttons = mockAppAlert.mock.calls.at(-1)[2];
    buttons.find((b: any) => b.style === 'cancel').onPress?.();
    expect(mockSetStringAsync).not.toHaveBeenCalled();
    message.props.onLongPress();
    const confirm = mockAppAlert.mock.calls.at(-1)[2].find((b: any) => b.text === '复制');
    await act(async () => { confirm.onPress?.(); });
    await waitFor(() => expect(mockSetStringAsync).toHaveBeenCalledWith('查库存'));
  });

  it('clipboard rejection is contained and a later long press remains recoverable', async () => {
    mockSetStringAsync.mockRejectedValueOnce(new Error('clipboard unavailable'));
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    const message = view.UNSAFE_getAllByType(Pressable).find((node: any) => typeof node.props.onLongPress === 'function');
    message.props.onLongPress();
    const confirm = mockAppAlert.mock.calls.at(-1)[2].find((b: any) => b.text === '复制');
    await act(async () => { confirm.onPress?.(); });
    await act(async () => { await Promise.resolve(); });
    await waitFor(() => expect(mockAppAlert).toHaveBeenCalledWith('复制失败', '暂时无法复制，请稍后重试。'));
    message.props.onLongPress();
    expect(mockAppAlert.mock.calls.filter((call: any[]) => call[0] === '复制消息').length).toBe(2);
  });
});
