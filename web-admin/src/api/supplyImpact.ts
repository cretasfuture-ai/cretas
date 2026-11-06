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
  boxExposure?: SupplyImpactBoxExposure | null;
}
export interface SupplyImpactBoxTotals {
  complete: boolean; totalBoxQuantity: string | null; knownBoxSubtotal: string | null;
  knownSourceCount: number; unknownSourceCount: number;
}
export interface SupplyImpactBoxSource {
  sourceId: string; finishedGoodsBatchId: string; batchNumber: string | null;
  sourceQuantity: string | null; sourceUnit: string | null;
  packagingUnit: string | null; packagingBaseUnit: string | null; packagingFactor: string | null;
  boxQuantity: string | null; reservedQuantity: string | null;
  deliveryRecordId: string | null; deliveryItemId: string | null;
  customerId: string | null; customerName: string | null; gaps: string[];
}
export interface SupplyImpactBoxCustomer {
  customerId: string | null; customerName: string | null; totals: SupplyImpactBoxTotals;
}
export interface SupplyImpactBoxExposure {
  scope: 'DISCOVERED_LINEAGE';
  inventoryBasis: 'PHYSICAL_ON_HAND_INCLUDING_RESERVED';
  quantityMeaning: 'PACKAGING_SNAPSHOT_EQUIVALENT';
  stock: SupplyImpactBoxTotals; shipped: SupplyImpactBoxTotals;
  stockSources: SupplyImpactBoxSource[]; shipmentSources: SupplyImpactBoxSource[];
  customers: SupplyImpactBoxCustomer[]; gaps: SupplyImpactGap[];
}

const BOX_EXPOSURE_KEYS = new Set(['scope', 'inventoryBasis', 'quantityMeaning', 'stock', 'shipped', 'stockSources', 'shipmentSources', 'customers', 'gaps']);
const BOX_TOTAL_KEYS = new Set(['complete', 'totalBoxQuantity', 'knownBoxSubtotal', 'knownSourceCount', 'unknownSourceCount']);
const BOX_SOURCE_KEYS = new Set(['sourceId', 'finishedGoodsBatchId', 'batchNumber', 'sourceQuantity', 'sourceUnit', 'packagingUnit', 'packagingBaseUnit', 'packagingFactor', 'boxQuantity', 'reservedQuantity', 'deliveryRecordId', 'deliveryItemId', 'customerId', 'customerName', 'gaps']);
const BOX_CUSTOMER_KEYS = new Set(['customerId', 'customerName', 'totals']);
const GAP_KEYS = new Set(['code', 'message']);
const PLAIN_DECIMAL = /^(?:0|[1-9]\d*)(?:\.\d+)?$/;
const nullableText = (value: unknown): value is string | null => value === null || typeof value === 'string';
const nullableDecimal = (value: unknown): value is string | null => value === null || (typeof value === 'string' && PLAIN_DECIMAL.test(value));

function isBoxTotals(value: unknown): value is SupplyImpactBoxTotals {
  if (!value || typeof value !== 'object') return false;
  const totals = value as Partial<SupplyImpactBoxTotals>;
  if (!Object.keys(totals).every(key => BOX_TOTAL_KEYS.has(key)) || typeof totals.complete !== 'boolean'
    || !nullableDecimal(totals.totalBoxQuantity) || !nullableDecimal(totals.knownBoxSubtotal)
    || typeof totals.knownSourceCount !== 'number' || !Number.isSafeInteger(totals.knownSourceCount) || totals.knownSourceCount < 0
    || typeof totals.unknownSourceCount !== 'number' || !Number.isSafeInteger(totals.unknownSourceCount) || totals.unknownSourceCount < 0) return false;
  if (totals.complete && (totals.knownSourceCount <= 0 || totals.unknownSourceCount !== 0 || totals.totalBoxQuantity === null
    || totals.knownBoxSubtotal === null || totals.totalBoxQuantity !== totals.knownBoxSubtotal)) return false;
  if (!totals.complete && totals.totalBoxQuantity !== null) return false;
  if (totals.knownSourceCount === 0 && totals.knownBoxSubtotal !== null) return false;
  if (totals.knownSourceCount > 0 && totals.knownBoxSubtotal === null) return false;
  return true;
}

function isBoxSource(value: unknown): value is SupplyImpactBoxSource {
  if (!value || typeof value !== 'object') return false;
  const source = value as Partial<SupplyImpactBoxSource>;
  const textFields = ['sourceId', 'finishedGoodsBatchId'] as const;
  return Object.keys(source).every(key => BOX_SOURCE_KEYS.has(key))
    && textFields.every(key => typeof source[key] === 'string' && source[key].length > 0)
    && nullableText(source.batchNumber) && nullableText(source.sourceUnit)
    && nullableText(source.packagingUnit) && nullableText(source.packagingBaseUnit)
    && nullableDecimal(source.sourceQuantity) && nullableDecimal(source.packagingFactor)
    && nullableDecimal(source.boxQuantity) && nullableDecimal(source.reservedQuantity)
    && nullableText(source.deliveryRecordId) && nullableText(source.deliveryItemId)
    && nullableText(source.customerId) && nullableText(source.customerName)
    && Array.isArray(source.gaps) && source.gaps.every(gap => typeof gap === 'string' && gap.trim().length > 0)
    && (source.boxQuantity !== null || source.gaps.length > 0);
}

function isBoxCustomer(value: unknown): value is SupplyImpactBoxCustomer {
  if (!value || typeof value !== 'object') return false;
  const customer = value as Partial<SupplyImpactBoxCustomer>;
  return Object.keys(customer).every(key => BOX_CUSTOMER_KEYS.has(key))
    && nullableText(customer.customerId) && nullableText(customer.customerName) && isBoxTotals(customer.totals);
}

export function isSupplyImpactBoxExposure(value: unknown): value is SupplyImpactBoxExposure {
  if (!value || typeof value !== 'object') return false;
  const exposure = value as Partial<SupplyImpactBoxExposure>;
  return Object.keys(exposure).every(key => BOX_EXPOSURE_KEYS.has(key))
    && exposure.scope === 'DISCOVERED_LINEAGE'
    && exposure.inventoryBasis === 'PHYSICAL_ON_HAND_INCLUDING_RESERVED'
    && exposure.quantityMeaning === 'PACKAGING_SNAPSHOT_EQUIVALENT'
    && isBoxTotals(exposure.stock) && isBoxTotals(exposure.shipped)
    && Array.isArray(exposure.stockSources) && exposure.stockSources.every(isBoxSource)
    && Array.isArray(exposure.shipmentSources) && exposure.shipmentSources.every(isBoxSource)
    && Array.isArray(exposure.customers) && exposure.customers.every(isBoxCustomer)
    && Array.isArray(exposure.gaps) && exposure.gaps.every(gap => !!gap && typeof gap === 'object'
      && Object.keys(gap).every(key => GAP_KEYS.has(key))
      && typeof gap.code === 'string' && gap.code.length > 0 && typeof gap.message === 'string' && gap.message.length > 0)
    && totalsMatchSources(exposure.stock, exposure.stockSources, true)
    && totalsMatchSources(exposure.shipped, exposure.shipmentSources, false)
    && customerGroupsMatch(exposure.shipmentSources, exposure.customers);
}

function totalsMatchSources(totals: SupplyImpactBoxTotals, sources: SupplyImpactBoxSource[], requireFinishedBatchUniqueness: boolean): boolean {
  const sourceIds = sources.map(source => source.sourceId);
  if (new Set(sourceIds).size !== sourceIds.length) return false;
  if (requireFinishedBatchUniqueness) {
    const finishedIds = sources.map(source => source.finishedGoodsBatchId);
    if (new Set(finishedIds).size !== finishedIds.length) return false;
  }
  const known = sources.filter(source => source.boxQuantity !== null).length;
  const unknown = sources.length - known;
  if (totals.knownSourceCount !== known || totals.unknownSourceCount < unknown) return false;
  return !totals.complete || (unknown === 0 && totals.unknownSourceCount === 0 && known === sources.length);
}

function customerGroupsMatch(sources: SupplyImpactBoxSource[], customers: SupplyImpactBoxCustomer[]): boolean {
  const sourceGroups = new Map<string, SupplyImpactBoxSource[]>();
  for (const source of sources) {
    const key = source.customerId || '__UNKNOWN_CUSTOMER__';
    sourceGroups.set(key, [...(sourceGroups.get(key) || []), source]);
  }
  const customerGroups = new Map<string, SupplyImpactBoxCustomer>();
  for (const customer of customers) {
    const key = customer.customerId || '__UNKNOWN_CUSTOMER__';
    if (customerGroups.has(key)) return false;
    customerGroups.set(key, customer);
  }
  if (sourceGroups.size !== customerGroups.size) return false;
  for (const [key, group] of sourceGroups) {
    const customer = customerGroups.get(key);
    if (!customer) return false;
    const known = group.filter(source => source.boxQuantity !== null).length;
    const unknown = group.length - known;
    if (customer.totals.knownSourceCount !== known || customer.totals.unknownSourceCount < unknown) return false;
  }
  return true;
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
