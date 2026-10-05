/**
 * 一线角色 AI 写操作开放 API
 *
 * 业务背景 (owner 2026-10-06 裁定): 一线角色 (操作员 / 出成率录入员 / 仓库员 / 质检员)
 * 在 App AI 助手里只能执行「已对其开放」的写操作。后端代码里固定一组默认开放
 * (日常工作, 这里关不掉), 其余由工厂管理员在 web-admin 逐个开放。
 * 查询类操作不受这里影响。
 *
 * 后端契约:
 *   GET    /api/mobile/{factoryId}/ai-frontline-grants
 *   PUT    /api/mobile/{factoryId}/ai-frontline-grants/{roleCode}/{intentCode}   开放
 *   DELETE /api/mobile/{factoryId}/ai-frontline-grants/{roleCode}/{intentCode}   关闭
 * PUT / DELETE 仅 factory_super_admin / permission_admin 可调, 其余 403。
 * 关闭「默认开放」的角色 → success:false / HTTP 400 (前端不提供这个操作)。
 */
import { del, get, put } from './request';

/** 后端固定的四个一线角色编码。未知编码按 string 透传, 不在前端写死过滤。 */
export type FrontlineRoleCode =
  | 'operator'
  | 'yield_operator'
  | 'warehouse_worker'
  | 'quality_inspector'
  | (string & {});

export type FrontlineSensitivityLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface FrontlineRole {
  code: FrontlineRoleCode;
  name: string;
}

/** 一条 AI 写操作 (意图) 及其对四个一线角色的开放状态。 */
export interface FrontlineGrantIntent {
  intentCode: string;
  intentName: string;
  category: string;
  sensitivityLevel: FrontlineSensitivityLevel;
  description: string;
  /** 后端代码里默认开放的角色, 不能在这里关闭。 */
  defaultOpenRoles: string[];
  /** 本工厂额外开放的角色。 */
  grantedRoles: string[];
}

export interface FrontlineGrantsOverview {
  roles: FrontlineRole[];
  intents: FrontlineGrantIntent[];
}

function grantPath(factoryId: string, roleCode: string, intentCode: string): string {
  return `/${factoryId}/ai-frontline-grants/${encodeURIComponent(roleCode)}/${encodeURIComponent(intentCode)}`;
}

/** 读取开放情况 (仅 AI 写操作, 顺序由后端决定)。 */
export function getFrontlineGrants(factoryId: string) {
  return get<FrontlineGrantsOverview>(`/${factoryId}/ai-frontline-grants`);
}

/**
 * 对某角色开放某操作。返回更新后的那一条。
 * `_silent`: 失败提示由页面自己给 (需要同时回退开关), 避免拦截器再弹一条重复的。
 */
export function grantFrontlineIntent(factoryId: string, roleCode: string, intentCode: string) {
  return put<FrontlineGrantIntent>(grantPath(factoryId, roleCode, intentCode), undefined, { _silent: true });
}

/** 对某角色关闭某操作。返回更新后的那一条。 */
export function revokeFrontlineIntent(factoryId: string, roleCode: string, intentCode: string) {
  return del<FrontlineGrantIntent>(grantPath(factoryId, roleCode, intentCode), { _silent: true });
}
