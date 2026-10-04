export type LaborCostPeriod = 'week' | 'month' | 'quarter';

const localDate = (date: Date): string => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

export function periodDates(period: LaborCostPeriod, now = new Date()) {
  const endDate = localDate(now);
  const start = new Date(now);
  if (period === 'week') {
    const day = start.getDay();
    const daysSinceMonday = (day + 6) % 7;
    start.setDate(start.getDate() - daysSinceMonday);
  }
  else if (period === 'month') start.setDate(1);
  else start.setMonth(Math.floor(start.getMonth() / 3) * 3, 1);
  return { startDate: localDate(start), endDate };
}

export function nullableNumber(value: unknown): number | null {
  return value === null || value === undefined || value === '' ? null : Number(value);
}
