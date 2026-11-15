/** Factory feature configuration types */

export interface FeatureConfig {
  id: number;
  factoryId: string;
  moduleId: string;
  moduleName: string;
  enabled: boolean;
  config: ModuleConfigDetail;
  conversationSummary?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ModuleConfigDetail {
  analysisDimensions?: string[];
  disabledScreens?: string[];
  disabledReports?: string[];
  quickActions?: string[];
  benchmarks?: Record<string, number>;
  priority?: number;
  [key: string]: unknown;
}

export interface CreateUserDTO {
  username: string;
  password: string;
  /**
   * ⚠️ 2026-09-01 由 `email: string` 改为可选 —— 这份类型此前比后端严。
   *
   * 后端 `CreateUserRequest.email` 只有 `@Email` + `@Size`, **没有 `@NotBlank`**;
   * 而且 `UserServiceImpl.createUser` 在它为空时会自动填
   * `<username>@cretas.com`。也就是说后端从来不要求调用方提供它 ——
   * 是前端(建号表单的 `required: true` + 这里的必填类型)单方面把它变成了必填项。
   * owner 2026-09-01 拍板: 建号表单直接隐藏邮箱, 必填只留
   * 用户名 / 密码 / 姓名 / 手机号 / 角色。
   */
  email?: string;
  fullName?: string;
  phone?: string;
  roleCode: string;
  position?: string;
}
