/**
 * 角色管理 → 查看权限：「没读到」不许长成「这个角色没有权限」。
 *
 * <h3>刀 D 之前它就是这么长的</h3>
 * 接口取不到时，页面调 `derivePermissions(role)` —— 按 `role.level` 和一句
 * `role.name.includes(m.key.replace('ment',''))` **现编一张 11 行的权限表**填进弹窗，
 * 与真实读数同样的表格、同样的标签、同样的颜色。管理员照着它判断谁能干什么，
 * 而它与系统实际执行的权限没有任何关系。核心原则 1：禁止降级处理，不返回假数据。
 *
 * <h3>三态各一条断言 —— 少任何一条，另外两条都可能是恒真式</h3>
 * <ol>
 *   <li>接口成功且有数据 ⇒ 照印后端下发的行（列/中文名/列序都由后端定，前端不再有第二份清单）。</li>
 *   <li>接口成功且**空列表** ⇒ 合法读数，照印空表，⛔ 不报错。</li>
 *   <li>接口失败 ⇒ 明确说没读到 + 给重试，⛔ 表里不许出现任何一行。</li>
 * </ol>
 * ⚠️ 第 2 条是防止「一失败就报错」被写成「只要表是空的就报错」——
 * 那两件事在页面上又会变成同一个长相。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const apiGet = vi.fn();
const apiPut = vi.fn();

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canAccess: () => true }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  put: (...args: unknown[]) => apiPut(...args),
}));
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}));

import RolesList from '../list.vue';

/** 后端下发的形状 —— 注意它带着旧写死清单里**没有**的列 (work_report / operations),
 *  正是「列由后端定」的证据: 前端凭自己那份 11 条清单渲染不出这两行。 */
const BACKEND_ROWS = [
  { module: 'dashboard', label: '首页', access: 'r' },
  { module: 'quality', label: '质量管理', access: 'rw' },
  { module: 'work_report', label: '工作报告', access: 'rw' },
  { module: 'operations', label: '运营协调', access: 'r' },
];

/**
 * 后端下发的角色清单。刻意包含三类, 每一类都在守一件具体的事:
 *  · `yield_operator` / `department_admin` —— 旧写死清单里**没有**它们, 而它们真有 F006 用户
 *    ⇒ 渲染得出来就证明清单来自端点;
 *  · `restaurant_owner` —— department='restaurant', 必须被滤掉 (Steve「餐饮不管」);
 *  · level 用权威刻度 (0 / 30 / 15), ⛔ 不是页面此前那套 1-4。
 */
const BACKEND_ROLES = [
  { name: 'factory_super_admin', displayName: '工厂总监', description: '拥有工厂所有权限', level: 0, department: 'all' },
  { name: 'yield_operator', displayName: '报工操作员', description: '报工写入', level: 30, department: 'production' },
  { name: 'department_admin', displayName: '部门管理员', description: '部门内管理', level: 15, department: 'department' },
  { name: 'restaurant_owner', displayName: '餐饮老板', description: '连锁经营决策', level: 0, department: 'restaurant' },
];

/**
 * 三条 GET 按 URL 分派, ⛔ 不按调用顺序 (顺序分派在请求顺序变化时会静默对调, 而两个响应
 * 都是合法形状 ⇒ 断言照样绿, 量的却是另一件事)。
 * ⚠️ 顺序要紧: `/{factoryId}/roles/{role}/permissions` 同时含 `/roles` 与 `/permissions`,
 *    所以先判 `/permissions`。
 */
function routeGet(permissions: unknown, rolesResponse: unknown = { success: true, data: BACKEND_ROLES }) {
  apiGet.mockImplementation((url: string) => {
    const u = String(url);
    if (u.includes('/permissions')) {
      return permissions instanceof Error ? Promise.reject(permissions) : Promise.resolve(permissions);
    }
    if (u.endsWith('/roles')) {
      return rolesResponse instanceof Error ? Promise.reject(rolesResponse) : Promise.resolve(rolesResponse);
    }
    return Promise.resolve({ success: true, data: { content: [], totalElements: 0 } });
  });
}

/**
 * Element Plus 桩 —— 与 `views/admin/__tests__/data-quality-queue.spec.ts` 同一套做法。
 * ⚠️ `el-table` 必须把 `row` 传进 `#default` 作用域插槽: 不传的话模板里
 * `#default="{ row }"` 会在 undefined 上解构而炸, 那是**桩的故障**, 不是产品的。
 */
const STUBS = {
  'router-link': { template: '<a><slot /></a>' },
  // ⚠️ 必须渲染具名 header 插槽: 只写 <slot /> 会把卡片头整段吞掉, 于是「徽标不见了」
  //    与「徽标写错了」在断言里长得一模一样 —— 那是桩的故障, 不是产品的。
  'el-card': { template: '<div><header><slot name="header" /></header><slot /></div>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-text': { template: '<span><slot /></span>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { props: ['label'], template: '<div><label>{{ label }}</label><slot /></div>' },
  'el-input': { props: ['modelValue'], template: '<input :value="modelValue" />' },
  'el-radio': { template: '<label><slot /></label>' },
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-tabs': { template: '<div><slot /></div>' },
  'el-tab-pane': { props: ['label'], template: '<section><slot /></section>' },
  'el-dialog': { props: ['modelValue'], template: '<div><slot /><slot name="footer" /></div>' },
  'el-descriptions': { template: '<dl><slot /></dl>' },
  'el-descriptions-item': { props: ['label'], template: '<div><dt>{{ label }}</dt><dd><slot /></dd></div>' },
  'el-alert': {
    props: ['title', 'type', 'closable', 'description'],
    template: '<div class="el-alert" :data-testid="$attrs[\'data-testid\']">{{ title }}{{ description }}<slot /></div>',
  },
  'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-table': {
    props: ['data'],
    template: '<table><tbody><tr v-for="(row, $index) in (data || [])" :key="$index"><slot :row="row" /></tr></tbody></table>',
  },
  'el-table-column': {
    props: ['prop', 'label'],
    template: '<td><slot :row="$parent.$props.data?.[0] ?? {}" /></td>',
  },
};

async function openViewDialog() {
  const wrapper = mount(RolesList, { global: { stubs: STUBS } });
  await flushPromises();
  // 直接驱动产品入口的那个 handler（表格行的「查看权限」按钮调的就是它），
  // ⛔ 不去构造一次 DOM 点击：那会把「按钮选择器写对了没有」混进这条断言里。
  const vm = wrapper.vm as unknown as {
    handleViewPermissions: (row: { name: string; level: number; displayName: string }) => Promise<void>;
    rolePermissions: Array<Record<string, unknown>>;
    permissionsError: string;
  };
  await vm.handleViewPermissions({ name: 'quality_controller', level: 3, displayName: '质检员' });
  await flushPromises();
  return { wrapper, vm };
}

describe('角色权限弹窗的三态', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPut.mockReset();
  });

  it('① 接口成功有数据: 照印后端下发的行 (含前端旧清单里没有的列)', async () => {
    routeGet({ success: true, data: BACKEND_ROWS });
    const { vm } = await openViewDialog();

    expect(vm.permissionsError).toBe('');
    expect(vm.rolePermissions.map((r) => r.module)).toEqual(
      ['dashboard', 'quality', 'work_report', 'operations'],
    );
    expect(vm.rolePermissions.map((r) => r.label)).toContain('工作报告');
  });

  it('② 接口成功但空列表: 合法读数, 照印空表, ⛔ 不报错', async () => {
    routeGet({ success: true, data: [] });
    const { vm } = await openViewDialog();

    expect(vm.rolePermissions).toEqual([]);
    expect(vm.permissionsError, '「一行都没有」是合法读数, 不是失败').toBe('');
  });

  it('③ 接口失败: 说清没读到 + 给重试, ⛔ 表里一行都不许有', async () => {
    routeGet(new Error('Network Error'));
    const { wrapper, vm } = await openViewDialog();

    expect(vm.rolePermissions, '🔴 失败时表里出现了行 —— 那些行只能是编出来的').toEqual([]);
    expect(vm.permissionsError).toContain('Network Error');

    const html = wrapper.html();
    expect(html).toContain('role-permissions-error');
    expect(html, 'fool-proof Rule 5: 不留死路, 要有下一步').toContain('重试');
    expect(html, '必须说清「不代表没有权限」').toContain('这不代表该角色没有权限');
  });

  it('⑤ 角色数徽标必须与实际渲染的行数一致 (它此前写死「共 14 种」而表里有 17 行)', async () => {
    routeGet({ success: true, data: BACKEND_ROWS });
    const wrapper = mount(RolesList, { global: { stubs: STUBS } });
    await flushPromises();
    const vm = wrapper.vm as unknown as { roles: unknown[] };

    // 阳性对照: 真实条数不是 14 —— 否则「写死 14」与「算出来」在页面上一模一样,
    // 这条断言就分不出它俩 (那正是这个缺陷藏了这么久的原因)。
    expect(vm.roles.length, '角色条数恰好是 14 时这条断言无法区分写死与现算').not.toBe(14);
    expect(wrapper.html()).toContain(`共 ${vm.roles.length} 种工厂角色`);
  });

  it('④ 编辑弹窗整块删除 —— 它打向一个全仓不存在的 PUT 端点, 且入口按钮 disabled', async () => {
    routeGet({ success: true, data: BACKEND_ROWS });
    const wrapper = mount(RolesList, { global: { stubs: STUBS } });
    await flushPromises();

    const vm = wrapper.vm as unknown as Record<string, unknown>;
    expect(vm.handleEdit, '编辑 handler 不该还在').toBeUndefined();
    expect(vm.editForm, 'editForm 不该还在').toBeUndefined();
    expect(apiPut, '这一页不该再有任何 PUT —— 那条路径后端没有 handler').not.toHaveBeenCalled();

    // 禁用按钮仍在 (改名称/描述确实还没端点), 但它得指出一条今天办得到的路 —— fool-proof Rule 5
    expect(wrapper.html()).toContain('role-edit-disabled');
    expect(wrapper.html(), '要指到真正能改权限的地方 (权限矩阵页, 写 L1 带审计)').toContain('权限矩阵');
  });
});

describe('角色清单来自后端, ⛔ 不是写死的', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPut.mockReset();
  });

  async function mounted() {
    const wrapper = mount(RolesList, { global: { stubs: STUBS } });
    await flushPromises();
    return { wrapper, vm: wrapper.vm as unknown as { roles: Array<Record<string, unknown>>; rolesError: string } };
  }

  it('⑥ 端点下发什么就渲染什么 —— 含旧写死清单里没有的角色', async () => {
    routeGet({ success: true, data: BACKEND_ROWS });
    const { vm } = await mounted();

    expect(vm.roles.map((r) => r.name))
      .toEqual(['factory_super_admin', 'yield_operator', 'department_admin']);
    // 🔴 这两个是「清单来自端点」的硬证据: 旧写死数组里根本没有它们, 而它们真有 F006 用户
    expect(vm.roles.map((r) => r.name)).toContain('yield_operator');
    expect(vm.roles.map((r) => r.name)).toContain('department_admin');
  });

  it('⑦ 枚举里没有的「幽灵角色码」不许再出现', async () => {
    routeGet({ success: true, data: BACKEND_ROWS });
    const { vm } = await mounted();

    // 刀 D 视觉验收实测: 这 5 个码在 FactoryUserRole 里根本不存在, 而旧写死清单里有,
    // 每一个都被后端 fromRoleCode 静默降级后渲染成「17 行全无权限」。
    for (const phantom of ['warehouse_operator', 'procurement_staff', 'sales_staff', 'hr_manager', 'equipment_manager']) {
      expect(vm.roles.map((r) => r.name), `幽灵码 ${phantom} 又回来了`).not.toContain(phantom);
    }
  });

  it('⑧ 餐饮角色被滤掉 (Steve「餐饮不管」)，且判据不是恒真式', async () => {
    routeGet({ success: true, data: BACKEND_ROWS });
    const { vm } = await mounted();

    expect(vm.roles.map((r) => r.name)).not.toContain('restaurant_owner');
    // 阳性对照: 端点确实下发了餐饮角色, 否则上面那条什么都没检查
    expect(BACKEND_ROLES.map((r) => r.name)).toContain('restaurant_owner');
  });

  it('⑨ 拉不到角色清单 ⇒ 说清没读到, ⛔ 不留一张看起来正常的空表', async () => {
    routeGet({ success: true, data: BACKEND_ROWS }, new Error('Network Error'));
    const { vm } = await mounted();

    expect(vm.roles).toEqual([]);
    expect(vm.rolesError, '「没读到」和「这个工厂没有角色」必须分得开').toContain('Network Error');
  });
});
