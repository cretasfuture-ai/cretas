/**
 * 本厂建厂就绪（只读）
 * GET /api/mobile/{factoryId}/factory-readiness/current
 *
 * 只读取，不应用配置、不发布、不写单据。读不到就抛错，由页面如实显示，不返回假数据。
 */
import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export interface ReadinessCheck {
  code: string;
  label: string;
  state: 'MET' | 'MISSING' | 'UNKNOWN';
  detail: string;
  remedyKey: string;
  remedyLabel: string;
}

export interface ReadinessFirstAction {
  label: string;
  path: string;
  query: Record<string, string>;
  detail: string;
}

export interface ReadinessDomain {
  domain: 'PURCHASE' | 'PRODUCTION' | 'SHIPPING';
  label: string;
  applicability: 'APPLICABLE' | 'NOT_APPLICABLE' | 'UNKNOWN';
  applicabilityBasis: string;
  state: 'READY' | 'MISSING' | 'UNKNOWN' | 'NOT_APPLICABLE';
  checks: ReadinessCheck[];
  notChecked: string[];
  firstRecord: { state: 'COMPLETED' | 'RECORDED' | 'NONE' | 'UNKNOWN'; detail: string };
  firstAction?: ReadinessFirstAction | null;
}

export type SelfReadiness =
  | { state: 'NO_CANONICAL_BLUEPRINT'; blueprint: null; readiness: null }
  | {
      state: 'READY_TO_READ';
      blueprint: { id: string; version: number };
      readiness: { businessReadiness?: ReadinessDomain[] | null };
    };

export async function getMyFactoryReadiness(factoryId?: string): Promise<SelfReadiness> {
  const current = getCurrentFactoryId(factoryId);
  if (!current) throw new Error('当前账号没有所属工厂，无法查看建厂就绪');
  const response = await apiClient.get<{ success: boolean; data: SelfReadiness; message?: string }>(
    `/api/mobile/${current}/factory-readiness/current`
  );
  const data = response?.data;
  if (!response?.success || !data) throw new Error(response?.message || '建厂就绪状态读取失败');
  if (data.state === 'READY_TO_READ' ? !data.readiness : data.state !== 'NO_CANONICAL_BLUEPRINT') {
    throw new Error('建厂就绪状态格式不正确，请刷新后重试');
  }
  return data;
}
