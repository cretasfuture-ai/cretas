import { get, post } from './request';
import type { ApiResponse } from '@/types/api';

/** S6-02: Skill 运行回执与可复用 Skill（后端 SavedSkillController）。 */

export type SkillParamType = 'STRING' | 'INTEGER' | 'NUMBER' | 'BOOLEAN' | 'DATE' | 'ENUM';

export interface SkillParamSpec {
  name: string;
  type: SkillParamType;
  required: boolean;
  min?: number;
  max?: number;
  maxLength?: number;
  allowedValues?: string[];
  factoryScopedRef?: string;
}

export interface SkillParameterContract {
  params: SkillParamSpec[];
}

export interface SkillRunStep {
  tool: string;
  descriptorVersion: string;
  params: Record<string, unknown>;
  gatewayStatus: string;
  /** 为空表示这一步没有经过工具网关 */
  auditEventId: string | null;
}

export interface SkillRun {
  id: string;
  factoryId: string;
  userId: number;
  skillName: string;
  savedSkillId: string | null;
  status: 'SUCCEEDED' | 'FAILED' | 'DENIED' | 'OUTCOME_UNKNOWN';
  steps: SkillRunStep[];
  createdAt: string;
}

export interface SavedSkill {
  id: string;
  factoryId: string;
  name: string;
  displayName: string;
  sourceRunId: string;
  sourceSkillName: string;
  steps: { tool: string; params: Record<string, unknown> }[];
  parameterContract: SkillParameterContract;
  enabled: boolean;
  createdBy: number;
  createdAt: string;
}

export interface SkillRunResult {
  success: boolean;
  skillName: string;
  data: unknown;
  message?: string;
  executedTools: string[];
  runId: string | null;
}

export interface SavedSkillRunResult {
  result: SkillRunResult;
  savedSkillId: string;
  sourceRunId: string;
}

export function runRegisteredSkill(factoryId: string, skillName: string,
  params: Record<string, unknown>): Promise<ApiResponse<SkillRunResult>> {
  return post(`/${factoryId}/skills/registered/${encodeURIComponent(skillName)}/run`, { params });
}

export function listMySkillRuns(factoryId: string): Promise<ApiResponse<SkillRun[]>> {
  return get(`/${factoryId}/skills/runs`);
}

export function saveSkillFromRun(factoryId: string, body: {
  runId: string;
  name: string;
  displayName?: string;
  parameterContract?: SkillParameterContract;
}): Promise<ApiResponse<SavedSkill>> {
  return post(`/${factoryId}/skills/saved`, body);
}

export function listSavedSkills(factoryId: string): Promise<ApiResponse<SavedSkill[]>> {
  return get(`/${factoryId}/skills/saved`);
}

export function runSavedSkill(factoryId: string, savedSkillId: string,
  params: Record<string, unknown>): Promise<ApiResponse<SavedSkillRunResult>> {
  return post(`/${factoryId}/skills/saved/${savedSkillId}/run`, { params });
}

export function setSavedSkillEnabled(factoryId: string, savedSkillId: string,
  enabled: boolean): Promise<ApiResponse<SavedSkill>> {
  return post(`/${factoryId}/skills/saved/${savedSkillId}/enabled`, { enabled });
}
