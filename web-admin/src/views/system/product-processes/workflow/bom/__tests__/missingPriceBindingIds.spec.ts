import { describe, expect, it } from 'vitest';
import { missingPriceBindingIds } from '../seasoningModel';

// 工作区 anomalies → 缺价 bindingId 集合。判据只认后端登记的 MISSING_PRICE, 前端不自己判价格。
describe('missingPriceBindingIds', () => {
  it('只收 MISSING_PRICE 且带 bindingId 的异常', () => {
    const ids = missingPriceBindingIds([
      { code: 'MISSING_PRICE', bindingId: 11 },
      { code: 'MISSING_PRICE', bindingId: null },
      { code: 'INVALID_MATERIAL', bindingId: 12 },
      { code: 'MISSING_PRICE', bindingId: 13 },
    ]);
    expect([...ids].sort()).toEqual(['11', '13']);
  });

  it('阴性对照: 空 / null 都是空集', () => {
    expect(missingPriceBindingIds([]).size).toBe(0);
    expect(missingPriceBindingIds(null).size).toBe(0);
    expect(missingPriceBindingIds(undefined).size).toBe(0);
  });
});
