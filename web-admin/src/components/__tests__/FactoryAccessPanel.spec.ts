import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const apiMocks = vi.hoisted(() => ({
  getFactoryAccess: vi.fn(),
  listFactoryAccessUsers: vi.fn(),
  getFactoryAccessUserPermissions: vi.fn(),
  updateFactoryAccessModule: vi.fn(),
  updateFactoryAccessRolePermission: vi.fn(),
  updateFactoryAccessUserPermission: vi.fn(),
}));
const messageMocks = vi.hoisted(() => ({
  confirm: vi.fn().mockResolvedValue(true),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('@/api/platformFactoryAccess', () => apiMocks);
vi.mock('element-plus', () => ({ ElMessage: messageMocks, ElMessageBox: { confirm: messageMocks.confirm } }));

import FactoryAccessPanel from '../FactoryAccessPanel.vue';

const access = (factoryId = 'F006', canManage = true) => ({
  factoryId,
  factoryName: factoryId === 'F006' ? '六膳门' : '二号厂',
  canManage,
  configurationVersion: 3,
  pendingConfiguration: false,
  modules: [{ code: 'procurement', name: '采购', category: 'business', enabled: true, renderingMode: 'standard' }],
  permissionModules: [{ code: 'procurement', name: '采购' }],
  roles: [{
    code: 'finance_manager', name: '财务主管', protectedRole: false,
    globalLevels: { procurement: 'r' }, overrides: { procurement: null }, effectiveLevels: { procurement: 'r' },
  }],
});

const permissions = (userId: number | string) => ({
  user: { id: userId, username: `u${userId}`, displayName: `员工${userId}`, roleCode: 'finance_manager', active: true, protectedRole: false },
  modules: [{ code: 'procurement', name: '采购', roleLevel: 'r' as const, overrideLevel: null, effectiveLevel: 'r' as const }],
});

function mountPanel(factoryId = 'F006', activeTab: 'activation' | 'roles' | 'employees' = 'activation') {
  return shallowMount(FactoryAccessPanel, {
    props: { factoryId, activeTab },
    global: { stubs: {
      'el-alert': true, 'el-button': true, 'el-empty': true, 'el-input': true,
      'el-option': true, 'el-pagination': true, 'el-select': true, 'el-skeleton': true,
      'el-switch': true, 'el-tag': true,
    } },
  });
}

describe('FactoryAccessPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    messageMocks.confirm.mockResolvedValue(true);
    apiMocks.getFactoryAccess.mockResolvedValue({ success: true, data: access() });
    apiMocks.listFactoryAccessUsers.mockResolvedValue({ success: true, data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
    apiMocks.getFactoryAccessUserPermissions.mockResolvedValue({ success: true, data: permissions(7) });
    apiMocks.updateFactoryAccessModule.mockResolvedValue({ success: true, data: access() });
    apiMocks.updateFactoryAccessRolePermission.mockResolvedValue({ success: true, data: access() });
    apiMocks.updateFactoryAccessUserPermission.mockResolvedValue({ success: true, data: permissions(7) });
  });

  it('loads real access state without writing on mount', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    expect(apiMocks.getFactoryAccess).toHaveBeenCalledWith('F006');
    expect(apiMocks.updateFactoryAccessModule).not.toHaveBeenCalled();
    expect((wrapper.vm as unknown as { accessState: { modules: unknown[] } }).accessState.modules).toHaveLength(1);
  });

  it('keeps canManage false read-only and does not call writes', async () => {
    apiMocks.getFactoryAccess.mockResolvedValue({ success: true, data: access('F006', false) });
    const wrapper = mountPanel();
    await flushPromises();
    const vm = wrapper.vm as unknown as { saveModule: (module: unknown, enabled: boolean) => Promise<void>; canManage: boolean };
    expect(vm.canManage).toBe(false);
    await vm.saveModule({ code: 'procurement', name: '采购' }, false);
    expect(apiMocks.updateFactoryAccessModule).not.toHaveBeenCalled();
  });

  it('saves a module only after scoped confirmation and applies server readback', async () => {
    const next = access();
    next.configurationVersion = 4;
    apiMocks.updateFactoryAccessModule.mockResolvedValue({ success: true, data: next });
    const wrapper = mountPanel();
    await flushPromises();
    const vm = wrapper.vm as unknown as {
      saveModule: (module: { code: string; name: string }, enabled: boolean) => Promise<void>;
      accessState: { configurationVersion: number };
    };
    await vm.saveModule({ code: 'procurement', name: '采购' }, false);
    expect(messageMocks.confirm).toHaveBeenCalled();
    expect(apiMocks.updateFactoryAccessModule).toHaveBeenCalledWith('F006', 'procurement', false);
    expect(vm.accessState.configurationVersion).toBe(4);
  });

  it('saves a role row with the current-factory override level', async () => {
    const wrapper = mountPanel('F006', 'roles');
    await flushPromises();
    const vm = wrapper.vm as unknown as {
      accessState: { roles: Array<{ code: string }> };
      setRoleDraft: (role: unknown, moduleCode: string, level: 'rw') => void;
      saveRole: (role: unknown) => Promise<void>;
    };
    const role = vm.accessState.roles[0];
    vm.setRoleDraft(role, 'procurement', 'rw');
    await vm.saveRole(role);
    expect(apiMocks.updateFactoryAccessRolePermission).toHaveBeenCalledWith('F006', 'finance_manager', 'procurement', 'rw');
    expect(messageMocks.confirm).toHaveBeenCalledWith(expect.stringContaining('当前工厂'), '确认保存授权配置', expect.any(Object));
  });

  it('saves an employee exception and keeps protected/inactive users read-only', async () => {
    const wrapper = mountPanel('F006', 'employees');
    await flushPromises();
    const vm = wrapper.vm as unknown as {
      selectedUser: unknown;
      userPermissions: unknown;
      setEmployeeDraft: (moduleCode: string, level: 'w') => void;
      saveEmployeePermission: (moduleCode: string) => Promise<void>;
      selectedUserIsWritable: boolean;
    };
    vm.selectedUser = { id: 7, username: 'u7', displayName: '员工7', roleCode: 'finance_manager', active: true, protectedRole: false };
    vm.userPermissions = permissions(7);
    vm.setEmployeeDraft('procurement', 'w');
    await vm.saveEmployeePermission('procurement');
    expect(apiMocks.updateFactoryAccessUserPermission).toHaveBeenCalledWith('F006', 7, 'procurement', 'w');
    expect(messageMocks.confirm).toHaveBeenCalledWith(expect.stringContaining('不改变其岗位默认'), '确认保存授权配置', expect.any(Object));

    vm.selectedUser = { id: 8, username: 'u8', displayName: '停用员工', roleCode: 'finance_manager', active: false, protectedRole: false };
    expect(vm.selectedUserIsWritable).toBe(false);
  });

  it('labels a cleared employee override as inheriting the role', async () => {
    const wrapper = mountPanel('F006', 'employees');
    await flushPromises();
    const vm = wrapper.vm as unknown as { employeeLevelLabel: (level: null) => string };
    expect(vm.employeeLevelLabel(null)).toBe('随岗位');
  });

  it('suppresses a stale factory response after selection changes', async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveSecond!: (value: unknown) => void;
    apiMocks.getFactoryAccess
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve; }));
    const wrapper = mountPanel('F006');
    await flushPromises();
    await wrapper.setProps({ factoryId: 'F001' });
    resolveFirst({ success: true, data: access('F006') });
    await flushPromises();
    expect((wrapper.vm as unknown as { accessState: unknown }).accessState).toBeNull();
    resolveSecond({ success: true, data: access('F001') });
    await flushPromises();
    expect((wrapper.vm as unknown as { accessState: { factoryId: string } }).accessState.factoryId).toBe('F001');
  });

  it('loads employees and rejects a late permission response for a different selection', async () => {
    apiMocks.listFactoryAccessUsers.mockResolvedValue({
      success: true,
      data: {
        content: [
          { id: 7, username: 'u7', displayName: '员工7', roleCode: 'finance_manager', active: true, protectedRole: false },
          { id: 8, username: 'u8', displayName: '员工8', roleCode: 'finance_manager', active: true, protectedRole: false },
        ], totalElements: 2, totalPages: 1, number: 0, size: 20,
      },
    });
    let resolveFirst!: (value: unknown) => void;
    apiMocks.getFactoryAccessUserPermissions
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({ success: true, data: permissions(8) });
    const wrapper = mountPanel('F006', 'employees');
    await flushPromises();
    const vm = wrapper.vm as unknown as { selectUser: (user: unknown) => Promise<void>; users: unknown[]; userPermissions: unknown };
    void vm.selectUser({ id: 7, username: 'u7', displayName: '员工7', roleCode: 'finance_manager', active: true, protectedRole: false });
    void vm.selectUser({ id: 8, username: 'u8', displayName: '员工8', roleCode: 'finance_manager', active: true, protectedRole: false });
    await flushPromises();
    resolveFirst({ success: true, data: permissions(7) });
    await flushPromises();
    expect((wrapper.vm as unknown as { userPermissions: { user: { id: number } } }).userPermissions.user.id).toBe(8);
  });
});
