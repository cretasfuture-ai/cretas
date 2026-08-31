export type SalesProcessingMode = 'STANDARD_SALE' | 'TOLL_PROCESSING';
export type MaterialSupplyMode = 'CUSTOMER_SUPPLIED' | 'FACTORY_SUPPLIED';
export type CustomerStockFulfillmentMode = 'ORDER_DRIVEN' | 'PRESTOCKED';

export interface SalesOrderSupplyContract {
  processingMode: SalesProcessingMode | '';
  materialSupplyMode: MaterialSupplyMode | '';
  customerStockFulfillmentMode: CustomerStockFulfillmentMode;
}

export interface CustomerSuppliedMaterialRequirement {
  id?: string;
  salesOrderItemId?: number;
  materialTypeId: string;
  materialName: string;
  expectedQuantity: number;
  unit: string;
  expectedArrivalAt: string;
  targetWarehouseId: string;
  targetWarehouseName?: string;
  receivedQuantity?: number;
  remainingQuantity?: number;
  status?: string;
  /** Optional read-only category supplied by detail APIs; never sent in create/update payloads. */
  category?: string;
  /** Authoritative read-only three-value family from RawMaterialType.mainCategory. */
  mainCategory?: string;
}

export type CustomerMaterialCategory = 'RAW' | 'AUXILIARY' | 'PACKAGING';

const RAW_CATEGORY_ALIASES = new Set([
  '原料', '主材', '蔬菜', '肉类', '水产', '米面', '干货', '禽类', '海鲜', '水产类',
  '海水鱼', '淡水鱼', '虾类', '贝类', '水果', '粉类', '油类', 'RAW', 'RAW_MATERIAL',
]);
const AUXILIARY_CATEGORY_ALIASES = new Set([
  '辅料', '调味料', '调料', '添加剂', '辅材', '调味品', 'AUXILIARY', 'SEASONING',
]);
const PACKAGING_CATEGORY_ALIASES = new Set(['包材', '包辅材', 'PACKAGING', 'PACKAGE']);

function categoryFallbackOf(category: unknown): CustomerMaterialCategory | null {
  const raw = String(category ?? '').trim();
  const key = /[A-Za-z_]/.test(raw) ? raw.toUpperCase() : raw;
  if (RAW_CATEGORY_ALIASES.has(key)) return 'RAW';
  if (AUXILIARY_CATEGORY_ALIASES.has(key)) return 'AUXILIARY';
  if (PACKAGING_CATEGORY_ALIASES.has(key)) return 'PACKAGING';
  return null;
}

/** Prefer authoritative mainCategory; fall back only for cached legacy DTOs that omit it. */
export function customerMaterialCategoryOf(
  mainCategory: unknown,
  legacyCategory?: unknown,
): CustomerMaterialCategory | null {
  const raw = String(mainCategory ?? '').trim();
  const code = raw.toUpperCase();
  if (code === 'RAW' || raw === '原料') return 'RAW';
  if (code === 'AUXILIARY' || raw === '辅料') return 'AUXILIARY';
  if (code === 'PACKAGING' || raw === '包材') return 'PACKAGING';
  return categoryFallbackOf(legacyCategory);
}

export function customerMaterialCategoryLabel(mainCategory: unknown, legacyCategory?: unknown): string {
  const mapped = customerMaterialCategoryOf(mainCategory, legacyCategory);
  return mapped === 'RAW' ? '原料' : mapped === 'AUXILIARY' ? '辅料' : mapped === 'PACKAGING' ? '包材' : '未知分类';
}

export const SALES_PROCESSING_MODE_OPTIONS: ReadonlyArray<{
  value: SalesProcessingMode;
  label: string;
}> = [
  { value: 'STANDARD_SALE', label: '普通销售' },
  { value: 'TOLL_PROCESSING', label: '代加工' },
];

export const MATERIAL_SUPPLY_MODE_OPTIONS: ReadonlyArray<{
  value: MaterialSupplyMode;
  label: string;
}> = [
  { value: 'FACTORY_SUPPLIED', label: '工厂备料' },
  { value: 'CUSTOMER_SUPPLIED', label: '客户自带物料' },
];

export const CUSTOMER_STOCK_FULFILLMENT_OPTIONS: ReadonlyArray<{
  value: CustomerStockFulfillmentMode;
  label: string;
  description: string;
}> = [
  {
    value: 'ORDER_DRIVEN',
    label: '本订单后续送料',
    description: '销售登记预计来料，仓储按本订单收货后再生产。',
  },
  {
    value: 'PRESTOCKED',
    label: '使用客户已有库存',
    description: '使用此前已为该客户生产入库、尚未绑定销售订单的成品。',
  },
];

const processingModeLabels: Record<SalesProcessingMode, string> = {
  STANDARD_SALE: '普通销售',
  TOLL_PROCESSING: '代加工',
};

const materialSupplyModeLabels: Record<MaterialSupplyMode, string> = {
  FACTORY_SUPPLIED: '工厂备料',
  CUSTOMER_SUPPLIED: '客户自带物料',
};

export function newSalesOrderSupplyContract(): SalesOrderSupplyContract {
  return {
    processingMode: 'STANDARD_SALE',
    materialSupplyMode: 'FACTORY_SUPPLIED',
    customerStockFulfillmentMode: 'ORDER_DRIVEN',
  };
}

export function processingModeLabel(value: unknown): string {
  return processingModeLabels[value as SalesProcessingMode] || '未设置（历史数据）';
}

export function materialSupplyModeLabel(value: unknown): string {
  return materialSupplyModeLabels[value as MaterialSupplyMode] || '未设置（历史数据）';
}

export function customerMaterialReceivingStatusLabel(value: unknown): string {
  const labels: Record<string, string> = {
    PENDING: '待收货',
    PENDING_RECEIPT: '待收货',
    RECEIVING: '收货中',
    PARTIALLY_RECEIVED: '部分收货',
    RECEIVED: '已收货',
    CONFIRMED: '已确认入库',
    POSTED: '已入库',
  };
  return labels[String(value || '')] || '请前往仓储查看';
}

export function supplyContractValidationError(contract: SalesOrderSupplyContract): string | null {
  if (!contract.processingMode) return '请选择加工方式';
  if (!contract.materialSupplyMode) return '请选择物料供应方式';
  if (contract.processingMode === 'STANDARD_SALE'
    && contract.materialSupplyMode === 'CUSTOMER_SUPPLIED') {
    return '普通销售不能选择客户自带物料；请改为代加工，或选择工厂备料';
  }
  if ((contract.processingMode !== 'TOLL_PROCESSING'
    || contract.materialSupplyMode !== 'CUSTOMER_SUPPLIED')
    && contract.customerStockFulfillmentMode === 'PRESTOCKED') {
    return '使用客户已有库存只适用于“代加工 + 客户自带物料”';
  }
  return null;
}

export function suppliedMaterialsValidationError(
  contract: SalesOrderSupplyContract,
  requirements: CustomerSuppliedMaterialRequirement[],
): string | null {
  if (contract.processingMode !== 'TOLL_PROCESSING'
    || contract.materialSupplyMode !== 'CUSTOMER_SUPPLIED'
    || contract.customerStockFulfillmentMode === 'PRESTOCKED') {
    return null;
  }
  if (requirements.length === 0) return '请至少添加一项客户自带物料需求';
  const seen = new Set<string>();
  for (const row of requirements) {
    if (!row.materialTypeId) return '请选择全部客户自带物料';
    if (seen.has(row.materialTypeId)) return `客户自带物料“${row.materialName || row.materialTypeId}”不能重复添加`;
    seen.add(row.materialTypeId);
    if (!(Number(row.expectedQuantity) > 0)) return `“${row.materialName || '客户自带物料'}”预计数量必须大于 0`;
    if (!row.unit) return `“${row.materialName || '客户自带物料'}”缺少库存计量单位`;
    if (!row.expectedArrivalAt) return `请填写“${row.materialName || '客户自带物料'}”预计到货时间`;
    if (!row.targetWarehouseId) return `请选择“${row.materialName || '客户自带物料'}”目标仓库`;
  }
  return null;
}

export function warehouseReceivingRoute(order: {
  id?: unknown;
  orderNumber?: unknown;
}): { path: string; query: Record<string, string> } {
  return {
    path: '/warehouse/materials',
    query: {
      view: 'receiving',
      sourceType: 'SALES_ORDER_CUSTOMER_SUPPLIED',
      salesOrderId: String(order.id || ''),
      salesOrderNo: String(order.orderNumber || ''),
    },
  };
}
