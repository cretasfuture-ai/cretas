import { describe, it, expect, vi } from 'vitest';

const get = vi.fn().mockResolvedValue({ success: true, data: { pending: 0 } });
vi.mock('@/api/request', () => ({ default: { get: (...a: unknown[]) => get(...a) } }));
vi.mock('@/api/currentFactoryId', () => ({ requireFactoryId: (id?: string) => id ?? 'F001' }));

import { getReminderBadge } from '@/api/reminder';

describe('getReminderBadge', () => {
  it('请求带 _silent: true，避免 403 触发全局阻断弹窗', async () => {
    await getReminderBadge('F001');
    expect(get).toHaveBeenCalledTimes(1);
    expect(get.mock.calls[0][0]).toBe('/F001/reminders/badge');
    expect(get.mock.calls[0][1]).toEqual({ _silent: true });
  });
});
