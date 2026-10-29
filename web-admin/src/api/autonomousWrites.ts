import { get, post, put } from './request';
import type { ApiResponse } from '@/types/api';

/** S6-04 定时任务写入：总开关、委托授权、定时任务及其运行记录（后端 /config/v2/*）。 */

export interface WriteSwitchView {
  factoryId: string;
  /** 没有记录时为 false：默认关闭，须本厂超级管理员明确打开 */
  writesEnabled: boolean;
  reason: string | null;
  changedBy: number | null;
  changedAt: string | null;
}

export type GrantState = 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'EXHAUSTED';

export interface AutonomousGrant {
  id: string;
  sourceType: string;
  /** 定时任务配置 id */
  sourceRef: string;
  toolName: string;
  runAsUserId: number;
  descriptorVersion: string;
  maxRuns: number;
  usedRuns: number;
  expiresAt: string;
  grantedBy: number;
  grantedAt: string;
  revokedAt: string | null;
  revokedBy: number | null;
  state: GrantState;
}

export interface SchedulerConfig {
  id: number;
  factoryId: string;
  taskCode: string;
  cronExpression: string;
  enabled: boolean;
  toolOrMethod: string;
  params: Record<string, unknown> | null;
  runAsUserId: number | null;
  lastExecutionStatus?: string | null;
}

export interface SchedulerRun {
  runId: string;
  configId: number;
  taskCode: string;
  toolName: string;
  startedAt: string;
  finishedAt: string | null;
  status: string;
  rawStatus: string;
  effectiveStatus: string;
  error: string | null;
}

export interface PageResult<T> {
  content: T[];
  totalElements: number;
}

export function getWriteSwitch(factoryId: string): Promise<ApiResponse<WriteSwitchView>> {
  return get(`/${factoryId}/config/v2/autonomous-writes/switch`);
}

export function setWriteSwitch(factoryId: string, writesEnabled: boolean,
  reason?: string): Promise<ApiResponse<WriteSwitchView>> {
  return put(`/${factoryId}/config/v2/autonomous-writes/switch`, { writesEnabled, reason: reason || undefined });
}

export function listGrants(factoryId: string): Promise<ApiResponse<AutonomousGrant[]>> {
  return get(`/${factoryId}/config/v2/autonomous-grants`);
}

export function issueGrant(factoryId: string, body: {
  sourceRef: string;
  toolName: string;
  maxRuns: number;
  expiresAt: string;
}): Promise<ApiResponse<AutonomousGrant>> {
  return post(`/${factoryId}/config/v2/autonomous-grants`, { sourceType: 'SCHEDULER', ...body });
}

export function revokeGrant(factoryId: string, grantId: string): Promise<ApiResponse<AutonomousGrant>> {
  return post(`/${factoryId}/config/v2/autonomous-grants/${grantId}/revoke`, {});
}

export function listSchedulerConfigs(factoryId: string): Promise<ApiResponse<SchedulerConfig[]>> {
  return get(`/${factoryId}/config/v2/scheduler`);
}

export function listSchedulerRuns(factoryId: string, taskCode: string,
  size = 10): Promise<ApiResponse<PageResult<SchedulerRun>>> {
  return get(`/${factoryId}/config/v2/scheduler/${encodeURIComponent(taskCode)}/runs`, { params: { page: 1, size } });
}
