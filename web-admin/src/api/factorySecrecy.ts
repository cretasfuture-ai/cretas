/**
 * 工厂级保密配置 (Steve 2026-11-03)。
 *
 * - 配置项的文字与规则来自 `@/config/secrecy-items.json` —— 由后端 Java 枚举 SecrecyItem 生成,
 *   SecrecyItemsJsonDriftContractTest 逐字节核对, ⛔ 不要手改。
 * - 工厂端只读: `/api/mobile/{factoryId}/feature-config` 列表里 moduleId = "secrecy" 的那一行带
 *   `secrecy: {CODE: bool}`。读取一律判断 `=== true` (缺了 / 读不到就当没开)。
 * - 平台写: `PUT /api/platform/factories/{factoryId}/secrecy/{itemCode}`、`POST .../secrecy/bulk`。
 */
import { adminGet, adminPost, adminPut, get } from './request';
import type { ApiResponse } from '@/types/api';
import secrecyItemsFile from '@/config/secrecy-items.json';

export type SecrecyCode =
  | 'PROCESS_SECRET'
  | 'PLATFORM_RECIPE_BLOCK'
  | 'EGRESS_REDACTION'
  | 'TRAINING_FILTER'
  | 'CUSTOMER_HELD'
  | 'AI_SECRECY_AUDIT';

export interface SecrecyItemMeta {
  code: SecrecyCode;
  letter: string;
  label: string;
  default: 'OFF' | 'FOLLOW_PROCESS_SECRET';
  triState: boolean;
  requiresReason: boolean;
  offRequiresConfirmation: boolean;
  offConfirmWarning: string | null;
  effectiveOnlyWhen: SecrecyCode | null;
  offEffect: string;
}

export const SECRECY_ITEMS: readonly SecrecyItemMeta[] = (secrecyItemsFile as { items: SecrecyItemMeta[] }).items;

export const SECRECY_MODULE_ID = 'secrecy';

/** 工厂端菜单 / 路由依赖的项: 页面路径 → 必须 `=== true` 的项。 */
export const SECRECY_GATED_PATHS: Readonly<Record<string, SecrecyCode>> = {
  '/system/process-secret': 'PROCESS_SECRET',
};

export type SecrecyFlags = Partial<Record<SecrecyCode, boolean>>;

/** 从 feature-config 列表里取出只读的保密配置; 没有那一行 → 空 (等于全都不当开)。 */
export function secrecyFromFeatureConfig(rows: unknown): SecrecyFlags {
  if (!Array.isArray(rows)) return {};
  const row = rows.find((item) => item && typeof item === 'object'
    && (item as Record<string, unknown>).moduleId === SECRECY_MODULE_ID) as Record<string, unknown> | undefined;
  const raw = row?.secrecy ?? (row?.config as Record<string, unknown> | undefined)?.secrecy;
  if (!raw || typeof raw !== 'object') return {};
  const flags: SecrecyFlags = {};
  for (const item of SECRECY_ITEMS) {
    const value = (raw as Record<string, unknown>)[item.code];
    if (typeof value === 'boolean') flags[item.code] = value;
  }
  return flags;
}

/** 只有明确是 true 才算开 (缺字段 / 读不到 / 非布尔都不算)。 */
export function isSecrecyOn(flags: SecrecyFlags | null | undefined, code: SecrecyCode): boolean {
  return flags?.[code] === true;
}

/** 因保密配置要隐藏的工厂端菜单路径。 */
export function secrecyHiddenMenuPaths(flags: SecrecyFlags): string[] {
  return Object.entries(SECRECY_GATED_PATHS)
    .filter(([, code]) => !isSecrecyOn(flags, code))
    .map(([path]) => path);
}

/** 当前工厂的保密配置 (路由守卫用)。读不到 → 空 (页面按「没开」处理)。 */
export async function getFactorySecrecyFlags(factoryId: string): Promise<SecrecyFlags> {
  if (!factoryId) return {};
  try {
    const response = await get<unknown>(`/${encodeURIComponent(factoryId)}/feature-config`);
    return response?.success ? secrecyFromFeatureConfig(response.data) : {};
  } catch {
    return {};
  }
}

// ------------------------------------------------------------------ 平台

export type StoredSecrecyValue = 'UNSET' | 'ON' | 'OFF' | 'FOLLOW';

export interface SecrecyItemState {
  code: SecrecyCode;
  letter: string;
  label: string;
  stored: StoredSecrecyValue;
  effective: boolean;
  inEffect: boolean;
}

export interface SecrecyAuditRow {
  id: number;
  itemCode: SecrecyCode;
  oldValue: StoredSecrecyValue;
  newValue: Exclude<StoredSecrecyValue, 'UNSET'>;
  actorId: number;
  actorRole: string;
  reason: string | null;
  confirmed: boolean;
  bulk: boolean;
  changedAt: string;
}

export interface FactorySecrecySettings {
  factoryId: string;
  items: SecrecyItemState[];
  recentAudit: SecrecyAuditRow[];
}

export interface SecrecyUpdate {
  enabled?: boolean | null;
  followProcessSecret?: boolean;
  reason?: string;
  confirmed?: boolean;
}

const ABS = { baseURL: '' } as const;

function base(factoryId: string): string {
  return `/api/platform/factories/${encodeURIComponent(factoryId)}/secrecy`;
}

export function getPlatformFactorySecrecy(factoryId: string): Promise<ApiResponse<FactorySecrecySettings>> {
  return adminGet<FactorySecrecySettings>(base(factoryId), ABS);
}

export function updatePlatformFactorySecrecy(
  factoryId: string,
  code: SecrecyCode,
  body: SecrecyUpdate,
): Promise<ApiResponse<unknown>> {
  return adminPut<unknown>(`${base(factoryId)}/${code}`, body);
}

export function bulkPlatformFactorySecrecy(
  factoryId: string,
  body: { enabled: boolean; reason?: string; confirmed?: boolean },
): Promise<ApiResponse<FactorySecrecySettings>> {
  return adminPost<FactorySecrecySettings>(`${base(factoryId)}/bulk`, body, ABS);
}

/** 改这一项要不要填理由 / 关闭要不要二次确认 (与后端同一份规则, 后端照样再校验)。 */
export function secrecyItemMeta(code: SecrecyCode): SecrecyItemMeta {
  const meta = SECRECY_ITEMS.find((item) => item.code === code);
  if (!meta) throw new Error(`未知的保密配置项: ${code}`);
  return meta;
}
