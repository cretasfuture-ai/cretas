import { canonicalUnitCode, canonicalUnitCodeKeepingCount } from '@/utils/unitPricing';
export type TransferItemType = 'RAW_MATERIAL' | 'FINISHED_GOODS' | 'PACKAGING_MATERIAL';
export type TransferType = 'HQ_TO_BRANCH' | 'BRANCH_TO_BRANCH' | 'BRANCH_TO_HQ' | 'WAREHOUSE_TO_WAREHOUSE';

export const TRANSFER_TYPE_OPTIONS: ReadonlyArray<{ value: TransferType; label: string }> = [
  { value: 'HQ_TO_BRANCH', label: '总部调往分部' },
  { value: 'BRANCH_TO_BRANCH', label: '分部之间调拨' },
  { value: 'BRANCH_TO_HQ', label: '分部退回总部' },
  { value: 'WAREHOUSE_TO_WAREHOUSE', label: '同一工厂仓库调拨' },
];

export interface TransferCreateRow {
  itemType: TransferItemType;
  selectionCategory: string;
  materialCategoryFilter?: string | null;
  selectedItemId: string;
  materialTypeId?: string;
  productTypeId?: string;
  itemName: string;
  quantity?: number;
  unit: string;
  materialPackagingSpecId?: string;
  packagingOptions?: Array<{
    id: string;
    name: string;
    packageUnit: string;
    baseUnit: string;
    conversionFactor: number;
    defaultSpec?: boolean;
  }>;
  _inventoryUnit?: string;
  _packageFactor?: number;
  unitPrice?: number;
  remark?: string;
  _currentStock?: number | string | null;
}

export interface TransferSelectableItem {
  id: string;
  name: string;
  code?: string;
  unit: string;
  currentStock: number;
  unitPrice?: number;
  category?: string;
  mainCategory?: string;
}

export interface TransferCategoryOption {
  value: string;
  label: string;
  itemType: TransferItemType;
  materialCategory?: string | null;
}

export const FINISHED_GOODS_CATEGORY = 'FINISHED_GOODS';
export const UNCLASSIFIED_MATERIAL_CATEGORY = 'MATERIAL:__UNCLASSIFIED__';

export interface FinishedGoodsInventoryBatch {
  productTypeId?: string | null;
  productName?: string | null;
  unit?: string | null;
  status?: string | null;
  availableQuantity?: number | string | null;
  producedQuantity?: number | string | null;
  shippedQuantity?: number | string | null;
  reservedQuantity?: number | string | null;
  unitPrice?: number | string | null;
}

export interface MaterialInventoryBatch {
  materialTypeId?: string | null;
  materialName?: string | null;
  materialCode?: string | null;
  materialCategory?: string | null;
  materialMainCategory?: string | null;
  quantityUnit?: string | null;
  unit?: string | null;
  currentQuantity?: number | string | null;
  status?: string | null;
  unitPrice?: number | string | null;
  /** 归属: CUSTOMER_OWNED = 客供料(来料加工), 手动调拨不可动 —— 见 NON_TRANSFERABLE 注释。 */
  ownership?: string | null;
  /** 来源单据类型: PRODUCTION_BATCH = 在制半成品工件, 不是真实原料库存。 */
  sourceDocType?: string | null;
}

/**
 * 🔴 2026-08-28 六膳门生产实测: 手动新建调拨单选「汉堡排原料 YL068」, 页面
 * 「现有库存 100,000 kg」, 提交却被后端拒绝「可用 0 kg, 请减少调拨数量或选择有库存的调出仓库」。
 *
 * 成因是**同一屏两套规则**:
 *   现有库存/物料下拉 ← `/inventory/by-warehouse` → `findByFactoryIdAndWarehouseId`(无任何归属过滤)
 *   提交闸           ← `findAvailableBatchesFEFOByWarehouse` → SQL 里排除了
 *                       `ownership <> COMPANY_OWNED` 与 `sourceDocType = 'PRODUCTION_BATCH'`
 *
 * ⛔ 后端那两条排除是**对的, 不要去掉**:
 *   - 客供料受 `ProductionInventoryOwnershipGuard` 的 fail-closed 归属契约约束, 只能由
 *     customer/销售单/行都对得上的生产计划经「按生产计划生成领料单」搬运
 *     (`FactoryMaterialRequisitionServiceImpl#relocatePickedMaterialToWorkshop`)。
 *     手动调拨没有这些上下文, 而调拨建目的批次时**根本不搬运 ownership**
 *     (TransferServiceImpl 里 ownership 出现 0 次) ⇒ 放开就等于把客供料洗成自有库存。
 *   - PRODUCTION_BATCH 是在制半成品工件, 误拣会把半成品当原料吃掉 (labor 双计 → 负库存)。
 *
 * ⇒ 该修的是**消费端**: 不可调拨的批次不该出现在调拨下拉/现有库存里。
 *
 * 生产实测受影响面 (2026-08-28): 主仓 1 条客供料 / 生产仓 **16 条** PRODUCTION_BATCH。
 * 只修客供料会留下 16 条一模一样的坑, 所以两类一起排除。
 */
const NON_TRANSFERABLE_OWNERSHIP = 'CUSTOMER_OWNED';
const NON_TRANSFERABLE_SOURCE_DOC_TYPE = 'PRODUCTION_BATCH';

/** 这个批次能不能被**手动调拨**动 —— 口径必须与后端 findAvailableBatchesFEFOByWarehouse 一致。 */
export function isManuallyTransferableBatch(batch: MaterialInventoryBatch): boolean {
  const ownership = String(batch.ownership ?? '').toUpperCase();
  const sourceDocType = String(batch.sourceDocType ?? '').toUpperCase();
  if (ownership === NON_TRANSFERABLE_OWNERSHIP) return false;
  if (sourceDocType === NON_TRANSFERABLE_SOURCE_DOC_TYPE) return false;
  return true;
}

function finiteNumber(value: unknown): number {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

function finishedGoodsAvailable(batch: FinishedGoodsInventoryBatch): number {
  if (batch.availableQuantity !== null && batch.availableQuantity !== undefined) {
    return finiteNumber(batch.availableQuantity);
  }
  return finiteNumber(batch.producedQuantity)
    - finiteNumber(batch.shippedQuantity)
    - finiteNumber(batch.reservedQuantity);
}

/**
 * 把分仓库存的成品批次聚合为调拨产品选项。库存仍按 canonical unit 计算，
 * UI 仅在渲染时调用 displayUnit，避免把中文展示值写回 payload/DB。
 */
export function aggregateFinishedGoodsOptions(
  batches: FinishedGoodsInventoryBatch[],
): TransferSelectableItem[] {
  const grouped = new Map<string, TransferSelectableItem>();
  for (const batch of batches) {
    const productTypeId = String(batch.productTypeId ?? '').trim();
    const unit = canonicalUnitCode(batch.unit);
    const available = finishedGoodsAvailable(batch);
    if (!productTypeId || !unit || available <= 0 || String(batch.status ?? '').toUpperCase() !== 'AVAILABLE') {
      continue;
    }
    const existing = grouped.get(productTypeId);
    if (existing) {
      // 同一 SKU 的库存必须同单位；异常混合单位不跨单位相加，避免虚大可调量。
      if (existing.unit === unit) existing.currentStock += available;
      continue;
    }
    const unitPrice = finiteNumber(batch.unitPrice);
    grouped.set(productTypeId, {
      id: productTypeId,
      name: String(batch.productName || productTypeId),
      code: productTypeId,
      unit,
      currentStock: available,
      unitPrice: unitPrice > 0 ? unitPrice : undefined,
    });
  }
  return Array.from(grouped.values()).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
}

export function aggregateMaterialInventoryOptions(
  batches: MaterialInventoryBatch[],
): TransferSelectableItem[] {
  const grouped = new Map<string, TransferSelectableItem>();
  for (const batch of batches) {
    const materialTypeId = String(batch.materialTypeId ?? '').trim();
    const unit = canonicalUnitCode(batch.quantityUnit || batch.unit);
    const available = finiteNumber(batch.currentQuantity);
    const status = String(batch.status ?? '').toUpperCase();
    if (!materialTypeId || !unit || available <= 0 || ['USED_UP', 'DEPLETED', 'EXPIRED', 'SCRAPPED'].includes(status)) {
      continue;
    }
    // 不可手动调拨的批次不进下拉, 也不计入「现有库存」—— 否则用户看到有货、选了必被拒。
    if (!isManuallyTransferableBatch(batch)) {
      continue;
    }
    const existing = grouped.get(materialTypeId);
    if (existing) {
      if (existing.unit === unit) existing.currentStock += available;
      continue;
    }
    const unitPrice = finiteNumber(batch.unitPrice);
    grouped.set(materialTypeId, {
      id: materialTypeId,
      name: String(batch.materialName || materialTypeId),
      code: String(batch.materialCode || ''),
      unit,
      currentStock: available,
      unitPrice: unitPrice > 0 ? unitPrice : undefined,
      category: String(batch.materialCategory || ''),
      mainCategory: String(batch.materialMainCategory || ''),
    });
  }
  return Array.from(grouped.values()).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
}

/**
 * 调拨页展示的基本类型固定使用主数据三大类，而不是把 category 小类原样铺开。
 * 三大类即使当前仓没有库存也展示，避免同一页面随库存偶然变成“原料/主材”两项；
 * 选中后物料下拉只列该大类的真实可调库存。
 */
export function buildTransferCategoryOptions(
  materialOptions: TransferSelectableItem[],
  finishedGoodsOptions: TransferSelectableItem[],
): TransferCategoryOption[] {
  const result: TransferCategoryOption[] = materialOptions.length > 0 ? [
    { value: 'MATERIAL:原料', label: '原料', itemType: 'RAW_MATERIAL', materialCategory: '原料' },
    { value: 'MATERIAL:辅料', label: '辅料', itemType: 'RAW_MATERIAL', materialCategory: '辅料' },
    { value: 'MATERIAL:包材', label: '包材', itemType: 'PACKAGING_MATERIAL', materialCategory: '包材' },
  ] : [];

  if (materialOptions.some((option) => !String(option.mainCategory || '').trim())) {
    result.push({ value: UNCLASSIFIED_MATERIAL_CATEGORY, label: '未分类原料（历史）', itemType: 'RAW_MATERIAL', materialCategory: null });
  }
  if (finishedGoodsOptions.length > 0) {
    result.push({
      value: FINISHED_GOODS_CATEGORY,
      label: '成品/菜品',
      itemType: 'FINISHED_GOODS',
    });
  }
  return result;
}

export function applyTransferCategory(
  row: TransferCreateRow,
  option: TransferCategoryOption,
): void {
  row.selectionCategory = option.value;
  row.itemType = option.itemType;
  row.materialCategoryFilter = option.materialCategory;
  resetSelectedOption(row);
}

export function optionsForTransferCategory(
  row: TransferCreateRow,
  materialOptions: TransferSelectableItem[],
  finishedGoodsOptions: TransferSelectableItem[],
): TransferSelectableItem[] {
  if (row.itemType === 'FINISHED_GOODS') return finishedGoodsOptions;
  if (row.materialCategoryFilter === null) {
    return materialOptions.filter((option) => !String(option.category || '').trim());
  }
  if (row.materialCategoryFilter === undefined) return [];
  return materialOptions.filter(
    (option) => String(option.mainCategory || '').trim() === row.materialCategoryFilter,
  );
}

export function applySelectedOption(row: TransferCreateRow, option: TransferSelectableItem): void {
  row.selectedItemId = option.id;
  row.itemName = option.name;
  row.unit = canonicalUnitCode(option.unit);
  row._inventoryUnit = canonicalUnitCode(option.unit);
  row._packageFactor = 1;
  row.materialPackagingSpecId = undefined;
  row.packagingOptions = [];
  row.unitPrice = option.unitPrice;
  row._currentStock = option.currentStock;
  if (row.itemType === 'FINISHED_GOODS') {
    row.productTypeId = option.id;
    row.materialTypeId = undefined;
  } else {
    row.materialTypeId = option.id;
    row.productTypeId = undefined;
  }
}

export function resetSelectedOption(row: TransferCreateRow): void {
  row.selectedItemId = '';
  row.materialTypeId = undefined;
  row.productTypeId = undefined;
  row.itemName = '';
  row.quantity = undefined;
  row.unit = '';
  row._inventoryUnit = '';
  row._packageFactor = 1;
  row.materialPackagingSpecId = undefined;
  row.packagingOptions = [];
  row._currentStock = null;
}

/**
 * 一行的物料身份 —— 原料/包材按 materialTypeId, 成品按 productTypeId。未选物料返回 null。
 */
export function transferRowIdentity(row: TransferCreateRow): string | null {
  const finishedGoods = row.itemType === 'FINISHED_GOODS';
  const id = String((finishedGoods ? row.productTypeId : row.materialTypeId) || row.selectedItemId || '').trim();
  if (!id) return null;
  return `${finishedGoods ? 'P' : 'M'}:${id}`;
}

export interface DuplicateTransferRowGroup {
  identity: string;
  name: string;
  rows: TransferCreateRow[];
  /** 合计基本量 = Σ(行数量 × 包装换算数), 与 _currentStock 同口径可直接比较。 */
  totalBaseQuantity: number;
  baseUnit: string;
}

/**
 * 找出同一物料被写成多行的那一组 (没有则 null)。
 *
 * <p>2026-08-09 六膳门 prod 事故 TRF-20260809-1790: 「金蒜牛排调味料 滚揉用」写了两行各
 * 1000kg, 而主仓该原料只有 1000kg。建单逐行 `quantity > _currentStock` 校验、后端逐行
 * `ensureCreateQuantityAvailable`、详情页逐行 `isStockShortage` —— 三处都按行比, 每行
 * 1000 ≤ 1000 全部合法, 没有一处把两行加起来看。审批通过后点「确认调拨入库」才由
 * `deductSourceInventory` 抛 "缺少 1000", 而那时明细已不可编辑, 只能取消重建。
 */
export function findDuplicateTransferRow(rows: TransferCreateRow[]): DuplicateTransferRowGroup | null {
  const groups = new Map<string, TransferCreateRow[]>();
  for (const row of rows) {
    const identity = transferRowIdentity(row);
    if (!identity) continue; // 未选物料的空行交给"请为每行选择物料"提示, 这里不抢答
    const bucket = groups.get(identity);
    if (bucket) bucket.push(row);
    else groups.set(identity, [row]);
  }
  for (const [identity, bucket] of groups) {
    if (bucket.length < 2) continue;
    const first = bucket[0];
    return {
      identity,
      name: first.itemName || identity.slice(2),
      rows: bucket,
      totalBaseQuantity: bucket.reduce(
        (sum, row) => sum + Number(row.quantity || 0) * Number(row._packageFactor || 1), 0),
      baseUnit: first._inventoryUnit || first.unit || '',
    };
  }
  return null;
}

/** 详情页 (后端返回) 的明细行形状 —— 与建单表单行不同, 但物料身份的定义必须是同一条。 */
export interface TransferDetailItemRow {
  itemType?: string | null;
  materialTypeId?: string | null;
  productTypeId?: string | null;
  quantity?: number | string | null;
  currentStock?: number | string | null;
}

export function transferDetailRowIdentity(row: TransferDetailItemRow): string | null {
  const finishedGoods = row.itemType === 'FINISHED_GOODS';
  const id = String((finishedGoods ? row.productTypeId : row.materialTypeId) ?? '').trim();
  return id ? `${finishedGoods ? 'P' : 'M'}:${id}` : null;
}

/**
 * 本单对每个物料的<b>合计</b>需求量 (基本单位)。
 *
 * <p>详情页的 `currentStock` 是<b>按物料</b>查的实时可用量 —— 同一物料的两行会各自拿到同一个
 * 数字。逐行比 "1000 ≤ 1000" 两行都判"够", 但它们扣的是同一批库存。必须先按物料加总。
 */
export function aggregateTransferDemand(rows: TransferDetailItemRow[]): Map<string, number> {
  const totals = new Map<string, number>();
  for (const row of rows) {
    const identity = transferDetailRowIdentity(row);
    if (!identity) continue;
    const qty = Number(row.quantity);
    if (!Number.isFinite(qty)) continue;
    totals.set(identity, (totals.get(identity) ?? 0) + qty);
  }
  return totals;
}

/** 该行是否缺货 —— 比的是"该物料在本单的合计需求", 不是本行数量。 */
export function isTransferRowShortage(
  row: TransferDetailItemRow,
  demand: Map<string, number>,
): boolean {
  if (row.currentStock === null || row.currentStock === undefined) return false;
  if (row.quantity === null || row.quantity === undefined) return false;
  const stock = Number(row.currentStock);
  const quantity = Number(row.quantity);
  if (!Number.isFinite(stock) || !Number.isFinite(quantity)) return false;
  // 无重复行时合计恒等于本行数量, 与旧的逐行行为一致。
  return stock < (demand.get(transferDetailRowIdentity(row) ?? '') ?? quantity);
}

export function toTransferItemPayload(row: TransferCreateRow) {
  const identity = row.itemType === 'FINISHED_GOODS'
    ? { productTypeId: row.productTypeId || row.selectedItemId }
    : { materialTypeId: row.materialTypeId || row.selectedItemId };
  return {
    itemType: row.itemType,
    ...identity,
    itemName: row.itemName,
    quantity: row.quantity,
    unit: canonicalUnitCodeKeepingCount(row.unit),
    ...(row.materialPackagingSpecId
      ? { materialPackagingSpecId: row.materialPackagingSpecId }
      : {}),
    unitPrice: row.unitPrice == null
      ? undefined
      : row.unitPrice * Number(row._packageFactor || 1),
    remark: row.remark || undefined,
  };
}
