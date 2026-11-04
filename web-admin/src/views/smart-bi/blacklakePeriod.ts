import type { BlacklakePeriodKind, BlacklakePeriodQuery } from '@/api/smartbi/blacklake';

const DATE_FORMATTER = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
});

export function beijingDate(now = new Date()): string {
  const parts = DATE_FORMATTER.formatToParts(now);
  const value = (name: string) => parts.find(part => part.type === name)?.value || '';
  return `${value('year')}-${value('month')}-${value('day')}`;
}

function dateAtUtcNoon(value: string): Date {
  return new Date(`${value}T12:00:00Z`);
}

function isoDate(value: Date): string {
  return value.toISOString().slice(0, 10);
}

function addDays(value: string, days: number): string {
  const result = dateAtUtcNoon(value);
  result.setUTCDate(result.getUTCDate() + days);
  return isoDate(result);
}

function daysInMonth(year: string, month: number): number {
  return new Date(Date.UTC(Number(year), month, 0, 12)).getUTCDate();
}

export function yesterdayInBeijing(now = new Date()): string {
  return addDays(beijingDate(now), -1);
}

export function periodForKind(kind: BlacklakePeriodKind, anchor = yesterdayInBeijing()): BlacklakePeriodQuery {
  const year = anchor.slice(0, 4);
  const month = Number(anchor.slice(5, 7));
  if (kind === 'year') return { start_date: `${year}-01-01`, end_date: `${year}-12-31`, basis: 'completed', kind };
  if (kind === 'month') return { start_date: `${anchor.slice(0, 7)}-01`, end_date: `${anchor.slice(0, 7)}-${String(daysInMonth(year, month)).padStart(2, '0')}`, basis: 'completed', kind };
  if (kind === 'quarter') {
    const startMonth = Math.floor((month - 1) / 3) * 3 + 1;
    const endMonth = startMonth + 2;
    return { start_date: `${year}-${String(startMonth).padStart(2, '0')}-01`, end_date: `${year}-${String(endMonth).padStart(2, '0')}-${String(daysInMonth(year, endMonth)).padStart(2, '0')}`, basis: 'completed', kind };
  }
  if (kind === 'week') {
    const weekday = dateAtUtcNoon(anchor).getUTCDay();
    const mondayOffset = weekday === 0 ? -6 : 1 - weekday;
    const start = addDays(anchor, mondayOffset);
    return { start_date: start, end_date: addDays(start, 6), basis: 'completed', kind };
  }
  return { start_date: anchor, end_date: anchor, basis: 'completed', kind: 'day' };
}

export function periodLabel(query: BlacklakePeriodQuery | null): string {
  if (!query) return '全部已采集';
  return query.start_date.slice(0,4) === query.end_date.slice(0,4) ? `${query.start_date.slice(5)} 至 ${query.end_date.slice(5)}` : `${query.start_date} 至 ${query.end_date}`;
}

export function periodIdentity(query: BlacklakePeriodQuery | null): string {
  if (!query) return 'all-history';
  return [query.start_date, query.end_date, query.basis, query.kind].join('|');
}

export function formatBlacklakeDateTime(value: string | null | undefined): string {
  if (!value) return '未提供';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value.replace(/\.\d+(?=(Z|[+-]\d\d:\d\d)$)/, '');
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(parsed);
  const part = (name: string) => parts.find(p => p.type === name)?.value || '';
  return `${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`;
}
