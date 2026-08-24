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
