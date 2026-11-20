import { describe, expect, it } from 'vitest';
import { processPieByUnit } from '../processPieByUnit';

describe('processPieByUnit (工序产出分布饼图)', () => {
  it('只画一个单位, 不把 kg 和盒加进同一个分母', () => {
    const pie = processPieByUnit([
      { process_category: '切丁', unit: 'kg', output: 300 },
      { process_category: '装盒', unit: 'kg', output: 100 },
      { process_category: '装盒', unit: '盒', output: 5000 },
    ]);
    expect(pie.unit).toBe('kg');
    expect(pie.slices).toEqual([
      { name: '切丁', value: 300 },
      { name: '装盒', value: 100 },
    ]);
    expect(pie.otherUnits).toEqual(['盒']);
  });

  it('同一工序同单位重复行合并成一块, 工序不重复', () => {
    const pie = processPieByUnit([
      { process_category: '装盒', unit: 'kg', output: '10' },
      { process_category: '装盒', unit: 'KG ', output: '5' },
    ]);
    expect(pie.slices).toEqual([{ name: '装盒', value: 15 }]);
    expect(pie.otherUnits).toEqual([]);
  });

  it('空数据', () => {
    expect(processPieByUnit(undefined).slices).toEqual([]);
    expect(processPieByUnit([]).unit).toBeNull();
  });
});
