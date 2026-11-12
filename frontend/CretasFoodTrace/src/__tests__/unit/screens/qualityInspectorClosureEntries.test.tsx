/**
 * 纪律排查 B14 App 端: 质检员首页「不良品处置」「批次召回」入口按 quality 模块权限出现,
 * 处置页「批准 / 驳回」按 approval_quality_disposition 出现 —— 两侧 (显示 / 不显示) 都钉住,
 * 并钉住「新代码不写岗位名单」(判据是登录响应 modulePermissions, 不是 role)。
 */
import fs from 'fs';
import path from 'path';
import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';

import QIHomeScreen from '../../../screens/quality-inspector/QIHomeScreen';
import QIDispositionScreen from '../../../screens/quality-inspector/QIDispositionScreen';
import QIRecallScreen from '../../../screens/quality-inspector/QIRecallScreen';
import {
  canApproveQualityDisposition,
  canOpenQualityClosure,
  canWriteQualityClosure,
} from '../../../utils/qualityClosurePermissions';
import type { User } from '../../../types/auth';

const mockNavigate = jest.fn();
let mockUser: User | null = null;

jest.mock('@react-navigation/native', () => {
  const ReactActual = jest.requireActual('react');
  return {
    useNavigation: () => ({ navigate: mockNavigate, goBack: jest.fn(), replace: jest.fn() }),
    useFocusEffect: (cb: () => void) => ReactActual.useEffect(cb, []),
  };
});
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) => selector({ user: mockUser }),
}));
jest.mock('../../../components/ai/FrontlineAIEntry', () => ({ FrontlineAIEntry: () => null }));
jest.mock('../../../utils/factoryIdHelper', () => ({ getFactoryId: () => 'F006' }));

const mockCandidates = jest.fn();
const mockPending = jest.fn();
jest.mock('../../../services/api/qualityDispositionApiClient', () => ({
  qualityDispositionAPI: {
    listDispositionCandidates: (...a: unknown[]) => mockCandidates(...a),
    getPendingDispositions: (...a: unknown[]) => mockPending(...a),
    approveDisposition: jest.fn(),
  },
}));
const mockRecalls = jest.fn();
jest.mock('../../../services/api/qualityRecallApiClient', () => {
  const actual = jest.requireActual('../../../services/api/qualityRecallApiClient');
  return { ...actual, qualityRecallAPI: { list: (...a: unknown[]) => mockRecalls(...a) } };
});

function factoryUser(role: string, modulePermissions?: Record<string, 'rw' | 'r' | 'w' | '-'>): User {
  return {
    id: 501,
    username: 'qa_user',
    email: '',
    fullName: 'QA 用户',
    createdAt: '',
    updatedAt: '',
    isActive: true,
    userType: 'factory',
    factoryUser: { role, factoryId: 'F006', permissions: [], modulePermissions },
  } as unknown as User;
}

const INSPECTOR = { dashboard: 'r', production: 'r', quality: 'rw', approval_quality_disposition: '-' } as const;

beforeEach(() => {
  jest.clearAllMocks();
  mockCandidates.mockResolvedValue([]);
  mockPending.mockResolvedValue([{
    id: 'D-1',
    entityId: 'QI-1',
    productionBatchId: 9,
    batchNumber: 'QA-B14-001',
    decisionMade: 'SCRAP',
    reason: '微生物超标',
    executorId: 77,
    executorName: '张三',
    approvalLevel: 'QUALITY_HEAD',
    createdAt: '2026-11-12T08:00:00',
  }]);
  mockRecalls.mockResolvedValue([]);
});

describe('权限判定 (纯函数): 只读 modulePermissions, 不认岗位名', () => {
  it('质检员默认: 能进、能提交, 不能审批', () => {
    const u = factoryUser('quality_inspector', { ...INSPECTOR });
    expect(canOpenQualityClosure(u)).toBe(true);
    expect(canWriteQualityClosure(u)).toBe(true);
    expect(canApproveQualityDisposition(u)).toBe(false);
  });

  it('同一个岗位名, 工厂把 quality 关掉 ⇒ 入口消失 (按配置, 不按岗位)', () => {
    const u = factoryUser('quality_inspector', { ...INSPECTOR, quality: '-' });
    expect(canOpenQualityClosure(u)).toBe(false);
    expect(canWriteQualityClosure(u)).toBe(false);
  });

  it('只读 quality ⇒ 能进不能写', () => {
    const u = factoryUser('quality_inspector', { ...INSPECTOR, quality: 'r' });
    expect(canOpenQualityClosure(u)).toBe(true);
    expect(canWriteQualityClosure(u)).toBe(false);
  });

  it('工厂把质检处置审批授给了质检员 ⇒ 能审批; 只有 w (非 rw) 不算', () => {
    expect(canApproveQualityDisposition(factoryUser('quality_inspector', { ...INSPECTOR, approval_quality_disposition: 'rw' }))).toBe(true);
    expect(canApproveQualityDisposition(factoryUser('quality_inspector', { ...INSPECTOR, approval_quality_disposition: 'w' }))).toBe(false);
  });

  it('非质检岗位只要配置了 quality 也能进 (例如班组长)', () => {
    expect(canOpenQualityClosure(factoryUser('team_leader', { quality: 'r' }))).toBe(true);
  });
});

describe('首页入口: 显示 / 不显示两侧', () => {
  it('有 quality 权限: 两个入口出现, 点了进对应页面', () => {
    mockUser = factoryUser('quality_inspector', { ...INSPECTOR });
    const view = render(<QIHomeScreen />);
    fireEvent.press(view.getByTestId('qi-home-disposition'));
    expect(mockNavigate).toHaveBeenLastCalledWith('QIDisposition');
    fireEvent.press(view.getByTestId('qi-home-recall'));
    expect(mockNavigate).toHaveBeenLastCalledWith('QIRecall');
    // 阳性对照: 原有本职入口仍在
    expect(view.getByTestId('qi-home-new-inspection')).toBeTruthy();
  });

  it('没有 quality 权限: 两个入口都不出现, 原有入口仍在', () => {
    mockUser = factoryUser('quality_inspector', { ...INSPECTOR, quality: '-' });
    const view = render(<QIHomeScreen />);
    expect(view.queryByTestId('qi-home-disposition')).toBeNull();
    expect(view.queryByTestId('qi-home-recall')).toBeNull();
    expect(view.getByTestId('qi-home-new-inspection')).toBeTruthy();
  });
});

describe('处置页审批按钮: 按 approval_quality_disposition', () => {
  it('质检员默认: 看得到等待审批的报废, 看不到批准 / 驳回', async () => {
    mockUser = factoryUser('quality_inspector', { ...INSPECTOR });
    const view = render(<QIDispositionScreen />);
    await waitFor(() => expect(mockPending).toHaveBeenCalledWith('F006'));
    fireEvent.press(view.getByTestId('qi-disposition-tab-pending'));
    expect(await view.findByTestId('qi-disposition-pending-D-1')).toBeTruthy();
    expect(view.getByText('QA-B14-001')).toBeTruthy();
    expect(view.queryByTestId('qi-disposition-approve-D-1')).toBeNull();
    expect(view.queryByTestId('qi-disposition-reject-D-1')).toBeNull();
    expect(view.getByTestId('qi-disposition-approve-hint')).toBeTruthy();
  });

  it('工厂授了审批码: 批准 / 驳回出现', async () => {
    mockUser = factoryUser('quality_inspector', { ...INSPECTOR, approval_quality_disposition: 'rw' });
    const view = render(<QIDispositionScreen />);
    await waitFor(() => expect(mockPending).toHaveBeenCalled());
    fireEvent.press(view.getByTestId('qi-disposition-tab-pending'));
    expect(await view.findByTestId('qi-disposition-approve-D-1')).toBeTruthy();
    expect(view.getByTestId('qi-disposition-reject-D-1')).toBeTruthy();
    expect(view.queryByTestId('qi-disposition-approve-hint')).toBeNull();
  });
});

describe('召回页「发起召回」按 quality 读写', () => {
  it('rw 有, r 没有', async () => {
    mockUser = factoryUser('quality_inspector', { ...INSPECTOR });
    const rw = render(<QIRecallScreen />);
    await waitFor(() => expect(mockRecalls).toHaveBeenCalled());
    expect(rw.getByTestId('qi-recall-create')).toBeTruthy();
    rw.unmount();

    mockUser = factoryUser('quality_inspector', { ...INSPECTOR, quality: 'r' });
    const r = render(<QIRecallScreen />);
    await waitFor(() => expect(mockRecalls).toHaveBeenCalledTimes(2));
    expect(r.queryByTestId('qi-recall-create')).toBeNull();
  });
});

describe('源码约束: 新页面与判定不写岗位名单', () => {
  const srcRoot = path.resolve(__dirname, '../../../');
  const files = [
    'utils/qualityClosurePermissions.ts',
    'screens/quality-inspector/QIDispositionScreen.tsx',
    'screens/quality-inspector/QIDispositionExecuteScreen.tsx',
    'screens/quality-inspector/QIRecallScreen.tsx',
    'screens/quality-inspector/QIRecallCreateScreen.tsx',
    'screens/quality-inspector/QIRecallDetailScreen.tsx',
    'screens/quality-inspector/qualityClosureShared.tsx',
  ];
  it.each(files)('%s 不出现岗位代码', (rel) => {
    const src = fs.readFileSync(path.join(srcRoot, rel), 'utf8');
    for (const role of ['quality_inspector', 'quality_manager', 'factory_super_admin', 'getRoleCode', 'roleCode']) {
      expect(src).not.toContain(role);
    }
  });
});
