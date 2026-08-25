import { describe, expect, it } from 'vitest';
import { daysInStorage, nextLocationCode, normalizeWarehouseLayout, normalizedLocationPrefix } from './warehouseLayoutModel';

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
