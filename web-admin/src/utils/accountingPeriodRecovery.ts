/** Narrow, same-factory navigation for an accounting-period business rejection. */
export interface PeriodRecoveryLocation {
  path: '/finance/accounting-period';
  query: { year: string; month: string; _returnTo: string; _periodRecovery: '1'; sourceFactoryId: string; sourceActorId: string };
}

export function safePeriodReturnTo(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 2000 || /[\\\u0000-\u001f]/.test(value)) return null;
  if (!/^\/finance\/return-refunds\/[A-Za-z0-9_-]+\?/.test(value)) return null;
  const url = new URL(value, 'http://period.local');
  if (url.origin !== 'http://period.local' || url.hash) return null;
  const keys = new Set<string>();
  for (const [key, item] of url.searchParams) {
    if (keys.has(key) || !['refundRequestId', 'ledgerId'].includes(key) || !/^[A-Za-z0-9_-]+$/.test(item)) return null;
    keys.add(key);
  }
  if (keys.size !== 2) return null;
  return url.pathname + url.search;
}

export function createPeriodRecoveryLocation(hint: unknown, returnTo: string, factoryId: string, actorId: unknown): PeriodRecoveryLocation | null {
  if (typeof hint !== 'string' || !hint.startsWith('/finance/accounting-period?') || /[\\\u0000-\u001f]/.test(hint)) return null;
  if (!/^[A-Za-z0-9_-]+$/.test(factoryId) || !Number.isSafeInteger(actorId) || Number(actorId) <= 0) return null;
  const url = new URL(hint, 'http://period.local');
  if (url.pathname !== '/finance/accounting-period' || url.hash || [...url.searchParams.keys()].length !== 2) return null;
  const year = url.searchParams.get('year');
  const month = url.searchParams.get('month');
  if (!year || !/^\d{4}$/.test(year) || Number(year) < 2000 || Number(year) > 2200
      || !month || !/^\d{1,2}$/.test(month) || Number(month) < 1 || Number(month) > 12) return null;
  const safeReturn = safePeriodReturnTo(returnTo);
  if (!safeReturn) return null;
  return { path: '/finance/accounting-period', query: { year, month: String(Number(month)), _returnTo: safeReturn, _periodRecovery: '1',
    sourceFactoryId: factoryId, sourceActorId: String(actorId) } };
}

export function periodReturnTarget(query: Record<string, unknown>, factoryId: string, actorId: unknown): string | null {
  if (query._periodRecovery !== '1' || !factoryId || !Number.isSafeInteger(actorId) || Number(actorId) <= 0 || query.sourceFactoryId !== factoryId || query.sourceActorId !== String(actorId)) return null;
  return safePeriodReturnTo(query._returnTo);
}
