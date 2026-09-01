/**
 * 守 owner 2026-09-02 的「填 30 吨 ⇒ 自动跳 70 吨」。
 * 阴性对照: 无份额的行不被推也不当分母 / 刚填的那行没份额不推 / 只填一个份额不提示。
 */
import { describe, expect, it } from 'vitest';
import { deriveByShare, shareSumWarning } from '../workflowInputShare';

describe('deriveByShare', () => {
  it('30/70: 填 a=30 ⇒ b=70', () => {
    const d = deriveByShare([{ id: 'a', share: 30 }, { id: 'b', share: 70 }], 'a', 30);
    expect([...d.entries()]).toEqual([['b', 70]]);
  });

  it('相对量: 3/7 同样推出 70', () => {
    expect(deriveByShare([{ id: 'a', share: 3 }, { id: 'b', share: 7 }], 'a', 30).get('b')).toBe(70);
  });

  it('阴性对照: 无份额的行不被推、也不当分母', () => {
    const d = deriveByShare([{ id: 'a', share: 30 }, { id: 'b', share: null }, { id: 'c', share: 70 }], 'a', 30);
    expect(d.has('b')).toBe(false);
    expect(d.get('c')).toBe(70);
  });

  it('阴性对照: 份额 0 的行不参与 —— 不被推成 0 (organizer 自查项)', () => {
    const d = deriveByShare([{ id: 'a', share: 30 }, { id: 'b', share: 70 }, { id: 'z', share: 0 }], 'a', 30);
    expect(d.has('z')).toBe(false);
    expect(d.get('b')).toBe(70);
  });

  it('阴性对照: 刚填的那行没份额 ⇒ 不推 (⛔ 不猜)', () => {
    expect(deriveByShare([{ id: 'a', share: null }, { id: 'b', share: 70 }], 'a', 30).size).toBe(0);
  });
});

describe('shareSumWarning', () => {
  it('30+70 = 100 → 不提示', () => {
    expect(shareSumWarning([30, 70])).toBeNull();
  });

  it('30+60 → 提示合计 90%', () => {
    expect(shareSumWarning([30, 60])).toContain('90%');
  });

  it('阴性对照: 只填一个 → 不提示 (相对量本来不要求和为 100)', () => {
    expect(shareSumWarning([30, null])).toBeNull();
  });
});
