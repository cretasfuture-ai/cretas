/**
 * 批次召回 API (App 端) —— 与网页 web-admin/src/api/factoryRecall.ts 同一组后端接口。
 *
 * Backend: FactoryRecallController  /api/mobile/{factoryId}/quality-recalls
 *   读: quality:read / quality:read_write;  写 (发起 / 客户联系 / 退货 / 结案): quality:read_write
 * 不新造后端; 召回范围、库存隔离、能否结案全部由后端计算, App 只展示与提交。
 */
import { apiClient } from './apiClient';

export interface RecallReturnOrderSummary {
  id: number | string;
  returnNumber: string;
  status: string;
  quantity: number;
}

export interface RecallShipmentSummary {
  shipmentId: string;
  shipmentNumber: string;
  customerName: string;
  quantity: number;
  unit: string;
  returnedQuantity: number;
  pendingReturnQuantity: number;
  remainingReturnQuantity: number;
  acknowledged: boolean;
  returnOrders: RecallReturnOrderSummary[];
}

export interface RecallBlocker {
  code: string;
  message: string;
  shipmentId?: string | null;
  quantity?: number | null;
}

export interface RecallBatchContext {
  batchId: number;
  batchNumber: string;
  productName: string;
  unit: string;
  remainingInventory: number;
  shipments: RecallShipmentSummary[];
}

export interface FactoryRecallDetail extends RecallBatchContext {
  id: number | string;
  eventCode: string;
  status: string;
  reason: string;
  completedAt?: string | null;
  canClose: boolean;
  blockers: RecallBlocker[];
}

export interface FactoryRecallSummary {
  id: number | string;
  eventCode: string;
  batchId: number;
  batchNumber: string;
  productName: string;
  status: string;
  reason: string;
  completedAt?: string | null;
}

export interface RecallBatchOption {
  id: number | string;
  batchNumber?: string | null;
  productName?: string | null;
  unit?: string | null;
}

interface Envelope<T> {
  success: boolean;
  data?: T | null;
  message?: string;
}

function unwrap<T>(res: Envelope<T>, fallback: string): T {
  if (!res || !res.success || res.data == null) throw new Error(res?.message || fallback);
  return res.data;
}

const base = (factoryId: string) => `/api/mobile/${factoryId}/quality-recalls`;
const event = (factoryId: string, id: number | string) => `${base(factoryId)}/${encodeURIComponent(String(id))}`;

/** 召回状态中文 (与网页 recalls/index.vue statusLabels 同一套)。 */
export const RECALL_STATUS_LABELS: Record<string, string> = {
  OPEN: '进行中',
  IN_PROGRESS: '进行中',
  FROZEN: '库存已隔离',
  COMPLETED: '已结案',
  CLOSED: '已结案',
  PENDING_APPROVAL: '待审批',
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  PENDING: '待处理',
  APPROVED: '已批准',
  PROCESSING: '处理中',
  RECEIVED: '已收货',
  RETURNED: '已退回',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
};

export function recallStatusText(status: string | null | undefined): string {
  const key = String(status ?? '').toUpperCase();
  return RECALL_STATUS_LABELS[key] ?? (key || '未知状态');
}

export function isRecallClosed(status: string | null | undefined): boolean {
  const key = String(status ?? '').toUpperCase();
  return key === 'COMPLETED' || key === 'CLOSED';
}

export const qualityRecallAPI = {
  async list(factoryId: string): Promise<FactoryRecallSummary[]> {
    return unwrap(await apiClient.get<Envelope<FactoryRecallSummary[]>>(base(factoryId)), '召回记录加载失败');
  },

  async preview(factoryId: string, batchId: number): Promise<RecallBatchContext> {
    return unwrap(
      await apiClient.get<Envelope<RecallBatchContext>>(`${base(factoryId)}/preview`, { params: { batchId } }),
      '批次召回预览加载失败'
    );
  },

  async create(factoryId: string, payload: { batchId: number; reason: string }): Promise<FactoryRecallDetail> {
    return unwrap(await apiClient.post<Envelope<FactoryRecallDetail>>(base(factoryId), payload), '发起召回失败');
  },

  async get(factoryId: string, id: number | string): Promise<FactoryRecallDetail> {
    return unwrap(await apiClient.get<Envelope<FactoryRecallDetail>>(event(factoryId, id)), '召回详情加载失败');
  },

  async acknowledge(
    factoryId: string,
    id: number | string,
    payload: { shipmentId: string; note: string }
  ): Promise<FactoryRecallDetail> {
    return unwrap(
      await apiClient.post<Envelope<FactoryRecallDetail>>(`${event(factoryId, id)}/customer-ack`, payload),
      '记录客户联系失败'
    );
  },

  async createReturn(
    factoryId: string,
    id: number | string,
    payload: { shipmentId: string; quantity: number; reason: string }
  ): Promise<RecallReturnOrderSummary> {
    return unwrap(
      await apiClient.post<Envelope<RecallReturnOrderSummary>>(`${event(factoryId, id)}/returns`, payload),
      '登记退货失败'
    );
  },

  async close(factoryId: string, id: number | string, note: string): Promise<FactoryRecallDetail> {
    return unwrap(
      await apiClient.post<Envelope<FactoryRecallDetail>>(`${event(factoryId, id)}/close`, { note }),
      '召回结案失败'
    );
  },

  /** 可选的生产批次 (与网页同一个来源: /processing/batches)。 */
  async listBatchOptions(factoryId: string): Promise<RecallBatchOption[]> {
    const res = await apiClient.get<Envelope<{ content?: RecallBatchOption[] } | RecallBatchOption[]>>(
      `/api/mobile/${factoryId}/processing/batches`,
      { params: { size: 200 } }
    );
    const data = unwrap(res, '生产批次读取失败');
    return Array.isArray(data) ? data : data.content ?? [];
  },
};

export default qualityRecallAPI;
