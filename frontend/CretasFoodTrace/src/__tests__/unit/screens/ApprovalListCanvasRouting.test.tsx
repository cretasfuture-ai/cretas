import React from 'react';
import { Alert } from 'react-native';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import ApprovalListScreen from '../../../screens/dispatcher/plan/ApprovalListScreen';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { myTodoApiClient, todoApprovalApiClient } from '../../../services/api/myTodoApiClient';

jest.mock('../../../services/api/schedulingApiClient', () => ({ schedulingApiClient: {
  getPendingApprovals: jest.fn(), approveForceInsert: jest.fn(), rejectForceInsert: jest.fn(),
} }));
jest.mock('../../../services/api/myTodoApiClient', () => ({
  myTodoApiClient: { getMyTodos: jest.fn() },
  todoApprovalApiClient: { oaAction: jest.fn() },
}));
jest.mock('expo-linear-gradient', () => ({ LinearGradient: require('react-native').View }));
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ goBack: jest.fn() }) }));
// 对话框本身不在本测试范围: 用两个按钮代替, 直接触发 onApprove / onReject
jest.mock('../../../components/approval/ApprovalConfirmDialog', () => {
  const { Text } = require('react-native');
  return {
    __esModule: true,
    default: ({ onApprove, onReject }: { onApprove: (c?: string) => Promise<void>; onReject: (r: string) => Promise<void> }) => (
      <>
        <Text onPress={() => onApprove('同意')}>do-approve</Text>
        <Text onPress={() => onReject('产能不够')}>do-reject</Text>
      </>
    ),
  };
});

const plan = (id: string, extra: Record<string, unknown> = {}) => ({
  id, planNumber: `PN-${id}`, productTypeId: 'PT', productTypeName: '猪蹄', plannedQuantity: 50,
  expectedCompletionDate: '2027-01-05', priority: 9, forceInsertReason: '客户急单', ...extra,
});

describe('强制插单审批列表: 走画布的计划只走 OA 动作入口', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    jest.mocked(schedulingApiClient.getPendingApprovals).mockResolvedValue({
      success: true, message: 'ok',
      data: [plan('direct'), plan('mine', { approvalWorkflowInstanceId: 'INST-M' }),
        plan('theirs', { approvalWorkflowInstanceId: 'INST-T' })],
    } as never);
    jest.mocked(myTodoApiClient.getMyTodos).mockResolvedValue({
      success: true,
      data: [{ type: 'FORCE_INSERT_APPROVAL', refId: 'mine', instanceId: 'INST-M', expectedNodeId: 'node-2' }],
    } as never);
    jest.mocked(todoApprovalApiClient.oaAction).mockResolvedValue({ success: true, data: {} } as never);
    jest.mocked(schedulingApiClient.approveForceInsert).mockResolvedValue({ success: true } as never);
    jest.mocked(schedulingApiClient.rejectForceInsert).mockResolvedValue({ success: true } as never);
  });
  afterEach(() => jest.restoreAllMocks());

  it('画布计划批准 → oaAction(实例, APPROVE, 我的节点, 意见), 不打直接审批入口', async () => {
    render(<ApprovalListScreen />);
    await waitFor(() => expect(screen.getByText('PN-mine')).toBeTruthy());
    fireEvent.press(screen.getByText('PN-mine'));
    fireEvent.press(screen.getByText('do-approve'));
    await waitFor(() => expect(todoApprovalApiClient.oaAction)
      .toHaveBeenCalledWith('INST-M', 'APPROVE', 'node-2', '同意'));
    expect(schedulingApiClient.approveForceInsert).not.toHaveBeenCalled();
  });

  it('画布计划驳回 → oaAction REJECT 且带驳回原因', async () => {
    render(<ApprovalListScreen />);
    await waitFor(() => expect(screen.getByText('PN-mine')).toBeTruthy());
    fireEvent.press(screen.getByText('PN-mine'));
    fireEvent.press(screen.getByText('do-reject'));
    await waitFor(() => expect(todoApprovalApiClient.oaAction)
      .toHaveBeenCalledWith('INST-M', 'REJECT', 'node-2', '产能不够'));
    expect(schedulingApiClient.rejectForceInsert).not.toHaveBeenCalled();
  });

  it('没有画布的计划仍走原直接审批入口', async () => {
    render(<ApprovalListScreen />);
    await waitFor(() => expect(screen.getByText('PN-direct')).toBeTruthy());
    fireEvent.press(screen.getByText('PN-direct'));
    fireEvent.press(screen.getByText('do-approve'));
    await waitFor(() => expect(schedulingApiClient.approveForceInsert).toHaveBeenCalledWith('direct', '同意'));
    expect(todoApprovalApiClient.oaAction).not.toHaveBeenCalled();
  });

  it('走画布但当前不是我的节点 → 不开对话框 (没有批准按钮), 说明去哪看', async () => {
    render(<ApprovalListScreen />);
    await waitFor(() => expect(screen.getByText('PN-theirs')).toBeTruthy());
    fireEvent.press(screen.getByText('PN-theirs'));
    expect(screen.queryByText('do-approve')).toBeNull();
    expect(Alert.alert).toHaveBeenCalledWith('这张插单走审批流程', expect.stringContaining('我的待办'));
    expect(screen.getAllByText('审批流程处理中')).toHaveLength(1);
  });
});
