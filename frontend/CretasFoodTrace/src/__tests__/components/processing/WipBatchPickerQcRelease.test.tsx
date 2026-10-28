/**
 * 质检放行 (缺口 11) —— 报工屏的上道半成品选择器:
 *  - 待检 / 不合格行照样列出, 但点不动, 行内写明原因;
 *  - 全部待检时给出路 (找质检员 + 重新加载), 且【不】触发 onEmpty (那会放开主管兜底, 把待检料绕过去);
 *  - 阴性对照: 没开需检 (inspectionStatus 缺省/null) 的行照常可选;
 *  - 审查高 2 / 低 11: 一行里只有一部分待检 → 可选, 「可领」只算放行了的那部分, 投入上限也按它。
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import WipBatchPicker, { wipFreeQuantity, wipQcBlockReason } from '../../../components/processing/WipBatchPicker';
import { yieldReportApi, WipRowDTO } from '../../../services/api/yieldReportApi';

jest.mock('../../../services/api/yieldReportApi', () => {
  const actual = jest.requireActual('../../../services/api/yieldReportApi');
  return {
    ...actual,
    yieldReportApi: { listWip: jest.fn() },
  };
});

const mockedApi = yieldReportApi as jest.Mocked<typeof yieldReportApi>;

function row(no: string, inspectionStatus?: WipRowDTO['inspectionStatus']): WipRowDTO {
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
    inspectionStatus,
  };
}

describe('WipBatchPicker 质检放行', () => {
  beforeEach(() => jest.clearAllMocks());

  it('原因文案: 待检 / 不合格 / 未需检', () => {
    expect(wipQcBlockReason({ inspectionStatus: 'PENDING' })).toContain('待检');
    expect(wipQcBlockReason({ inspectionStatus: 'REJECTED' })).toContain('不合格');
    expect(wipQcBlockReason({ inspectionStatus: 'RELEASED' })).toBeNull();
    expect(wipQcBlockReason({ inspectionStatus: null })).toBeNull();
    expect(wipQcBlockReason({})).toBeNull();
  });

  it('待检行显示原因且点不动; 未需检行可选', async () => {
    mockedApi.listWip.mockResolvedValue({ success: true, data: [row('W-PEND', 'PENDING'), row('W-OK')] } as never);
    const onChange = jest.fn();
    render(<WipBatchPicker batchId={1} factoryId="F006" selectedSourceWipNo={null} onChange={onChange} />);

    expect(await screen.findByTestId('wip-row-blocked-W-PEND')).toBeTruthy();
    expect(screen.getByText('待检，质检员放行后才能领用')).toBeTruthy();

    fireEvent.press(screen.getByTestId('wip-row-W-PEND'));
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.press(screen.getByTestId('wip-row-W-OK'));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ sourceWipNo: 'W-OK' }));
  });

  it('部分待检 (50 里 20 待检): 可选, 显示可领 30 和另有 20 待检, 投入上限给 30', async () => {
    const partial = { ...row('W-PART', 'PENDING'), inspectionPendingQuantity: 20 };
    expect(wipFreeQuantity(partial)).toBe(30);
    expect(wipQcBlockReason(partial)).toBeNull();
    // 旧数据: 待检但没给数量 → 整行都算待检
    expect(wipFreeQuantity(row('W-OLD', 'PENDING'))).toBe(0);
    mockedApi.listWip.mockResolvedValue({ success: true, data: [partial] } as never);
    const onChange = jest.fn();
    render(<WipBatchPicker batchId={1} factoryId="F006" selectedSourceWipNo={null} onChange={onChange} />);

    expect(await screen.findByTestId('wip-row-pending-W-PART')).toBeTruthy();
    expect(screen.getByText('可领 30 kg')).toBeTruthy();
    expect(screen.queryByTestId('wip-row-blocked-W-PART')).toBeNull();
    fireEvent.press(screen.getByTestId('wip-row-W-PART'));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ sourceWipNo: 'W-PART', availableQuantity: 30 }));
  });

  it('全部待检: 提示找质检员放行, 不触发 onEmpty', async () => {
    mockedApi.listWip.mockResolvedValue({ success: true, data: [row('W-P1', 'PENDING')] } as never);
    const onEmpty = jest.fn();
    render(
      <WipBatchPicker batchId={1} factoryId="F006" selectedSourceWipNo={null} onChange={jest.fn()} onEmpty={onEmpty} />,
    );
    expect(await screen.findByTestId('wip-all-pending-hint')).toBeTruthy();
    expect(screen.getByText('请通知质检员放行，放行后点「重新加载」')).toBeTruthy();
    await waitFor(() => expect(mockedApi.listWip).toHaveBeenCalled());
    expect(onEmpty).not.toHaveBeenCalled();
  });
});
