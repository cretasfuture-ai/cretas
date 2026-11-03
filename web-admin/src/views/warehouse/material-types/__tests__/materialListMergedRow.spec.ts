import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import List from '../list.vue';

const h = vi.hoisted(() => ({
  role: 'warehouse_manager',
  get: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F1' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: () => true,
    canAccess: () => true,
    canViewPrice: false,
    currentRole: h.role,
  }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@/api/request', () => ({
  get: h.get,
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
  patch: vi.fn(),
}));

const rows = [
  { id: 'a', code: 'RM-1', name: '生抽', category: '调味料', unit: 'kg', aliases: ['海天酱油', '酱油'] },
  { id: 'b', code: 'RM-2', name: '海天酱油', category: '调味料', unit: 'kg', isActive: false, mergedIntoId: 'a', mergedIntoName: '生抽' },
];

const wrappers: VueWrapper[] = [];
async function mountList() {
  const w = mount(List, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        MaterialAliasDialog: true,
        MaterialMergeDialog: true,
        ConceptDisambiguationAlert: true,
        UpstreamMissingHint: true,
        UnitSelect: true,
        'el-dialog': true,
      },
    },
  });
  wrappers.push(w);
  await flushPromises();
  return w;
}

beforeEach(() => {
  h.role = 'warehouse_manager';
  h.get.mockReset();
  h.get.mockImplementation((url: string) =>
    Promise.resolve(
      url.endsWith('/raw-material-types')
        ? { success: true, data: { content: rows, totalElements: rows.length } }
        : { success: true, data: [] },
    ),
  );
});
afterEach(() => {
  wrappers.splice(0).forEach((w) => w.unmount());
});

describe('原料类型列表: 别名与已合并行', () => {
  it('显示别名行; 已合并行显示「已合并到」标签且没有别名/合并/编辑按钮', async () => {
    const w = await mountList();
    expect(w.find('[data-test="alias-line"]').text()).toContain('别名：海天酱油、酱油');
    const tags = w.findAll('[data-test="merged-tag"]');
    expect(tags).toHaveLength(1);
    expect(tags[0].text()).toContain('已合并到「生抽」');
    // 两行数据: 只有未合并的那行有 别名 / 合并到… 按钮
    expect(w.findAll('[data-test="row-alias"]').length).toBe(1);
    expect(w.findAll('[data-test="row-merge"]').length).toBe(1);
  });

  it('非仓储主管角色: 别名/合并按钮可见但禁用 (与编辑同一道写门禁)', async () => {
    h.role = 'warehouse_worker';
    const w = await mountList();
    expect(w.find('[data-test="row-merge"]').attributes('disabled')).toBeDefined();
    expect(w.find('[data-test="row-alias"]').attributes('disabled')).toBeDefined();
  });
});
