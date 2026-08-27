import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { autoTotalWeightKg, autoWeightSkippedHint, isMassUnit } from '../autoWeight';

const HERE = dirname(fileURLToPath(import.meta.url));
const LIST_VUE = resolve(HERE, '../list.vue');

describe('autoTotalWeightKg —— 「数量」和「重量」不是一回事', () => {
  it('kg：原样（后端 numeric(10,3)，保留 3 位）', () => {
    expect(autoTotalWeightKg(3, 'kg')).toBe(3);
    expect(autoTotalWeightKg(12.3456, 'kg')).toBe(12.346);
    expect(autoTotalWeightKg(3, '公斤')).toBe(3);
    expect(autoTotalWeightKg(3, '千克')).toBe(3);
    expect(autoTotalWeightKg(3, ' kg ')).toBe(3);
  });

  it('g：换算成 kg —— 修复前录 500 g 会被填成 500 kg（1000×）', () => {
    expect(autoTotalWeightKg(500, 'g')).toBe(0.5);
    expect(autoTotalWeightKg(500, '克')).toBe(0.5);
  });

  it('吨：换算成 kg', () => {
    expect(autoTotalWeightKg(2, '吨')).toBe(2000);
    expect(autoTotalWeightKg(2, 't')).toBe(2000);
  });

  it('🔴 非重量单位一律返回 null —— ⛔ 不许回退成数量', () => {
    // 生产实测(2026-08-27 cretas_prod_db): 484 个原料类型里 102 个是这些单位
    for (const unit of ['箱', '个', '盒', '片', '卷', '张', '只', '包', '件', 'm', 'L']) {
      expect(autoTotalWeightKg(3, unit)).toBeNull();
    }
  });

  it('阴性对照：单位缺失 / 数量非正 也返回 null，⛔ 不返回 0', () => {
    // 0 是一个合法的重量读数, 用它表示「算不出来」就是在编数据
    // —— 与 MaterialBatch.getTotalWeight() 在 weightPerUnit==null 时 return ZERO 同一个毛病。
    expect(autoTotalWeightKg(3, null)).toBeNull();
    expect(autoTotalWeightKg(3, undefined)).toBeNull();
    expect(autoTotalWeightKg(3, '')).toBeNull();
    expect(autoTotalWeightKg(null, 'kg')).toBeNull();
    expect(autoTotalWeightKg(0, 'kg')).toBeNull();
    expect(autoTotalWeightKg(-1, 'kg')).toBeNull();
    expect(autoTotalWeightKg(Number.NaN, 'kg')).toBeNull();
  });

  it('isMassUnit 与 autoTotalWeightKg 口径一致（⛔ 两份判断会漂）', () => {
    for (const unit of ['kg', 'g', '吨', '公斤', '千克', '克', 't', 'T', 'KG']) {
      expect(isMassUnit(unit)).toBe(true);
      expect(autoTotalWeightKg(1, unit)).not.toBeNull();
    }
    for (const unit of ['箱', '个', '', null, undefined]) {
      expect(isMassUnit(unit)).toBe(false);
      expect(autoTotalWeightKg(1, unit)).toBeNull();
    }
  });

  it('跳过时的提示带上那个单位（让用户知道为什么要自己填）', () => {
    expect(autoWeightSkippedHint('箱')).toContain('箱');
    expect(autoWeightSkippedHint('箱')).toContain('总重量');
    expect(autoWeightSkippedHint('')).toContain('该单位');
    expect(autoWeightSkippedHint(null)).toContain('该单位');
  });
});

describe('接上没有 —— list.vue 真的在用这个 helper', () => {
  const src = readFileSync(LIST_VUE, 'utf-8');

  it('入库表单调 autoTotalWeightKg', () => {
    // 🔴 helper 写对了但没人调 = 形态 B。这条守的是「接上了」。
    expect(src).toContain("from './autoWeight'");
    expect(src).toContain('autoTotalWeightKg(qty, formData.quantityUnit)');
  });

  it('⛔ 那句不看单位的 `totalWeight = qty` 不许回来', () => {
    // 修复前的原文: formData.totalWeight = Number((qty).toFixed(3));
    expect(src).not.toContain('formData.totalWeight = Number((qty).toFixed(3))');
  });
});
