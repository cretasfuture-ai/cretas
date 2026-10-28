/**
 * P-NUCLEAR-1 (28-Backlog #30) — 核价单 询价/核价/采购 pipeline API client.
 *
 * <p>Backend: InquiryQuoteController at /api/mobile/{factoryId}/purchase/inquiry-quotes
 */
import { get, post } from './request';
import type { ApiResponse } from '@/types/api';

export type InquiryQuoteStatus =
  | 'DRAFT'
  | 'INQUIRING'
  | 'QUOTED'
  | 'SELECTED'
  | 'CONVERTED'
  | 'CANCELLED';

export interface InquiryQuote {
  id: string;
  factoryId: string;
  inquiryNumber: string;
  title?: string | null;
  materialTypeId: string;
  materialName?: string | null;
  specification?: string | null;
  quantity: number;
  unit: string;
  inquiryDate: string;
  requiredDate?: string | null;
  status: InquiryQuoteStatus;
  selectedSupplierId?: string | null;
  selectedSupplierName?: string | null;
  selectedUnitPrice?: number | null;
  purchaseOrderId?: string | null;
  purchaseOrderNumber?: string | null;
  /** 发起比价的采购草稿; 有值时选定报价回填这张草稿, 不另建采购单 */
  sourcePurchaseOrderId?: string | null;
  remark?: string | null;
  createdAt: string;
  updatedAt?: string | null;
  version?: number | null;
}

export interface InquiryQuoteSupplierPrice {
  id: string;
  inquiryQuoteId: string;
  supplierId: string;
  supplierName?: string | null;
  unitPrice: number;
  /** null = 税率未知 (不是 0%) */
  taxRate?: number | null;
  currency?: string | null;
  quotedUnit?: string | null;
  specification?: string | null;
  minOrderQuantity?: number | null;
  priceEffectiveDate?: string | null;
  validUntil?: string | null;
  deliveryDays?: number | null;
  quotedAt: string;
  remark?: string | null;
}

export interface QuoteIssue {
  code: string;
  message: string;
}

export interface QuoteComparisonRow {
  priceId: string;
  supplierId: string;
  supplierName?: string | null;
  unitPrice?: number | null;
  taxRate?: number | null;
  taxInclusiveUnitPrice?: number | null;
  estimatedAmount?: number | null;
  currency?: string | null;
  quotedUnit?: string | null;
  specification?: string | null;
  minOrderQuantity?: number | null;
  minOrderQuantitySource?: 'QUOTE' | 'SUPPLIER_MATERIAL' | null;
  minOrderQuantityUnit?: string | null;
  leadTimeDays?: number | null;
  leadTimeSource?: 'QUOTE' | 'SUPPLIER_MATERIAL' | null;
  expectedArrivalDate?: string | null;
  validUntil?: string | null;
  priceEffectiveDate?: string | null;
  comparable: boolean;
  notComparableReasons: QuoteIssue[];
  selectable: boolean;
  blockingReasons: QuoteIssue[];
  warnings: QuoteIssue[];
  rank?: number | null;
  purchasePackagingSpecId?: string | null;
}

export interface QuoteComparison {
  inquiryId: string;
  inquiryNumber: string;
  materialTypeId: string;
  materialName?: string | null;
  quantity: number;
  referenceUnit: string;
  referenceCurrency: string;
  referenceSpecification?: string | null;
  referenceTaxRate?: number | null;
  referenceTaxRateSource?: string | null;
  requiredDate?: string | null;
  evaluatedOn: string;
  rankingBasis: string;
  sourcePurchaseOrderId?: string | null;
  comparableCount: number;
  selectableCount: number;
  rows: QuoteComparisonRow[];
}

export interface QuotePriceImpactRow {
  recipeId: string;
  recipeCode?: string | null;
  productTypeId?: string | null;
  productName?: string | null;
  recipeVersion?: number | null;
  recipeStatus?: string | null;
  isCurrent?: boolean | null;
  recipeItemId: number;
  standardQuantity?: number | null;
  priceUnit?: string | null;
  currentUnitPrice?: number | null;
  currentItemCost?: number | null;
  estimatedItemCost?: number | null;
  deltaItemCost?: number | null;
  computable: boolean;
  notComputableReason?: string | null;
}

export interface QuotePriceImpact {
  inquiryId: string;
  inquiryNumber: string;
  priceId: string;
  supplierName?: string | null;
  materialName?: string | null;
  quotedUnitPrice?: number | null;
  quotedUnit?: string | null;
  priceEffectiveDate: string;
  evaluatedOn: string;
  nature: 'ESTIMATE';
  historicalCostsModified: boolean;
  note: string;
  affectedRecipeCount: number;
  computableRowCount: number;
  hiddenRecipeCount: number;
  rows: QuotePriceImpactRow[];
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ============================================================================
// 核价单 CRUD
// ============================================================================

export function createInquiry(
  factoryId: string,
  body: {
    title?: string;
    materialTypeId: string;
    materialName?: string;
    specification?: string;
    quantity: number;
    unit: string;
    inquiryDate: string;
    requiredDate?: string;
    remark?: string;
    sourcePurchaseOrderId?: string;
  },
): Promise<ApiResponse<InquiryQuote>> {
  return post<InquiryQuote>(`/${factoryId}/purchase/inquiry-quotes`, body);
}

export function listInquiries(
  factoryId: string,
  params: { page?: number; size?: number } = {},
): Promise<ApiResponse<PageResponse<InquiryQuote>>> {
  return get<PageResponse<InquiryQuote>>(`/${factoryId}/purchase/inquiry-quotes`, {
    params: { page: params.page ?? 1, size: params.size ?? 20 },
  });
}

export function getInquiry(
  factoryId: string,
  inquiryId: string,
): Promise<ApiResponse<InquiryQuote>> {
  return get<InquiryQuote>(`/${factoryId}/purchase/inquiry-quotes/${inquiryId}`);
}

export function submitInquiry(
  factoryId: string,
  inquiryId: string,
): Promise<ApiResponse<InquiryQuote>> {
  return post<InquiryQuote>(
    `/${factoryId}/purchase/inquiry-quotes/${inquiryId}/submit`,
  );
}

export function cancelInquiry(
  factoryId: string,
  inquiryId: string,
): Promise<ApiResponse<InquiryQuote>> {
  return post<InquiryQuote>(
    `/${factoryId}/purchase/inquiry-quotes/${inquiryId}/cancel`,
  );
}

// ============================================================================
// 供应商报价
// ============================================================================

export function addSupplierPrice(
  factoryId: string,
  inquiryId: string,
  body: {
    supplierId: string;
    unitPrice: number;
    taxRate?: number;
    currency?: string;
    quotedUnit?: string;
    specification?: string;
    minOrderQuantity?: number;
    priceEffectiveDate?: string;
    validUntil?: string;
    deliveryDays?: number;
    remark?: string;
  },
): Promise<ApiResponse<InquiryQuoteSupplierPrice>> {
  return post<InquiryQuoteSupplierPrice>(
    `/${factoryId}/purchase/inquiry-quotes/${inquiryId}/supplier-prices`,
    body,
  );
}

export function listSupplierPrices(
  factoryId: string,
  inquiryId: string,
): Promise<ApiResponse<InquiryQuoteSupplierPrice[]>> {
  return get<InquiryQuoteSupplierPrice[]>(
    `/${factoryId}/purchase/inquiry-quotes/${inquiryId}/supplier-prices`,
  );
}

/** 比价: 只有可比且在有效期内的报价有名次; 不可比的给原因。 */
export function getQuoteComparison(
  factoryId: string,
  inquiryId: string,
): Promise<ApiResponse<QuoteComparison>> {
  return get<QuoteComparison>(
    `/${factoryId}/purchase/inquiry-quotes/${inquiryId}/comparison`,
  );
}

/** 报价价格对 BOM 的影响估算 (只读, 不回写 BOM 与历史成本)。 */
export function getQuotePriceImpact(
  factoryId: string,
  inquiryId: string,
  priceId: string,
): Promise<ApiResponse<QuotePriceImpact>> {
  return get<QuotePriceImpact>(
    `/${factoryId}/purchase/inquiry-quotes/${inquiryId}/supplier-prices/${priceId}/bom-impact`,
  );
}

// ============================================================================
// 选定 + 转化采购单 (防呆 R4: idempotent, 409 已生成 PO)
// ============================================================================

export function selectAndConvert(
  factoryId: string,
  inquiryId: string,
  body: {
    selectedSupplierId: string;
    expectedDeliveryDate?: string;
    remark?: string;
  },
): Promise<ApiResponse<{ id: string; orderNumber: string }>> {
  return post(
    `/${factoryId}/purchase/inquiry-quotes/${inquiryId}/select-and-convert`,
    body,
  );
}
