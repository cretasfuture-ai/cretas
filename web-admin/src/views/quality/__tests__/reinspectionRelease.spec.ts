import { describe, expect, it } from 'vitest';
import { canReviewPassingReinspection, dispositionResultFilter } from '../reinspectionRelease';

describe('隔离批次复检放行入口', () => {
  it('只允许已核验 FAILED 批次的同批 PASS 进入人工复核', () => {
    expect(canReviewPassingReinspection({ id: 11003, qualityStatus: 'FAILED' }, { productionBatchId: '11003', result: 'PASS' })).toBe(true);
    expect(dispositionResultFilter({ id: 11003, qualityStatus: 'FAILED' })).toBe('FAIL,CONDITIONAL,PASS');
  });

  it.each([null, {}, { id: 11003 }, { id: 11003, qualityStatus: 'PASSED' }, { id: '', qualityStatus: 'FAILED' }])('缺失或非隔离上下文不扩大全厂待处置结果: %j', batch => {
    expect(canReviewPassingReinspection(batch, { productionBatchId: 11003, result: 'PASS' })).toBe(false);
    expect(dispositionResultFilter(batch)).toBe('FAIL,CONDITIONAL');
  });

  it.each([{ productionBatchId: 11004, result: 'PASS' }, { result: 'PASS' }, { productionBatchId: 11003, result: 'PENDING' }])('不接受异批、缺批及未检结果: %j', row => {
    expect(canReviewPassingReinspection({ id: 11003, qualityStatus: 'FAILED' }, row)).toBe(false);
  });
});
