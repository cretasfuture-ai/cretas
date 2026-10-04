import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { ActivityIndicator, FlatList } from 'react-native';
import BatchWorkersScreen from '../../../screens/hr/production/BatchWorkersScreen';
import { useAuthStore } from '../../../store/authStore';

const mockGetAccess = jest.fn();
const mockGetWorkers = jest.fn();
const mockRemoveWorker = jest.fn();
const mockAppAlert = jest.fn();
const mockReact = React;

type CommitProbeRecord = { actorKey: string; tree: unknown; accessCalls: number; workerCalls: number; passiveActorsAtLayout: string[] };
type TestRendererHandle = { toJSON: () => unknown; update: (element: React.ReactElement) => void; unmount: () => void };
type TestRendererApi = { create: (element: React.ReactElement) => TestRendererHandle; act: (callback: () => void | Promise<void>) => void | Promise<void> };
const testRenderer = require('react-test-renderer') as TestRendererApi;
const rendererAct = testRenderer.act;
let activeRenderer: TestRendererHandle | null = null;
let commitProbeRecords: CommitProbeRecord[] = [];
let passiveProbeActors: string[] = [];

function CommitProbe({ actorKey, children }: { actorKey: string; children: React.ReactNode }) {
  React.useLayoutEffect(() => {
    if (activeRenderer) {
      commitProbeRecords.push({
        actorKey,
        tree: activeRenderer.toJSON(),
        accessCalls: mockGetAccess.mock.calls.length,
        workerCalls: mockGetWorkers.mock.calls.length,
        passiveActorsAtLayout: [...passiveProbeActors],
      });
    }
  }, [actorKey]);
  React.useEffect(() => {
    passiveProbeActors.push(actorKey);
  }, [actorKey]);
  return <>{children}</>;
}

jest.mock('../../../services/api/canvasApiClient', () => ({ canvasApiClient: { getUserModuleAccess: (...args: unknown[]) => mockGetAccess(...args) } }));
jest.mock('../../../services/api/schedulingApiClient', () => ({ schedulingApiClient: {
  getBatchWorkers: (...args: unknown[]) => mockGetWorkers(...args),
  removeWorkerFromBatch: (...args: unknown[]) => mockRemoveWorker(...args),
} }));
jest.mock('../../../components/ui/AppDialog', () => ({ appAlert: (...args: unknown[]) => mockAppAlert(...args) }));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn() }),
  useRoute: () => ({ params: { batchId: '1037', batchName: 'B-1037 - 冷冻鱼排' } }),
  useFocusEffect: (callback: () => void | (() => void)) => mockReact.useEffect(callback, [callback]),
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) => {
      const dictionary = require('../../../i18n/locales/zh-CN/hr.json');
      const value = key.split('.').reduce((current: unknown, part: string) => current && typeof current === 'object' ? (current as Record<string, unknown>)[part] : undefined, dictionary);
      return typeof value === 'string' ? value.replace('{name}', String(options?.name || '')) : key;
    },
  }),
}));

function user(id: number, factoryId: string, level: 'r' | 'rw' = 'rw') {
  return {
    id, username: `hr-${id}`, email: '', createdAt: '', updatedAt: '', isActive: true, userType: 'factory' as const, factoryId,
    factoryUser: { role: 'hr_admin' as const, factoryId, permissions: [], modulePermissions: { production: level } },
  };
}
const allowed = (factoryEnabled = true) => [{ moduleCode: 'production_plan', displayName: '生产计划', category: 'production', permissionModule: 'production_plan', roleDefaultAllowed: true, override: null, effectiveAllowed: true, factoryEnabled }];
const workerRows = [{ id: 1, userId: 11, userName: '未开始员工', workType: '一车间', workMinutes: null, status: 'assigned' }, { id: 2, userId: 12, userName: '正在员工', workType: '一车间', workMinutes: 35, status: 'working' }, { id: 3, userId: 13, userName: '完成员工', workType: '一车间', workMinutes: 60, status: 'completed' }, { id: 4, userId: 14, userName: '未知员工', workType: '一车间', workMinutes: 0, status: 'unknown' }];

describe('BatchWorkersScreen readback and guarded removal', () => {
  beforeEach(() => {
    mockGetAccess.mockReset(); mockGetWorkers.mockReset(); mockRemoveWorker.mockReset(); mockAppAlert.mockReset();
    useAuthStore.setState({ user: user(11, 'F006'), isAuthenticated: true });
    mockGetAccess.mockResolvedValue(allowed());
    mockGetWorkers.mockResolvedValue({ content: workerRows.map((row) => ({ ...row })), totalElements: 4 });
    mockRemoveWorker.mockResolvedValue({ success: true });
  });
  afterEach(() => { useAuthStore.setState({ user: null, isAuthenticated: false }); });

  it('shows assigned, working, completed and unknown statuses, and counts only working', async () => {
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(view.getByText('未开始员工')).toBeTruthy());
    expect(view.getByText('已分配')).toBeTruthy();
    expect(view.getAllByText('工作中').length).toBeGreaterThan(0);
    expect(view.getByText('已完成')).toBeTruthy();
    expect(view.getByText('未知')).toBeTruthy();
    expect(view.getByText('1')).toBeTruthy();
    expect(view.queryByText('添加员工')).toBeNull();
  });

  it('shows access failure without requesting workers when module is closed', async () => {
    mockGetAccess.mockResolvedValue(allowed(false));
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(view.getByText('生产计划模块已关闭')).toBeTruthy());
    expect(mockGetWorkers).not.toHaveBeenCalled();
  });

  it('does not DELETE when the confirmation callback is stale after actor change', async () => {
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(view.getByText('未开始员工')).toBeTruthy());
    fireEvent.press(view.getByTestId('batch-worker-remove-11'));
    const confirm = mockAppAlert.mock.calls[0][2][1].onPress as () => void;
    await act(async () => { useAuthStore.setState({ user: user(22, 'F007'), isAuthenticated: true }); confirm(); });
    expect(mockRemoveWorker).not.toHaveBeenCalled();
    expect(mockAppAlert).toHaveBeenCalledWith('错误', '账号权限或工厂已变化，请刷新后重试。');
  });

  it('blocks retained old worker row and refresh callbacks after an actor switch', async () => {
    mockGetWorkers.mockReset();
    mockGetWorkers.mockResolvedValueOnce({ content: workerRows, totalElements: workerRows.length }).mockResolvedValueOnce({ content: [{ id: 7, userId: 77, userName: 'F007员工', workType: '新车间', workMinutes: null, status: 'assigned' }], totalElements: 1 });
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(view.getByText('未开始员工')).toBeTruthy());
    const retainedRemove = view.getByTestId('batch-worker-remove-11').props.onPress as () => void;
    const retainedRefresh = view.UNSAFE_getByType(FlatList).props.refreshControl.props.onRefresh as () => void;
    useAuthStore.setState({ user: user(22, 'F007'), isAuthenticated: true });
    view.rerender(<BatchWorkersScreen />);
    const accessAfterActor = mockGetAccess.mock.calls.length;
    const getAfterActor = mockGetWorkers.mock.calls.length;
    const alertAfterActor = mockAppAlert.mock.calls.length;
    await act(async () => {
      retainedRemove();
      retainedRefresh();
      expect(mockGetAccess.mock.calls.length).toBe(accessAfterActor);
      expect(mockGetWorkers.mock.calls.length).toBe(getAfterActor);
      expect(mockAppAlert.mock.calls.length).toBe(alertAfterActor);
      expect(mockRemoveWorker).not.toHaveBeenCalled();
      expect(JSON.stringify(view.toJSON())).not.toContain('未开始员工');
    });
    await waitFor(() => expect(view.getByText('F007员工')).toBeTruthy());
    expect(view.queryByText('未开始员工')).toBeNull();
  });

  it('proves the actor-switch commit clears old workers before the new actor effect runs', async () => {
    mockGetWorkers.mockReset();
    mockGetWorkers.mockResolvedValueOnce({ content: workerRows, totalElements: workerRows.length }).mockResolvedValueOnce({ content: [{ id: 7, userId: 77, userName: 'F007员工', workType: '新车间', workMinutes: null, status: 'assigned' }], totalElements: 1 });
    commitProbeRecords = [];
    passiveProbeActors = [];
    activeRenderer = null;

    let renderer!: TestRendererHandle;
    await rendererAct(async () => {
      renderer = testRenderer.create(<CommitProbe actorKey="11:F006"><BatchWorkersScreen /></CommitProbe>);
      activeRenderer = renderer;
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(mockGetWorkers).toHaveBeenCalledWith('1037', 'F006');
    expect(passiveProbeActors).toContain('11:F006');
    const callsBeforeSwitch = mockGetWorkers.mock.calls.length;
    const accessBeforeSwitch = mockGetAccess.mock.calls.length;

    rendererAct(() => {
      useAuthStore.setState({ user: user(22, 'F007'), isAuthenticated: true });
      renderer.update(<CommitProbe actorKey="22:F007"><BatchWorkersScreen /></CommitProbe>);
    });
    const switchCommit = commitProbeRecords.find((record) => record.actorKey === '22:F007');
    expect(switchCommit).toBeDefined();
    expect(JSON.stringify(switchCommit!.tree)).not.toContain('未开始员工');
    expect(switchCommit!.accessCalls).toBe(accessBeforeSwitch);
    expect(switchCommit!.workerCalls).toBe(callsBeforeSwitch);
    expect(switchCommit!.passiveActorsAtLayout).not.toContain('22:F007');

    await waitFor(() => expect(passiveProbeActors).toContain('22:F007'));
    await waitFor(() => expect(mockGetAccess).toHaveBeenCalledWith('F007', '22'));
    await waitFor(() => expect(mockGetWorkers).toHaveBeenCalledWith('1037', 'F007'));
    expect(mockGetWorkers.mock.calls.length).toBeGreaterThan(callsBeforeSwitch);
    expect(mockGetAccess.mock.calls.length).toBeGreaterThan(accessBeforeSwitch);
    expect(JSON.stringify(renderer.toJSON())).toContain('F007员工');
    expect(JSON.stringify(renderer.toJSON())).not.toContain('未开始员工');
    rendererAct(() => { renderer.unmount(); });
    activeRenderer = null;
  });

  it('ignores a suspended old workers success after the new actor readback', async () => {
    let releaseOld!: (value: unknown) => void;
    const oldRequest = new Promise((resolve) => { releaseOld = resolve; });
    mockGetWorkers.mockReset();
    mockGetWorkers.mockReturnValueOnce(oldRequest).mockResolvedValueOnce({ content: [{ id: 7, userId: 77, userName: 'F007员工', workType: '新车间', workMinutes: null, status: 'assigned' }], totalElements: 1 });
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(mockGetWorkers).toHaveBeenCalledWith('1037', 'F006'));
    await act(async () => { useAuthStore.setState({ user: user(22, 'F007'), isAuthenticated: true }); });
    await waitFor(() => expect(mockGetWorkers).toHaveBeenCalledWith('1037', 'F007'));
    await waitFor(() => expect(view.getByText('F007员工')).toBeTruthy());
    releaseOld({ content: [{ id: 6, userId: 66, userName: 'F006旧员工', workType: '旧车间', workMinutes: 1, status: 'working' }], totalElements: 1 });
    await waitFor(() => expect(view.getByText('F007员工')).toBeTruthy());
    expect(view.queryByText('F006旧员工')).toBeNull();
    expect(view.UNSAFE_queryByType(ActivityIndicator)).toBeNull();
  });

  it('ignores a suspended old workers error after the new actor readback', async () => {
    let rejectOld!: (error: Error) => void;
    const oldRequest = new Promise((_, reject) => { rejectOld = reject; });
    mockGetWorkers.mockReset();
    mockGetWorkers.mockReturnValueOnce(oldRequest).mockResolvedValueOnce({ content: [{ id: 8, userId: 88, userName: 'F007员工2', workType: '新车间', workMinutes: 5, status: 'completed' }], totalElements: 1 });
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(mockGetWorkers).toHaveBeenCalledWith('1037', 'F006'));
    await act(async () => { useAuthStore.setState({ user: user(23, 'F007'), isAuthenticated: true }); });
    await waitFor(() => expect(view.getByText('F007员工2')).toBeTruthy());
    rejectOld(new Error('old actor failed'));
    await waitFor(() => expect(view.getByText('F007员工2')).toBeTruthy());
    expect(view.queryByText('加载失败')).toBeNull();
    expect(view.UNSAFE_queryByType(ActivityIndicator)).toBeNull();
  });

  it('rechecks current write access and module before one valid DELETE', async () => {
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(view.getByText('未开始员工')).toBeTruthy());
    fireEvent.press(view.getByTestId('batch-worker-remove-11'));
    const confirm = mockAppAlert.mock.calls[0][2][1].onPress as () => void;
    await act(async () => { confirm(); });
    await waitFor(() => expect(mockRemoveWorker).toHaveBeenCalledTimes(1));
    expect(mockGetAccess).toHaveBeenCalledTimes(3);
    expect(mockRemoveWorker).toHaveBeenCalledWith('1037', 11, 'F006');
  });

  it('blocks DELETE after production write permission is revoked', async () => {
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(view.getByText('未开始员工')).toBeTruthy());
    fireEvent.press(view.getByTestId('batch-worker-remove-11'));
    const confirm = mockAppAlert.mock.calls[0][2][1].onPress as () => void;
    await act(async () => { useAuthStore.setState({ user: user(11, 'F006', 'r') }); confirm(); });
    expect(mockRemoveWorker).not.toHaveBeenCalled();
  });

  it('surfaces a false-success remove as a visible failure', async () => {
    mockRemoveWorker.mockRejectedValue(new Error('false success'));
    const view = render(<BatchWorkersScreen />);
    await waitFor(() => expect(view.getByText('未开始员工')).toBeTruthy());
    fireEvent.press(view.getByTestId('batch-worker-remove-11'));
    const confirm = mockAppAlert.mock.calls[0][2][1].onPress as () => void;
    await act(async () => { confirm(); });
    await waitFor(() => expect(mockAppAlert).toHaveBeenCalledWith('错误', '移除失败，请重试'));
  });
});
