/**
 * 外部软件接口授权与只读快照（统一原型迁移 P10，MIGRATION-PLAN 4.2 / 4.3）。
 *
 * - 工厂端只读：`/api/mobile/{factoryId}/feature-config` 列表里 moduleId = "integrations" 的那一行带
 *   `integrations: {blacklake: {authorized: true, label, lastCollectedAt, stale}}`（只列平台已授权且 ACTIVE 的系统）。
 *   一切外部对照列 / 外部数据页只认 {@link isExternalAuthorized}：`authorized === true` 才算，缺了 / 读不到 = 没授权，
 *   ⛔ 不按厂名写死、不用 disabledMenuPaths。
 * - 工厂端读：`GET /{factoryId}/external-integrations`、`GET .../{system}/report-comparison`（没授权后端 404）。
 * - 平台写：`PUT /api/platform/factories/{factoryId}/integrations/{system}`、`POST .../{system}/push-token`。
 */
import { adminGet, adminPost, adminPut, get } from './request';
import type { ApiResponse } from '@/types/api';

export const INTEGRATIONS_MODULE_ID = 'integrations';

export type ExternalSystemCode = 'blacklake';

export interface ExternalAuthorization {
  authorized: boolean;
  label?: string;
  /** 北京时间 YYYY-MM-DD HH:mm；null = 有授权但还没收到外部数据 */
  lastCollectedAt?: string | null;
  stale?: boolean;
}

export type ExternalAuthorizations = Partial<Record<ExternalSystemCode, ExternalAuthorization>>;

/** 依赖外部授权的页面路径 → 需要哪个系统的授权（侧栏隐藏 + 路由守卫共用这一份）。 */
export const EXTERNAL_GATED_PATHS: Readonly<Record<string, ExternalSystemCode>> = {
  '/external-software': 'blacklake',
};

/** 从 feature-config 列表里取出只读的外部授权；没有那一行 / 形状不对 → 空（= 全都没授权）。 */
export function integrationsFromFeatureConfig(rows: unknown): ExternalAuthorizations {
  if (!Array.isArray(rows)) return {};
  const row = rows.find((item) => item && typeof item === 'object'
    && (item as Record<string, unknown>).moduleId === INTEGRATIONS_MODULE_ID) as Record<string, unknown> | undefined;
  const raw = row?.integrations ?? (row?.config as Record<string, unknown> | undefined)?.integrations;
  if (!raw || typeof raw !== 'object') return {};
  const out: ExternalAuthorizations = {};
  for (const [code, value] of Object.entries(raw as Record<string, unknown>)) {
    if (code !== 'blacklake' || !value || typeof value !== 'object') continue;
    const v = value as Record<string, unknown>;
    out[code] = {
      authorized: v.authorized === true,
      label: typeof v.label === 'string' ? v.label : undefined,
      lastCollectedAt: typeof v.lastCollectedAt === 'string' ? v.lastCollectedAt : null,
      stale: v.stale === true,
    };
  }
  return out;
}

/** 只有明确 `authorized === true` 才算有授权。 */
export function isExternalAuthorized(auth: ExternalAuthorizations | null | undefined, system: ExternalSystemCode): boolean {
  return auth?.[system]?.authorized === true;
}

/** 因没有外部授权要隐藏的工厂端菜单路径。 */
export function externalHiddenMenuPaths(auth: ExternalAuthorizations): string[] {
  return Object.entries(EXTERNAL_GATED_PATHS)
    .filter(([, system]) => !isExternalAuthorized(auth, system))
    .map(([path]) => path);
}

/** 当前工厂的外部授权（路由守卫 / 页面用）。读不到 → 空（按没授权处理）。 */
export async function getFactoryExternalAuthorizations(factoryId: string): Promise<ExternalAuthorizations> {
  if (!factoryId) return {};
  try {
    const response = await get<unknown>(`/${encodeURIComponent(factoryId)}/feature-config`);
    return response?.success ? integrationsFromFeatureConfig(response.data) : {};
  } catch {
    return {};
  }
}

// ------------------------------------------------------------------ 工厂端只读

export interface ExternalDatasetView {
  dataset: string;
  label: string;
  collectedAt: string;
  rowCount: number;
}

export interface ExternalIntegrationView {
  system: ExternalSystemCode;
  label: string;
  status: string;
  authorizedAt: string | null;
  lastCollectedAt: string | null;
  stale: boolean;
  connected: boolean;
  reason: string | null;
  datasets: ExternalDatasetView[];
}

export type MatchState = 'MATCH' | 'DIFF' | 'UNIT_MISMATCH' | 'EXTERNAL_ONLY' | 'CRETAS_ONLY';

export interface ComparisonRow {
  reportDate: string;
  productName: string;
  processName: string;
  externalGoodQty: number | null;
  externalUnit: string | null;
  externalReportCount: number | null;
  cretasGoodQty: number | null;
  cretasUnit: string | null;
  cretasReportCount: number | null;
  difference: number | null;
  state: MatchState;
}

export interface ReportComparison {
  system: ExternalSystemCode;
  label: string;
  available: boolean;
  reason: string | null;
  asOf: string | null;
  stale: boolean;
  from: string | null;
  to: string | null;
  cretasTruncated: boolean;
  rows: ComparisonRow[];
}

export function listFactoryIntegrations(factoryId: string) {
  return get<ExternalIntegrationView[]>(`/${encodeURIComponent(factoryId)}/external-integrations`);
}

export function getReportComparison(factoryId: string, system: ExternalSystemCode) {
  return get<ReportComparison>(`/${encodeURIComponent(factoryId)}/external-integrations/${system}/report-comparison`);
}

// ------------------------------------------------------------------ 平台

export interface PlatformIntegrationRow {
  system: ExternalSystemCode;
  label: string;
  /** UNSET = 还没有授权行 */
  status: 'UNSET' | 'ACTIVE' | 'SUSPENDED';
  sourceOrgId: string | null;
  authorizedAt: string | null;
  authorizedBy: number | null;
  note: string | null;
  hasPushToken: boolean;
  pushTokenIssuedAt: string | null;
  lastCollectedAt: string | null;
  stale: boolean;
}

export interface PlatformIntegrationUpdate {
  status: 'ACTIVE' | 'SUSPENDED';
  sourceOrgId: string | null;
  note: string | null;
}

export interface IssuedPushToken {
  system: ExternalSystemCode;
  /** 明文只在这次响应里出现一次 */
  token: string;
  issuedAt: string;
}

const ABS = { baseURL: '' } as const;

function base(factoryId: string): string {
  return `/api/platform/factories/${encodeURIComponent(factoryId)}/integrations`;
}

/**
 * 暂停授权的确认文案。暂停 = 作废推送凭证（后端清空令牌哈希，审查中1）：
 * 重新授权后旧凭证不会复活，必须重新签发并在本机重新 set-token。
 */
export function suspendConfirmMessage(factoryId: string, label: string): string {
  return `暂停后，${factoryId} 的「${label}」对照列与外部软件页立即不再显示，推送会被拒收，当前推送凭证同时作废。重新授权需重新签发凭证，并在本机重新保存（set-token）。`;
}

export function getPlatformIntegrations(factoryId: string): Promise<ApiResponse<PlatformIntegrationRow[]>> {
  return adminGet<PlatformIntegrationRow[]>(base(factoryId), ABS);
}

export function updatePlatformIntegration(
  factoryId: string,
  system: ExternalSystemCode,
  body: PlatformIntegrationUpdate,
): Promise<ApiResponse<PlatformIntegrationRow[]>> {
  return adminPut<PlatformIntegrationRow[]>(`${base(factoryId)}/${system}`, body);
}

export function issuePlatformPushToken(factoryId: string, system: ExternalSystemCode): Promise<ApiResponse<IssuedPushToken>> {
  return adminPost<IssuedPushToken>(`${base(factoryId)}/${system}/push-token`, {}, ABS);
}
