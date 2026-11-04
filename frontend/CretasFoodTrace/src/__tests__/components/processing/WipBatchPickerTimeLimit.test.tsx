/**
 * 工序时限 (缺口 14) —— 报工屏的上道半成品选择器:
 *  - 全部超时: 行点不动, 写明找质检员 + 重新加载, 且【不】触发 onEmpty;
 *  - 按笔部分超时: 可领写能领的量, 另一行写超时量, 投入上限按能领的量, 和待检同时存在取较小;
 *  - 倒计时每分钟按 deadlineAt 重算; 暂停中不倒数;
 *  - 状态读失败: 行照常可选, 一句说明;
 *  - 阴性对照: 不传 taskId 不读状态。
 */
import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import WipBatchPicker, { wipDrawableQuantity } from '../../../components/processing/WipBatchPicker';
import { yieldReportApi, WipRowDTO } from '../../../services/api/yieldReportApi';
import { processTimeLimitApi, ProcessTimeLimitStatus } from '../../../services/api/processTimeLimitApi';
import {
  backendActionHint,
  formatDuration,
  parseBackendTime,
  remainingMinutesAt,
  timeLimitBlockReason,
  timeLimitLine,
} from '../../../utils/processTimeLimit';

jest.mock('../../../services/api/yieldReportApi', () => {
  const actual = jest.requireActual('../../../services/api/yieldReportApi');
  return { ...actual, yieldReportApi: { listWip: jest.fn() } };
});
jest.mock('../../../services/api/processTimeLimitApi', () => ({
  processTimeLimitApi: { getTaskStatuses: jest.fn() },
}));

const mockedWip = yieldReportApi as jest.Mocked<typeof yieldReportApi>;
const mockedLimits = processTimeLimitApi as jest.Mocked<typeof processTimeLimitApi>;

/** 2026-11-04 10:00 东八区。 */
const NOW = Date.UTC(2026, 10, 4, 2, 0, 0);

function row(no: string, extra: Partial<WipRowDTO> = {}): WipRowDTO {
  return {
    intermediateBatchNo: no,
    sourceWorkProcessTaskId: 1,
    processOrder: 1,
    processName: '焯水',
    productTypeId: 'PT-1',
    producedQuantity: 50,
    consumedQuantity: 0,
    availableQuantity: 50,
    unit: 'kg',
    status: 'AVAILABLE',
    ...extra,
  };
}

function status(batchNumber: string, extra: Partial<ProcessTimeLimitStatus> = {}): ProcessTimeLimitStatus {
  return {
    targetType: 'SEMI_FINISHED',
    targetId: '1',
    batchNumber,
    workflowPortId: 'in-1',
    processName: '卤制',
    limitMinutes: 120,
    startEvent: 'OUTPUT_POSTED',
    state: 'OK',
    startAt: '2026-11-04T09:00:00',
    deadlineAt: '2026-11-04T11:20:30',
    remainingMinutes: 80,
    reason: null,
    balance: 50,
    expiredQuantity: 0,
    drawableQuantity: 50,
    unit: 'kg',
    paused: false,
    perLot: false,
    ...extra,
  };
}

const expired = (no: string) => status(no, {
  state: 'EXPIRED', deadlineAt: '2026-11-04T09:25:30', remainingMinutes: -35, expiredQuantity: 50, drawableQuantity: 0,
});

function givenRows(rows: WipRowDTO[], statuses: ProcessTimeLimitStatus[]) {
  mockedWip.listWip.mockResolvedValue({ success: true, data: rows } as never);
  mockedLimits.getTaskStatuses.mockResolvedValue({ success: true, data: statuses } as never);
}

describe('时限文字', () => {
  it('后端时间按东八区读, 不看手机时区; 带小数秒和空格分隔都能读', () => {
    expect(parseBackendTime('2026-11-04T10:00:00')).toBe(NOW);
    expect(parseBackendTime('2026-11-04T10:00:00.123456')).toBe(NOW);
    expect(parseBackendTime('2026-11-04 10:00:00')).toBe(NOW);
    expect(parseBackendTime(null)).toBeNull();
  });

  it('时长写法', () => {
    expect(formatDuration(35)).toBe('35 分钟');
    expect(formatDuration(80)).toBe('1 小时 20 分');
    expect(formatDuration(-120)).toBe('2 小时');
  });

  it('暂停中不按截止时间倒数, 用后端读数', () => {
    const paused = status('W', { paused: true, deadlineAt: '2026-11-04T09:00:00', remainingMinutes: 40 });
    expect(remainingMinutesAt(paused, NOW)).toBe(40);
    expect(timeLimitLine(paused, NOW)).toBe('还剩 40 分钟（冷藏暂停中，暂不计时）');
  });

  it('快到时限的文字自带意思; 本地过了截止时间不再说还剩', () => {
    expect(timeLimitLine(status('W', { state: 'WARNING', deadlineAt: '2026-11-04T10:10:00' }), NOW))
      .toBe('还剩 10 分钟，快到时限');
    expect(timeLimitLine(status('W', { deadlineAt: '2026-11-04T09:59:00' }), NOW))
      .toBe('时限已到，能不能领以提交结果为准');
  });

  it('超时原因带时长; 没超时不挡', () => {
    expect(timeLimitBlockReason(expired('W'), NOW)).toBe('已超时 35 分钟，请找质检员处置');
    expect(timeLimitBlockReason(status('W'), NOW)).toBeNull();
    expect(timeLimitBlockReason(undefined, NOW)).toBeNull();
  });

  it('待检和超时同时有: 取较小的量', () => {
    const partial = status('W', { state: 'EXPIRED', perLot: true, expiredQuantity: 10, drawableQuantity: 40 });
    const held = row('W', { inspectionStatus: 'PENDING', inspectionPendingQuantity: 20 });
    expect(wipDrawableQuantity(held, partial)).toBe(30);
    expect(wipDrawableQuantity(row('W'), partial)).toBe(40);
  });

  it('后端下一步提示读 actionHint, 旧 hint 兜底', () => {
    expect(backendActionHint({ actionHint: '请通知质检员' })).toBe('请通知质检员');
    expect(backendActionHint({ hint: '旧' })).toBe('旧');
    expect(backendActionHint(undefined)).toBe('');
  });
});

describe('WipBatchPicker 工序时限', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers({ now: NOW });
  });
  afterEach(() => jest.useRealTimers());

  const mount = (props: Partial<React.ComponentProps<typeof WipBatchPicker>> = {}) => render(
    <WipBatchPicker batchId={1} factoryId="F006" taskId={9} selectedSourceWipNo={null} onChange={jest.fn()} {...props} />,
  );

  it('全部超时: 行内写原因, 点不动, 提示找质检员, 重新加载再读一次, 不触发 onEmpty', async () => {
    givenRows([row('W-1')], [expired('W-1')]);
    const onChange = jest.fn();
    const onEmpty = jest.fn();
    mount({ onChange, onEmpty });

    expect(await screen.findByText('已超时 35 分钟，请找质检员处置')).toBeTruthy();
    fireEvent.press(screen.getByTestId('wip-row-W-1'));
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByTestId('wip-all-pending-hint')).toBeTruthy();
    expect(screen.getByText('请通知质检员处置，处置后点重新加载')).toBeTruthy();
    expect(onEmpty).not.toHaveBeenCalled();

    fireEvent.press(screen.getByText('重新加载'));
    await act(async () => { await Promise.resolve(); });
    expect(mockedLimits.getTaskStatuses).toHaveBeenCalledTimes(2);
  });

  it('按笔部分超时: 可领写能领的量, 另一行写超时量, 投入上限给能领的量', async () => {
    givenRows([row('W-2')], [status('W-2', { state: 'EXPIRED', perLot: true, expiredQuantity: 15, drawableQuantity: 35 })]);
    const onChange = jest.fn();
    mount({ onChange });

    expect(await screen.findByText('可领 35 kg')).toBeTruthy();
    expect(screen.getByTestId('wip-row-expired-W-2').props.children.join('')).toBe('另有 15 kg 已超时');
    fireEvent.press(screen.getByTestId('wip-row-W-2'));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ sourceWipNo: 'W-2', availableQuantity: 35 }));
  });

  it('还剩时间每分钟按截止时间重算', async () => {
    givenRows([row('W-3')], [status('W-3')]);
    mount();
    expect(await screen.findByText('还剩 1 小时 20 分')).toBeTruthy();
    await act(async () => { jest.advanceTimersByTime(60_000); });
    expect(screen.getByText('还剩 1 小时 19 分')).toBeTruthy();
  });

  it('到点再问一次后端, 已选的这笔不能领了就撤掉选择', async () => {
    givenRows([row('W-4')], [status('W-4', { deadlineAt: '2026-11-04T10:00:30' })]);
    const onChange = jest.fn();
    mount({ onChange, selectedSourceWipNo: 'W-4' });
    await screen.findByTestId('wip-row-W-4');
    onChange.mockClear();

    mockedLimits.getTaskStatuses.mockResolvedValue({ success: true, data: [expired('W-4')] } as never);
    await act(async () => { jest.advanceTimersByTime(60_000); });
    expect(mockedLimits.getTaskStatuses).toHaveBeenCalledTimes(2);
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it('还没起算: 显示原因, 可选', async () => {
    givenRows([row('W-5')], [status('W-5', {
      state: 'NOT_STARTED', deadlineAt: null, startAt: null, remainingMinutes: null, reason: '还没有开始计时（上道完工后开始算）',
    })]);
    const onChange = jest.fn();
    mount({ onChange });
    expect(await screen.findByText('还没有开始计时（上道完工后开始算）')).toBeTruthy();
    fireEvent.press(screen.getByTestId('wip-row-W-5'));
    expect(onChange).toHaveBeenCalled();
  });

  it('状态读失败: 行照常可选, 写一句说明', async () => {
    mockedWip.listWip.mockResolvedValue({ success: true, data: [row('W-6')] } as never);
    mockedLimits.getTaskStatuses.mockRejectedValue(new Error('boom'));
    const onChange = jest.fn();
    mount({ onChange });
    expect(await screen.findByTestId('wip-time-limit-unavailable')).toBeTruthy();
    fireEvent.press(screen.getByTestId('wip-row-W-6'));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ sourceWipNo: 'W-6', availableQuantity: 50 }));
  });

  it('不传 taskId: 不读状态', async () => {
    givenRows([row('W-7')], []);
    mount({ taskId: undefined });
    await screen.findByTestId('wip-row-W-7');
    expect(mockedLimits.getTaskStatuses).not.toHaveBeenCalled();
  });

  it('父组件重新渲染 (内联 onEmpty 换了引用) 不重新加载', async () => {
    givenRows([row('W-8')], [status('W-8')]);
    const view = mount({ onEmpty: () => undefined });
    await screen.findByTestId('wip-row-W-8');
    view.rerender(
      <WipBatchPicker batchId={1} factoryId="F006" taskId={9} selectedSourceWipNo={null} onChange={jest.fn()} onEmpty={() => undefined} />,
    );
    await act(async () => { await Promise.resolve(); });
    expect(mockedWip.listWip).toHaveBeenCalledTimes(1);
  });
});
