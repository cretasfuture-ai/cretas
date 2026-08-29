import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import { User } from '../../types/auth';

/**
 * 用户管理API客户端
 * 总计14个API - 路径：/api/mobile/{factoryId}/users/*
 */

// ========== 类型定义 ==========

// 导出User类型供其他组件使用
export type { User };

export interface UserDTO {
  id: number;
  username: string;
  email?: string;
  realName: string;
  fullName?: string;  // 后端可能返回 fullName 替代 realName
  phone?: string;
  role?: string;       // 保留兼容
  roleCode: string;    // 后端实际返回的角色枚举值 (OPERATOR, DEPARTMENT_ADMIN, FACTORY_SUPER_ADMIN)
  roleDisplayName?: string;  // 角色显示名称
  department?: string;
  departmentDisplayName?: string;  // 部门显示名称
  position?: string;
  isActive: boolean;
  hireDate?: string;
  /** 工号 (后端 UserDTO.employeeCode, "001-999") */
  employeeCode?: string;
  /** 用工类型 (后端 UserDTO.hireType / hireTypeDisplayName) */
  hireType?: string;
  hireTypeDisplayName?: string;
  /** 是否临时工 (后端 UserDTO.isTemporaryWorker) */
  isTemporaryWorker?: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  realName: string;
  email?: string;
  phone?: string;
  role: string;
  department?: string;
  position?: string;
}

export interface UpdateUserRequest {
  realName?: string;
  email?: string;
  phone?: string;
  department?: string;
  position?: string;
}

export interface UpdateUserRoleRequest {
  roleCode: string;
}

export interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ========== API客户端类 ==========

class UserApiClient {
  private getFactoryPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}`;
  }

  /**
   * 1. 获取用户列表（分页）
   * GET /api/{factoryId}/users
   */
  async getUsers(params?: {
    factoryId?: string;
    page?: number;
    size?: number;
    sortBy?: string;
    sortDirection?: 'ASC' | 'DESC';
    keyword?: string;
  }): Promise<PageResponse<UserDTO>> {
    const { factoryId, ...queryParams } = params || {};
    // apiClient返回包装格式: {code, data, message, success}
    const response = await apiClient.get<{
      code: number;
      data: PageResponse<UserDTO>;
      message: string;
      success: boolean;
    }>(
      `${this.getFactoryPath(factoryId)}/users`,
      { params: queryParams }
    );
    // 提取实际数据
    return response.data || { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 };
  }

  /**
   * 2. 创建用户
   * POST /api/{factoryId}/users
   */
  async createUser(request: CreateUserRequest, factoryId?: string): Promise<UserDTO> {
    const response = await apiClient.post<{ code: number; data: UserDTO; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users`,
      request
    );
    return response.data;
  }

  /**
   * 3. 获取用户详情
   * GET /api/{factoryId}/users/{userId}
   */
  async getUserById(userId: number, factoryId?: string): Promise<UserDTO> {
    const response = await apiClient.get<{ code: number; data: UserDTO; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/${userId}`
    );
    return response.data;
  }

  /**
   * 4. 更新用户信息
   * PUT /api/{factoryId}/users/{userId}
   */
  /**
   * 改**自己**的资料 —— 只有姓名与邮箱。
   *
   * ⛔ 自助编辑**不要**用 {@link updateUser}: 那个接口是人事管理的
   * `PUT /users/{userId}`，由 `@RequireModule("hr_employee")` +
   * `hr:read_write` 守着。2026-08-29 逐屏走查实测，仓储主管点「我的 → 编辑 → 保存」
   * 拿到 **403**，提示他去开通整个人事模块的写权限 —— 那是错的处方。
   */
  async updateMyProfile(
    request: { fullName?: string; email?: string },
    factoryId?: string
  ): Promise<UserDTO> {
    const response = await apiClient.put<{ code: number; data: UserDTO; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/me/profile`,
      request
    );
    return response.data;
  }

  async updateUser(
    userId: number,
    request: UpdateUserRequest,
    factoryId?: string
  ): Promise<UserDTO> {
    const response = await apiClient.put<{ code: number; data: UserDTO; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/${userId}`,
      request
    );
    return response.data;
  }

  /**
   * 5. 删除用户
   * DELETE /api/{factoryId}/users/{userId}
   */
  async deleteUser(userId: number, factoryId?: string): Promise<void> {
    await apiClient.delete(`${this.getFactoryPath(factoryId)}/users/${userId}`);
  }

  /**
   * 6. 激活用户
   * POST /api/{factoryId}/users/{userId}/activate
   */
  async activateUser(userId: number, factoryId?: string): Promise<UserDTO> {
    const response = await apiClient.post<{ code: number; data: UserDTO; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/${userId}/activate`
    );
    return response.data;
  }

  /**
   * 7. 停用用户
   * POST /api/{factoryId}/users/{userId}/deactivate
   */
  async deactivateUser(userId: number, factoryId?: string): Promise<UserDTO> {
    const response = await apiClient.post<{ code: number; data: UserDTO; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/${userId}/deactivate`
    );
    return response.data;
  }

  /**
   * 8. 更新用户角色
   * PUT /api/{factoryId}/users/{userId}/role
   */
  async updateUserRole(
    userId: number,
    request: UpdateUserRoleRequest,
    factoryId?: string
  ): Promise<UserDTO> {
    const response = await apiClient.put<{ code: number; data: UserDTO; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/${userId}/role`,
      request
    );
    return response.data;
  }

  /**
   * 9. 按角色获取用户列表
   * GET /api/{factoryId}/users/role/{roleCode}
   */
  async getUsersByRole(roleCode: string, factoryId?: string): Promise<UserDTO[]> {
    const response = await apiClient.get<{ code: number; data: UserDTO[]; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/role/${roleCode}`
    );
    return response.data || [];
  }

  /**
   * 10. 搜索用户
   * GET /api/{factoryId}/users/search
   */
  async searchUsers(params: {
    keyword: string;
    factoryId?: string;
    role?: string;
    department?: string;
    isActive?: boolean;
  }): Promise<UserDTO[]> {
    const { factoryId, ...queryParams } = params;
    // 🔴 2026-08-27: 这里原来标注成 `data: UserDTO[]` 并 `return response.data || []`。
    //    而后端 `UserController:320` 返回的是 `ApiResponse<PageResponse<UserDTO>>` ——
    //    `data` 是**分页对象**不是数组。后果不是「搜不到」, 是**整屏崩**:
    //      UserManagementScreen:139  setUsers(results)      <- 存进一个非数组
    //      UserManagementScreen:336  users.filter(...)      <- TypeError
    //    而 `results.length` 在崩之前只是 undefined, 日志里看不出问题。
    //    这屏用户走得到(员工列表, 从 FAManagementScreen 进), 搜索一次就崩。
    const response = await apiClient.get<{
      code: number;
      data: PageResponse<UserDTO>;
      message: string;
      success: boolean;
    }>(
      `${this.getFactoryPath(factoryId)}/users/search`,
      { params: queryParams }
    );
    const page = response.data;
    // ⛔ 不兜底成 `[]` —— 那会把「后端契约变了 / 请求没成功」显示成「搜到 0 个用户」,
    //    是在编一个读数。搜不到的正常情况是 `content: []`, 与这里的失败完全不同。
    if (!page || !Array.isArray(page.content)) {
      throw new Error(
        '用户搜索返回的不是分页结构（期望 data.content 为数组）: '
          + JSON.stringify(page)?.slice(0, 200)
      );
    }
    return page.content;
  }

  /**
   * 11. 检查用户名是否存在
   * GET /api/{factoryId}/users/check/username
   */
  async checkUsernameExists(username: string, factoryId?: string): Promise<boolean> {
    const response = await apiClient.get<{ code: number; data: boolean; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/check/username`,
      { params: { username } }
    );
    return response.data;
  }

  /**
   * 12. 检查邮箱是否存在
   * GET /api/{factoryId}/users/check/email
   */
  async checkEmailExists(email: string, factoryId?: string): Promise<boolean> {
    const response = await apiClient.get<{ code: number; data: boolean; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/check/email`,
      { params: { email } }
    );
    return response.data;
  }

  /**
   * 13. 导出用户列表
   * GET /api/{factoryId}/users/export
   */
  async exportUsers(params?: {
    factoryId?: string;
    role?: string;
    department?: string;
    isActive?: boolean;
  }): Promise<Blob> {
    const { factoryId, ...queryParams } = params || {};
    // apiClient拦截器已统一返回data
    return await apiClient.get<Blob>(
      `${this.getFactoryPath(factoryId)}/users/export`,
      { params: queryParams, responseType: 'blob' }
    );
  }

  /**
   * 14. 批量导入用户
   * POST /api/{factoryId}/users/import
   */
  async importUsers(file: File, factoryId?: string): Promise<{
    success: number;
    failed: number;
    errors?: string[];
  }> {
    const formData = new FormData();
    formData.append('file', file);

    const response = await apiClient.post<{
      code: number;
      data: { success: number; failed: number; errors?: string[] };
      message: string;
      success: boolean;
    }>(
      `${this.getFactoryPath(factoryId)}/users/import`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      }
    );
    return response.data;
  }

  /**
   * 15. 修改密码
   * PUT /api/{factoryId}/users/{userId}/password
   *
   * 注意：
   * - 需要提供旧密码验证
   * - 新密码需要满足安全强度要求（后端验证）
   */
  async changePassword(
    userId: number,
    request: ChangePasswordRequest,
    factoryId?: string
  ): Promise<{ message: string }> {
    console.log('Changing password for user:', userId);

    const response = await apiClient.put<{ code: number; data: { message: string }; message: string; success: boolean; }>(
      `${this.getFactoryPath(factoryId)}/users/${userId}/password`,
      request
    );
    return response.data;
  }
}

export const userApiClient = new UserApiClient();
