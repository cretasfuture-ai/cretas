/**
 * 扫码出库确认页 —— 客户来料提前说明走领料单 (#1606 后续, Steve 2026-11-12 选 B)。
 *
 * 对着真实的 WHOutboundIssueScreen 跑, 只桩掉导航与 API 客户端。
 * 阴性 (客户来料): 不出现数量输入和「确认出库」, 出现说明与「返回继续扫码」, 点了不调出库接口。
 * 阳性对照 (公司料): 数量输入和「确认出库」照常出现 —— 证明阴性那条不是整屏没渲染。
 */
import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';

import WHOutboundIssueScreen, {
  CUSTOMER_OWNED_OUTBOUND_HINT,
  CUSTOMER_OWNED_OUTBOUND_TITLE,
} from '../../screens/warehouse/outbound/WHOutboundIssueScreen';

const mockGoBack = jest.fn();
let mockParams: Record<string, unknown> = {};

jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: mockParams }),
  useNavigation: () => ({ goBack: mockGoBack, navigate: jest.fn() }),
}));

const mockUseBatch = jest.fn();
jest.mock('../../services/api/materialBatchApiClient', () => ({
  materialBatchApiClient: { useBatch: (...args: unknown[]) => mockUseBatch(...args) },
}));

function params(ownership: string | undefined) {
  return {
    batchId: 'MB-1',
    batchNumber: 'QA-B13-CUST-01',
    materialName: '客户猪肉',
    remainingQuantity: 50,
    quantityUnit: 'kg',
    ownership,
  };
}

describe('WHOutboundIssueScreen 客户来料', () => {
  beforeEach(() => {
    mockGoBack.mockReset();
    mockUseBatch.mockReset();
  });

  it('客户来料: 填数量之前就说明走领料单, 没有数量输入和确认出库', () => {
    mockParams = params('CUSTOMER_OWNED');
    const screen = render(<WHOutboundIssueScreen />);

    // 必发生的读数: 批次身份已渲染
    expect(screen.getByText('QA-B13-CUST-01')).toBeTruthy();
    expect(screen.getByText(CUSTOMER_OWNED_OUTBOUND_TITLE)).toBeTruthy();
    expect(screen.getByText(CUSTOMER_OWNED_OUTBOUND_HINT)).toBeTruthy();
    expect(CUSTOMER_OWNED_OUTBOUND_HINT).toContain('仓储管理 → 领料单');
    expect(CUSTOMER_OWNED_OUTBOUND_HINT).toContain('确认领料');
    expect(CUSTOMER_OWNED_OUTBOUND_HINT).toContain('联系生产计划员先开单');
    expect(screen.queryByTestId('wh-outbound-submit')).toBeNull();
    expect(screen.queryByText('出库数量')).toBeNull();

    fireEvent.press(screen.getByTestId('wh-outbound-back-to-scan'));
    expect(mockGoBack).toHaveBeenCalledTimes(1);
    expect(mockUseBatch).not.toHaveBeenCalled();
  });

  it('阳性对照: 公司料照常显示数量输入和确认出库, 不出现客户来料说明', () => {
    mockParams = params('COMPANY_OWNED');
    const screen = render(<WHOutboundIssueScreen />);

    expect(screen.getByText('QA-B13-CUST-01')).toBeTruthy();
    expect(screen.getByText('出库数量')).toBeTruthy();
    expect(screen.getByTestId('wh-outbound-submit')).toBeTruthy();
    expect(screen.queryByText(CUSTOMER_OWNED_OUTBOUND_TITLE)).toBeNull();
  });

  it('旧标签响应没有归属字段: 按原流程 (由后端 409 兜底)', () => {
    mockParams = params(undefined);
    const screen = render(<WHOutboundIssueScreen />);
    expect(screen.getByTestId('wh-outbound-submit')).toBeTruthy();
  });
});
