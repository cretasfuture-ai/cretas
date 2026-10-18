import { describe, expect, it } from 'vitest';
import { createPeriodRecoveryLocation, periodReturnTarget, safePeriodReturnTo } from '../accountingPeriodRecovery';

const target = '/finance/return-refunds/return-a?refundRequestId=RF-1&ledgerId=REV-1';
describe('accounting-period recovery navigation', () => {
  it('preserves the exact refund and ledger and binds factory and actor', () => {
    const location = createPeriodRecoveryLocation('/finance/accounting-period?year=2026&month=10', target, 'F001', 8)!;
    expect(periodReturnTarget(location.query, 'F001', 8)).toBe(target);
    expect(periodReturnTarget(location.query, 'F002', 8)).toBeNull();
    expect(periodReturnTarget(location.query, 'F001', 9)).toBeNull();
    expect(periodReturnTarget({ ...location.query, sourceActorId: ['8'] }, 'F001', 8)).toBeNull();
    expect(periodReturnTarget({ ...location.query, _periodRecovery: ['1', 'x'] }, 'F001', 8)).toBeNull();
  });
  it.each(['https://evil.test/finance/accounting-period?year=2026&month=10',
    '/finance/accounting-period?year=2026&month=0', '/finance/accounting-period?year=2026&month=13',
    '/finance/accounting-period?year=2026&year=2025', '/finance/accounting-period?year=2026&month=10&redirect=evil',
    '/finance/accounting-period?year=2026&month=10#other', '/finance/other?year=2026&month=10'])('rejects an unrelated or malformed hint %s', hint => {
    expect(createPeriodRecoveryLocation(hint, target, 'F001', 8)).toBeNull();
  });
  it.each(['//evil.test', 'https://evil.test', '/finance/return-refunds/a?ledgerId=a&ledgerId=b',
    '/finance/return-refunds/a?redirect=evil', '/finance/return-refunds/a#foreign', '/finance/return-refunds/a\\other',
    '/finance/return-refunds/a/../other', '/finance/return-refunds/a',
    '/finance/return-refunds/a?refundRequestId=RF-1', '/finance/return-refunds/a?ledgerId=REV-1',
    '/finance/voucher/a', '/sales/returns/a'])('rejects unsafe return paths %s', path => {
    expect(safePeriodReturnTo(path)).toBeNull();
  });
  it('does not permit an unauthenticated recovery', () => {
    expect(createPeriodRecoveryLocation('/finance/accounting-period?year=2026&month=10', target, '', 8)).toBeNull();
    expect(createPeriodRecoveryLocation('/finance/accounting-period?year=2026&month=10', target, 'F001', undefined)).toBeNull();
  });
});
