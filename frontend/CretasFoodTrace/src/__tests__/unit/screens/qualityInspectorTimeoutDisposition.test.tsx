/**
 * 食品 DAG 缺口 14 App 端: 质检员「超时处置」。
 * 钉住: 入口按后端读门槛 (quality / production 任一可读) 显示 / 不显示; 处置按钮按 quality 读写;
 * 处置结果无默认值、结论必填; 选了就写后果; 提交带的是选中的结果与结论; 新文件不写岗位名。
 */
import fs from 'fs';
import path from 'path';
import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';

import QIHomeScreen from '../../../screens/quality-inspector/QIHomeScreen';
import QITimeoutScreen from '../../../screens/quality-inspector/QITimeoutScreen';
import QITimeoutDecisionScreen from '../../../screens/quality-inspector/QITimeoutDecisionScreen';
import {
  appendPreset,
  decisionConsequence,
  overdueText,
  submitBlockedReason,
} from '../../../screens/quality-inspector/qiTimeoutView';
import { canDecideTimeoutEvent, canViewTimeoutEvents } from '../../../utils/qualityClosurePermissions';
import type { ProcessTimeLimitEvent } from '../../../services/api/processTimeLimitApi';
import type { User } from '../../../types/auth';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
let mockUser: User | null = null;
let mockParams: Record<string, unknown> = {};

jest.mock('@react-navigation/native', () => {
  const ReactActual = jest.requireActual('react');
  return {
    useNavigation: () => ({ navigate: mockNavigate, goBack: mockGoBack }),
    useRoute: () => ({ params: mockParams }),
    useFocusEffect: (cb: () => void) => ReactActual.useEffect(cb, []),
  };
});
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) => selector({ user: mockUser }),
}));
jest.mock('../../../components/ai/FrontlineAIEntry', () => ({ FrontlineAIEntry: () => null }));
jest.mock('../../../utils/factoryIdHelper', () => ({ getFactoryId: () => 'F006', requireFactoryId: () => 'F006' }));

const mockAlert = jest.fn();
jest.mock('../../../components/ui/AppDialog', () => ({
  appAlert: (...a: unknown[]) => mockAlert(...a),
  appPrompt: jest.fn(),
}));

const mockListOpen = jest.fn();
const mockListRecords = jest.fn();
const mockDecide = jest.fn();
jest.mock('../../../services/api/processTimeLimitApi', () => {
  const actual = jest.requireActual('../../../services/api/processTimeLimitApi');
  return {
    ...actual,
    processTimeLimitApi: {
      listOpenEvents: (...a: unknown[]) => mockListOpen(...a),
      listEventRecords: (...a: unknown[]) => mockListRecords(...a),
      decide: (...a: unknown[]) => mockDecide(...a),
    },
  };
});

function factoryUser(modulePermissions: Record<string, 'rw' | 'r' | 'w' | '-'>): User {
  return {
    id: 9001, username: 'qa', email: '', fullName: 'QA', createdAt: '', updatedAt: '', isActive: true,
    userType: 'factory',
    factoryUser: { role: 'any_role', factoryId: 'F006', permissions: [], modulePermissions },
  } as unknown as User;
}
const INSPECTOR = { dashboard: 'r', production: 'r', quality: 'rw' } as const;

// 真样本参照: F006 计划 PLAN-1794315754383-106830FC, 半成品 QA-V15-14-SEMI 0.1kg 被拦
const EVENT: ProcessTimeLimitEvent = {
  id: 41, targetType: 'SEMI_FINISHED', targetId: 'WIP-1', batchNumber: 'QA-V15-14-SEMI', processName: '冷却',
  limitMinutes: 120, startEvent: 'OUTPUT_POSTED', startAt: '2026-11-12T17:00:00', deadlineAt: '2026-11-12T19:00:00',
  expiredQuantity: 0.1, unit: 'kg', recheckValidMinutes: 60, status: 'OPEN',
  conclusion: null, decidedByName: null, decidedAt: null, validUntil: null,
};

beforeEach(() => {
  jest.clearAllMocks();
  mockListOpen.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [EVENT] });
  mockListRecords.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [] });
  mockParams = { event: EVENT };
});

describe('权限: 与后端 ProcessTimeLimitController 同一门槛, 不认岗位名', () => {
  it('quality 可读或 production 可读都能看清单; 都没有就不能', () => {
    expect(canViewTimeoutEvents(factoryUser({ quality: 'r' }))).toBe(true);
    expect(canViewTimeoutEvents(factoryUser({ production: 'r' }))).toBe(true);
    expect(canViewTimeoutEvents(factoryUser({ quality: '-', production: '-' }))).toBe(false);
  });
  it('处置只认 quality 读写; production 读写不算', () => {
    expect(canDecideTimeoutEvent(factoryUser({ quality: 'rw' }))).toBe(true);
    expect(canDecideTimeoutEvent(factoryUser({ quality: 'r', production: 'rw' }))).toBe(false);
  });
});

describe('首页「超时处置」入口: 显示 / 不显示', () => {
  it('质检员默认: 出现, 点了进清单', () => {
    mockUser = factoryUser({ ...INSPECTOR });
    const view = render(<QIHomeScreen />);
    fireEvent.press(view.getByTestId('qi-home-timeout'));
    expect(mockNavigate).toHaveBeenLastCalledWith('QITimeout');
  });
  it('工厂把 quality 和 production 都关掉: 不出现, 原有入口仍在', () => {
    mockUser = factoryUser({ ...INSPECTOR, quality: '-', production: '-' });
    const view = render(<QIHomeScreen />);
    expect(view.queryByTestId('qi-home-timeout')).toBeNull();
    expect(view.getByTestId('qi-home-new-inspection')).toBeTruthy();
  });
});

describe('清单', () => {
  it('卡片带批次号、工序、超时数量; 有写权限写「去处置」', async () => {
    mockUser = factoryUser({ ...INSPECTOR });
    const view = render(<QITimeoutScreen />);
    await waitFor(() => expect(mockListOpen).toHaveBeenCalledWith('F006'));
    expect(await view.findByText('QA-V15-14-SEMI')).toBeTruthy();
    expect(view.getByText(/超时数量：0\.1 kg/)).toBeTruthy();
    expect(view.getByText('去处置')).toBeTruthy();
    expect(view.queryByTestId('qi-timeout-readonly-hint')).toBeNull();
  });
  it('只读账号: 写「查看」并说明要写权限', async () => {
    mockUser = factoryUser({ quality: 'r', production: 'r' });
    const view = render(<QITimeoutScreen />);
    expect(await view.findByTestId('qi-timeout-readonly-hint')).toBeTruthy();
    expect(view.getByText('查看')).toBeTruthy();
  });
});

describe('处置页', () => {
  it('进页时没有默认结果, 提交禁用并写明原因', () => {
    mockUser = factoryUser({ ...INSPECTOR });
    const view = render(<QITimeoutDecisionScreen />);
    expect(view.getByTestId('qi-timeout-blocked-reason').props.children).toBe('请先选「复检放行」或「判不合格」');
    expect(view.queryByTestId('qi-timeout-consequence')).toBeNull();
    expect(view.queryByTestId('qi-timeout-conclusion')).toBeNull();
  });

  it('选判不合格 → 写后果; 结论空时仍禁用; 点常用结论后提交, 带的是选中结果与结论', async () => {
    mockUser = factoryUser({ ...INSPECTOR });
    mockDecide.mockResolvedValue({ success: true, code: 200, message: '已判定不合格', data: { ...EVENT, status: 'REJECTED' } });
    const view = render(<QITimeoutDecisionScreen />);
    fireEvent.press(view.getByTestId('qi-timeout-decision-REJECTED'));
    expect(view.getByTestId('qi-timeout-consequence').props.children).toBe('判不合格后，超时的 0.1 kg 从库存转出，不能再领用');
    expect(view.getByTestId('qi-timeout-blocked-reason').props.children).toBe('请填写复检结论');
    fireEvent.press(view.getByTestId('qi-timeout-preset-微生物超标'));
    expect(view.queryByTestId('qi-timeout-blocked-reason')).toBeNull();
    fireEvent.press(view.getByTestId('qi-timeout-submit'));
    // 二次确认框: 标题带结果和批次号, 点确认才提交
    const [title, , buttons] = mockAlert.mock.calls[0] as [string, string, { text: string; onPress?: () => void }[]];
    expect(title).toBe('判不合格 — QA-V15-14-SEMI');
    expect(mockDecide).not.toHaveBeenCalled();
    buttons.find((b) => b.text === '确认判不合格')?.onPress?.();
    await waitFor(() => expect(mockDecide).toHaveBeenCalledWith(41, { decision: 'REJECTED', conclusion: '微生物超标' }, 'F006'));
  });

  it('没有 quality 读写: 不显示两个处置按钮, 显示只读说明', () => {
    mockUser = factoryUser({ quality: 'r', production: 'rw' });
    const view = render(<QITimeoutDecisionScreen />);
    expect(view.getByTestId('qi-timeout-decision-readonly')).toBeTruthy();
    expect(view.queryByTestId('qi-timeout-decision-RELEASED')).toBeNull();
    expect(view.queryByTestId('qi-timeout-submit')).toBeNull();
  });

  it('别人已处置过 (提交失败且清单里没了这条): 提示返回清单', async () => {
    mockUser = factoryUser({ ...INSPECTOR });
    mockDecide.mockRejectedValue(new Error('这条超时已经处置过了'));
    mockListOpen.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [] });
    const view = render(<QITimeoutDecisionScreen />);
    fireEvent.press(view.getByTestId('qi-timeout-decision-RELEASED'));
    fireEvent.press(view.getByTestId('qi-timeout-preset-复检感官合格'));
    fireEvent.press(view.getByTestId('qi-timeout-submit'));
    (mockAlert.mock.calls[0][2] as { text: string; onPress?: () => void }[]).find((b) => b.text === '确认复检放行')?.onPress?.();
    await waitFor(() => expect(mockAlert).toHaveBeenCalledTimes(2));
    const [title, msg, buttons] = mockAlert.mock.calls[1] as [string, string, { text: string }[]];
    expect(title).toBe('处置没有提交成功');
    expect(msg).toContain('这条已经被处理过了');
    expect(buttons?.[0]?.text).toBe('返回清单');
  });
});

describe('显示口径 (与网页同一套)', () => {
  it('放行后果按 recheckValidMinutes; 原料判不合格是整批', () => {
    expect(decisionConsequence(EVENT, 'RELEASED')).toBe('放行后 1 小时 内可以领用，过了还要再处置');
    expect(decisionConsequence({ ...EVENT, recheckValidMinutes: null }, 'RELEASED')).toBe('放行后这批料不再限时');
    expect(decisionConsequence({ ...EVENT, targetType: 'RAW_MATERIAL' }, 'REJECTED')).toBe('判不合格后，整批标为不合格，不能再领用');
  });
  it('常用结论追加不重复; 结论只有空格算没填', () => {
    expect(appendPreset('', 'A')).toBe('A');
    expect(appendPreset('A', 'B')).toBe('A；B');
    expect(appendPreset('A；B', 'B')).toBe('A；B');
    expect(submitBlockedReason('RELEASED', '   ')).toBe('请填写复检结论');
  });
  it('已超时按东八区截止时间算, 截止缺失写「—」', () => {
    const deadline = Date.UTC(2026, 10, 12, 19 - 8, 0, 0);
    expect(overdueText(EVENT, deadline + 15 * 60000 + 1)).toBe('16 分钟');
    expect(overdueText({ deadlineAt: null }, Date.now())).toBe('—');
  });
});

describe('源码约束: 不写岗位名', () => {
  const srcRoot = path.resolve(__dirname, '../../../');
  it.each([
    'screens/quality-inspector/QITimeoutScreen.tsx',
    'screens/quality-inspector/QITimeoutDecisionScreen.tsx',
    'screens/quality-inspector/qiTimeoutView.ts',
  ])('%s', (rel) => {
    const src = fs.readFileSync(path.join(srcRoot, rel), 'utf8');
    for (const role of ['quality_inspector', 'quality_manager', 'factory_super_admin', 'getRoleCode', 'roleCode']) {
      expect(src).not.toContain(role);
    }
  });
});
