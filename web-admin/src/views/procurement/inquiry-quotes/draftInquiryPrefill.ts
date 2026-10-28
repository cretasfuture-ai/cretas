/**
 * 从采购草稿的物料行发起比价: 采购单详情页带着 query 跳到核价单列表,
 * 这里把 query 解析成新建核价单表单的预填值。
 *
 * 只有 sourcePurchaseOrderId + materialTypeId + unit + 正数量 齐全才算有效预填;
 * 缺任何一项返回 null, 不猜。
 */
import type { LocationQuery } from 'vue-router';

export interface DraftInquiryPrefill {
  sourcePurchaseOrderId: string;
  sourceOrderNumber: string;
  materialTypeId: string;
  materialName: string;
  specification: string;
  quantity: number;
  unit: string;
}

function one(query: LocationQuery, key: string): string {
  const value = query[key];
  const raw = Array.isArray(value) ? value[0] : value;
  return typeof raw === 'string' ? raw.trim() : '';
}

export function parseDraftInquiryPrefill(query: LocationQuery): DraftInquiryPrefill | null {
  const sourcePurchaseOrderId = one(query, 'sourcePurchaseOrderId');
  const materialTypeId = one(query, 'materialTypeId');
  const unit = one(query, 'unit');
  const quantity = Number(one(query, 'quantity'));
  if (!sourcePurchaseOrderId || !materialTypeId || !unit || !Number.isFinite(quantity) || quantity <= 0) {
    return null;
  }
  return {
    sourcePurchaseOrderId,
    sourceOrderNumber: one(query, 'sourceOrderNumber'),
    materialTypeId,
    materialName: one(query, 'materialName'),
    specification: one(query, 'specification'),
    quantity,
    unit,
  };
}

/** 采购单详情页用: 生成跳到核价单列表的 query。 */
export function buildDraftInquiryQuery(input: {
  orderId: string;
  orderNumber?: string | null;
  materialTypeId: string;
  materialName?: string | null;
  specification?: string | null;
  quantity: number;
  unit: string;
}): Record<string, string> {
  const query: Record<string, string> = {
    sourcePurchaseOrderId: input.orderId,
    materialTypeId: input.materialTypeId,
    quantity: String(input.quantity),
    unit: input.unit,
  };
  if (input.orderNumber) query.sourceOrderNumber = input.orderNumber;
  if (input.materialName) query.materialName = input.materialName;
  if (input.specification) query.specification = input.specification;
  return query;
}
