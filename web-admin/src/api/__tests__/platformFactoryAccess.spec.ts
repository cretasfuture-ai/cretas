import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMocks = vi.hoisted(() => ({
  adminGet: vi.fn(),
  adminPut: vi.fn(),
}));

vi.mock('../request', () => requestMocks);

import {
  getFactoryAccess,
  getFactoryAccessUserPermissions,
  listFactoryAccessUsers,
  updateFactoryAccessModule,
  updateFactoryAccessRolePermission,
  updateFactoryAccessUserPermission,
} from '../platformFactoryAccess';

const access = (factoryId = 'F006') => ({
  factoryId,
  factoryName: '六膳门',
  canManage: true,
  configurationVersion: 4,
  pendingConfiguration: false,
  modules: [],
  permissionModules: [],
  roles: [],
});

describe('platform factory access API', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads the full factory access state and rejects a stale factory response', async () => {
    requestMocks.adminGet.mockResolvedValue({ success: true, data: access() });
    await expect(getFactoryAccess('F006')).resolves.toMatchObject({ data: { factoryId: 'F006' } });
    expect(requestMocks.adminGet).toHaveBeenCalledWith('/api/platform/factories/F006/access', { baseURL: '' });

    requestMocks.adminGet.mockResolvedValue({ success: true, data: access('F001') });
    await expect(getFactoryAccess('F006')).rejects.toThrow('响应范围不匹配');
  });

  it('uses real pageable employee-directory query parameters', async () => {
    requestMocks.adminGet.mockResolvedValue({
      success: true,
      data: { content: [], totalElements: 0, totalPages: 0, number: 1, size: 20 },
    });
    await listFactoryAccessUsers('F/006', { keyword: '张', page: 1, size: 20 });
    expect(requestMocks.adminGet).toHaveBeenCalledWith(
      '/api/platform/factories/F%2F006/access/users',
      { baseURL: '', params: { keyword: '张', page: 1, size: 20 } },
    );
  });

  it('rejects a permissions response for a different employee', async () => {
    requestMocks.adminGet.mockResolvedValue({
      success: true,
      data: { user: { id: 22 }, modules: [] },
    });
    await expect(getFactoryAccessUserPermissions('F006', 7)).rejects.toThrow('员工授权响应范围不匹配');
  });

  it('sends scoped module, role and employee writes with exact levels', async () => {
    requestMocks.adminPut
      .mockResolvedValueOnce({ success: true, data: access() })
      .mockResolvedValueOnce({ success: true, data: access() })
      .mockResolvedValueOnce({ success: true, data: { user: { id: 7 }, modules: [] } });

    await updateFactoryAccessModule('F006', 'procurement', false);
    await updateFactoryAccessRolePermission('F006', 'finance_manager', 'procurement', 'rw');
    await updateFactoryAccessUserPermission('F006', 7, 'procurement', null);

    expect(requestMocks.adminPut).toHaveBeenNthCalledWith(
      1,
      '/api/platform/factories/F006/access/modules/procurement',
      { enabled: false },
    );
    expect(requestMocks.adminPut).toHaveBeenNthCalledWith(
      2,
      '/api/platform/factories/F006/access/roles/finance_manager/procurement',
      { level: 'rw' },
    );
    expect(requestMocks.adminPut).toHaveBeenNthCalledWith(
      3,
      '/api/platform/factories/F006/access/users/7/permissions/procurement',
      { level: null },
    );
  });

  it('does not turn an unsuccessful response into empty successful data', async () => {
    requestMocks.adminGet.mockResolvedValue({ success: false, data: null, message: '无权查看' });
    await expect(getFactoryAccess('F006')).rejects.toThrow('无权查看');
  });
});
