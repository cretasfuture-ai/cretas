import { groupProcessBreakdown, defectRateLabel } from '../../../utils/processBreakdown';

describe('groupProcessBreakdown (工序产出分布)', () => {
  const rows = [
    { process_category: '装盒', unit: 'kg', output: '100', defectRate: '0.05', defectStatus: 'COUNTED' },
    { process_category: '装盒', unit: '盒', output: 30, defectRate: null, defectStatus: 'NOT_COUNTED' },
    { process_category: '切丁', unit: 'kg', output: 300, defectRate: 0, defectStatus: 'COUNTED' },
  ];

  it('同一工序只出现一次, 不同单位分成子项', () => {
    const groups = groupProcessBreakdown(rows);
    expect(groups.map((g) => g.process)).toEqual(['装盒', '切丁']);
    expect(groups[0]!.lines.map((l) => l.unit)).toEqual(['kg', '盒']);
  });

  it('占比只在同单位内算, kg 与盒不进同一个分母', () => {
    const [box, dice] = groupProcessBreakdown(rows);
    // kg 总量 400: 装盒 100 → 25%, 切丁 300 → 75%; 盒只有装盒 → 100% (跨单位求和会得到 100/430)
    expect(box!.lines[0]!.sharePct).toBeCloseTo(25);
    expect(dice!.lines[0]!.sharePct).toBeCloseTo(75);
    expect(box!.lines[1]!.sharePct).toBeCloseTo(100);
  });

  it('不良率: 没清点 ≠ 零不良', () => {
    const [box, dice] = groupProcessBreakdown(rows);
    expect(defectRateLabel(box!.lines[0]!)).toBe('不良率 5.0%');
    expect(defectRateLabel(box!.lines[1]!)).toBe('不良未清点');
    expect(defectRateLabel(dice!.lines[0]!)).toBe('不良率 0.0%');
  });

  it('旧后端没有 defectStatus 且没有率: 当不知道, 不当 0', () => {
    const [g] = groupProcessBreakdown([{ process_category: 'X', unit: 'kg', output: 1 }]);
    expect(g!.lines[0]!.defectRate).toBeNull();
    expect(defectRateLabel(g!.lines[0]!)).toBe('不良未清点');
  });

  it('空数据', () => {
    expect(groupProcessBreakdown(undefined)).toEqual([]);
    expect(groupProcessBreakdown([])).toEqual([]);
  });
});
