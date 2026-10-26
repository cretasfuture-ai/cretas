// 按工厂备份套餐 (手册 docs/security/tenant-backup-plans-runbook.md)。
//
// 平台端: PlatformTenantBackupController  /api/platform/tenant-backups  @RequireRole super_admin
//   - 只有平台能改套餐, 改动必须填原因 (合同编号等), 记进改动记录。
//   - 手动执行要求发起人在整厂导出白名单里, 后台执行, 结果看执行记录。
// 工厂端: FactoryDataBackupController  /api/mobile/{factoryId}/data-backups
//   @RequireRole factory_super_admin / permission_admin; 只读。
//   只有「客户自持钥匙」的成功备份能下载 (平台钥匙的件工厂解不开)。

import request, { get, type ApiResponse } from './request';

export type BackupFrequency = 'DAILY' | 'WEEKLY' | 'MONTHLY';
export type BackupKeyHolder = 'PLATFORM' | 'CUSTOMER';
export type BackupRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
export type BackupHealth = 'NOT_ENABLED' | 'WAITING_FIRST_RUN' | 'OK' | 'OVERDUE' | 'LAST_FAILED';

export const RETENTION_OPTIONS = [7, 30, 90, 365] as const;

export interface BackupPlan {
  factoryId: string;
  enabled: boolean;
  frequency: BackupFrequency;
  retentionDays: number;
  includeAttachments: boolean;
  keyHolder: BackupKeyHolder;
  customerRecipient?: string | null;
  source: 'AUTO_NEW_FACTORY' | 'PLATFORM';
  updatedByAdminId?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface BackupRun {
  id: number;
  factoryId: string;
  triggerSource: string;
  actorType?: string | null;
  actorId?: number | null;
  status: BackupRunStatus;
  startedAt: string;
  finishedAt?: string | null;
  exportRequestId?: number | null;
  keyHolder?: BackupKeyHolder | null;
  attachmentsMode?: string | null;
  retentionDays?: number | null;
  objectKey?: string | null;
  packageBytes?: number | null;
  encryptedBytes?: number | null;
  encryptedSha256?: string | null;
  errorMessage?: string | null;
}

export interface BackupPlanChange {
  id: number;
  factoryId: string;
  actorType: string;
  actorId?: number | null;
  actorRole?: string | null;
  beforePlan?: string | null;
  afterPlan: string;
  reason?: string | null;
  changedAt: string;
}

export interface BackupFactoryRow {
  factoryId: string;
  factoryName?: string | null;
  plan?: BackupPlan | null;
  lastRun?: BackupRun | null;
  lastSuccess?: BackupRun | null;
  health: BackupHealth;
}

export interface BackupStoredObject {
  key: string;
  size: number;
  takenAt?: string | null;
}

export interface BackupFactoryDetail {
  factoryId: string;
  factoryName?: string | null;
  plan?: BackupPlan | null;
  runs: BackupRun[];
  changes: BackupPlanChange[];
  stored: BackupStoredObject[];
  storedError?: string | null;
}

export interface BackupPlanRequest {
  enabled: boolean;
  frequency: BackupFrequency;
  retentionDays: number;
  includeAttachments: boolean;
  keyHolder: BackupKeyHolder;
  customerRecipient?: string | null;
  reason: string;
}

export interface FactoryBackupView {
  plan?: BackupPlan | null;
  runs: BackupRun[];
}

const PLATFORM = '/api/platform/tenant-backups';

export const listTenantBackups = (): Promise<ApiResponse<BackupFactoryRow[]>> =>
  request.get(PLATFORM, { baseURL: '' });

export const getTenantBackupDetail = (factoryId: string): Promise<ApiResponse<BackupFactoryDetail>> =>
  request.get(`${PLATFORM}/${encodeURIComponent(factoryId)}`, { baseURL: '' });

export const updateTenantBackupPlan = (
  factoryId: string,
  body: BackupPlanRequest,
): Promise<ApiResponse<BackupPlan>> =>
  request.put(`${PLATFORM}/${encodeURIComponent(factoryId)}/plan`, body, { baseURL: '' });

export const runTenantBackupNow = (factoryId: string): Promise<ApiResponse<string>> =>
  request.post(`${PLATFORM}/${encodeURIComponent(factoryId)}/run`, undefined, { baseURL: '' });

export const getFactoryBackups = (factoryId: string) =>
  get<FactoryBackupView>(`/${factoryId}/data-backups`);

/** 下载客户自持钥匙的备份件 (加密件, 客户用自己的私钥解)。 */
export const downloadFactoryBackup = (factoryId: string, runId: number): Promise<Blob> =>
  request.get(`/${factoryId}/data-backups/${runId}/download`, { responseType: 'blob' });

export const FREQUENCY_LABELS: Record<BackupFrequency, string> = {
  DAILY: '每天',
  WEEKLY: '每周',
  MONTHLY: '每月',
};

export function retentionLabel(days?: number | null): string {
  if (!days) return '—';
  return days === 365 ? '1 年' : `${days} 天`;
}

export const RUN_STATUS_LABELS: Record<BackupRunStatus, string> = {
  RUNNING: '进行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  SKIPPED: '未执行',
};

export function triggerLabel(trigger?: string | null): string {
  if (trigger === 'BACKUP_SCHEDULER') return '定时';
  if (trigger === 'BACKUP_MANUAL') return '平台手动';
  return trigger || '—';
}

export function formatBytes(n?: number | null): string {
  if (n == null) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export function formatTime(s?: string | null): string {
  return s ? s.replace('T', ' ').substring(0, 19) : '—';
}
