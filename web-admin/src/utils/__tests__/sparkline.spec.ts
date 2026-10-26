import { describe, it, expect } from 'vitest';
import { sparklinePath, sparklineSVG } from '../sparkline';

describe('sparklinePath', () => {
  it('draws a single segment for complete data', () => {
    const d = sparklinePath([1, 2, 3]);
    expect(d.startsWith('M ')).toBe(true);
    expect(d.match(/M /g)).toHaveLength(1);
  });

  it('leaves a gap for missing values instead of drawing them as 0', () => {
    // 2 月没上传: 线在 2 月断开, 不能掉到 0 再拉回来
    const d = sparklinePath([100, null, 120, 130]);
    expect(d.match(/M /g)).toHaveLength(2);
    const ys = [...d.matchAll(/[ML] [\d.]+,([\d.]+)/g)].map(m => Number(m[1]));
    expect(ys).toHaveLength(3);
    // 最小值是 100 (不是 null→0), 所以最低点在底边附近而不是更低
    expect(Math.max(...ys)).toBeLessThanOrEqual(22);
  });

  it('returns empty when fewer than 2 real points remain', () => {
    expect(sparklinePath([null, 5, null])).toBe('');
  });
});

describe('sparklineSVG', () => {
  it('ignores missing values (only the real points are drawn)', () => {
    const svg = sparklineSVG([1, null, 3]);
    expect(svg).toContain('<svg');
    expect(svg).not.toContain('NaN');
    const line = /<path d="(M [^"]+)" fill="none"/.exec(svg)?.[1] ?? '';
    expect(line.match(/[ML] /g)).toHaveLength(2);
  });
});
