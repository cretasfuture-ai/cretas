import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import DisposalList from './list.vue';

/**
 * D-S302-03: 审批按钮跟后端角色闸走, 新建入口仍跟质检写权限走。
 * 财务经理进来: 能批, 不能建, 也不发他没权限的原料批次请求。
 */
const get = vi.fn();
const writable = reactive<Record<string, boolean>>({ quality: false, finance: false });
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: (module: string) => writable[module] === true, canAccess: () => true }),
}));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => get(...args), post: vi.fn(), put: vi.fn() }));
vi.mock('vue-router', () => ({ useRoute: () => reactive({ query: {} }), useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/api/attachment', () => ({ getUploadUrl: vi.fn(), putFileWithProgress: vi.fn() }));
vi.mock('@/api/factoryRecall', () => ({ getFactoryRecall: vi.fn() }));

const stubs = {
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': {
    template: `<div><slot :row="{ id: 7, status: 'PENDING', disposalType: 'SCRAP' }" /></div>`,
  },
  'el-button': { template: '<button><slot /></button>' },
  'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-dialog': { template: '<div />' },
  DisposalVoucherLinks: true,
};

async function mountAs(role: Partial<typeof writable>) {
  writable.quality = role.quality === true;
  writable.finance = role.finance === true;
  const wrapper = shallowMount(DisposalList, { global: { stubs, directives: { loading: () => {} } } });
  await flushPromises();
  return wrapper;
}

const has = (wrapper: Awaited<ReturnType<typeof mountAs>>, action: string) =>
  wrapper.find(`[data-activity-action="${action}"]`).exists();

describe('报废页按钮权限', () => {
  beforeEach(() => {
    get.mockReset();
    get.mockResolvedValue({ success: true, data: [] });
  });

  it('财务经理: 有批准和拒绝, 没有新建, 不请求原料批次', async () => {
    const wrapper = await mountAs({ finance: true });
    expect(has(wrapper, 'disposal.approve')).toBe(true);
    expect(has(wrapper, 'disposal.reject.open')).toBe(true);
    expect(has(wrapper, 'disposal.create.open')).toBe(false);
    expect(get.mock.calls.map(([url]) => String(url))).not.toContain('/F006/material-batches');
    expect(get.mock.calls.map(([url]) => String(url))).toContain('/F006/disposal-records');
  });

  it('质检角色: 有新建, 没有点了必 403 的批准和拒绝', async () => {
    const wrapper = await mountAs({ quality: true });
    expect(has(wrapper, 'disposal.create.open')).toBe(true);
    expect(has(wrapper, 'disposal.approve')).toBe(false);
    expect(has(wrapper, 'disposal.reject.open')).toBe(false);
    expect(get.mock.calls.map(([url]) => String(url))).toContain('/F006/material-batches');
  });

  it('工厂管理员: 两边都有', async () => {
    const wrapper = await mountAs({ quality: true, finance: true });
    expect(has(wrapper, 'disposal.create.open')).toBe(true);
    expect(has(wrapper, 'disposal.approve')).toBe(true);
  });
});
