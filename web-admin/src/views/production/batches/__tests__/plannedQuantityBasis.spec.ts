import { describe, expect, it } from 'vitest';
import {
  plannedBasisOf, plannedColumnLabel, plannedQuantityOrNull, completionRatioPercent,
  completionRatioLabel, completionRatioHint,
} from '../plannedQuantityBasis';

describe('plannedBasisOf (B4)', () => {
  it('customFields.theoryKg 与 plannedQuantity 相等 → 理论产能', () => {
    expect(plannedBasisOf({ plannedQuantity: 8680, customFields: { theoryKg: 8680 } })).toBe('THEORETICAL_CAPACITY');
    expect(plannedBasisOf({ plannedQuantity: '8680.0', customFields: { theoryKg: 8680 } })).toBe('THEORETICAL_CAPACITY');
  });
  it('阴性对照: 没有证据 / 数不相等 / 无计划量 → 仍是计划数量', () => {
    expect(plannedBasisOf({ plannedQuantity: 8680, customFields: {} })).toBe('PLAN');
    expect(plannedBasisOf({ plannedQuantity: 8680, customFields: { theoryKg: 9000 } })).toBe('PLAN');
    expect(plannedBasisOf({ plannedQuantity: null, customFields: { theoryKg: 8680 } })).toBe('PLAN');
    expect(plannedBasisOf({ plannedQuantity: 8680 })).toBe('PLAN');
    expect(plannedBasisOf(null)).toBe('PLAN');
  });
  it('无计划数量 (null / 0) 返回 null, 不当 0', () => {
    expect(plannedQuantityOrNull({ plannedQuantity: 0 })).toBeNull();
    expect(plannedQuantityOrNull({ plannedQuantity: null })).toBeNull();
    expect(plannedQuantityOrNull({ plannedQuantity: 12 })).toBe(12);
  });
  it('列头: 全理论 / 全计划 / 混合 / 都没有', () => {
    const t = { plannedQuantity: 1, customFields: { theoryKg: 1 } };
    const p = { plannedQuantity: 2 };
    expect(plannedColumnLabel([t, t])).toBe('理论产能');
    expect(plannedColumnLabel([p, p])).toBe('计划数量');
    expect(plannedColumnLabel([t, p])).toBe('计划数量 / 理论产能');
    expect(plannedColumnLabel([{ plannedQuantity: null }])).toBe('计划数量');
  });
});

describe('completionRatioPercent (B5): 含不良总产量 ÷ 计划', () => {
  const imported = {
    plannedQuantity: 8680, actualQuantity: 6695, goodQuantity: 6592, unit: 'kg', plannedUnit: 'kg', efficiency: 75.95,
  };
  it('味美 261015-210061: 77.1% (库里的 efficiency 是良品÷计划 76.0%, 与说明不符)', () => {
    expect(completionRatioPercent(imported)).toBe(77.1);
  });
  it('变异守卫: 若按库里 efficiency 直接显示会是 75.95, 本断言红', () => {
    expect(completionRatioPercent(imported)).not.toBeCloseTo(75.95, 1);
  });
  it('单位不同: 沿用后端 efficiency, 不自己猜换算; 后端为 null 则 null', () => {
    expect(completionRatioPercent({ ...imported, plannedUnit: '箱', efficiency: 50 })).toBe(50);
    expect(completionRatioPercent({ ...imported, plannedUnit: '箱', efficiency: null })).toBeNull();
  });
  it('缺实际量或无计划量: 沿用后端 efficiency', () => {
    expect(completionRatioPercent({ ...imported, actualQuantity: null, efficiency: 60 })).toBe(60);
    expect(completionRatioPercent({ ...imported, plannedQuantity: null, efficiency: null })).toBeNull();
  });
  it('文字随基数变化', () => {
    expect(completionRatioLabel('THEORETICAL_CAPACITY')).toBe('总产量 ÷ 理论产能');
    expect(completionRatioHint('PLAN')).toBe('含不良总产量 ÷ 计划数量');
  });
});
