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

export interface ProcessOperatorDirectoryUser {
  id: number;
  username: string;
  realName?: string;
  fullName?: string;
  roleCode?: string;
  isActive?: boolean;
}

export const PROCESS_OPERATOR_ROLE_CODES = ['operator', 'yield_operator'] as const;

/**
 * 合并当前工厂的现场操作员与报工员。同一人若因历史角色数据重复出现，只保留一项。
 */
export function mergeProcessOperatorUsers(
  groups: ProcessOperatorDirectoryUser[][],
): ProcessOperatorDirectoryUser[] {
  const allowedRoles = new Set<string>(PROCESS_OPERATOR_ROLE_CODES);
  const byId = new Map<number, ProcessOperatorDirectoryUser>();
  for (const user of groups.flat()) {
    if (user.isActive === false || !user.roleCode || !allowedRoles.has(user.roleCode)) continue;
    byId.set(user.id, user);
  }
  return [...byId.values()];
}

/**
 * 获取可执行现场工序的用户列表，供负责人指派使用。
 * 端点: GET /{factoryId}/users/directory?roleCode=operator|yield_operator
 * (2026-08-22 从 /users/role/operator 改过来 —— 那个要 hr 权限)
 * 响应: { success, data: UserDTO[] }  — 每项含 id (Long), username, fullName/realName
 */
export async function getOperatorUsers(factoryId: string) {
  // 🔴 2026-08-22: 改调只读名录 —— /users/role/{code} 同样要 **hr** 权限,
  //   而这里只是要列出操作员填下拉。⚠️ 端点注释(上面那行)已同步更新。
  const responses = await Promise.all(PROCESS_OPERATOR_ROLE_CODES.map((roleCode) =>
    get<ProcessOperatorDirectoryUser[]>(
      `/${factoryId}/users/directory`,
      { params: { roleCode } },
    ),
  ));
  const failed = responses.find((response) => !response.success || !Array.isArray(response.data));
  if (failed) {
    return { ...failed, data: [] as ProcessOperatorDirectoryUser[] };
  }
  return {
    ...responses[0],
    data: mergeProcessOperatorUsers(responses.map((response) => response.data)),
  };
}
