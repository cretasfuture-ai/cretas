import { describe, expect, it } from 'vitest';
import type { FactoryWarehouse } from '@/api/factoryWarehouse';
import { warehouseOccupancyAt } from './warehouseOccupancy';

describe('仓储位置按业务仓库展示', () => {
  it('同一物理库位允许多个业务仓库的批次并列展示', () => {
    const warehouses = [
      { id: 'raw-id', code: 'WH-RAW', name: '原料库', type: 'RAW' },
      { id: 'work-id', code: 'WH-WKS', name: '线边库', type: 'WORKSHOP' },
    ] as FactoryWarehouse[];
    const batches = [
      { location: 'LC01', warehouseId: 'raw-id' },
      { location: 'LC01', warehouseId: 'raw-id' },
      { location: 'LC01', warehouseId: 'work-id' },
      { location: 'LC02', warehouseId: 'work-id' },
    ];
    expect(warehouseOccupancyAt('LC01', batches, warehouses)).toEqual([
      '原料库（原料仓） 2 批',
      '线边库（生产仓） 1 批',
    ]);
  });

  it('仓库记录缺失时保留原 ID，不把库存算成空闲', () => {
    expect(warehouseOccupancyAt('LC01', [{ location: 'LC01', warehouseId: 'old-id' }], []))
      .toEqual(['仓库 old-id 1 批']);
  });
});
