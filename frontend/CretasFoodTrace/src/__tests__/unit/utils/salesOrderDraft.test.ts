import type { ProductPackagingSpec } from '../../../services/api/productTypeApiClient';
import {
  boxQuantityFor,
  decideTaxRate,
  isWeighPricedSpecification,
  optionalTaxRate,
  packagingSpecsForUnit,
  salesUnitOptions,
  specificationSnapshot,
} from '../../../utils/salesOrderDraft';

const specs: ProductPackagingSpec[] = [
  { id: 'box-12', name: '12盒/箱', packageUnit: '箱', baseUnit: '盒', conversionFactor: 12, defaultSpec: true, active: true, sortOrder: 1 },
  { id: 'box-24', name: '24盒/箱', packageUnit: '箱', baseUnit: '盒', conversionFactor: 24, defaultSpec: false, active: true, sortOrder: 2 },
  { id: 'old', name: '旧规格', packageUnit: '件', baseUnit: '盒', conversionFactor: 1, defaultSpec: false, active: false, sortOrder: 3 },
];

const boxSpec = specs[0]!;

describe('sales order draft contracts', () => {
  it('offers the SKU base unit and active packaging units only', () => {
    expect(salesUnitOptions('盒', specs)).toEqual(['盒', '箱']);
    expect(packagingSpecsForUnit('箱', specs).map((spec) => spec.id)).toEqual(['box-12', 'box-24']);
  });

  it('keeps a blank tax rate undefined so the backend customer default applies', () => {
    expect(optionalTaxRate('')).toBeUndefined();
    expect(optionalTaxRate('13')).toBe(13);
    expect(optionalTaxRate('101')).toBeUndefined();
  });
});

/**
 * 税率三态。被守的行为：**留空不许静默变成 0%**。
 *
 * 后端链是 item.taxRate → SalesOrder.defaultTaxRate（prefill 自 customer）→ 0
 * （SalesServiceImpl:685-688），最后那一跳是静默的。
 * prod 实测 29 个客户里 22 个没配默认税率 ⇒ 对四分之三的客户，留空 = 把税算没。
 */
describe('decideTaxRate —— 「不知道」不许折叠进「0」', () => {
  it('用户填了就用用户填的（0 也算填了，免税品是合法的）', () => {
    expect(decideTaxRate('13', 9)).toEqual({ kind: 'explicit', taxRate: 13 });
    expect(decideTaxRate('0', undefined)).toEqual({ kind: 'explicit', taxRate: 0 });
  });

  it('填了非法值 → invalid（不是 must-ask，两者的提示不一样）', () => {
    expect(decideTaxRate('101', 13)).toEqual({ kind: 'invalid' });
    expect(decideTaxRate('abc', 13)).toEqual({ kind: 'invalid' });
    expect(decideTaxRate('-1', 13)).toEqual({ kind: 'invalid' });
  });

  it('留空 + 客户有默认 → 交给后端第 2 层（这正是三层链存在的理由）', () => {
    expect(decideTaxRate('', 13)).toEqual({ kind: 'customer-default', customerDefaultTaxRate: 13 });
  });

  it('🔴 留空 + 客户有默认 0% → 仍是 customer-default，不是 must-ask', () => {
    // 0 是合法的默认税率（免税品）。这里判的必须是 null/undefined，不是 falsy ——
    // 写成 `if (!customerDefault)` 会把「客户明确配了 0%」误判成「没配」。
    expect(decideTaxRate('', 0)).toEqual({ kind: 'customer-default', customerDefaultTaxRate: 0 });
  });

  it('🔴 留空 + 客户没配默认 → must-ask（后端会兜底成 0，把税算没）', () => {
    expect(decideTaxRate('', undefined)).toEqual({ kind: 'must-ask' });
    expect(decideTaxRate('', null)).toEqual({ kind: 'must-ask' });
    expect(decideTaxRate('   ', undefined)).toEqual({ kind: 'must-ask' });
  });
});

/**
 * 箱数。被守的行为：**算不出来返回 undefined，绝不返回 0**。
 * 箱数是仓库拣货照着数的数 —— 一个假的 0 比一个空格危险得多（形态 A¹⁰）。
 */
describe('boxQuantityFor —— 算不出来就说不知道', () => {
  it('阳性对照：正常情况真的算得出来（否则下面每条 undefined 都是恒真式）', () => {
    expect(boxQuantityFor({ quantity: '24', unit: '盒', packagingSpec: boxSpec })).toBe(2);
  });

  it('按包装单位下单 → 箱数就是数量本身', () => {
    expect(boxQuantityFor({ quantity: '5', unit: '箱', packagingSpec: boxSpec })).toBe(5);
  });

  it('按基本单位下单 → 数量 / 换算系数，取整口径与 web-admin calcBox 逐字相同', () => {
    // 25/12 = 2.0833… → Math.round(x*100)/100 = 2.08
    expect(boxQuantityFor({ quantity: '25', unit: '盒', packagingSpec: boxSpec })).toBe(2.08);
  });

  it('没有箱规行但 SKU 配了箱规系数 → 按系数换算', () => {
    expect(boxQuantityFor({
      quantity: '30', unit: 'kg', productBaseUnit: 'kg', productBoxConversionCoefficient: 10,
    })).toBe(3);
  });

  it('🔴 抄码品不计箱数', () => {
    expect(isWeighPricedSpecification('抄码')).toBe(true);
    expect(isWeighPricedSpecification(' 抄码 ')).toBe(true);
    // 精确匹配，不用 includes —— 与 web-admin isAbacaItem 同口径（避免误报）。
    expect(isWeighPricedSpecification('抄码规格箱')).toBe(false);
    expect(boxQuantityFor({ quantity: '24', unit: '盒', specification: '抄码', packagingSpec: boxSpec }))
      .toBeUndefined();
  });

  it('🔴 数量非法 / 为 0 / 为负 → undefined，⛔ 不是 0', () => {
    for (const quantity of ['', '0', '-3', 'abc']) {
      expect(boxQuantityFor({ quantity, unit: '盒', packagingSpec: boxSpec })).toBeUndefined();
    }
  });

  it('🔴 单位与箱规对不上 → undefined（不猜，不做单位归一）', () => {
    // RN 侧没有 canonicalUnitCode 那张等价表。照抄一份正是漂移的来源 ⇒ 比不上就说不知道。
    expect(boxQuantityFor({ quantity: '24', unit: '件', packagingSpec: boxSpec })).toBeUndefined();
  });

  it('🔴 换算系数缺失 / 为 0 → undefined，⛔ 不许除出 Infinity 也不许落成 0', () => {
    const brokenSpec = { packageUnit: '箱', baseUnit: '盒', conversionFactor: 0 };
    expect(boxQuantityFor({ quantity: '24', unit: '盒', packagingSpec: brokenSpec })).toBeUndefined();
    expect(boxQuantityFor({
      quantity: '30', unit: 'kg', productBaseUnit: 'kg', productBoxConversionCoefficient: 0,
    })).toBeUndefined();
  });

  it('🔴 什么箱规信息都没有 → undefined', () => {
    expect(boxQuantityFor({ quantity: '24', unit: '盒' })).toBeUndefined();
  });

  it('不变式：任何输入下，返回值要么是 undefined，要么 > 0', () => {
    const inputs = [
      { quantity: '24', unit: '盒', packagingSpec: boxSpec },
      { quantity: '0.5', unit: '盒', packagingSpec: boxSpec },
      { quantity: '1', unit: '箱', packagingSpec: boxSpec },
      { quantity: '', unit: '盒', packagingSpec: boxSpec },
      { quantity: '24', unit: '件', packagingSpec: boxSpec },
      { quantity: '24', unit: '盒' },
    ];
    for (const input of inputs) {
      const result = boxQuantityFor(input);
      if (result !== undefined) {
        expect(result).toBeGreaterThan(0);
      }
    }
  });
});

describe('specificationSnapshot —— 取不到就 undefined，不是空串', () => {
  it('specification 优先，退到 packageSpec（与 web-admin onProductSelect 同口径）', () => {
    expect(specificationSnapshot('310g*42袋/箱', '整箱')).toBe('310g*42袋/箱');
    expect(specificationSnapshot(undefined, '整箱')).toBe('整箱');
    expect(specificationSnapshot('  ', '整箱')).toBe('整箱');
  });

  it('🔴 两个都没有 → undefined，⛔ 不是 空串', () => {
    // 后端 applyLineDisplaySnapshots 只在请求侧 blank 时才退到 packagingSpecName。
    // 送一个空串不会报错，但会白白占掉那条兜底 —— 属于「产出端有、消费端收不到」那一类。
    expect(specificationSnapshot(undefined, undefined)).toBeUndefined();
    expect(specificationSnapshot('', '   ')).toBeUndefined();
  });
});
