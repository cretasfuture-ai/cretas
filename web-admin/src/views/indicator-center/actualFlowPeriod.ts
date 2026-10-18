export type ActualFlowPeriod = [string, string];
export function dateOnly(value: Date): string {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
}
export function isCalendarDay(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  return dateOnly(new Date(year!, month! - 1, day!)) === value;
}
export function isValidPeriod(value: unknown): value is ActualFlowPeriod {
  return Array.isArray(value) && value.length === 2 && isCalendarDay(value[0])
    && isCalendarDay(value[1]) && value[0] <= value[1];
}
