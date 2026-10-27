/**
 * 质检放行 API (缺口 11)。
 *
 * Backend: QcReleaseController @ /api/mobile/{factoryId}/qc-release
 *   GET  /pending    待检清单 (半成品 / 在制半成品 / 成品)
 *   POST /decisions  放行 / 判不合格 (结论必填; 记录放行人、放行时间)
 *   GET  /records    放行记录
 */
import { get, post } from './request';

export type QcReleaseTargetType = 'SEMI_FINISHED' | 'WIP_MATERIAL_BATCH' | 'FINISHED_GOODS';
export type QcReleaseDecision = 'RELEASED' | 'REJECTED';

export const QC_RELEASE_TARGET_LABELS: Record<QcReleaseTargetType, string> = {
  SEMI_FINISHED: '半成品',
  WIP_MATERIAL_BATCH: '在制半成品',
  FINISHED_GOODS: '成品',
};

export interface QcPendingItem {
  targetType: QcReleaseTargetType;
  targetId: string;
  formLabel: string;
  batchNumber: string | null;
  productTypeId: string | null;
  productName: string | null;
  quantity: number | null;
  unit: string | null;
  producedAt: string | null;
  /** 挡放行的未处理 CCP 偏离条数; 0 = 可放行 */
  openDeviationCount: number;
}

export interface QcReleaseRecord {
  id: number;
  targetType: QcReleaseTargetType;
  targetId: string;
  batchNumber: string | null;
  productTypeId: string | null;
  quantity: number | null;
  unit: string | null;
  decision: QcReleaseDecision;
  conclusion: string;
  releasedBy: number;
  releasedByName: string | null;
  releasedAt: string;
}

export interface QcReleaseDecisionRequest {
  targetType: QcReleaseTargetType;
  targetId: string;
  decision: QcReleaseDecision;
  conclusion: string;
}

const base = (factoryId: string) => `/${factoryId}/qc-release`;

export const listQcPending = (factoryId: string) =>
  get<QcPendingItem[]>(`${base(factoryId)}/pending`);

export const decideQcRelease = (factoryId: string, body: QcReleaseDecisionRequest) =>
  post<QcReleaseRecord>(`${base(factoryId)}/decisions`, body);

export const listQcReleaseRecords = (
  factoryId: string,
  params: { targetType?: QcReleaseTargetType; targetId?: string } = {},
) => get<QcReleaseRecord[]>(`${base(factoryId)}/records`, { params });

/**
 * 被扣成待检 / 判不合格的批次号集合 —— 逐道录入的上游批次下拉据此把这些批次标「待检」并禁选。
 * 读失败时返回空集合并把 ok=false 交给调用方 (调用方据此提示「待检状态未知」, 不假装全部可选)。
 */
export async function loadHeldBatchNumbers(factoryId: string): Promise<{ ok: boolean; held: Set<string> }> {
  try {
    const res = await get<QcPendingItem[]>(`${base(factoryId)}/pending`, { _silent: true });
    if (!res.success || !Array.isArray(res.data)) return { ok: false, held: new Set() };
    const held = new Set<string>();
    for (const item of res.data) {
      if (item.batchNumber) held.add(item.batchNumber);
      // 在制批次的批号是 "WIP-" + 生产批号; 下拉里显示的是生产批号, 两个都放进去
      if (item.targetType === 'WIP_MATERIAL_BATCH' && item.batchNumber?.startsWith('WIP-')) {
        held.add(item.batchNumber.slice(4));
      }
    }
    return { ok: true, held };
  } catch {
    return { ok: false, held: new Set() };
  }
}
