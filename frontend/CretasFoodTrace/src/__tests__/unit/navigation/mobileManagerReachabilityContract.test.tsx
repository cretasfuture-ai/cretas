import fs from 'fs';
import path from 'path';
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { useFocusEffect } from '@react-navigation/native';

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({ navigate: mockNavigate, goBack: jest.fn(), setOptions: jest.fn(), addListener: jest.fn() }),
  useRoute: () => ({ params: {} }),
  useFocusEffect: jest.fn(),
}));

import EquipmentManagementScreen from '../../../screens/processing/EquipmentManagementScreen';
import QILabelQcQueueScreen, { getManagerTaskRoute } from '../../../screens/quality-inspector/QILabelQcQueueScreen';
import QILabelQcSubmittedScreen from '../../../screens/quality-inspector/QILabelQcSubmittedScreen';
import QILabelQcReviewScreen from '../../../screens/quality-inspector/QILabelQcReviewScreen';
import { labelQcApi } from '../../../services/api/labelQcApi';
import { useAuthStore } from '../../../store/authStore';
import type { LabelQcTaskDetail, LabelQcTaskPage, LabelQcTaskSummary } from '../../../types/labelQc';
import type { User } from '../../../types/auth';

const readNavigator = (file: string) => fs.readFileSync(
  path.resolve(__dirname, '../../../navigation', file),
  'utf8',
);

describe('mobile manager navigator reachability', () => {
  it('dispatches both roles to dedicated navigators with real business screens', () => {
    const app = fs.readFileSync(
      path.resolve(__dirname, '../../../navigation/AppNavigator.tsx'),
      'utf8',
    );
    expect(app).toContain('return <EquipmentAdminNavigator />');
    expect(app).toContain('return <QualityManagerNavigator />');
    expect(readNavigator('EquipmentAdminNavigator.tsx')).toContain('EquipmentManagementScreen');
    expect(readNavigator('EquipmentAdminNavigator.tsx')).toContain('EquipmentMonitoringScreen');
    expect(readNavigator('QualityManagerNavigator.tsx')).toContain('QILabelQcReviewScreen');
    expect(readNavigator('QualityManagerNavigator.tsx')).toContain('QILabelQcSubmittedScreen');
  });

  it('hides the create action in manager queue mode', () => {
    const { queryByTestId } = render(<QILabelQcQueueScreen managerMode />);
    expect(queryByTestId('qi-label-qc-queue-create-button')).toBeNull();
  });

  it('ignores a rejected queue response from the previous actor', async () => {
    const firstActor = managerUser({ quality: 'r' });
    const secondActor = managerUser({ quality: 'r' }, 22);
    const firstResponse = deferred<LabelQcTaskPage>();
    const secondResponse = deferred<LabelQcTaskPage>();
    let requestCount = 0;
    const listTasks = jest.spyOn(labelQcApi, 'listTasks').mockImplementation(() => {
      requestCount += 1;
      return requestCount === 1 ? firstResponse.promise : secondResponse.promise;
    });
    const focusEffect = useFocusEffect as jest.Mock;
    let lastFocusedActorKey: string | null = null;
    focusEffect.mockImplementation((callback: () => void) => {
      const actor = useAuthStore.getState().user;
      const actorKey = actor ? `${actor.id}:${actor.factoryId ?? actor.factoryUser?.factoryId ?? 'none'}` : 'none';
      if (actorKey !== lastFocusedActorKey) {
        lastFocusedActorKey = actorKey;
        void callback();
      }
    });
    useAuthStore.setState({ user: firstActor, isAuthenticated: true });
    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<QILabelQcQueueScreen managerMode />);
      await waitFor(() => expect(listTasks).toHaveBeenCalledTimes(1));
      await act(async () => {
        useAuthStore.setState({ user: secondActor, isAuthenticated: true });
      });
      await waitFor(() => expect(listTasks).toHaveBeenCalledTimes(2));
      await act(async () => {
        firstResponse.reject(new Error('旧账号错误'));
        await expect(firstResponse.promise).rejects.toThrow('旧账号错误');
      });
      expect(screen?.queryByText('旧账号错误')).toBeNull();
      await act(async () => {
        secondResponse.resolve(emptyTaskPage());
        await secondResponse.promise;
      });
    } finally {
      screen?.unmount();
      listTasks.mockRestore();
      focusEffect.mockReset();
      useAuthStore.setState({ user: null, isAuthenticated: false });
    }
  });

  it('uses the real queue return and hides inspector-only Submitted actions in manager mode', () => {
    const { queryByTestId, getByTestId } = render(<QILabelQcSubmittedScreen managerMode />);
    expect(queryByTestId('qi-label-qc-submitted-new-task-button')).toBeNull();
    expect(queryByTestId('qi-label-qc-submitted-home-button')).toBeNull();
    expect(getByTestId('qi-label-qc-submitted-queue-button')).toBeTruthy();
  });

  it('hides previous queue rows on a same-account cross-factory switch before the new load settles', async () => {
    const firstActor = managerUser({ quality: 'r' }, 11, 'F006');
    const secondActor = managerUser({ quality: 'r' }, 11, 'F007');
    const firstResponse = deferred<LabelQcTaskPage>();
    const secondResponse = deferred<LabelQcTaskPage>();
    let requestCount = 0;
    const listTasks = jest.spyOn(labelQcApi, 'listTasks').mockImplementation(() => {
      requestCount += 1;
      return requestCount === 1 ? firstResponse.promise : secondResponse.promise;
    });
    const focusEffect = useFocusEffect as jest.Mock;
    let lastFocusedActorKey: string | null = null;
    focusEffect.mockImplementation((callback: () => void) => {
      const actor = useAuthStore.getState().user;
      const actorKey = actor ? `${actor.id}:${actor.factoryId ?? actor.factoryUser?.factoryId ?? 'none'}` : 'none';
      if (actorKey !== lastFocusedActorKey) {
        lastFocusedActorKey = actorKey;
        void callback();
      }
    });
    useAuthStore.setState({ user: firstActor, isAuthenticated: true });
    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<QILabelQcQueueScreen managerMode />);
      await waitFor(() => expect(listTasks).toHaveBeenCalledTimes(1));
      await act(async () => {
        firstResponse.resolve(queueTaskPage('TASK-F006', 'F006旧任务'));
        await firstResponse.promise;
      });
      expect(screen.getAllByText('F006旧任务').length).toBeGreaterThan(0);
      await act(async () => {
        useAuthStore.setState({ user: secondActor, isAuthenticated: true });
      });
      expect(screen.queryByText('F006旧任务')).toBeNull();
      await waitFor(() => expect(listTasks).toHaveBeenCalledTimes(2));
      await act(async () => {
        secondResponse.resolve(queueTaskPage('TASK-F007', 'F007新任务'));
        await secondResponse.promise;
      });
      expect(screen.getAllByText('F007新任务').length).toBeGreaterThan(0);
    } finally {
      screen?.unmount();
      listTasks.mockRestore();
      focusEffect.mockReset();
      useAuthStore.setState({ user: null, isAuthenticated: false });
    }
  });

  it('fails closed for a quality manager with read-only quality permission', async () => {
    const user = managerUser({ quality: 'r' });
    useAuthStore.setState({ user, isAuthenticated: true });
    const detail = reviewableDetail();
    const getTask = jest.spyOn(labelQcApi, 'getTask').mockResolvedValue(detail);
    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<QILabelQcReviewScreen managerMode />);
      expect(await screen.findByTestId('qi-label-qc-review-write-unavailable')).toBeTruthy();
      expect(getTask).toHaveBeenCalled();
    } finally {
      screen?.unmount();
      getTask.mockRestore();
      useAuthStore.setState({ user: null, isAuthenticated: false });
    }
  });

  it('does not display a late response from the previous actor', async () => {
    const firstActor = managerUser({ quality: 'rw' });
    const secondActor = managerUser({ quality: 'rw' }, 22);
    const firstResponse = deferred<LabelQcTaskDetail>();
    const secondResponse = deferred<LabelQcTaskDetail>();
    let requestCount = 0;
    const getTask = jest.spyOn(labelQcApi, 'getTask').mockImplementation(() => {
      requestCount += 1;
      return requestCount === 1 ? firstResponse.promise : secondResponse.promise;
    });
    useAuthStore.setState({ user: firstActor, isAuthenticated: true });
    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<QILabelQcSubmittedScreen managerMode />);
      await waitFor(() => expect(getTask).toHaveBeenCalled());
      await act(async () => {
        useAuthStore.setState({ user: secondActor, isAuthenticated: true });
      });
      await waitFor(() => expect(getTask).toHaveBeenCalledTimes(2));
      await act(async () => {
        firstResponse.resolve(submittedDetail());
        await firstResponse.promise;
      });
      expect(screen?.queryByText('1 张')).toBeNull();
      await act(async () => {
        secondResponse.resolve({ ...submittedDetail(), task: { ...submittedDetail().task, id: 'TASK-B', skuName: '新账号任务' } });
        await secondResponse.promise;
      });
    } finally {
      screen?.unmount();
      getTask.mockRestore();
      useAuthStore.setState({ user: null, isAuthenticated: false });
    }
  });

  it('does not render prior detail context during a cross-factory pending or failed load', async () => {
    const firstActor = managerUser({ quality: 'r' }, 11, 'F006');
    const secondActor = managerUser({ quality: 'r' }, 11, 'F007');
    const firstResponse = deferred<LabelQcTaskDetail>();
    const secondResponse = deferred<LabelQcTaskDetail>();
    let requestCount = 0;
    const getTask = jest.spyOn(labelQcApi, 'getTask').mockImplementation(() => {
      requestCount += 1;
      return requestCount === 1 ? firstResponse.promise : secondResponse.promise;
    });
    useAuthStore.setState({ user: firstActor, isAuthenticated: true });
    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<QILabelQcSubmittedScreen managerMode />);
      await waitFor(() => expect(getTask).toHaveBeenCalledTimes(1));
      await act(async () => {
        firstResponse.resolve(submittedDetail());
        await firstResponse.promise;
      });
      expect(screen.getByText('SKU-A · 旧账号任务')).toBeTruthy();
      expect(screen.getByText('B-A')).toBeTruthy();
      expect(screen.getByText('TASK-A')).toBeTruthy();

      await act(async () => {
        useAuthStore.setState({ user: secondActor, isAuthenticated: true });
      });
      await waitFor(() => expect(getTask).toHaveBeenCalledTimes(2));
      expect(screen.queryByText('SKU-A · 旧账号任务')).toBeNull();
      expect(screen.queryByText('B-A')).toBeNull();
      expect(screen.queryByText('TASK-A')).toBeNull();
      await act(async () => {
        secondResponse.reject(new Error('F007 状态读取失败'));
        await expect(secondResponse.promise).rejects.toThrow('F007 状态读取失败');
      });
      expect(screen.getByText('状态暂时无法刷新，请稍后重试。')).toBeTruthy();
      expect(screen.queryByText('SKU-A · 旧账号任务')).toBeNull();
    } finally {
      screen?.unmount();
      getTask.mockRestore();
      useAuthStore.setState({ user: null, isAuthenticated: false });
    }
  });

  it('polls Submitted status every five seconds and stops after a terminal result', async () => {
    jest.useFakeTimers();
    const user = managerUser({ quality: 'r' });
    const responses = [submittedDetailWithStatus('QUEUED'), submittedDetailWithStatus('AUTO_PASSED')];
    const getTask = jest.spyOn(labelQcApi, 'getTask').mockImplementation(async () => {
      return responses.shift() ?? submittedDetailWithStatus('AUTO_PASSED');
    });
    useAuthStore.setState({ user, isAuthenticated: true });
    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<QILabelQcSubmittedScreen managerMode />);
      await act(async () => {
        jest.advanceTimersByTime(0);
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(getTask).toHaveBeenCalledTimes(1);
      await act(async () => {
        jest.advanceTimersByTime(4999);
      });
      expect(getTask).toHaveBeenCalledTimes(1);
      await act(async () => {
        jest.advanceTimersByTime(1);
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(getTask).toHaveBeenCalledTimes(2);
      await act(async () => {
        jest.advanceTimersByTime(10000);
      });
      expect(getTask).toHaveBeenCalledTimes(2);
    } finally {
      screen?.unmount();
      getTask.mockRestore();
      useAuthStore.setState({ user: null, isAuthenticated: false });
      jest.useRealTimers();
    }
  });

  it('routes only reviewable manager task statuses into the review screen', () => {
    expect(getManagerTaskRoute('NEEDS_REVIEW')).toBe('QILabelQcReview');
    expect(getManagerTaskRoute('ANALYSIS_FAILED')).toBe('QILabelQcReview');
    expect(getManagerTaskRoute('NEEDS_REVIEW', false)).toBe('QILabelQcSubmitted');
    expect(getManagerTaskRoute('DRAFT')).toBe('QILabelQcSubmitted');
    expect(getManagerTaskRoute('AUTO_PASSED')).toBe('QILabelQcSubmitted');
    expect(getManagerTaskRoute('REVIEWED')).toBe('QILabelQcSubmitted');
  });

  it.each([
    { managerMode: false, quality: 'rw' as const, role: 'quality_inspector', destination: 'QILabelQcSubmitted' },
    { managerMode: true, quality: 'r' as const, role: 'quality_manager', destination: 'QILabelQcSubmitted' },
    { managerMode: true, quality: 'rw' as const, role: 'quality_manager', destination: 'QILabelQcReview' },
  ])('opens $role / $quality records only through $destination', async ({ managerMode, quality, role, destination }) => {
    const actor = managerUser({ quality });
    if (actor.userType === 'factory') actor.factoryUser.role = role as typeof actor.factoryUser.role;
    useAuthStore.setState({ user: actor, isAuthenticated: true });
    const listTasks = jest.spyOn(labelQcApi, 'listTasks').mockResolvedValue(queueTaskPage('TASK-ROLE', '角色路由任务'));
    mockNavigate.mockClear();
    const focusEffect = useFocusEffect as jest.Mock;
    focusEffect.mockImplementation((callback: () => void | (() => void)) => React.useEffect(callback, [callback]));
    let screen: ReturnType<typeof render> | undefined;
    try {
      screen = render(<QILabelQcQueueScreen managerMode={managerMode} />);
      await waitFor(() => expect(screen!.getAllByText('角色路由任务').length).toBeGreaterThan(0));
      fireEvent.press(screen.getAllByText('角色路由任务')[0]);
      expect(mockNavigate).toHaveBeenCalledTimes(1);
      expect(mockNavigate).toHaveBeenCalledWith(destination, expect.objectContaining({ taskId: 'TASK-ROLE' }));
    } finally {
      screen?.unmount();
      listTasks.mockRestore();
      focusEffect.mockReset();
      useAuthStore.setState({ user: null, isAuthenticated: false });
    }
  });

  it('hides equipment writes when the current account has no write permission', () => {
    const { queryByTestId } = render(<EquipmentManagementScreen />);
    expect(queryByTestId('equipment-management-create-button')).toBeNull();
    expect(queryByTestId('equipment-management-fab')).toBeNull();
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, resolve, reject };
}

function emptyTaskPage(): LabelQcTaskPage {
  return {
    content: [], page: 1, currentPage: 1, size: 50, totalElements: 0, totalPages: 0,
    first: true, last: true,
  };
}

function managerUser(modulePermissions: { quality: 'r' | 'rw' }, id = 11, factoryId = 'F006'): User {
  return {
    id,
    username: `quality-${id}`,
    email: `quality-${id}@example.test`,
    createdAt: '',
    updatedAt: '',
    isActive: true,
    userType: 'factory',
    factoryId,
    factoryUser: {
      role: 'quality_manager',
      factoryId,
      permissions: [],
      modulePermissions,
    },
  } as User;
}

function queueTaskPage(id: string, skuName: string): LabelQcTaskPage {
  const task: LabelQcTaskSummary = {
    id, productTypeId: 'SKU', skuCode: skuName, skuName, batchNumber: 'B-1',
    productionDate: '2026-10-04', createdBy: 11, status: 'NEEDS_REVIEW', version: 1,
    photoCount: 1, aiCandidateCount: 1, finalDefectCount: 0, archived: false,
    trainingStatus: 'PENDING', createdAt: '', updatedAt: '',
  };
  return { content: [task], page: 1, currentPage: 1, size: 50, totalElements: 1, totalPages: 1, first: true, last: true };
}

function submittedDetail(): LabelQcTaskDetail {
  return {
    task: {
      id: 'TASK-A', productTypeId: 'SKU-A', skuCode: 'SKU-A', skuName: '旧账号任务',
      batchNumber: 'B-A', productionDate: '2026-10-03', createdBy: 11, status: 'AUTO_PASSED',
      version: 1, photoCount: 1, aiCandidateCount: 0, finalDefectCount: 0, archived: false,
      trainingStatus: 'PENDING', createdAt: '', updatedAt: '',
    },
    photos: [],
  };
}

function submittedDetailWithStatus(status: LabelQcTaskDetail['task']['status']): LabelQcTaskDetail {
  return {
    ...submittedDetail(),
    task: { ...submittedDetail().task, status },
  };
}

function reviewableDetail(): LabelQcTaskDetail {
  return {
    ...submittedDetail(),
    task: { ...submittedDetail().task, status: 'NEEDS_REVIEW' },
  };
}
