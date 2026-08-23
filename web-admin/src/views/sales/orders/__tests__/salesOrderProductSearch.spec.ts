import { describe, expect, it } from 'vitest';
import { filterSalesProducts, isSalesOrderProduct, matchesSalesProduct } from '../salesOrderProductSearch';

const chicken = { id: '1', name: '北京飞熊鸡', code: 'CPF001', productCategory: 'FINISHED_PRODUCT' };
const semi = { id: '2', name: '香辣半成品', code: 'WIP-02', productCategory: 'SEMI_FINISHED' };

describe('sales order product search', () => {
  it('matches Chinese name, code, full pinyin and initials', () => {
    expect(matchesSalesProduct(chicken, '北京')).toBe(true);
    expect(matchesSalesProduct(chicken, 'CPF001')).toBe(true);
    expect(matchesSalesProduct(chicken, 'beijing')).toBe(true);
    expect(matchesSalesProduct(chicken, 'bjfxj')).toBe(true);
  });

  it('keeps finished and semi-finished products but excludes raw materials', () => {
    expect(isSalesOrderProduct(chicken)).toBe(true);
    expect(isSalesOrderProduct(semi)).toBe(true);
    expect(isSalesOrderProduct({ id: 'raw', name: '鸡胸肉原料', productCategory: 'RAW_MATERIAL' })).toBe(false);
    expect(filterSalesProducts([chicken, semi, { id: 'raw', name: '鸡胸肉原料', productCategory: 'RAW_MATERIAL' }], '')).toHaveLength(2);
  });
});
