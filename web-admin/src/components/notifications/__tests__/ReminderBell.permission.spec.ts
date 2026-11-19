import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const mock = vi.hoisted(() => ({
  badge: vi.fn(),
  levels: {} as Record<string, string>,
}));
vi.mock('@/api/reminder', () => ({ getReminderBadge: (...a: unknown[]) => mock.badge(...a) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ getPermissionLevel: (m: string) => mock.levels[m] ?? '-' }),
}));
import ReminderBell from '../ReminderBell.vue';

const stubs = {
  'el-tooltip': { template: '<div data-testid="bell"><slot /></div>' },
  'el-badge': { template: '<div><slot /></div>' },
  'el-icon': { template: '<i><slot /></i>' },
};
function create() { return mount(ReminderBell, { global: { stubs } }); }

beforeEach(() => {
  vi.clearAllMocks();
  mock.levels = {};
  mock.badge.mockResolvedValue({ data: { pending: 2 } });
});

describe('ReminderBell 按后端 /reminders/badge 同一组权限挂载', () => {
  it('质量经理(无 sales/finance 读、无 system 读写): 不渲染也不请求', async () => {
    mock.levels = { quality: 'rw', production: 'r', system: 'r' };
    const w = create(); await flushPromises();
    expect(w.find('[data-testid="bell"]').exists()).toBe(false);
    expect(mock.badge).not.toHaveBeenCalled();
    w.unmount();
  });
  it('有 sales:read: 渲染并请求', async () => {
    mock.levels = { sales: 'r' };
    const w = create(); await flushPromises();
    expect(w.find('[data-testid="bell"]').exists()).toBe(true);
    expect(mock.badge).toHaveBeenCalledTimes(1);
    w.unmount();
  });
  it('finance:read_write / system:read_write 也渲染; system 只读不算', async () => {
    mock.levels = { finance: 'rw' };
    const a = create(); await flushPromises(); expect(a.find('[data-testid="bell"]').exists()).toBe(true); a.unmount();
    mock.levels = { system: 'rw' };
    const b = create(); await flushPromises(); expect(b.find('[data-testid="bell"]').exists()).toBe(true); b.unmount();
    mock.badge.mockClear();
    mock.levels = { system: 'r' };
    const c = create(); await flushPromises();
    expect(c.find('[data-testid="bell"]').exists()).toBe(false);
    expect(mock.badge).not.toHaveBeenCalled();
    c.unmount();
  });
});
