import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { DispositionSuggestion } from '../../../components/quality/DispositionSuggestion';
import { qualityDispositionAPI } from '../../../services/api/qualityDispositionApiClient';
import {
  DispositionAction,
  DispositionStatus,
  requiresApproval,
} from '../../../types/qualityDisposition';

jest.mock('../../../store/authStore', () => ({
  useAuthStore: () => ({
    user: { id: 1558, factoryId: 'F006', role: 'quality_inspector' },
  }),
}));

jest.mock('../../../services/api/qualityDispositionApiClient', () => ({
  qualityDispositionAPI: {
    evaluateDisposition: jest.fn(),
    executeDisposition: jest.fn(),
  },
}));

const mockedApi = qualityDispositionAPI as jest.Mocked<typeof qualityDispositionAPI>;

describe('DispositionSuggestion 跨平台提交反馈', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedApi.evaluateDisposition.mockResolvedValue({
      inspectionId: 'qi-1',
      productionBatchId: 10806,
      recommendedAction: DispositionAction.REWORK,
      recommendedActionDescription: '返工',
      requiresApproval: true,
      triggeredRuleName: '返工审批',
      confidence: 85,
      reason: '不合格批次需要返工',
      alternativeActions: [],
      inspectionSummary: {
        passRate: 0,
        defectRate: 100,
        inspectionResult: 'FAIL',
        qualityGrade: 'D',
        sampleSize: 2,
        passCount: 0,
        failCount: 2,
      },
    });
    mockedApi.executeDisposition.mockResolvedValue({
      dispositionId: 'approval-1',
      status: DispositionStatus.PENDING_APPROVAL,
      executedAction: DispositionAction.REWORK,
      message: '处置请求已提交，等待审批',
      newBatchStatus: 'PENDING_APPROVAL',
      approvalInitiated: true,
      approvalRequestId: 'approval-1',
      executedAt: '2026-08-25T02:10:00',
    });
  });

  it('返工提交后持久显示待审批、单据号和库存未变更说明', async () => {
    render(
      <DispositionSuggestion
        batchId={10806}
        inspectionId="qi-1"
        qualityScore={0}
      />
    );

    await screen.findByText('AI 处置建议');
    const submitButtons = screen.getAllByText('提交审批');
    fireEvent.press(submitButtons[0]);
    fireEvent.press(screen.getAllByText('提交审批').slice(-1)[0]);

    await waitFor(() => expect(mockedApi.executeDisposition).toHaveBeenCalledWith(
      'F006',
      expect.objectContaining({
        batchId: 10806,
        inspectionId: 'qi-1',
        actionCode: DispositionAction.REWORK,
        executorId: 1558,
      })
    ));
    expect(await screen.findByText('处置申请已提交')).toBeTruthy();
    expect(screen.getByText('单据编号：approval-1')).toBeTruthy();
    expect(screen.getByText('当前状态：待审批，审批通过前不会变更库存')).toBeTruthy();
  });

  it('前端动作审批真值与后端一致：只有直接放行默认免审批', () => {
    expect(requiresApproval(DispositionAction.RELEASE)).toBe(false);
    expect(requiresApproval(DispositionAction.CONDITIONAL_RELEASE)).toBe(true);
    expect(requiresApproval(DispositionAction.REWORK)).toBe(true);
    expect(requiresApproval(DispositionAction.SCRAP)).toBe(true);
    expect(requiresApproval(DispositionAction.SPECIAL_APPROVAL)).toBe(true);
    expect(requiresApproval(DispositionAction.HOLD)).toBe(true);
  });
});
