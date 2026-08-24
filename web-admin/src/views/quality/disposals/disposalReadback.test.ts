import { describe, expect, it } from 'vitest';
import { getApplicantName, getBatchDisplayName, getBatchNumber, getQuantityDisplay } from './disposalReadback';

describe('finished goods disposal readback', () => {
  it('shows flat finished-goods fields returned by the backend', () => {
    const row = {
      batchNumber: 'PB-F006-001',
      batchDisplayName: '黄油鸡成品',
      disposalQuantity: 10,
      quantityUnit: '盒',
      applicantName: 'f006_admin'
    };
    expect(getBatchNumber(row)).toBe('PB-F006-001');
    expect(getBatchDisplayName(row)).toBe('黄油鸡成品');
    expect(getQuantityDisplay(row)).toBe('10 盒');
    expect(getApplicantName(row)).toBe('f006_admin');
  });

  it('labels missing legacy applicant truth without borrowing the approver', () => {
    expect(getApplicantName({ approvedByName: '张六膳' })).toBe('历史未记录');
  });
});
