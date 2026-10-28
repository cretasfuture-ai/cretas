/**
 * 锅次 API (S3-16)。
 *
 * Backend: ProductionPotController @ /api/mobile/{factoryId}/production-pots
 *   GET  ?productionBatchId=                    某生产批次的锅次 + 投入/产出分摊进度
 *   GET  /by-batches?productionBatchIds=1,2     多个生产批次的锅次 (溯源页)
 *   POST /                                      录入一锅
 *   PUT  /{potId}                               更正 (reason 必填, 原值留痕)
 *   POST /{potId}/confirm                       主管确认 (关联不明 / 摄像候选时间)
 *   POST /{potId}/void                          作废 (reason 必填, 不删除)
 *   GET  /trace/consumers/{nodeType}/{nodeId}   哪些锅用了这个原料/在制批 → 出到哪里
 *   GET  /trace/producers/{nodeType}/{nodeId}   这个成品/在制批来自哪几锅
 *   GET  /{potId}/trace                         这一锅出到了哪些批次、最终哪些成品
 */
import { get, post, put } from './request';

export type PotNodeType = 'MATERIAL_BATCH' | 'FINISHED_BATCH';
export type PotTimeSource = 'MANUAL' | 'DEVICE' | 'CAMERA_CANDIDATE' | 'UNKNOWN';
export type PotBindingStatus = 'CONFIRMED' | 'PENDING_CONFIRMATION';

export interface PotLink {
  nodeType: PotNodeType;
  nodeId: string;
  quantity: number;
  unit?: string | null;
}

export interface PotLinkView extends PotLink {
  batchNumber: string | null;
}

export interface PotCorrectionView {
  action: 'CORRECT' | 'CONFIRM' | 'VOID';
  fieldName: string;
  oldValue: string | null;
  newValue: string | null;
  reason: string;
  correctedBy: number | null;
  correctedByName: string | null;
  correctedAt: string;
}

export interface PotView {
  id: number;
  productionBatchId: number;
  productionPlanId: string | null;
  processSheetRowId: number | null;
  processCode: string | null;
  processName: string | null;
  potNumber: number;
  equipmentLabel: string | null;
  startedAt: string | null;
  endedAt: string | null;
  timeSource: PotTimeSource;
  timeConfirmed: boolean;
  /** 只有时间齐全且已确认才有值。 */
  durationMinutes: number | null;
  bindingStatus: PotBindingStatus;
  operatorId: number | null;
  operatorName: string | null;
  outputQuantity: number | null;
  outputUnit: string | null;
  processParams: Record<string, unknown>;
  source: 'POT_API' | 'PROCESS_SHEET';
  status: 'RECORDED' | 'VOIDED';
  voidReason: string | null;
  remark: string | null;
  inputs: PotLinkView[];
  outputs: PotLinkView[];
  corrections: PotCorrectionView[];
  /** 该产品工艺保密且当前账号无完整查看权: 工序名 / 代码 / 工艺参数 / 备注已由后端去掉。 */
  processRedacted: boolean;
}

export interface PotAllocationLine {
  nodeType: string;
  nodeId: string;
  batchNumber: string | null;
  batchQuantity: number | null;
  allocatedQuantity: number;
  unit: string | null;
  complete: boolean;
}

export interface BatchPotsView {
  productionBatchId: number;
  batchNumber: string;
  potCount: number;
  pendingCount: number;
  pots: PotView[];
  inputAllocation: PotAllocationLine[];
  outputAllocation: PotAllocationLine[];
}

export interface PotCommand {
  productionBatchId: number;
  processCode?: string | null;
  processName?: string | null;
  potNumber: number;
  equipmentLabel?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  timeSource?: PotTimeSource;
  bindingStatus?: PotBindingStatus;
  operatorName?: string | null;
  outputQuantity?: number | null;
  outputUnit?: string | null;
  processParams?: Record<string, unknown>;
  inputs?: PotLink[];
  outputs?: PotLink[];
  remark?: string | null;
}

export interface PotCorrectionCommand extends Partial<Omit<PotCommand, 'productionBatchId' | 'processCode' | 'processName' | 'bindingStatus'>> {
  reason: string;
}

export interface NodeRef {
  nodeType: string;
  nodeId: string;
  batchNumber: string | null;
}

export interface PotBrief {
  potId: number;
  productionBatchId: number;
  processCode: string | null;
  processName: string | null;
  potNumber: number;
  equipmentLabel: string | null;
  startedAt: string | null;
  endedAt: string | null;
  pending: boolean;
  inputQuantity: number | null;
  inputUnit: string | null;
}

export interface BatchPotScope {
  productionBatchId: number;
  productionBatchNumber: string | null;
  inputNodeType: string;
  inputNodeId: string;
  inputBatchNumber: string | null;
  potCount: number;
  narrowed: boolean;
  reason: string;
  batchQuantity: number | null;
  allocatedQuantity: number;
  unit: string | null;
  affectedPots: PotBrief[];
  pendingAffectedPots: number;
  outputsNarrowed: boolean;
  affectedOutputs: NodeRef[];
  excludedOutputs: NodeRef[];
  downstreamFinishedGoods: NodeRef[];
}

export interface MaterialPotTrace {
  nodeType: string;
  nodeId: string;
  batchNumber: string | null;
  batches: BatchPotScope[];
}

const base = (factoryId: string) => `/${factoryId}/production-pots`;

export const listBatchPots = (factoryId: string, productionBatchId: number | string) =>
  get<BatchPotsView>(base(factoryId), { params: { productionBatchId } });

export const listPotsForBatches = (factoryId: string, productionBatchIds: Array<number | string>) =>
  get<PotView[]>(`${base(factoryId)}/by-batches`, { params: { productionBatchIds: productionBatchIds.join(',') } });

export const recordPot = (factoryId: string, body: PotCommand) => post<PotView>(base(factoryId), body);

export const correctPot = (factoryId: string, potId: number, body: PotCorrectionCommand) =>
  put<PotView>(`${base(factoryId)}/${potId}`, body);

export const confirmPot = (factoryId: string, potId: number, body: { startedAt?: string | null; endedAt?: string | null; reason: string }) =>
  post<PotView>(`${base(factoryId)}/${potId}/confirm`, body);

export const voidPot = (factoryId: string, potId: number, reason: string) =>
  post<PotView>(`${base(factoryId)}/${potId}/void`, { reason });

export const tracePotConsumers = (factoryId: string, nodeType: PotNodeType, nodeId: string) =>
  get<MaterialPotTrace>(`${base(factoryId)}/trace/consumers/${nodeType}/${encodeURIComponent(nodeId)}`);

export const TIME_SOURCE_LABEL: Record<PotTimeSource, string> = {
  MANUAL: '人工录入',
  DEVICE: '设备',
  CAMERA_CANDIDATE: '摄像候选',
  UNKNOWN: '未知',
};

/** 待确认 = 时间未确认 或 锅批关联不明 (与后端 ProductionPotService.isPending 同口径)。 */
export function isPotPending(p: Pick<PotView, 'timeConfirmed' | 'bindingStatus'>): boolean {
  return !p.timeConfirmed || p.bindingStatus === 'PENDING_CONFIRMATION';
}
