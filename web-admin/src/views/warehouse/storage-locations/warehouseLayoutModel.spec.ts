import { describe, expect, it } from 'vitest';
import { daysInStorage, formatQuantity, maxDaysInStorage, nextLocationCode, normalizeWarehouseLayout, normalizedLocationPrefix } from './warehouseLayoutModel';

describe('真实工厂储存位置与库存时间模型', () => {
  it('empty factory has no fabricated zones, locations, or floors', () => {
    expect(normalizeWarehouseLayout(null)).toEqual({ zones: [], locations: [], floors: [] });
  });

  it('fills the first unused position under the selected zone prefix', () => {
    const zone = { id: 'cold', name: '冷藏区', prefix: 'LC' };
    expect(nextLocationCode(zone, [
      { id: '1', zoneId: 'cold', code: 'LC01', name: '1号位' },
      { id: '3', zoneId: 'cold', code: 'LC03', name: '3号位' },
    ])).toBe('LC02');
  });

  it('computes the age of inventory currently still stored', () => {
    expect(daysInStorage('2026-08-20T00:00:00Z', Date.parse('2026-08-25T12:00:00Z'))).toBe(5);
    expect(daysInStorage(undefined)).toBeNull();
  });

  it('derives a searchable first-level storage type from a position code', () => {
    expect(normalizedLocationPrefix('lc013')).toBe('LC');
    expect(normalizedLocationPrefix('01')).toBe('');
  });
});

describe('2026-08-25 生产实测缺陷的回归钉', () => {
  it('浮点误差不再打给仓管 —— 168.96999999999997 必须收敛成 168.97', () => {
    // 生产现场盘点页实测出现过的两个原值
    expect(formatQuantity(168.96999999999997)).toBe('168.97');
    expect(formatQuantity(29.990000000000002)).toBe('29.99');
    // 阴性对照: 真实精度不许被抹掉
    expect(formatQuantity(0.04)).toBe('0.04');
    expect(formatQuantity(114361)).toBe('114361');
    expect(formatQuantity(1.125)).toBe('1.125');
  });

  it('全部批次都没有入库日期时是「未知」, ⛔ 不是 0 天', () => {
    // 原实现写的是 `Math.max(...entries.map(e => daysInStorage(e) ?? 0))`,
    // 缺日期时返回 0, 等于向仓管断言「今天入库」—— 那是编造的读数。
    expect(maxDaysInStorage([undefined, undefined])).toBeNull();
    expect(maxDaysInStorage([])).toBeNull();
  });

  it('只要有一条批次有日期, 就按有日期的那些算最长在库天数', () => {
    const now = Date.parse('2026-08-25T12:00:00Z');
    expect(maxDaysInStorage([undefined, '2026-08-20T00:00:00Z'], now)).toBe(5);
    expect(maxDaysInStorage(['2026-08-24T00:00:00Z', '2026-08-18T00:00:00Z'], now)).toBe(7);
  });
});
