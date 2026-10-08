// @ts-nocheck
/**
 * AI 对话界面标准 (D:/Temp/claude/ai-chat-standard.md) R1+R2: 工厂 AIChatScreen 版式。
 *
 * 真实挂载 AIChatScreen, 只桩 SSE / 预览 / 确认 HTTP、导航、i18n 资源这几个边界;
 * 确认卡、富文本、反馈、Markdown 走真实实现。每条「没有」都配了一个「必有」的对照。
 *
 * jest 里 Paper 是手写替身 (fonts = {}), variant 的字号 / 字体测不出来 ——
 * 所以字号字体断言只打在显式 style 上; computed style 要在网页版上量。
 */
import React from 'react';
import { Platform, StyleSheet, AccessibilityInfo, Animated } from 'react-native';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';
import { previewRows } from '../../../components/ai/aiWriteConfirm';

let mockUser: any;
let mockRole = 'quality_inspector';
let mockExecuteIntentStream: jest.Mock;
let mockPreviewIntent: jest.Mock;
let mockConfirmIntent: jest.Mock;

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
  apiClient: { post: jest.fn(async () => ({ success: true, data: { success: true } })) },
}));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ setOptions: jest.fn(), dispatch: jest.fn(), getParent: () => null }),
  useRoute: () => ({ params: undefined }),
  CommonActions: { navigate: jest.fn() },
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => lookup(zhHome, key) ?? key, i18n: { language: 'zh-CN' } }) }));
jest.mock('i18next', () => ({ __esModule: true, default: { language: 'zh-CN' } }));
jest.mock('react-native-safe-area-context', () => ({ SafeAreaView: ({ children, ...props }: any) => { const { View } = require('react-native'); return <View {...props}>{children}</View>; } }));
jest.mock('../../../services/ai', () => ({ aiService: {}, detectAnalysisMode: () => ({ mode: 'quick' }) }));
jest.mock('../../../components/ai/AIModeIndicator', () => ({ AIModeIndicator: () => { const { Text } = require('react-native'); return <Text>MODE_CHIP</Text>; } }));
jest.mock('../../../components/common/VoiceMicButton', () => ({ VoiceMicButton: () => null }));
jest.mock('../../../components/ai/RestaurantGrossMarginRunCard', () => ({ RestaurantGrossMarginRunCard: () => null }));
jest.mock('../../../services/audio/feedbackSounds', () => ({ feedbackSounds: { onWriteSuccess: jest.fn(), onNeedMoreInfo: jest.fn(), onError: jest.fn() } }));

const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

function factoryUser() {
  return { id: 31, userType: 'factory', factoryUser: { factoryId: 'F006', factoryType: 'FACTORY', role: mockRole, permissions: [] } };
}

async function send(view: ReturnType<typeof render>, text: string) {
  if (!view.queryByTestId('ai-chat-input')) fireEvent.press(view.getByTestId('keyboard-toggle-btn'));
  fireEvent.changeText(view.getByTestId('ai-chat-input'), text);
  await act(async () => { fireEvent.press(view.getByTestId('ai-chat-send-btn')); });
}

/** 让流式「回答」一次到位: result + complete */
function replyWith(result: Record<string, unknown>, completeStatus = 'COMPLETED') {
  mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => {
    callbacks.onSessionId?.('s1');
    callbacks.onMeta?.({ model: 'x', questionType: 'BUSINESS' });
    callbacks.onResult?.(result);
    callbacks.onComplete?.({ status: completeStatus, totalLatencyMs: 4200 });
  });
}

const flat = (node: any) => StyleSheet.flatten(node.props.style) || {};

/** 从节点往上找第一个声明了 key 的祖先样式 (RTL 的 parent 先给的是复合组件, 样式在更上面一层) */
function inheritedStyle(node: any, key: string) {
  for (let cur = node.parent; cur; cur = cur.parent) {
    const style = StyleSheet.flatten(cur.props?.style);
    if (style && style[key] !== undefined) return style[key];
  }
  return undefined;
}

/** 渲染树里所有文本节点的内容 (图标在 jest 里也渲染成 Text, 内容是图标名) */
function allTextContents(view: ReturnType<typeof render>): string[] {
  return view.UNSAFE_getAllByType(require('react-native').Text)
    .map((node: any) => React.Children.toArray(node.props.children).filter((c) => typeof c === 'string' || typeof c === 'number').join(''))
    .filter((text: string) => text !== '');
}

const SUCCESS_ANSWER = { status: 'SUCCESS', message: '库存已查完', intentCode: 'MATERIAL_BATCH_QUERY', intentCategory: 'QUERY', metadata: {} };

beforeEach(() => {
  mockUser = factoryUser();
  mockRole = 'quality_inspector';
  mockPreviewIntent = jest.fn();
  mockConfirmIntent = jest.fn();
  mockExecuteIntentStream = jest.fn(async (_input: string, callbacks: any) => {
    callbacks.onResult?.(SUCCESS_ANSWER);
    callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 100 });
  });
});
afterEach(() => { jest.useRealTimers(); });

describe('标准 L1/L3/L4: 助手回答全宽、无头像、无气泡、无时间戳', () => {
  it('1. 助手消息无头像: 正文列在, 树里没有 robot 图标 (对照: send 图标看得见, 说明这个查法找得到图标)', async () => {
    const view = render(<AIChatScreen />);
    // 欢迎语同样没有头像 / 气泡
    expect(view.getAllByTestId('ai-chat-assistant-body')).toHaveLength(1);
    expect(view.queryAllByText('robot')).toHaveLength(0);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    expect(view.getAllByTestId('ai-chat-assistant-body')).toHaveLength(1);
    expect(view.queryAllByText('robot')).toHaveLength(0);
    expect(view.queryAllByText('send').length).toBeGreaterThan(0);
  });

  it('1b. 助手正文列没有气泡样式 (无背景 / 圆角 / 内边距 / maxWidth), 用户气泡有背景 (对照)', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    const body = flat(view.getByTestId('ai-chat-assistant-body'));
    for (const key of ['backgroundColor', 'borderRadius', 'borderTopLeftRadius', 'padding', 'paddingHorizontal', 'maxWidth', 'shadowOpacity', 'elevation']) {
      expect(body[key]).toBeUndefined();
    }
    expect(body.alignSelf).toBe('stretch');
    expect(flat(view.getByTestId('ai-chat-user-bubble')).backgroundColor).toBeTruthy();
  });

  it('2. 无时间戳: 回答渲染后没有 HH:MM 文本 (对照: 内容恰为 09:30 的回答, 同一个查法能找到)', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    const timeLike = /^\d{1,2}:\d{2}$/;
    const texts = allTextContents(view);
    expect(texts).toContain('库存已查完'); // 必发生: 回答真的渲染了
    expect(texts.filter((text) => timeLike.test(text))).toEqual([]);

    replyWith({ ...SUCCESS_ANSWER, message: '09:30' });
    await send(view, '几点了');
    await waitFor(() => expect(view.getByText('09:30')).toBeTruthy());
    expect(allTextContents(view).filter((text) => timeLike.test(text))).toEqual(['09:30']);
  });
});

describe('标准 E1/E2: 遥测只在显式开关下出现', () => {
  const ORIGINAL_DEV = (globalThis as any).__DEV__;
  afterEach(() => { (globalThis as any).__DEV__ = ORIGINAL_DEV; });

  it('3. __DEV__ = true 且不设开关 → ai-telemetry-row / ai-input-mode-row 都没有; 开关设 1 (isolateModules) → 都有', async () => {
    (globalThis as any).__DEV__ = true;
    expect(process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY).toBeUndefined();
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    expect(view.queryByTestId('ai-telemetry-row')).toBeNull();
    expect(view.queryByText('MODE_CHIP')).toBeNull();
    fireEvent.changeText(view.getByTestId('ai-chat-input'), '再查一次');
    expect(view.queryByTestId('ai-input-mode-row')).toBeNull();

    // 阳性对照: 同一条回答, 开关在模块加载时读一次, 所以在隔离的模块表里重新加载 (连 react / RTL 一起, 否则 hooks 对不上)
    const saved = process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY;
    const savedSkip = process.env.RNTL_SKIP_AUTO_CLEANUP;
    process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY = '1';
    process.env.RNTL_SKIP_AUTO_CLEANUP = 'true';
    let rtl: any; let Screen: any; let ReactIso: any;
    try {
      jest.isolateModules(() => {
        ReactIso = require('react');
        rtl = require('@testing-library/react-native');
        Screen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;
      });
    } finally {
      if (saved === undefined) delete process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY; else process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY = saved;
      if (savedSkip === undefined) delete process.env.RNTL_SKIP_AUTO_CLEANUP; else process.env.RNTL_SKIP_AUTO_CLEANUP = savedSkip;
    }
    const withSwitch = rtl.render(ReactIso.createElement(Screen));
    rtl.fireEvent.press(withSwitch.getByTestId('keyboard-toggle-btn'));
    rtl.fireEvent.changeText(withSwitch.getByTestId('ai-chat-input'), '查库存');
    await rtl.act(async () => { rtl.fireEvent.press(withSwitch.getByTestId('ai-chat-send-btn')); });
    await rtl.waitFor(() => expect(withSwitch.getByText('库存已查完')).toBeTruthy());
    expect(withSwitch.getByTestId('ai-telemetry-row')).toBeTruthy();
    rtl.fireEvent.changeText(withSwitch.getByTestId('ai-chat-input'), '再查一次');
    expect(withSwitch.getByTestId('ai-input-mode-row')).toBeTruthy();
    rtl.cleanup();
  });
});

const PREVIEW = {
  intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT', intentName: '仓管快速入库', status: 'PREVIEW',
  confirmableAction: {
    confirmToken: 'token', commandDigest: 'a'.repeat(64), expiresAt: '2099-01-01T00:05:00Z', expiresInSeconds: 300,
    description: '确认后将实际执行「仓管快速入库」',
    // 中文键 (「今天的打卡」「剩余数量」) + 英文已知键 (orderNumber / yourProposedQty) + 未知英文键 (internalFlag) + 系统键 (canDo)
    previewData: { success: true, data: { orderNumber: 'PO-1', '今天的打卡': '已打卡', '剩余数量': 5, yourProposedQty: 1, internalFlag: 7, canDo: true } },
    currentValues: {}, newValues: {}, impactSummary: '确认后立即生效。', actionType: 'WRITE', riskLevel: 'MEDIUM',
  },
};
const BLOCKED_WRITE = {
  intentRecognized: true, intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT', intentName: '仓管快速入库',
  status: 'WRITE_CONFIRM_REQUIRED', message: '该操作会写入/修改数据，执行前需要确认。', aiMode: 'WRITE',
};

async function openReadyCard() {
  replyWith(BLOCKED_WRITE, 'WRITE_CONFIRM_REQUIRED');
  mockPreviewIntent.mockResolvedValue(PREVIEW);
  const view = render(<AIChatScreen />);
  await send(view, '收货 PO-1 1 公斤');
  await waitFor(() => expect(view.getByTestId('ai-confirm-execute')).toBeTruthy());
  return view;
}

describe('标准 F4/F5/F6: 只有完成的回答才有反馈', () => {
  it('4a. SUCCESS 回答 → ai-chat-feedback 恰好 1 个', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(1);
    expect(view.getByLabelText('这个回答有帮助')).toBeTruthy();
  });

  it('4b. NEED_CLARIFICATION + suggestedActions → 0 个 (对照: 同一个屏上选项真的渲染了)', async () => {
    replyWith({
      status: 'NEED_CLARIFICATION', message: '我不太确定您想执行什么操作。', intentCode: 'MATERIAL_BATCH_QUERY',
      suggestedActions: [{ actionCode: 'SELECT_INTENT', actionName: '完成工序', parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE' } }],
    }, 'NEED_CLARIFICATION');
    const view = render(<AIChatScreen />);
    await send(view, '就是六十箱');
    await waitFor(() => expect(view.getByTestId('suggested-action-0')).toBeTruthy());
    expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(0);
  });

  it('4c. 状态不是「待用户」、但带了候选按钮 → 照样 0 个 (只靠 suggestedActions 条件挡住)', async () => {
    replyWith({
      status: 'SUCCESS', message: '您是想做下面哪件事？', intentCode: 'MATERIAL_BATCH_QUERY',
      candidates: [{ intentCode: 'A_ONE', intentName: '查库存' }, { intentCode: 'B_TWO', intentName: '查批次' }],
    });
    const view = render(<AIChatScreen />);
    await send(view, '查一下');
    await waitFor(() => expect(view.getByTestId('suggested-action-1')).toBeTruthy());
    expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(0);
  });

  it('4d. NEED_MORE_INFO (没有选项) → 0 个 (只靠状态条件挡住; 对照: 同样没选项的 SUCCESS 回答有 1 个)', async () => {
    replyWith({
      status: 'NEED_MORE_INFO', message: '缺参数', intentCode: 'WORK_PROCESS_TASK_START',
      clarificationQuestions: ['请问要开始哪道工序任务？'],
    }, 'NEED_MORE_INFO');
    const view = render(<AIChatScreen />);
    await send(view, '开始工序');
    await waitFor(() => expect(view.getByText(/还需要以下信息/)).toBeTruthy());
    expect(view.queryByTestId('suggested-action-0')).toBeNull();
    expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(0);

    await send(view, '查库存');
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(1);
  });

  it('4e. 写确认卡 ready → 0 个, 且没点确认就不发 confirm; 确认成功后追加的结果消息要有 1 个', async () => {
    mockConfirmIntent.mockResolvedValue({ status: 'SUCCESS', message: '已入库', intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT' });
    const view = await openReadyCard();
    expect(view.getByTestId('ai-confirm-execute')).toBeTruthy(); // 必发生: 确认卡 ready
    expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(0);
    expect(mockConfirmIntent).toHaveBeenCalledTimes(0);
    await act(async () => { fireEvent.press(view.getByTestId('ai-confirm-execute')); });
    await waitFor(() => expect(mockConfirmIntent).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(1));
  });

  it('4f. 出错的回答不出反馈 (对照: 重试成功后的回答有)', async () => {
    mockExecuteIntentStream.mockImplementationOnce(async (_input: string, callbacks: any) => { callbacks.onError?.('boom'); });
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByTestId('ai-chat-error-banner')).toBeTruthy());
    expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(0);
    await act(async () => { fireEvent.press(view.getByTestId('ai-chat-retry')); });
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    expect(view.queryAllByTestId('ai-chat-feedback')).toHaveLength(1);
  });

  it('F5/F6. 反馈栏没有贯穿横线 (borderTopWidth 为空), 👍 / 👎 触摸区至少 44×44', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByTestId('ai-chat-feedback')).toBeTruthy());
    // 反馈栏里所有带样式的节点: 没有一个画顶边线 (对照: 反馈栏容器 marginTop 12 的那一层确实被扫到了, 不是空扫)
    const styled = view.getByTestId('ai-chat-feedback').findAll((node: any) => node.props?.style !== undefined)
      .map((node: any) => StyleSheet.flatten(node.props.style) || {});
    expect(styled.some((style: any) => style.marginTop === 12)).toBe(true);
    expect(styled.filter((style: any) => (style.borderTopWidth ?? 0) > 0)).toEqual([]);
    for (const label of ['这个回答有帮助', '这个回答没有帮助']) {
      const style = flat(view.getByLabelText(label));
      expect(style.minWidth).toBeGreaterThanOrEqual(44);
      expect(style.minHeight).toBeGreaterThanOrEqual(44);
    }
  });
});

describe('标准 F3: 澄清选项一个样式', () => {
  const THREE_OPTIONS = {
    status: 'NEED_CLARIFICATION', message: '我不太确定您想执行什么操作。', intentCode: 'X',
    suggestedActions: [
      { actionCode: 'SELECT_INTENT', actionName: '完成工序', description: '把这道工序报完工', parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE' } },
      { actionCode: 'SELECT_INTENT', actionName: '记录抄码品称重', parameters: { intentCode: 'ABACA_WEIGHT_LOG' } },
      { actionCode: 'REPHRASE', actionName: '重新描述' },
    ],
  };

  it('5. 3 个选项 (第一个带说明): 都是 alignSelf stretch / minHeight 48, 没有 maxWidth / minWidth, 三个样式互相相等', async () => {
    replyWith(THREE_OPTIONS, 'NEED_CLARIFICATION');
    const view = render(<AIChatScreen />);
    await send(view, '就是六十箱');
    await waitFor(() => expect(view.getByTestId('suggested-action-2')).toBeTruthy());
    const styles = [0, 1, 2].map((i) => flat(view.getByTestId(`suggested-action-${i}`)));
    for (const style of styles) {
      expect(style.alignSelf).toBe('stretch');
      expect(style.minHeight).toBe(48);
      expect(style.maxWidth).toBeUndefined();
      expect(style.minWidth).toBeUndefined();
    }
    const layoutKeys = ['alignSelf', 'minHeight', 'paddingHorizontal', 'paddingVertical', 'borderRadius', 'maxWidth', 'minWidth', 'width'];
    const pick = (style: any) => Object.fromEntries(layoutKeys.map((k) => [k, style[k]]));
    expect(pick(styles[1])).toEqual(pick(styles[0]));
    expect(pick(styles[2])).toEqual(pick(styles[0]));
    // 竖排: 容器是 column
    expect(inheritedStyle(view.getByTestId('suggested-action-0'), 'flexDirection')).toBe('column');
    // F3 (Codex 本机验收 A2, 2026-10-08): 同组只有一个带说明 ⇒ 整组都不显示说明 (以前只显示这一个, 按钮高 48/64/48)
    expect(view.queryByText('把这道工序报完工')).toBeNull();
    // 说明没丢: 并进无障碍标签; 没说明的选项标签就是名字本身
    expect(view.getByTestId('suggested-action-0').props.accessibilityLabel).toBe('完成工序，把这道工序报完工');
    expect(view.getByTestId('suggested-action-1').props.accessibilityLabel).toBe('记录抄码品称重');
  });

  it('5c. 同组每个都有说明: 每个都显示, 单行 (阳性对照: 说明不是被一刀切掉的)', async () => {
    replyWith({
      ...THREE_OPTIONS,
      suggestedActions: [
        { actionCode: 'SELECT_INTENT', actionName: '完成工序', description: '把这道工序报完工', parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE' } },
        { actionCode: 'SELECT_INTENT', actionName: '记录抄码品称重', description: '记一次抄码品的重量', parameters: { intentCode: 'ABACA_WEIGHT_LOG' } },
      ],
    }, 'NEED_CLARIFICATION');
    const view = render(<AIChatScreen />);
    await send(view, '就是六十箱');
    await waitFor(() => expect(view.getByTestId('suggested-action-1')).toBeTruthy());
    for (const text of ['把这道工序报完工', '记一次抄码品的重量']) {
      expect(view.getByText(text).props.numberOfLines).toBe(1);
    }
  });

  it('5b. 追问芯片是另一套样式 (横排换行 / 最宽 220 / 至少 40), 不和澄清选项共用', async () => {
    replyWith({ ...SUCCESS_ANSWER, followUps: [{ label: '看低库存', question: '请查看当前低库存物料' }] });
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByTestId('ai-chat-followup-0')).toBeTruthy());
    const chip = flat(view.getByTestId('ai-chat-followup-0'));
    expect(chip.minHeight).toBe(40);
    expect(chip.maxWidth).toBe(220);
    expect(chip.alignSelf).toBeUndefined();
    expect(flat(view.getByTestId('ai-chat-followups')).flexDirection).toBe('row');
    expect(flat(view.getByTestId('ai-chat-followups')).flexWrap).toBe('wrap');
  });
});

describe('标准 W1: 加载用骨架屏', () => {
  it('6. 发出后、onResult 之前 (不等 3 秒) 就有骨架, 3 个子条, 带文字; 回答到达后消失', async () => {
    let callbacks: any;
    mockExecuteIntentStream.mockImplementationOnce((_input: string, c: any) => { callbacks = c; return new Promise(() => {}); });
    jest.useFakeTimers();
    const view = render(<AIChatScreen />);
    await send(view, '查询生产情况');
    // 没有推进任何计时器: 3 秒的「慢」状态还没到
    const skeleton = view.getByTestId('ai-chat-loading-skeleton');
    expect(React.Children.toArray(skeleton.props.children)).toHaveLength(3);
    expect(view.getByText('正在理解你的话…')).toBeTruthy();
    const widths = React.Children.toArray(skeleton.props.children).map((bar: any) => StyleSheet.flatten(bar.props.style).width);
    expect(widths).toEqual(['92%', '78%', '62%']);
    const bar = StyleSheet.flatten((React.Children.toArray(skeleton.props.children)[0] as any).props.style);
    expect(bar.height).toBe(14);
    expect(bar.borderRadius).toBe(5);
    expect(view.queryByTestId('ai-chat-assistant-body')).toBeTruthy();

    await act(async () => { jest.advanceTimersByTime(3000); });
    expect(view.getByText('正在处理')).toBeTruthy();
    expect(view.getByTestId('ai-chat-loading-skeleton')).toBeTruthy();

    await act(async () => {
      callbacks.onResult?.(SUCCESS_ANSWER);
      callbacks.onComplete?.({ status: 'COMPLETED', totalLatencyMs: 10 });
    });
    expect(view.getByText('库存已查完')).toBeTruthy(); // 必发生: 回答到了
    expect(view.queryByTestId('ai-chat-loading-skeleton')).toBeNull();
  });

  it('6b. 动画: 开了「减少动态效果」不转 (Animated.loop 0 次); 对照: 没开时转 (Animated.loop 1 次, 周期 1500ms)', async () => {
    const timingSpy = Animated.timing as jest.Mock;
    const loopSpy = Animated.loop as jest.Mock;
    const hang = () => mockExecuteIntentStream.mockImplementationOnce(() => new Promise(() => {}));

    (AccessibilityInfo.isReduceMotionEnabled as jest.Mock).mockResolvedValueOnce(true);
    loopSpy.mockClear(); timingSpy.mockClear();
    hang();
    const reduced = render(<AIChatScreen />);
    await send(reduced, '查询生产情况');
    await act(async () => { await Promise.resolve(); });
    expect(reduced.getByTestId('ai-chat-loading-skeleton')).toBeTruthy(); // 必发生: 骨架在, 只是不动
    expect(loopSpy).toHaveBeenCalledTimes(0);
    reduced.unmount();

    loopSpy.mockClear(); timingSpy.mockClear();
    hang();
    const normal = render(<AIChatScreen />);
    await send(normal, '查询生产情况');
    await act(async () => { await Promise.resolve(); });
    expect(loopSpy).toHaveBeenCalledTimes(1);
    const durations = timingSpy.mock.calls.map((call: any[]) => call[1].duration);
    expect(durations.reduce((a: number, b: number) => a + b, 0)).toBe(1500);
    const toValues = timingSpy.mock.calls.map((call: any[]) => call[1].toValue);
    expect(toValues).toEqual([0.6, 1]);
  });
});

describe('H3 / H4: 一线硬约束不削弱', () => {
  it('7. 确认卡业务行一行都不藏: ai-confirm-rows 子节点数 = previewData 去掉系统键后的键数 (中文键 / 已知英文键 / 未知英文键都显示)', async () => {
    const view = await openReadyCard();
    const expected = previewRows(PREVIEW.confirmableAction);
    const rows = view.getByTestId('ai-confirm-rows');
    const rendered = React.Children.toArray(rows.props.children).length;
    // origin/main 上同一份数据是 4 行 (藏掉了未知英文键 internalFlag); 2026-10-08 起业务行不藏 (H3), 是 5 行 —— 只多不少
    expect(expected.map((r) => r.key)).toEqual(['orderNumber', '今天的打卡', '剩余数量', 'yourProposedQty', 'internalFlag']);
    expect(rendered).toBe(expected.length);
    expect(rendered).toBe(5);
    expect(view.getByText('今天的打卡')).toBeTruthy();
    expect(view.getByText('已打卡')).toBeTruthy();
    // 未知英文键照原样显示键名, 值也在
    expect(view.getByText('internalFlag')).toBeTruthy();
    expect(view.getByText('7')).toBeTruthy();
    // 阴性对照: 系统键 canDo 不显示
    expect(view.queryByText('canDo')).toBeNull();
    // 外框占满正文列, 不再收缩成 92%
    const card = flat(view.getByTestId('ai-confirm-card'));
    expect(card.alignSelf).toBe('stretch');
    expect(card.maxWidth).toBeUndefined();
  });

  it('8. 点 suggested-action-0: executeIntentStream 第 5 个参数 intentCode / clarificationSelection / clarificationCandidates 与改前一致', async () => {
    replyWith({
      status: 'NEED_CLARIFICATION', message: '我不太确定您想执行什么操作。',
      suggestedActions: [
        { actionCode: 'SELECT_INTENT', actionName: '完成工序', parameters: { intentCode: 'WORK_PROCESS_TASK_COMPLETE' } },
        { actionCode: 'SELECT_INTENT', actionName: '记录抄码品称重', parameters: { intentCode: 'ABACA_WEIGHT_LOG' } },
        { actionCode: 'REPHRASE', actionName: '重新描述' },
      ],
    }, 'NEED_CLARIFICATION');
    const view = render(<AIChatScreen />);
    await send(view, '就是六十箱');
    await waitFor(() => expect(view.getByTestId('suggested-action-0')).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByTestId('suggested-action-0')); });
    await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(2));
    const call = mockExecuteIntentStream.mock.calls[1];
    expect(call[0]).toBe('就是六十箱');
    expect(call[2]).toBe('F006');
    const options = call[4];
    expect(options.intentCode).toBe('WORK_PROCESS_TASK_COMPLETE');
    expect(options.context.clarificationSelection).toBe(true);
    expect(options.context.clarificationCandidates).toEqual(['WORK_PROCESS_TASK_COMPLETE', 'ABACA_WEIGHT_LOG']);
    expect(options.sessionId).toBe('s1'); // 会话 id 照旧带回
    expect(Object.keys(options).sort()).toEqual(['context', 'intentCode', 'sessionId']);
    // 对照: 普通输入的下一句不带意图码与澄清字段
    await send(view, '再说一遍');
    expect(mockExecuteIntentStream.mock.calls[2][4]?.intentCode).toBeUndefined();
    expect(mockExecuteIntentStream.mock.calls[2][4]?.context?.clarificationSelection).toBeUndefined();
  });
});

describe('标准 L2 / S1 / S4: 用户气泡、消息流、输入框', () => {
  it('9. 用户气泡四角圆角都是 12 (没有 borderTopRightRadius: 0), 最宽 min((宽-32)*0.78, 320), 内边距 12/10', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    const bubble = flat(view.getByTestId('ai-chat-user-bubble'));
    expect(bubble.borderRadius).toBe(12);
    expect(bubble.borderTopRightRadius).toBeUndefined();
    expect(bubble.borderTopLeftRadius).toBeUndefined();
    expect(bubble.paddingHorizontal).toBe(12);
    expect(bubble.paddingVertical).toBe(10);
    expect(bubble.maxWidth).toBeCloseTo(Math.min((375 - 32) * 0.78, 320), 5);
    expect(bubble.maxWidth).toBeLessThanOrEqual(268);
    // 对照: 助手正文列没有圆角
    expect(flat(view.getByTestId('ai-chat-assistant-body')).borderRadius).toBeUndefined();
  });

  it('9b. 消息流: 容器左右 padding 16; 用户消息之后间距 8, 助手消息之后 24', async () => {
    const view = render(<AIChatScreen />);
    await send(view, '查库存');
    await waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    expect(StyleSheet.flatten(view.getByTestId('ai-chat-thread').props.contentContainerStyle).padding).toBe(16);
    expect(inheritedStyle(view.getByTestId('ai-chat-user-bubble'), 'marginBottom')).toBe(8);
    expect(inheritedStyle(view.getByTestId('ai-chat-assistant-body'), 'marginBottom')).toBe(24);
  });

  it('10. 输入框外框: 圆角 8、最低 44、最高 120、横向内边距 12 (对照: 语音优先态不出输入框, 大麦克风容器在)', () => {
    const view = render(<AIChatScreen />);
    expect(view.getByTestId('voice-first-container')).toBeTruthy();
    expect(view.queryByTestId('ai-chat-input-frame')).toBeNull();
    fireEvent.press(view.getByTestId('keyboard-toggle-btn'));
    const frame = flat(view.getByTestId('ai-chat-input-frame'));
    expect(frame.borderRadius).toBe(8);
    expect(frame.minHeight).toBe(44);
    expect(frame.maxHeight).toBe(120);
    expect(frame.paddingHorizontal).toBe(12);
    expect(frame.paddingVertical).toBe(0);
  });
});

describe('标准 T1: 对话区一套字体栈 (web)', () => {
  /** 在隔离的模块表里把 Platform.OS 改成 web 再加载组件; native 对照不改 */
  function loadIsolated(os: 'web' | 'ios', modules: string[]) {
    const savedSkip = process.env.RNTL_SKIP_AUTO_CLEANUP;
    process.env.RNTL_SKIP_AUTO_CLEANUP = 'true';
    const loaded: any = {};
    try {
      jest.isolateModules(() => {
        require('react-native').Platform.OS = os;
        loaded.React = require('react');
        loaded.rtl = require('@testing-library/react-native');
        for (const m of modules) loaded[m.split('/').pop()!] = require(m);
      });
    } finally {
      if (savedSkip === undefined) delete process.env.RNTL_SKIP_AUTO_CLEANUP; else process.env.RNTL_SKIP_AUTO_CLEANUP = savedSkip;
    }
    return loaded;
  }

  it('web: chatFontFamily 是系统栈; 原生: 空对象 (原生端字体用 Paper 平台默认)', () => {
    const web = loadIsolated('web', ['../../../components/ai/chatTypography']);
    const nativeSide = loadIsolated('ios', ['../../../components/ai/chatTypography']);
    const { CHAT_FONT_STACK, chatFontFamily } = web.chatTypography;
    expect(CHAT_FONT_STACK).toMatch(/-apple-system.*"PingFang SC".*"Microsoft YaHei".*sans-serif/);
    expect(CHAT_FONT_STACK).not.toMatch(/Noto|Playfair/);
    expect(chatFontFamily).toEqual({ fontFamily: CHAT_FONT_STACK });
    expect(nativeSide.chatTypography.chatFontFamily).toEqual({});
    web.rtl.cleanup(); nativeSide.rtl.cleanup();
  });

  it('withChatFonts (T1 / T2): 两个平台都把字距 0 交给 configureFonts; 只有 web 交字体栈 (原生端保留平台默认字体)', () => {
    const configOf = (os: 'web' | 'ios') => {
      let config: Record<string, unknown> | undefined;
      let replaced = false;
      jest.isolateModules(() => {
        require('react-native').Platform.OS = os;
        const paper = require('react-native-paper');
        const spy = jest.spyOn(paper, 'configureFonts').mockImplementation((arg: any) => { config = arg?.config; return { marker: os }; });
        const { withChatFonts } = require('../../../components/ai/chatTypography');
        const theme = { colors: {}, fonts: { bodyLarge: { fontFamily: 'Roboto', letterSpacing: 0.15 } } };
        replaced = withChatFonts(theme).fonts.marker === os;
        spy.mockRestore();
      });
      return { config, replaced };
    };
    const web = configOf('web');
    const ios = configOf('ios');
    expect(web.replaced).toBe(true);
    expect(web.config).toEqual({ fontFamily: expect.stringContaining('-apple-system'), letterSpacing: 0 });
    expect(ios.replaced).toBe(true);
    expect(ios.config).toEqual({ letterSpacing: 0 });
    // 对照: 原生端不许把 web 的字体栈带过去
    expect(ios.config).not.toHaveProperty('fontFamily');
  });

  it('web: 反馈栏与富文本卡里的原生 Text 都用系统栈; 对照: 原生端不写 fontFamily', () => {
    const detail = { orderId: 'o1', orderNumber: 'PO-1', supplierName: '某供应商' };
    for (const [os, expectFont] of [['web', true], ['ios', false]] as const) {
      const m = loadIsolated(os, ['../../../components/ai/FeedbackWidget', '../../../components/ai/RichContentRenderer', '../../../components/ai/chatTypography']);
      const feedback = m.rtl.render(m.React.createElement(m.FeedbackWidget.FeedbackWidget, { queryId: 'q', question: 'q', answer: 'a', intentCode: 'X' }));
      const rich = m.rtl.render(m.React.createElement(m.RichContentRenderer.RichContentRenderer, { data: m.RichContentRenderer.detectRichData(detail) }));
      const prompt = StyleSheet.flatten(feedback.getByText('这个回答有帮助吗？').props.style);
      const supplier = StyleSheet.flatten(rich.getByText('某供应商').props.style);
      if (expectFont) {
        expect(prompt.fontFamily).toBe(m.chatTypography.CHAT_FONT_STACK);
        expect(supplier.fontFamily).toBe(m.chatTypography.CHAT_FONT_STACK);
      } else {
        expect(prompt.fontFamily).toBeUndefined();
        expect(supplier.fontFamily).toBeUndefined();
      }
      m.rtl.cleanup();
    }
  });

  it('web: 整屏里 Markdown 正文 / 反馈栏的 fontFamily 同一个 (用户气泡与助手正文走 Paper 主题, jest 里替身没有字体, 在网页版上量)', async () => {
    const m = loadIsolated('web', ['../../../screens/factory-admin/ai-analysis/AIChatScreen', '../../../components/ai/chatTypography']);
    const view = m.rtl.render(m.React.createElement(m.AIChatScreen.default));
    m.rtl.fireEvent.press(view.getByTestId('keyboard-toggle-btn'));
    m.rtl.fireEvent.changeText(view.getByTestId('ai-chat-input'), '查库存');
    await m.rtl.act(async () => { m.rtl.fireEvent.press(view.getByTestId('ai-chat-send-btn')); });
    await m.rtl.waitFor(() => expect(view.getByText('库存已查完')).toBeTruthy());
    const answer = StyleSheet.flatten(view.getByText('库存已查完').props.style);
    const prompt = StyleSheet.flatten(view.getByText('这个回答有帮助吗？').props.style);
    expect(answer.fontFamily).toBe(m.chatTypography.CHAT_FONT_STACK);
    expect(prompt.fontFamily).toBe(answer.fontFamily);
    // 输入框: web 上单行 (numberOfLines=1)
    expect(view.getByTestId('ai-chat-input').props.numberOfLines).toBe(1);
    m.rtl.cleanup();
  });

  it('原生端: 输入框不限制行数 (对照上一条 web 的 numberOfLines=1)', () => {
    const view = render(<AIChatScreen />);
    fireEvent.press(view.getByTestId('keyboard-toggle-btn'));
    expect(Platform.OS).toBe('ios');
    expect(view.getByTestId('ai-chat-input').props.numberOfLines).toBeUndefined();
  });
});
