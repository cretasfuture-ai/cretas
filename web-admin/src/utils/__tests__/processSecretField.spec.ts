import { describe, expect, it } from 'vitest';
import { isProcessSecretHidden, isProcessSecretRow, processSecretMessage } from '../processSecretField';

describe('process-secret field markers', () => {
  const totalsOnly = {
    productTypeId: 'P17',
    conversionRate: null,
    requiredQuantity: 120,
    processSecret: { visibility: 'TOTALS_ONLY', message: '配比已隐藏', hiddenFields: ['conversionRate'] },
  };

  it('marks only the hidden fields, not the totals next to them', () => {
    expect(isProcessSecretHidden(totalsOnly, 'conversionRate')).toBe(true);
    expect(isProcessSecretHidden(totalsOnly, 'requiredQuantity')).toBe(false);
    expect(isProcessSecretRow(totalsOnly)).toBe(true);
    expect(processSecretMessage(totalsOnly)).toBe('配比已隐藏');
  });

  it('treats a plain null as "not filled", not as secret', () => {
    const notFilled = { productTypeId: 'P1', conversionRate: null };
    expect(isProcessSecretHidden(notFilled, 'conversionRate')).toBe(false);
    expect(isProcessSecretRow(notFilled)).toBe(false);
    expect(processSecretMessage(notFilled)).toBe('');
  });

  it('treats a V1 whole-object summary (no hiddenFields) as fully restricted', () => {
    const summary = { id: 'r1', processSecret: { visibility: 'RESTRICTED', message: 'x' } };
    expect(isProcessSecretHidden(summary, 'anything')).toBe(true);
  });

  it('ignores non-objects', () => {
    expect(isProcessSecretRow(null)).toBe(false);
    expect(isProcessSecretHidden(undefined, 'conversionRate')).toBe(false);
  });
});
