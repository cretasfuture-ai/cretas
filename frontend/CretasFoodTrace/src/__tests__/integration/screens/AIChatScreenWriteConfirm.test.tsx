// @ts-nocheck
/**
 * AIChatScreen 写操作确认卡 (2026-10-04)。
 *
 * 链路: 流式 WRITE_CONFIRM_REQUIRED → POST /preview (原话 + 被拦 intentCode + sessionId)
 *       → PREVIEW + confirmableAction → 「确认执行」→ POST /confirm (token 走请求头)。
 *
 * 每条正向断言都配一条对照:
 *   出卡           ↔ 普通读结果不出卡、不发预览
 *   确认调用一次    ↔ 连点仍然一次 / 取消零次
 *   结果正文        ↔ 过期 / 网络 / 无权限 / 预览被拦 都是明确的话, 不是假成功
 */
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import zhHome from '../../../i18n/locales/zh-CN/home.json';

let mockUser: any;
let mockRole: string | null;
const mockExecuteIntentStream = jest.fn();
const mockPreviewIntent = jest.fn();
const mockConfirmIntent = jest.fn();
const mockOnWriteSuccess = jest.fn();
const mockOnError = jest.fn();

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
// 只换掉网络两端; buildConfirmationProof 用真的 (它决定 body 的四个字段)
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
jest.mock('../../../components/ai/RichContentRenderer', () => ({
  RichContentRenderer: () => null,
  detectRichData: () => null,
}));
jest.mock('../../../components/ai/RestaurantGrossMarginRunCard', () => ({
  RestaurantGrossMarginRunCard: () => null,
}));
jest.mock('../../../components/common/VoiceMicButton', () => ({ VoiceMicButton: () => null }));
jest.mock('../../../services/audio/feedbackSounds', () => ({
  feedbackSounds: {
    onWriteSuccess: (...a: unknown[]) => mockOnWriteSuccess(...a),
    onNeedMoreInfo: jest.fn(),
    onError: (...a: unknown[]) => mockOnError(...a),
  },
}));

const AIChatScreen = require('../../../screens/factory-admin/ai-analysis/AIChatScreen').default;

function factoryUser(factoryId: string) {
  return {
    id: 7,
    userType: 'factory',
    factoryUser: { factoryId, factoryType: 'FACTORY', role: 'warehouse_manager', permissions: [] },
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

const USER_TEXT = '收货 PO-0123 第 2 行 50 公斤';
const DIGEST = 'a'.repeat(64);
const TOKEN = 'opaque-confirm-token-xyz';
const EXPIRES_AT = '2099-01-01T00:05:00Z';

const BLOCKED_WRITE = {
  intentRecognized: true,
  intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT',
  status: 'WRITE_CONFIRM_REQUIRED',
  message: '该操作会写入/修改数据，执行前需要确认。',
  requiresApproval: true,
  aiMode: 'WRITE',
};

function previewOk(overrides: Record<string, unknown> = {}) {
  return {
    intentRecognized: true,
    intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT',
    intentName: '仓管快速入库',
    status: 'PREVIEW',
    message: '{"success":true}',
    aiMode: 'WRITE',
    confirmableAction: {
      confirmToken: TOKEN,
      commandDigest: DIGEST,
      expiresAt: EXPIRES_AT,
      expiresInSeconds: 300,
      description: '确认后将实际执行「仓管快速入库」',
      previewData: {
        success: true,
        data: {
          poId: 'PO-0123',
          orderNumber: 'PO-20261004-0123',
          materialName: '鸡胸肉',
          unit: 'kg',
          remainingCap: 60,
          yourProposedQty: 50,
          status: 'PREVIEW',
          canDo: true,
        },
      },
      currentValues: {},
      newValues: {},
      impactSummary: '此操作将写入「仓管快速入库」相关数据，确认后立即生效。',
      actionType: 'WRITE',
      riskLevel: 'MEDIUM',
      ...overrides,
    },
  };
}

function installBlockedWriteStream() {
  mockExecuteIntentStream.mockImplementation(async (_message: string, callbacks: any) => {
    callbacks.onSessionId?.('sess-write-1');
    callbacks.onResult?.(BLOCKED_WRITE);
    callbacks.onComplete?.({ status: 'WRITE_CONFIRM_REQUIRED', cacheHit: false, totalLatencyMs: 300 });
  });
}

async function openReadyCard() {
  installBlockedWriteStream();
  mockPreviewIntent.mockResolvedValue(previewOk());
  const screen = render(<AIChatScreen />);
  await send(screen, USER_TEXT);
  await waitFor(() => expect(screen.getByTestId('ai-confirm-execute')).toBeTruthy());
  return screen;
}

beforeEach(() => {
  mockUser = factoryUser('F006');
  mockRole = 'warehouse_manager';
  mockExecuteIntentStream.mockReset();
  mockPreviewIntent.mockReset();
  mockConfirmIntent.mockReset();
  mockOnWriteSuccess.mockReset();
  mockOnError.mockReset();
});

describe('AIChatScreen 写操作确认卡', () => {
  it('被 W0 拦下的写操作: 用原话 + 被拦意图码 + sessionId 请求预览, 出确认卡和关键内容', async () => {
    const screen = await openReadyCard();

    expect(mockPreviewIntent).toHaveBeenCalledTimes(1);
    const [request, factoryId] = mockPreviewIntent.mock.calls[0];
    expect(factoryId).toBe('F006');
    expect(request).toEqual({
      userInput: USER_TEXT,
      intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT',
      sessionId: 'sess-write-1',
    });

    const card = flattenText(screen.getByTestId('ai-confirm-card'));
    expect(card).toContain('「仓管快速入库」请确认');
    // 关键内容: 中文标签 + 值; 系统键 (status/canDo) 不露出
    const rows = flattenText(screen.getByTestId('ai-confirm-rows'));
    expect(rows).toContain('单号');
    expect(rows).toContain('PO-20261004-0123');
    expect(rows).toContain('物料');
    expect(rows).toContain('鸡胸肉');
    expect(rows).toContain('本次数量');
    expect(rows).not.toContain('canDo');
    expect(flattenText(screen.getByTestId('ai-confirm-impact'))).toContain('确认后立即生效');
    expect(flattenText(screen.getByTestId('ai-confirm-countdown'))).toMatch(/秒内有效/);
    // 气泡正文是后端那句人话, 不是 PREVIEW 的 JSON
    expect(screen.getAllByText('该操作会写入/修改数据，执行前需要确认。').length).toBeGreaterThan(0);
    expect(mockConfirmIntent).not.toHaveBeenCalled();
  });

  it('对照: 普通读结果不出确认卡, 也不发预览', async () => {
    mockExecuteIntentStream.mockImplementation(async (_m: string, callbacks: any) => {
      callbacks.onResult?.({ status: 'COMPLETED', message: '库存 120 公斤', intentCode: 'INVENTORY_QUERY' });
      callbacks.onComplete?.({ status: 'COMPLETED', cacheHit: false, totalLatencyMs: 100 });
    });
    const screen = render(<AIChatScreen />);
    await send(screen, '鸡胸肉还有多少');
    await waitFor(() => expect(screen.getAllByText('库存 120 公斤').length).toBeGreaterThan(0));
    expect(screen.queryByTestId('ai-confirm-card')).toBeNull();
    expect(mockPreviewIntent).not.toHaveBeenCalled();
  });

  it('确认执行: confirm 恰好一次, 带 token/digest/expiresAt 与客户端关联 ID, 结果作为新消息显示', async () => {
    mockConfirmIntent.mockResolvedValue({
      status: 'SUCCESS',
      intentCode: 'WAREHOUSE_RECEIVE_WITH_LIMIT',
      message: '已入库 50 kg 鸡胸肉 (PO-20261004-0123)',
    });
    const screen = await openReadyCard();

    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-execute')); });

    await waitFor(() => expect(screen.getAllByText('已入库 50 kg 鸡胸肉 (PO-20261004-0123)').length).toBeGreaterThan(0));
    expect(mockConfirmIntent).toHaveBeenCalledTimes(1);
    const [proof, factoryId] = mockConfirmIntent.mock.calls[0];
    expect(factoryId).toBe('F006');
    expect(proof.confirmToken).toBe(TOKEN);
    expect(proof.commandDigest).toBe(DIGEST);
    expect(proof.expiresAt).toBe(EXPIRES_AT);
    expect(proof.requestId).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/);
    expect(proof.idempotencyKey).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/);
    expect(Object.keys(proof).sort()).toEqual(['commandDigest', 'confirmToken', 'expiresAt', 'idempotencyKey', 'requestId']);
    // 卡片收起, 按钮消失 —— 不能再点第二次
    expect(flattenText(screen.getByTestId('ai-confirm-collapsed'))).toContain('已执行');
    expect(screen.queryByTestId('ai-confirm-execute')).toBeNull();
    expect(mockOnWriteSuccess).toHaveBeenCalledTimes(1);
  });

  it('连点「确认执行」(同一渲染里两次 + 在途时再点): confirm 仍然只调用一次', async () => {
    let resolveConfirm!: (v: unknown) => void;
    mockConfirmIntent.mockImplementation(() => new Promise((resolve) => { resolveConfirm = resolve; }));
    const screen = await openReadyCard();

    const button = screen.getByTestId('ai-confirm-execute');
    await act(async () => {
      fireEvent.press(button);
      fireEvent.press(button);
    });
    // 在途: 按钮禁用并显示「正在执行…」; 再点一次也不会发请求
    expect(flattenText(screen.getByTestId('ai-confirm-execute'))).toContain('正在执行');
    expect(screen.getByTestId('ai-confirm-execute').props.accessibilityState).toEqual(
      expect.objectContaining({ disabled: true, busy: true }),
    );
    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-execute')); });
    expect(mockConfirmIntent).toHaveBeenCalledTimes(1);

    await act(async () => { resolveConfirm({ status: 'SUCCESS', message: '已入库' }); });
    await waitFor(() => expect(screen.getAllByText('已入库').length).toBeGreaterThan(0));
    expect(mockConfirmIntent).toHaveBeenCalledTimes(1);
  });

  it('取消: 不发任何请求, 回复「已取消，没有执行任何操作」', async () => {
    const screen = await openReadyCard();
    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-cancel')); });

    expect(screen.getAllByText('已取消，没有执行任何操作').length).toBeGreaterThan(0);
    expect(flattenText(screen.getByTestId('ai-confirm-collapsed'))).toContain('已取消');
    expect(screen.queryByTestId('ai-confirm-execute')).toBeNull();
    expect(mockConfirmIntent).not.toHaveBeenCalled();
  });

  it('令牌已过期: 显示后端原话 + 「重新生成预览」, 点了重新请求预览而不是重放旧 token', async () => {
    mockConfirmIntent.mockResolvedValue({ status: 'FAILED', message: '令牌已过期，请重新发起预览' });
    const screen = await openReadyCard();
    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-execute')); });

    await waitFor(() => expect(screen.getByTestId('ai-confirm-notice')).toBeTruthy());
    const notice = flattenText(screen.getByTestId('ai-confirm-notice'));
    expect(notice).toContain('令牌已过期，请重新发起预览');
    expect(notice).toContain('没有执行任何操作');
    expect(screen.queryByTestId('ai-confirm-execute')).toBeNull();
    // 不是假成功: 没有「已执行」收起, 没有成功提示音
    expect(screen.queryByTestId('ai-confirm-collapsed')).toBeNull();
    expect(mockOnWriteSuccess).not.toHaveBeenCalled();
    expect(mockOnError).toHaveBeenCalledTimes(1);

    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-regenerate')); });
    await waitFor(() => expect(mockPreviewIntent).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByTestId('ai-confirm-execute')).toBeTruthy());
    expect(mockConfirmIntent).toHaveBeenCalledTimes(1);
  });

  it('确认时网络断开: 明确说「无法确认是否已执行」, 不给一键重试 (防双写)', async () => {
    mockConfirmIntent.mockRejectedValue(Object.assign(new Error('Network Error'), { request: {} }));
    const screen = await openReadyCard();
    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-execute')); });

    await waitFor(() => expect(screen.getByTestId('ai-confirm-notice')).toBeTruthy());
    expect(flattenText(screen.getByTestId('ai-confirm-notice'))).toContain('无法确认「仓管快速入库」是否已经执行');
    expect(screen.queryByTestId('ai-confirm-regenerate')).toBeNull();
    expect(screen.queryByTestId('ai-confirm-collapsed')).toBeNull();
  });

  it('无权限 (HTTP 403): 显示后端原话, 不给重试', async () => {
    mockConfirmIntent.mockRejectedValue(Object.assign(new Error('x'), {
      response: { status: 403, data: { message: '需要 inventory:write 权限，请联系工厂管理员开通' } },
    }));
    const screen = await openReadyCard();
    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-execute')); });

    await waitFor(() => expect(screen.getByTestId('ai-confirm-notice')).toBeTruthy());
    expect(flattenText(screen.getByTestId('ai-confirm-notice'))).toBe('需要 inventory:write 权限，请联系工厂管理员开通');
    expect(screen.queryByTestId('ai-confirm-regenerate')).toBeNull();
  });

  it('预览被业务边界拦住 (canDo=false): 显示后端原话, 不渲染「确认执行」', async () => {
    installBlockedWriteStream();
    mockPreviewIntent.mockResolvedValue(previewOk({
      previewData: {
        success: true,
        data: { status: 'OVER_LIMIT', canDo: false, message: '⛔ 实收 80kg 超过超收上限 (最多 60kg)' },
      },
    }));
    const screen = render(<AIChatScreen />);
    await send(screen, USER_TEXT);

    await waitFor(() => expect(screen.getByTestId('ai-confirm-notice')).toBeTruthy());
    expect(flattenText(screen.getByTestId('ai-confirm-notice'))).toContain('超过超收上限');
    expect(screen.queryByTestId('ai-confirm-execute')).toBeNull();
    expect(mockConfirmIntent).not.toHaveBeenCalled();
  });

  it('后端不支持预览 (如工序报工): 显示原因 + 去对应页面手工操作, 不渲染「确认执行」', async () => {
    installBlockedWriteStream();
    mockPreviewIntent.mockResolvedValue({
      intentCode: 'WORK_PROCESS_TASK_COMPLETE',
      intentName: '完成工序',
      status: 'FAILED',
      message: '预览模式暂不支持此意图类型',
    });
    const screen = render(<AIChatScreen />);
    await send(screen, '3 号任务做完了 60 箱');

    await waitFor(() => expect(screen.getByTestId('ai-confirm-notice')).toBeTruthy());
    const notice = flattenText(screen.getByTestId('ai-confirm-notice'));
    expect(notice).toContain('预览模式暂不支持此意图类型');
    expect(notice).toContain('请到对应功能页面手工操作');
    expect(screen.queryByTestId('ai-confirm-execute')).toBeNull();
  });

  describe('预览追问 (NEED_MORE_INFO) 的答案接得回来 (2026-10-07 真机: 答批次号被当新话识别成溯源查询)', () => {
    const ASK = {
      intentCode: 'MATERIAL_BATCH_USE',
      intentName: '领用原料',
      status: 'NEED_MORE_INFO',
      clarificationQuestions: ['要领用哪个批次？「2015胸肉」现在可用的批次有：MT-20260829-9687（2015胸肉，剩 12.5 kg）。请直接说批次号。'],
    };

    it('带 sessionId 的预览追问: 提示直接回答, 下一句流式请求带着这个 sessionId', async () => {
      installBlockedWriteStream();
      mockPreviewIntent.mockResolvedValue({ ...ASK, sessionId: 'sess-ask-1' });
      const screen = render(<AIChatScreen />);
      await send(screen, '领用2015胸肉0.1公斤');
      await waitFor(() => expect(screen.getByTestId('ai-confirm-notice')).toBeTruthy());
      const notice = flattenText(screen.getByTestId('ai-confirm-notice'));
      expect(notice).toContain('要领用哪个批次');
      expect(notice).toContain('不用重新说一遍');
      expect(notice).not.toContain('请补充后重新说一遍');

      mockExecuteIntentStream.mockClear();
      await send(screen, 'MT-20260829-9687');

      await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(1));   // 必发生: 第二句真的发出去了
      const [text, , , , options] = mockExecuteIntentStream.mock.calls[0];
      expect(text).toBe('MT-20260829-9687');
      expect(options.sessionId).toBe('sess-ask-1');
    });

    it('对照: 追问没带 sessionId (服务端没开会话) → 仍说「请补充后重新说一遍」, 下一句不带这个 id', async () => {
      installBlockedWriteStream();
      mockPreviewIntent.mockResolvedValue({ ...ASK });
      const screen = render(<AIChatScreen />);
      await send(screen, '领用2015胸肉0.1公斤');
      await waitFor(() => expect(screen.getByTestId('ai-confirm-notice')).toBeTruthy());
      expect(flattenText(screen.getByTestId('ai-confirm-notice'))).toContain('请补充后重新说一遍');

      mockExecuteIntentStream.mockClear();
      await send(screen, 'MT-20260829-9687');

      await waitFor(() => expect(mockExecuteIntentStream).toHaveBeenCalledTimes(1));
      const [, , , , options] = mockExecuteIntentStream.mock.calls[0];
      expect(options.sessionId).toBe('sess-write-1');   // 流式那一轮自己的会话, 不是追问会话
    });
  });

  it('预览请求网络失败: 明确说没有执行, 可重新生成预览', async () => {
    installBlockedWriteStream();
    mockPreviewIntent.mockRejectedValueOnce(new Error('Network Error')).mockResolvedValueOnce(previewOk());
    const screen = render(<AIChatScreen />);
    await send(screen, USER_TEXT);

    await waitFor(() => expect(screen.getByTestId('ai-confirm-notice')).toBeTruthy());
    expect(flattenText(screen.getByTestId('ai-confirm-notice'))).toContain('没有执行任何操作');
    // SITE B 拦截响应不带 intentName: 卡片说「这个操作」, ⛔ 不把英文意图码露给一线
    const blockedCard = flattenText(screen.getByTestId('ai-confirm-card'));
    expect(blockedCard).toContain('这个操作暂时不能执行');
    expect(blockedCard).not.toContain('WAREHOUSE_RECEIVE_WITH_LIMIT');
    await act(async () => { fireEvent.press(screen.getByTestId('ai-confirm-regenerate')); });
    await waitFor(() => expect(screen.getByTestId('ai-confirm-execute')).toBeTruthy());
    expect(mockPreviewIntent).toHaveBeenCalledTimes(2);
  });
});
