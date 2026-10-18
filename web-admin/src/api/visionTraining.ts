import { get, post } from './request';
import { ApiError, type ApiResponse } from '@/types/api';

export type VisionStage = 'preview' | 'collect' | 'dataset' | 'train' | 'evaluate' | 'independent_acceptance' | 'enable';
export type VisionReceiptStatus = 'pending' | 'succeeded' | 'failed';

export interface VisionTaskSpec {
  factory_id: string;
  workstation_id: string;
  task_family: string;
  product_scope: string[];
  packaging_scope: string[];
  data_version: string;
  recipe_version: string;
  human_standard_version: string;
  independent_acceptance_version: string;
  recipe_id?: string | null;
  ocr_rules_version?: string | null;
}

export interface VisionTemplate {
  template_id: string;
  version: string;
  task_family: string;
  title: string;
  scope: 'generic' | 'specialized';
  required_fields: string[];
  stages: VisionStage[];
  adapter_id?: string | null;
  ocr_rules_required: boolean;
  human_standard: string;
  independent_acceptance: string;
}

export interface VisionPreview {
  preview_id: string;
  preview_hash: string;
  sop_version: string;
  template: VisionTemplate;
  spec: VisionTaskSpec;
  stage_plan: VisionStage[];
  missing_requirements: string[];
  warnings: string[];
  generated_at: string;
}

export interface VisionStageReceipt {
  receipt_id: string;
  stage: VisionStage;
  status: VisionReceiptStatus;
  project_id: string;
  factory_id: string;
  task_family: string;
  training_executed: boolean;
  production_writes: number;
  message: string;
  created_at: string;
}

export interface VisionDraft {
  project_id: string;
  sop_version: string;
  template: VisionTemplate;
  spec: VisionTaskSpec;
  status: 'draft' | 'ready' | 'training' | 'evaluated' | 'accepted' | 'enabled' | 'blocked';
  preview_hash: string;
  created_at: string;
  updated_at: string;
  receipts: VisionStageReceipt[];
}

export interface VisionPlan {
  project_id: string;
  status: VisionDraft['status'];
  items: { stage: VisionStage; status: VisionReceiptStatus; reason: string; receipt_id?: string | null }[];
  training_success: boolean;
  accepted: boolean;
  enabled: boolean;
  blockers: string[];
}

export interface VisionTemplateCatalog {
  sopVersion: string;
  templates: VisionTemplate[];
  taskSpecSchema: Record<string, unknown>;
  recipes: VisionRecipe[];
}

export interface VisionRecipe {
  recipe_id: string;
  factory_id: string;
  task_family: string;
  recipe_version: string;
  available: boolean;
  reason: string;
  data_version?: string | null;
  human_standard_version?: string | null;
  independent_acceptance_version?: string | null;
  ocr_rules_version?: string | null;
  stage_input_hashes?: Partial<Record<VisionStage, string>>;
}
export interface VisionJob {
  job_id: string;
  identity: { factory_id: string; project_id: string; recipe_id?: string | null };
  stage: VisionStage;
  status: 'queued' | 'running' | 'succeeded' | 'failed' | 'blocked';
  blocked_reason?: string | null;
  updated_at: string;
}

const absolute = { baseURL: '' } as const;
function prefix(factoryId: string) {
  if (!factoryId.trim()) throw new Error('请先选择工厂');
  return `/api/mobile/${encodeURIComponent(factoryId)}/vision-training`;
}
function required<T>(response: ApiResponse<T>): T {
  if (!response.success || response.data == null) throw new ApiError(response.message || '视觉训练服务未返回有效结果', response.code);
  return response.data;
}
export async function getVisionTemplates(factoryId: string): Promise<VisionTemplateCatalog> {
  return required(await get<VisionTemplateCatalog>(`${prefix(factoryId)}/templates`, absolute));
}
export async function previewVisionTraining(factoryId: string, spec: VisionTaskSpec, templateId: string): Promise<VisionPreview> {
  return required(await post<VisionPreview>(`${prefix(factoryId)}/preview`, { spec, template_id: templateId }, absolute));
}
export async function saveVisionDraft(factoryId: string, preview: VisionPreview): Promise<VisionDraft> {
  return required(await post<VisionDraft>(`${prefix(factoryId)}/drafts`, { preview }, absolute));
}
export async function getVisionDraft(factoryId: string, projectId: string): Promise<VisionDraft> {
  return required(await get<VisionDraft>(`${prefix(factoryId)}/drafts/${encodeURIComponent(projectId)}`, absolute));
}
export async function listVisionDrafts(factoryId: string): Promise<VisionDraft[]> {
  return required(await get<VisionDraft[]>(`${prefix(factoryId)}/drafts`, absolute));
}
export async function getVisionPlan(factoryId: string, projectId: string): Promise<VisionPlan> {
  return required(await get<VisionPlan>(`${prefix(factoryId)}/drafts/${encodeURIComponent(projectId)}/plan`, absolute));
}
export async function listVisionJobs(factoryId: string, projectId: string): Promise<VisionJob[]> {
  return required(await get<VisionJob[]>(`${prefix(factoryId)}/drafts/${encodeURIComponent(projectId)}/jobs`, absolute));
}
export async function enqueueVisionStage(factoryId: string, projectId: string, stage: VisionStage, recipeId: string): Promise<VisionJob> {
  return required(await post<VisionJob>(`${prefix(factoryId)}/drafts/${encodeURIComponent(projectId)}/jobs`, { stage, recipe_id: recipeId }, absolute));
}
