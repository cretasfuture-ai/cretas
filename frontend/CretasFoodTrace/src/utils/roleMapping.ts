/**
 * 角色映射工具 - 处理后端用户数据到前端格式的转换
 */

import { User } from '../types/auth';

function requirePermissions(value: unknown): string[] {
  if (!Array.isArray(value) || value.some(permission => typeof permission !== 'string')) {
    throw new Error('后端用户数据缺少有效的权限数组');
  }
  return value;
}

/**
 * 转换后端用户数据为前端格式
 */
export function transformBackendUser(backendUser: any): User {
  console.log('🔄 transformBackendUser - Input:', JSON.stringify(backendUser, null, 2));

  // 如果已经是前端格式，仅校验后端权限真值。
  if (backendUser.userType && (backendUser.platformUser || backendUser.factoryUser)) {
    console.log('🔄 Already in frontend format, validating permissions...');
    if (backendUser.factoryUser) requirePermissions(backendUser.factoryUser.permissions);
    if (backendUser.platformUser) requirePermissions(backendUser.platformUser.permissions);

    return backendUser as User;
  }

  // 转换后端格式到前端格式
  const baseUser = {
    id: backendUser.id?.toString() || '',
    username: backendUser.username || '',
    email: backendUser.email || '',
    phone: backendUser.phone || backendUser.phoneNumber,
    fullName: backendUser.fullName || backendUser.realName,
    avatar: backendUser.avatar,
    lastLoginAt: backendUser.lastLoginAt,
    createdAt: backendUser.createdAt || new Date().toISOString(),
    updatedAt: backendUser.updatedAt || new Date().toISOString(),
    isActive: backendUser.isActive !== false,
  };

  // 确定用户类型和角色
  const userType = backendUser.userType || backendUser.type || 'factory';
  const role = backendUser.role || backendUser.roleCode || 'viewer';

  console.log(`🎯 Determined userType: ${userType}, role: ${role}`);

  // 处理平台用户
  if (userType === 'platform' || role === 'platform_admin' || role === 'developer') {
    const user: User = {
      ...baseUser,
      userType: 'platform' as const,
      platformUser: {
        role: role === 'developer' ? 'platform_admin' : role,
        permissions: requirePermissions(backendUser.permissions),
      },
    };
    console.log('✅ Created platform user:', JSON.stringify(user, null, 2));
    return user;
  }

  // 处理工厂用户
  const user: User = {
    ...baseUser,
    userType: 'factory' as const,
    factoryUser: {
      role: role,
      factoryId: backendUser.factoryId || '',
      factoryType: backendUser.factoryType || 'FACTORY',
      department: backendUser.department,
      position: backendUser.position,
      permissions: requirePermissions(backendUser.permissions),
    },
  };

  console.log('✅ Created factory user:', JSON.stringify(user, null, 2));
  return user;
}

/**
 * 获取用户角色
 */
export function getUserRole(user: User): string | null {
  if (user.userType === 'platform' && user.platformUser) {
    return user.platformUser.role;
  }

  if (user.userType === 'factory' && user.factoryUser) {
    return user.factoryUser.role;
  }

  return null;
}

/**
 * 获取用户显示名称
 */
export function getUserDisplayName(user: User): string {
  // fullName 在 BaseUser 中定义，直接使用
  return user.fullName || user.username;
}

/**
 * 检查用户是否有特定角色
 */
export function hasRole(user: User, role: string): boolean {
  const userRole = getUserRole(user);
  return userRole === role;
}

/**
 * 检查用户是否是平台管理员
 */
export function isPlatformAdmin(user: User): boolean {
  return user.userType === 'platform' &&
    (hasRole(user, 'platform_super_admin') || hasRole(user, 'developer'));
}

/**
 * 检查用户是否是工厂管理员
 */
export function isFactoryAdmin(user: User): boolean {
  return user.userType === 'factory' &&
    (hasRole(user, 'factory_super_admin') || hasRole(user, 'permission_admin'));
}
