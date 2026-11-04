import { describe, expect, it } from 'vitest';
import { reworkRatioBlockReason, reworkRatioRequestValue } from '../productReworkRatio';

describe('产品回料比例上限', () => {
  it('空值发 0 (清除), 不发 null (后端 null = 不改)', () => {
    expect(reworkRatioRequestValue(null)).toBe(0);
    expect(reworkRatioRequestValue(undefined)).toBe(0);
    expect(reworkRatioRequestValue(NaN)).toBe(0);
    expect(reworkRatioRequestValue(30)).toBe(30);
  });
  it('空值合法 (不设上限=不拦); (0,100] 合法; 域外给原因', () => {
    expect(reworkRatioBlockReason(null)).toBeNull();
    expect(reworkRatioBlockReason(0.01)).toBeNull();
    expect(reworkRatioBlockReason(100)).toBeNull();
    expect(reworkRatioBlockReason(0)).not.toBeNull();
    expect(reworkRatioBlockReason(-5)).not.toBeNull();
    expect(reworkRatioBlockReason(100.5)).not.toBeNull();
  });
});
