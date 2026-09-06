/**
 * step-2 编辑态 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906, owner 2026-09-06 裁定)。
 *
 * 打在**渲染出来的格子**上 (形态 B), 成对:
 * - 阳性: 点工厂角色的格 ⇒ 出编辑器 ⇒ 保存 ⇒ PUT 发出 ⇒ **重新 GET**(N9: 显示的是回读, 不是请求体)
 *   ⇒ 回读值出现在格上; 「全平台生效」提示在页面上。
 * - 阴性 N8: 餐饮角色的格 / 餐饮模块的列 点了**不出编辑器**、不发 PUT; unactivated 同。
 * - 阴性: 后端 400/403 原样显示, 不吞成成功。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import type { PermissionMatrixView } from '@/api/permissionMatrixApi';

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));

const getPermissionMatrix = vi.fn();
const writePermissionMatrixCell = vi.fn();
vi.mock('@/api/permissionMatrixApi', () => ({
  getPermissionMatrix: (...args: unknown[]) => getPermissionMatrix(...args),
  writePermissionMatrixCell: (...args: unknown[]) => writePermissionMatrixCell(...args),
}));

vi.mock('../moduleAxis', () => ({
  sidebarModuleAxis: () => ['finance', 'restaurantOps', 'sales'],
  offAxisModules: (all: string[], axis: string[]) => all.filter((m) => !axis.includes(m)),
  axisModulesMissingFromData: (axis: string[], all: string[]) =>
    axis.filter((m) => !all.includes(m)),
}));

import PermissionMatrixPage from '../index.vue';

function view(l1SalesFinance: 'rw' | 'r' | 'w' | '-'): PermissionMatrixView {
  return {
    factoryId: 'F006',
    modules: ['finance', 'restaurantOps', 'sales'],
    roles: [
      { code: 'sales_manager', displayName: '销售主管', group: 'factory', level: 10 },
      { code: 'restaurant_owner', displayName: '餐饮老板', group: 'restaurant', level: 5 },
      { code: 'unactivated', displayName: '未激活', group: 'factory', level: 99 },
    ],
    l1: {
      sales_manager: { finance: l1SalesFinance, sales: 'rw', restaurantOps: '-' },
      restaurant_owner: { finance: 'rw', sales: 'rw', restaurantOps: 'rw' },
      unactivated: { finance: '-', sales: '-', restaurantOps: '-' },
    },
    hardcoded: {},
    diffs: [],
    l2Overrides: [],
    l2RoleOverrides: [],
    modulesWithoutL1: [],
  };
}

async function mountPage(initial: PermissionMatrixView) {
  getPermissionMatrix.mockResolvedValueOnce(initial);
  const wrapper = mount(PermissionMatrixPage);
  await flushPromises();
  return wrapper;
}

describe('permission matrix step-2 edit', () => {
  beforeEach(() => {
    getPermissionMatrix.mockReset();
    writePermissionMatrixCell.mockReset();
  });

  it('页面明示「全平台生效」', async () => {
    const wrapper = await mountPage(view('-'));
    expect(wrapper.find('[data-testid="pm-global-warning"]').text()).toContain('全平台生效');
  });

  it('阳性: 点工厂格 → 编辑器 → 保存 ⇒ PUT + 重新 GET, 格上显示的是回读值', async () => {
    const wrapper = await mountPage(view('-'));
    const cell = wrapper.find('[data-cell="sales_manager/finance"]');
    expect(cell.attributes('data-editable')).toBe('true');
    expect(cell.text()).toContain('-');

    await cell.trigger('click');
    const editor = wrapper.find('[data-testid="pm-editor"]');
    expect(editor.exists()).toBe(true);

    await wrapper.find('[data-testid="pm-editor-level"]').setValue('r');
    await wrapper.find('[data-testid="pm-editor-reason"]').setValue('财务看报表');

    // 回读: 后端返回 r, 第二次 GET 也返回 r —— 页面必须靠第二次 GET 而不是请求体
    writePermissionMatrixCell.mockResolvedValueOnce({
      role: 'sales_manager', module: 'finance', oldLevel: '-', level: 'r', auditId: 501,
    });
    getPermissionMatrix.mockResolvedValueOnce(view('r'));

    await wrapper.find('[data-testid="pm-editor-save"]').trigger('click');
    await flushPromises();

    expect(writePermissionMatrixCell).toHaveBeenCalledTimes(1);
    expect(writePermissionMatrixCell).toHaveBeenCalledWith('F006', 'sales_manager', 'finance', 'r', '财务看报表');
    expect(getPermissionMatrix).toHaveBeenCalledTimes(2);   // 初次 + 写后回读
    expect(wrapper.find('[data-testid="pm-editor"]').exists()).toBe(false);
    expect(wrapper.find('[data-cell="sales_manager/finance"]').text()).toContain('r');
    expect(wrapper.find('[data-testid="pm-last-write"]').text()).toContain('#501');
  });

  it('N9 反向: 写成功但回读仍是旧值 ⇒ 格上显示旧值 (页面不信请求体)', async () => {
    const wrapper = await mountPage(view('-'));
    await wrapper.find('[data-cell="sales_manager/finance"]').trigger('click');
    await wrapper.find('[data-testid="pm-editor-level"]').setValue('rw');
    writePermissionMatrixCell.mockResolvedValueOnce({
      role: 'sales_manager', module: 'finance', oldLevel: '-', level: 'rw', auditId: 502,
    });
    getPermissionMatrix.mockResolvedValueOnce(view('-'));   // 回读说还是 -
    await wrapper.find('[data-testid="pm-editor-save"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-cell="sales_manager/finance"]').text()).toContain('-');
    expect(wrapper.find('[data-cell="sales_manager/finance"]').text()).not.toContain('rw');
  });

  it('N8 餐饮不管: 餐饮角色的格 与 餐饮模块的列 点了不出编辑器、不发 PUT; unactivated 同', async () => {
    const wrapper = await mountPage(view('-'));
    for (const key of ['restaurant_owner/finance', 'sales_manager/restaurantOps', 'unactivated/finance']) {
      const cell = wrapper.find(`[data-cell="${key}"]`);
      expect(cell.attributes('data-editable'), key).toBe('false');
      await cell.trigger('click');
      expect(wrapper.find('[data-testid="pm-editor"]').exists(), key).toBe(false);
    }
    expect(writePermissionMatrixCell).not.toHaveBeenCalled();
    // 阳性对照: 同一张表里工厂格能进编辑
    await wrapper.find('[data-cell="sales_manager/finance"]').trigger('click');
    expect(wrapper.find('[data-testid="pm-editor"]').exists()).toBe(true);
  });

  it('阴性: 后端拒绝 (400/403) ⇒ 原样显示, 编辑器留着, 不重新 GET', async () => {
    const wrapper = await mountPage(view('-'));
    await wrapper.find('[data-cell="sales_manager/finance"]').trigger('click');
    writePermissionMatrixCell.mockRejectedValueOnce(new Error('禁止降低 platform_admin 对 system 的权限'));
    await wrapper.find('[data-testid="pm-editor-save"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="pm-save-error"]').text()).toContain('禁止降低');
    expect(wrapper.find('[data-testid="pm-editor"]').exists()).toBe(true);
    expect(getPermissionMatrix).toHaveBeenCalledTimes(1);
  });

  it('取消 ⇒ 编辑器消失, 不发 PUT', async () => {
    const wrapper = await mountPage(view('-'));
    await wrapper.find('[data-cell="sales_manager/finance"]').trigger('click');
    await wrapper.find('[data-testid="pm-editor-cancel"]').trigger('click');
    expect(wrapper.find('[data-testid="pm-editor"]').exists()).toBe(false);
    expect(writePermissionMatrixCell).not.toHaveBeenCalled();
  });
});
