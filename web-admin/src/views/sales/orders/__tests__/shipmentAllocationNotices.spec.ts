/**
 * Q3 (2026-11-09): 子发运单建成、但有行没能自动分配批次时, 后端在响应 allocationNotices 里逐行说明原因。
 * 这里钉住: 读得出 / 读不出时是空 / 自动全量子单把原因交给调用方 / 规格不一致时指向确认换算入口。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

const postMock = vi.fn();
vi.mock('@/api/request', () => ({
  post: (...args: unknown[]) => postMock(...args),
  get: vi.fn(),
}));

import {
  allocationNoticeLines,
  allocationNoticesOf,
  needsPackagingConfirmation,
} from '../shipmentAllocationNotices';
import { createAutoFullChildShipment } from '../deliveryAutoChild';

const MISMATCH = {
  deliveryItemId: 501,
  productName: '卤猪蹄 200g',
  code: 'BATCH_PACKAGING_SPEC_MISMATCH',
  message: '成品批次 FG-1 的规格（1箱=10袋）与发货行规格（1箱=12袋）不一致，系统不会自动换算',
  hint: '请在「分配批次」里核对换算后确认',
};

describe('allocationNoticesOf', () => {
  it('reads every well-formed notice and normalises the line id to a string', () => {
    const notices = allocationNoticesOf({ id: 'S1', allocationNotices: [MISMATCH, { junk: true }] });
    expect(notices).toHaveLength(1);
    expect(notices[0].deliveryItemId).toBe('501');
    expect(needsPackagingConfirmation(notices)).toBe(true);
    expect(allocationNoticeLines(notices)[0]).toBe(
      '卤猪蹄 200g：成品批次 FG-1 的规格（1箱=10袋）与发货行规格（1箱=12袋）不一致，系统不会自动换算'
      + '（请在「分配批次」里核对换算后确认）',
    );
  });

  it('is empty when the response has no notices (all lines allocated)', () => {
    expect(allocationNoticesOf({ id: 'S1' })).toEqual([]);
    expect(allocationNoticesOf(null)).toEqual([]);
  });

  it('a plain stock shortfall is not a conversion to confirm', () => {
    const notices = allocationNoticesOf({ allocationNotices: [{ ...MISMATCH, code: 'FIFO_STOCK_SHORTFALL' }] });
    expect(needsPackagingConfirmation(notices)).toBe(false);
  });
});

describe('createAutoFullChildShipment → onAllocationNotices', () => {
  const base = {
    factoryId: 'F006',
    masterId: 'DLV-9',
    masterItems: [{ id: 101, salesOrderItemId: 'SOI-1' }],
    plannedItems: [{ salesOrderItemId: 'SOI-1', deliveredQuantity: 3 }],
    deliveryMethod: 'LOGISTICS',
    logisticsCompany: '',
    trackingNumber: '',
    deliveryAddress: '仓库A',
  };

  beforeEach(() => postMock.mockReset());

  it('hands the backend notices to the caller when the child was created with unallocated lines', async () => {
    postMock.mockResolvedValue({ success: true, data: { id: 'S1', allocationNotices: [MISMATCH] } });
    const seen = vi.fn();

    const ok = await createAutoFullChildShipment({ ...base, onAllocationNotices: seen });

    expect(ok).toBe(true);
    expect(seen).toHaveBeenCalledTimes(1);
    expect(seen.mock.calls[0][0][0].code).toBe('BATCH_PACKAGING_SPEC_MISMATCH');
  });

  it('does not call back when every line was allocated', async () => {
    postMock.mockResolvedValue({ success: true, data: { id: 'S1', allocationNotices: [] } });
    const seen = vi.fn();

    await createAutoFullChildShipment({ ...base, onAllocationNotices: seen });

    expect(seen).not.toHaveBeenCalled();
  });
});
