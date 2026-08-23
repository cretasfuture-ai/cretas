import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { reservedHoldLabel, reservedQuantityOf, shouldShowReservedHold } from '../reservedHold';

const HERE = dirname(fileURLToPath(import.meta.url));
const INVENTORY_VIEW = resolve(HERE, '../index.vue');

describe('reservedQuantityOf —— 「没有这个字段」和「占用量是 0」是两件事', () => {
  it('取得到就返回数值（字符串也认，后端 BigDecimal 序列化过来可能是字符串）', () => {
    expect(reservedQuantityOf({ reservedQuantity: 20 })).toBe(20);
    expect(reservedQuantityOf({ reservedQuantity: '20.5' })).toBe(20.5);
    expect(reservedQuantityOf({ reservedQuantity: 0 })).toBe(0);
  });

  it('⛔ 字段缺失时返回 null 而不是 0 —— 返回 0 等于替后端断言「这批没被占用」', () => {
    expect(reservedQuantityOf({})).toBeNull();
    expect(reservedQuantityOf({ reservedQuantity: null })).toBeNull();
    expect(reservedQuantityOf({ reservedQuantity: undefined })).toBeNull();
    expect(reservedQuantityOf({ reservedQuantity: '' })).toBeNull();
    expect(reservedQuantityOf({ reservedQuantity: 'abc' })).toBeNull();
    expect(reservedQuantityOf(null)).toBeNull();
    expect(reservedQuantityOf(undefined)).toBeNull();
  });

  it('负数当读不出来处理 —— 库存不变式不允许负占用, 出现就是脏数据', () => {
    expect(reservedQuantityOf({ reservedQuantity: -1 })).toBeNull();
  });
});

describe('shouldShowReservedHold —— 宁可窄而可信', () => {
  it('确实被占着才显示', () => {
    expect(shouldShowReservedHold({ reservedQuantity: 20 })).toBe(true);
  });

  it('占用为 0 不显示 —— 多印一行「被占用 0」是噪音, 而噪音正是让人以后忽略这行字的原因', () => {
    expect(shouldShowReservedHold({ reservedQuantity: 0 })).toBe(false);
  });

  it('读不出来不显示 —— ⛔ 不猜', () => {
    expect(shouldShowReservedHold({})).toBe(false);
    expect(shouldShowReservedHold(null)).toBe(false);
  });
});

describe('reservedHoldLabel —— 说清扣掉的是什么, 且不指向一个不存在的计划', () => {
  it('带上数量和单位', () => {
    expect(reservedHoldLabel({ reservedQuantity: 20, quantityUnit: 'kg' })).toBe('另有 20kg 被未结报工占用');
  });

  it('单位取不到时只说数量, ⛔ 不编一个单位出来', () => {
    expect(reservedHoldLabel({ reservedQuantity: 20 })).toBe('另有 20 被未结报工占用');
  });

  it('quantityUnit 缺失时回退 unit', () => {
    expect(reservedHoldLabel({ reservedQuantity: 4, unit: '卷' })).toBe('另有 4卷 被未结报工占用');
  });

  it('⛔ 阴性对照: 不许说成「其他生产计划」—— 占着它的通常正是本计划自己已提交的报工', () => {
    const label = reservedHoldLabel({ reservedQuantity: 20, quantityUnit: 'kg' });
    expect(label).not.toContain('其他未结生产计划');
    expect(label).not.toContain('冲突计划');
    expect(label).toContain('未结报工');
  });

  it('没被占着就没有文字', () => {
    expect(reservedHoldLabel({ reservedQuantity: 0 })).toBe('');
    expect(reservedHoldLabel({})).toBe('');
  });
});

/**
 * 接线断言 —— 只测 helper 不算数。
 *
 * ⚠️ 局限说清楚: 这条读的是 `index.vue` 的模板源码，不是挂载后的渲染结果。
 * 该视图 2000+ 行且依赖大量 API，挂载成本远高于本次改动本身。
 * 它挡得住「helper 写好了却没人调」，挡不住「调了但被 CSS 藏起来」。
 */
describe('接线: 库存列表那一列真的用上了 helper', () => {
  const source = readFileSync(INVENTORY_VIEW, 'utf8');

  it('模板 import 了这两个函数', () => {
    expect(source).toContain("from './reservedHold'");
    expect(source).toMatch(/shouldShowReservedHold/);
    expect(source).toMatch(/reservedHoldLabel/);
  });

  it('「当前数量」那一列的 v-if 绑的是 shouldShowReservedHold(row), ⛔ 不是写死的 false', () => {
    const binding = source.match(/v-if="shouldShowReservedHold\(row\)"/);
    expect(binding, '占用说明没有绑到 helper 上 —— helper 写好了但没人调').not.toBeNull();
    expect(source).not.toMatch(/v-if="false"/);
  });
});
