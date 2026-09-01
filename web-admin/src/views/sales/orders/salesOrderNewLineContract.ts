import {
  canonicalUnitCode,
  canonicalUnitCodeKeepingCount,
  mergeCanonicalUnitOptions,
} from '@/utils/unitPricing';

export interface SalesOrderPackagingSpec {
  id?: string | null;
  packageUnit?: string | null;
  baseUnit?: string | null;
  conversionFactor?: number | null;
  defaultSpec?: boolean | null;
  active?: boolean | null;
}

export interface SalesOrderPricedProduct {
  unit?: string | null;
  measurementUnit?: string | null;
  defaultUnit?: string | null;
  level1Unit?: string | null;
  boxConversionCoefficient?: number | null;
  unitPrice?: number | null;
  taxIncludedUnitPrice?: number | null;
  taxRate?: number | string | null;
  packagingSpecs?: SalesOrderPackagingSpec[] | null;
}

export function productTaxRatePercent(product: SalesOrderPricedProduct): number {
  const raw = product.taxRate;
  if (typeof raw === 'number' && Number.isFinite(raw)) return raw;
  const matched = String(raw || '').match(/(?:TAX_)?(\d+(?:\.\d+)?)/i);
  return matched ? Number(matched[1]) : 13;
}

export function initialTaxIncludedUnitPrice(product: SalesOrderPricedProduct): number {
  const configured = Number(product.taxIncludedUnitPrice);
  if (Number.isFinite(configured) && configured > 0) return configured;
  const untaxed = Number(product.unitPrice);
  if (!Number.isFinite(untaxed) || untaxed <= 0) return 0;
  return Number((untaxed * (1 + productTaxRatePercent(product) / 100)).toFixed(2));
}

export function untaxedUnitPrice(taxIncludedUnitPrice: number, taxRate: number): number {
  const price = Number(taxIncludedUnitPrice);
  const rate = Number(taxRate);
  if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(rate) || rate < 0 || rate > 100) return 0;
  return Number((price / (1 + rate / 100)).toFixed(4));
}

/**
 * 税率必须是 0-100 的有效数字才能反算未税单价; 清空/非法税率一律判定为「未知」而不是 0。
 * 与 {@link OrderItemsEditor.vue} 里的同名私有 helper 同一条判据 —— 这里导出供
 * 其它录入面 (如 sales/orders/list.vue 的行内表格) 复用, 避免第三份实现。
 */
export function validTaxRatePercent(rate: unknown): number | null {
  if (rate === null || rate === undefined || rate === '') return null;
  const n = Number(rate);
  return Number.isFinite(n) && n >= 0 && n <= 100 ? n : null;
}

/**
 * 含税单价 + 税率 → 未税单价 (仅供展示; 与提交给后端的 unitPrice 用同一条公式
 * {@link untaxedUnitPrice}, 保证「展示的数」和「实际存的数」永远一致)。
 * 税率未知 (null/undefined/空串/超出 0-100) 时返回 null —— 调用方必须把它当成
 * 「算不出来」处理, 禁止落到 0 或原样回显含税价 (⛔ 不要用 `?? 0` 兜底掉这个 null)。
 */
export function derivedUntaxedPriceOrNull(
  taxIncludedUnitPrice: unknown,
  taxRate: unknown,
): number | null {
  const rate = validTaxRatePercent(taxRate);
  if (rate === null) return null;
  return untaxedUnitPrice(Number(taxIncludedUnitPrice) || 0, rate);
}

export function salesOrderUnitOptions(product: SalesOrderPricedProduct): string[] {
  const baseUnit = canonicalUnitCodeKeepingCount(String(
    product.unit || product.measurementUnit || product.defaultUnit || '',
  ));
  const sources: unknown[] = [baseUnit];
  if (Number(product.boxConversionCoefficient) > 0) sources.push(product.level1Unit || '箱');
  for (const spec of product.packagingSpecs || []) {
    if (spec.active === false) continue;
    sources.push(spec.baseUnit, spec.packageUnit);
  }
  return mergeCanonicalUnitOptions(sources);
}

export function packagingSpecIdForUnit(
  product: SalesOrderPricedProduct,
  unit: string,
): string | undefined {
  const unitCode = canonicalUnitCode(unit);
  const matching = (product.packagingSpecs || []).filter((spec) =>
    spec.active !== false
      && Boolean(spec.id)
      && canonicalUnitCode(spec.packageUnit || '') === unitCode,
  );
  const selected = matching.find((spec) => spec.defaultSpec === true)
    || (matching.length === 1 ? matching[0] : undefined);
  return selected?.id ? String(selected.id) : undefined;
}

export function ambiguousPackagingUnit(product: SalesOrderPricedProduct, unit: string): boolean {
  const unitCode = canonicalUnitCode(unit);
  const matching = (product.packagingSpecs || []).filter((spec) =>
    spec.active !== false
      && Boolean(spec.id)
      && canonicalUnitCode(spec.packageUnit || '') === unitCode,
  );
  return matching.length > 1 && !matching.some((spec) => spec.defaultSpec === true);
}
