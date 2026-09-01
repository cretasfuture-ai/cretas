import { describe, expect, it } from 'vitest';
import {
  canonicalUnitCode,
  displayUnit,
  formatPriceUnit,
  formatUnitPrice,
  pricingAmountPreview,
  purchaseOrderPricingPayload,
  resolvePurchaseSuggestionUnits,
  mergeCanonicalUnitOptions,
  sameUnit,
} from '../unitPricing';

describe('formatUnitPrice —— 有小数才显示小数 (owner 2026-08-20)', () => {
  it('整数价不带小数点, 小数价保留到最后一位有效数字', () => {
    expect(formatUnitPrice(23)).toBe('23');
    expect(formatUnitPrice(23.5)).toBe('23.5');
    expect(formatUnitPrice(23.4567)).toBe('23.4567');
    // 阴性对照: 就是这个长相被判为「四个零没有信息量」, 不许回来
    expect(formatUnitPrice(23)).not.toBe('23.0000');
    expect(formatUnitPrice(23.5)).not.toBe('23.5000');
  });

  it('整数位上的 0 不会被尾零正则一起咬掉', () => {
    // `\.?0+$` 若写错成 `0+$` 就会把 230 咬成 23 —— 这条是那个错法的对照。
    expect(formatUnitPrice(230)).toBe('230');
    expect(formatUnitPrice(100)).toBe('100');
    expect(formatUnitPrice(20.05)).toBe('20.05');
  });

  it('读不到就说读不到, ⛔ 不兜底成 0', () => {
    expect(formatUnitPrice(null)).toBe('-');
    expect(formatUnitPrice(undefined)).toBe('-');
    expect(formatUnitPrice('')).toBe('-');
    expect(formatUnitPrice('abc')).toBe('-');
    // 0 是合法价 (赠品/试用), 必须与「没有值」区分开
    expect(formatUnitPrice(0)).toBe('0');
  });

  it('不折成万/亿, 也不吃掉第 3、4 位小数', () => {
    // 这两条钉的是「⛔ 不要改用 formatNumber」那句注释所守的行为
    expect(formatUnitPrice(23000)).toBe('23000');
    expect(formatUnitPrice(23000)).not.toContain('万');
    expect(formatUnitPrice(1.2345)).toBe('1.2345');
  });

  it('字符串数字也能吃 (后端 BigDecimal 有时序列化成字符串)', () => {
    expect(formatUnitPrice('23.0000')).toBe('23');
    expect(formatUnitPrice('23.4500')).toBe('23.45');
  });
});

describe('unitPricing', () => {
  /**
   * 🔴 #1672 回归网。件/个/只 都归一到 `pcs`, 但**互相不是同一个单位**
   * (`sameUnit` 的 DISTINCT_COUNT_LABELS: 一只鸡不是一件包材)。
   * 展示时若走一遍规范码再取 label, 三个会被渲染成同一个字 —— 用户填「件」
   * 的产品界面上变成「只」。#1672 正是这么回归的, 红了 8 天没人看见。
   */
  it('计数单位展示不得把一个中文标签改写成另一个', () => {
    expect(displayUnit('件')).toBe('件');
    expect(displayUnit('个')).toBe('个');
    expect(displayUnit('只')).toBe('只');
  });

  /**
   * 通用不变式: **用户填的单位, 展示出来必须还是同一个单位**。
   * 这条比上面三行更耐用 —— 以后再往 UNIT_LABELS 里改任何一条,
   * 只要它把某个单位渲染成了「别的单位」, 这里就会红。
   */
  it('displayUnit 的产物必须与输入是同一个单位', () => {
    for (const unit of ['件', '个', '只', '盒', '箱', '袋', '片', 'box', 'case', 'bag', 'slice']) {
      expect(sameUnit(unit, displayUnit(unit))).toBe(true);
    }
  });

  /**
   * ✅ **口径已定案 (2026-08-01)**: 裸规范码 `pcs` 展示成「件」。
   *
   * 判据不是偏好, 是**两侧同源规则自己给出的**: 前端 `DISTINCT_COUNT_LABELS` 与后端
   * `UnitContractServiceImpl.DISTINCT_COUNT_LABELS` 都是 `{只, 个}` —— 两边都把「件」
   * 排除在外, 即两边都认定 **件 ≡ pcs**。所以把 pcs 渲染成「只」等于断言 只 ≡ pcs,
   * 与该规则直接矛盾(旧行为下 `sameUnit('pcs', displayUnit('pcs'))` 是 **false**,
   * 一个单位不等于它自己的展示标签)。Java 侧 `UnitDisplayNames` 同样是「件」。
   *
   * 这条现在钉的是**不变式**而不是当前值: 展示标签必须与它自己的规范码互认。
   */
  it('裸 pcs 展示成「件」, 且不再与 sameUnit 自相矛盾', () => {
    expect(displayUnit('pcs')).toBe('件');
    expect(sameUnit('pcs', displayUnit('pcs'))).toBe(true);
  });

  /**
   * 🔴 用户手填的 只/个/件 必须**原样保留** —— 这是 #1672 造成、#2097 修掉的线上缺陷
   * (用户填「件」的产品界面上显示成「只」)。上面改 `UNIT_LABELS.pcs` 只影响裸码,
   * 绝不能把这条一起带翻。
   */
  it('用户手填的 只/个/件 原样保留, 不被 pcs 口径影响', () => {
    expect(displayUnit('只')).toBe('只');
    expect(displayUnit('个')).toBe('个');
    expect(displayUnit('件')).toBe('件');
  });

  /**
   * 🔴 2026-09-02 (缺陷 33 根治): `canonicalUnitCode` 的目标码改成后端 `normalize()`
   * 的不动点之后, `canonicalUnitCode('盒')` 直接就是 `'盒'` (不再折成 `box`) ——
   * 这里原先断言的是「折成英文别名」, 现在改断言「折成后端真正的规范码 (中文)」,
   * 且不动点意味着 `canonicalUnitCode(canonicalUnitCode(x)) === canonicalUnitCode(x)`。
   * `displayUnit`/`formatPriceUnit` 的输入输出不受影响 —— 它们对英文别名和中文规范码
   * 给出同一个展示结果 (查表命中 vs 回落到 code 本身, 见 UNIT_LABELS 注释)。
   */
  it('normalizes canonical aliases to the backend fixed point without changing the price value', () => {
    expect(canonicalUnitCode('公斤')).toBe('kg');
    expect(canonicalUnitCode('盒')).toBe('盒');
    expect(canonicalUnitCode('box')).toBe('盒');
    // 不动点: 目标码再喂一次自己, 结果不变
    expect(canonicalUnitCode(canonicalUnitCode('盒'))).toBe('盒');
    expect(displayUnit('box')).toBe('盒');
    expect(canonicalUnitCode('箱')).toBe('箱');
    expect(canonicalUnitCode('case')).toBe('箱');
    expect(displayUnit('case')).toBe('箱');
    expect(canonicalUnitCode('片')).toBe('片');
    expect(canonicalUnitCode('slice')).toBe('片');
    expect(displayUnit('slice')).toBe('片');
    expect(canonicalUnitCode('g')).toBe('g');
    expect(canonicalUnitCode('kg')).toBe('kg');
    expect(displayUnit('g')).toBe('g');
    expect(displayUnit('kg')).toBe('kg');
    // 遗留复合写法 'pcs:只' 只看 group[1]('pcs'), 折到 UNIT_ALIASES['pcs'] 现在的目标 '件'
    // (不看 group[2] 具体标签 —— 见 canonicalUnitCode 自己的注释, 这是内部比较键允许的折叠)。
    expect(canonicalUnitCode('pcs:只')).toBe('件');
    // 但 displayUnit 自己有独立的 legacyCountComposite 分支, 原样保留用户当初填的标签,
    // 不受 canonicalUnitCode 目标码变更影响 —— 这条阴性对照钉住两者不是同一份逻辑。
    expect(displayUnit('pcs:只')).toBe('只');
    // 裸规范码走 2026-08-01 定案的「件」
    expect(displayUnit('pcs')).toBe('件');
    expect(formatPriceUnit('box')).toBe('元/盒');
    expect(formatPriceUnit('case')).toBe('元/箱');
    expect(formatPriceUnit('slice')).toBe('元/片');
    expect(formatPriceUnit('g')).toBe('元/g');
    expect(formatPriceUnit('kg')).toBe('元/kg');
  });

  /**
   * 🔴 2026-09-02: 去重键改成中文规范码之后, `mergeCanonicalUnitOptions` 的输出
   * 也跟着变成中文 —— 这不是本函数自己的逻辑变了, 是它调用的 `canonicalUnitCode`
   * 变了。'ml' 与 '毫升' 都折到小写 'ml' (与后端不动点对齐, 见 UNIT_ALIASES 注释),
   * 不再是历史上刻意选的大写 'mL'。
   */
  it('builds price-unit options from material/catalog values without a static whitelist', () => {
    expect(mergeCanonicalUnitOptions(['kg', '袋', 'mL'], 'CUSTOM_TRAY', 'bag'))
      .toEqual(['kg', '袋', 'ml', 'CUSTOM_TRAY']);
  });

  it('uses backend lineAmount before any client calculation', () => {
    expect(pricingAmountPreview({ quantity: 100_000, quantityUnit: 'g', unitPrice: 30, priceUnit: 'kg', lineAmount: 3_000 }))
      .toEqual({ amount: 3_000, source: 'backend-line-amount', message: '' });
  });

  it('uses backend convertedPricingQuantity for different units', () => {
    expect(pricingAmountPreview({ quantity: 100_000, quantityUnit: 'g', unitPrice: 30, priceUnit: 'kg', convertedPricingQuantity: 100 }).amount)
      .toBe(3_000);
  });

  it('fails closed when units differ and no authoritative conversion exists', () => {
    expect(pricingAmountPreview({ quantity: 100_000, quantityUnit: 'g', unitPrice: 30, priceUnit: 'kg' }))
      .toEqual({ amount: null, source: 'pending', message: '保存后由系统换算' });
  });

  it('allows direct multiplication only when canonical units match', () => {
    expect(pricingAmountPreview({ quantity: 2, quantityUnit: '盒', unitPrice: 15, priceUnit: 'box' }).amount).toBe(30);
  });

  it('keeps referencePriceUnit as the PO priceUnit when suggestion quantity is in another unit', () => {
    const suggestion = {
      quantity: 1000,
      unit: 'g',
      quantityUnit: 'g',
      referenceUnitPrice: 20,
      referencePriceUnit: 'kg',
      priceUnit: 'g',
    };

    expect(resolvePurchaseSuggestionUnits(suggestion)).toEqual({ quantityUnit: 'g', priceUnit: 'kg' });
    expect(purchaseOrderPricingPayload(suggestion)).toMatchObject({
      unit: 'g',
      quantityUnit: 'g',
      priceUnit: 'kg',
    });
  });
});
