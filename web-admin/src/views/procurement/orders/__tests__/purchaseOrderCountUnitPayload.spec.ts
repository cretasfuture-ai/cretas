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
 *
 * <h2>⚠️ 2026-09-02 (同日追加): 根治点从「调用点改用 KeepingCount」上移到「表本身」</h2>
 * 本文件原先记录的根因是「list.vue 的调用点用了 canonicalUnitCode 而不是
 * canonicalUnitCodeKeepingCount」—— 这仍然是**当天上午**发生的真实缺陷与真实修复
 * (#474)。**当天晚些时候**又发现同一形状的缺陷在 `MergePurchaseDialog.vue`
 * 重演 (它从没接过 #474 的修复), 于是把根治点上移: `unitPricing.ts` 的
 * `UNIT_ALIASES` 目标码本身改成后端 `normalize()` 的不动点, `canonicalUnitCode`
 * 现在对 个/只/件 也不再折叠。下面测试里凡是"证明旧行为会复现缺陷"的对照,
 * 已改用手工构造的旧折叠函数, 不再拿真实的 `canonicalUnitCode` 当"旧实现"用
 * (它已经是修复后的实现)。
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

  it('⛔ 负对照 2: 「件」保持为「件」(与 pcs 是同一个单位, 只是不再折叠成内部别名)', () => {
    // 🔴 2026-09-02 (缺陷 33 根治): UNIT_ALIASES 的目标码改成后端 normalize() 的不动点
    // 之后, canonicalUnitCode('件') 直接返回 '件' 本身 (不再折成 'pcs')。「件 ≡ pcs」这个
    // 事实没有被推翻 —— canonicalUnitCode('pcs') 依然是 '件' (两个写法归到同一个规范码),
    // 只是「规范码」这个身份本身从英文别名换成了后端真正的中文码。
    const applyRelationPrice = buildApplyRelationPrice(canonicalUnitCodeKeepingCount);
    const item: Record<string, unknown> = {};
    applyRelationPrice(item, {
      purchaseUnit: '件', baseUnit: '件',
      effectivePriceUnit: null, effectivePurchasePrice: null,
      defaultPurchasePrice: 5, priceSource: null,
    }, '件');
    expect(item.priceUnit).toBe('件');
    expect(canonicalUnitCode('pcs')).toBe('件'); // 两个写法仍然归到同一处
  });

  /**
   * 🔴 2026-09-02 (缺陷 33 根治): 根治点搬到了 `UNIT_ALIASES` 表本身, `canonicalUnitCode`
   * 现在对 个/只/件 也不折叠了 —— 上面那条「注入真实的旧 canonicalUnitCode 会复现缺陷」
   * 的证明**不再成立**(它已经不是"旧"的了)。这里改用一个**手工构造**的、精确复刻
   * "2026-09-02 前 pcs 折叠写法"的替身函数 (不是从 unitPricing.ts 抄一份真表, 那会造出
   * 硬约束 8 警告过的第二份单位表), 证明依赖注入这条缝本身仍然有效: 换一个真的会折叠
   * 个/只/件的实现进去, 断言确实会塌。
   */
  it('硬约束 C″: 注入手工构造的旧折叠实现会让断言塌成 pcs —— 证明依赖注入缝仍然有效', () => {
    const oldFoldingCanonicalUnitCode = (value: unknown): string => {
      const raw = String(value ?? '').trim();
      const OLD_TARGETS: Record<string, string> = {
        pcs: 'pcs', pc: 'pcs', piece: 'pcs', pieces: 'pcs', '个': 'pcs', '只': 'pcs', '件': 'pcs',
      };
      return OLD_TARGETS[raw] || raw;
    };
    // 阳性对照: 先证明这个替身函数真的复刻了旧行为, 不是随手写的空函数。
    expect(oldFoldingCanonicalUnitCode('个')).toBe('pcs');

    const buggyApplyRelationPrice = buildApplyRelationPrice(oldFoldingCanonicalUnitCode);
    const item: Record<string, unknown> = {};
    buggyApplyRelationPrice(item, {
      purchaseUnit: '个', baseUnit: '个',
      effectivePriceUnit: null, effectivePurchasePrice: null,
      defaultPurchasePrice: 3.2, priceSource: null,
    }, '个');
    expect(item.priceUnit, '注入旧函数后可观测量没有变化 —— 说明这条判据打不到被测代码').toBe('pcs');
    expect(item.priceUnit).not.toBe('个');

    // 与之相对: 注入当前真实的 canonicalUnitCode (已根治) 不会复现缺陷。
    const fixedApplyRelationPrice = buildApplyRelationPrice(canonicalUnitCode);
    const fixedItem: Record<string, unknown> = {};
    fixedApplyRelationPrice(fixedItem, {
      purchaseUnit: '个', baseUnit: '个',
      effectivePriceUnit: null, effectivePurchasePrice: null,
      defaultPurchasePrice: 3.2, priceSource: null,
    }, '个');
    expect(fixedItem.priceUnit).toBe('个');
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

    // 🔴 2026-09-02: 「用同一份数据跑一遍旧函数」这句话本身就是缺陷描述 —— 根治后
    // canonicalUnitCode('个') 已经不是 'pcs' 了 (下面单独验证一次, 作为回归网)。
    // 「修复前三个字互不相同」这条对照因此改用手工构造的旧折叠函数复现, 不再用
    // 当前真实的 canonicalUnitCode (它已经是修复后的实现, 拿它做"旧行为"对照
    // 会是一句假话)。
    expect(canonicalUnitCode(item.quantityUnit || item.unit), '回归网: 根治后 canonicalUnitCode 不再折叠').toBe('个');

    const oldFoldingCanonicalUnitCode = (value: unknown): string => {
      const raw = String(value ?? '').trim();
      return raw === '个' || raw === '只' || raw === '件' || raw === 'pcs' ? 'pcs' : raw;
    };
    const buggyUnit = oldFoldingCanonicalUnitCode(item.quantityUnit || item.unit);
    expect(buggyUnit).toBe('pcs');
    expect(displayUnit(buggyUnit)).toBe('件');
    expect(new Set(['个', buggyUnit, displayUnit(buggyUnit)]).size, '修复前(手工构造对照)三个字互不相同').toBe(3);
    expect(new Set(['个', submitted.unit, displayUnit(submitted.unit)]).size, '修复后三方归一').toBe(1);
  });
});
