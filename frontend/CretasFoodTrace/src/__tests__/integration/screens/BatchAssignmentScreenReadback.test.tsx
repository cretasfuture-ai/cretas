import React, { ReactNode } from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { FlatList, TouchableOpacity } from 'react-native';
import { Card } from 'react-native-paper';
import BatchAssignmentScreen from '../../../screens/hr/production/BatchAssignmentScreen';
import { useAuthStore } from '../../../store/authStore';

const mockGetAccess = jest.fn();
const mockGetAssignments = jest.fn();
const mockDispatch = jest.fn();
const mockBack = jest.fn();
const mockFocusCallbacks: Array<() => void | (() => void)> = [];
const mockReact = React;

type CommitProbeRecord = { actorKey: string; tree: unknown; accessCalls: number; assignmentCalls: number; passiveActorsAtLayout: string[] };
type TestRendererHandle = { toJSON: () => unknown; update: (element: React.ReactElement) => void };
type TestRendererApi = { create: (element: React.ReactElement) => TestRendererHandle; act: (callback: () => void | Promise<void>) => void | Promise<void> };
const testRenderer = require('react-test-renderer') as TestRendererApi;
const rendererAct = testRenderer.act;
let activeRenderer: TestRendererHandle | null = null;
let commitProbeRecords: CommitProbeRecord[] = [];
let passiveProbeActors: string[] = [];

function CommitProbe({ actorKey, children }: { actorKey: string; children: ReactNode }) {
  React.useLayoutEffect(() => {
    if (activeRenderer) {
      commitProbeRecords.push({
        actorKey,
        tree: activeRenderer.toJSON(),
        accessCalls: mockGetAccess.mock.calls.length,
        assignmentCalls: mockGetAssignments.mock.calls.length,
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
jest.mock('../../../services/api/schedulingApiClient', () => ({ schedulingApiClient: { getBatchAssignments: (...args: unknown[]) => mockGetAssignments(...args) } }));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ dispatch: mockDispatch, goBack: mockBack }),
  useFocusEffect: (callback: () => void | (() => void)) => { mockFocusCallbacks.push(callback); return mockReact.useEffect(callback, [callback]); },
  CommonActions: { navigate: jest.fn() },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const dictionary = require('../../../i18n/locales/zh-CN/hr.json');
      const value = key.split('.').reduce((current: unknown, part: string) => current && typeof current === 'object' ? (current as Record<string, unknown>)[part] : undefined, dictionary);
      return typeof value === 'string' ? value : key;
    },
  }),
}));

function user(id: number, factoryId: string, moduleLevel: 'r' | 'rw' = 'r') {
  return {
    id, username: `hr-${id}`, email: '', createdAt: '', updatedAt: '', isActive: true, userType: 'factory' as const,
    factoryId,
    factoryUser: { role: 'hr_admin' as const, factoryId, permissions: [], modulePermissions: { production: moduleLevel } },
  };
}

const allowed = (factoryEnabled = true) => [{ moduleCode: 'production_plan', displayName: '生产计划', category: 'production', permissionModule: 'production_plan', roleDefaultAllowed: true, override: null, effectiveAllowed: true, factoryEnabled }];

describe('BatchAssignmentScreen readback', () => {
  beforeEach(() => {
    mockGetAccess.mockReset(); mockGetAssignments.mockReset(); mockDispatch.mockReset(); mockBack.mockReset(); mockFocusCallbacks.length = 0;
    useAuthStore.setState({ user: user(11, 'F006'), isAuthenticated: true });
    mockGetAccess.mockResolvedValue(allowed());
    mockGetAssignments.mockResolvedValue({ content: [], totalElements: 0, totalPages: 1, page: 1 });
  });
  afterEach(() => { useAuthStore.setState({ user: null, isAuthenticated: false }); });

  it('preflights production_plan and keeps denied access separate from empty', async () => {
    mockGetAccess.mockResolvedValue([{ ...allowed()[0], effectiveAllowed: false }]);
    const view = render(<BatchAssignmentScreen />);
    await waitFor(() => expect(view.getByText('暂无生产计划模块权限')).toBeTruthy());
    expect(mockGetAssignments).not.toHaveBeenCalled();
  });

  it('shows an explicit successful empty state only after an allowed GET', async () => {
    const view = render(<BatchAssignmentScreen />);
    await waitFor(() => expect(view.getByText('暂无批次任务')).toBeTruthy());
    expect(mockGetAssignments).toHaveBeenCalledWith({ factoryId: 'F006', page: 1, size: 20 });
    expect(mockGetAccess).toHaveBeenCalledWith('F006', '11');
  });

  it('reads the next real page with the current actor owner and keeps partial metadata', async () => {
    mockGetAssignments.mockReset();
    mockGetAssignments.mockResolvedValueOnce({ content: [{ batchId: 'page-1', batchNumber: 'P1', productName: '第一页', status: 'PLANNED', assignedCount: null, requiredCount: null, totalWorkHours: null, laborCost: null, assignedEmployees: [] }], totalElements: 21, totalPages: 2, page: 1 });
    mockGetAssignments.mockResolvedValueOnce({ content: [{ batchId: 'page-2', batchNumber: 'P2', productName: '第二页', status: 'PLANNED', assignedCount: null, requiredCount: null, totalWorkHours: null, laborCost: null, assignedEmployees: [] }], totalElements: 21, totalPages: 2, page: 2 });
    const view = render(<BatchAssignmentScreen />);
    await waitFor(() => expect(view.getByText('P1')).toBeTruthy());
    fireEvent(view.UNSAFE_getByType(FlatList), 'onEndReached');
    await waitFor(() => expect(mockGetAssignments).toHaveBeenCalledWith({ factoryId: 'F006', page: 2, size: 20 }));
    expect(view.getByText('P2')).toBeTruthy();
  });

  it('drops old actor data and reloads the current factory after an actor switch', async () => {
    let resolveFirst!: (value: unknown) => void;
    const first = new Promise((resolve) => { resolveFirst = resolve; });
    mockGetAssignments.mockReturnValueOnce(first).mockResolvedValueOnce({
      content: [{ batchId: 'f007-batch', batchNumber: 'F007-1', productName: '新工厂', status: 'PLANNED', assignedCount: null, requiredCount: null, totalWorkHours: null, laborCost: null, assignedEmployees: [] }], totalElements: 1, totalPages: 1, page: 1,
    });
    const view = render(<BatchAssignmentScreen />);
    await waitFor(() => expect(mockGetAssignments).toHaveBeenCalledWith({ factoryId: 'F006', page: 1, size: 20 }));
    await act(async () => { useAuthStore.setState({ user: user(22, 'F007'), isAuthenticated: true }); });
    await waitFor(() => expect(mockGetAssignments).toHaveBeenCalledWith({ factoryId: 'F007', page: 1, size: 20 }));
    resolveFirst({ content: [{ batchId: 'old', batchNumber: 'F006-old', productName: '旧数据', status: 'COMPLETED', assignedCount: 1, requiredCount: 1, totalWorkHours: 1, laborCost: 2, assignedEmployees: [] }], totalElements: 1, totalPages: 1, page: 1 });
    await waitFor(() => expect(view.getByText('F007-1')).toBeTruthy());
    expect(view.queryByText('F006-old')).toBeNull();
  });

  it('blocks retained old row, refresh, end and focus callbacks before actor effects flush', async () => {
    mockGetAssignments.mockReset();
    mockGetAssignments.mockResolvedValueOnce({ content: [{ batchId: 'f006-batch', batchNumber: 'F006-1', productName: '旧工厂批次', status: 'PLANNED', assignedCount: null, requiredCount: null, totalWorkHours: null, laborCost: null, assignedEmployees: [] }], totalElements: 1, totalPages: 1, page: 1 });
    mockGetAssignments.mockResolvedValueOnce({ content: [{ batchId: 'f007-batch', batchNumber: 'F007-1', productName: '新工厂批次', status: 'PLANNED', assignedCount: null, requiredCount: null, totalWorkHours: null, laborCost: null, assignedEmployees: [] }], totalElements: 1, totalPages: 1, page: 1 });
    const view = render(<BatchAssignmentScreen />);
    await waitFor(() => expect(view.getByText('F006-1')).toBeTruthy());

    const list = view.UNSAFE_getByType(FlatList);
    const retainedRefresh = list.props.refreshControl.props.onRefresh as () => void;
    const retainedEndReached = list.props.onEndReached as () => void;
    const retainedFocus = mockFocusCallbacks[mockFocusCallbacks.length - 1]!;
    const retainedRow = view.UNSAFE_getAllByType(TouchableOpacity).find((node) => node.props.children?.type === Card);
    expect(retainedRow).toBeTruthy();
    const retainedRowPress = retainedRow!.props.onPress as () => void;
    const dispatchBefore = mockDispatch.mock.calls.length;

    useAuthStore.setState({ user: user(22, 'F007'), isAuthenticated: true });
    view.rerender(<BatchAssignmentScreen />);
    const accessAfterActor = mockGetAccess.mock.calls.length;
    const getAfterActor = mockGetAssignments.mock.calls.length;
    await act(async () => {
      expect(JSON.stringify(view.toJSON())).not.toContain('F006-1');
      retainedRowPress();
      retainedRefresh();
      retainedEndReached();
      retainedFocus();
      expect(mockGetAccess.mock.calls.length).toBe(accessAfterActor);
      expect(mockGetAssignments.mock.calls.length).toBe(getAfterActor);
      expect(mockDispatch.mock.calls.length).toBe(dispatchBefore);
      expect(JSON.stringify(view.toJSON())).not.toContain('F006-1');
      expect(JSON.stringify(view.toJSON())).not.toContain('F007-1');
    });
    await waitFor(() => expect(view.getByText('F007-1')).toBeTruthy());
    expect(view.queryByText('F006-1')).toBeNull();
  });

  it('proves the actor-switch commit clears old rows before the new actor effect runs', async () => {
    mockGetAssignments.mockReset();
    mockGetAssignments.mockResolvedValueOnce({ content: [{ batchId: 'f006-batch', batchNumber: 'F006-1', productName: '旧工厂批次', status: 'PLANNED', assignedCount: null, requiredCount: null, totalWorkHours: null, laborCost: null, assignedEmployees: [] }], totalElements: 1, totalPages: 1, page: 1 });
    mockGetAssignments.mockResolvedValueOnce({ content: [{ batchId: 'f007-batch', batchNumber: 'F007-1', productName: '新工厂批次', status: 'PLANNED', assignedCount: null, requiredCount: null, totalWorkHours: null, laborCost: null, assignedEmployees: [] }], totalElements: 1, totalPages: 1, page: 1 });
    commitProbeRecords = [];
    passiveProbeActors = [];
    activeRenderer = null;

    let renderer!: TestRendererHandle;
    await rendererAct(async () => {
      renderer = testRenderer.create(<CommitProbe actorKey="11:F006"><BatchAssignmentScreen /></CommitProbe>);
      activeRenderer = renderer;
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(mockGetAssignments).toHaveBeenCalledWith({ factoryId: 'F006', page: 1, size: 20 });
    const callsBeforeSwitch = mockGetAssignments.mock.calls.length;
    const accessBeforeSwitch = mockGetAccess.mock.calls.length;
    expect(passiveProbeActors).toContain('11:F006');

    rendererAct(() => {
      useAuthStore.setState({ user: user(22, 'F007'), isAuthenticated: true });
      renderer.update(<CommitProbe actorKey="22:F007"><BatchAssignmentScreen /></CommitProbe>);
    });
    const switchCommit = commitProbeRecords.find((record) => record.actorKey === '22:F007');
    expect(switchCommit).toBeDefined();
    expect(JSON.stringify(switchCommit!.tree)).not.toContain('F006-1');
    expect(switchCommit!.accessCalls).toBe(accessBeforeSwitch);
    expect(switchCommit!.assignmentCalls).toBe(callsBeforeSwitch);
    expect(switchCommit!.passiveActorsAtLayout).not.toContain('22:F007');

    await waitFor(() => expect(passiveProbeActors).toContain('22:F007'));
    await waitFor(() => expect(mockGetAccess).toHaveBeenCalledWith('F007', '22'));
    await waitFor(() => expect(mockGetAssignments).toHaveBeenCalledWith({ factoryId: 'F007', page: 1, size: 20 }));
    expect(mockGetAssignments.mock.calls.length).toBeGreaterThan(callsBeforeSwitch);
    expect(mockGetAccess.mock.calls.length).toBeGreaterThan(accessBeforeSwitch);
    expect(JSON.stringify(renderer.toJSON())).toContain('F007-1');
    expect(JSON.stringify(renderer.toJSON())).not.toContain('F006-1');
    activeRenderer = null;
  });

  it('shows factory module closure explicitly without issuing the batch GET', async () => {
    mockGetAccess.mockResolvedValue(allowed(false));
    const view = render(<BatchAssignmentScreen />);
    await waitFor(() => expect(view.getByText('生产计划模块已关闭')).toBeTruthy());
    expect(mockGetAssignments).not.toHaveBeenCalled();
  });
});
