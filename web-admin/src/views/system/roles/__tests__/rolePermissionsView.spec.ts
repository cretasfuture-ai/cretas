import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const api = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/api/request', () => api)
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-PRIVATE' }) }))
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }))
import RolePermissions from '../list.vue'

const role = { name: 'warehouse_manager', displayName: '验收仓储角色', description: '来自服务端的角色', level: 10 }
const permissions = [{ module: 'warehouse', label: '仓库管理', access: 'r' }]
let permissionsResponse: unknown
let catalogueFails = false
let usersResponse: unknown
let scopeResponse: unknown
const wrappers: VueWrapper[] = []
function openPage() {
  const wrapper = mount(RolePermissions, { attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: { teleport: true,
      'el-dialog': { props: ['modelValue', 'title'], template: '<section v-if="modelValue" role="dialog"><h3>{{ title }}</h3><slot /><slot name="footer" /></section>' },
    } } })
  wrappers.push(wrapper)
  return wrapper
}
async function viewWarehouse(wrapper: VueWrapper, code = 'warehouse_manager') {
  await flushPromises()
  const row = wrapper.findAll('.el-table__row').find(row => row.text().includes(code))
  if (!row) throw new Error('warehouse row absent')
  const button = row.findAll('button').find(button => button.text() === '查看权限')
  if (!button) throw new Error('view permissions button absent')
  await button.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  catalogueFails = false
  scopeResponse = new Error("数据范围读取失败")
  permissionsResponse = { success: true, data: permissions }
  usersResponse = { success: true, data: { content: [{ roleCode: 'warehouse_manager' }], totalElements: 1 } }
  api.get.mockImplementation(async (path: string) => {
    if (path === '/F-PRIVATE/roles') {
      if (catalogueFails) throw new Error('角色目录读取失败')
      return { success: true, data: [role] }
    }
    if (path === '/F-PRIVATE/users') return usersResponse
    if (path.endsWith('/data-scope')) {
      if (scopeResponse instanceof Error) throw scopeResponse
      return scopeResponse
    }
    if (path.endsWith('/permissions')) {
      if (permissionsResponse instanceof Error) throw permissionsResponse
      return permissionsResponse
    }
    throw new Error('unexpected read endpoint')
  })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

async function openScope(wrapper: VueWrapper) {
  await viewWarehouse(wrapper);
  const tab = wrapper.findAll('[role="tab"]').find(tab => tab.text() === '数据权限');
  if (!tab) throw new Error('scope tab missing');
  await tab.trigger('click'); await flushPromises();
}
function scopeView(scope = 'ALL', editable = true, version = 0) {
  return { success: true, data: { roleCode: 'warehouse_manager', scope, configured: version > 0, version, editable } };
}
describe('persisted factory role data scope', () => {
  it('reads the effective server scope and saves with its version before authoritative readback', async () => {
    scopeResponse = scopeView('ALL', true, 3);
    const wrapper = openPage(); await openScope(wrapper);
    expect(wrapper.text()).toContain('当前生效：全工厂');
    const radio = wrapper.findAll('.el-radio').find(row => row.text() === '仅本人创建');
    if (!radio) throw new Error('self choice absent');
    await radio.trigger('click');
    api.put.mockImplementation(async () => { scopeResponse = scopeView('SELF', true, 4); return scopeResponse; });
    const save = wrapper.findAll('button').find(button => button.text() === '保存数据范围');
    if (!save) throw new Error('save absent');
    await save.trigger('click'); await flushPromises();
    expect(api.put).toHaveBeenCalledWith('/F-PRIVATE/roles/warehouse_manager/data-scope', { scope: 'SELF', version: 3 });
    expect(wrapper.text()).toContain('当前生效：仅本人创建');
    expect(wrapper.text()).toContain('本工厂已配置');
    expect(api.get).toHaveBeenCalledWith('/F-PRIVATE/roles/warehouse_manager/data-scope');
  });
  it('does not display a failed save as an effective grant', async () => {
    scopeResponse = scopeView();
    const wrapper = openPage(); await openScope(wrapper);
    const radio = wrapper.findAll('.el-radio').find(row => row.text() === '仅本人创建');
    if (!radio) throw new Error('self choice absent'); await radio.trigger('click');
    api.put.mockResolvedValue({ success: false, message: '版本冲突' });
    await wrapper.findAll('button').find(button => button.text() === '保存数据范围')!.trigger('click'); await flushPromises();
    expect(wrapper.text()).toContain('当前生效：全工厂');
    expect(wrapper.text()).toContain('版本冲突');
    expect(wrapper.text()).not.toContain('当前生效：仅本人创建');
  });
  it('uses server editability and cannot save a locked or read-only role', async () => {
    scopeResponse = scopeView('ALL', false);
    const wrapper = openPage(); await openScope(wrapper);
    expect(wrapper.findAll('button').some(button => button.text() === '保存数据范围')).toBe(false);
    expect(api.put).not.toHaveBeenCalled();
  });
  it.each(['CUSTOM','UNKNOWN','constructor','toString'])('rejects unsupported server scope %s rather than guessing ALL', async scope => {
    scopeResponse = scopeView(scope);
    const wrapper = openPage(); await openScope(wrapper);
    expect(wrapper.find('[data-testid="role-data-scope-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="role-data-scope-result"]').exists()).toBe(false);
    expect(api.put).not.toHaveBeenCalled();
  });
});
