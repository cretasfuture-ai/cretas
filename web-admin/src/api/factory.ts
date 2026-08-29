/**
 * Factory management API client
 * Feature configs, user creation, etc.
 */
import { get, post, put } from './request';
import type { FeatureConfig, CreateUserDTO } from '@/types/factory';

// === Feature Config ===

export function getFeatureConfigs(factoryId: string) {
  return get<FeatureConfig[]>(`/${factoryId}/feature-config`);
}

export function getModuleConfig(factoryId: string, moduleId: string) {
  return get<FeatureConfig>(`/${factoryId}/feature-config/${moduleId}`);
}

export function toggleModule(factoryId: string, moduleId: string, enabled: boolean) {
  return put<FeatureConfig>(`/${factoryId}/feature-config/${moduleId}/toggle`, { enabled });
}

export function updateModuleConfig(factoryId: string, moduleId: string, config: Record<string, unknown>) {
  return put<FeatureConfig>(`/${factoryId}/feature-config/${moduleId}`, config);
}

export function batchUpdateConfigs(factoryId: string, configs: Partial<FeatureConfig>[]) {
  return post<FeatureConfig[]>(`/${factoryId}/feature-config/batch`, configs);
}

export function initDefaultConfigs(factoryId: string) {
  return post<FeatureConfig[]>(`/${factoryId}/feature-config/init-defaults`);
}

// === User Management ===

export function createUser(factoryId: string, userData: CreateUserDTO) {
  return post(`/${factoryId}/users`, userData);
}

export function activateUser(factoryId: string, userId: number) {
  return post(`/${factoryId}/users/${userId}/activate`);
}

export function deactivateUser(factoryId: string, userId: number) {
  return post(`/${factoryId}/users/${userId}/deactivate`);
}

/**
 * 获取"操作员"角色用户列表，供小组长下拉选择使用。
 * 端点: GET /{factoryId}/users/directory?roleCode=operator
 * (2026-08-22 从 /users/role/operator 改过来 —— 那个要 hr 权限)
 * 响应: { success, data: UserDTO[] }  — 每项含 id (Long), username, fullName/realName
 */
export function getOperatorUsers(factoryId: string) {
  // 🔴 2026-08-22: 改调只读名录 —— /users/role/{code} 同样要 **hr** 权限,
  //   而这里只是要列出操作员填下拉。⚠️ 端点注释(上面那行)已同步更新。
  return get<Array<{
    id: number;
    username: string;
    realName?: string;
    fullName?: string;
    roleCode?: string;
    isActive?: boolean;
  }>>(
    `/${factoryId}/users/directory`, { params: { roleCode: 'operator' } });
}
