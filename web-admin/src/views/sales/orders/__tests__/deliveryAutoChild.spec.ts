/**
 * 缺陷40 — 「一次性全量发运」母单创建后自动建子发运单, detail.vue 与 list.vue 共用同一个
 * helper (硬约束 8: 共享结构只改一处会漂移)。真实行为测试 (不是 source-string 扫描) ——
 * mock POST, 断言真正调用了哪个 URL、带什么 body, 以及 FULL 判定的边界条件。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';

const postMock = vi.fn();
vi.mock('@/api/request', () => ({
  post: (...args: unknown[]) => postMock(...args),
  get: vi.fn(),
}));

import { createAutoFullChildShipment, isFullQuickDeliveryPlan } from '../deliveryAutoChild';

const MASTER_ITEMS = [
  { id: 101, salesOrderItemId: 'SOI-1', productTypeId: 'PT-1' },
  { id: 102, salesOrderItemId: 'SOI-2', productTypeId: 'PT-2' },
];

describe('createAutoFullChildShipment', () => {
  beforeEach(() => {
    postMock.mockReset();
    postMock.mockResolvedValue({ success: true, data: {} });
  });

  it('posts one child-shipment request against the parent delivery, keyed by parentDeliveryItemId', async () => {
    const ok = await createAutoFullChildShipment({
      factoryId: 'F006',
      masterId: 'DLV-1',
      masterItems: MASTER_ITEMS,
      plannedItems: [
        { salesOrderItemId: 'SOI-1', deliveredQuantity: 10 },
        { salesOrderItemId: 'SOI-2', deliveredQuantity: 5 },
      ],
      deliveryMethod: 'LOGISTICS',
      logisticsCompany: '顺丰冷链',
      trackingNumber: 'SF123',
      deliveryAddress: '仓库A',
    });

    expect(ok).toBe(true);
    expect(postMock).toHaveBeenCalledTimes(1);
    const [url, body] = postMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toBe('/F006/sales/deliveries/DLV-1/shipments');
    expect(body.idempotencyKey).toBe('web-full-shipment-DLV-1');
    expect(body.deliveryMode).toBe('FULL');
    expect(body.deliveryMethod).toBe('LOGISTICS');
    expect(body.items).toEqual([
      { parentDeliveryItemId: 101, quantity: 10 },
      { parentDeliveryItemId: 102, quantity: 5 },
    ]);
  });

  it('falls back to matching by productTypeId when salesOrderItemId is absent (无单出库/AI 场景兼容)', async () => {
    const ok = await createAutoFullChildShipment({
      factoryId: 'F006',
      masterId: 'DLV-2',
      masterItems: MASTER_ITEMS,
      plannedItems: [{ productTypeId: 'PT-2', deliveredQuantity: 7 }],
      deliveryMethod: 'SELF_DELIVERY',
      logisticsCompany: '',
      trackingNumber: '',
      deliveryAddress: '仓库B',
    });

    expect(ok).toBe(true);
    const [, body] = postMock.mock.calls[0] as [string, Record<string, unknown>];
    expect(body.items).toEqual([{ parentDeliveryItemId: 102, quantity: 7 }]);
  });

  it('returns false and never calls the backend when a planned line cannot be matched to a master item', async () => {
    const ok = await createAutoFullChildShipment({
      factoryId: 'F006',
      masterId: 'DLV-3',
      masterItems: MASTER_ITEMS,
      plannedItems: [
        { salesOrderItemId: 'SOI-1', deliveredQuantity: 10 },
        { salesOrderItemId: 'SOI-DOES-NOT-EXIST', deliveredQuantity: 3 },
      ],
      deliveryMethod: 'LOGISTICS',
      logisticsCompany: 'X',
      trackingNumber: 'Y',
      deliveryAddress: 'Z',
    });

    expect(ok).toBe(false);
    expect(postMock).not.toHaveBeenCalled();
  });

  it('returns false without calling the backend when masterId is missing', async () => {
    const ok = await createAutoFullChildShipment({
      factoryId: 'F006',
      masterId: '',
      masterItems: MASTER_ITEMS,
      plannedItems: [{ salesOrderItemId: 'SOI-1', deliveredQuantity: 10 }],
      deliveryMethod: 'LOGISTICS',
      logisticsCompany: 'X',
      trackingNumber: 'Y',
      deliveryAddress: 'Z',
    });

    expect(ok).toBe(false);
    expect(postMock).not.toHaveBeenCalled();
  });

  it('propagates a backend rejection instead of swallowing it (detail.vue relies on this to surface errors)', async () => {
    postMock.mockRejectedValueOnce(new Error('SHIPMENT_LOGISTICS_COMPANY_REQUIRED'));
    await expect(createAutoFullChildShipment({
      factoryId: 'F006',
      masterId: 'DLV-4',
      masterItems: MASTER_ITEMS,
      plannedItems: [{ salesOrderItemId: 'SOI-1', deliveredQuantity: 10 }],
      deliveryMethod: 'LOGISTICS',
      logisticsCompany: '',
      trackingNumber: '',
      deliveryAddress: 'Z',
    })).rejects.toThrow('SHIPMENT_LOGISTICS_COMPANY_REQUIRED');
  });
});

describe('isFullQuickDeliveryPlan (list.vue 隐式 FULL 判定)', () => {
  it('is true when every line still equals its prefilled max (order-quantity) default', () => {
    expect(isFullQuickDeliveryPlan([
      { deliveredQuantity: 10, maxQuantity: 10 },
      { deliveredQuantity: 5, maxQuantity: 5 },
    ])).toBe(true);
  });

  it('is false when the user reduced any single line (partial delivery)', () => {
    expect(isFullQuickDeliveryPlan([
      { deliveredQuantity: 10, maxQuantity: 10 },
      { deliveredQuantity: 3, maxQuantity: 5 },
    ])).toBe(false);
  });

  it('is false for an empty plan (no lines to deliver)', () => {
    expect(isFullQuickDeliveryPlan([])).toBe(false);
  });
});
