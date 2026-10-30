import { adminGet, adminPost, adminPut } from './request';
import type { ApiResponse } from '@/types/api';

export interface PlatformFactory {
  id: string;
  name: string;
  factoryType?: string;
  industryCode?: string;
  isActive?: boolean;
}

export interface FactoryMenuModule {
  moduleId: string;
  moduleName: string;
  visible: boolean;
  configured: boolean;
  disabledMenuPaths: string[];
}

export interface FactoryMenuConfig {
  factoryId: string;
  factoryName: string;
  modules: FactoryMenuModule[];
}

export interface FactoryViewContext {
  factoryId: string;
  factoryName: string;
  factoryType?: string;
  accessToken: string;
  expiresIn: number;
  readOnly: true;
}

export interface FactoryMenuUpdate {
  visible: boolean;
  disabledMenuPaths: string[];
}

const ABS = { baseURL: '' } as const;

function unwrapList(value: unknown): unknown[] {
  if (Array.isArray(value)) return value;
  if (!value || typeof value !== 'object') return [];
  const row = value as Record<string, unknown>;
  for (const key of ['list', 'records', 'content', 'items']) {
    if (Array.isArray(row[key])) return row[key] as unknown[];
  }
  return [];
}

export async function listPlatformFactories(): Promise<PlatformFactory[]> {
  const response = await adminGet<unknown>('/api/platform/factories', ABS);
  const raw = response.data;
  const hasKnownListShape = Array.isArray(raw) || (raw && typeof raw === 'object'
    && ['list', 'records', 'content', 'items'].some((key) => Array.isArray((raw as Record<string, unknown>)[key])));
  if (!hasKnownListShape) throw new Error('工厂列表响应格式无效，未加载任何工厂');
  return unwrapList(raw).map((item) => {
    const row = (item && typeof item === 'object') ? item as Record<string, unknown> : {};
    return {
      id: String(row.id ?? row.factoryId ?? ''),
      name: String(row.name ?? row.factoryName ?? row.id ?? ''),
      factoryType: row.factoryType ? String(row.factoryType) : undefined,
      industryCode: row.industryCode ? String(row.industryCode) : undefined,
      isActive: row.isActive !== false,
    };
  }).filter((factory) => factory.id);
}

export function getFactoryMenuConfig(factoryId: string): Promise<ApiResponse<FactoryMenuConfig>> {
  return adminGet<FactoryMenuConfig>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/menu-config`,
    ABS,
  );
}

export function updateFactoryMenuConfig(
  factoryId: string,
  moduleId: string,
  payload: FactoryMenuUpdate,
): Promise<ApiResponse<FactoryMenuConfig>> {
  return adminPut<FactoryMenuConfig>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/menu-config/${encodeURIComponent(moduleId)}`,
    payload,
  );
}

export function createFactoryViewContext(
  factoryId: string,
): Promise<ApiResponse<FactoryViewContext>> {
  return adminPost<FactoryViewContext>(
    `/api/platform/factories/${encodeURIComponent(factoryId)}/view-context`,
    {},
    ABS,
  );
}
