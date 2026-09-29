import { loadAllWarehouseMaterialBatches } from '../../../../screens/warehouse/inventory/warehouseInventoryPages';
import { warehouseAnnotationPoints, warehouseMapExtent } from '../../../../screens/warehouse/inventory/warehouseFloorMapModel';
import type { WarehouseMapFloor } from '../../../../services/api/warehouseLayoutApiClient';

const batch = (id: string, factoryId = 'F006') => ({ id, factoryId });
const floor: WarehouseMapFloor = {
  id: 'f1', name: '一层', background: 'data:image/png;base64,AAA',
  racks: [{ id: 'r1', zoneId: 'z1', name: '一号货架', x: 920, y: 520, width: 140, height: 90, levels: 3 }],
  zoneShapes: [{ zoneId: 'z1', x: 10, y: 15, width: 200, height: 100 }],
  annotations: [{ id: 'a1', kind: 'polygon', x: 20, y: 30, width: 80, height: 40, points: [0, 0, 80, 0, 80, 40] }],
};

describe('手机仓库地图读取 Web 保存的布局', () => {
  it('画布覆盖区域、货架和标注的完整范围', () => {
    expect(warehouseMapExtent(floor)).toEqual({ width: 1100, height: 650 });
  });

  it('多边形的相对坐标转换为 Web 同一画布坐标', () => {
    expect(warehouseAnnotationPoints(floor.annotations![0]!)).toBe('20,30 100,30 100,70');
  });

  it('库位占用统计读取全部分页', async () => {
    const fetch = jest.fn().mockResolvedValueOnce({ content: [batch('1')], totalElements: 2 })
      .mockResolvedValueOnce({ content: [batch('2')], totalElements: 2 });
    expect(await loadAllWarehouseMaterialBatches('F006', fetch)).toEqual([batch('1'), batch('2')]);
    expect(fetch.mock.calls).toEqual([[1], [2]]);
  });

  it('分页重复或跨工厂时不显示虚假的空闲库位', async () => {
    const fetch = jest.fn().mockResolvedValueOnce({ content: [batch('1')], totalElements: 2 })
      .mockResolvedValueOnce({ content: [batch('2', 'OTHER')], totalElements: 2 });
    await expect(loadAllWarehouseMaterialBatches('F006', fetch)).rejects.toThrow('所属工厂不一致');
  });
});
