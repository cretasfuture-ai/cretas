import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ApiError } from '@/types/api';

const getReminderBadge = vi.fn();
vi.mock('@/api/reminder', () => ({
  getReminderBadge: (...args: unknown[]) => getReminderBadge(...args),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

import ReminderBell from '../ReminderBell.vue';

const stubs = {
  'el-tooltip': { template: '<div class="tip"><slot /></div>' },
  'el-badge': {
    props: ['value', 'hidden'],
    template: '<div class="badge" :data-value="value"><slot /></div>',
  },
  'el-icon': { template: '<i class="icon"><slot /></i>' },
  Bell: { template: '<span />' },
};

function mountBell() {
  return mount(ReminderBell, { global: { stubs } });
}

describe('ReminderBell', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    getReminderBadge.mockReset();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('403: 铃铛隐藏且轮询停止', async () => {
    getReminderBadge.mockRejectedValue(new ApiError('权限不足', 'FORBIDDEN', 403));
    const w = mountBell();
    await flushPromises();
    expect(getReminderBadge).toHaveBeenCalledTimes(1);
    expect(w.find('.badge').exists()).toBe(false);
    await vi.advanceTimersByTimeAsync(180_000);
    expect(getReminderBadge).toHaveBeenCalledTimes(1);
  });

  it('200 pending=3: 铃铛渲染、徽标为 3，60s 后再轮询一次', async () => {
    getReminderBadge.mockResolvedValue({ success: true, data: { pending: 3 } });
    const w = mountBell();
    await flushPromises();
    expect(w.find('.badge').attributes('data-value')).toBe('3');
    expect(getReminderBadge).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(getReminderBadge).toHaveBeenCalledTimes(2);
  });

  it('阴性对照 500: 铃铛仍渲染，60s 后仍重试', async () => {
    getReminderBadge.mockRejectedValue(new ApiError('服务器错误', 'SERVER_ERROR', 500));
    const w = mountBell();
    await flushPromises();
    expect(w.find('.badge').exists()).toBe(true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(getReminderBadge).toHaveBeenCalledTimes(2);
    expect(w.find('.badge').exists()).toBe(true);
  });
});
