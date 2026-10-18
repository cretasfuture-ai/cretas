export function readMarginPeriod(query: Record<string, unknown>): [string, string] | null {
  const start = query.periodStart, end = query.periodEnd;
  const valid = (value: unknown): value is string => {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const date = new Date(`${value}T00:00:00Z`);
    const [year, month, day] = value.split('-').map(Number);
    return Number.isFinite(date.getTime()) && date.getUTCFullYear() === year
      && date.getUTCMonth() + 1 === month && date.getUTCDate() === day;
  };
  return valid(start) && valid(end) && start <= end ? [start, end] : null;
}

export function readMonthRange(value: unknown): [string, string] | null {
  if (!Array.isArray(value) || value.length !== 2 || value.some(month => typeof month !== 'string' || !/^\d{4}-\d{2}$/.test(month))) return null;
  return readMarginPeriod({ periodStart: `${value[0]}-01`, periodEnd: `${value[1]}-01` }) ? [value[0], value[1]] : null;
}

export function readIncomeMonthRange(query: Record<string, unknown>): [string, string] | null {
  const period = readMarginPeriod(query);
  if (!period || !period[0].endsWith('-01')) return null;
  const nextDay = new Date(`${period[1]}T00:00:00Z`);
  nextDay.setUTCDate(nextDay.getUTCDate() + 1);
  return nextDay.getUTCDate() === 1 ? [period[0].slice(0, 7), period[1].slice(0, 7)] : null;
}

export function samePeriodIncomeUrl(factoryId: string, period: [string, string]): string | null {
  if (!factoryId || !readIncomeMonthRange({ periodStart: period[0], periodEnd: period[1] })) return null;
  return '/finance/three-statements?' + new URLSearchParams({ tab: 'income-statement', factoryId, periodStart: period[0], periodEnd: period[1] });
}
