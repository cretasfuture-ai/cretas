import { describe, expect, it } from 'vitest';
import {
  DEFAULT_OVER_RECEIVE_RULE,
  overReceiveBlockedMessage,
  overReceiveExceeds,
  overReceiveHelp,
  overReceiveMax,
} from '../overReceiveRule';

/** 纪律排查 A11: 入库弹窗按本厂多收规则预先显示边界 (判定以服务端为准)。 */
describe('overReceiveRule', () => {
  it('默认 (读不到规则) = 硬拦: 上限就是报产, 多 1 就超', () => {
    expect(DEFAULT_OVER_RECEIVE_RULE.mode).toBe('BLOCK');
    expect(overReceiveMax(90, DEFAULT_OVER_RECEIVE_RULE)).toBe(90);
    expect(overReceiveExceeds(90, 91, DEFAULT_OVER_RECEIVE_RULE)).toBe(true);
    expect(overReceiveExceeds(90, 90, DEFAULT_OVER_RECEIVE_RULE)).toBe(false);
    expect(overReceiveHelp(90, '公斤', DEFAULT_OVER_RECEIVE_RULE)).toContain('上限为生产报产 90 公斤');
    expect(overReceiveBlockedMessage(90, '公斤', DEFAULT_OVER_RECEIVE_RULE)).toBe('仓库实收不能超过生产报产，请先让生产修正结单');
  });

  it('比例内 (5%): 上限 = 报产 × 1.05; 阴性对照: 超过比例仍超', () => {
    const rule = { mode: 'WITHIN_RATE' as const, rate: 0.05 };
    expect(overReceiveMax(100, rule)).toBe(105);
    expect(overReceiveExceeds(100, 105, rule)).toBe(false);
    expect(overReceiveExceeds(100, 105.01, rule)).toBe(true);
    expect(overReceiveHelp(100, '箱', rule)).toContain('本厂允许多收 5%（最多 105 箱）');
    expect(overReceiveBlockedMessage(100, '箱', rule)).toContain('多收上限 105 箱');
  });

  it('一律填原因放行: 不设上限, 提示要选原因和责任侧', () => {
    const rule = { mode: 'ALWAYS_WITH_REASON' as const, rate: 0.05 };
    expect(overReceiveMax(100, rule)).toBeUndefined();
    expect(overReceiveExceeds(100, 10_000, rule)).toBe(false);
    expect(overReceiveHelp(100, '箱', rule)).toContain('须选差异原因和责任侧');
  });
});
