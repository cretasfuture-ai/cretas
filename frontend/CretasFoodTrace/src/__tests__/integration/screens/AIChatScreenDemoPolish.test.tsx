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

const ORIGINAL_DEV = (globalThis as any).__DEV__;

describe('AIChatScreen 第五幕 polish', () => {
  beforeEach(() => {
    mockUser = factoryUser('CATTLE_3701_001');
    mockRole = 'factory_super_admin';
    mockParent = null;
    mockDispatch.mockReset();
    mockExecuteIntentStream.mockReset();
    (globalThis as any).__DEV__ = ORIGINAL_DEV;
  });
  afterAll(() => { (globalThis as any).__DEV__ = ORIGINAL_DEV; });

  describe('(b) 快捷卡按行业', () => {
    it('CATTLE_3701_001 的欢迎页是四张牛业卡', () => {
      const screen = render(<AIChatScreen />);
      expect(screen.getByText('这批来自哪几头牛')).toBeTruthy();
      expect(screen.getByText('谁 DNA 不匹配')).toBeTruthy();
      expect(screen.getByText('拍耳标认牛')).toBeTruthy();
      expect(screen.queryByText('生产报工')).toBeNull();
      expect(screen.queryByTestId('quick-action-card-4')).toBeNull();
    });

    it('对照：F006 同角色的欢迎页仍是原来的六张 FA 卡', () => {
      mockUser = factoryUser('F006');
      const screen = render(<AIChatScreen />);
      expect(screen.getByText('生产报工')).toBeTruthy();
      expect(screen.getByText('毛利率')).toBeTruthy();
      expect(screen.queryByText('这批来自哪几头牛')).toBeNull();
      expect(screen.getByTestId('quick-action-card-5')).toBeTruthy();
    });
  });

  describe('(f) 卡片落点要能到另一个 tab 的 stack', () => {
    it('父导航器有 BossOverviewTab 时，投递到 BossOverviewTab.{screen}', () => {
      const parentNavigate = jest.fn();
      mockParent = {
        navigate: parentNavigate,
        getState: () => ({ routeNames: ['BossOverviewTab', 'BossAnalysisTab', 'BossProfileTab'] }),
      };
      const screen = render(<AIChatScreen />);
      fireEvent.press(screen.getByText('这批来自哪几头牛'));
      expect(parentNavigate).toHaveBeenCalledWith('BossOverviewTab', { screen: 'Traceability', params: undefined });
      fireEvent.press(screen.getByText('拍耳标认牛'));
      expect(parentNavigate).toHaveBeenLastCalledWith('BossOverviewTab', { screen: 'VisionVerify', params: undefined });
      expect(mockExecuteIntentStream).not.toHaveBeenCalled();
    });

    it('对照：父导航器没有 BossOverviewTab（餐饮树）时仍走 FAManagementTab', () => {
      const parentNavigate = jest.fn();
      mockParent = { navigate: parentNavigate, getState: () => ({ routeNames: ['FAHomeTab', 'FAManagementTab'] }) };
      mockUser = factoryUser('F006');
      const screen = render(<AIChatScreen />);
      fireEvent.press(screen.getByText('BOM达成率'));
      expect(parentNavigate).toHaveBeenCalledWith('FAManagementTab', { screen: 'BomAchievement', params: undefined });
    });

    it('「谁 DNA 不匹配」走问答，不导航', async () => {
      installTextReply('牛 A\n牛 B');
      const screen = render(<AIChatScreen />);
      await act(async () => { fireEvent.press(screen.getByText('谁 DNA 不匹配')); });
      expect(mockExecuteIntentStream).toHaveBeenCalledWith(
        '哪一头牛的 DNA 比对不匹配', expect.any(Object), 'CATTLE_3701_001', undefined,
        undefined, expect.any(AbortSignal),
      );
      expect(mockDispatch).not.toHaveBeenCalled();
    });
  });

  describe('(c) 遥测只给开发者看', () => {
    it('非 __DEV__：回答完成后没有模式 chip、没有「4.2s」', async () => {
      (globalThis as any).__DEV__ = false;
      installTextReply('本批来自 6 头牛。');
      const screen = render(<AIChatScreen />);
      await send(screen, '这批来自哪几头牛');
      await waitFor(() => expect(screen.getByText('本批来自 6 头牛。')).toBeTruthy());
      // 「必发生」对照：回答真的渲染出来了，所以下面的「没有」不是「根本没跑」
      expect(screen.queryByTestId('ai-telemetry-row')).toBeNull();
      expect(screen.queryByText('MODE_CHIP')).toBeNull();
      expect(screen.queryByText('4.2s')).toBeNull();
      expect(screen.queryByTestId('ai-input-mode-row')).toBeNull();
    });

    // 标准 E2: 遥测开关是显式的 EXPO_PUBLIC_AI_DEBUG_TELEMETRY=1, 不再看 __DEV__
    // (RN 网页版是开发服务器, __DEV__ 恒为 true, 业务用户验收时遥测就是这么漏出来的)。
    it('阴性：没设开关时，即使 __DEV__ = true 也没有模式 chip、没有「4.2s」', async () => {
      (globalThis as any).__DEV__ = true;
      installTextReply('本批来自 6 头牛。');
      const screen = render(<AIChatScreen />);
      await send(screen, '这批来自哪几头牛');
      await waitFor(() => expect(screen.getByText('本批来自 6 头牛。')).toBeTruthy());
      expect(screen.queryByTestId('ai-telemetry-row')).toBeNull();
      expect(screen.queryByText('MODE_CHIP')).toBeNull();
      expect(screen.queryByText('4.2s')).toBeNull();
    });

    it('对照：开关设为 1 时同一条回答渲染模式 chip 与耗时', async () => {
      (globalThis as any).__DEV__ = false;
      installTextReply('本批来自 6 头牛。');
      const saved = process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY;
      const savedSkip = process.env.RNTL_SKIP_AUTO_CLEANUP;
      process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY = '1';
      // 隔离表里再加载一份 RTL 会在 test 内注册 afterEach 而报错; 关掉自动清理, 末尾手动 cleanup
      process.env.RNTL_SKIP_AUTO_CLEANUP = 'true';
      let rtl: any; let Screen: any; let ReactIso: any;
      try {
        // 开关在模块加载时读一次, 所以要在隔离的模块表里重新加载屏幕 (连 react / RTL 一起, 否则 hooks 对不上)
        jest.isolateModules(() => {
          ReactIso = require('react');
          rtl = require('@testing-library/react-native');
          Screen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;
        });
      } finally {
        if (saved === undefined) delete process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY;
        else process.env.EXPO_PUBLIC_AI_DEBUG_TELEMETRY = saved;
        if (savedSkip === undefined) delete process.env.RNTL_SKIP_AUTO_CLEANUP;
        else process.env.RNTL_SKIP_AUTO_CLEANUP = savedSkip;
      }
      const screen = rtl.render(ReactIso.createElement(Screen));
      rtl.fireEvent.press(screen.getByTestId('keyboard-toggle-btn'));
      rtl.fireEvent.changeText(screen.getByTestId('ai-chat-input'), '这批来自哪几头牛');
      await rtl.act(async () => { rtl.fireEvent.press(screen.getByTestId('ai-chat-send-btn')); });
      await rtl.waitFor(() => expect(screen.getByTestId('ai-telemetry-row')).toBeTruthy());
      expect(screen.getByText('4.2s')).toBeTruthy();
      rtl.cleanup();
    });
  });

  describe('(d) 无障碍', () => {
    it('发送 / 键盘切换 / 输入框 / 取消 都有 accessibilityLabel（取自 i18n）', () => {
      const screen = render(<AIChatScreen />);
      const toggle = screen.getByTestId('keyboard-toggle-btn');
      expect(toggle.props.accessibilityLabel).toBe('切换到键盘输入');
      expect(toggle.props.accessibilityRole).toBe('button');
      expect(screen.getByText('点一下说话')).toBeTruthy();
      fireEvent.press(toggle);
      const sendBtn = screen.getByTestId('ai-chat-send-btn');
      expect(sendBtn.props.accessibilityLabel).toBe('发送');
      expect(sendBtn.props.accessibilityRole).toBe('button');
      expect(sendBtn.props.accessibilityState).toEqual({ disabled: true });
      expect(screen.getByTestId('ai-chat-input').props.accessibilityLabel).toBe('问题输入框');
      fireEvent.changeText(screen.getByTestId('ai-chat-input'), '你好');
      expect(screen.getByTestId('ai-chat-send-btn').props.accessibilityState).toEqual({ disabled: false });
    });

    it('建议操作按钮带 label', async () => {
      mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
        callbacks.onResult?.({
          status: 'NEED_CLARIFICATION',
          message: '你是想问哪一个？',
          suggestedActions: [{ actionName: '看批次', actionCode: 'BATCH' }],
        });
        callbacks.onComplete?.({ status: 'NEED_CLARIFICATION', totalLatencyMs: 5 });
      });
      const screen = render(<AIChatScreen />);
      await send(screen, '看看');
      await waitFor(() => expect(screen.getByTestId('suggested-action-0')).toBeTruthy());
      expect(screen.getByTestId('suggested-action-0').props.accessibilityLabel).toBe('看批次');
    });
  });

  describe('(e) 气泡保留换行', () => {
    it('完成态：每头牛一行的正文，两行之间的 \\n 还在', async () => {
      installTextReply('SL-001 黑安格斯\nSL-002 西门塔尔\nSL-003 利木赞');
      const screen = render(<AIChatScreen />);
      await send(screen, '这批来自哪几头牛');
      await waitFor(() => expect(screen.getByText(/SL-003/)).toBeTruthy());
      const text = flattenText(screen.toJSON());
      expect(text).toContain('SL-001 黑安格斯\nSL-002 西门塔尔\nSL-003 利木赞');
      // 阴性对照：没有被压成一行
      expect(text).not.toContain('黑安格斯 SL-002');
    });

    it('流式态：逐 token 追加的正文也保留 \\n', async () => {
      mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
        callbacks.onToken?.('第一行');
        callbacks.onToken?.('\n第二行');
        // 不 complete —— 停在流式态
        await new Promise(() => undefined);
      });
      const screen = render(<AIChatScreen />);
      await send(screen, '来');
      await waitFor(() => expect(screen.getByText(/第二行/)).toBeTruthy());
      expect(flattenText(screen.toJSON())).toContain('第一行\n第二行');
    });
  });
});
