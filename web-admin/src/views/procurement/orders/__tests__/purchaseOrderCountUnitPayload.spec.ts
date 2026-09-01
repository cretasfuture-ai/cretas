import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { canonicalUnitCode, canonicalUnitCodeKeepingCount, displayUnit } from '@/utils/unitPricing';

/**
 * 阻断性缺陷 (2026-09-02, LIUSHANMEN 生产实测): 字典单位「个」/「只」的物料一律建不了
 * 采购订单。真实样本: L1纸箱 (RMT_1782891392801, 单位「个」)、
 * YL-元益漫-黄油鸡 (RMT_1782891406160, 单位「只」)。
 *
 * <h2>根因</h2>
 * `applyRelationPrice` / `onSupplierMaterialChange` / `onPurchaseSpecChange` /
 * `openEditDialog` 在把值写进 `item.unit` / `item.priceUnit` / `item.quantityUnit`
 * **之前**就用 `canonicalUnitCode` 把「个」/「只」折成了 `'pcs'`。提交时 handleCreate
 * 里的 `canonicalUnitCodeKeepingCount(i.priceUnit)` 已经够不着 —— 值在更早的地方
 * 已经被换掉, 守卫接在了它守不住的地方之后 (见 `.claude/rules/measurement-and-wiring.md`
 * 形态 B「机制在, 只是没接上」)。
 *
 * 后端两条独立的失败路径都会被这个 bug 触发:
 * - `PurchaseServiceImpl:3294` `unit('pcs') != baseUnit('个')` → 422
 *   `MATERIAL_PACKAGING_SPEC_REQUIRED`
 * - `PurchaseServiceImpl:3905-3908` `priceUnit` 与供应关系采购单位精确比较不等 → 400
 *   `PURCHASE_PRICE_UNIT_RELATION_MISMATCH` (即使 quantityUnit 那一半已经修好, 这条
 *   仍然会挡下同一批物料 —— 这正是本文件要单独钉住 priceUnit 的原因)
 *
 * <h2>为什么走「抽函数体真实执行」而不是整份 mount</h2>
 * list.vue 5000+ 行, 本目录既有测试(`resolveOutputProductLabel.source.spec.ts` 同一手法
 * 用在 production/plans/list.vue)一律走「读源码文本 + 按大括号配平抽出函数体 + 真实执行」，
 * 而不是整份 mount Element Plus + router + pinia。这里延续同一套路, 但断言收在
 * **payload 的实际值**上 (`item.priceUnit` / 提交对象), 不是「源码里有没有这几个字」——
 * 源码字面量只在下面「前提」那条里作为阳性对照出现。
 */

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

/** 按大括号配平抽出具名函数体, 抽不到就抛 —— ⛔ 不返回空串 (与 resolveOutputProductLabel 同款)。 */
function extractFunctionBody(name: string, source: string): string {
  const head = new RegExp(`function\\s+${name}\\s*\\(`).exec(source);
  if (!head) {
    throw new Error(
      `前提不成立: 源码里找不到 ${name}() —— 这条闸没有量到任何东西, 不要把它读成「通过」。`,
    );
  }
  const open = source.indexOf('{', head.index);
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(open, i + 1);
    }
  }
  throw new Error(`前提不成立: ${name}() 的大括号没有配平, 函数体没抽全`);
}

type RelationFixture = {
  purchaseUnit?: string | null;
  baseUnit?: string | null;
  effectivePriceUnit?: string | null;
  effectivePurchasePrice?: number | null;
  defaultPurchasePrice?: number | null;
  priceSource?: string | null;
};

/**
 * 真实执行 `applyRelationPrice` —— 逐字从 list.vue 抽出的函数体 (含它调用的
 * `hasConfiguredPrice`, 同样逐字抽出, 不手写重复实现以免与源码漂移)。
 * `canonicalFn` 由调用方注入, 用来做「换回旧实现会不会复现缺陷」的对照。
 */
function buildApplyRelationPrice(
  canonicalFn: (value: unknown) => string,
): (item: Record<string, unknown>, relation: RelationFixture, expectedPriceUnit: string) => void {
  const hasConfiguredPriceBody = extractFunctionBody('hasConfiguredPrice', listSource);
  const applyRelationPriceBody = extractFunctionBody('applyRelationPrice', listSource);
  // eslint-disable-next-line @typescript-eslint/no-implied-eval
  const factory = new Function(
    'canonicalUnitCodeKeepingCount',
    `
    function hasConfiguredPrice(value) ${hasConfiguredPriceBody}
    return function applyRelationPrice(item, relation, expectedPriceUnit) ${applyRelationPriceBody}
    `,
  );
  return factory(canonicalFn);
}

describe('阻断性缺陷: 「个」/「只」字典单位的物料必须能建采购单', () => {
  it('前提: applyRelationPrice 仍调用 canonicalUnitCodeKeepingCount(源码字面量) —— 下面「真实执行」的结论不是本测试自己臆造的', () => {
    const body = extractFunctionBody('applyRelationPrice', listSource);
    expect(body).toContain('canonicalUnitCodeKeepingCount(expectedPriceUnit)');
    expect(body).toContain('canonicalUnitCodeKeepingCount(relation.effectivePriceUnit)');
    expect(body).toContain('canonicalUnitCodeKeepingCount(relation.purchaseUnit || relation.baseUnit)');
    // 阴性对照: 不许残留裸的 canonicalUnitCode( —— 那正是这条缺陷的成因。
    // (canonicalUnitCode( 不是 canonicalUnitCodeKeepingCount( 的子串 —— 后者在
    // "...Code" 后紧跟的是 "KeepingCount(" 而不是 "(", 所以这条正则不会误伤它。)
    expect(body).not.toMatch(/canonicalUnitCode\(/);
  });

  it('真实执行: L1纸箱(单位「个」)的 priceUnit 保留字面, 不折成 pcs', () => {
    const applyRelationPrice = buildApplyRelationPrice(canonicalUnitCodeKeepingCount);
    const item: Record<string, unknown> = {};
    applyRelationPrice(item, {
      purchaseUnit: '个', baseUnit: '个',
      effectivePriceUnit: null, effectivePurchasePrice: null,
      defaultPurchasePrice: 3.2, priceSource: null,
    }, '个');
    expect(item.priceUnit).toBe('个');
    expect(item.priceUnit).not.toBe('pcs');
    expect(item.unitPrice).toBe(3.2);
    expect(item.priceSource).toBe('SUPPLIER_RELATION');
  });

  it('真实执行: YL-元益漫-黄油鸡(单位「只」)的 priceUnit 保留字面, 不折成 pcs', () => {
    const applyRelationPrice = buildApplyRelationPrice(canonicalUnitCodeKeepingCount);
    const item: Record<string, unknown> = {};
    applyRelationPrice(item, {
      purchaseUnit: '只', baseUnit: '只',
      effectivePriceUnit: '只', effectivePurchasePrice: 18.5,
      defaultPurchasePrice: null, priceSource: 'MATERIAL_REFERENCE',
    }, '只');
    expect(item.priceUnit).toBe('只');
    expect(item.priceUnit).not.toBe('pcs');
    expect(item.unitPrice).toBe(18.5);
  });

  it('⛔ 负对照 1: kg 物料行为不受影响 —— 没有「顺手改坏」可换算单位', () => {
    const applyRelationPrice = buildApplyRelationPrice(canonicalUnitCodeKeepingCount);
    const item: Record<string, unknown> = {};
    applyRelationPrice(item, {
      purchaseUnit: 'kg', baseUnit: 'kg',
      effectivePriceUnit: null, effectivePurchasePrice: null,
      defaultPurchasePrice: 8, priceSource: null,
    }, 'kg');
    expect(item.priceUnit).toBe('kg');
  });

  it('⛔ 负对照 2: 「件」仍然折成 pcs —— 只有「个」/「只」被单独保留, 「件」≡pcs 没被推翻', () => {
    const applyRelationPrice = buildApplyRelationPrice(canonicalUnitCodeKeepingCount);
    const item: Record<string, unknown> = {};
    applyRelationPrice(item, {
      purchaseUnit: '件', baseUnit: '件',
      effectivePriceUnit: null, effectivePurchasePrice: null,
      defaultPurchasePrice: 5, priceSource: null,
    }, '件');
    expect(item.priceUnit).toBe('pcs');
  });

  it('硬约束 C″: 注入旧实现 canonicalUnitCode 会让上面那条断言塌成 pcs —— 证明它不是在守空气', () => {
    // 这不是靠改文件再改回来做的变异对照 (那一步在 PR 描述里另外用 git 做), 而是把
    // 「哪个规范化函数被调用」作为依赖注入点, 直接验证「换回旧行为 == 复现缺陷」。
    const buggyApplyRelationPrice = buildApplyRelationPrice(canonicalUnitCode);
    const item: Record<string, unknown> = {};
    buggyApplyRelationPrice(item, {
      purchaseUnit: '个', baseUnit: '个',
      effectivePriceUnit: null, effectivePurchasePrice: null,
      defaultPurchasePrice: 3.2, priceSource: null,
    }, '个');
    expect(item.priceUnit, '注入旧函数后可观测量没有变化 —— 说明这条判据打不到被测代码').toBe('pcs');
    expect(item.priceUnit).not.toBe('个');
  });

  it('提交前的完整载荷: unit/quantityUnit/priceUnit 与字典单位三方一致 (不是字典「个」/发送「pcs」/显示「件」三个不同的字)', () => {
    // 与 list.vue handleCreate 的 payload.items.map 逐字相同的收尾转换。
    const applyRelationPrice = buildApplyRelationPrice(canonicalUnitCodeKeepingCount);
    const item: { quantityUnit: string; unit: string; priceUnit?: string } = {
      quantityUnit: '个', unit: '个',
    };
    applyRelationPrice(item as unknown as Record<string, unknown>, {
      purchaseUnit: '个', baseUnit: '个',
      effectivePriceUnit: null, effectivePurchasePrice: null,
      defaultPurchasePrice: 3.2, priceSource: null,
    }, '个');

    const submitted = {
      unit: canonicalUnitCodeKeepingCount(item.quantityUnit || item.unit),
      quantityUnit: canonicalUnitCodeKeepingCount(item.quantityUnit || item.unit),
      priceUnit: canonicalUnitCodeKeepingCount(item.priceUnit),
    };

    expect(submitted.unit).toBe('个');
    expect(submitted.quantityUnit).toBe('个');
    expect(submitted.priceUnit).toBe('个');
    // 用户在下拉/详情页看到的字 —— displayUnit 对「个」/「只」原样返回 (RAW_COUNT_LABELS)。
    expect(displayUnit(submitted.unit)).toBe('个');

    // 对照: 缺陷发生前 (未修复) 的三个字互不相同 —— 字典「个」/ 发出去的「pcs」/
    // 显示成的「件」。用同一份数据跑一遍旧函数, 证明修复前后三方一致性的差异是真实的。
    const buggyUnit = canonicalUnitCode(item.quantityUnit || item.unit);
    expect(buggyUnit).toBe('pcs');
    expect(displayUnit(buggyUnit)).toBe('件');
    expect(new Set(['个', buggyUnit, displayUnit(buggyUnit)]).size, '修复前三个字互不相同').toBe(3);
    expect(new Set(['个', submitted.unit, displayUnit(submitted.unit)]).size, '修复后三方归一').toBe(1);
  });
});
