// 工艺保密 (process secret V1, PR #1096) — 工厂端管理客户端。
//
// Backend: ProcessSecretController  /api/mobile/{factoryId}/process-secrets/*
// 谁能调由服务端决定 (ProcessSecretPolicyService):
//   - 任何调用都必须是本厂用户 (平台人员一律拒绝);
//   - 某产品第一次设保密: 只有本厂工厂总监 (factory_super_admin), 且必须指定负责人;
//   - 之后的修改 / 关闭 / 授权 / 审计: 只有该产品的负责人。
// 页面只按 currentUserIsOwner 显示按钮, 最终以服务端为准 (403 原样提示)。
//
// axios baseURL = '/api/mobile', 所以路径从 "/{factoryId}/..." 开始。

import { del, get, post, put } from './request';

/** A 档「工艺保密访问」(云端保存, 仅负责人与获授权者可看) / B 档「核心工艺不入云」。 */
export type ProcessSecretStorageMode = 'CLOUD_RESTRICTED' | 'CUSTOMER_HELD';

export interface ProcessSecretPolicy {
  productTypeId: string;
  storageMode: ProcessSecretStorageMode;
  ownerUserId: number;
  policyVersion: number;
  currentUserIsOwner: boolean;
  updatedAt?: string | null;
}

export interface ProcessSecretGrant {
  id: number;
  factoryId: string;
  productTypeId: string;
  granteeType: 'FACTORY_USER' | 'PLATFORM_ADMIN';
  granteeId: number;
  canViewFull: boolean;
  canExport: boolean;
  canEdit: boolean;
  purpose: string;
  expiresAt: string;
  grantedBy?: number | null;
  revokedAt?: string | null;
  revokedBy?: number | null;
  createdAt?: string | null;
}

export interface ProcessSecretGrantRequest {
  granteeType: 'FACTORY_USER';
  granteeId: number;
  canViewFull: boolean;
  canExport: boolean;
  canEdit: boolean;
  purpose: string;
  /** 必须带时区, 例如 2026-10-31T18:00:00+08:00。 */
  expiresAt: string;
}

export interface ProcessSecretAccessEvent {
  id: number;
  productTypeId: string;
  actorType: string;
  actorId?: number | null;
  action: string;
  decision: string;
  reason?: string | null;
  resourceRef?: string | null;
  createdAt?: string | null;
}

function base(factoryId: string): string {
  return `/${factoryId}/process-secrets`;
}

export const listProcessSecretPolicies = (factoryId: string) =>
  get<ProcessSecretPolicy[]>(`${base(factoryId)}/policies`);

export const upsertProcessSecretPolicy = (
  factoryId: string,
  productTypeId: string,
  body: { storageMode: ProcessSecretStorageMode; ownerUserId: number; expectedPolicyVersion?: number | null },
) => put<ProcessSecretPolicy>(`${base(factoryId)}/policies/${encodeURIComponent(productTypeId)}`, body);

export const disableProcessSecretPolicy = (factoryId: string, productTypeId: string, expectedPolicyVersion: number) =>
  del<void>(`${base(factoryId)}/policies/${encodeURIComponent(productTypeId)}`, {
    params: { expectedPolicyVersion },
  });

export const listProcessSecretGrants = (factoryId: string, productTypeId: string) =>
  get<ProcessSecretGrant[]>(`${base(factoryId)}/policies/${encodeURIComponent(productTypeId)}/grants`);

export const createProcessSecretGrant = (factoryId: string, productTypeId: string, body: ProcessSecretGrantRequest) =>
  post<ProcessSecretGrant>(`${base(factoryId)}/policies/${encodeURIComponent(productTypeId)}/grants`, body);

export const revokeProcessSecretGrant = (factoryId: string, grantId: number) =>
  post<ProcessSecretGrant>(`${base(factoryId)}/grants/${grantId}/revoke`);

export const listProcessSecretAuditEvents = (factoryId: string, productTypeId: string, limit = 100) =>
  get<ProcessSecretAccessEvent[]>(
    `${base(factoryId)}/policies/${encodeURIComponent(productTypeId)}/audit-events`, { params: { limit } });

/** 产品下拉用: 本厂启用中的产品。 */
export const listActiveProductTypes = (factoryId: string) =>
  get<{ id: string; name: string; code?: string }[]>(`/${factoryId}/product-types/active`);

export interface DirectoryUser {
  id: number;
  fullName?: string | null;
  username?: string | null;
  department?: string | null;
  roleCode?: string | null;
}

/** 选负责人 / 被授权人: 只读员工名录 (只有 id / 姓名 / 部门 / 角色)。 */
export const listUserDirectory = (factoryId: string) =>
  get<DirectoryUser[]>(`/${factoryId}/users/directory`);
