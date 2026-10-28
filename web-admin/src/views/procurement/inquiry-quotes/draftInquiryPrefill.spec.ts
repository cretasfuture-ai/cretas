import { describe, expect, it } from 'vitest';
import { buildDraftInquiryQuery, parseDraftInquiryPrefill } from './draftInquiryPrefill';

describe('draftInquiryPrefill', () => {
  it('round-trips a purchase draft line into the create-inquiry prefill', () => {
    const query = buildDraftInquiryQuery({
      orderId: 'PO-1',
      orderNumber: 'PO-20261028-0001',
      materialTypeId: 'MAT-1',
      materialName: '辣椒',
      specification: '特级',
      quantity: 100,
      unit: 'kg',
    });
    expect(parseDraftInquiryPrefill(query)).toEqual({
      sourcePurchaseOrderId: 'PO-1',
      sourceOrderNumber: 'PO-20261028-0001',
      materialTypeId: 'MAT-1',
      materialName: '辣椒',
      specification: '特级',
      quantity: 100,
      unit: 'kg',
    });
  });

  it('returns null instead of guessing when the draft id, unit or a positive quantity is missing', () => {
    expect(parseDraftInquiryPrefill({ materialTypeId: 'MAT-1', quantity: '1', unit: 'kg' })).toBeNull();
    expect(parseDraftInquiryPrefill({ sourcePurchaseOrderId: 'PO-1', materialTypeId: 'MAT-1', quantity: '1' }))
      .toBeNull();
    expect(parseDraftInquiryPrefill({
      sourcePurchaseOrderId: 'PO-1', materialTypeId: 'MAT-1', quantity: '0', unit: 'kg',
    })).toBeNull();
  });
});
