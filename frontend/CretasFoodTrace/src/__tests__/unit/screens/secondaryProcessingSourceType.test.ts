/**
 * 二次加工批次类型: 后端写 SECONDARY, App 必须认 (之前只认 SEMI_FINISHED, 半成品选择区从不出现)。
 */
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { isSecondaryBatchSourceType } from '../../../utils/secondaryProcessing';

describe('isSecondaryBatchSourceType', () => {
  it('后端实际写的 SECONDARY 算二次加工; 旧值 SEMI_FINISHED 兼容', () => {
    expect(isSecondaryBatchSourceType('SECONDARY')).toBe(true);
    expect(isSecondaryBatchSourceType('SEMI_FINISHED')).toBe(true);
  });
  it('普通批次 / 空值不算', () => {
    expect(isSecondaryBatchSourceType(undefined)).toBe(false);
    expect(isSecondaryBatchSourceType(null)).toBe(false);
    expect(isSecondaryBatchSourceType('NORMAL')).toBe(false);
    expect(isSecondaryBatchSourceType('')).toBe(false);
  });
  it('报工屏用它判定, 不再直接比 SEMI_FINISHED', () => {
    const src = readFileSync(resolve(__dirname, '../../../screens/processing/YieldStepReportScreen.tsx'), 'utf-8');
    expect(src).toContain('isSecondaryBatchSourceType(batchSourceType)');
    expect(src).not.toContain("batchSourceType === 'SEMI_FINISHED'");
  });
});
