import type { ProductPackagingSpec } from '../services/api/productTypeApiClient';

export function salesUnitOptions(baseUnit: string, specs: ProductPackagingSpec[]): string[] {
  const units = new Set<string>();
  if (baseUnit) units.add(baseUnit);
  specs.filter((spec) => spec.active !== false).forEach((spec) => units.add(spec.packageUnit));
  return Array.from(units);
}

export function packagingSpecsForUnit(
  unit: string,
  specs: ProductPackagingSpec[],
): ProductPackagingSpec[] {
  return specs.filter((spec) => spec.active !== false && spec.packageUnit === unit);
}

export function optionalTaxRate(value: string): number | undefined {
  if (!value.trim()) return undefined;
  const taxRate = Number(value);
  return Number.isFinite(taxRate) && taxRate >= 0 && taxRate <= 100 ? taxRate : undefined;
}

/**
 * 「抄码」= 称重商品，每箱重量不一致，按箱计没有意义。
 * 与 web-admin `isAbacaItem` 同口径：**精确匹配 trim() === '抄码'**，不用 includes（避免误报）。
 */
export function isWeighPricedSpecification(specification: string | undefined): boolean {
  return String(specification ?? '').trim() === '抄码';
}

export interface BoxQuantityInput {
  quantity: string;
  unit: string;
  specification?: string;
  /** 用户在本行选中的箱规（没选就是 undefined）。 */
  packagingSpec?: Pick<ProductPackagingSpec, 'packageUnit' | 'baseUnit' | 'conversionFactor'>;
  /** SKU 主数据的基本单位与箱规系数，用于「产品配了箱规系数但没有 packaging_spec 行」的老数据。 */
  productBaseUnit?: string;
  productBoxConversionCoefficient?: number;
}

/**
 * 算本行的箱数。<b>算不出来就返回 undefined，绝不返回 0。</b>
 *
 * <p>形态 A¹⁰：兜底的默认值会把「我不知道」翻译成「是 0」。箱数是仓库拣货照着数的数，
 * 一个假的 0 比一个空格危险得多 —— 所以这里每条不确定的路径都走 undefined，
 * 由界面显示「未配置箱规」，而不是往后端送一个编出来的数。
 *
 * <p>⚠️ <b>登记：这是 web-admin `calcBox`（sales/orders/list.vue:1252-1282）的第二份实现</b>
 * （形态 D）。Vue 与 RN 跨运行时，抽不成一份。缓解方式不是「再写一道跨运行时的闸」
 * （那道闸自己会变成第三份），而是让这一份<b>严格更保守</b>：
 * <ul>
 *   <li>只保留 calcBox 三条分支里语义无歧义的两条（按箱下单 / 按基本单位下单换算）；</li>
 *   <li>单位比较用<b>精确相等</b>，不做 canonicalUnitCode 归一 —— RN 侧没有那张
 *       等价表，照抄一份正是漂移的来源。比不上就返回 undefined。</li>
 *   <li>取整口径与 calcBox 逐字相同：{@code Math.round(x * 100) / 100}。</li>
 * </ul>
 * ⇒ 两份可能「RN 说不知道而 Web 算得出」，但不会「两份算出不同的数」。
 */
export function boxQuantityFor(input: BoxQuantityInput): number | undefined {
  // ① 抄码品不计箱数（与 web-admin calcBox 第一分支同）。
  if (isWeighPricedSpecification(input.specification)) return undefined;

  const quantity = Number(input.quantity);
  if (!Number.isFinite(quantity) || quantity <= 0) return undefined;

  const unit = String(input.unit ?? '').trim();
  if (!unit) return undefined;

  const spec = input.packagingSpec;
  if (spec) {
    // ② 用户直接按箱（包装单位）下单 → 箱数就是数量本身。
    if (unit === String(spec.packageUnit ?? '').trim()) return quantity;
    // ③ 按箱规的基本单位下单 → 数量 / 换算系数。
    const factor = Number(spec.conversionFactor);
    if (unit === String(spec.baseUnit ?? '').trim() && Number.isFinite(factor) && factor > 0) {
      return Math.round((quantity / factor) * 100) / 100;
    }
    return undefined;
  }

  // ④ 没有箱规行，但 SKU 主数据配了箱规系数，且下单单位正是 SKU 基本单位。
  const coefficient = Number(input.productBoxConversionCoefficient);
  const productBaseUnit = String(input.productBaseUnit ?? '').trim();
  if (productBaseUnit && unit === productBaseUnit && Number.isFinite(coefficient) && coefficient > 0) {
    return Math.round((quantity / coefficient) * 100) / 100;
  }

  // ⑤ 其余一律「不知道」。
  return undefined;
}

/**
 * 本行的规格快照：SKU 的 `specification` 优先，退到 `packageSpec`。
 * 与 web-admin `onProductSelect` 的 `p.specification || p.packageSpec || ''` 同口径。
 * <p>两者都没有就返回 undefined —— <b>不返回空串</b>：后端
 * `applyLineDisplaySnapshots` 只在请求侧 blank 时才会退到 packagingSpecName，
 * 送一个空串会白白占掉那条兜底。
 */
export function specificationSnapshot(
  productSpecification: string | undefined,
  productPackageSpec: string | undefined,
): string | undefined {
  const picked = (productSpecification ?? '').trim() || (productPackageSpec ?? '').trim();
  return picked || undefined;
}

/**
 * 税率该发什么 —— 三态，<b>「不知道」不折叠进「0」</b>。
 *
 * <p>后端三层链是 `item.taxRate` → `SalesOrder.defaultTaxRate`（建单时 prefill 自
 * `customer.defaultTaxRate`）→ <b>0</b>（SalesServiceImpl:685-688）。
 * 最后那一跳是静默的：客户没配默认税率时，一张留空的单子就把税算没了。
 *
 * <p>2026-08-19 prod 实测（cretas_prod_db / public schema）：
 * `customers` 29 行里只有 <b>7</b> 行配了 default_tax_rate（都 &gt; 0），
 * 另外 22 行（75.9%）没配 ⇒ 对四分之三的客户，留空 = 0%。
 * 同期 `sales_orders` 38 行里 default_tax_rate 非空的是 <b>0</b> 行。
 *
 * ⇒ 判据：客户有默认税率才允许留空（那正是三层链存在的理由）；
 *    客户没有默认税率时，留空是**未知**而不是零，必须让用户显式填。
 */
export type TaxRateDecision =
  | { kind: 'explicit'; taxRate: number }
  | { kind: 'customer-default'; customerDefaultTaxRate: number }
  | { kind: 'must-ask' }
  | { kind: 'invalid' };

export function decideTaxRate(
  rawInput: string,
  customerDefaultTaxRate: number | null | undefined,
): TaxRateDecision {
  if (rawInput.trim()) {
    const taxRate = optionalTaxRate(rawInput);
    return taxRate === undefined ? { kind: 'invalid' } : { kind: 'explicit', taxRate };
  }
  // 留空。客户侧有默认值才是「已知」——注意 0 是合法的默认税率（免税品），
  // 所以这里判的是 null/undefined，不是 falsy。
  if (customerDefaultTaxRate === null || customerDefaultTaxRate === undefined) {
    return { kind: 'must-ask' };
  }
  return { kind: 'customer-default', customerDefaultTaxRate };
}
