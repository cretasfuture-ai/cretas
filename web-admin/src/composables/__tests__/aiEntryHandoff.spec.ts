import { describe, expect, it, vi } from 'vitest';
import { queueAiEntry, takeAiEntry } from '../aiEntryHandoff';

describe('business entry handoff', () => {
  const identity = { factoryId: 'WHOLESALE_TEST', actorId: '1709' };
  it('keeps original text out of the URL and consumes it once', () => {
    const target = queueAiEntry(identity, '/sales/orders', '给客户开十公斤的订单');
    expect(JSON.stringify(target)).not.toContain('给客户');
    expect(takeAiEntry(target.query.handoff, identity, target.path)).toBe('给客户开十公斤的订单');
    expect(takeAiEntry(target.query.handoff, identity, target.path)).toBe('');
  });
  it.each([
    [{ ...identity, factoryId: 'OTHER' }, '/sales/orders'],
    [{ ...identity, actorId: 'OTHER' }, '/sales/orders'],
    [identity, '/procurement/orders'],
  ])('does not transfer across identity or domain boundaries', (otherIdentity, path) => {
    const target = queueAiEntry(identity, '/sales/orders', '客户资料');
    expect(takeAiEntry(target.query.handoff, otherIdentity, path)).toBe('');
  });
  it('expires after five minutes', () => {
    const target = queueAiEntry(identity, '/procurement/orders', '采购资料');
    const clock = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 300_001);
    expect(takeAiEntry(target.query.handoff, identity, target.path)).toBe('');
    clock.mockRestore();
  });
});
