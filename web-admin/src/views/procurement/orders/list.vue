<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import request, { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, ChatDotRound, Download, QuestionFilled } from '@element-plus/icons-vue';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import AttachmentDropZone from '@/components/attachment/AttachmentDropZone.vue';
import { PURCHASE_ORDER_CONFIG } from '@/components/ai-entry/types';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import { formatAmount } from '@/utils/tableFormatters';
import CanvasDynamicFields from '@/components/canvas/CanvasDynamicFields.vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';
import {
  RowActionMenu,
  TableFooter,
  ViewModeSwitcher,
  GridView,
  KanbanView,
  TimelinePlaceholder,
  CalendarPlaceholder,
  ListColumnSelector,
  useBusinessTableColumns,
} from '@/components/list';
// Sprint 6 W3-A — inline 3-chip link counter (文件 / 图片 / 合同).
import LinkChipCell from '@/components/list/LinkChipCell.vue';
import { useLinkChipCounts } from '@/composables/useLinkChipCounts';
import CreateReturnOrderDialog from '@/components/dialog/CreateReturnOrderDialog.vue';
import { copyPurchaseOrder } from '@/api/orderCopy';
import { resolveReferenceByName } from '@/utils/referenceResolver';
// M-10 (2026-08-20 手动测试台账): 精确状态文案 + 展示归类, 与后端
// PurchaseOrderStatus 枚举一一对应 (11 值), TS Record 完全映射防漂移.
import {
  ALL_PURCHASE_ORDER_STATUSES,
  PURCHASE_ORDER_STATUS_LABELS,
  PURCHASE_ORDER_STATUS_TIER_OPTIONS,
  getPurchaseOrderStatusLabel,
  getPurchaseOrderStatusTier,
} from '@/constants/purchaseOrderStatus';
// PR #878 (#860 follow-up) — 转发 / 分享链接 dialog.
import ForwardShareDialog from '@/components/dialog/ForwardShareDialog.vue';
import type { ViewMode } from '@/types/viewMode';
import { computeRowActions } from '@/composables/useRowActions';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';
import { canonicalUnitCode, canonicalUnitCodeKeepingCount, displayUnit, pricingAmountPreview } from '@/utils/unitPricing';
import { enumLabel } from '@/utils/enumDisplay';
// R-4 (2026-08-20，HANDOFF 台账): 供应商下拉支持拼音/首字母搜索 (bjfx / beijing / 北京 都要命中).
import { usePinyinFilter } from '@/utils/pinyinSearch';
import { canDownloadSupplyOrder, canSubmitPurchaseOrder, purchaseOrderMoreActions } from './purchaseOrderActionIa';
import { resolveSupplierMaterialRelations } from './purchaseOrderEditPrefill';
import {
  listSupplierMaterials,
  listSupplierPurchaseSpecs,
  type SupplierMaterialRelation,
  type SupplierPurchaseSpec,
} from '@/api/supplierManagement';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const { goCreate } = useCreateAndReturn();

const canViewPrice = computed(() => permissionStore.canViewPrice);
const PROCUREMENT_DEFAULT_COLUMNS = ['purchaseType', 'orderDate', 'totalAmount', 'attachments'];
const {
  columns: procurementColumnOptions,
  visibleKeys: procurementVisibleColumns,
  isVisible: procurementColumnVisible,
  reset: resetProcurementColumns,
} = useBusinessTableColumns({
  storageKey: 'cretas:purchase-orders:visible-columns:v1',
  columns: () => [
    { key: 'purchaseType', label: '类型' },
    { key: 'orderDate', label: '下单日期' },
    ...(canViewPrice.value ? [{ key: 'totalAmount', label: '总金额' }] : []),
    { key: 'attachments', label: '附件' },
  ],
  defaults: PROCUREMENT_DEFAULT_COLUMNS,
  max: 4,
});

// U-VIEW-1 (Sprint 4 Wave 2 Chat L) — view-mode switcher (5 modes).
const viewMode = ref<ViewMode>('table');

const factoryToday = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
function rowActionsFor(row: TableRow) {
  return computeRowActions(
    'purchaseOrder',
    { status: String(row.status || ''), id: String(row.id || ''), canEdit: canWrite.value },
    { canViewPrice: canViewPrice.value }
  );
}
function moreRowActionsFor(row: TableRow) {
  return purchaseOrderMoreActions(rowActionsFor(row));
}
// 退货 dialog state (#860 follow-up — wires existing backend ReturnOrderController).
const returnDialogVisible = ref(false);
const returnDialogRow = ref<TableRow | null>(null);
const returnDialogItems = computed(() => {
  const row = returnDialogRow.value;
  if (!row || !Array.isArray(row.items)) return [];
  return (row.items as TableRow[]).map((it) => ({
    id: it.id,
    materialTypeId: it.materialTypeId ? String(it.materialTypeId) : null,
    productTypeId: null as string | null,
    itemName: String(it.materialName || it.itemName || '-'),
    unitPrice: Number(it.unitPrice) || 0,
    // For procurement, cap return at ordered quantity (no per-line received-qty
    // hydration on list endpoint; service-side over-return guard will reject).
    maxQuantity: Number(it.quantity) || 0,
    batchNumber: it.batchNumber ? String(it.batchNumber) : null,
  }));
});
function openReturnDialog(row: TableRow): void {
  if (!Array.isArray(row.items) || row.items.length === 0) {
    ElMessage.warning('采购单无明细, 无法发起退货. 请打开订单详情确认.');
    return;
  }
  returnDialogRow.value = row;
  returnDialogVisible.value = true;
}
function handleReturnSuccess(): void {
  returnDialogVisible.value = false;
  returnDialogRow.value = null;
  void loadData();
}

function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': goDetail(String(row.id)); break;
    case 'edit':
      router.push({ path: '/procurement/orders', query: { edit: String(row.id) } });
      break;
    case 'submit': handleAction(String(row.id), 'submit'); break;
    case 'cancel': handleAction(String(row.id), 'cancel'); break;
    case 'print-pdf': handleDownloadPdf(row); break;
    case 'copy': void handleCopyOrder(row); break;
    case 'return': openReturnDialog(row); break;
    case 'edit-price':
      ElMessage.info(`请在采购单 ${row.orderNumber || row.id} 详情页维护行项目单价`);
      router.push({ path: `/procurement/orders/${row.id}`, query: { action: 'price' } });
      break;
    case 'view-price-history':
      ElMessage.info(`请在采购单 ${row.orderNumber || row.id} 详情页查看价格上下文`);
      router.push(`/procurement/orders/${row.id}`);
      break;
    case 'forward':
      forwardEntityId.value = String(row.id || '');
      forwardEntityLabel.value = String(row.orderNumber || row.id || '');
      forwardDialogVisible.value = true;
      break;
    case 'undo-approval':
      ElMessage({
        message: `采购单 ${row.orderNumber || row.id} 暂不支持列表撤销审批，请进入详情页按当前状态处理`,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
      break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
  }
}

// #860 follow-up (2026-05-18): 复制采购订单 → 新草稿. 后端复用供应商/品项/价格,
// 不复制审批/到货状态. 错误由 axios interceptor sticky toast 显示, 不要 try/catch 吞.
async function handleCopyOrder(row: TableRow): Promise<void> {
  const orderNumber = String(row.orderNumber || row.id || '');
  try {
    await ElMessageBox.confirm(
      `确认复制采购单 ${orderNumber} 为新草稿？复制内容包含供应商、品项和价格，不复制审批/到货状态。`,
      '复制采购单',
      { confirmButtonText: '复制', cancelButtonText: '取消', type: 'info' }
    );
  } catch {
    return; // 用户取消
  }
  const res = await copyPurchaseOrder(factoryId.value, String(row.id));
  if (res?.success && res.data) {
    ElMessage.success(`已复制为 ${res.data.orderNumber}`);
    await loadData();
  }
}
function openAiForRow(row: TableRow) {
  console.info('[RowAction AI]', { entityType: 'purchaseOrder', entityId: row.id, orderNumber: row.orderNumber });
  aiEntryVisible.value = true;
}

// U-NAV-1 业务流程图导航 (Sprint 2 Track G + FU Chat 3 bucket-filter)
const { stats: workflowStats, loading: workflowLoading } = useWorkflowStats(factoryId, 'purchase');

// Sprint 6 W3-A — inline 3-chip 链接计数 (文件 / 图片 / 合同).
const { fetchLinkChipCounts, countsFor: linkCountsFor } =
  useLinkChipCounts(factoryId, 'PURCHASE_ORDER');

function handleWorkflowNodeClick(nodeId: string) {
  const primary = getBucketPrimaryStatus('purchase', nodeId);
  if (!primary) return;
  statusFilter.value = primary;
  pagination.value.page = 1;
  loadData();
  ElMessage.success(`已切到 "${getBucketLabel('purchase', nodeId)}" (显示状态: ${primary}). bucket 含多个状态, 想看其他请打开状态下拉切换.`);
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const statusFilter = ref('');
const searchKeyword = ref('');
const dateRange = ref<[string, string] | null>(null);
const dialogVisible = ref(false);
interface AttachmentDropZoneExposed {
  uploadQueued: (entityId?: string) => Promise<{ succeeded: number; failed: number }>;
  clear: () => void;
}
const createAttachmentRef = ref<AttachmentDropZoneExposed>();
const createdOrderId = ref('');
const attachmentQueue = ref({ total: 0, pending: 0, failed: 0 });

// U-FOOTER-1: sticky summary stats
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: statusFilter.value ? { status: statusFilter.value } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('purchaseOrder', summaryRequest);

interface ProcurementOrderItem {
  supplierMaterialId?: string;
  purchasePackagingSpecId?: string | null;
  materialPackagingSpecId?: string | null;
  materialTypeId: string;
  quantity: number;
  unit: string;
  quantityUnit: string;
  unitPrice: number | null;
  priceUnit: string;
  priceSource?: string | null;
  lineAmount?: number | null;
  convertedPricingQuantity?: number | null;
  taxRate?: number | string | null;
  /** 行级合同号(如 SAN-16572); 为空时收货侧回落到单头框架合同号 */
  contractNumber?: string;
}

const commonTaxRateOptions = [
  { label: '0%', value: 0 },
  { label: '9%', value: 9 },
  { label: '13%', value: 13 },
];

function validateTaxRate(rate: unknown): string | null {
  if (rate == null || rate === '') return '请选择税率（免税请选择 0%）';
  const numeric = Number(rate);
  if (!Number.isFinite(numeric) || numeric < 0 || numeric > 100) {
    return '税率必须是 0-100 之间的数字';
  }
  return null;
}

function normalizeTaxRateForPayload(rate: unknown): number {
  const message = validateTaxRate(rate);
  if (message) throw new Error(message);
  return Number(rate);
}

const form = ref({
  supplierId: '',
  purchaseType: 'DIRECT',
  expectedDeliveryDate: '',
  remark: '',
  relatedSalesOrderId: '',
  contractNumber: '',       // SP6 合同号（选填，对应纸质/框架合同）
  settlementType: '',       // SP6 结算方式
  invoiceReminderDays: null as number | null,  // SP6 开票提醒天数
  items: [newPurchaseItem()] as ProcurementOrderItem[],
  customFields: {} as TableRow,
});
const editingOrderId = ref('');
const editingVersion = ref<number | null>(null);
const editingOrderDate = ref('');
const isEditing = computed(() => Boolean(editingOrderId.value));

function itemAmountPreview(item: ProcurementOrderItem) {
  return pricingAmountPreview(item);
}

function itemTaxPreview(item: ProcurementOrderItem): { untaxed: number; tax: number; taxed: number } | null {
  const amount = itemAmountPreview(item).amount;
  const rate = Number(item.taxRate);
  if (amount == null || !Number.isFinite(rate)) return null;
  const untaxed = Number(amount);
  const tax = Number((untaxed * rate / 100).toFixed(2));
  return { untaxed, tax, taxed: Number((untaxed + tax).toFixed(2)) };
}

const suppliers = ref<TableRow[]>([]);
// R-4 拼音/首字母搜索: filter-method 场景下 el-select 关掉内建字面过滤,
// 过滤结果必须自己维护 (见 usePinyinFilter 内部注释)。
const supplierFilter = usePinyinFilter(
  () => suppliers.value,
  (s) => [s.shortName as string, s.name as string],
);
const handleSupplierFilter = supplierFilter.handleFilter;
const handleSupplierVisibleChange = supplierFilter.handleVisibleChange;
const filteredSuppliers = supplierFilter.filtered;
const materials = ref<TableRow[]>([]);
const salesOrders = ref<TableRow[]>([]);
const supplierMaterialRelations = ref<SupplierMaterialRelation[]>([]);
const purchaseSpecCache = ref<Record<string, SupplierPurchaseSpec[]>>({});
const materialSpecCache = ref<Record<string, SupplierPurchaseSpec[]>>({});

function materialTaxRate(value: unknown): number | null {
  if (value == null || value === '') return null;
  const match = String(value).match(/^TAX_(\d+(?:\.\d+)?)$/i);
  const numeric = match ? Number(match[1]) : Number(value);
  return Number.isFinite(numeric) && numeric >= 0 && numeric <= 100 ? numeric : null;
}

async function onSupplierChange(): Promise<void> {
  supplierMaterialRelations.value = await resolveSupplierMaterialRelations(
    factoryId.value,
    form.value.supplierId,
    listSupplierMaterials,
  );
  purchaseSpecCache.value = {};
  materialSpecCache.value = {};
  form.value.items = [newPurchaseItem()];
}

function newPurchaseItem(): ProcurementOrderItem {
  return {
    supplierMaterialId: '', purchasePackagingSpecId: null, materialPackagingSpecId: null,
    materialTypeId: '', quantity: 0,
    unit: '', quantityUnit: '', unitPrice: null, priceUnit: '', priceSource: null, taxRate: null,
    // 行级合同号: 客户实际单据是同一张单两行两个合同号(SAN-16572 / SAN-16562),
    // 单头 form.contractNumber 是框架合同号, 放不下。为空时收货侧回落到单头。
    contractNumber: '',
  };
}

async function loadPurchaseSpecs(relationId: string): Promise<SupplierPurchaseSpec[]> {
  if (!purchaseSpecCache.value[relationId]) {
    purchaseSpecCache.value[relationId] = (
      await listSupplierPurchaseSpecs(factoryId.value, form.value.supplierId, relationId)
    ).filter((row) => row.active !== false);
  }
  return purchaseSpecCache.value[relationId];
}

/**
 * 物料侧包装换算的缓存键必须**带上供应关系** —— 推导出来的采购价是按那条关系算的,
 * 只按 materialTypeId 缓存会让换了供应商之后拿到上一家的价。
 */
function materialSpecKey(materialTypeId: string, supplierMaterialId?: string): string {
  return `${materialTypeId}::${supplierMaterialId || ''}`;
}

async function loadMaterialSpecs(
  materialTypeId: string,
  supplierMaterialId?: string,
): Promise<SupplierPurchaseSpec[]> {
  const key = materialSpecKey(materialTypeId, supplierMaterialId);
  if (!materialSpecCache.value[key]) {
    // 带 supplierMaterialId 时后端会顺便把每条规格对应的采购价推导出来
    // (PurchasePriceDerivation) —— 否则「配了包装换算反而没价了」。
    // ⛔ 前端不做单位换算, 见 utils/unitPricing.ts 的 fail-closed 约定。
    const query = supplierMaterialId
      ? `?supplierMaterialId=${encodeURIComponent(supplierMaterialId)}` : '';
    const response = await get<{
      packagingSpecs?: Array<{
        id: string;
        name: string;
        packageUnit: string;
        baseUnit: string;
        conversionFactor: number;
        defaultSpec?: boolean;
        active?: boolean;
        derivedPrice?: number | string | null;
        derivedPriceUnit?: string | null;
        derivedPriceSource?: string | null;
      }>;
    } | null>(`/${factoryId.value}/material-packaging/by-material/${materialTypeId}${query}`);
    materialSpecCache.value[key] = (response.data?.packagingSpecs || [])
      .filter((row) => row.active !== false)
      .map((row) => ({
        id: `material:${row.id}`,
        supplierMaterialId: supplierMaterialId || '',
        materialTypeId,
        name: row.name,
        purchasePackageUnit: row.packageUnit,
        inventoryBaseUnit: row.baseUnit,
        factor: Number(row.conversionFactor),
        defaultSpec: row.defaultSpec,
        active: row.active,
        // 后端 BigDecimal 序列化后可能是 string —— 统一收成 number, 但 ⛔ 不兜底成 0
        // (0 会被 hasConfiguredPrice 当成一个真实报价带进单据)。
        derivedPrice: hasConfiguredPrice(row.derivedPrice) ? Number(row.derivedPrice) : null,
        derivedPriceUnit: row.derivedPriceUnit ?? null,
        derivedPriceSource: row.derivedPriceSource ?? null,
      }));
  }
  return materialSpecCache.value[key];
}

function specsForItem(item: ProcurementOrderItem): SupplierPurchaseSpec[] {
  const supplierSpecs = item.supplierMaterialId
    ? (purchaseSpecCache.value[item.supplierMaterialId] ?? []) : [];
  return supplierSpecs.length > 0
    ? supplierSpecs
    : (materialSpecCache.value[materialSpecKey(item.materialTypeId, item.supplierMaterialId)] ?? []);
}

function hasConfiguredPrice(value: unknown): boolean {
  return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value));
}

function applyRelationPrice(
  item: ProcurementOrderItem,
  relation: SupplierMaterialRelation,
  expectedPriceUnit: string,
): void {
  const expectedUnit = canonicalUnitCode(expectedPriceUnit);
  const effectiveUnit = canonicalUnitCode(relation.effectivePriceUnit);
  const relationUnit = canonicalUnitCode(relation.purchaseUnit || relation.baseUnit);
  if (hasConfiguredPrice(relation.effectivePurchasePrice) && effectiveUnit === expectedUnit) {
    item.unitPrice = Number(relation.effectivePurchasePrice);
    item.priceUnit = effectiveUnit;
    item.priceSource = relation.priceSource || 'MATERIAL_REFERENCE';
    return;
  }
  if (hasConfiguredPrice(relation.defaultPurchasePrice) && relationUnit === expectedUnit) {
    item.unitPrice = Number(relation.defaultPurchasePrice);
    item.priceUnit = relationUnit;
    item.priceSource = 'SUPPLIER_RELATION';
    return;
  }
  item.unitPrice = null;
  item.priceUnit = expectedUnit;
  item.priceSource = 'MISSING';
}

function priceSourceLabel(source: string | null | undefined): string {
  const labels: Record<string, string> = {
    SUPPLIER_SPEC: '供应商采购规格报价',
    SUPPLIER_RELATION: '供应商—物料默认采购价',
    // 换算过的必须说出来 —— 采购员看到的数字不是他在供应商页面填的那个, 得知道它从哪来。
    SUPPLIER_RELATION_CONVERTED: '供应商默认采购价 · 按本规格换算',
    MATERIAL_REFERENCE: '原料档案采购参考价',
    MANUAL: '本单手动价格',
    ORDER_SNAPSHOT: '订单历史价格快照',
    MISSING: '未配置采购价',
  };
  return labels[String(source || '')] || '价格来源待确认';
}

function onUnitPriceEdited(item: ProcurementOrderItem): void {
  item.priceSource = hasConfiguredPrice(item.unitPrice) ? 'MANUAL' : 'MISSING';
}

async function onSupplierMaterialChange(item: ProcurementOrderItem): Promise<void> {
  const relation = supplierMaterialRelations.value.find((row) => row.id === item.supplierMaterialId);
  if (!relation) return;
  item.materialTypeId = relation.materialTypeId;
  item.purchasePackagingSpecId = null;
  item.materialPackagingSpecId = null;
  const purchaseUnit = canonicalUnitCode(relation.purchaseUnit || relation.baseUnit);
  item.unit = purchaseUnit;
  item.quantityUnit = purchaseUnit;
  applyRelationPrice(item, relation, purchaseUnit);
  const material = materials.value.find((row) => String(row.id) === relation.materialTypeId);
  item.taxRate = materialTaxRate(material?.taxRate);
  const [supplierSpecs, materialSpecs] = await Promise.all([
    loadPurchaseSpecs(relation.id),
    loadMaterialSpecs(relation.materialTypeId, relation.id),
  ]);
  const specs = supplierSpecs.length > 0 ? supplierSpecs : materialSpecs;
  const defaultSpec = specs.find((row) => row.defaultSpec);
  if (defaultSpec) {
    item.purchasePackagingSpecId = defaultSpec.id;
    onPurchaseSpecChange(item);
  }
}

function onPurchaseSpecChange(item: ProcurementOrderItem): void {
  const spec = specsForItem(item).find((row) => row.id === item.purchasePackagingSpecId);
  const relation = supplierMaterialRelations.value.find((row) => row.id === item.supplierMaterialId);
  if (!relation) return;
  const unit = canonicalUnitCode(spec?.purchasePackageUnit || relation.purchaseUnit || relation.baseUnit);
  item.materialPackagingSpecId = spec?.id.startsWith('material:')
    ? spec.id.slice('material:'.length) : null;
  item.unit = unit;
  item.quantityUnit = unit;
  item.priceUnit = unit;
  if (hasConfiguredPrice(spec?.quotedPrice)) {
    item.unitPrice = Number(spec?.quotedPrice);
    item.priceSource = 'SUPPLIER_SPEC';
  } else if (hasConfiguredPrice(spec?.derivedPrice)) {
    // 客户 2026-07-30 (Sheet 第 38 行):「设完换算规格后不再自动填入供应商配好的采购单价」。
    // 该规格没有自己的报价, 但后端已用这一行声明的「1 箱 = N kg」把关系价折到了包装单位。
    // 换算是后端做的 —— 前端只搬数, 不碰单位数学 (utils/unitPricing.ts 的三种权威口径)。
    item.unitPrice = Number(spec?.derivedPrice);
    item.priceUnit = canonicalUnitCode(spec?.derivedPriceUnit || unit);
    item.priceSource = spec?.derivedPriceSource === 'SUPPLIER_RELATION_CONVERTED'
      ? 'SUPPLIER_RELATION_CONVERTED' : 'SUPPLIER_RELATION';
  } else {
    applyRelationPrice(item, relation, unit);
  }
}

function inventoryQuantityPreview(item: ProcurementOrderItem): string {
  const spec = specsForItem(item).find((row) => row.id === item.purchasePackagingSpecId);
  const relation = supplierMaterialRelations.value.find((row) => row.id === item.supplierMaterialId);
  const quantity = Number(item.quantity || 0);
  const baseUnit = spec?.inventoryBaseUnit || relation?.baseUnit || relation?.purchaseUnit;
  const inventoryQuantity = spec ? quantity * Number(spec.factor) : quantity;
  return quantity > 0 && baseUnit ? `${Number(inventoryQuantity.toFixed(4))} ${displayUnit(baseUnit)}` : '-';
}

function requiresPurchaseSpec(item: ProcurementOrderItem): boolean {
  return specsForItem(item).length > 0 && !item.purchasePackagingSpecId;
}

// M-10: 旧版这里手写了一份只有 7 项的 statusMap (漏了 4 个审批环节状态), 现改为
// 从 @/constants/purchaseOrderStatus 取「展示归类」—— 采购员只想知道「批了没/货到没」,
// 11 个后端精确状态在列表页折叠成 7 档 (3 档是多状态并集, 其余 4 档是需要单独区分的
// 终态)。精确状态仍保留在 detail.vue。
const purchaseTypeColumnFilters = [
  { text: '直接采购', value: 'DIRECT' },
  { text: '统一采购', value: 'UNIFIED' },
  { text: '紧急采购', value: 'URGENT' },
];
const purchaseStatusColumnFilters = PURCHASE_ORDER_STATUS_TIER_OPTIONS.map((tier) => ({
  text: tier.tierLabel,
  value: tier.tierKey,
}));
function filterPurchaseType(value: string, row: TableRow): boolean {
  return String(row.purchaseType || '') === value;
}
function filterPurchaseStatus(value: string, row: TableRow): boolean {
  return getPurchaseOrderStatusTier(String(row.status || ''))?.tierKey === value;
}

// Kanban 视图按精确 row.status 分组 (KanbanView 内部是严格字符串匹配), 之前直接把
// 只有 7 项的旧 statusMap 塞给它当 columns —— 意味着落在缺的 4 个状态上的行会被
// discoveredColumns 判定为"不在已知列里"而整行从看板消失 (M-10 的又一个受害面, 不只是
// 筛选器)。改成: 给每行算出归类 tierKey 当分组字段, columns 用同一份 7 档归类,
// 与表格/筛选器口径保持一致, 且天然不会再漏行。
/*
 * ⚠️ 返回类型必须显式写成 Record<string, any>[]，⛔ 不能让它自己推。
 *
 * `row` 是 any，展开 any **不会**给对象字面量贡献任何已知键，于是 TS 把元素类型
 * 推成**恰好** `{ __statusTierKey: string }`。KanbanView 的 props 是
 * `statusField: keyof T` / `titleField?: keyof T`，T 一旦被绑成那个单键类型，
 * `keyof T` 就只有 `"__statusTierKey"` 一个值 ——
 * 于是 title-field="orderNumber" / subtitle-field="supplierName" / row-key="id"
 * 三个全部 TS2322。
 *
 * 🔴 这个错**在 `vue-tsc --noEmit` 下看不见**：本仓根 tsconfig 是
 * `{files: [], references: [...]}`，裸跑 --noEmit 检查 **0 个文件**，必然 exit 0。
 * 权威命令是 `vue-tsc -b`（package.json 的 build:check 用的就是它）。
 */
const kanbanRows = computed<Record<string, any>[]>(() =>
  tableData.value.map((row) => ({
    ...row,
    __statusTierKey: getPurchaseOrderStatusTier(String(row.status || ''))?.tierKey || String(row.status || ''),
  }))
);
const kanbanStatusColumns = PURCHASE_ORDER_STATUS_TIER_OPTIONS.map((tier) => ({
  status: tier.tierKey,
  label: tier.tierLabel,
}));

// D13: Dirty form guard — warn user before leaving with unsaved changes
const isDirty = ref(false);
watch(dialogVisible, (val) => { isDirty.value = val; });
function handleBeforeUnload(e: BeforeUnloadEvent) {
  if (isDirty.value) { e.preventDefault(); e.returnValue = ''; }
}
onMounted(() => {
  loadData();
  loadSuppliers();
  loadMaterials();
  loadSalesOrders();
  const editId = String(route.query.edit || '');
  if (editId) launchEditDialog(editId);
  window.addEventListener('beforeunload', handleBeforeUnload);
});

// 这个入口坏过两次, 两次的症状都是「地址栏变了但什么也没发生」: 先是 watch 缺失, 后是
// openEditDialog 内部抛异常而 `void` 把 rejection 丢掉. 任何未处理的异常都会复现同一症状,
// 所以统一从这里进, 失败必须变成看得见的错误.
function launchEditDialog(orderId: string): void {
  void openEditDialog(orderId).catch((error: unknown) => {
    ElMessage({
      message: (error as Error)?.message || '采购单编辑打开失败',
      type: 'error',
      duration: 0,
      showClose: true,
    });
    void clearEditQuery();
  });
}

// 点「编辑」时用户已经在 /procurement/orders 上, router.push 只改 query 不换路由 → Vue 复用同一个
// 组件实例, onMounted 不会二次执行. 旧代码只在 onMounted 读一次 route.query.edit, 于是地址栏多出
// ?edit=xxx 而编辑弹窗永远不打开 —— 即客户反馈的「点击后仅会在域名后新增字符且无响应」.
watch(() => route.query.edit, (value) => {
  const editId = String(value || '');
  if (editId) launchEditDialog(editId);
});
onBeforeUnmount(() => { window.removeEventListener('beforeunload', handleBeforeUnload); });

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const url = statusFilter.value
      ? `/${factoryId.value}/purchase/orders/by-status`
      : `/${factoryId.value}/purchase/orders`;
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    if (statusFilter.value) params.status = statusFilter.value;
    const response = await get(url, { params });
    if (response.success && response.data) {
      let rows = response.data.content || [];
      // Client-side keyword + date filter (Apr 21 2026): backend lacks
      // keyword param on purchase/orders; filter locally on current page.
      const kw = searchKeyword.value.trim().toLowerCase();
      if (kw) {
        rows = rows.filter((r: TableRow) =>
          String(r.orderNumber || '').toLowerCase().includes(kw) ||
          String(r.supplierName || (r.supplier as TableRow)?.name || '').toLowerCase().includes(kw)
        );
      }
      if (dateRange.value && dateRange.value[0] && dateRange.value[1]) {
        const [from, to] = dateRange.value;
        rows = rows.filter((r: TableRow) => {
          const d = String(r.orderDate || '').slice(0, 10);
          return d && d >= from && d <= to;
        });
      }
      tableData.value = rows;
      pagination.value.total = response.data.totalElements || 0;

      // Sprint 6 W3-A — fire-and-forget batch 3-chip counts (文件/图片/合同).
      // List itself is unaffected by chip request failure; chip falls back to "-".
      void fetchLinkChipCounts(rows.map((r: TableRow) => String(r.id)).filter(Boolean));
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    loading.value = false;
  }
}

async function loadSuppliers() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/suppliers`, { params: { page: 1, size: 100 } });
    if (res.success && res.data) suppliers.value = res.data.content || [];
  } catch { /* axios interceptor already displayed error toast */ }
}

async function loadMaterials() {
  if (!factoryId.value) return;
  try {
    // Bug B3 fix: use /active endpoint (unpaginated, same source as BOM and warehouse)
    const res = await get(`/${factoryId.value}/raw-material-types/active`);
    if (res.success && res.data) materials.value = Array.isArray(res.data) ? res.data : res.data.content || [];
  } catch { /* axios interceptor already displayed error toast */ }
}

async function loadSalesOrders() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/sales/orders`, { params: { page: 1, size: 100 } });
    if (res.success && res.data) salesOrders.value = res.data.content || [];
  } catch { /* optional field, ignore errors */ }
}

/**
 * 三个下拉的数据源刷新 —— **不阻塞弹窗显示**。
 *
 * 2026-08-21 owner 实测「点新建采购订单要 2~3 秒才有反应」。成因不是网络慢，是
 * `openCreateDialog` / `openEditDialog` 都把 `await Promise.all([三个 loader])` 放在
 * `dialogVisible = true` **前面** —— 弹窗要等三个请求全回来才显示。
 *
 * ⚠️ 本文件已经有过两次同一结构的 prod 事故 (2026-08-04「点了没反应」: 其中一个请求
 * 404 抛出, 在 `dialogVisible = true` 之前就断了)。
 * ▎「永远打不开」和「2 秒才打开」是同一个结构缺陷的两个刻度 —— 之前只修了失败那一刻度。
 * ⇒ 根治是**先显示弹窗, 再取数**, 让「取数」在结构上不可能再影响「弹窗出不出来」。
 *
 * ⛔ 保留 2026-04-28 张权反馈那条修复的**意图**(每次打开强制刷新, 防止 dropdown 用旧
 *    cache): 改的是「阻塞地刷新」, 不是「不刷新」。三个 loader 自己 catch 不会 reject,
 *    刷新期间 select 挂 `:loading` 让用户看得见「还在更新」。
 */
const dropdownsRefreshing = ref(false);
function refreshDropdownSources(): Promise<void> {
  dropdownsRefreshing.value = true;
  return Promise.all([loadSuppliers(), loadMaterials(), loadSalesOrders()])
    .then((): void => undefined)
    // ⚠️ 显式 `: void` 不是啰嗦: 缺了它 vue-tsc 报 TS7011(隐式 any 返回值)。
    //    2026-08-21 实测漏网 —— 那次提交只跑了 vitest, ⛔ 没跑 vue-tsc -b --force,
    //    而本仓 `vue-tsc --noEmit` 检查 0 个文件、vitest 也不做类型检查, 于是一路溜到 prod。
    .finally((): void => {
      dropdownsRefreshing.value = false;
    });
}

function addItem() {
  form.value.items.push(newPurchaseItem());
}

function removeItem(index: number) {
  if (form.value.items.length > 1) form.value.items.splice(index, 1);
}

const submitting = ref(false);

async function handleCreate() {
  if (submitting.value) return; // P-2: prevent double-submit
  if (createdOrderId.value) {
    submitting.value = true;
    try {
      const result = await createAttachmentRef.value?.uploadQueued(createdOrderId.value);
      if ((result?.failed ?? 0) > 0) {
        ElMessage.error('采购订单已创建，但仍有附件上传失败；请重试失败文件，系统不会重复创建订单');
        return;
      }
      ElMessage.success('采购订单及附件已保存');
      dialogVisible.value = false;
      resetForm();
      loadData();
    } finally {
      submitting.value = false;
    }
    return;
  }
  if (!form.value.supplierId) return ElMessage.warning('请选择供应商');
  if (!Array.isArray(form.value.items) || form.value.items.length === 0) {
    return ElMessage.warning('请至少添加一行原料明细');
  }
  if (form.value.items.some(i => !i.supplierMaterialId || !i.materialTypeId)) return ElMessage.warning('请选择当前供应商可供的所有原料');
  const missingSpecIndex = form.value.items.findIndex(requiresPurchaseSpec);
  if (missingSpecIndex >= 0) return ElMessage.warning(`第 ${missingSpecIndex + 1} 行已配置采购规格，请选择规格`);
  const invalidQtyIndex = form.value.items.findIndex(i => !(Number(i.quantity) > 0));
  if (invalidQtyIndex >= 0) {
    return ElMessage.warning(`第 ${invalidQtyIndex + 1} 行采购数量必须大于 0`);
  }
  if (form.value.items.some(i => !i.unit)) return ElMessage.warning('请填写所有明细的单位');
  const missingPriceIndex = form.value.items.findIndex((i) => !hasConfiguredPrice(i.unitPrice));
  if (missingPriceIndex >= 0) {
    return ElMessage.warning(`第 ${missingPriceIndex + 1} 行尚未配置采购价，请维护供应商关系/采购规格报价或明确填写本单价格`);
  }
  if (form.value.items.some(i => !canonicalUnitCode(i.priceUnit))) {
    return ElMessage.warning('采购价必须绑定明确的计价单位');
  }
  const invalidTaxRateIndex = form.value.items.findIndex((i) => validateTaxRate(i.taxRate));
  if (invalidTaxRateIndex >= 0) {
    return ElMessage.warning(`第 ${invalidTaxRateIndex + 1} 行${validateTaxRate(form.value.items[invalidTaxRateIndex].taxRate)}`);
  }
  submitting.value = true;
  try {
    // W-12 fix (Round 15): previously relatedSalesOrderId was stripped from payload
    // and only embedded as "[关联销售订单: SO-XXX]" in remark. Now that the backend has
    // a proper sales_order_id column + filter (V20260424_03 + PurchaseService fix),
    // pass it through as salesOrderId. Keep the remark text for backward-compat
    // readability — users who view old POs will still see the ref.
    let remark = form.value.remark || '';
    const salesOrderId = form.value.relatedSalesOrderId || null;
    if (form.value.relatedSalesOrderId) {
      const so = salesOrders.value.find((o: TableRow) => o.id === form.value.relatedSalesOrderId);
      const soRef = so ? `[关联销售订单: ${so.orderNumber}]` : '';
      remark = soRef ? (remark ? `${soRef} ${remark}` : soRef) : remark;
    }
    const { relatedSalesOrderId: _unused, items, ...formData } = form.value;
    const payload = {
      ...formData,
      items: items.map((i) => ({
        ...i,
        purchasePackagingSpecId: i.purchasePackagingSpecId?.startsWith('material:')
          ? null : i.purchasePackagingSpecId,
        materialPackagingSpecId: i.purchasePackagingSpecId?.startsWith('material:')
          ? i.purchasePackagingSpecId.slice('material:'.length)
          : i.materialPackagingSpecId || null,
        unit: canonicalUnitCodeKeepingCount(i.quantityUnit || i.unit),
        quantityUnit: canonicalUnitCodeKeepingCount(i.quantityUnit || i.unit),
        priceUnit: canonicalUnitCodeKeepingCount(i.priceUnit),
        taxRate: normalizeTaxRateForPayload(i.taxRate),
      })),
      remark,
      salesOrderId,
      orderDate: isEditing.value ? editingOrderDate.value : factoryToday(),
      ...(isEditing.value ? { version: editingVersion.value } : {}),
    };
    const res = isEditing.value
      ? await put(`/${factoryId.value}/purchase/orders/${editingOrderId.value}`, payload)
      : await post(`/${factoryId.value}/purchase/orders`, payload);
    if (res.success) {
      const savedOrderId = String((res.data as TableRow | undefined)?.id || editingOrderId.value || '');
      if (!isEditing.value && attachmentQueue.value.total > 0) {
        if (!savedOrderId) {
          ElMessage.error('采购订单已创建，但响应缺少订单 ID，附件无法安全绑定；请从订单详情上传附件');
          return;
        }
        createdOrderId.value = savedOrderId;
        const uploadResult = await createAttachmentRef.value?.uploadQueued(savedOrderId);
        if ((uploadResult?.failed ?? 0) > 0) {
          ElMessage.error('采购订单已创建一次，但部分附件上传失败；请重试附件，勿再次创建订单');
          return;
        }
      }
      ElMessage.success(isEditing.value ? '采购草稿已更新' : '采购订单及附件已创建');
      dialogVisible.value = false;
      resetForm();
      await clearEditQuery();
      loadData();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', error);
  } finally {
    submitting.value = false;
  }
}

function resetForm() {
  editingOrderId.value = '';
  editingVersion.value = null;
  editingOrderDate.value = '';
  form.value = { supplierId: '', purchaseType: 'DIRECT', expectedDeliveryDate: '', remark: '', relatedSalesOrderId: '', contractNumber: '', settlementType: '', invoiceReminderDays: null, items: [newPurchaseItem()], customFields: {} as TableRow };
  supplierMaterialRelations.value = [];
  purchaseSpecCache.value = {};
  createdOrderId.value = '';
  attachmentQueue.value = { total: 0, pending: 0, failed: 0 };
  createAttachmentRef.value?.clear();
}

// 张权 Apr 28 反馈: "基础数据已经新建了 但是采购订单 下拉没有选项"
// — 用户先打开本页, dropdown 已加载; 然后跳到基础数据页新建供应商/原料;
// 切回本页打开新建对话框时, dropdown 仍是旧 cache. 修复: 每次打开对话框
// 强制刷新 3 个 dropdown 数据源.
function openCreateDialog() {
  resetForm();
  // ⚠️ 顺序是判据的一部分: 先显示, 再取数。反过来写就是本轮修的那个缺陷。
  dialogVisible.value = true;
  void refreshDropdownSources();
}

async function clearEditQuery() {
  if (!route.query.edit) return;
  const query = { ...route.query };
  delete query.edit;
  await router.replace({ path: route.path, query });
}

async function closeOrderEditor() {
  if (createdOrderId.value && (attachmentQueue.value.pending > 0 || attachmentQueue.value.failed > 0)) {
    try {
      await ElMessageBox.confirm(
        '采购订单已经创建，但附件尚未全部上传。关闭后可在订单详情继续上传，订单不会回滚或重复创建。',
        '附件尚未完成',
        { type: 'warning', confirmButtonText: '仍然关闭', cancelButtonText: '继续处理附件' },
      );
    } catch { return; }
  }
  dialogVisible.value = false;
  resetForm();
  await clearEditQuery();
}

async function openEditDialog(orderId: string) {
  resetForm();
  // 三个下拉与单据读取**并行**, 且不 await —— 它们只喂 el-option 的 label,
  // 预填只用到 id (form.supplierId = order.supplierId), 不依赖列表先到。
  // ⛔ 不能像新建那样「先开弹窗」: 下面的草稿判定不通过时**根本不该开**,
  //    先开再关会闪一下, 比慢更糟。所以这里只能减少要等的来回数 (6 → 3)。
  void refreshDropdownSources();
  const response = await get<TableRow>(`/${factoryId.value}/purchase/orders/${orderId}`);
  if (!response.success || !response.data) {
    // 静默 return 会让用户第二次看到「点了没反应」, 且地址栏已经变了 —— 读不到单子必须说清楚.
    ElMessage({
      message: response.message || '采购单读取失败，无法进入编辑',
      type: 'error',
      duration: 0,
      showClose: true,
    });
    await clearEditQuery();
    return;
  }
  const order = response.data;
  if (String(order.status) !== 'DRAFT') {
    // 同函数内其它拒绝分支都是 sticky 的, 只有这一条是 3 秒即散的 warning ——
    // 而它恰恰是最像「点了没反应」的那条 (地址栏已经变了, 弹窗不开, 提示又一闪而过).
    // 按防呆规范「4 位一体」: 具体原因 + sticky + 下一步动作.
    ElMessage({
      message: `仅草稿采购单允许编辑，当前状态为「${getPurchaseOrderStatusLabel(String(order.status))?.text || enumLabel(String(order.status))}」。`
        + '如需修改，请复制为新单据后编辑，或先取消本单。',
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    await clearEditQuery();
    return;
  }
  editingOrderId.value = orderId;
  editingVersion.value = Number(order.version);
  editingOrderDate.value = String(order.orderDate || factoryToday());
  const orderSupplierId = String(order.supplierId || '');
  form.value.supplierId = orderSupplierId;
  // 「开始采购」按销售订单自动生成的草稿单没有供应商 (Sheet 第 22 行), 而编辑入口正是
  // 为「事后补录供应商」而加的. 这里必须容许空 supplierId —— 空 id 直接拼进
  // /{factoryId}/suppliers/{supplierId}/materials 会得到 /suppliers//materials 并 404,
  // 把整个 openEditDialog 打断在 dialogVisible=true 之前, 复现「点了没反应」.
  supplierMaterialRelations.value = await resolveSupplierMaterialRelations(
    factoryId.value,
    orderSupplierId,
    listSupplierMaterials,
  );
  await Promise.all(supplierMaterialRelations.value.flatMap((relation) => [
    loadPurchaseSpecs(relation.id),
    loadMaterialSpecs(relation.materialTypeId, relation.id),
  ]));
  form.value = {
    supplierId: orderSupplierId,
    purchaseType: String(order.purchaseType || 'DIRECT'),
    expectedDeliveryDate: String(order.expectedDeliveryDate || ''),
    remark: String(order.remark || ''),
    relatedSalesOrderId: String(order.salesOrderId || order.relatedSalesOrderId || ''),
    contractNumber: String(order.contractNumber || ''),
    settlementType: String(order.settlementType || ''),
    invoiceReminderDays: order.invoiceReminderDays == null ? null : Number(order.invoiceReminderDays),
    items: (Array.isArray(order.items) ? order.items : []).map((raw) => {
      const item = raw as TableRow;
      const quantityUnit = canonicalUnitCode(item.quantityUnit || item.unit);
      return {
        supplierMaterialId: String(item.supplierMaterialId || supplierMaterialRelations.value.find((relation) => relation.materialTypeId === String(item.materialTypeId || ''))?.id || ''),
        purchasePackagingSpecId: item.purchasePackagingSpecId
          ? String(item.purchasePackagingSpecId)
          : item.materialPackagingSpecId
            ? `material:${String(item.materialPackagingSpecId)}`
            : null,
        materialPackagingSpecId: item.materialPackagingSpecId ? String(item.materialPackagingSpecId) : null,
        materialTypeId: String(item.materialTypeId || ''),
        quantity: Number(item.quantity || 0),
        unit: quantityUnit,
        quantityUnit,
        unitPrice: item.unitPrice == null ? null : Number(item.unitPrice),
        priceUnit: canonicalUnitCodeKeepingCount(item.priceUnit || item.unit),
        priceSource: String(item.priceSource || 'ORDER_SNAPSHOT'),
        lineAmount: item.lineAmount == null ? null : Number(item.lineAmount),
        convertedPricingQuantity: item.convertedPricingQuantity == null ? null : Number(item.convertedPricingQuantity),
        taxRate: item.taxRate == null ? null : Number(item.taxRate),
        contractNumber: String(item.contractNumber || ''),
      };
    }),
    customFields: (order.customFields as TableRow) || {},
  };
  if (!form.value.items.length) addItem();
  dialogVisible.value = true;
}

async function handleAction(orderId: string, action: string) {
  const actionMap: Record<string, { label: string; url: string }> = {
    submit: { label: '提交', url: `/${factoryId.value}/purchase/orders/${orderId}/submit` },
    cancel: { label: '取消', url: `/${factoryId.value}/purchase/orders/${orderId}/cancel` },
  };
  const a = actionMap[action];
  if (!a) return;
  // 防呆 Rule2 (fool-proof-design): 确认框必带身份信息 (单号/供应商/金额),
  // 不用泛泛的"确认审批此采购订单？" —— 低技术素养用户需明确知道在操作哪一单。
  const row = tableData.value.find((r) => String(r.id) === String(orderId));
  const ctx = row
    ? `${row.orderNumber || ''}${row.supplierName ? ' · ' + row.supplierName : ''}` +
      `${row.totalAmount != null ? ' · ¥' + Number(row.totalAmount).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) : ''}`
    : '';
  try {
    await ElMessageBox.confirm(
      ctx ? `确认${a.label}采购订单 ${ctx}？` : `确认${a.label}此${label('purchaseOrder')}？`,
      `${a.label}采购订单`,
      { type: 'warning', confirmButtonText: `确认${a.label}`, cancelButtonText: '取消' },
    );
    const res = await post(a.url);
    if (res.success) {
      ElMessage.success(`${a.label}成功`);
      loadData();
    } else {
      ElMessage.error(res.message || `${a.label}失败`);
    }
  } catch (error) { if (error !== 'cancel') ElMessage.error(`${a.label}失败`); }
}

function goDetail(id: string) {
  router.push(`/procurement/orders/${id}`);
}

// P0 (六扇门 May 7 transcript): 列表行内直接下载 PDF 供货单
const pdfDownloadingIds = ref<Set<string>>(new Set());
async function handleDownloadPdf(row: TableRow) {
  if (!factoryId.value || !row.id) return;
  const id = String(row.id);
  if (pdfDownloadingIds.value.has(id)) return;
  pdfDownloadingIds.value.add(id);
  try {
    const response = await request.get(
      `/${factoryId.value}/purchase/orders/${id}/pdf`,
      {
        params: { external: true },
        responseType: 'blob',
      }
    );
    const blob = new Blob([response.data], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `供货单_${row.orderNumber || id}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success('PDF 下载成功');
  } catch (e) {
    console.error('[PDF 下载失败]', e);
    ElMessage.error('PDF 下载失败,请稍后重试');
  } finally {
    pdfDownloadingIds.value.delete(id);
  }
}

function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; loadData(); }
function handleStatusChange() { pagination.value.page = 1; loadData(); }
function handleSearch() { pagination.value.page = 1; loadData(); }
function handleRefresh() {
  statusFilter.value = '';
  searchKeyword.value = '';
  dateRange.value = null;
  pagination.value.page = 1;
  loadData();
}

// ==================== AI Entry ====================
const aiEntryVisible = ref(false);

// ==================== Forward Share Dialog (PR #872) ====================
// State for ForwardShareDialog — scoped to the row clicked via 转发 inline chip.
const forwardDialogVisible = ref(false);
const forwardEntityId = ref('');
const forwardEntityLabel = ref('');

function handleAiFill(params: TableRow) {
  // Match supplierName to supplierId
  const supplierName = String(params.supplierName || '');
  const supplierResolution = resolveReferenceByName(supplierName, suppliers.value);
  if (supplierResolution.status !== 'MATCHED') {
    ElMessage.warning(supplierResolution.status === 'AMBIGUOUS'
      ? `供应商“${supplierName}”匹配到多个候选，请明确名称`
      : `未找到供应商“${supplierName}”，请先维护供应商档案`);
    return;
  }
  form.value.supplierId = supplierResolution.id;
  form.value.purchaseType = String(params.purchaseType || 'DIRECT');
  form.value.expectedDeliveryDate = String(params.expectedDeliveryDate || '');
  form.value.remark = String(params.remark || '');

  if (Array.isArray(params.items) && params.items.length > 0) {
    try {
      form.value.items = (params.items as TableRow[]).map((item) => {
      // Try to match materialName to materialTypeId
      const matName = String(item.materialName || '');
      const materialResolution = resolveReferenceByName(matName, materials.value);
      if (materialResolution.status !== 'MATCHED') {
        throw new Error(`原料“${matName}”${materialResolution.status === 'AMBIGUOUS' ? '匹配到多个候选' : '未找到'}`);
      }
      return {
        materialTypeId: materialResolution.id,
        quantity: Number(item.quantity || 0),
        unit: canonicalUnitCodeKeepingCount(item.quantityUnit || item.unit || 'kg'),
        quantityUnit: canonicalUnitCodeKeepingCount(item.quantityUnit || item.unit || 'kg'),
        unitPrice: item.unitPrice == null ? null : Number(item.unitPrice),
        priceUnit: canonicalUnitCodeKeepingCount(item.priceUnit || item.unit || 'kg'),
        priceSource: item.unitPrice == null ? 'MISSING' : 'MANUAL',
        taxRate: validateTaxRate(item.taxRate) ? null : normalizeTaxRateForPayload(item.taxRate),
      };
      });
    } catch (error) {
      ElMessage.warning(error instanceof Error ? error.message : 'AI 原料匹配失败');
      return;
    }
  }

  dialogVisible.value = true;
}
</script>

<template>
  <CanvasAwareWrapper module-code="purchase_order">
  <div class="page-wrapper">
    <!-- U-NAV-1 业务流程图导航 (Sprint 2 Track G) -->
    <WorkflowBar
      :nodes="workflowStats?.nodes ?? []"
      :loading="workflowLoading"
      title="采购工作流"
      :ai-trigger-enabled="true"
      @node-click="handleWorkflowNodeClick"
      @ai-trigger="aiEntryVisible = true"
    />
    <ConceptDisambiguationAlert
      here-name="采购订单"
      here="我们向供应商下的订单（进货方向、应付账款）"
      other-name="销售管理 → 销售订单"
      other="客户向我们下的订单（出货方向、应收账款）"
      other-path="/sales/orders"
    />
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ label('purchaseOrder') }}管理</span>
            <span class="data-count">共 {{ pagination.total }} 条记录</span>
          </div>
          <div class="header-right">
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="aiEntryVisible = true">
              AI录入
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateDialog">
              新建{{ label('purchaseOrder') }}
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索 订单号 / 供应商"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="下单起始"
          end-placeholder="下单结束"
          value-format="YYYY-MM-DD"
          style="width: 280px"
          @change="handleSearch"
        />
        <el-select v-model="statusFilter" placeholder="按状态筛选" clearable style="width: 160px" @change="handleStatusChange">
          <el-option
            v-for="s in ALL_PURCHASE_ORDER_STATUSES"
            :key="s"
            :label="PURCHASE_ORDER_STATUS_LABELS[s].text"
            :value="s"
          />
        </el-select>
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">重置</el-button>
        <ListColumnSelector
          v-model="procurementVisibleColumns"
          :columns="procurementColumnOptions"
          :max="4"
          fixed-summary="订单编号、供应商、状态和操作固定显示"
          @reset="resetProcurementColumns"
        />
        <!-- U-VIEW-1 view-mode switcher (Sprint 4 Wave 2 Chat L) -->
        <div style="margin-left: auto">
          <ViewModeSwitcher v-model="viewMode" />
        </div>
      </div>

      <GridView
        v-if="viewMode === 'grid'"
        :rows="tableData"
        title-field="orderNumber"
        subtitle-field="supplierName"
        status-field="status"
        row-key="id"
      />
      <KanbanView
        v-else-if="viewMode === 'kanban'"
        :rows="kanbanRows"
        status-field="__statusTierKey"
        title-field="orderNumber"
        subtitle-field="supplierName"
        :columns="kanbanStatusColumns"
        row-key="id"
      />
      <TimelinePlaceholder v-else-if="viewMode === 'timeline'" />
      <CalendarPlaceholder v-else-if="viewMode === 'calendar'" />
      <el-table
        v-else
        :data="tableData"
        v-loading="loading"
        empty-text="暂无数据"
        stripe
        border
        table-layout="fixed"
        style="width: 100%"
        :scrollbar-always-on="true"
        class="wide-table business-list-table"
      >
        <el-table-column prop="orderNumber" label="订单编号" width="170" sortable />
        <el-table-column prop="supplierName" label="供应商" min-width="150" show-overflow-tooltip sortable>
          <template #default="{ row }">{{ row.supplierName || row.supplier?.name || row.supplierId || '-' }}</template>
        </el-table-column>
        <el-table-column
          v-if="procurementColumnVisible('purchaseType')"
          prop="purchaseType"
          label="类型"
          width="120"
          align="center"
          :filters="purchaseTypeColumnFilters"
          :filter-method="filterPurchaseType"
          :filter-multiple="false"
        >
          <template #default="{ row }">
            <el-tag size="small" :type="row.purchaseType === 'URGENT' ? 'danger' : ''">
              {{ row.purchaseType === 'DIRECT' ? '直接' : row.purchaseType === 'URGENT' ? '紧急' : '统一' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="procurementColumnVisible('orderDate')"
          prop="orderDate"
          label="下单日期"
          width="120"
          sortable
        />
        <!--
          RBAC defense-in-depth (PR #415 Option B 2026-05-12 + P3 column-hide fix):
          backend PriceFieldResponseAdvice strips totalAmount → null for roles
          lacking procurement:price:view. v-if on the column itself hides BOTH
          the header and cells for non-whitelisted roles (warehouse_manager etc).
          Without v-if on <el-table-column>, Element Plus still renders the
          column header even when every cell template returns nothing → E2E
          flagged 总金额 header visible despite null cells (misleading UX).
        -->
        <el-table-column
          v-if="canViewPrice && procurementColumnVisible('totalAmount')"
          prop="totalAmount"
          label="总金额"
          width="130"
          align="right"
          sortable
        >
          <template #default="{ row }">
            <span v-if="row.totalAmount != null">{{ formatAmount(row.totalAmount) }}</span>
            <span v-else class="price-masked">—</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="status"
          label="状态"
          width="120"
          align="center"
          :filters="purchaseStatusColumnFilters"
          :filter-method="filterPurchaseStatus"
          :filter-multiple="false"
        >
          <template #default="{ row }">
            <el-tag :type="(getPurchaseOrderStatusTier(row.status)?.tagType) || 'info'" size="small">
              {{ getPurchaseOrderStatusTier(row.status)?.tierLabel || enumLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <!--
          Sprint 6 W3-A — 行内 3-chip 链接计数 (文件 / 图片 / 合同).
          数据源: POST /attachments/batch-3chip-counts (batch, 避免 N+1).
          EntityType=PURCHASE_ORDER 由 useLinkChipCounts composable 锁定.
        -->
        <el-table-column v-if="procurementColumnVisible('attachments')" label="附件" width="200" align="center">
          <template #header>
            <span style="display: inline-flex; align-items: center; gap: 4px;">
              附件
              <el-tooltip placement="top">
                <template #content>
                  <div style="line-height: 1.6;">
                    <div><b>文件</b>: 通用文档 (PDF / Word / Excel / OTHER)</div>
                    <div><b>图片</b>: 照片 / 视频 (PHOTO / VIDEO)</div>
                    <div><b>合同</b>: 采购合同 / 法律文件 (CONTRACT)</div>
                    <div style="margin-top: 4px; color: var(--text-color-secondary);">点详情查看附件清单</div>
                  </div>
                </template>
                <el-icon style="cursor: help; color: var(--text-color-secondary, #909399); font-size: 12px;"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <LinkChipCell :counts="linkCountsFor(row.id)" />
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="260"
          fixed="right"
          align="center"
          class-name="business-list-table__actions"
        >
          <template #default="{ row }">
            <div class="business-action-row">
              <el-button type="primary" link size="small" @click="goDetail(row.id)">详情</el-button>
              <!-- P0 (六扇门 May 7 transcript): 下载 PDF 供货单 (含 Code128 + QR 条码) -->
              <!-- M-3 (2026-08-20): DRAFT 还没提交/审批, 不能先把对外供货单发出去 -->
              <el-button v-if="canDownloadSupplyOrder(row.status)" type="info" link size="small" :icon="Download"
                :loading="pdfDownloadingIds.has(String(row.id))"
                @click="handleDownloadPdf(row)">对外供货单</el-button>
              <el-button v-if="canSubmitPurchaseOrder(row.status, canWrite)" type="warning" link size="small" @click="handleAction(row.id, 'submit')">提交</el-button>
              <RowActionMenu
                :actions="moreRowActionsFor(row)"
                button-label="更多"
                @action-click="(id: string) => handleRowActionClick(id, row)"
                @ai-trigger="() => openAiForRow(row)"
              />
            </div>
          </template>
        </el-table-column>
      </el-table>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前采购订单${formatSummaryForAI(footerSummary, { filter: { status: statusFilter } })}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <el-pagination v-model:current-page="pagination.page" v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]" :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange" @size-change="handleSizeChange" />
      </div>
    </el-card>

    <!-- May 7 2026 用户反馈: "新建采购订单需要把页面放大,要不然很多明细的话是很小的".
         改全屏 dialog (full-screen modal),明细行有充足空间. -->
    <el-dialog :model-value="dialogVisible" :title="`${isEditing ? '编辑草稿' : '新建'}${label('purchaseOrder')}`" fullscreen destroy-on-close @update:model-value="(open: boolean) => { if (!open) closeOrderEditor() }">
      <el-form :model="form" label-width="100px">
        <el-form-item :label="label('supplier')" required>
          <el-select
            v-model="form.supplierId"
            placeholder="请选择（支持拼音/首字母，如 bjfx / beijing）"
            filterable
            :filter-method="handleSupplierFilter"
            :disabled="isEditing"
            :loading="dropdownsRefreshing"
            loading-text="正在更新供应商列表…"
            style="width: 100%"
            @change="onSupplierChange"
            @visible-change="handleSupplierVisibleChange"
          >
            <!-- 简称优先: 全称太长时下拉认不出来, 这是客户提简称的原始诉求 -->
            <!-- R-4 (2026-08-20): 自定义 filter-method 接拼音/首字母搜索 (见 handleSupplierFilter),
                 v-for 改成喂 filteredSuppliers 而不是原始 suppliers —— filter-method 场景下
                 el-select 内建的字面正则过滤会被整个关掉, 过滤结果必须由调用方自己维护. -->
            <el-option
              v-for="s in filteredSuppliers"
              :key="s.id"
              :label="s.shortName ? `${s.shortName}（${s.name}）` : s.name"
              :value="s.id"
            />
          </el-select>
          <UpstreamMissingHint
            v-if="suppliers.length === 0"
            description="本工厂暂无供应商，无法下采购单"
            target-module="procurement"
            require-write
            action-text="去创建供应商"
            contact-text="请联系采购或管理员先创建供应商"
            @action="goCreate('/procurement/suppliers')"
          />
        </el-form-item>
        <el-form-item label="采购类型">
          <el-radio-group v-model="form.purchaseType">
            <el-radio value="DIRECT">直接采购</el-radio>
            <el-radio value="HQ_UNIFIED">总部统采</el-radio>
            <el-radio value="URGENT">紧急采购</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="关联销售订单">
          <el-select v-model="form.relatedSalesOrderId" placeholder="可选 - 选择关联的销售订单" filterable clearable :loading="dropdownsRefreshing" loading-text="正在更新销售订单列表…" style="width: 100%">
            <el-option
              v-for="so in salesOrders"
              :key="so.id"
              :label="`${so.orderNumber} - ${so.customerName || so.customer?.name || ''}`"
              :value="so.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="期望交货">
          <el-date-picker v-model="form.expectedDeliveryDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="合同号">
          <el-input
            v-model="form.contractNumber"
            placeholder="选填 — 纸质合同号或框架合同编号，如 HT-2026-001"
            :maxlength="100"
            clearable
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结算方式">
          <el-select v-model="form.settlementType" placeholder="选填 — 选择结算方式" clearable style="width: 100%">
            <el-option label="预付" value="PREPAID" />
            <el-option label="赊销先入库" value="CREDIT_FIRST" />
            <el-option label="未到票" value="NO_INVOICE" />
            <el-option label="月结" value="MONTHLY" />
            <el-option label="账期" value="CREDIT_PERIOD" />
            <el-option label="现结" value="IMMEDIATE" />
          </el-select>
        </el-form-item>
        <el-form-item label="开票提醒天数">
          <el-input-number
            v-model="form.invoiceReminderDays"
            :min="0"
            :max="365"
            placeholder="选填 — 收货后 N 天未收票则提醒"
            style="width: 200px"
          />
          <span style="margin-left: 8px; color: #909399; font-size: 13px">天（0 = 不提醒，空 = 使用工厂默认）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <CanvasDynamicFields v-model="form.customFields" module-code="purchase_order" />
        <template v-if="!isEditing">
          <el-divider>附件</el-divider>
          <el-alert
            v-if="createdOrderId"
            type="warning"
            :closable="false"
            show-icon
            title="采购订单已经创建，当前只处理附件重试；不会再次创建订单。"
            style="margin-bottom: 10px"
          />
          <AttachmentDropZone
            ref="createAttachmentRef"
            entity-type="PURCHASE_ORDER"
            :entity-id="createdOrderId"
            :factory-id="factoryId"
            business-tag="PURCHASE_DOC"
            :auto-upload="false"
            @queue-change="attachmentQueue = $event"
          />
        </template>
        <el-divider>{{ label('rawMaterial') }}明细</el-divider>
        <el-alert
          v-if="form.supplierId && supplierMaterialRelations.length === 0"
          type="warning"
          :closable="false"
          show-icon
          title="该供应商尚未维护可采购原料，请先在供应商详情的“供应原料”中建立关系。"
          style="margin-bottom: 12px"
        />
        <div class="item-detail-scroll">
        <div class="item-row item-header">
          <!-- May 7 2026 用户反馈: 数量/单价/箱数 input-number 控件 -/+ 占两端,
               value 字段被挤压看不到数字. fullscreen dialog 1200px+ 有充足空间,
               把所有列加宽确保 3 位以上数字 + 小数点 + -/+ 控件都能完整显示.
               Aug 20 2026 反馈: 未税单价/税率两列把派生数字堆在控件下方, 撑成三行,
               右侧大片空白闲置 —— 拆成独立列, 行高压回一行 (M-2).
               Aug 20 2026 反馈2: 采购数量(5位数+precision=3)/未税单价(6位数+precision=2)
               仍会被两端 -/+ 控件遮挡 —— 改用 controls-position="right" 把控件收到右侧
               单侧, 并按实测(真实 el-input-number DOM, controls-position=right 时
               padding 开销固定 57px)再加宽: 采购数量 120→140px, 未税单价输入框
               112→130px(price-input-cell 容器同步 140→158px, 含 tooltip 图标).
               ⚠️ 表头 span 宽度与输入框/容器宽度是两处, 改宽度必须同步改, 只改一处会错位. -->
          <span style="width: 210px"><span class="req-star">*</span>供应原料</span>
          <span style="width: 170px"><span class="req-star">*</span>采购规格</span>
          <span style="width: 140px"><span class="req-star">*</span>采购数量</span>
          <span style="width: 90px">采购单位</span>
          <span style="width: 110px">折合入库量</span>
          <span style="width: 158px">未税单价</span>
          <span style="width: 100px">金额</span>
          <span style="width: 110px"><span class="req-star">*</span>税率</span>
          <span style="width: 90px">税额</span>
          <span style="width: 110px">含税金额</span>
          <span style="width: 150px">合同号</span>
          <span style="width: 60px">操作</span>
        </div>
        <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
          <el-select
            v-model="item.supplierMaterialId"
            placeholder="选择该供应商的原料"
            filterable
            :disabled="!form.supplierId"
            style="width: 210px"
            @change="onSupplierMaterialChange(item)"
          >
            <el-option
              v-for="relation in supplierMaterialRelations"
              :key="relation.id"
              :label="`${relation.materialName}${relation.supplierMaterialCode ? ` · ${relation.supplierMaterialCode}` : ''}`"
              :value="relation.id"
            />
          </el-select>
          <el-select
            v-model="item.purchasePackagingSpecId"
            :placeholder="specsForItem(item).length > 0 ? '选择采购规格' : '不涉及（按基本单位）'"
            :disabled="!item.supplierMaterialId || specsForItem(item).length === 0"
            style="width: 170px"
            @change="onPurchaseSpecChange(item)"
          >
            <el-option
              v-for="spec in specsForItem(item)"
              :key="spec.id"
              :label="`${spec.name} · 1${displayUnit(spec.purchasePackageUnit)}=${spec.factor}${displayUnit(spec.inventoryBaseUnit)}`"
              :value="spec.id"
            />
          </el-select>
          <el-input-number v-model="item.quantity" :min="0.001" :precision="3" placeholder="采购数量" controls-position="right" style="width: 140px" />
          <el-input :model-value="displayUnit(item.unit) || '-'" disabled style="width: 90px" />
          <div class="inventory-preview" style="width: 110px">{{ inventoryQuantityPreview(item) }}</div>
          <div class="price-input-cell" style="width: 158px">
            <el-input-number
              v-model="item.unitPrice"
              :min="0"
              :precision="2"
              placeholder="未配置采购价"
              controls-position="right"
              style="width: 130px"
              @change="onUnitPriceEdited(item)"
            />
            <el-tooltip placement="top">
              <template #content>{{ priceSourceLabel(item.priceSource) }} · 元/{{ displayUnit(item.priceUnit) || '-' }}</template>
              <el-icon :class="['price-source-icon', { 'price-source-icon--missing': item.priceSource === 'MISSING' }]"><QuestionFilled /></el-icon>
            </el-tooltip>
          </div>
          <div class="amount-cell" style="width: 100px">
            <span v-if="itemAmountPreview(item).amount != null">{{ itemAmountPreview(item).amount?.toFixed(2) }}</span>
            <span v-else class="amount-cell--pending">{{ itemAmountPreview(item).message }}</span>
          </div>
          <div class="tax-select-cell" style="width: 110px">
            <el-tooltip :disabled="!validateTaxRate(item.taxRate)" :content="validateTaxRate(item.taxRate) || ''" placement="top">
              <el-select
                v-model="item.taxRate"
                placeholder="请选择税率"
                filterable
                allow-create
                default-first-option
                style="width: 100%"
                :class="{ 'is-error-select': validateTaxRate(item.taxRate) }"
              >
                <el-option
                  v-for="opt in commonTaxRateOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-tooltip>
          </div>
          <div class="amount-cell" style="width: 90px">{{ itemTaxPreview(item) ? itemTaxPreview(item)?.tax.toFixed(2) : '-' }}</div>
          <div class="amount-cell" style="width: 110px">{{ itemTaxPreview(item) ? itemTaxPreview(item)?.taxed.toFixed(2) : '-' }}</div>
          <el-input
            v-model="item.contractNumber"
            maxlength="64"
            placeholder="选填 如 SAN-16572"
            style="width: 150px"
          />
          <el-button type="danger" link @click="removeItem(idx)" :disabled="form.items.length <= 1" style="width: 60px">删除</el-button>
        </div>
        <el-button style="width: 100%; margin-top: 8px" @click="addItem">+ 添加行</el-button>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="closeOrderEditor">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">
          {{ createdOrderId ? (attachmentQueue.failed ? '重试附件并完成' : '完成') : (isEditing ? '保存修改' : '创建') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- AI 对话录入 -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PURCHASE_ORDER_CONFIG"
      @fill-form="handleAiFill"
    />

    <!-- PR #872 (#860 follow-up) — 转发分享链接 dialog (replaces (开发中) chip). -->
    <ForwardShareDialog
      v-model:visible="forwardDialogVisible"
      :factory-id="factoryId"
      entity-type="PurchaseOrder"
      entity-type-label="采购单"
      :entity-id="forwardEntityId"
      :entity-label="forwardEntityLabel"
    />

    <!-- PR #866 (#860 follow-up) — 退货 dialog. Wires existing ReturnOrderController. -->
    <CreateReturnOrderDialog
      v-if="returnDialogRow"
      v-model="returnDialogVisible"
      :factory-id="factoryId"
      return-type="PURCHASE_RETURN"
      :source-order-id="String(returnDialogRow.id)"
      :source-order-number="String(returnDialogRow.orderNumber || returnDialogRow.id)"
      :counterparty-id="String(returnDialogRow.supplierId || '')"
      :counterparty-name="String(returnDialogRow.supplierName || (returnDialogRow.supplier as TableRow)?.name || returnDialogRow.supplierId || '-')"
      :items="returnDialogItems"
      @success="handleReturnSuccess"
    />
  </div>
  </CanvasAwareWrapper>
</template>

<style lang="scss" scoped>
.wide-table :deep(.el-scrollbar__bar.is-horizontal) { opacity: 1; }
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 20px; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: baseline; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
    .data-count { font-size: 13px; color: #909399; }
  }
}
.search-bar { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
.item-detail-scroll { overflow-x: auto; padding-bottom: 4px; }
.item-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; width: max-content; min-width: 100%; }
.price-input-cell { display: flex; align-items: center; gap: 4px; }
.price-source-icon { color: #909399; font-size: 14px; cursor: help; flex-shrink: 0; }
.price-source-icon--missing { color: #e6a23c; }
.amount-cell {
  text-align: right; font-size: 13px; color: #303133; padding-right: 6px;
  font-variant-numeric: tabular-nums; font-feature-settings: 'tnum';
}
.amount-cell--pending { color: #909399; font-size: 12px; }
.inventory-preview { color: #303133; font-size: 13px; text-align: center; }
.tax-select-cell { :deep(.is-error-select .el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset; } }
.item-header { font-size: 13px; font-weight: 600; color: #606266; margin-bottom: 4px;
  span { text-align: center; display: inline-block; }
}
.req-star { color: #f56c6c; margin-right: 2px; }
</style>
