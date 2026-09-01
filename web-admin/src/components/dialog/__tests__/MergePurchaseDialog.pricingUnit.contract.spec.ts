import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, vi } from 'vitest';

import {
  purchaseOrderPricingPayload,
  resolvePurchaseSuggestionUnits,
  resolveUnitSwitch,
} from '@/utils/unitPricing';

/**
 * MergePurchaseDialog 的单位契约。
 *
 * 🔴 为什么这道闸存在 (2026-08-19 实测):
 * 「开始采购」按钮在 2026-07-20 被 `bb17530017` 从销售订单列表删掉, 从那天起单张 SO
 * 也走**合并弹窗** (list.vue 自己的注释:「单张时等价于开始采购但走合并弹窗」)。
 * 而 2026-07-17 `57150296ad` 给后端建议接口加的 `referencePriceUnit`、
 * 2026-07-18 `9b66079820` 给前端加的「参考单价 / 计价单位」列, **全都做在了
 * StartPurchaseDialog 上** —— 那个组件从 7-20 起没有任何 view 挂载它
 * (prod bundle 里 grep 不到「开始采购」, 而「合并采购」有 30 处)。
 * 于是修复上线了、用户看到的那条路没有。
 *
 * 具体后果: 本弹窗当时把 referencePriceUnit 丢掉、payload 不发 priceUnit,
 * 后端 `applyPurchasePriceContract` 便走
 * `firstNonBlank(priceUnit, canonicalQuantityUnit)` 把计价单位**兜底成数量单位**,
 * 且 quantityToPriceFactor 记 1 —— BOM 里的「20 元/盒」被静默记成「20 元/g」。
 */

const source = readFileSync(
  resolve(process.cwd(), 'src/components/dialog/MergePurchaseDialog.vue'),
  'utf8',
);

/**
 * 去掉注释后的源码 —— 阴性对照必须打在**代码**上。
 *
 * 🔴 第一版直接 `expect(source).not.toContain('quantityUnitToLevel1Factor')` 当场误报:
 * 命中的是解释「为什么不许再写一份因子表」的那句注释, 不是任何调用。
 * 这正是本仓记过的「grep 把 docstring 里提到函数名的那行也数上」——
 * 闸要量的是结构, 不是文本。
 */
const code = source
  .replace(/\/\*[\s\S]*?\*\//g, '')       // 块注释 (含 JSDoc)
  .replace(/^\s*\/\/.*$/gm, '')            // 行注释
  .replace(/<!--[\s\S]*?-->/g, '');        // template 注释

/**
 * 取一个顶格函数的函数体 (到下一个顶格 `}` 为止) —— 与
 * `views/sales/orders/__tests__/salesOrderUnitPayload.source.spec.ts` 同一手法。
 *
 * 🔴 为什么必须按函数体断言: 变异 M6 把 `await resolveUnitSwitch(...)` 整个换掉之后,
 * 全文 `toContain('resolveUnitSwitch')` **仍然是绿的** —— 因为 import 那一行还留着这个名字。
 * 闸当时量的是「名字出现过」, 不是「它被调用了」。这正是本仓记过的
 * 「grep 把 import/docstring 也数进去」那一类。
 */
function functionBody(src: string, declaration: string): string {
  const start = src.indexOf(declaration);
  if (start < 0) return '';
  const end = src.indexOf('\n}\n', start);
  if (end < 0) return '';
  return src.slice(start, end);
}

describe('MergePurchaseDialog pricing-unit contract', () => {
  it('reads the authoritative price unit instead of assuming the quantity unit', () => {
    expect(source).toContain('resolvePurchaseSuggestionUnits');
    expect(source).toContain('referencePriceUnit');
    expect(source).toContain('priceUnit: units.priceUnit');
  });

  it('submits priceUnit so the backend cannot fall back to the quantity unit', () => {
    expect(source).toContain('purchaseOrderPricingPayload');
    expect(source).toContain('priceUnit: it.priceUnit');
    expect(source).toContain('quantityUnit: it.quantityUnit');
    // ⛔ 阴性对照: 不许退回「只发 unit + unitPrice」的裸对象字面量。
    expect(source).not.toMatch(/items:\s*editableItems\.value\.map\(\(it\)\s*=>\s*\(\{/);
  });

  it('shows the pricing unit next to the number so 20 is never read as 20 元/g', () => {
    expect(source).toContain('formatPriceUnit(row.priceUnit)');
    expect(source).toContain('参考单价 / 计价单位');
  });

  it('labels the read-only demand columns with the FROZEN suggestion unit', () => {
    // 🔴 这三个数是后端按 BOM 口径给的, 换采购单位不会改动它们。
    // 用可变的 quantityUnit 当标签, 「12.50 kg」会被重新标成「12.50 箱」。
    for (const field of ['_requiredQuantity', '_currentStock', '_netRequired']) {
      expect(source).toContain(`row.${field}?.toFixed(2) }} {{ displayUnit(row._suggestionUnit) }}`);
    }
    // 阴性对照: 那三列绝不能回到可变单位。
    expect(source).not.toContain('_requiredQuantity?.toFixed(2) }} {{ displayUnit(row.quantityUnit) }}');
  });

  it('delegates unit conversion to the backend and never computes a local factor', () => {
    // 🔴 断言打在**函数体**上, 不是全文 —— 全文匹配会被 import 那一行喂饱 (见 M6)。
    const body = functionBody(code, 'async function onQuantityUnitChange(');
    // 阳性对照: 函数体真的取到了, 否则下面几条全是恒真式。
    expect(body.length, 'onQuantityUnitChange 没定位到 —— 下面的断言全部作废')
      .toBeGreaterThan(200);

    expect(body, '换算必须交给共享决策点').toContain('await resolveUnitSwitch(');
    expect(body, '必须真的调后端 convert 接口').toContain('convertUnit(props.factoryId');
    expect(body).toContain("scene: 'PROCUREMENT'");
    // 失败要回滚, 不能留下「单位换了、数字没换」的行
    expect(body).toContain('item.quantityUnit = outcome.unit;');

    // ⛔ 阴性对照打在**代码**上 (已剥注释): 不许把 StartPurchaseDialog 那份
    //    「只认包装层级」的因子表抄过来。注释里可以提它的名字, 代码里不许有。
    expect(code).not.toContain('quantityUnitToLevel1Factor');
    expect(code).not.toContain('level1PerLevel2 * level2PerLevel3');
  });

  /**
   * 以下三条从 `StartPurchaseDialog.contract.spec.ts` 迁移过来 ——
   * 那个组件 2026-08-19 被删 (自 7-20 起就没有任何 view 挂载它)。
   * ⛔ 载体没了不等于契约没了: 它守的东西现在由**合并弹窗**承担, 断言跟着搬家。
   */
  it('keeps the supplier optional while still offering a way to fill it', () => {
    expect(source).toContain('supplierId: supplierId.value || null');
    expect(source).toContain('供应商（选填）');
    expect(source).toContain('`/${props.factoryId}/suppliers`');
  });

  it('submits the packaging fields the receiving flow snapshots', () => {
    expect(source).toContain('specification: it.specification || null');
    expect(source).toContain('boxQuantity: it.boxQuantity');
    expect(source).toContain('material-packaging/by-material');
    expect(source).toContain('materialPackagingSpecId: packagingByMaterial.value');
  });

  it('invalidates the numeric price when its pricing unit changes', () => {
    expect(source).toContain('function onPriceUnitChange');
    expect(source).toContain('item.unitPrice = null');
    expect(source).toContain('@change="onPriceUnitChange(row)"');
    expect(source).toContain('切换计价单位后需重新输入对应单价');
  });

  it('the comment-stripper itself works (positive + negative control)', () => {
    // 阳性: 剥注释后, 真实代码仍在。
    expect(code).toContain('async function onQuantityUnitChange');
    // 阴性: 只出现在注释里的那个名字, 剥完必须消失 —— 否则上一条断言是恒真式。
    expect(source).toContain('quantityUnitToLevel1Factor');
    expect(code).not.toContain('quantityUnitToLevel1Factor');
  });
});

/**
 * 行为断言 —— 上面是源码闸 (看得见「接上了没有」), 这里跑真实的共享函数。
 */
describe('purchase suggestion pricing units survive the merge dialog mapping', () => {
  it('keeps 盒-denominated price separate from g-denominated quantity', () => {
    const backendItem = { unit: 'g', quantityUnit: 'g', referencePriceUnit: '盒' };

    const units = resolvePurchaseSuggestionUnits(backendItem);

    // 🔴 2026-09-02 (缺陷 33 根治): 内部比较键不再是 box —— unitPricing.ts 的
    // UNIT_ALIASES 目标码改成后端 normalize() 的不动点之后, canonicalUnitCode('盒')
    // 直接返回 '盒' 本身。el-select 的 value / 包装规格匹配现在用的就是这个中文码,
    // 不再需要经 storageUnitCode 才翻回中文 (那一步现在是 no-op, 见下方 payload 断言)。
    expect(units.quantityUnit).toBe('g');
    expect(units.priceUnit).toBe('盒');
    expect(units.priceUnit).not.toBe(units.quantityUnit);

    const payload = purchaseOrderPricingPayload({
      ...backendItem,
      quantityUnit: units.quantityUnit,
      priceUnit: units.priceUnit,
    });

    // 🔴 但**发出去**的是用户看到的那个字 —— 与销售侧 `9fcde2af43` 同一条规则。
    // 采购侧原先还在发 box, 那是「同一个缺陷只改一半」造出来的漂移。
    expect(payload.priceUnit).toBe('盒');
    expect(payload.unit).toBe('g');          // 可换算单位两侧同为拉丁符号
    expect(payload.quantityUnit).toBe('g');
  });

  it('translates package/count units at the request boundary, not the comparison key', () => {
    const payload = purchaseOrderPricingPayload({
      unit: 'case', quantityUnit: 'case', referencePriceUnit: 'pcs',
    });
    expect(payload.unit).toBe('箱');
    expect(payload.quantityUnit).toBe('箱');
    expect(payload.priceUnit).toBe('件');
    // 阴性对照: 英文码不许再出现在请求体里 (库里已有 box 的历史行就是这么写进去的)。
    expect([payload.unit, payload.quantityUnit, payload.priceUnit]).not.toContain('case');
    expect([payload.unit, payload.quantityUnit, payload.priceUnit]).not.toContain('pcs');
  });

  it('falls back to the quantity unit only when the backend really sent no price unit', () => {
    const units = resolvePurchaseSuggestionUnits({ unit: 'kg' });
    expect(units.priceUnit).toBe('kg');
    expect(units.quantityUnit).toBe('kg');
  });
});

/**
 * 缺陷 33 的兄弟场景 (2026-09-02): 物料字典单位是「个」/「只」的原料, 经**合并采购弹窗**
 * 这条路径提交采购单。#474 只修了 `procurement/orders/list.vue` 的 7 处调用点 ——
 * `MergePurchaseDialog.vue` 走的是完全不同的组件, 从没接过那次修复, 同一形状的
 * 422/400 会在这条路径重演。根治点在 `unitPricing.ts` 的 `UNIT_ALIASES` 本身,
 * 所以这里断言的是**共享函数**的行为 —— MergePurchaseDialog.vue 通过
 * `resolvePurchaseSuggestionUnits` + `purchaseOrderPricingPayload` 复用同一份逻辑,
 * 断言打在这两个函数上就等于打在了本弹窗的提交路径上。
 */
describe('缺陷 33: 字典单位「个」/「只」的物料经合并采购弹窗提交, 不再折成「件」', () => {
  it('真实样本 L1纸箱 (字典单位「个」): quantityUnit 与 priceUnit 全程保留「个」', () => {
    const backendItem = { unit: '个', quantityUnit: '个', referencePriceUnit: '个' };
    const units = resolvePurchaseSuggestionUnits(backendItem);
    expect(units.quantityUnit).toBe('个');
    expect(units.priceUnit).toBe('个');

    const payload = purchaseOrderPricingPayload({ ...backendItem, ...units });
    expect(payload.unit).toBe('个');
    expect(payload.quantityUnit).toBe('个');
    expect(payload.priceUnit).toBe('个');
    // 阴性对照: 「件」不许出现 —— 那正是 422 的成因 (物料字典单位「个」!= 提交单位「件」)
    expect([payload.unit, payload.quantityUnit, payload.priceUnit]).not.toContain('件');
    expect([payload.unit, payload.quantityUnit, payload.priceUnit]).not.toContain('pcs');
  });

  it('真实样本 YL-元益漫-黄油鸡 (字典单位「只」): 全程保留「只」', () => {
    const backendItem = { unit: '只', quantityUnit: '只', referencePriceUnit: '只' };
    const units = resolvePurchaseSuggestionUnits(backendItem);
    expect(units.quantityUnit).toBe('只');
    expect(units.priceUnit).toBe('只');

    const payload = purchaseOrderPricingPayload({ ...backendItem, ...units });
    expect(payload.unit).toBe('只');
    expect(payload.quantityUnit).toBe('只');
    expect(payload.priceUnit).toBe('只');
    expect([payload.unit, payload.quantityUnit, payload.priceUnit]).not.toContain('件');
    expect([payload.unit, payload.quantityUnit, payload.priceUnit]).not.toContain('pcs');
  });

  it('阴性对照: 「件」仍然正确地保持为「件」(没有过度纠正)', () => {
    const backendItem = { unit: '件', quantityUnit: '件', referencePriceUnit: '件' };
    const units = resolvePurchaseSuggestionUnits(backendItem);
    const payload = purchaseOrderPricingPayload({ ...backendItem, ...units });
    expect(payload.unit).toBe('件');
    expect(payload.quantityUnit).toBe('件');
    expect(payload.priceUnit).toBe('件');
  });

  it('阴性对照: kg 物料不受影响 (没有连坐改坏可换算单位)', () => {
    const backendItem = { unit: 'kg', quantityUnit: 'kg', referencePriceUnit: 'kg' };
    const units = resolvePurchaseSuggestionUnits(backendItem);
    const payload = purchaseOrderPricingPayload({ ...backendItem, ...units });
    expect(payload.unit).toBe('kg');
    expect(payload.quantityUnit).toBe('kg');
    expect(payload.priceUnit).toBe('kg');
  });
});

/**
 * `resolveUnitSwitch` 的行为 —— 这是「g 与 kg 无法自动换算」那条 bug 的正面覆盖。
 * 判据不是「前端算得对」, 而是「前端把算术交给了后端, 并且失败时不留脏数据」。
 */
describe('resolveUnitSwitch delegates to the backend and fails closed', () => {
  it('adopts the backend quantity for g -> kg (the exact 2026-07-20 complaint)', async () => {
    const convert = vi.fn().mockResolvedValue({ status: 'CONVERTED', quantity: 1.25 });

    const outcome = await resolveUnitSwitch({ quantity: 1250, fromUnit: 'g', toUnit: 'kg' }, convert);

    expect(convert).toHaveBeenCalledWith(1250, 'g', 'kg');
    expect(outcome).toEqual({ ok: true, quantity: 1.25, unit: 'kg' });
  });

  it('does NOT do the arithmetic itself — it returns whatever the backend said', async () => {
    // 后端给一个「不合物理」的数, 前端也必须原样采用。
    // 这条断言的作用是**证明前端没有自己的因子表**: 如果它偷偷算 g→kg,
    // 结果会是 1.25 而不是 999。
    const convert = vi.fn().mockResolvedValue({ status: 'CONVERTED', quantity: 999 });

    const outcome = await resolveUnitSwitch({ quantity: 1250, fromUnit: 'g', toUnit: 'kg' }, convert);

    expect(outcome).toEqual({ ok: true, quantity: 999, unit: 'kg' });
  });

  it('rolls back to the original unit and surfaces the backend message when unconvertible', async () => {
    const convert = vi.fn().mockResolvedValue({
      status: 'PRODUCT_CONVERSION_MISSING',
      quantity: null,
      message: '未找到产品有效换算关系',
    });

    const outcome = await resolveUnitSwitch({ quantity: 10, fromUnit: 'g', toUnit: '箱' }, convert);

    expect(outcome.ok).toBe(false);
    // fail closed: 单位回滚到原来的, 绝不留下「单位换了、数字没换」的行。
    expect(outcome.unit).toBe('g');
    if (!outcome.ok) expect(outcome.message).toBe('未找到产品有效换算关系');
  });

  it('fails closed when the backend call throws, not silently keeping the number', async () => {
    const convert = vi.fn().mockRejectedValue(new Error('network down'));

    const outcome = await resolveUnitSwitch({ quantity: 10, fromUnit: 'kg', toUnit: 'case' }, convert);

    expect(outcome.ok).toBe(false);
    expect(outcome.unit).toBe('kg');
  });

  it('short-circuits identical units without calling the backend', async () => {
    const convert = vi.fn();

    const outcome = await resolveUnitSwitch({ quantity: 7, fromUnit: '千克', toUnit: 'kg' }, convert);

    // 千克 与 kg 是同一个规范码 ⇒ 不该打网络。
    expect(convert).not.toHaveBeenCalled();
    expect(outcome).toEqual({ ok: true, quantity: 7, unit: 'kg' });
  });
});
