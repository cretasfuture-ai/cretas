import { purchaseUntaxedUnitPrice } from './purchaseOrderTaxInclusivePricing';

// Compare the quote with the existing server calculation. This is a preview guard,
// not a replacement for the persisted purchase/finance amount contract.
function decimal(value: number): [bigint, bigint] {
  const [mantissa, exponent = '0'] = value.toString().toLowerCase().split('e');
  const scale = (mantissa.split('.')[1]?.length || 0) - Number(exponent);
  const digits = BigInt(mantissa.replace('.', ''));
  return scale >= 0 ? [digits, 10n ** BigInt(scale)] : [digits * 10n ** BigInt(-scale), 1n];
}
const roundPositive = (numerator: bigint, denominator: bigint) =>
  (2n * numerator + denominator) / (2n * denominator);
const money = (cents: bigint) => `${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`;

export function purchaseAiQuoteDifference(quantity: number, grossUnitPrice: number, taxRate: number):
  { quoted: string; stored: string } | null {
  if (![quantity, grossUnitPrice, taxRate].every(Number.isFinite)
    || quantity <= 0 || grossUnitPrice < 0 || taxRate < 0 || taxRate > 100) return null;
  const untaxedPrice = purchaseUntaxedUnitPrice(grossUnitPrice, taxRate);
  if (untaxedPrice === null) return null;
  const [q, qd] = decimal(quantity);
  const [g, gd] = decimal(grossUnitPrice);
  const [n, nd] = decimal(untaxedPrice);
  const [r, rd] = decimal(taxRate);
  const quoted = roundPositive(q * g * 100n, qd * gd);
  const net = roundPositive(q * n * 100n, qd * nd);
  const multiplier = 1000000n + roundPositive(r * 10000n, rd);
  const stored = roundPositive(net * multiplier, 1000000n);
  return quoted === stored ? null : { quoted: money(quoted), stored: money(stored) };
}
