function finiteNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

export function purchaseTaxIncludedUnitPrice(
  untaxedUnitPrice: unknown,
  taxRate: unknown,
): number | null {
  const price = finiteNumber(untaxedUnitPrice);
  const rate = finiteNumber(taxRate);
  if (price === null || price < 0 || rate === null || rate < 0 || rate > 100) return null;
  return Number((price * (1 + rate / 100)).toFixed(2));
}

export function purchaseUntaxedUnitPrice(
  taxIncludedUnitPrice: unknown,
  taxRate: unknown,
): number | null {
  const price = finiteNumber(taxIncludedUnitPrice);
  const rate = finiteNumber(taxRate);
  if (price === null || price < 0 || rate === null || rate < 0 || rate > 100) return null;
  return Number((price / (1 + rate / 100)).toFixed(4));
}
