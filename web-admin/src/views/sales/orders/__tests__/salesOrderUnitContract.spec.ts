import { describe, expect, it } from 'vitest';
import {
  canonicalSalesOrderItemPayload,
  packagingOptionsForUnit,
  packagingSelectionError,
} from '../salesOrderUnitContract';

const caseToBox = {
  id: 'spec-case-box',
  packageUnit: '箱',
  baseUnit: '盒',
  conversionFactor: 8,
  active: true,
};

describe('sales order unit and packaging contract', () => {
  /**
   * 🔴 2026-08-18: 本组断言原先钉的是 `unit: 'box'` —— 它<b>不是</b>恒真式, 也不是变异没生效,
   * 它好好地守着一个<b>现在被判定为缺陷</b>的行为(前端把用户选的「盒」翻成英文码再发出去)。
   * 需求变了就改断言, 但改法是把守的东西从「字面」抬到「性质」并补阴性对照:
   * 正面钉「发的就是用户看到的那个字」, 反面钉「英文码不许回来」——
   * 只写 `not.toBe('box')` 太容易恒真(发成 `'BOX'`/`''` 都能过)。
   *
   * 包装规格匹配 (`packagingOptionsForUnit` / `packagingSelectionError`) 仍然按<b>内部比较键</b>
   * 走 `canonicalUnitCode` —— 这次改动<b>刻意没动它们</b>, 所以下面每条都连着断言一遍。
   */
  it('submits a Chinese display box order as the Chinese storage unit without an outer-case spec', () => {
    const line = {
      productTypeId: 'SKU-BOX',
      quantity: 5,
      unit: '盒',
      boxQuantity: 0.63,
      packagingSpecId: '',
      packagingSpecs: [caseToBox],
    };

    // 比较键那一侧没变: 1箱=8盒 的箱规对「盒」下单仍然不可选
    expect(packagingOptionsForUnit(line)).toEqual([]);
    expect(canonicalSalesOrderItemPayload(line)).toMatchObject({
      productTypeId: 'SKU-BOX',
      quantity: 5,
      unit: '盒',
      boxQuantity: 0.63,
    });
    expect(canonicalSalesOrderItemPayload(line).unit).not.toBe('box');
    expect(canonicalSalesOrderItemPayload(line)).not.toHaveProperty('packagingSpecId');
    expect(canonicalSalesOrderItemPayload(line)).not.toHaveProperty('packagingSpecs');
  });

  it('translates a legacy English unit coming back from the API into the Chinese storage unit', () => {
    // 存量订单 / 旧缓存回传的是 `box`; 用户在界面上看到的是「盒」, 发出去的就该是「盒」。
    const line = {
      productTypeId: 'SKU-BOX',
      quantity: 5,
      unit: 'box',
      packagingSpecs: [caseToBox],
    };
    expect(canonicalSalesOrderItemPayload(line).unit).toBe('盒');
    expect(canonicalSalesOrderItemPayload(line).unit).not.toBe('box');
    // 比较键不变 ⇒ 箱规匹配结论与上一条完全一致
    expect(packagingOptionsForUnit(line)).toEqual([]);
  });

  it('retains the selected spec only when ordering by its canonical case unit', () => {
    const line = {
      productTypeId: 'SKU-BOX',
      quantity: 1,
      unit: 'case',
      packagingSpecId: caseToBox.id,
      packagingSpecs: [caseToBox],
    };

    expect(packagingOptionsForUnit(line)).toEqual([caseToBox]);
    expect(packagingSelectionError(line)).toBeNull();
    expect(canonicalSalesOrderItemPayload(line)).toMatchObject({
      unit: '箱',
      packagingSpecId: caseToBox.id,
    });
    expect(canonicalSalesOrderItemPayload(line).unit).not.toBe('case');
  });

  it('preserves the explicitly selected source warehouse in the canonical line payload', () => {
    const payload = canonicalSalesOrderItemPayload({
      productTypeId: 'SKU-BOX',
      unit: 'box',
      sourceWarehouseCode: 'WH-FG',
    } as Parameters<typeof canonicalSalesOrderItemPayload>[0] & {
      productTypeId: string;
      sourceWarehouseCode: string;
    });

    expect(payload).toMatchObject({
      productTypeId: 'SKU-BOX',
      unit: '盒',
      sourceWarehouseCode: 'WH-FG',
    });
  });

  it('keeps 只/个 as the user typed them (#1976: 一只鸡不是一件包材)', () => {
    expect(canonicalSalesOrderItemPayload({ unit: '只', packagingSpecs: [] }).unit).toBe('只');
    expect(canonicalSalesOrderItemPayload({ unit: '个', packagingSpecs: [] }).unit).toBe('个');
    expect(canonicalSalesOrderItemPayload({ unit: '件', packagingSpecs: [] }).unit).toBe('件');
    // 阴性对照: 三个不许被折成同一个字, 也不许折成英文码
    expect(canonicalSalesOrderItemPayload({ unit: '只', packagingSpecs: [] }).unit).not.toBe('件');
    expect(canonicalSalesOrderItemPayload({ unit: '只', packagingSpecs: [] }).unit).not.toBe('pcs');
  });

  it('blocks a stale package spec before a base-unit request can be sent', () => {
    const line = {
      unit: '盒',
      packagingSpecId: caseToBox.id,
      packagingSpecs: [caseToBox],
    };
    expect(packagingSelectionError(line)).toContain('下单单位不一致');
  });

  it.each([
    ['片', '片'],
    ['slice', '片'],
    ['箱', '箱'],
    ['case', '箱'],
    ['袋', '袋'],
    ['bag', '袋'],
    // 可换算的国际单位两侧同为拉丁符号 —— 不连坐改中文
    ['g', 'g'],
    ['kg', 'kg'],
    ['公斤', 'kg'],
  ])('sends %s to the backend as %s without inventing a packaging spec', (unit, expected) => {
    const payload = canonicalSalesOrderItemPayload({ unit, packagingSpecs: [] });
    expect(payload.unit).toBe(expected);
    expect(payload).not.toHaveProperty('packagingSpecId');
  });
});
