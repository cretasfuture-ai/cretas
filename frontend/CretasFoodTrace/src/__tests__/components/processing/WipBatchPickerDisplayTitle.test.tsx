/**
 * 报工第 2 道「上道半成品有 N 笔」选择列表的行标题 (2026-11-12 真机验收 op-07):
 * 一笔显示成 `553ab0a6-…-B11154-S1-2313` —— 这是后端没有人起的批次号时拼的内部键
 * `{产品类型 ID}-B{生产批次 ID}-S{第几道}-{任务 ID}` (WipInventoryServiceImpl#generateBatchNo)。
 * 钉住: 人起的批次号原样; 内部键且是本屏这一批 → 本批批次号 + 第几道; 对不上 → 「批次号没查到」, 不编;
 * 选中 / 提交仍回传原来的 intermediateBatchNo。
 */
import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import WipBatchPicker, { wipDisplayTitle } from '../../../components/processing/WipBatchPicker';
import { yieldReportApi, WipRowDTO } from '../../../services/api/yieldReportApi';

jest.mock('../../../services/api/yieldReportApi', () => {
  const actual = jest.requireActual('../../../services/api/yieldReportApi');
  return { ...actual, yieldReportApi: { listWip: jest.fn() } };
});
jest.mock('../../../services/api/processTimeLimitApi', () => ({
  processTimeLimitApi: { getTaskStatuses: jest.fn() },
}));

const mockedWip = yieldReportApi as jest.Mocked<typeof yieldReportApi>;

const SYSTEM_KEY = '553ab0a6-0b0c-41a3-ab58-c5a792711641-B11154-S1-2313';

function row(no: string, extra: Partial<WipRowDTO> = {}): WipRowDTO {
  return {
    intermediateBatchNo: no,
    sourceWorkProcessTaskId: 2313,
    processOrder: 1,
    processName: 'QA-S3-1104-腌制',
    productTypeId: '553ab0a6-0b0c-41a3-ab58-c5a792711641',
    producedQuantity: 0.1,
    consumedQuantity: 0,
    availableQuantity: 0.1,
    unit: 'kg',
    status: 'AVAILABLE',
    ...extra,
  };
}

describe('wipDisplayTitle', () => {
  it('人起的批次号原样显示', () => {
    expect(wipDisplayTitle(row('QA-V15-14-SEMI'), { batchId: 11154, batchNumber: 'PB-1' }))
      .toEqual({ title: 'QA-V15-14-SEMI', isSystemKey: false });
  });

  it('内部键且生产批次就是本屏这一批: 本批批次号 + 第几道产出', () => {
    expect(wipDisplayTitle(row(SYSTEM_KEY), { batchId: 11154, batchNumber: 'PB-PLAN-1794315754383-106830FC-11154' }))
      .toEqual({ title: 'PB-PLAN-1794315754383-106830FC-11154 · 第 1 道产出', isSystemKey: true });
  });

  it('内部键但不是本屏这一批: 如实写「批次号没查到」, 不拿本批批次号顶替', () => {
    expect(wipDisplayTitle(row(SYSTEM_KEY), { batchId: 99999, batchNumber: 'PB-OTHER' }))
      .toEqual({ title: '批次号没查到 · 第 1 道产出', isSystemKey: true });
    expect(wipDisplayTitle(row(SYSTEM_KEY, { processOrder: null }), {}).title).toBe('批次号没查到 · 第 1 道产出');
  });

  it('本批批次号还没读到时也不编', () => {
    expect(wipDisplayTitle(row(SYSTEM_KEY), { batchId: 11154, batchNumber: '' }).title).toBe('批次号没查到 · 第 1 道产出');
  });
});

describe('选择列表上的标题 (真机样本)', () => {
  beforeEach(() => {
    mockedWip.listWip.mockResolvedValue({ success: true, data: [row(SYSTEM_KEY), row('QA-V15-14-SEMI')] } as never);
  });

  it('不再显示内部串; 选中后回传的仍是原来的 sourceWipNo', async () => {
    const onChange = jest.fn();
    render(
      <WipBatchPicker batchId={88888} batchNumber="PB-THIS" selectedSourceWipNo={null} onChange={onChange} />,
    );
    expect(await screen.findByTestId(`wip-row-title-${SYSTEM_KEY}`)).toHaveTextContent('批次号没查到 · 第 1 道产出');
    expect(screen.getByTestId('wip-row-title-QA-V15-14-SEMI')).toHaveTextContent('QA-V15-14-SEMI');
    expect(screen.queryByText(SYSTEM_KEY)).toBeNull();

    fireEvent.press(screen.getByTestId(`wip-row-${SYSTEM_KEY}`));
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ sourceWipNo: SYSTEM_KEY }));
  });
});
