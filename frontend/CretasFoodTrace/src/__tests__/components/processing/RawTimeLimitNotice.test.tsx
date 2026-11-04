import React from 'react';
import { render, waitFor } from '@testing-library/react-native';
import { RawTimeLimitNotice } from '../../../components/processing/RawTimeLimitNotice';
import { processTimeLimitApi, ProcessTimeLimitStatus } from '../../../services/api/processTimeLimitApi';

jest.mock('../../../services/api/processTimeLimitApi', () => ({
  processTimeLimitApi: { getTaskStatuses: jest.fn() },
}));

const getTaskStatuses = processTimeLimitApi.getTaskStatuses as jest.Mock;

function status(overrides: Partial<ProcessTimeLimitStatus>): ProcessTimeLimitStatus {
  return {
    targetType: 'RAW_MATERIAL_BATCH', targetId: 'A', batchNumber: 'MB-A', workflowPortId: 'in', processName: '蒸煮',
    limitMinutes: 120, startEvent: 'THAW_MARKED', state: 'OK', startAt: null, deadlineAt: null,
    remainingMinutes: 80, reason: null, balance: 10, expiredQuantity: 0, drawableQuantity: 10, unit: 'kg',
    paused: false, perLot: false, pauseMarkId: null, ...overrides,
  };
}

describe('RawTimeLimitNotice', () => {
  beforeEach(() => getTaskStatuses.mockReset());

  it('没配时限的工序不渲染', async () => {
    getTaskStatuses.mockResolvedValue({ success: true, data: [] });
    const { queryByTestId } = render(<RawTimeLimitNotice taskId={1} />);
    await waitFor(() => expect(getTaskStatuses).toHaveBeenCalled());
    expect(queryByTestId('raw-time-limit-notice')).toBeNull();
  });

  it('半成品的状态不混进原料提示', async () => {
    getTaskStatuses.mockResolvedValue({ success: true, data: [status({ targetType: 'SEMI_FINISHED' })] });
    const { queryByTestId } = render(<RawTimeLimitNotice taskId={1} />);
    await waitFor(() => expect(getTaskStatuses).toHaveBeenCalled());
    expect(queryByTestId('raw-time-limit-notice')).toBeNull();
  });

  it('超时写清原因和找谁, 还没解冻写清要登记', async () => {
    getTaskStatuses.mockResolvedValue({
      success: true,
      data: [
        status({ state: 'EXPIRED', remainingMinutes: -35, expiredQuantity: 10, drawableQuantity: 0 }),
        status({ targetId: 'B', batchNumber: 'MB-B', state: 'START_MISSING', reason: '还没有登记开始解冻，不能投料' }),
      ],
    });
    const { findByTestId } = render(<RawTimeLimitNotice taskId={1} />);
    expect((await findByTestId('raw-time-limit-A')).props.children).toContain('已超时 35 分钟，请找质检员处置');
    expect((await findByTestId('raw-time-limit-B')).props.children).toBe('还没有登记开始解冻，不能投料');
  });

  it('加载失败不装作没有时限', async () => {
    getTaskStatuses.mockRejectedValue(new Error('net'));
    const { findByTestId } = render(<RawTimeLimitNotice taskId={1} />);
    expect(await findByTestId('raw-time-limit-load-failed')).toBeTruthy();
  });
});
