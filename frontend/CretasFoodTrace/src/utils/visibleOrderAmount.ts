import { formatNumberWithCommas } from './formatters';

/** A masked or absent price must never look like a real zero. */
export function formatVisibleOrderAmount(value: number | string | null | undefined): string {
  if (value == null || (typeof value === 'string' && value.trim() === '') || !Number.isFinite(Number(value))) {
    return '金额未提供';
  }
  return `¥${formatNumberWithCommas(Number(value))}`;
}
