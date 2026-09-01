import { describe, expect, it } from 'vitest';
import {
  deriveAuxiliaryQty,
  massUnitToKg,
  rawPickedTotalKg,
  splitZeroQtyRows,
} from '../confirmPickingAuxDerive';

/**
 * 验收数字来自 brief 里的真实 LIUSHANMEN BOM (熟肉饼, 洋葱泥 WL2020, dosagePerKgG=83.8):
 *   RAW 1+1+1=3kg ⇒ 洋葱泥 83.8×3÷1000 = 0.2514 kg
 *   RAW 合计改到 5kg ⇒ 洋葱泥 83.8×5÷1000 = 0.419 kg
 * 断言的是精确数字, 不是"出现了一个数"。
 */
describe('deriveAuxiliaryQty — 单锅基准 dosagePerKgG × RAW合计kg ÷ 1000', () => {
  it('RAW=3kg ⇒ 洋葱泥 0.2514kg (精确值, 不是约等于)', () => {
    expect(deriveAuxiliaryQty(83.8, 3, 'kg')).toBe(0.2514);
  });

  it('RAW 从 3kg 改到 5kg ⇒ 辅料重新算成 0.419kg', () => {
    expect(deriveAuxiliaryQty(83.8, 5, 'kg')).toBe(0.419);
  });

  it('阴性对照: RAW 合计为 0 ⇒ 返回 null (「按实际领用」), ⛔ 不是编出来的 0', () => {
    expect(deriveAuxiliaryQty(83.8, 0, 'kg')).toBeNull();
  });

  it('阴性对照: RAW 合计为负数(异常输入) ⇒ 同样返回 null, 不产出负数辅料量', () => {
    expect(deriveAuxiliaryQty(83.8, -1, 'kg')).toBeNull();
  });

  it('没有比例数据 (dosagePerKgG=null/0/负数) ⇒ 返回 null, 不接管这一行', () => {
    expect(deriveAuxiliaryQty(null, 3, 'kg')).toBeNull();
    expect(deriveAuxiliaryQty(0, 3, 'kg')).toBeNull();
    expect(deriveAuxiliaryQty(-5, 3, 'kg')).toBeNull();
  });

  it('单位是 g 时按克折算, 不是按 kg', () => {
    // 83.8 g/kg × 3kg = 251.4g
    expect(deriveAuxiliaryQty(83.8, 3, 'g')).toBeCloseTo(251.4, 3);
  });

  it('非质量单位(个/只/件)不硬折 —— 返回 null, 留给人工填', () => {
    expect(deriveAuxiliaryQty(83.8, 3, '个')).toBeNull();
    expect(deriveAuxiliaryQty(83.8, 3, '只')).toBeNull();
  });
});

describe('massUnitToKg — 镜像后端 massToKg', () => {
  it('kg/g/斤/吨 可换算', () => {
    expect(massUnitToKg(1, 'kg')).toBe(1);
    expect(massUnitToKg(1000, 'g')).toBe(1);
    expect(massUnitToKg(2, '斤')).toBe(1);
    expect(massUnitToKg(1, '吨')).toBe(1000);
  });

  it('计数单位不硬折, 返回 null', () => {
    expect(massUnitToKg(5, '个')).toBeNull();
    expect(massUnitToKg(5, '只')).toBeNull();
  });
});

describe('rawPickedTotalKg — RAW 行「实际拣货」合计, 只累加能换算成质量的行', () => {
  it('3 个 RAW 行各 1kg 合计 3kg', () => {
    const rows = [
      { materialCategory: 'RAW', pickedInput: 1, unit: 'kg' },
      { materialCategory: 'RAW', pickedInput: 1, unit: 'kg' },
      { materialCategory: 'RAW', pickedInput: 1, unit: 'kg' },
    ];
    expect(rawPickedTotalKg(rows)).toBe(3);
  });

  it('AUXILIARY/PACKAGING 行不计入分母, 即便它们也叫 pickedInput', () => {
    const rows = [
      { materialCategory: 'RAW', pickedInput: 3, unit: 'kg' },
      { materialCategory: 'AUXILIARY', pickedInput: 999, unit: 'kg' },
      { materialCategory: 'PACKAGING', pickedInput: 999, unit: 'kg' },
    ];
    expect(rawPickedTotalKg(rows)).toBe(3);
  });

  it('留空(null)的 RAW 行不计入, 计数单位的 RAW 行也不计入', () => {
    const rows = [
      { materialCategory: 'RAW', pickedInput: null, unit: 'kg' },
      { materialCategory: 'RAW', pickedInput: 2, unit: '个' },
      { materialCategory: 'RAW', pickedInput: 4, unit: 'kg' },
    ];
    expect(rawPickedTotalKg(rows)).toBe(4);
  });
});

describe('splitZeroQtyRows — 缺陷43: 零值行不删, 只拆分', () => {
  it('按数量把行分成非零/零两组, 顺序不打乱, 数量不丢', () => {
    const items = [{ q: 1 }, { q: 0 }, { q: 2 }, { q: 0 }, { q: 0 }];
    const { nonZero, zero } = splitZeroQtyRows(items, (it) => it.q);
    expect(nonZero).toHaveLength(2);
    expect(zero).toHaveLength(3);
    expect(nonZero.map((it) => it.q)).toEqual([1, 2]);
  });
});
