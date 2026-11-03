/**
 * 温度关联批次 API (食品 DAG 缺口 15)。
 *
 * Backend: ColdChainExposureController @ /api/mobile/{factoryId}/cold-chain
 *   GET  /alerts                                  冷链告警 (最近 N 天)
 *   GET  /alerts/{alertId}/affected-batches       超温时段 + 受影响批次
 *   POST /alerts/{alertId}/holds                  选中批次转质检 (沿用质检放行的待检状态)
 *   GET  /equipment                               冷链设备 + 当前所在仓库
 *   GET  /equipment/{id}/locations                设备库位历史
 *   POST /equipment/{id}/locations                登记设备从某时刻起在某仓库
 */
import { get, post } from './request';

export type ColdChainHoldTargetType = 'RAW_MATERIAL_BATCH' | 'WIP_MATERIAL_BATCH' | 'FINISHED_GOODS';

export interface ColdChainAlertView {
  id: number;
  equipmentId: number;
  equipmentCode: string | null;
  equipmentName: string | null;
  alertTime: string;
  severity: string;
  minTemp: number;
  maxTemp: number;
  durationMinutes: number;
  status: string;
  heldBatchCount: number;
}

export interface ColdChainExcursionView {
  start: string;
  end: string;
  minutes: number;
  minTemp: number;
  maxTemp: number;
  targetTempMin: number;
  targetTempMax: number;
  readingCount: number;
  ongoing: boolean;
}

export interface ColdChainSegmentView {
  warehouseId: string;
  warehouseName: string | null;
  start: string;
  end: string;
  minutes: number;
}

export interface ColdChainGapView {
  start: string;
  end: string;
  minutes: number;
}

export interface ColdChainAffectedBatch {
  targetType: ColdChainHoldTargetType;
  formLabel: string;
  targetId: string;
  batchNumber: string | null;
  itemName: string | null;
  warehouseId: string;
  warehouseName: string | null;
  quantityNow: number | null;
  unit: string | null;
  status: string | null;
  /** AFFECTED = 暴露时长确定; UNCERTAIN = 在库时间不确定 */
  verdict: 'AFFECTED' | 'UNCERTAIN';
  exposureMinutes: number | null;
  maxExposureMinutes: number;
  firstExposedAt: string | null;
  lastExposedAt: string | null;
  inStockFrom: string;
  inStockUntil: string;
  uncertainReason: string | null;
  holdable: boolean;
  holdBlockedReason: string | null;
  alreadyHeld: boolean;
}

export interface ColdChainAffectedReport {
  alert: ColdChainAlertView;
  excursion: ColdChainExcursionView;
  segments: ColdChainSegmentView[];
  unlocatedPeriods: ColdChainGapView[];
  batches: ColdChainAffectedBatch[];
  affectedCount: number;
  uncertainCount: number;
  notAffectedCount: number;
  note: string | null;
}

export interface ColdChainHoldOutcome {
  targetType: ColdChainHoldTargetType;
  targetId: string;
  batchNumber: string | null;
  outcome: 'HELD' | 'ALREADY_HELD' | 'ALREADY_PENDING' | 'SKIPPED';
  message: string;
}

export interface ColdChainEquipmentView {
  id: number;
  equipmentCode: string;
  equipmentName: string;
  type: string;
  targetTempMin: number;
  targetTempMax: number;
  active: boolean;
  currentWarehouseId: string | null;
  currentWarehouseName: string | null;
  currentSince: string | null;
}

export interface ColdChainLocationView {
  id: number;
  equipmentId: number;
  warehouseId: string;
  warehouseName: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  remark: string | null;
}

const base = (factoryId: string) => `/${factoryId}/cold-chain`;

export const listColdChainAlerts = (factoryId: string, days = 30) =>
  get<ColdChainAlertView[]>(`${base(factoryId)}/alerts`, { params: { days } });

export const getColdChainAffectedBatches = (factoryId: string, alertId: number) =>
  get<ColdChainAffectedReport>(`${base(factoryId)}/alerts/${alertId}/affected-batches`);

export const holdColdChainBatches = (
  factoryId: string,
  alertId: number,
  targets: { targetType: ColdChainHoldTargetType; targetId: string }[],
) => post<ColdChainHoldOutcome[]>(`${base(factoryId)}/alerts/${alertId}/holds`, { targets });

export const listColdChainEquipment = (factoryId: string) =>
  get<ColdChainEquipmentView[]>(`${base(factoryId)}/equipment`);

export const listColdChainLocations = (factoryId: string, equipmentId: number) =>
  get<ColdChainLocationView[]>(`${base(factoryId)}/equipment/${equipmentId}/locations`);

export const assignColdChainLocation = (
  factoryId: string,
  equipmentId: number,
  body: { warehouseId: string; effectiveFrom?: string | null; remark?: string | null },
) => post<ColdChainLocationView>(`${base(factoryId)}/equipment/${equipmentId}/locations`, body);
