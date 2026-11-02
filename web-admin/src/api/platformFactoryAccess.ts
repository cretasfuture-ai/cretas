import { adminGet, adminPut } from './request';
import type { ApiResponse } from '@/types/api';

export type FactoryPermissionLevel = null | '-' | 'r' | 'w' | 'rw';

export interface FactoryAccessModule {
  code: string;
  name: string;
  category: string;
  enabled: boolean;
  renderingMode: string;
}

export interface FactoryPermissionModule {
  code: string;
  name: string;
}

export interface FactoryAccessRole {
  code: string;
  name: string;
  protectedRole: boolean;
  globalLevels: Record<string, FactoryPermissionLevel>;
  overrides: Record<string, FactoryPermissionLevel | undefined>;
  effectiveLevels: Record<string, FactoryPermissionLevel>;
}

export interface FactoryAccessState {
  factoryId: string;
  factoryName: string;
  canManage: boolean;
  configurationVersion: number | null;
  pendingConfiguration: boolean;
  modules: FactoryAccessModule[];
  permissionModules: FactoryPermissionModule[];
  roles: FactoryAccessRole[];
}

export interface FactoryAccessUser {
  id: number | string;
  username: string;
  displayName: string;
  roleCode: string;
  active: boolean;
  protectedRole: boolean;
}

export interface FactoryAccessUserPage {
  content: FactoryAccessUser[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface FactoryAccessUserPermissions {
  user: FactoryAccessUser;
  modules: Array<{
    code: string;
    name: string;
    roleLevel: FactoryPermissionLevel;
    overrideLevel: FactoryPermissionLevel;
    effectiveLevel: FactoryPermissionLevel;
  }>;
}

export interface FactoryAccessUsersQuery {
  keyword?: string;
  page?: number;
  size?: number;
}

export interface FactoryAccessLevelUpdate {
  level: FactoryPermissionLevel;
}

const ABS = { baseURL: '' } as const;

function assertSuccess<T>(response: ApiResponse<T>, fallback: string): ApiResponse<T> {
  if (!response.success || !response.data) {
    throw new Error(response.message || fallback);
  }
  return response;
}

function assertFactory<T extends { factoryId: string }>(
  response: ApiResponse<T>,
  factoryId: string,
): ApiResponse<T> {
  const checked = assertSuccess(response, '工厂授权配置加载失败');
  if (checked.data.factoryId !== factoryId) {
    throw new Error('工厂授权响应范围不匹配，已停止应用');
  }
  return checked;
}

function assertUser<T extends { user: { id: number | string } }>(
  response: ApiResponse<T>,
  userId: number | string,
): ApiResponse<T> {
  const checked = assertSuccess(response, '员工授权加载失败');
  if (String(checked.data.user.id) !== String(userId)) {
    throw new Error('员工授权响应范围不匹配，已停止应用');
  }
  return checked;
}

export async function getFactoryAccess(factoryId: string): Promise<ApiResponse<FactoryAccessState>> {
  const response = await adminGet<FactoryAccessState>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/access`,
    ABS,
  );
  return assertFactory(response, factoryId);
}

export async function listFactoryAccessUsers(
  factoryId: string,
  query: FactoryAccessUsersQuery = {},
): Promise<ApiResponse<FactoryAccessUserPage>> {
  const response = await adminGet<FactoryAccessUserPage>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/access/users`,
    { ...ABS, params: query },
  );
  return assertSuccess(response, '工厂员工目录加载失败');
}

export async function getFactoryAccessUserPermissions(
  factoryId: string,
  userId: number | string,
): Promise<ApiResponse<FactoryAccessUserPermissions>> {
  const response = await adminGet<FactoryAccessUserPermissions>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/access/users/${encodeURIComponent(String(userId))}/permissions`,
    ABS,
  );
  return assertUser(response, userId);
}

export async function updateFactoryAccessModule(
  factoryId: string,
  moduleCode: string,
  enabled: boolean,
): Promise<ApiResponse<FactoryAccessState>> {
  const response = await adminPut<FactoryAccessState>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/access/modules/${encodeURIComponent(moduleCode)}`,
    { enabled },
  );
  return assertFactory(response, factoryId);
}

export async function updateFactoryAccessRolePermission(
  factoryId: string,
  roleCode: string,
  moduleCode: string,
  level: FactoryPermissionLevel,
): Promise<ApiResponse<FactoryAccessState>> {
  const response = await adminPut<FactoryAccessState>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/access/roles/${encodeURIComponent(roleCode)}/${encodeURIComponent(moduleCode)}`,
    { level },
  );
  return assertFactory(response, factoryId);
}

export async function updateFactoryAccessUserPermission(
  factoryId: string,
  userId: number | string,
  moduleCode: string,
  level: FactoryPermissionLevel,
): Promise<ApiResponse<FactoryAccessUserPermissions>> {
  const response = await adminPut<FactoryAccessUserPermissions>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/access/users/${encodeURIComponent(String(userId))}/permissions/${encodeURIComponent(moduleCode)}`,
    { level },
  );
  return assertUser(response, userId);
}
