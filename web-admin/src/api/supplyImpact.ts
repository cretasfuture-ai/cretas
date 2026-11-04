/**
 * 供应商 / 原料影响分析 (断供 / 质量 / 涨价), 全部只读。
 * 后端: GET /api/mobile/{factoryId}/supply-impact/{disruption|quality|price}
 * 缺数据由后端放进 gaps, 页面如实显示, 不猜。
 */
import { get } from '@/api/request';

export interface SupplyImpactGap { code: string; message: string }
export interface SupplyImpactSubject { kind: 'SUPPLIER' | 'MATERIAL'; id: string; name: string }

export interface SupplyImpactMaterialRef {
  materialTypeId: string; name: string; unit?: string | null; otherSuppliers: string[];
}
export interface SupplyImpactRecipe {
  recipeId: string; productTypeId: string; productName?: string | null; version?: number | null;
  materialTypeId: string; materialName?: string | null; optional: boolean;
  groupAlternatives: string[]; registeredSubstitutes: string[]; hasAlternative: boolean;
}
export interface SupplyImpactPlan {
  planId: string; planNumber: string; productTypeId?: string | null; status: string;
  plannedDate?: string | null; plannedQuantity?: number | null; plannedUnit?: string | null;
  materialTypeId?: string | null; materialName?: string | null;
  requiredQuantity?: number | null; quantityUnit?: string | null; quantityReason?: string | null;
  sourceOrderIds: string[];
}
export interface SupplyImpactStock {
  materialTypeId: string; materialName: string; unit?: string | null; onHand: number;
  inTransitFromSubject: number; inTransitFromOthers: number; horizonDemand?: number | null;
  coverDays?: number | null; note?: string | null;
}
export interface SupplyImpactOrder {
  orderId: string; orderNumber: string; customerId?: string | null; customerName?: string | null;
  status: string; requiredDeliveryDate?: string | null; productName?: string | null;
  undeliveredQuantity: number; unit?: string | null; linkedBy: 'PLAN' | 'PRODUCT';
}
export interface SupplyImpactDisruption {
  subject: SupplyImpactSubject; horizonDays: number;
  materials: SupplyImpactMaterialRef[]; recipes: SupplyImpactRecipe[]; plans: SupplyImpactPlan[];
  stock: SupplyImpactStock[]; orders: SupplyImpactOrder[]; gaps: SupplyImpactGap[];
}
export interface SupplyImpactBatch {
  batchId: string; batchNumber: string; materialName?: string | null; supplierName?: string | null;
  receiptDate?: string | null; receiptQuantity?: number | null; remainingQuantity?: number | null; unit?: string | null;
}
export interface SupplyImpactNode {
  nodeType: string; nodeId: string; batchNumber?: string | null; name?: string | null;
  quantity?: number | null; unit?: string | null;
}
export interface SupplyImpactCustomer { customerName: string; shipmentCount: number; quantities: string[] }
export interface SupplyImpactQuality {
  subject: SupplyImpactSubject; from?: string | null; to?: string | null;
  sourceBatches: SupplyImpactBatch[]; productionBatches: SupplyImpactNode[];
  finishedBatches: SupplyImpactNode[]; shipments: SupplyImpactNode[];
  customers: SupplyImpactCustomer[]; unshippedOrders: SupplyImpactOrder[]; gaps: SupplyImpactGap[];
}
export interface SupplyImpactProductCost {
  productTypeId?: string | null; productName?: string | null; planNumber?: string | null;
  materialQuantityPerPlanUnit?: number | null; quantityUnit?: string | null;
  unitCostBefore?: number | null; unitCostDelta?: number | null; plannedUnit?: string | null;
  planCostDelta?: number | null; reason?: string | null;
}
export interface SupplyImpactPrice {
  subject: SupplyImpactSubject; percent: number; currentUnitPrice?: number | null;
  recipes: SupplyImpactRecipe[]; products: SupplyImpactProductCost[];
  orders: SupplyImpactOrder[]; gaps: SupplyImpactGap[];
}

export interface SupplyImpactTarget { supplierId?: string; materialTypeId?: string }

function params(t: SupplyImpactTarget, extra: Record<string, unknown> = {}) {
  return { params: { supplierId: t.supplierId, materialTypeId: t.materialTypeId, ...extra } };
}

export async function fetchDisruption(factoryId: string, t: SupplyImpactTarget, horizonDays: number) {
  return (await get<SupplyImpactDisruption>(`/${factoryId}/supply-impact/disruption`, params(t, { horizonDays }))).data;
}
export async function fetchQuality(factoryId: string, t: SupplyImpactTarget, from?: string, to?: string) {
  return (await get<SupplyImpactQuality>(`/${factoryId}/supply-impact/quality`, params(t, { from, to }))).data;
}
export async function fetchPrice(factoryId: string, materialTypeId: string, percent: number) {
  return (await get<SupplyImpactPrice>(`/${factoryId}/supply-impact/price`, { params: { materialTypeId, percent } })).data;
}
