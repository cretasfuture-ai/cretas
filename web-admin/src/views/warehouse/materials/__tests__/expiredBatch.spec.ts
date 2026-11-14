import { describe, expect, it } from 'vitest';
import { expiredDays, expiredHint, expiredReasonError } from '../expiredBatch';

/** 北京时间 2026-11-14 00:30（UTC 11-13 16:30）：按北京时间算今天，不能按 UTC 算成 11-13。 */
const NOW = new Date('2026-11-13T16:30:00Z');

describe('R35 建批次时已过期', () => {
  it('到期日早于今天（北京时间）⇒ 过期天数；今天到期 / 未来 / 没填 ⇒ 0', () => {
    expect(expiredDays('2026-11-11', NOW)).toBe(3);
    expect(expiredDays('2026-11-13', NOW)).toBe(1);
    expect(expiredDays('2026-11-14', NOW)).toBe(0);
    expect(expiredDays('2026-12-01', NOW)).toBe(0);
    expect(expiredDays('', NOW)).toBe(0);
  });
  it('字段旁提示写明过期天数', () => {
    expect(expiredHint('2026-11-11', NOW)).toContain('已过 3 天');
    expect(expiredHint('2026-11-20', NOW)).toBe('');
  });
  it('阴性：过期没写原因 / 只写 1 个字 ⇒ 拦；阳性：写了原因放行；没过期不要求原因', () => {
    expect(expiredReasonError('2026-11-11', '', NOW)).toContain('请写原因');
    expect(expiredReasonError('2026-11-11', ' 退 ', NOW)).toContain('至少 2 个字');
    expect(expiredReasonError('2026-11-11', '客户坚持送来，退货前暂存', NOW)).toBe('');
    expect(expiredReasonError('2026-11-20', '', NOW)).toBe('');
  });
});
