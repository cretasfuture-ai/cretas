import { describe, expect, it } from 'vitest';
import { beijingDate, periodForKind, periodIdentity, periodLabel, yesterdayInBeijing } from '../blacklakePeriod';

describe('Blacklake period helpers', () => {
  it('uses Beijing date rather than browser-local date', () => {
    const lateUtc = new Date('2026-11-04T23:30:00.000Z');
    expect(beijingDate(lateUtc)).toBe('2026-11-05');
    expect(yesterdayInBeijing(lateUtc)).toBe('2026-11-04');
  });

  it('builds complete natural week, quarter, month, and year ranges', () => {
    expect(periodForKind('week', '2026-11-05')).toMatchObject({ start_date: '2026-11-02', end_date: '2026-11-08', kind: 'week', basis: 'completed' });
    expect(periodForKind('quarter', '2026-11-05')).toMatchObject({ start_date: '2026-10-01', end_date: '2026-12-31', kind: 'quarter' });
    expect(periodForKind('month', '2024-02-10')).toMatchObject({ start_date: '2024-02-01', end_date: '2024-02-29', kind: 'month' });
    expect(periodForKind('year', '2026-11-05')).toMatchObject({ start_date: '2026-01-01', end_date: '2026-12-31', kind: 'year' });
  });

  it('handles a week crossing the year boundary and leap day', () => {
    expect(periodForKind('week', '2027-01-01')).toMatchObject({ start_date: '2026-12-28', end_date: '2027-01-03' });
    expect(periodForKind('day', '2024-02-29')).toMatchObject({ start_date: '2024-02-29', end_date: '2024-02-29' });
  });

  it('keeps all-history distinct from an explicit query', () => {
    const query = periodForKind('day', '2026-11-04');
    expect(periodLabel(null)).toBe('全部已采集');
    expect(periodIdentity(null)).toBe('all-history');
    expect(periodIdentity(query)).not.toBe('all-history');
  });
});
