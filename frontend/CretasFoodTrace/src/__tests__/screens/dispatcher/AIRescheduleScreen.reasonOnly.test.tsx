import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { Alert } from 'react-native';
import AIRescheduleScreen from '../../../screens/dispatcher/ai/AIRescheduleScreen';

const mockGetPlans = jest.fn();
const mockGetPlan = jest.fn();
const mockReschedule = jest.fn();
const mockAiReschedule = jest.fn();
const mockWhatIf = jest.fn();

jest.mock('../../../services/api/schedulingApiClient', () => ({
  schedulingApiClient: {
    getPlans: (...args: unknown[]) => mockGetPlans(...args),
    getPlan: (...args: unknown[]) => mockGetPlan(...args),
    reschedule: (...args: unknown[]) => mockReschedule(...args),
  },
}));

jest.mock('../../../services/api/schedulingOptimizationApiClient', () => ({
  schedulingOptimizationApiClient: {
    whatIf: (...args: unknown[]) => mockWhatIf(...args),
    reschedule: (...args: unknown[]) => mockAiReschedule(...args),
  },
}));

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn() }),
}));

jest.mock('expo-linear-gradient', () => ({
  LinearGradient: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));
jest.mock('@react-native-community/datetimepicker', () => {
  const ReactNative = require('react-native');
  const React = require('react');
  const DatePicker = ({ onChange, testID }: { onChange: (event: unknown, date?: Date) => void; testID?: string }) => (
    React.createElement(ReactNative.TouchableOpacity, {
      testID,
      onPress: () => onChange({ type: 'set' }, new Date(2026, 9, 20, 12, 0, 0)),
    })
  );
  return { ...ReactNative, __esModule: true, default: DatePicker };
});
jest.mock('../../../utils/formatters', () => ({
  formatDate: (date: Date) => {
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  },
}));

const schedulingPlan = {
  id: 'sched-plan-123',
  factoryId: 'F006',
  planDate: '2026-10-20',
  shiftType: 'day',
  totalWorkers: 2,
  totalEquipment: 1,
  estimatedOutput: 100,
  status: 'confirmed',
  createdBy: 7,
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-20T00:00:00Z',
};

function renderScreen() {
  mockGetPlans.mockResolvedValue({ success: true, data: { content: [schedulingPlan] } });
  return render(<AIRescheduleScreen />);
}

describe('AIRescheduleScreen scheduling-plan reason capture', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date(2026, 9, 20, 12, 0, 0));
    mockGetPlans.mockReset();
    mockGetPlan.mockReset();
    mockGetPlan.mockResolvedValue({ success: true, data: schedulingPlan });
    mockReschedule.mockReset();
    mockAiReschedule.mockReset();
    mockWhatIf.mockReset();
    (Alert.alert as jest.Mock).mockClear();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('loads a selected plan date instead of silently staying on today', async () => {
    jest.setSystemTime(new Date(2026, 9, 22, 12, 0, 0));
    mockGetPlans.mockImplementation(async ({ startDate }: { startDate: string }) => ({
      success: true,
      data: { content: startDate === '2026-10-20' ? [schedulingPlan] : [] },
    }));
    const view = render(<AIRescheduleScreen />);

    await waitFor(() => expect(mockGetPlans).toHaveBeenCalledWith({
      startDate: '2026-10-22',
      endDate: '2026-10-22',
      status: 'draft,confirmed,in_progress',
      page: 0,
      size: 20,
    }));
    expect(view.getByText('暂无可记录调整原因的计划')).toBeTruthy();
    fireEvent.press(view.getByTestId('schedule-date-selector'));
    fireEvent.press(view.getByTestId('schedule-date-picker'));

    await waitFor(() => expect(mockGetPlans).toHaveBeenCalledWith({
      startDate: '2026-10-20',
      endDate: '2026-10-20',
      status: 'draft,confirmed,in_progress',
      page: 0,
      size: 20,
    }));
    await waitFor(() => expect(view.getByTestId('scheduling-plan-sched-plan-123')).toBeTruthy());
    expect(view.getByTestId('selected-plan-date').props.children).toContain('2026-10-20');
  });

  it('disables empty submit and sends the selected scheduling id and trimmed reason once', async () => {
    const view = renderScreen();

    await waitFor(() => expect(view.getByText('2026-10-20')).toBeTruthy());
    expect(mockGetPlans).toHaveBeenCalledWith({
      startDate: '2026-10-20',
      endDate: '2026-10-20',
      status: 'draft,confirmed,in_progress',
      page: 0,
      size: 20,
    });
    expect(view.getByTestId('adjustment-reason-save').props.disabled).toBe(true);
    fireEvent.changeText(view.getByTestId('adjustment-reason-input'), '  设备维护  ');
    fireEvent.press(view.getByTestId('adjustment-reason-save'));

    await waitFor(() => expect(mockReschedule).toHaveBeenCalledTimes(1));
    expect(mockReschedule).toHaveBeenCalledWith({ planId: 'sched-plan-123', reason: '设备维护' });
    expect(mockReschedule.mock.calls[0][0]).not.toHaveProperty('strategy');
    expect(mockReschedule.mock.calls[0][0]).not.toHaveProperty('preserveAssignments');
    expect(mockGetPlan).toHaveBeenCalledWith('sched-plan-123');
    expect(mockAiReschedule).not.toHaveBeenCalled();
  });

  it('reports truthful success and refreshes the scheduling list without claiming a reschedule', async () => {
    mockReschedule.mockResolvedValue({ success: true, data: schedulingPlan });
    const view = renderScreen();

    await waitFor(() => expect(view.getByText('2026-10-20')).toBeTruthy());
    fireEvent.changeText(view.getByTestId('adjustment-reason-input'), '换班记录');
    fireEvent.press(view.getByTestId('adjustment-reason-save'));
    await waitFor(() => expect(mockReschedule).toHaveBeenCalledTimes(1));

    const successCall = (Alert.alert as jest.Mock).mock.calls.find(([title]) => title === '成功');
    expect(successCall?.[1]).toContain('排程时间和人员未变');
    expect(successCall?.[1]).not.toContain('重排成功');
    await act(async () => {
      mockGetPlans.mockResolvedValue({
        success: true,
        data: { content: [{ ...schedulingPlan, notes: '原备注\n调整原因: 换班记录' }] },
      });
      const action = (successCall?.[2] as Array<{ onPress?: () => void }> | undefined)?.[0];
      action?.onPress?.();
    });
    await waitFor(() => expect(mockGetPlans).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.getByTestId('selected-plan-notes').props.children.join('')).toContain('换班记录'));
  });

  it('keeps the entered reason after failure and disables plan switching while busy', async () => {
    let resolveRequest: ((value: unknown) => void) | undefined;
    mockReschedule.mockImplementation(() => new Promise((resolve) => { resolveRequest = resolve; }));
    const view = renderScreen();

    await waitFor(() => expect(view.getByText('2026-10-20')).toBeTruthy());
    fireEvent.changeText(view.getByTestId('adjustment-reason-input'), '网络恢复后补录');
    fireEvent.press(view.getByTestId('adjustment-reason-save'));
    await waitFor(() => expect(mockReschedule).toHaveBeenCalledTimes(1));
    expect(view.getByTestId('adjustment-reason-save').props.disabled).toBe(true);
    expect(view.getByTestId('scheduling-plan-sched-plan-123').props.disabled).toBe(true);
    fireEvent.press(view.getByTestId('adjustment-reason-save'));
    expect(mockReschedule).toHaveBeenCalledTimes(1);

    await act(async () => { resolveRequest?.({ success: false, message: '记录失败' }); });
    await waitFor(() => expect(Alert.alert).toHaveBeenCalledWith('失败', '记录失败'));
    expect(Alert.alert).not.toHaveBeenCalledWith('成功', expect.anything(), expect.anything());
    expect(view.getByTestId('adjustment-reason-input').props.value).toBe('网络恢复后补录');
  });

  it('filters terminal scheduling plans and performs no write for them', async () => {
    mockGetPlans.mockResolvedValue({
      success: true,
      data: {
        content: [schedulingPlan, { ...schedulingPlan, id: 'sched-terminal', status: 'completed' }],
      },
    });
    const view = render(<AIRescheduleScreen />);

    await waitFor(() => expect(view.getByTestId('scheduling-plan-sched-plan-123')).toBeTruthy());
    expect(view.queryByTestId('scheduling-plan-sched-terminal')).toBeNull();
    expect(mockReschedule).not.toHaveBeenCalled();
    expect(view.getByTestId('adjustment-reason-save').props.disabled).toBe(true);
  });

  it('shows a safe empty state with no write', async () => {
    mockGetPlans.mockResolvedValue({ success: true, data: { content: [] } });
    const view = render(<AIRescheduleScreen />);

    await waitFor(() => expect(view.getByText('暂无可记录调整原因的计划')).toBeTruthy());
    expect(view.getByTestId('adjustment-reason-save').props.disabled).toBe(true);
    expect(mockReschedule).not.toHaveBeenCalled();
    expect(mockAiReschedule).not.toHaveBeenCalled();
  });

  it.each([
    { ...schedulingPlan, status: 'completed' },
    { ...schedulingPlan, id: 'different-scheduling-plan' },
  ])('rejects a changed scheduling plan before sending a write', async (freshPlan) => {
    mockGetPlan.mockResolvedValue({ success: true, data: freshPlan });
    const view = renderScreen();
    await waitFor(() => expect(view.getByText('2026-10-20')).toBeTruthy());
    fireEvent.changeText(view.getByTestId('adjustment-reason-input'), '待核对原因');
    fireEvent.press(view.getByTestId('adjustment-reason-save'));
    await waitFor(() => expect(Alert.alert).toHaveBeenCalledWith('错误', '计划状态已变化，请刷新后重新选择。'));
    expect(mockReschedule).not.toHaveBeenCalled();
    expect(view.getByTestId('adjustment-reason-input').props.value).toBe('待核对原因');
  });

  it('shows a failed list request as an error rather than an empty plan list', async () => {
    mockGetPlans.mockResolvedValue({ success: false, message: '调度服务暂不可用' });
    const view = render(<AIRescheduleScreen />);
    await waitFor(() => expect(view.getByText('调度服务暂不可用')).toBeTruthy());
    expect(view.queryByText('暂无可记录调整原因的计划')).toBeNull();
    expect(mockReschedule).not.toHaveBeenCalled();
  });

  it('does not claim success when the write response identifies another plan', async () => {
    mockReschedule.mockResolvedValue({ success: true, data: { ...schedulingPlan, id: 'unrelated-plan' } });
    const view = renderScreen();
    await waitFor(() => expect(view.getByText('2026-10-20')).toBeTruthy());
    fireEvent.changeText(view.getByTestId('adjustment-reason-input'), '需核对回执');
    fireEvent.press(view.getByTestId('adjustment-reason-save'));
    await waitFor(() => expect(Alert.alert).toHaveBeenCalledWith('结果待核对', expect.stringContaining('避免重复保存')));
    expect(Alert.alert).not.toHaveBeenCalledWith('成功', expect.anything(), expect.anything());
    expect(mockReschedule).toHaveBeenCalledTimes(1);
    expect(view.getByTestId('adjustment-reason-input').props.value).toBe('需核对回执');
  });
});
