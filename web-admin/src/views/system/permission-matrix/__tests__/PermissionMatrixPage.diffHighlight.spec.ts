/**
 * 权限矩阵页的**高亮**判据 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906)。
 *
 * 这个页面唯一有实质判断的行为就是「哪些格子该亮」。所以断言必须打在
 * <b>渲染出来的那个格子</b>上 (形态 B: 单测直接调纯函数会绕过「谁调它」),
 * 并且成对出现:
 * - 阳性: fixture 里有 1 个 override + 1 个 fallback ⇒ 恰好 2 格带 `pm-cell--diff`;
 * - 阴性: 同一张表里两侧一致的那些格子<b>一个都不许亮</b> (少了这一半,
 *   一个「所有格子都加 diff class」的实现也能让阳性断言全绿)。
 * - 阴性 2: 一份完全无分歧的 fixture ⇒ 页面上 `pm-cell--diff` 数量为 0。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import type { PermissionMatrixView } from '@/api/permissionMatrixApi';

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
// step-2 R2: 页面读 permissionStore.canWrite('system') 决定编辑态; 本 spec 只看高亮, 给个恒 true 的替身
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));

const getPermissionMatrix = vi.fn();
vi.mock('@/api/permissionMatrixApi', () => ({
  getPermissionMatrix: (...args: unknown[]) => getPermissionMatrix(...args),
}));

// 轴写死成 3 列, 让「哪几格该亮」是可以手数的。
vi.mock('../moduleAxis', () => ({
  sidebarModuleAxis: () => ['finance', 'production', 'quality'],
  offAxisModules: (all: string[], axis: string[]) => all.filter((m) => !axis.includes(m)),
  axisModulesMissingFromData: (axis: string[], all: string[]) =>
    axis.filter((m) => !all.includes(m)),
}));

import PermissionMatrixPage from '../index.vue';

function viewWith(overrides: Partial<PermissionMatrixView>): PermissionMatrixView {
  return {
    factoryId: 'F006',
    modules: ['finance', 'production', 'quality'],
    roles: [
      { code: 'viewer', displayName: '查看者', group: 'factory', level: 50 },
      { code: 'operator', displayName: '操作员', group: 'factory', level: 30 },
    ],
    l1: {},
    hardcoded: {},
    diffs: [],
    l2Overrides: [],
    l2RoleOverrides: [],
    modulesWithoutL1: [],
    ...overrides,
  };
}

/** 1 个 override + 1 个 fallback + 4 个一致格 */
const WITH_DIFFS = viewWith({
  l1: {
    viewer: { finance: '-', production: 'r', quality: 'r' },
    operator: { finance: '-', quality: '-' },   // production 没有 L1 行
  },
  hardcoded: {
    viewer: { finance: 'r', production: 'r', quality: 'r' },
    operator: { finance: '-', production: 'rw', quality: '-' },
  },
  diffs: [
    { role: 'viewer', module: 'finance', l1: '-', hardcoded: 'r', kind: 'override' },
    { role: 'operator', module: 'production', l1: null, hardcoded: 'rw', kind: 'fallback' },
  ],
});

/** 阴性对照 fixture: 两侧逐格一致 ⇒ 一格都不该亮 */
const NO_DIFFS = viewWith({
  l1: {
    viewer: { finance: 'r', production: 'r', quality: 'r' },
    operator: { finance: '-', production: 'rw', quality: '-' },
  },
  hardcoded: {
    viewer: { finance: 'r', production: 'r', quality: 'r' },
    operator: { finance: '-', production: 'rw', quality: '-' },
  },
  diffs: [],
});

async function render() {
  const wrapper = mount(PermissionMatrixPage);
  await flushPromises();
  return wrapper;
}

describe('权限矩阵页 · 分歧高亮', () => {
  beforeEach(() => {
    getPermissionMatrix.mockReset();
  });

  it('阳性: 恰好 2 格带高亮, 且正是 fixture 里那两格', async () => {
    getPermissionMatrix.mockResolvedValue(WITH_DIFFS);
    const wrapper = await render();

    // 前提断言: 表真的渲染出来了 (6 格), 否则「0 格亮」是仪器没跑
    expect(wrapper.findAll('td.pm-cell')).toHaveLength(6);

    const highlighted = wrapper.findAll('td.pm-cell--diff');
    expect(highlighted).toHaveLength(2);
    expect(highlighted.map((td) => td.attributes('data-cell')).sort())
      .toEqual(['operator/production', 'viewer/finance']);
  });

  it('阳性: override 与 fallback 用不同的 class, 且 tooltip 写出硬编码值', async () => {
    getPermissionMatrix.mockResolvedValue(WITH_DIFFS);
    const wrapper = await render();

    const override = wrapper.find('td[data-cell="viewer/finance"]');
    expect(override.classes()).toContain('pm-cell--override');
    expect(override.attributes('title')).toContain('硬编码矩阵: r');

    const fallback = wrapper.find('td[data-cell="operator/production"]');
    expect(fallback.classes()).toContain('pm-cell--fallback');
    expect(fallback.attributes('title')).toContain('硬编码矩阵: rw');
    // L1 无行 ⇒ 显示的是兜底值, 并标「兜底」
    expect(fallback.text()).toContain('rw');
    expect(fallback.text()).toContain('兜底');
  });

  it('阴性对照 (同一张表): 两侧一致的 4 格一格都不亮', async () => {
    getPermissionMatrix.mockResolvedValue(WITH_DIFFS);
    const wrapper = await render();

    for (const key of ['viewer/production', 'viewer/quality', 'operator/finance', 'operator/quality']) {
      const td = wrapper.find(`td[data-cell="${key}"]`);
      expect(td.exists(), `${key} 这一格没渲染出来`).toBe(true);
      expect(td.classes(), `${key} 不该高亮`).not.toContain('pm-cell--diff');
    }
  });

  it('阴性对照 (整份无分歧 fixture): 高亮 0 格, 而表格照样有 6 格', async () => {
    getPermissionMatrix.mockResolvedValue(NO_DIFFS);
    const wrapper = await render();

    expect(wrapper.findAll('td.pm-cell')).toHaveLength(6);   // Y: 一定会发生的读数
    expect(wrapper.findAll('td.pm-cell--diff')).toHaveLength(0);
    expect(wrapper.get('[data-testid="pm-diff-count"]').text()).toBe('0');
  });

  it('L2 关掉的模块列置灰, 且那一列的格子带 l2-off', async () => {
    getPermissionMatrix.mockResolvedValue(viewWith({
      l1: { viewer: { quality: 'r' }, operator: { quality: 'r' } },
      hardcoded: { viewer: { quality: 'r' }, operator: { quality: 'r' } },
      l2Overrides: [{ module: 'quality', enabled: false }],
    }));
    const wrapper = await render();

    expect(wrapper.find('td[data-cell="viewer/quality"]').classes())
      .toContain('pm-cell--l2-off');
    expect(wrapper.find('td[data-cell="viewer/finance"]').classes())
      .not.toContain('pm-cell--l2-off');
  });

  it('⛔ 只读: 页面上没有任何 input / select / 保存按钮', async () => {
    getPermissionMatrix.mockResolvedValue(WITH_DIFFS);
    const wrapper = await render();

    expect(wrapper.findAll('input')).toHaveLength(0);
    expect(wrapper.findAll('select')).toHaveLength(0);
    expect(wrapper.findAll('button')).toHaveLength(0);
    expect(wrapper.text()).not.toContain('保存');
  });

  it('接口报错时显示错误, ⛔ 不降级成空表格', async () => {
    getPermissionMatrix.mockRejectedValue(new Error('权限不足'));
    const wrapper = await render();

    expect(wrapper.get('[data-testid="pm-error"]').text()).toContain('权限不足');
    expect(wrapper.find('[data-testid="pm-table"]').exists()).toBe(false);
  });
});
