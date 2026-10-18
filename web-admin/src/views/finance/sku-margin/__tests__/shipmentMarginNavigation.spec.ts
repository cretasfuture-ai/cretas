import { describe, expect, it } from 'vitest';
import { readMarginPeriod, readSourceLocation, shipmentSourceUrl } from '../shipmentMarginNavigation';

const query = { factoryId: 'NEW', periodStart: '2026-10-01', periodEnd: '2026-10-31', sourceSku: '盒装&A', sourceLineId: '36' };
describe('只按本厂有效期间定位出货来源', () => {
  it('链接保留工厂、期间、产品、行号，特殊字符可往返', () => {
    const url = shipmentSourceUrl('NEW', ['2026-10-01', '2026-10-31'], '盒装&A', 36);
    const restored = Object.fromEntries(new URL(url, 'http://localhost').searchParams);
    expect(readSourceLocation(restored, 'NEW')).toEqual({ factoryId: 'NEW', period: ['2026-10-01', '2026-10-31'], productTypeId: '盒装&A', lineId: 36 });
    expect(readSourceLocation(restored, 'OTHER')).toBeNull();
  });
  it.each(['2026-02-30', '2026-2-01', '', ['2026-10-01'], 'not-a-date'])('非法日期不进入查询 %j', periodStart => {
    expect(readMarginPeriod({ ...query, periodStart })).toBeNull();
  });
  it.each(['0', '-1', '1.5', '9007199254740992', ['36'], ''])('非法行号不定位 %j', sourceLineId => {
    expect(readSourceLocation({ ...query, sourceLineId }, 'NEW')).toBeNull();
  });
  it('反向期间、缺产品或工厂拒绝，闰日可用', () => {
    expect(readMarginPeriod({ periodStart: '2026-11-01', periodEnd: '2026-10-31' })).toBeNull();
    expect(readSourceLocation({ ...query, sourceSku: '' }, 'NEW')).toBeNull();
    expect(readSourceLocation(query, '')).toBeNull();
    expect(readMarginPeriod({ periodStart: '2028-02-29', periodEnd: '2028-03-01' })).toEqual(['2028-02-29', '2028-03-01']);
  });
});
