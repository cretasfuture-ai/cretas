/**
 * 扫码作业页说明文字按模式写 (出库不再写成「跳入库录入页」) + 扫码出库不带计划字段的决定留痕。
 *
 * 第 2 条 (#1606 审查遗留「扫码出库页没有计划字段」) 的结论: 不加。
 * 扫码出库是仓管员对公司料的「通用出库」(后端 /material-batches/{id}/use, productionPlanId 可空);
 * 要绑生产计划的出库 (含客户来料) 走生产开的领料单, 仓管在网页「仓储管理 → 领料单」做。
 * 在一线仓管屏上加计划选择会破坏「只填一个数量」的闭环, 也会和领料单重复。
 * 这里用阳性对照钉住现状: 出库确认页只有数量输入, 提交时 useBatch 只带 (批次, 数量) 两个参数。
 */
import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';

import { scanSuccessTip } from '../../screens/warehouse/shared/WHScanOperationScreen';
import WHOutboundIssueScreen from '../../screens/warehouse/outbound/WHOutboundIssueScreen';

const mockAlert = jest.fn();
jest.mock('../../components/ui/AppDialog', () => ({ appAlert: (...a: unknown[]) => mockAlert(...a) }));
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({
    params: { batchId: 'MB-1', batchNumber: 'B-1', materialName: '猪肉', remainingQuantity: 50, quantityUnit: 'kg' },
  }),
  useNavigation: () => ({ goBack: jest.fn(), navigate: jest.fn() }),
}));
const mockUseBatch = jest.fn();
jest.mock('../../services/api/materialBatchApiClient', () => ({
  materialBatchApiClient: { useBatch: (...a: unknown[]) => mockUseBatch(...a) },
}));

describe('扫码作业页说明按模式', () => {
  it('出库模式写出库, 不出现「入库」', () => {
    const tip = scanSuccessTip('outbound');
    expect(tip).toContain('出库确认页');
    expect(tip).not.toContain('入库');
  });

  it('阳性对照: 入库模式仍写入库录入页', () => {
    expect(scanSuccessTip('inbound')).toContain('入库录入页');
  });
});

describe('扫码出库不带计划字段 (决定留痕)', () => {
  it('只有「出库数量」一个输入, 提交只带批次和数量', async () => {
    mockUseBatch.mockResolvedValue({ success: true });
    const screen = render(<WHOutboundIssueScreen />);
    expect(screen.queryByText(/计划/)).toBeNull();
    expect(screen.getByText('出库数量')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('wh-outbound-quantity'), '10');
    fireEvent.press(screen.getByTestId('wh-outbound-submit'));
    const confirm = mockAlert.mock.calls.find((c) => c[0] === '确认出库');
    expect(confirm).toBeTruthy();
    const confirmBtn = (confirm![2] as Array<{ text: string; onPress?: () => Promise<void> }>)
      .find((b) => b.text === '确定出库');
    await confirmBtn!.onPress!();
    await waitFor(() => expect(mockUseBatch).toHaveBeenCalledTimes(1));
    expect(mockUseBatch.mock.calls[0]).toEqual(['MB-1', 10]);
  });
});
