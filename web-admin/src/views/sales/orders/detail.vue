<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get, post, put } from '@/api/request';
import OrderItemsEditor, { type OrderItemRow } from './components/OrderItemsEditor.vue';
import { initialTaxIncludedUnitPrice } from './salesOrderNewLineContract';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import DocumentTraceDrawer from '@/components/DocumentTraceDrawer.vue';
import AttachmentDropZone from '@/components/attachment/AttachmentDropZone.vue';
import type { Attachment } from '@/api/attachment';
import { getSalesOrderDocumentTrace } from '@/api/documentTrace';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { displayUnit } from '@/utils/unitPricing';
import { handleCatchError } from '@/utils/errorToast';
import { enumLabel } from '@/utils/enumDisplay';
import { getCustomer, type Customer } from '@/api/customer';
import {
  getSalesOrderApprovalProgress,
  type SalesOrderApprovalProgress,
} from '@/api/salesFinanceReview';
import {
  actionableDeliveryCount,
  deliveryActionState,
  deliveryCapacityByLine,
  deliveryMoney,
  deliveryTransportAggregate,
  formatBusinessDateTime,
  pendingReshipmentMaster,
  productionActionState,
  resolveDeliveryAddress,
  shipmentValidationError,
  shortShipmentSummary,
} from './salesOrderGuards';
// T4-D1 (issue #525): F006 customer asked for 来源仓库 (总仓/线边仓) per line item.
// 2026-07-02 fix: resolve against the loaded warehouse list (DB name is
// authoritative) instead of always showing the hardcoded WH-WKS/WH-LOG label
// — see utils/warehouse.ts for the LIUSHANMEN "同仓库多名字" incident.
import { warehouseNameByCode, type WarehouseCodeNameLike } from '@/utils/warehouse';
import { allocationEmptyStateDesc, allocationEmptyStateTitle } from './allocationEmptyState';
import type { TableRow } from '@/types/api';
import {
  customerMaterialReceivingStatusLabel,
  customerMaterialCategoryLabel,
  materialSupplyModeLabel,
  processingModeLabel,
  warehouseReceivingRoute,
  type CustomerSuppliedMaterialRequirement,
} from './salesOrderSupplyContract';
import { toDateString } from '@/utils/dateFormat';
import { formatUnitPrice, salesOrderDisplayMoney, warehouseNameById, type WarehouseIdNameLike } from './salesOrderDisplay';
import { createAutoFullChildShipment } from './deliveryAutoChild';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
/** 产品级利润报表要 finance|procurement —— 与「能看到金额」是两个判据。 */
const canViewProfitReport = computed(() => permissionStore.canAccess('finance') || permissionStore.canAccess('procurement'));
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('sales'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
// 🔴 2026-08-22: 开票/收款这几个入口原来挂的是 canWrite('sales'),
//   而它们调的端点要过**两道闸**, 第二道 @RequireModule("finance_ar"/"finance")
//   映射到 permission 模块 **finance** ⇒ 销售/生产/仓储主管看得见、点必 403。
// ⛔ 不给这些角色开 finance —— 生成应收/收款流水是财务动作。
// ⚠️ 不能用 canViewPrice 代替: 「看得到金额」和「能记一笔账」是两件事。
const canWriteFinance = computed(() => permissionStore.canWrite('finance'));
const canViewLinkedPurchases = computed(() => permissionStore.canAccess('procurement'));
const canAccessWarehouse = computed(() => permissionStore.canAccess('warehouse'));
const orderId = computed(() => route.params.id as string);

const loading = ref(false);
const submitting = ref(false);
const notFound = ref(false);
const order = ref<TableRow | null>(null);

// 客户 2026-07-30: 单据上看不到上下游、也没有「返回原单」——「严重影响工作效率」
const documentTraceVisible = ref(false);
function loadDocumentTrace() {
  const fid = factoryId.value;
  // 禁止降级: 没有工厂上下文就明确报错, 不用空串去调接口拿一个看不懂的 400
  if (!fid) return Promise.reject(new Error('当前账号未绑定工厂，无法查询单据追踪'));
  return getSalesOrderDocumentTrace(fid, orderId.value);
}
const approvalProgress = ref<SalesOrderApprovalProgress | null>(null);
const deliveries = ref<TableRow[]>([]);
const signatureDialogVisible = ref(false);
const signatureTarget = ref<TableRow | null>(null);
const signatureUploadKey = ref(0);
const signaturePhotoUrls = ref<string[]>([]);
const signatureQueue = ref({ total: 0, pending: 0, failed: 0 });
const signatureForm = ref({ signedByName: '', remark: '' });
const associatedProductionPlans = ref<TableRow[]>([]);
const productionPlansLoading = ref(false);
const productionPlansFailed = ref(false);
// 🔴 2026-08-22: 403 单独一态。⛔ 不能和「加载失败」合并 ——
//   后者的指引是「刷新后重试」, 而权限不足时刷新一万次也没用。
const productionPlansForbidden = ref(false);
const deliveriesLoading = ref(false);
const deliveriesFailed = ref(false);
const customer = ref<Customer | null>(null);
const invoices = ref<TableRow[]>([]);
const payments = ref<TableRow[]>([]);
// 空标签页有两种含义, 必须分得开: '' = 确实没有收款记录; 非空 = 这次没能查到(权限/网络/后端).
const paymentsLoadError = ref<string>('');
const purchaseOrders = ref<TableRow[]>([]);
const activeTab = ref('detail');

const customerSuppliedRequirements = computed<CustomerSuppliedMaterialRequirement[]>(() =>
  Array.isArray(order.value?.suppliedMaterials)
    ? order.value.suppliedMaterials as CustomerSuppliedMaterialRequirement[]
    : [],
);
const suppliedMaterialOptions = ref<TableRow[]>([]);
const isCustomerSuppliedOrder = computed(() =>
  order.value?.processingMode === 'TOLL_PROCESSING'
    && order.value?.materialSupplyMode === 'CUSTOMER_SUPPLIED',
);
const isPrestockedCustomerStock = computed(() =>
  isCustomerSuppliedOrder.value
    && order.value?.customerStockFulfillmentMode === 'PRESTOCKED',
);
const isOrderDrivenCustomerSuppliedOrder = computed(() =>
  isCustomerSuppliedOrder.value && !isPrestockedCustomerStock.value,
);
type SalesOrderReservation = {
  reservationId: string;
  finishedGoodsBatchId: string;
  batchNumber: string;
  productTypeId: string;
  productName: string;
  reservedQuantity: number;
  unit: string;
  status: string;
};
const activeReservations = ref<SalesOrderReservation[]>([]);
const reservationsLoading = ref(false);
const reservationsFailed = ref(false);

function customerMaterialProgress(row: CustomerSuppliedMaterialRequirement): string {
  const expected = Number(row.expectedQuantity || 0);
  const received = Number(row.receivedQuantity || 0);
  const remaining = row.remainingQuantity == null
    ? Math.max(0, expected - received)
    : Number(row.remainingQuantity);
  const unit = displayUnit(row.unit) || '未配置';
  return `应收 ${expected}${unit} / 已收 ${received}${unit} / 待收 ${remaining}${unit}`;
}

function goWarehouseReceiving() {
  if (!order.value) return;
  router.push(warehouseReceivingRoute(order.value));
}

const deliveryDialogVisible = ref(false);
/**
 * 刚建出来的发货单号 —— 切到「发货记录」后把那一行高亮, 让人一眼找到。
 *
 * 只在本次创建后有值; 重新加载页面就没有了 —— 它描述的是"你刚才那一下", 不是单据属性。
 */
const highlightedDeliveryNumber = ref('');
const deliveryIdempotencyKey = ref('');
const deliveryForm = ref<{ deliveryAddress: string; logisticsCompany: string; trackingNumber: string; deliveryMethod: 'LOGISTICS' | 'SELF_DELIVERY' | 'SELF_PICKUP'; deliveryMode: 'FULL' | 'PARTIAL'; items: TableRow[] }>({
  deliveryAddress: '', logisticsCompany: '', trackingNumber: '', deliveryMethod: 'LOGISTICS', deliveryMode: 'FULL', items: [],
});
const shipmentDialogVisible = ref(false);
const shipmentParent = ref<TableRow | null>(null);
const shipmentForm = ref({
  idempotencyKey: '',
  plannedShipmentDate: toDateString(new Date()),
  deliveryMethod: 'LOGISTICS',
  logisticsCompany: '',
  trackingNumber: '',
  deliveryAddress: '',
  remark: '',
  items: [] as TableRow[],
});

// T-SHORT-SHIP (owner 2026-09-04 拍板): 短交结案 — 镜像采购侧「少收关闭」
// (PendingPurchaseReceivingPanel.vue 的 closeDialogVisible/closeForm), 多一道
// shortCloseAcknowledged 勾选确认 (owner 原话「必须是显式的、经过确认的点击，
// 绝不能隐式发生」), 提交按钮在勾选前禁用。
const shortCloseDialogVisible = ref(false);
const shortCloseSubmitting = ref(false);
const shortCloseAcknowledged = ref(false);
const shortCloseForm = ref<{ reasonCode: string; notes: string }>({ reasonCode: '', notes: '' });
const SHORT_CLOSE_REASON_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'CUSTOMER_WAIVED_REMAINDER', label: '客户同意放弃剩余数量' },
  { value: 'SOURCE_SHORTAGE', label: '原料/成品已耗尽，短期无法补齐' },
  { value: 'ORDER_BALANCE_CANCELLED', label: '客户取消订单剩余部分' },
  { value: 'OTHER', label: '其他原因' },
];

type AllocationSummary = {
  allocated: number;
  planned: number;
  complete: boolean;
  /** 整单都是物料行 —— 没有任何成品批次要分。 */
  materialOnly: boolean;
  materialLines: number;
  /** 成品行单位一致时的显示单位; 混单位时为空串(不标, 避免拿一个单位去标另一种的数)。 */
  unit: string;
  /** 真实批次号与该批次分配数量，供销售/仓储追溯。 */
  batches: string[];
};
const allocationSummary = ref<Record<string, AllocationSummary>>({});
const masterDeliveries = computed(() => deliveries.value.filter((row) => !row.parentDeliveryId && row.recordRole !== 'SHIPMENT'));
const shipmentRows = computed(() => deliveries.value.filter((row) => row.parentDeliveryId || row.recordRole === 'SHIPMENT'));
const deliveryAddressMissing = computed(() => !deliveryForm.value.deliveryAddress.trim());
const deliveryTotals = computed(() => deliveryMoney(deliveryForm.value.items));
const deliveryCapacity = computed(() => deliveryCapacityByLine(
  Array.isArray(order.value?.items) ? order.value.items as TableRow[] : [],
  deliveries.value,
));
const productionAction = computed(() => productionActionState(
  Array.isArray(order.value?.items) ? order.value.items as TableRow[] : [],
  associatedProductionPlans.value,
  productionPlansLoading.value,
  productionPlansFailed.value,
  productionPlansForbidden.value,
));
const deliveryAction = computed(() => deliveryActionState(
  Array.isArray(order.value?.items) ? order.value.items as TableRow[] : [],
  deliveries.value,
  deliveriesLoading.value,
  deliveriesFailed.value,
));
const actionableDeliveries = computed(() => actionableDeliveryCount(deliveries.value));
// T-SHORT-SHIP (owner 2026-09-04 拍板): 短交 = 实发缺口, 与 deliveryCapacity.remaining
// (安排量缺口) 是两码事 —— 母单一旦建出来就能把 remaining 占满, 而实发可以远小于安排量
// (实拍样本 SO-20260904-0001: 10kg 全部安排掉, 只有 9.8kg 真的发出去)。两个按钮都只在
// 「部分发货 + 实发缺口 > 0」时出现, 且要求显式点击 —— 见 handleReshipOutstanding /
// openShortCloseDialog, 任何一条路径都不会自动发生。
const shortShipment = computed(() => shortShipmentSummary(
  Array.isArray(order.value?.items) ? order.value.items as TableRow[] : [],
));
const showShortShipmentActions = computed(() =>
  order.value?.status === 'PARTIAL_DELIVERED' && shortShipment.value.outstanding > 0.000001);
const transportAggregate = computed(() => deliveryTransportAggregate(deliveries.value));
const transportDisplayStatus = computed(() => deliveries.value.length > 0
  ? transportAggregate.value
  : String(order.value?.transportPlanStatus || 'PLANNING'));
const signatureSubmissionReady = computed(() => Boolean(
  signatureTarget.value
    && signatureForm.value.signedByName.trim()
    && signaturePhotoUrls.value.length > 0
    && signatureQueue.value.pending === 0
    && signatureQueue.value.failed === 0,
));

function existingSignaturePhotoUrls(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((url): url is string => typeof url === 'string' && Boolean(url));
  if (typeof value !== 'string' || !value.trim()) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed)
      ? parsed.filter((url): url is string => typeof url === 'string' && Boolean(url))
      : [];
  } catch {
    return [];
  }
}

function openSignatureDialog(row: TableRow) {
  signatureTarget.value = row;
  signatureForm.value = {
    signedByName: String(row.signedByName || ''),
    remark: String(row.signatureRemark || ''),
  };
  signaturePhotoUrls.value = existingSignaturePhotoUrls(row.signaturePhotoUrls);
  signatureQueue.value = { total: 0, pending: 0, failed: 0 };
  signatureUploadKey.value += 1;
  signatureDialogVisible.value = true;
}

function handleSignatureUploaded(attachment: Attachment) {
  if (attachment.fileUrl && !signaturePhotoUrls.value.includes(attachment.fileUrl)) {
    signaturePhotoUrls.value.push(attachment.fileUrl);
  }
}

function handleSignatureDeleted(attachment: Attachment) {
  signaturePhotoUrls.value = signaturePhotoUrls.value.filter((url) => url !== attachment.fileUrl);
}

function handleSignatureQueueChange(summary: { total: number; pending: number; failed: number }) {
  signatureQueue.value = summary;
}

// T-RTA (issue #531): F006 客户反馈 第四次会议 956-1037 — 申请退货 dialog.
// Builds CreateReturnOrderRequest with returnType=SALES_RETURN + counterpartyId=customerId
// + sourceOrderId=orderId. Items default from order.items with checked=true, qty=delivered.
const returnDialogVisible = ref(false);
const returnSubmitting = ref(false);
type ReturnDialogItem = {
  productTypeId: string;
  itemName: string;
  unitPrice?: number;
  maxQuantity: number; // delivered (cap for refund)
  selected: boolean;
  quantity: number;
  reason: string;
};
// T-RTA business logic (issue #571): withGoods toggle drives backend branching.
//   true  = 有货退回 → 入成品仓不良品区 (status=DEFECTIVE, 不计入可售库存) → AR冲减 at completion
//   false = 无食物退货 → 直接退款, 无库存动作 → AR冲减 immediate on approve
const returnForm = ref<{ returnDate: string; reasonCategory: string; reason: string; remark: string; withGoods: boolean; items: ReturnDialogItem[] }>({
  returnDate: toDateString(new Date()),
  reasonCategory: '',
  reason: '',
  remark: '',
  withGoods: true, // default 实物退货 (customer's primary case per 第四次:956-1037)
  items: [],
});

// fool-proof-design Rule 3 (约束选择而非自由翻找): 退货原因改标准 enum 下拉, 选"其他"才展开
// textarea 补充说明. Mirror components/dialog/CreateReturnOrderDialog.vue's pattern —
// reasonCategory !== 其他 时, reasonCategory 本身就是提交的 reason 文本; 选"其他"时
// returnForm.reason 走独立 textarea (必填).
const RETURN_REASON_OPTIONS = ['客户验收不合格', '包装破损', '滞销退仓', '质量问题', '其他'] as const;
function onReturnReasonCategoryChange(val: string): void {
  if (val && val !== '其他') {
    returnForm.value.reason = val;
  } else {
    returnForm.value.reason = '';
  }
}

// 批次分配对话框 (P0-13 强制批次追溯 — R16 深度测试后补完)
const batchAllocDialogVisible = ref(false);
// 2026-08-12: 物料(原料/辅料/包材)行没有【成品】批次可分配 —— 它的货在 material_batches,
// 发货时由后端 FIFO 自动扣(SalesServiceImpl.deductMaterialInventory)。
// 不认出来的话, 分配对话框会对物料行显示「全厂无可发货成品库存, 请先完成生产入库」——
// 那句话是错的(货就在物料仓里), 会把仓管直接劝退。
const materialIdSet = ref<Set<string>>(new Set());
const batchAllocLoading = ref(false);
// 🔴 C1 (2026-07-05): unit = 该行数量的计量单位 (与发货行 item.unit 同口径, 后端已按此换算过
// availableQuantity/allocatedQty — 见 recommendFifo unit 参数)。batchNativeUnit = 该批次入库时
// 记录的原生单位, 与 unit 不同时说明后端做了跨单位换算 (如批次按 kg 入库, 发货按 盒 结算)。
type AllocRow = { finishedGoodsBatchId: string; batchNumber: string; productionDate: string; availableQuantity: number; allocatedQty: number; unit: string; batchNativeUnit: string };
// 🔴 G1: sourceWarehouseCode (发货行声明的来源仓, 空=未声明) + stockWarehouses (该产品实际有货的仓库 code,
// 仅在无推荐批次时查, 用于诚实空态提示「成品在 X 仓」而非误导的「请先生产」)。
type AllocItem = { deliveryItemId: string; productName: string; productTypeId: string; deliveredQuantity: number; unit: string; sourceWarehouseCode: string; allocations: AllocRow[]; stockWarehouses: string[]; isMaterial: boolean };
const batchAllocForm = ref<{ deliveryId: string; deliveryNumber: string; items: AllocItem[] }>({
  deliveryId: '', deliveryNumber: '', items: [],
});

// 上传发票 PDF 对话框 (V3 P0-3c)
const issueDialogVisible = ref(false);
const issueTargetInvoiceId = ref<string>('');
const issuePdfFile = ref<File | null>(null);

// 收款登记对话框
const paymentDialogVisible = ref(false);
const paymentForm = ref<{ amount: number; paymentMethod: string; paymentDate: string; paymentReference: string; remark: string; receiptUrl: string }>({
  amount: 0, paymentMethod: 'BANK_TRANSFER', paymentDate: '', paymentReference: '', remark: '', receiptUrl: '',
});
const receiptFile = ref<File | null>(null);
const receiptUploading = ref(false);

type SalesOrderActionResponse = {
  success?: boolean;
  message?: string;
  data?: {
    status?: unknown;
  };
};

function responseOrderStatus(response: unknown): string {
  const res = response as SalesOrderActionResponse;
  return typeof res.data?.status === 'string' ? res.data.status : '';
}

function orderAmountHint(row: TableRow | null): string {
  const amount = Number(row?.totalAmount || 0);
  return formatAmount(Number.isFinite(amount) ? amount : 0);
}

function approvalDecisionHint(row: TableRow | null): string {
  const amount = Number(row?.totalAmount || 0);
  const safeAmount = Number.isFinite(amount) ? amount : 0;
  return `订单金额：${formatAmount(safeAmount)}。系统将按当前已发布的销售订单 OA 规则自动路由；提交后可在个人 OA 查看实际节点和处理人。`;
}

function actionSuccessMessage(action: string, labelText: string, response: unknown): string {
  const status = responseOrderStatus(response);
  if ((action === 'confirm' || action === 'submit-for-review') && status === 'FINANCE_APPROVED') {
    return `${labelText}成功，已按当前 OA 规则自动通过`;
  }
  if ((action === 'confirm' || action === 'submit-for-review') && status === 'PENDING_FINANCE_REVIEW') {
    return `${labelText}成功，已进入 OA 审批`;
  }
  return `${labelText}成功`;
}

function actionConfirmMessage(action: string, labelText: string): string {
  const orderNumber = String(order.value?.orderNumber || orderId.value);
  if (action === 'confirm') {
    return `确认销售订单 ${orderNumber} 并提交 OA 路由？\n\n${approvalDecisionHint(order.value)}`;
  }
  if (action === 'submit-for-review') {
    return `确认将销售订单 ${orderNumber} 提交 OA 审批？\n\n${approvalDecisionHint(order.value)}`;
  }
  return `确认${labelText}销售订单 ${orderNumber}？订单金额：${orderAmountHint(order.value)}`;
}

const statusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  CONFIRMED: { text: '已确认', type: '' },
  PENDING_FINANCE_REVIEW: { text: '待 OA 审批', type: 'warning' },
  FINANCE_APPROVED: { text: '审批已通过', type: 'success' },
  FINANCE_REJECTED: { text: '审批已驳回', type: 'danger' },
  PROCESSING: { text: '处理中', type: 'warning' },
  PARTIAL_DELIVERED: { text: '部分发货', type: 'warning' },
  COMPLETED: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' },
  SHORT_CLOSED: { text: '短交结案', type: 'info' },
};

const delStatusMap: Record<string, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  // Issue #740: 销售创建后等仓库 confirm 的中间态
  PENDING_WAREHOUSE_CONFIRM: { text: '待仓库确认', type: 'warning' },
  PENDING_SPLIT: { text: '待分批', type: 'warning' },
  PARTIALLY_SCHEDULED: { text: '部分已安排', type: 'warning' },
  FULLY_SCHEDULED: { text: '已全部安排', type: '' },
  PARTIALLY_SHIPPED: { text: '部分已发货', type: 'warning' },
  PICKED: { text: '已拣货', type: '' },
  SHIPPED: { text: '已发货', type: 'warning' },
  DELIVERED: { text: '已签收', type: 'success' },
  RETURNED: { text: '已退回', type: 'danger' },
  CANCELLED: { text: '已取消', type: 'info' },
};

const invoiceStatusMap: Record<string, { text: string; type: string }> = {
  REQUESTED: { text: '待审核', type: 'warning' },
  APPROVED: { text: '已审核', type: '' },
  REJECTED: { text: '已驳回', type: 'danger' },
  ISSUED: { text: '已开票', type: 'success' },
  CANCELLED: { text: '已取消', type: 'info' },
};

const paymentStatusMap: Record<string, { text: string; type: string }> = {
  PENDING: { text: '待确认', type: 'warning' },
  VERIFIED: { text: '已确认', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
};

// V3 P0-9 / v1 §2.4.4 — 订单级状态 (区别于 record-level paymentStatusMap/invoiceStatusMap)
const orderPaymentStatusMap: Record<string, { text: string; type: string }> = {
  UNPAID: { text: '待收款', type: 'info' },
  PARTIAL: { text: '部分收款', type: 'warning' },
  PAID: { text: '已收款', type: 'success' },
};
const orderInvoiceStatusMap: Record<string, { text: string; type: string }> = {
  NOT_INVOICED: { text: '待开票', type: 'info' },
  PARTIAL_INVOICED: { text: '部分开票', type: 'warning' },
  FULLY_INVOICED: { text: '已开票', type: 'success' },
};
const orderTransportStatusMap: Record<string, { text: string; type: string }> = {
  PLANNING: { text: '待出厂', type: 'info' },
  IN_PRODUCTION: { text: '生产中', type: 'warning' },
  IN_TRANSIT: { text: '运输中', type: 'warning' },
  DELIVERED: { text: '已发货', type: 'success' },
  PARTIALLY_RECEIVED: { text: '部分签收', type: 'warning' },
  RECEIVED: { text: '已签收', type: 'success' },
};

const orderFormulas = ref<TableRow>({});

// ─── Task 4: DRAFT 编辑产品行 ───
const { goCreate } = useCreateAndReturn();

const editItemsVisible = ref(false);
const editItemsRows = ref<OrderItemRow[]>([]);
const editItemsSaving = ref(false);
const products = ref<Array<{
  id: string;
  name: string;
  unit?: string;
  code?: string;
  specification?: string;
  packageSpec?: string;
  productCategory?: string;
  category?: string;
}>>([]);

const isDraft = computed(() => String((order.value as any)?.status || '').toUpperCase() === 'DRAFT');

async function loadProductsForEdit() {
  if (!factoryId.value) return;
  try {
    // Mirror list.vue loadProducts — same endpoints / response shape.
    // /sellable 是销售订单专用白名单；物料只能在客供物料区维护。
    const res = await get(`/${factoryId.value}/product-types/sellable`, { _silent: true } as never);
    const unwrap = (d: any) => (Array.isArray(d) ? d : (d?.content ?? [])) as any[];
    materialIdSet.value = new Set();
    const list = unwrap(res?.data);
    products.value = list.map((p: any) => ({
      id: String(p.id),
      name: String(p.name ?? p.productName ?? ''),
      unit: p.unit,
      code: p.code,
      specification: p.specification,
      packageSpec: p.packageSpec,
      productCategory: p.productCategory,
      category: p.category,
    }));
  } catch { products.value = []; }
}

// 2026-07-02 fix (LIUSHANMEN "同仓库多名字"): 来源仓库列必须显示真实 DB name,
// 不能一律硬编码"总仓/线边仓" — 客户可在仓库配置里重命名 WH-LOG/WH-WKS。
const warehouses = ref<(WarehouseCodeNameLike & WarehouseIdNameLike)[]>([]);

async function loadWarehouses() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/factory/warehouses`, { _silent: true } as never);
    warehouses.value = Array.isArray(res?.data) ? res.data : [];
  } catch { warehouses.value = []; }
}

function sourceWarehouseLabel(code: string | null | undefined): string {
  return warehouseNameByCode(warehouses.value, code);
}

function targetWarehouseLabel(row: CustomerSuppliedMaterialRequirement): string {
  return String(row.targetWarehouseName || '').trim()
    || warehouseNameById(warehouses.value, row.targetWarehouseId);
}

function customerMaterialCategory(row: CustomerSuppliedMaterialRequirement): string {
  const option = suppliedMaterialOptions.value.find((candidate) => String(candidate.id) === String(row.materialTypeId));
  return customerMaterialCategoryLabel(
    row.mainCategory || option?.mainCategory,
    row.category || option?.category,
  );
}

async function loadSuppliedMaterialOptions() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/raw-material-types/active`, { _silent: true } as never);
    const data = res?.data;
    suppliedMaterialOptions.value = Array.isArray(data) ? data : Array.isArray(data?.content) ? data.content : [];
  } catch {
    suppliedMaterialOptions.value = [];
    ElMessage.error('客供物料分类加载失败，请刷新后重试');
  }
}

const displayOrderMoney = computed(() => salesOrderDisplayMoney(
  Array.isArray(order.value?.items) ? order.value.items as TableRow[] : [],
));

function openEditItems() {
  const existing = Array.isArray((order.value as any)?.items) ? (order.value as any).items : [];
  editItemsRows.value = existing.length
    ? existing.map((it: any) => {
        // 🔒 2026-09-02: unitPrice 存储口径没变(仍是未税, 见 SalesOrderItem.getLineAmount())——
        // 已有产品行不需要任何数据迁移。这里只是给「含税单价」这个新增的界面录入字段
        // 算一个初始展示值(未税 unitPrice × (1+税率/100)), 方便继续编辑; 不改写 unitPrice 本身。
        const unitPrice = Number(it.unitPrice ?? 0);
        const taxRate = Number(it.taxRate ?? 13);
        return {
          productTypeId: String(it.productTypeId ?? ''),
          productName: it.productName,
          quantity: Number(it.quantity ?? 0),
          unit: it.unit ?? '份',
          unitPrice,
          taxIncludedUnitPrice: initialTaxIncludedUnitPrice({ unitPrice, taxRate }),
          taxRate,
          specification: it.specification ?? '',
        };
      })
    : [{ productTypeId: '', quantity: 0, unit: '份', unitPrice: 0, taxIncludedUnitPrice: 0, taxRate: 13 }];
  editItemsVisible.value = true;
}

function goCreateProductType() {
  goCreate('/system/products');
}

async function saveEditItems() {
  if (!factoryId.value || !orderId.value) return;
  const items = editItemsRows.value
    .filter((r) => r.productTypeId)
    // taxIncludedUnitPrice 仅供界面录入 (见 OrderItemsEditor.vue 顶部注释), 不进后端 DTO。
    .map(({ taxIncludedUnitPrice: _taxIncludedUnitPrice, ...r }) => ({
      ...r, quantity: r.quantity ?? 0, unitPrice: r.unitPrice ?? 0, taxRate: r.taxRate ?? 0,
    }));
  if (items.length === 0) { ElMessage.warning('请至少添加一个产品行'); return; }
  editItemsSaving.value = true;
  try {
    // 🔴 2026-08-07 走客供料全流程时撞到的死结:
    //   UpdateSalesOrderRequest 把 processingMode / materialSupplyMode 标了 @NotNull,
    //   而这里只发 { items, version } → 400「加工方式不能为空, 物料供应方式不能为空」。
    //   配上「单价为空不许提审」那道闸, 用户会被彻底卡住:
    //     建单时单价留空 → 提审被拦「请先补全单价」
    //       → 点「编辑产品行」补单价 → 保存被拦「加工方式不能为空」
    //         → 两边都出不去。
    //   这里把订单已有的两个值原样回传(本对话框不改它们), 不动后端契约。
    const current = order.value as {
      version?: number; processingMode?: string; materialSupplyMode?: string;
    } | null;
    const res = await put(`/${factoryId.value}/sales/orders/${orderId.value}`, {
      items,
      processingMode: current?.processingMode,
      materialSupplyMode: current?.materialSupplyMode,
      version: current?.version,
    });
    if (res.success) {
      ElMessage.success('产品行已保存');
      editItemsVisible.value = false;
      await loadOrder();
    }
  } finally { editItemsSaving.value = false; }
}

onMounted(async () => {
  await loadOrder();
  await Promise.all([loadDeliveries(), loadAssociatedProductionPlans(), loadOrderCustomer(), loadApprovalProgress()]);
  loadInvoices();
  loadPayments();
  loadPurchaseOrders();
  loadFormulas();
  loadWarehouses();
  loadSuppliedMaterialOptions();
  await loadProductsForEdit();
  if (route.query.editItems === '1' && isDraft.value) {
    openEditItems();
    const { editItems: _omit, ...rest } = route.query;
    router.replace({ query: rest as Record<string, string> });
  }
});

async function loadOrder() {
  if (!factoryId.value || !orderId.value) return;
  loading.value = true;
  notFound.value = false;
  try {
    const res = await get(`/${factoryId.value}/sales/orders/${orderId.value}`);
    if (res.success && res.data) {
      order.value = res.data;
      if (res.data.customerStockFulfillmentMode === 'PRESTOCKED') {
        await loadActiveReservations();
      } else {
        activeReservations.value = [];
        reservationsFailed.value = false;
      }
    } else {
      notFound.value = true;
      order.value = null;
    }
  } catch (e: unknown) {
    // R18-ext #283 fix: any load failure on SO detail means the order is
    // unreachable for this user — most likely 404/not-found, possibly 403.
    // Either way, render empty state rather than a blank page. Interceptor
    // already showed the server's message; don't stack a duplicate toast.
    notFound.value = true;
    order.value = null;
  }
  finally { loading.value = false; }
}

async function loadActiveReservations() {
  reservationsLoading.value = true;
  reservationsFailed.value = false;
  try {
    const res = await get<SalesOrderReservation[]>(
      `/${factoryId.value}/sales/orders/${orderId.value}/reservations`,
    );
    activeReservations.value = res.success && Array.isArray(res.data) ? res.data : [];
    reservationsFailed.value = !res.success;
  } catch {
    activeReservations.value = [];
    reservationsFailed.value = true;
  } finally {
    reservationsLoading.value = false;
  }
}

async function loadApprovalProgress() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await getSalesOrderApprovalProgress(factoryId.value, orderId.value);
    approvalProgress.value = res.success && res.data ? res.data : null;
  } catch {
    approvalProgress.value = null;
  }
}

function approvalProgressStatusLabel(status: string | null): string {
  const labels: Record<string, string> = {
    RUNNING: '审批中',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    CANCELLED: '已撤回',
    COMPLETED: '已完成',
  };
  return status ? (labels[status] ?? enumLabel(status)) : '-';
}

async function goToApprovalProgress() {
  const deepLink = approvalProgress.value?.deepLink;
  await router.push(deepLink || '/workflow/my-created');
}

async function loadFormulas() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/sales/orders/${orderId.value}/formulas`);
    if (res.success && res.data) orderFormulas.value = res.data;
  } catch { /* formula module may not be configured for this factory */ }
}

const taxGroupData = computed(() => {
  const raw = orderFormulas.value?.tax_group_sum;
  if (!Array.isArray(raw) || raw.length === 0) return null;
  return raw.map((row: TableRow) => ({
    taxRate: row.tax_rate != null ? Number(row.tax_rate) : 0,
    amount: row.agg_value != null ? Number(row.agg_value) : 0,
  })).sort((a: { taxRate: number }, b: { taxRate: number }) => a.taxRate - b.taxRate);
});

function taxIncludedUnitPrice(row: TableRow): number | null {
  if (row.unitPrice == null) return null;
  const unitPrice = Number(row.unitPrice);
  const taxRate = Number(row.taxRate || 0);
  return unitPrice * (1 + taxRate / 100);
}

function formatTaxIncludedUnitPrice(row: TableRow): string {
  const amount = taxIncludedUnitPrice(row);
  return amount == null ? '—' : formatAmount(amount);
}

function displayLineMoney(row: TableRow) {
  return salesOrderDisplayMoney([row]);
}

async function loadDeliveries() {
  if (!factoryId.value || !orderId.value) return;
  deliveriesLoading.value = true;
  deliveriesFailed.value = false;
  try {
    const res = await get(`/${factoryId.value}/sales/deliveries/by-order/${orderId.value}`);
    if (res.success) {
      deliveries.value = Array.isArray(res.data) ? res.data : [];
      await loadAllocationSummaries();
    }
  } catch {
    deliveriesFailed.value = true;
    deliveries.value = [];
  } finally {
    deliveriesLoading.value = false;
  }
}

async function loadAllocationSummaries() {
  // 🔴 2026-08-13: materialIdSet 必须先就绪。它由 loadProductsForEdit 填,
  // 而 onMounted 里那次是排在 loadDeliveries【之后】的 —— 直接依赖挂载顺序,
  // 这里会拿到空集合, 于是每一行物料都被当成成品去算「已分配」。
  if (!materialIdSet.value.size) await loadProductsForEdit();

  const next: Record<string, AllocationSummary> = {};
  for (const delivery of deliveries.value) {
    if (delivery.recordRole === 'MASTER') continue;
    const items = Array.isArray(delivery.items) ? delivery.items as TableRow[] : [];
    let planned = 0;
    let allocated = 0;
    let goodsLines = 0;
    let materialLines = 0;
    const batches: string[] = [];
    const units = new Set<string>();
    for (const item of items) {
      const productTypeId = String(item.productTypeId ?? item.productType?.id ?? '');
      // 物料行由后端在「确认发货」时按 FIFO 自动扣减, 从不产生批次分配记录。
      // 把它算进 planned 会让这一行永远停在「已分配 0/N」——看上去像仓库还欠一步,
      // 实际上没有任何事可做。这与分配对话框里那条「物料无需分配批次」必须一致。
      if (materialIdSet.value.has(productTypeId)) {
        materialLines += 1;
        continue;
      }
      goodsLines += 1;
      units.add(displayUnit(item.unit));
      planned += Number(item.deliveredQuantity || 0);
      if (!item.id) continue;
      try {
        const res = await get<TableRow[]>(`/${factoryId.value}/sales-deliveries/items/${item.id}/batch-allocations`);
        if (res.success && Array.isArray(res.data)) {
          allocated += res.data.reduce((sum, row) => sum + Number(row.allocatedQty || 0), 0);
          res.data.forEach((row) => batches.push(
            `${String(item.productName || item.productTypeId || '产品')} · ${String(row.batchNumber || row.finishedGoodsBatchId || '批次未标注')} ${Number(row.allocatedQty || 0)}${displayUnit(String(row.unit || item.unit || ''))}`,
          ));
        }
      } catch { /* fail closed: leave incomplete */ }
    }
    next[String(delivery.id)] = {
      allocated,
      planned,
      // 整单都是物料 → 没有任何批次要分, 直接算完成, 否则「继续分配」按钮永远亮着。
      // 🔴 2026-09-02: 这个 == 特意【没有】跟着缺陷 41 改成 <=。它回答的是「还有没有活要干」
      // (按钮显示"分配批次"/"继续分配"/"查看修改批次"), 不是「这份分配合不合法」——
      // 那道校验(handleBatchAllocate 的分配合计检查)已经放宽为 <=, 但已确认的 0.98/1.00 短发
      // 仍然「没分完」, 按钮理应继续提示"继续分配/修改批次", 而不是谎称"已完成"。
      complete: goodsLines === 0
        ? materialLines > 0
        : planned > 0 && Math.abs(allocated - planned) < 0.000001,
      materialOnly: goodsLines === 0 && materialLines > 0,
      materialLines,
      // 🔴 单位只在【成品行单位一致】时才敢标。原来固定取 items[0].unit,
      // 于是「2只 + 1个」被汇总成一句「3 只」——把两种单位的数加起来再随便挑一个标上。
      unit: units.size === 1 ? [...units][0] : '',
      batches,
    };
  }
  allocationSummary.value = next;
}

async function loadAssociatedProductionPlans() {
  if (!factoryId.value || !orderId.value) return;
  productionPlansLoading.value = true;
  productionPlansFailed.value = false;
  productionPlansForbidden.value = false;
  try {
    // 🔴 2026-08-22: 加 _silent。这是**可选区块**(关联生产计划), 本函数自己已经
    //   catch 并置 productionPlansFailed 降级, 但全局拦截器会先弹一个红框
    //   「操作无法完成 — 账号 xxx 缺少模块 production_plan 的访问权限」。
    //
    // ⚠️ 成因不是缺陷, 是**收窄的后果**: @RequireModule("production_plan") 经
    //   ProductionModuleRegistry 映射回 permission 模块 `production`, 而
    //   V20261030_08 按 owner 要求把 production 从 sales_manager 砍掉了
    //   (「销售主管只要 工作台+个人OA+销售管理」)。收窄是对的, 是这个调用没跟上。
    //
    // ⛔ 不给 sales 开 production 模块 —— 那等于把昨天的收窄撤回。
    // ⚠️ 只静音**这一个可选区块**, ⛔ 不动全局拦截器 —— 那道提示对真正的操作失败仍然要弹。
    const res = await get(`/${factoryId.value}/production-plans/by-sales-order/${orderId.value}`,
      { _silent: true } as never);
    associatedProductionPlans.value = res.success && Array.isArray(res.data) ? res.data : [];
  } catch (err) {
    // ⚠️ 403 = 这个账号没有生产模块权限(**预期的权限边界**), 不是故障。
    //    其它错误才算故障。两者的行动指引完全不同, 所以在这里就分开。
    // 🔴 2026-08-22 二修: 第一版判的是 `err.response.status`(axios 形状), 而请求层
    //   抛的是 **ApiError**(`new ApiError('权限不足', 'FORBIDDEN', 403)`), status/code
    //   是**顶层字段**, 没有 response ⇒ 那个判据恒不成立, 403 分支从没进去过,
    //   按钮仍显示「生产状态待确认」而不是「生产状态不可见」。
    // ▎我自己刚写的修复上的「机制在、没接上」—— 部署后靠**看按钮文案**才发现,
    //   ⛔ 光看「红框不弹了」会误判成修好了(那是 _silent 的功劳, 与本分支无关)。
    // ⚠️ 两种形状都认: 不是每条路径都包成 ApiError。
    const e = err as { status?: number; code?: string; response?: { status?: number } };
    const status = e?.status ?? e?.response?.status;
    if (status === 403 || e?.code === 'FORBIDDEN') {
      productionPlansForbidden.value = true;
    } else {
      productionPlansFailed.value = true;
    }
    associatedProductionPlans.value = [];
  } finally {
    productionPlansLoading.value = false;
  }
}

async function loadOrderCustomer() {
  const customerId = String(order.value?.customerId ?? '');
  if (!factoryId.value || !customerId) {
    customer.value = null;
    return;
  }
  try {
    customer.value = await getCustomer(factoryId.value, customerId);
  } catch {
    customer.value = null;
  }
}

async function loadInvoices() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/finance/invoices/by-sales-order/${orderId.value}`);
    if (res.success) invoices.value = Array.isArray(res.data) ? res.data : [];
  } catch { /* ignore — invoice module may not be initialised */ }
}

async function loadPayments() {
  if (!factoryId.value || !orderId.value) return;
  // 🔴 2026-08-19 (测试同学 8-12 报「详情里的收款记录不能与收款管理同步」):
  //    这里原来是 `catch { /* ignore */ }` —— 任何加载失败都被渲染成一个**空标签页**,
  //    与「这单确实没收过款」长得一模一样。最可能的失败是 403: 该端点要求
  //    finance:read_write 或 sales:read_write(**写**权限, 见 PaymentRecordController),
  //    只读账号打开销售订单详情就会拿到 403 → 标签页空 → 用户报「没同步」。
  //
  //    ⚠️ 根因未坐实(prod 数据里 payment_records.sales_order_id 写得好好的, 该查得到),
  //    但这个吞异常让问题在现场**无法诊断** —— 本仓硬约束: 「没喊」有两种,
  //    「决定说没有」和「没能做决定」必须分开, 后者必须喊。
  paymentsLoadError.value = '';
  try {
    const res = await get(`/${factoryId.value}/finance/payments/by-sales-order/${orderId.value}`);
    if (res.success) {
      payments.value = Array.isArray(res.data) ? res.data : [];
    } else {
      payments.value = [];
      paymentsLoadError.value = res.message || '收款记录加载失败';
    }
  } catch (e) {
    payments.value = [];
    paymentsLoadError.value = e instanceof Error && e.message
      ? e.message
      : '收款记录加载失败，请刷新重试；若持续失败请联系管理员确认本账号的财务查看权限。';
  }
}

async function loadPurchaseOrders() {
  if (!canViewLinkedPurchases.value || !factoryId.value || !orderId.value) return;
  try {
    // 关联采购订单 — 通过销售订单号查询
    const res = await get(`/${factoryId.value}/purchase/orders`, {
      params: { salesOrderId: orderId.value, page: 1, size: 50 },
    });
    if (res.success && res.data) {
      const list = Array.isArray(res.data) ? res.data : (res.data.content || res.data.records || []);
      purchaseOrders.value = list.filter((po: TableRow) => po.salesOrderId === orderId.value);
    }
  } catch { /* ignore — 后端可能尚未实现按销售订单查询采购单 */ }
}

async function handleAction(action: string) {
  if (submitting.value) return;
  const map: Record<string, { label: string; url: string }> = {
    confirm: { label: '确认并提交 OA 审批', url: `/${factoryId.value}/sales/orders/${orderId.value}/confirm` },
    cancel: { label: '取消订单', url: `/${factoryId.value}/sales/orders/${orderId.value}/cancel` },
    'submit-for-review': { label: '提交 OA 审批', url: `/${factoryId.value}/sales/orders/${orderId.value}/submit-for-review` },
  };
  const a = map[action];
  if (!a) return;
  try {
    await ElMessageBox.confirm(actionConfirmMessage(action, a.label), '操作确认', {
      confirmButtonText: action === 'cancel' ? '确认取消' : '提交并判定',
      cancelButtonText: '返回',
      type: action === 'cancel' ? 'warning' : 'info',
    });
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(a.url);
    if (res.success) {
      ElMessage.success(actionSuccessMessage(action, a.label, res));
      await Promise.all([loadOrder(), loadApprovalProgress()]);
    }
    else { ElMessage.error(res.message || `${a.label}失败，请重试`); }
  } catch (e) { handleCatchError(e, `${a.label}失败，请检查网络`); }
  finally { submitting.value = false; }
}

// Apr 18 2026 bug #51: 用户报告"开始生产"按钮报错"请求资源不存在"其实按钮前端根本没实现。
// SO OA 审批通过后的下一步是创建生产计划（SO → ProductionPlan 关联），此按钮
// 跳到生产计划页并带 salesOrderId 提示, 用户在那边新建 plan。不直接改 SO 状态(那
// 是 plan 开始生产后的联动), 只承担"导航 + 提示"职责, 避免前端伪装后端未实现的能力。
async function handleStartProduction() {
  if (!order.value || productionAction.value.disabled) return;
  ElMessage.info(`请为订单 ${order.value.orderNumber || orderId.value} 创建生产计划`);
  await router.push({
    path: '/production/plans',
    query: { salesOrderId: String(orderId.value), action: 'create' },
  });
}

function openDeliveryDialog() {
  if (!order.value?.items?.length || deliveryAction.value.disabled) return;
  const remainingByItem = new Map(deliveryCapacity.value.map((line) => [line.itemId, line.remaining]));
  const address = resolveDeliveryAddress(order.value.deliveryAddress, customer.value?.shippingAddress);
  deliveryForm.value = {
    deliveryAddress: address,
    logisticsCompany: '',
    trackingNumber: '',
    deliveryMethod: 'LOGISTICS',
    deliveryMode: 'FULL',
    items: (order.value.items as TableRow[]).map((it) => ({
      salesOrderItemId: it.id,
      productTypeId: it.productTypeId,
      productName: it.productName,
      deliveredQuantity: remainingByItem.get(String(it.id ?? '')) ?? 0,
      maxDeliverableQuantity: remainingByItem.get(String(it.id ?? '')) ?? 0,
      unit: it.unit,
      packagingSpecId: it.packagingSpecId,
      unitPrice: it.unitPrice,
      taxRate: it.taxRate,
      // T4-D5 (issue #553): propagate per-line source warehouse code from the
      // sales order item. Backend SalesServiceImpl.createDeliveryRecord stores
      // it on SalesDeliveryItem; allocation logic doesn't filter yet but the
      // hint is preserved for reporting + future intelligent picking.
      sourceWarehouseCode: it.sourceWarehouseCode || '',
    })),
  };
  deliveryIdempotencyKey.value = `web-delivery-${orderId.value}-${Date.now()}`;
  deliveryDialogVisible.value = true;
  if (!address) {
    ElMessage.warning('客户/订单未维护收货地址，请为本次发货单补充发货地址');
  }
}

// T-RTA (issue #531): F006 客户反馈 — open return-order dialog from sales order detail.
function openReturnDialog() {
  if (!order.value?.items?.length) {
    ElMessage.warning('订单无明细, 无法发起退货');
    return;
  }
  returnForm.value = {
    returnDate: toDateString(new Date()),
    reasonCategory: '',
    reason: '',
    remark: '',
    withGoods: true, // default 实物退货
    items: (order.value.items as TableRow[]).map((it) => {
      const delivered = Number(it.deliveredQuantity) || 0;
      return {
        productTypeId: String(it.productTypeId || ''),
        itemName: String(it.productName || it.productTypeName || '-'),
        unitPrice: Number(it.unitPrice) || 0,
        maxQuantity: delivered, // can't refund more than delivered
        selected: delivered > 0,
        quantity: delivered,
        reason: '',
      };
    }),
  };
  returnDialogVisible.value = true;
}

async function handleCreateReturn() {
  const selectedItems = returnForm.value.items.filter((i) => i.selected && i.quantity > 0);
  if (selectedItems.length === 0) {
    ElMessage.warning('请至少选择一行并填写退货数量');
    return;
  }
  if (!returnForm.value.reasonCategory) {
    ElMessage.warning('请选择退货原因');
    return;
  }
  if (!returnForm.value.reason.trim()) {
    ElMessage.warning('请填写退货原因');
    return;
  }
  // Quantity caps: each row's quantity should not exceed maxQuantity (delivered).
  for (const it of selectedItems) {
    if (it.maxQuantity > 0 && it.quantity > it.maxQuantity) {
      ElMessage.warning(`${it.itemName} 退货数量超过已发货数量 (${it.maxQuantity})`);
      return;
    }
  }
  returnSubmitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/return-orders`, {
      returnType: 'SALES_RETURN',
      counterpartyId: order.value?.customerId || '',
      sourceOrderId: orderId.value,
      returnDate: returnForm.value.returnDate,
      reason: returnForm.value.reason,
      remark: returnForm.value.remark,
      // T-RTA business logic (#571): explicit branch — backend uses this to defer
      // AR/AP冲减 to completion path for 有食物 (waits for physical receipt).
      withGoods: returnForm.value.withGoods,
      items: selectedItems.map((it) => ({
        productTypeId: it.productTypeId,
        itemName: it.itemName,
        quantity: it.quantity,
        unitPrice: it.unitPrice,
        reason: it.reason,
      })),
    });
    if (res.success) {
      ElMessage.success('退货单已创建 (DRAFT 草稿). 在销售退货页继续提交审批.');
      returnDialogVisible.value = false;
    }
  } catch { /* interceptor */ }
  finally { returnSubmitting.value = false; }
}

async function handleCreateDelivery() {
  if (submitting.value) return;
  if (deliveryAddressMissing.value) return ElMessage.warning('请填写发货地址；订单和客户主档均未维护地址时不能静默创建');
  const filteredItems = deliveryForm.value.items.filter(i => i.deliveredQuantity > 0);
  if (filteredItems.length === 0) return ElMessage.warning('请至少填写一个发货数量');
  const overLimit = filteredItems.find((item) => Number(item.deliveredQuantity) > Number(item.maxDeliverableQuantity) + 0.000001);
  if (overLimit) return ElMessage.warning(`${overLimit.productName || '产品'} 发货数量超过剩余可安排数量`);
  if (deliveryForm.value.deliveryMode === 'FULL'
    && filteredItems.length !== deliveryForm.value.items.length) {
    return ElMessage.warning('一次性全量发运必须覆盖每一行的全部剩余数量');
  }
  if (deliveryForm.value.deliveryMode === 'FULL'
    && filteredItems.some((item) => Math.abs(Number(item.deliveredQuantity) - Number(item.maxDeliverableQuantity)) > 0.000001)) {
    return ElMessage.warning('一次性全量发运的每一行数量必须等于全部剩余量');
  }
  if (deliveryForm.value.deliveryMethod === 'LOGISTICS' && !deliveryForm.value.logisticsCompany.trim()) {
    return ElMessage.warning('物流配送必须填写物流公司');
  }
  if (deliveryForm.value.deliveryMethod === 'LOGISTICS' && !deliveryForm.value.trackingNumber.trim()) {
    return ElMessage.warning('物流配送必须填写运单号');
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/sales/deliveries`, {
      idempotencyKey: deliveryIdempotencyKey.value,
      salesOrderId: orderId.value,
      customerId: order.value?.customerId || '',
      deliveryDate: toDateString(new Date()),
      deliveryAddress: deliveryForm.value.deliveryAddress,
      logisticsCompany: deliveryForm.value.logisticsCompany,
      items: filteredItems,
    });
    if (res.success) {
      // 客户 2026-07-31:「点击生成发货单以后……但是发货单在哪里看」——
      // 建完停在「订单详情」tab, 单子是建出来了但人看不到, 得自己去点「发货记录」。
      // 直接切过去并把新建那条高亮, 让"建好了"这件事**看得见**。
      const createdNumber = (res.data as { deliveryNumber?: string } | null)?.deliveryNumber || '';
      // 一次性全量发货仍遵循母/子单状态机：母单只负责计划，自动建立唯一全量子单，
      // 后续由仓库确认并扣库；分批模式保留母单供销售逐批创建子发运单。
      if (deliveryForm.value.deliveryMode === 'FULL') {
        const master = res.data as { id?: string; items?: TableRow[] } | null;
        const created = master?.id ? await createAutoFullChildShipment({
          factoryId: factoryId.value,
          masterId: master.id,
          masterItems: Array.isArray(master.items) ? master.items : [],
          plannedItems: filteredItems,
          deliveryMethod: deliveryForm.value.deliveryMethod,
          logisticsCompany: deliveryForm.value.logisticsCompany,
          trackingNumber: deliveryForm.value.trackingNumber,
          deliveryAddress: deliveryForm.value.deliveryAddress,
        }) : false;
        if (!created) {
          ElMessage.warning('母发货单已创建，但未能自动建立全量子发运单，请在发货记录中重试');
        }
      }
      ElMessage.success(createdNumber ? `发货单 ${createdNumber} 已创建` : '发货单创建成功');
      deliveryDialogVisible.value = false;
      activeTab.value = 'delivery';
      highlightedDeliveryNumber.value = createdNumber;
      loadOrder(); loadDeliveries();
    } else { ElMessage.error(res.message || '创建失败，请重试'); }
  } catch (e) { handleCatchError(e, '创建失败，请检查网络'); }
  finally { submitting.value = false; }
}

/** 刚建出来的那一行加高亮; 其余行不动。 */
function deliveryRowClass({ row }: { row: TableRow }): string {
  const number = String((row as { deliveryNumber?: string }).deliveryNumber || '');
  return number && number === highlightedDeliveryNumber.value ? 'just-created-row' : '';
}

function deliveryQuantitySummary(row: TableRow): string {
  const items = Array.isArray(row.items) ? row.items as TableRow[] : [];
  if (!items.length) return '暂无明细';
  return items.map((item) => `${item.productName || item.productTypeId || '产品'} ${Number(item.deliveredQuantity || 0)}${displayUnit(String(item.unit || ''))}`).join('、');
}

function deliveryAuditSummary(row: TableRow): string {
  const shipped = ['SHIPPED', 'DELIVERED', 'SIGNED'].includes(String(row.status || ''));
  const operator = row.shippedByName || (row.shippedBy ? `用户 #${row.shippedBy}` : '待仓库处理');
  const time = shipped ? (row.updatedAt || row.shippedAt || '时间待记录') : (row.createdAt || '时间待记录');
  return `${shipped ? '仓库确认' : '销售创建'}：${operator} · ${time}`;
}

function openShipmentDialog(parent: TableRow) {
  const children = shipmentRows.value.filter((row) => String(row.parentDeliveryId || '') === String(parent.id || ''));
  const arranged = new Map<string, number>();
  children.filter((row) => !['CANCELLED', 'RETURNED'].includes(String(row.status || ''))).forEach((row) => {
    const items = Array.isArray(row.items) ? row.items as TableRow[] : [];
    items.forEach((item) => {
      const key = String(item.salesOrderItemId || item.productTypeId || '');
      arranged.set(key, (arranged.get(key) || 0) + Number(item.deliveredQuantity || 0));
    });
  });
  shipmentParent.value = parent;
  shipmentForm.value = {
    idempotencyKey: `web-shipment-${parent.id}-${Date.now()}`,
    plannedShipmentDate: toDateString(new Date()),
    deliveryMethod: 'LOGISTICS',
    logisticsCompany: '',
    trackingNumber: '',
    deliveryAddress: String(parent.deliveryAddress || ''),
    remark: '',
    items: (Array.isArray(parent.items) ? parent.items as TableRow[] : []).map((item) => {
      const key = String(item.salesOrderItemId || item.productTypeId || '');
      const remaining = Math.max(0, Number(item.deliveredQuantity || 0) - (arranged.get(key) || 0));
      return { ...item, parentDeliveryItemId: item.id, remaining, quantity: remaining };
    }),
  };
  shipmentDialogVisible.value = true;
}

async function handleCreateShipment() {
  if (submitting.value || !shipmentParent.value) return;
  const validationError = shipmentValidationError(shipmentForm.value);
  if (validationError) return ElMessage.warning(validationError);
  const items = shipmentForm.value.items.filter((item) => Number(item.quantity || 0) > 0);
  if (!shipmentForm.value.deliveryAddress.trim()) return ElMessage.warning('请填写子发运地址');
  if (items.length === 0) return ElMessage.warning('请填写本次发运数量');
  const over = items.find((item) => Number(item.quantity) > Number(item.remaining) + 0.000001);
  if (over) return ElMessage.warning(`${over.productName || '产品'} 超过母单剩余可安排数量`);
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/sales/deliveries/${shipmentParent.value.id}/shipments`, {
      idempotencyKey: shipmentForm.value.idempotencyKey,
      plannedShipmentDate: shipmentForm.value.plannedShipmentDate,
      deliveryMethod: shipmentForm.value.deliveryMethod,
      logisticsCompany: shipmentForm.value.logisticsCompany,
      trackingNumber: shipmentForm.value.trackingNumber,
      deliveryAddress: shipmentForm.value.deliveryAddress,
      remark: shipmentForm.value.remark,
      items: items.map((item) => ({ parentDeliveryItemId: item.parentDeliveryItemId, quantity: item.quantity })),
    });
    if (res.success) {
      ElMessage.success(`子发运单 ${res.data?.deliveryNumber || ''} 创建成功`);
      shipmentDialogVisible.value = false;
      await loadDeliveries();
    }
  } catch (e) { handleCatchError(e, '子发运单创建失败，请检查网络'); }
  finally { submitting.value = false; }
}

// T-SHORT-SHIP「补发剩余」: 找到还有未安排行的母单并复用现有「新建分批发运」对话框
// (与 openShipmentDialog 同一套逻辑, 只是多了一步「先找该打开哪张母单」)。找不到时说明
// 连「安排量」都没覆盖这批缺口 (例如母单从没建过整单量) —— 引导去新建发货单, 不假装
// 打开了一个不存在的目标。
function handleReshipOutstanding() {
  if (!order.value || shortShipment.value.outstanding <= 0) return;
  const target = pendingReshipmentMaster(masterDeliveries.value, deliveries.value);
  if (target) {
    openShipmentDialog(target);
    return;
  }
  ElMessage.info('现有发货单已排满，请先"新建发货单"为剩余数量安排新的发货单');
  openDeliveryDialog();
}

function openShortCloseDialog() {
  if (!order.value || shortShipment.value.outstanding <= 0) return;
  shortCloseForm.value = { reasonCode: '', notes: '' };
  shortCloseAcknowledged.value = false;
  shortCloseDialogVisible.value = true;
}

async function submitShortClose() {
  if (!order.value || shortCloseSubmitting.value) return;
  if (!shortCloseForm.value.reasonCode) return ElMessage.warning('请选择短交结案原因');
  const notes = shortCloseForm.value.notes.trim();
  if (shortCloseForm.value.reasonCode === 'OTHER' && !notes) {
    return ElMessage.warning('选择"其他原因"时必须填写补充说明');
  }
  // owner 拍板的硬约束: 结案是不可逆的显式动作, 勾选确认之前提交按钮本就 disabled,
  // 这里再拦一道防止直接调 submitShortClose() 绕过界面。
  if (!shortCloseAcknowledged.value) return ElMessage.warning('请先勾选确认，再提交短交结案');
  shortCloseSubmitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/sales/orders/${orderId.value}/close-short-shipment`, {
      reasonCode: shortCloseForm.value.reasonCode,
      notes: notes || undefined,
    });
    if (res.success) {
      ElMessage.success('订单已按短交结案');
      shortCloseDialogVisible.value = false;
      await loadOrder();
    }
  } catch (e) { handleCatchError(e, '短交结案失败，请检查网络'); }
  finally { shortCloseSubmitting.value = false; }
}

async function submitDeliveredWithSignature() {
  const target = signatureTarget.value;
  if (!target || !signatureSubmissionReady.value || submitting.value) return;
  submitting.value = true;
  let signatureSaved = false;
  try {
    const signatureRes = await post<TableRow>(
      `/${factoryId.value}/sales/deliveries/${target.id}/signature`,
      {
        photoUrls: signaturePhotoUrls.value,
        signedByName: signatureForm.value.signedByName.trim(),
        remark: signatureForm.value.remark.trim(),
      },
    );
    if (!signatureRes.success) {
      throw new Error(signatureRes.message || '签收凭证保存失败');
    }
    signatureSaved = true;

    const deliveredRes = await post<TableRow>(`/${factoryId.value}/sales/deliveries/${target.id}/delivered`);
    if (!deliveredRes.success) {
      throw new Error(deliveredRes.message || '签收状态更新失败');
    }

    ElMessage.success(`发货单 ${target.deliveryNumber || target.id} 已完成客户签收`);
    signatureDialogVisible.value = false;
    await Promise.all([loadDeliveries(), loadOrder()]);
  } catch (e) {
    if (signatureSaved) {
      ElMessage({
        message: '签收凭证已保存，但签收状态未更新。请保留本弹窗内容并再次点击“保存凭证并确认签收”；系统不会重复扣减库存。',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    } else {
      handleCatchError(e, '签收凭证保存失败，请检查照片、签收人和网络后重试');
    }
  }
  finally { submitting.value = false; }
}

// 批次分配 (P0-13) — /ship endpoint 校验总分配量必须等于发货量, 否则返回
// "未完成批次分配". 此对话框调用 GET /recommend-fifo 预填 FIFO 推荐, 用户可调整.
async function openBatchAllocDialog(deliveryId: string, deliveryNumber: string) {
  batchAllocLoading.value = true;
  try {
    // 物料集合必须先就绪 —— 空集合会让所有行都被当成成品, 物料行又会看到那句错的空态。
    if (!materialIdSet.value.size) {
      await loadProductsForEdit();
    }
    const detailRes = await get<TableRow>(`/${factoryId.value}/sales/deliveries/${deliveryId}`);
    if (!detailRes.success || !detailRes.data) { ElMessage.error('加载发货单明细失败'); return; }
    const rawItems = (detailRes.data.items as TableRow[]) || [];
    if (!rawItems.length) { ElMessage.warning('发货单无明细'); return; }

    const items: AllocItem[] = [];
    for (const it of rawItems) {
      const productTypeId = (it.productTypeId || (it.productType as TableRow)?.id) as string;
      const deliveredQuantity = Number(it.deliveredQuantity || 0);
      const productName = (it.productName as string) || ((it.productType as TableRow)?.name as string) || '未命名产品';
      const deliveryItemId = String(it.id || '');
      // 🔴 C1 (2026-07-05): 该行的计量单位 — 传给 recommend-fifo 让后端把跨单位的成品批次
      // (同产品一批记 kg 一批记 盒, F006 现场确认) 统一换算为该单位再比较/推荐, 不再裸相加.
      const unit = (it.unit as string) || '';
      // R6 #7: 传该行的 sourceWarehouseCode 给 recommend-fifo, 使预填的 FEFO 推荐与
      // 后端 commit/deduct 用同一仓库 (后端 recommendFifo 已 honor 该参数; 不传则 default WH-LOG,
      // 与按行仓库发货的批次会对不上)。空值省略, 走后端默认。
      const sourceWarehouseCode = (it.sourceWarehouseCode as string) || '';

      let allocations: AllocRow[] = [];
      let stockWarehouses: string[] = [];
      // 物料行不查成品批次 —— 查了必然是空, 然后显示一句误导仓管的空态。
      const isMaterial = materialIdSet.value.has(String(productTypeId));
      if (!isMaterial && productTypeId && deliveredQuantity > 0) {
        const existingRes = await get<Array<TableRow>>(
          `/${factoryId.value}/sales-deliveries/items/${deliveryItemId}/batch-allocations`
        );
        if (existingRes.success && Array.isArray(existingRes.data) && existingRes.data.length > 0) {
          allocations = existingRes.data.map(r => ({
            finishedGoodsBatchId: String(r.finishedGoodsBatchId || ''),
            batchNumber: String(r.batchNumber || ''),
            productionDate: String(r.productionDate || ''),
            availableQuantity: Number(r.allocatedQty || 0),
            allocatedQty: Number(r.allocatedQty || 0),
            unit: String(r.unit || unit || ''),
            batchNativeUnit: String(r.unit || ''),
          }));
        }
        const swcParam = sourceWarehouseCode
          ? `&sourceWarehouseCode=${encodeURIComponent(sourceWarehouseCode)}`
          : '';
        const unitParam = unit ? `&unit=${encodeURIComponent(unit)}` : '';
        if (allocations.length === 0) {
          const recRes = await get<Array<TableRow>>(
            `/${factoryId.value}/sales-deliveries/items/${deliveryItemId}/batch-allocations/recommend-fifo?productTypeId=${productTypeId}&requiredQty=${deliveredQuantity}${unitParam}${swcParam}`
          );
          if (recRes.success && Array.isArray(recRes.data)) {
            allocations = recRes.data.map(r => ({
              finishedGoodsBatchId: String(r.batchId),
              batchNumber: String(r.batchNumber || ''),
              productionDate: String(r.productionDate || ''),
              availableQuantity: Number(r.availableQuantity || 0),
              allocatedQty: Number(r.recommendedQuantity || 0),
              unit: String(r.unit || unit || ''),
              batchNativeUnit: String(r.batchNativeUnit || ''),
            }));
          }
        }
        // 🔴 G1: 无推荐批次时查该产品实际有货的仓库, 供诚实空态提示 (成品在 X 仓 vs 真的没货).
        if (allocations.length === 0) {
          try {
            const whRes = await get<string[]>(
              `/${factoryId.value}/sales-deliveries/items/${deliveryItemId}/batch-allocations/stock-warehouses?productTypeId=${productTypeId}`
            );
            if (whRes.success && Array.isArray(whRes.data)) stockWarehouses = whRes.data.map(String);
          } catch { /* 非关键: 查不到就退回通用提示 */ }
        }
      }
      items.push({ deliveryItemId, productName, productTypeId, deliveredQuantity, unit, sourceWarehouseCode, allocations, stockWarehouses, isMaterial });
    }
    batchAllocForm.value = { deliveryId, deliveryNumber, items };
    batchAllocDialogVisible.value = true;
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载失败，请检查网络');
  } finally {
    batchAllocLoading.value = false;
  }
}

function sumAllocated(item: AllocItem): number {
  return item.allocations.reduce((s, a) => s + Number(a.allocatedQty || 0), 0);
}

// 🔴 G1: 诚实空态 (fool-proof Rule 5) — 无可分配批次时, 区分「成品在别的仓」vs「真的没货」,
// 绝不误导用户「请先生产」当成品其实存在于其他仓库。
// 客户 2026-07-31 (Sheet 第 40 行): 这段话里的仓库原本是裸 code (`WH-FG`), 现在统一经
// sourceWarehouseLabel 解析成客户自己配置的仓库名; payload 仍然只存 code。
function emptyStateTitle(item: AllocItem): string {
  return allocationEmptyStateTitle(item.stockWarehouses);
}
function emptyStateDesc(item: AllocItem): string {
  return allocationEmptyStateDesc(
    item.stockWarehouses,
    item.sourceWarehouseCode,
    sourceWarehouseLabel,
  );
}

async function handleBatchAllocate() {
  if (submitting.value) return;
  // Zero-quantity items don't need allocation (skip them from validation + submission).
  const activeItems = batchAllocForm.value.items.filter(it => it.deliveredQuantity > 0);
  if (activeItems.length === 0) {
    return ElMessage.warning('无需分配 (所有发货行数量为 0)');
  }
  // Validation: per-item total must not exceed deliveredQuantity (backend enforces same rule).
  // 🔴 owner 2026-09-02 拍板: 分配合计 <= 发货量 (原「必须等于」把短发——食品厂常态，
  // 例如车装不下、生产损耗——堵在了这一步；仍然拒绝「分配 > 发货量」这一种超分配真错误)。
  for (const item of activeItems) {
    // 物料行不参与成品批次分配 —— 不跳过的话它永远命中「尚无可分配批次」的空态提示 (它的
    // allocations 恒为空数组), 同一张子发运单里只要有一行物料就会把成品行也一起卡死。
    if (item.isMaterial) {
      continue;
    }
    if (item.allocations.length === 0) {
      return ElMessage.warning(`${item.productName}: ${emptyStateDesc(item)}`);
    }
    const total = sumAllocated(item);
    if (total - item.deliveredQuantity > 0.001) {
      return ElMessage.warning(`${item.productName}: 分配合计 ${total} 超过发货量 ${item.deliveredQuantity}，分配量不能大于发货量`);
    }
    // Duplicate batch guard — backend 先清空再写入 would accept dupes silently.
    const uniqueIds = new Set(item.allocations.map(a => a.finishedGoodsBatchId));
    if (uniqueIds.size !== item.allocations.length) {
      return ElMessage.warning(`${item.productName}: 同一批次不能重复分配`);
    }
  }
  submitting.value = true;
  let success = 0, failed = 0;
  const errors: string[] = [];
  // Per-item try/catch — the axios interceptor rejects on success:false, so a
  // single outer try would abort the loop after the first failure and leave the
  // remaining items un-attempted (bug caught in code review).
  let skippedMaterials = 0;
  for (const item of activeItems) {
    // 🔴 2026-08-13 真机 E2E 抓到: 上一版只在【校验】循环里跳过物料行, 这个【提交】循环没跳 ——
    // 于是照样 POST 一个空的 allocations, 后端回「批次分配列表不能为空」, 整次提交失败。
    // 一个循环改了另一个没改 = 半截修复。物料行没有成品批次可分配, 两个循环都必须跳过。
    if (item.isMaterial) {
      skippedMaterials++;
      continue;
    }
    try {
      const allocations = item.allocations
        .filter(a => Number(a.allocatedQty) > 0)
        .map(a => ({ finishedGoodsBatchId: a.finishedGoodsBatchId, allocatedQty: Number(a.allocatedQty) }));
      const res = await post(
        `/${factoryId.value}/sales-deliveries/items/${item.deliveryItemId}/batch-allocations`,
        { allocations },
      );
      if (res.success) success++;
      else { failed++; errors.push(`${item.productName}: ${res.message || '失败'}`); }
    } catch (e: unknown) {
      failed++;
      const msg = (e && typeof e === 'object' && 'message' in e)
        ? String((e as { message: unknown }).message)
        : '网络错误';
      errors.push(`${item.productName}: ${msg}`);
    }
  }
  submitting.value = false;
  if (failed === 0) {
    // 全是物料行时 success=0 —— 说「成功 0 项」等于告诉用户什么都没发生, 而实际是
    // 「本来就不需要分配」。两种情况的下一步动作不同, 提示必须分开。
    ElMessage.success(skippedMaterials > 0 && success === 0
      ? `物料行无需分配批次 (${skippedMaterials} 项)，可直接确认发货`
      : `批次分配成功 (${success} 项)`);
    batchAllocDialogVisible.value = false;
    loadDeliveries();
  } else {
    // Reload regardless — partial successes persisted on the server, user should
    // see current state before deciding whether to retry failed items.
    loadDeliveries();
    ElMessage.error(`${success} 成功 / ${failed} 失败:\n${errors.join('\n')}`);
  }
}

async function handleApproveInvoice(invoiceId: string) {
  if (submitting.value) return;
  try {
    const { value: notes } = await ElMessageBox.prompt('审核备注 (选填)', '审核通过开票申请', {
      confirmButtonText: '通过', cancelButtonText: '取消', inputPlaceholder: '（选填）',
    });
    submitting.value = true;
    const res = await post(`/${factoryId.value}/finance/invoices/${invoiceId}/approve`, { notes: notes || '' });
    if (res.success) { ElMessage.success('已审核通过'); loadInvoices(); }
    else ElMessage.error(res.message || '审核失败');
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}

async function handleRejectInvoice(invoiceId: string) {
  if (submitting.value) return;
  try {
    const { value: notes } = await ElMessageBox.prompt('请填写驳回原因', '驳回开票申请', {
      confirmButtonText: '驳回', cancelButtonText: '取消', inputValidator: (v) => !!v || '驳回原因必填',
    });
    submitting.value = true;
    const res = await post(`/${factoryId.value}/finance/invoices/${invoiceId}/reject`, { notes });
    if (res.success) { ElMessage.success('已驳回'); loadInvoices(); }
    else ElMessage.error(res.message || '驳回失败');
  } catch { /* cancelled */ }
  finally { submitting.value = false; }
}

// V3 P0-3c — 开具发票 (上传 PDF 闭环)
function openIssueDialog(invoiceId: string) {
  issueTargetInvoiceId.value = invoiceId;
  issuePdfFile.value = null;
  issueDialogVisible.value = true;
}

function handleFileChange(file: { raw: File }) {
  issuePdfFile.value = file.raw;
}

async function handleIssueInvoice() {
  if (submitting.value) return;
  if (!issuePdfFile.value) {
    return ElMessage.warning('请先选择发票 PDF 文件 (客户要求: 销售从订单页下载发票必须有附件)');
  }
  if (!issuePdfFile.value.name.toLowerCase().endsWith('.pdf')) {
    return ElMessage.warning('只支持 PDF 文件');
  }
  submitting.value = true;
  try {
    const formData = new FormData();
    formData.append('file', issuePdfFile.value);
    // 用原生 fetch 调 multipart endpoint, 走 vite proxy 不需要全 URL
    const url = `/api/mobile/${factoryId.value}/finance/invoices/${issueTargetInvoiceId.value}/issue`;
    const token = localStorage.getItem('cretas_access_token') || '';
    const resp = await fetch(url, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    });
    const data = await resp.json();
    if (data.success) {
      ElMessage.success('发票已开具, 销售可在订单页下载');
      issueDialogVisible.value = false;
      loadInvoices();
      loadOrder();
    } else {
      ElMessage.error(data.message || '开具失败');
    }
  } catch {
    ElMessage.error('开具失败, 请检查网络');
  } finally {
    submitting.value = false;
  }
}

// ──────────────────────────────────────────────
// 收款登记
// ──────────────────────────────────────────────

function openPaymentDialog() {
  const remaining = computedRemainingAmount();
  paymentForm.value = {
    amount: remaining > 0 ? remaining : 0,
    paymentMethod: 'BANK_TRANSFER',
    paymentDate: toDateString(new Date()),
    paymentReference: '',
    remark: '',
    receiptUrl: '',
  };
  receiptFile.value = null;
  paymentDialogVisible.value = true;
}

function computedRemainingAmount() {
  if (!order.value) return 0;
  const total = Number(order.value.totalAmount || 0);
  const paid = Number(order.value.paidAmount || 0);
  return Math.max(0, total - paid);
}

// fool-proof-design Rule 1 (block-not-clamp fix): 登记收款此前完全无上限 — 只显示 "待收 X"
// 文案, 提交只检查 amount>0, 没有任何上限校验 (客户真实反馈: 输入 300 超过待收 150 也能提交,
// 无红字无禁用). 未发现后端/业务上有"预收款/超额收款"合法场景 (PaymentRecordServiceImpl /
// PaymentRecordController 均无相关上限或分支处理), 故默认硬阻断, 不做"确认超额"放行口子.
const paymentOverLimit = computed(
  () => Number(paymentForm.value.amount || 0) > computedRemainingAmount() + 0.001,
);

// V3 P0-11 补强 — 审批进度时间线 (Verification Round 2 / Agent A 截图 3 硬伤)
// 客户截图底部固定显示 "张权 提交申请 → 刘会林 审批人(已同意)" timeline
// 我们从现有 SalesOrder 字段直接渲染, 不加新字段
const approvalTimeline = computed<Array<{
  type: 'success' | 'warning' | 'danger' | 'primary' | 'info';
  title: string;
  user: string;
  time: string;
  notes?: string;
}>>(() => {
  if (!order.value) return [];
  const o = order.value as TableRow;
  const nodes: Array<{ type: 'success' | 'warning' | 'danger' | 'primary' | 'info'; title: string; user: string; time: string; notes?: string }> = [];

  // 节点 1: 创建
  if (o.createdAt) {
    nodes.push({
      type: 'primary',
      title: '订单创建',
      user: String(o.salesperson || o.createdByName || `用户#${o.createdBy || '?'}`),
      time: String(o.createdAt),
    });
  }

  // 节点 2: 确认
  if (o.confirmedAt) {
    nodes.push({
      type: 'primary',
      title: '订单确认',
      user: String(o.confirmedByName || o.salesperson || '业务员'),
      time: String(o.confirmedAt),
    });
  }

  // 节点 3：提交 OA 审批（从 status 推断，无独立时间字段）
  if (o.status === 'PENDING_FINANCE_REVIEW') {
    nodes.push({
      type: 'warning',
      title: '已提交 OA 审批',
      user: '系统',
      time: String(o.updatedAt || ''),
    });
  }

  // 节点 4：OA 审批通过 / 驳回
  if (o.financeReviewedAt) {
    const isApproved = ['FINANCE_APPROVED', 'PROCESSING', 'PARTIAL_DELIVERED', 'COMPLETED'].includes(String(o.status));
    nodes.push({
      type: isApproved ? 'success' : 'danger',
      title: isApproved ? 'OA 审批通过' : 'OA 审批驳回',
      user: o.financeReviewedByName
        ? String(o.financeReviewedByName)
        : (o.financeReviewedBy ? `审批账号 ${o.financeReviewedBy}` : '系统自动审批'),
      time: String(o.financeReviewedAt),
      notes: o.financeReviewNotes ? String(o.financeReviewNotes) : undefined,
    });
  }

  // 节点 5: 发运与签收分别留痕；优先使用叶子子发运，避免母单汇总时间冒充物流事件。
  if (deliveries.value.length > 0) {
    const leafRows = shipmentRows.value.length > 0 ? shipmentRows.value : deliveries.value;
    const shippedRows = leafRows.filter((row) => ['SHIPPED', 'DELIVERED'].includes(String(row.status)));
    if (shippedRows.length > 0) {
      const latestShipped = shippedRows[shippedRows.length - 1];
      nodes.push({
        type: 'warning',
        title: shippedRows.length === 1 ? '确认发货' : `确认发货（${shippedRows.length}次）`,
        user: String(latestShipped.shippedByName || '仓库'),
        time: String(latestShipped.shippedAt || latestShipped.deliveryDate || latestShipped.createdAt || ''),
        notes: latestShipped.trackingNumber
          ? `${latestShipped.logisticsCompany || '物流'} · 运单号 ${latestShipped.trackingNumber}`
          : '未填写运单号（历史数据）',
      });
    }
    const signedRows = leafRows.filter((row) => String(row.status) === 'DELIVERED');
    if (signedRows.length > 0) {
      const latestSigned = signedRows[signedRows.length - 1];
      nodes.push({
        type: 'success',
        title: signedRows.length === 1 ? '客户签收' : `客户签收（${signedRows.length}次）`,
        user: String(latestSigned.signedByName || '签收确认'),
        time: String(latestSigned.signedAt || latestSigned.updatedAt || latestSigned.deliveryDate || ''),
      });
    }
  }

  // Bug #29 fix (Apr 18 2026): 补齐开票 3 段事件 — 申请 / 审核 / 开具
  if (invoices.value.length > 0) {
    const sortedByRequest = [...invoices.value].sort((a, b) =>
      String(a.requestedAt || a.createdAt || '').localeCompare(String(b.requestedAt || b.createdAt || ''))
    );
    const earliestRequested = sortedByRequest[0].requestedAt || sortedByRequest[0].createdAt;
    if (earliestRequested) {
      nodes.push({
        type: 'primary',
        title: invoices.value.length === 1 ? '开票申请' : `开票申请 (${invoices.value.length} 条)`,
        user: '业务员',
        time: String(earliestRequested),
      });
    }

    const reviewed = invoices.value.filter(inv => inv.reviewedAt);
    if (reviewed.length > 0) {
      const latestReview = reviewed.sort((a, b) =>
        String(b.reviewedAt).localeCompare(String(a.reviewedAt))
      )[0];
      const isApproved = ['APPROVED', 'ISSUED'].includes(String(latestReview.status));
      nodes.push({
        type: isApproved ? 'success' : 'danger',
        title: reviewed.length === invoices.value.length
          ? (isApproved ? '发票审核通过' : '发票审核驳回')
          : `发票审核中 (${reviewed.length}/${invoices.value.length})`,
        user: String(latestReview.reviewedByName || `财务#${latestReview.reviewedBy || '?'}`),
        time: String(latestReview.reviewedAt),
        notes: latestReview.reviewNotes ? String(latestReview.reviewNotes) : undefined,
      });
    }

    const issued = invoices.value.filter(inv => inv.issuedAt);
    if (issued.length > 0) {
      const latestIssued = issued.sort((a, b) =>
        String(b.issuedAt).localeCompare(String(a.issuedAt))
      )[0];
      nodes.push({
        type: 'success',
        title: issued.length === invoices.value.length
          ? '发票已开具'
          : `发票开具中 (${issued.length}/${invoices.value.length})`,
        user: '财务',
        time: String(latestIssued.issuedAt),
      });
    }
  }

  // 节点 6: 收款 (有 payments 记录就显示)
  if (payments.value.length > 0) {
    const latestPayment = payments.value[0];
    nodes.push({
      type: ['VERIFIED'].includes(String(latestPayment.status)) ? 'success' : 'warning',
      title: payments.value.length === 1 ? '已收款' : `已收款 (${payments.value.length} 笔)`,
      user: '财务',
      time: String(latestPayment.paymentDate || latestPayment.createdAt || ''),
    });
  }

  // 节点 7: 取消 (终态)
  if (o.status === 'CANCELLED') {
    nodes.push({
      type: 'danger',
      title: '订单已取消',
      user: '系统',
      time: String(o.updatedAt || ''),
    });
  }

  // 节点 8: 完成 (终态)
  if (o.status === 'COMPLETED') {
    nodes.push({
      type: 'success',
      title: '订单完成',
      user: '系统',
      time: String(o.updatedAt || ''),
    });
  }

  return nodes;
});

function approvalReasonLines(notes?: string): string[] {
  if (!notes) return [];
  return notes.split(/\r?\n|；/).map((line) => line.trim()).filter(Boolean);
}

async function handleReceiptChange(file: { raw: File }) {
  receiptFile.value = file.raw;
  // 立即上传, 拿到 URL 存入 form
  receiptUploading.value = true;
  try {
    const formData = new FormData();
    formData.append('file', file.raw);
    const token = localStorage.getItem('cretas_access_token') || '';
    const resp = await fetch(`/api/mobile/${factoryId.value}/upload/receipt`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    });
    const json = await resp.json();
    if (json.success && json.data?.url) {
      paymentForm.value.receiptUrl = json.data.url;
      ElMessage.success('凭证已上传');
    } else {
      ElMessage.error(json.message || '凭证上传失败');
    }
  } catch {
    ElMessage.error('凭证上传失败');
  } finally {
    receiptUploading.value = false;
  }
}

async function handleCreatePayment() {
  if (submitting.value) return;
  if (!paymentForm.value.amount || paymentForm.value.amount <= 0) {
    return ElMessage.warning('收款金额必须 > 0');
  }
  // fool-proof-design Rule 1 defense-in-depth (UI 已 :disabled 提交按钮) — sticky 防绕开.
  if (paymentOverLimit.value) {
    ElMessage({
      type: 'error',
      duration: 0,
      showClose: true,
      message: `收款金额超过待收余额 ${formatAmount(computedRemainingAmount())}, 请调低`,
    });
    return;
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/payments/record`, {
      salesOrderId: orderId.value,
      amount: paymentForm.value.amount,
      paymentMethod: paymentForm.value.paymentMethod,
      paymentDate: paymentForm.value.paymentDate || null,
      paymentReference: paymentForm.value.paymentReference,
      remark: paymentForm.value.remark,
      receiptUrl: paymentForm.value.receiptUrl || null,
    });
    if (res.success) {
      ElMessage.success('收款记录已创建');
      paymentDialogVisible.value = false;
      loadPayments();
      loadOrder();
    } else { ElMessage.error(res.message || '创建失败'); }
  } catch (e) { handleCatchError(e, '创建失败，请检查网络'); }
  finally { submitting.value = false; }
}

// T-INV (issue: F006 客户反馈 — "一键收款"): one-click full-amount payment record.
// Customer's transcript wording was "一键收" — the 登记收款 dialog already auto-fills
// remaining amount, so a true "一键" UX is dialog-less: confirm modal → POST.
// Skips dialog form entirely; uses 默认 BANK_TRANSFER + today's date + no receipt.
async function handleQuickPayFull() {
  if (submitting.value) return;
  const remaining = computedRemainingAmount();
  if (remaining <= 0) {
    return ElMessage.info('订单已全额收款, 无需再收');
  }
  try {
    // formatAmount already prepends ¥ — do NOT add another (reviewer Important #1).
    // paymentDate: omit → backend defaults to server-local today (avoids UTC-midnight
    // drift where Asia/Shanghai 23:30 click would land on tomorrow's UTC date).
    await ElMessageBox.confirm(
      `应收余额 ${formatAmount(remaining)}, 收款方式默认 银行转账, 系统按今日记录. 一键登记?`,
      '一键收款确认',
      { type: 'warning' }
    );
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/payments/record`, {
      salesOrderId: orderId.value,
      amount: remaining,
      paymentMethod: 'BANK_TRANSFER',
      paymentDate: null, // let backend default to server-today (reviewer M2: avoid UTC drift)
      paymentReference: null, // no reference recorded for quick-pay path (reviewer M4)
      remark: '一键收款 (全额, 默认方式)',
      receiptUrl: null,
    });
    if (res.success) {
      ElMessage.success('一键收款已登记');
      loadPayments();
      loadOrder();
    } else { ElMessage.error(res.message || '创建失败'); }
  } catch (e) { handleCatchError(e, '一键收款失败, 请检查网络'); }
  finally { submitting.value = false; }
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-button :icon="ArrowLeft" @click="router.push('/sales/orders')">返回</el-button>
            <span class="page-title">{{ label('salesOrder') }}详情</span>
            <el-button v-if="order" plain @click="documentTraceVisible = true">单据追溯</el-button>
            <el-tag v-if="order" :type="(statusMap[order.status]?.type) || 'info'" size="large">
              {{ statusMap[order.status]?.text || order.status }}
            </el-tag>
            <el-tag v-if="order" :type="orderPaymentStatusMap[order.paymentStatus]?.type || 'info'" size="small" style="margin-left: 8px">
              收款: {{ orderPaymentStatusMap[order.paymentStatus]?.text || '待收款' }}
            </el-tag>
            <el-tag v-if="order" :type="orderInvoiceStatusMap[order.invoiceStatus]?.type || 'info'" size="small" style="margin-left: 4px">
              开票: {{ orderInvoiceStatusMap[order.invoiceStatus]?.text || '待开票' }}
            </el-tag>
            <el-tag v-if="order" :type="orderTransportStatusMap[order.transportPlanStatus]?.type || 'info'" size="small" style="margin-left: 4px">
              运输: {{ orderTransportStatusMap[transportDisplayStatus]?.text || '待出厂' }}
            </el-tag>
          </div>
          <div class="header-right" v-if="order && canWrite">
            <el-button v-if="order.status === 'DRAFT'" type="success" :loading="submitting" @click="handleAction('confirm')">确认并提交 OA 审批</el-button>
            <el-button v-if="order.status === 'CONFIRMED'" type="warning" :loading="submitting" @click="handleAction('submit-for-review')">提交 OA 审批</el-button>
            <el-button v-if="order.status === 'PENDING_FINANCE_REVIEW'" type="primary" plain @click="goToApprovalProgress">前往 OA 审批中心</el-button>
            <el-tooltip
              v-if="order.status === 'FINANCE_APPROVED'"
              :content="productionAction.tooltip"
              :disabled="!productionAction.tooltip"
              placement="top"
            >
              <span>
                <el-button
                  type="primary"
                  :loading="productionPlansLoading"
                  :disabled="productionAction.disabled"
                  @click="handleStartProduction"
                >{{ productionAction.label }}</el-button>
              </span>
            </el-tooltip>
            <el-button
              v-if="productionAction.relatedId"
              link
              type="primary"
              @click="router.push(`/production/plans?salesOrderId=${orderId}`)"
            >查看生产计划</el-button>
            <!-- 销售只创建发货任务；库存扣减固定由仓库出货管理确认。 -->
            <el-tooltip
              v-if="['CONFIRMED','FINANCE_APPROVED','PROCESSING','PARTIAL_DELIVERED'].includes(order.status)"
              :content="deliveryAction.tooltip"
              :disabled="!deliveryAction.tooltip"
              placement="top"
            >
              <span>
                <el-button
                  type="primary"
                  :loading="deliveriesLoading"
                  :disabled="deliveryAction.disabled"
                  @click="openDeliveryDialog"
                >{{ deliveryAction.label }}</el-button>
              </span>
            </el-tooltip>
            <el-button v-if="isOrderDrivenCustomerSuppliedOrder" type="warning" plain @click="goWarehouseReceiving">前往仓储收货任务</el-button>
            <!-- T-SHORT-SHIP (owner 2026-09-04 拍板): 短交必须同时支持「补发」和「关单/短交
                 结案」两条路, 且都是显式确认点击。两个按钮只在部分发货且实发有缺口时并排出现,
                 不做任何默认/自动的那一条。 -->
            <el-button
              v-if="showShortShipmentActions"
              type="primary"
              plain
              @click="handleReshipOutstanding"
            >补发剩余 {{ shortShipment.outstanding }}{{ displayUnit(shortShipment.unit) }}</el-button>
            <el-button
              v-if="showShortShipmentActions"
              type="danger"
              plain
              @click="openShortCloseDialog"
            >短交结案</el-button>
            <!-- T-RTA (issue #531): F006 客户反馈 第四次会议 956-1037 — 申请退货 入口.
                 Opens dialog that builds CreateReturnOrderRequest with returnType=SALES_RETURN. -->
            <el-button v-if="['PARTIAL_DELIVERED','DELIVERED','COMPLETED'].includes(order.status)"
                       type="warning" :loading="submitting" @click="openReturnDialog">申请退货</el-button>
            <el-button v-if="['DRAFT','CONFIRMED'].includes(order.status)" type="danger" :disabled="submitting" @click="handleAction('cancel')">取消</el-button>
            <!-- Sprint 4 Wave 2 S-PROFIT-DETAIL-1: per-product profit drill-down.
                 Only meaningful for non-DRAFT orders with line items + visible to price-cleared roles. -->
            <!-- 🔴 2026-08-22: 加 canViewProfitReport。这一页调 /reports/sales/profit-detail/,
                 后端要 finance|procurement ⇒ 只看 canViewPrice 会让 sales_manager 点出 403。
                 ▎「看得到金额」不等于「进得去利润报表」—— 两个判据不是一回事。 -->
            <el-button
              v-if="canViewPrice && canViewProfitReport && order.status !== 'DRAFT'"
              type="info"
              plain
              @click="router.push(`/sales/orders/${orderId}/profit`)"
            >产品级利润分析</el-button>
          </div>
          <div class="header-right" v-else-if="order && isOrderDrivenCustomerSuppliedOrder">
            <el-button type="warning" plain @click="goWarehouseReceiving">前往仓储收货任务</el-button>
          </div>
        </div>
      </template>

      <!-- R18-ext #283: explicit empty state for 404 / not-found -->
      <el-empty
        v-if="notFound"
        :description="`订单 ${orderId} 不存在或已被删除`"
      >
        <el-button type="primary" @click="router.push('/sales/orders')">返回订单列表</el-button>
        <el-button @click="router.back()">返回上页</el-button>
      </el-empty>

      <template v-if="order && !notFound">
        <!-- 订单头部摘要 (4 状态联动) -->
        <el-descriptions :column="4" border>
          <el-descriptions-item label="订单编号">{{ order.orderNumber }}</el-descriptions-item>
          <el-descriptions-item :label="label('customer')">{{ order.customerName || order.customer?.name || order.customerId }}</el-descriptions-item>
          <el-descriptions-item label="下单日期">{{ order.orderDate }}</el-descriptions-item>
          <el-descriptions-item label="业务员">{{ order.salesperson || '-' }}</el-descriptions-item>
          <el-descriptions-item label="加工方式">{{ processingModeLabel(order.processingMode) }}</el-descriptions-item>
          <el-descriptions-item label="物料供应方式">
            <el-tag size="small" :type="order.materialSupplyMode === 'CUSTOMER_SUPPLIED' ? 'warning' : 'info'">
              {{ materialSupplyModeLabel(order.materialSupplyMode) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="未税总额">{{ formatAmount(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="已发货金额">{{ order.actualShippedAmount ? formatAmount(order.actualShippedAmount) : '0.00' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="已开票">{{ order.invoicedAmount ? formatAmount(order.invoicedAmount) : '0.00' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="已收款">{{ order.paidAmount ? formatAmount(order.paidAmount) : '0.00' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 4 tab 业务中心 (V3 P0-11 — 金矿截图 49m17s) -->
        <el-tabs v-model="activeTab" class="business-tabs">

          <!-- ─── Tab 1: 订单详情 ─── -->
          <el-tab-pane label="订单详情" name="detail">
            <el-descriptions :column="3" border style="margin-top: 8px">
              <el-descriptions-item label="交货日期">{{ order.requiredDeliveryDate || '-' }}</el-descriptions-item>
              <el-descriptions-item label="下单箱数">{{ order.boxQuantity || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="canViewPrice" label="折扣">{{ order.discountAmount ? formatAmount(order.discountAmount) : '-' }}</el-descriptions-item>
              <el-descriptions-item label="含运费">{{ order.shippingIncluded ? '是' : '否' }}</el-descriptions-item>
              <el-descriptions-item v-if="canViewPrice" label="运费">{{ order.shippingFee ? formatAmount(order.shippingFee) : '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="canViewPrice" label="预估利润">{{ order.estimatedProfit ? formatAmount(order.estimatedProfit) : '-' }}</el-descriptions-item>
              <el-descriptions-item label="交货地址" :span="3">{{ order.deliveryAddress || '-' }}</el-descriptions-item>
              <el-descriptions-item label="备注" :span="3">{{ order.remark || '-' }}</el-descriptions-item>
            </el-descriptions>

            <section v-if="isPrestockedCustomerStock" class="customer-supplied-progress" aria-label="客户已有成品库存预留">
              <div class="section-heading">
                <div>
                  <h3>客户已有成品库存</h3>
                  <p>以下批次已精确预留给本销售订单；销售创建发货单后，由仓管核对实发数量并确认出库。</p>
                </div>
                <el-button
                  v-if="canAccessWarehouse"
                  type="warning"
                  plain
                  @click="router.push('/warehouse/shipments')"
                >前往仓库确认出库</el-button>
              </div>
              <el-alert
                v-if="reservationsFailed"
                type="error"
                :closable="false"
                show-icon
                title="预留批次加载失败，请刷新后再创建发货单。"
              />
              <el-table
                v-else
                v-loading="reservationsLoading"
                :data="activeReservations"
                border
                empty-text="当前没有生效中的客户成品预留"
              >
                <el-table-column prop="productName" label="成品" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.productName || row.productTypeId }}</template>
                </el-table-column>
                <el-table-column prop="batchNumber" label="精确批次" min-width="220" show-overflow-tooltip />
                <el-table-column label="已预留数量" width="170" align="right">
                  <template #default="{ row }">{{ row.reservedQuantity }} {{ displayUnit(row.unit) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="100" align="center">
                  <template #default><el-tag type="success">已预留</el-tag></template>
                </el-table-column>
              </el-table>
            </section>

            <section v-if="isOrderDrivenCustomerSuppliedOrder" class="customer-supplied-progress" aria-label="客户自带物料收货进度">
              <div class="section-heading">
                <div>
                  <h3>客户自带物料需求</h3>
                  <p>销售侧仅查看预计来料和收货进度；实收、批次及库存只能由仓储任务处理。</p>
                </div>
                <el-button type="warning" plain @click="goWarehouseReceiving">前往仓储收货任务</el-button>
              </div>
              <el-alert
                v-if="customerSuppliedRequirements.length === 0"
                type="warning"
                :closable="false"
                show-icon
                title="该代加工订单尚未返回结构化客供料需求，请在订单草稿中补齐后再提交。"
              />
              <el-table v-else :data="customerSuppliedRequirements" border>
                <el-table-column label="物料分类 / 名称" min-width="250" show-overflow-tooltip>
                  <template #default="{ row }"><el-tag size="small" effect="plain">{{ customerMaterialCategory(row) }}</el-tag> {{ row.materialName || row.materialTypeId }}</template>
                </el-table-column>
                <el-table-column label="预计到货" width="125">
                  <template #default="{ row }">{{ formatBusinessDateTime(row.expectedArrivalAt) }}</template>
                </el-table-column>
                <el-table-column label="目标仓库" min-width="160" show-overflow-tooltip>
                  <template #default="{ row }">{{ targetWarehouseLabel(row) }}</template>
                </el-table-column>
                <el-table-column label="收货进度" min-width="260">
                  <template #default="{ row }">{{ customerMaterialProgress(row) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="110">
                  <template #default="{ row }"><el-tag type="warning">{{ customerMaterialReceivingStatusLabel(row.status) }}</el-tag></template>
                </el-table-column>
              </el-table>
            </section>

            <h3 style="margin: 20px 0 12px">{{ label('product') }}明细</h3>
            <!-- Task 4: DRAFT 草稿可编辑产品行 -->
            <div v-if="isDraft && canWrite" style="margin-bottom: 8px;">
              <el-button type="primary" size="small" @click="openEditItems">编辑产品行</el-button>
            </div>
            <el-alert
              v-else-if="!isDraft && !(order.items && order.items.length)"
              type="info"
              :closable="false"
              title="该订单非草稿状态且无产品行，请联系销售在草稿阶段补充。"
            />
            <!-- T3-13 fix (issue #524): widen 规格 + 产品 columns + scroll-x fallback to prevent 成品详情规格列盖住. -->
            <el-table :data="order.items || []" border stripe style="width: 100%" :scrollbar-always-on="true">
              <el-table-column prop="productName" :label="label('product')" min-width="180" show-overflow-tooltip />
              <el-table-column prop="specification" label="规格" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.specification || '-' }}</template>
              </el-table-column>
              <el-table-column prop="quantity" label="订单数量" width="110" align="right" />
              <el-table-column label="单位" width="80" align="center">
                <template #default="{ row }">{{ displayUnit(row.unit) }}</template>
              </el-table-column>
              <el-table-column prop="boxQuantity" label="箱数" width="80" align="right">
                <template #default="{ row }">{{ row.boxQuantity || '-' }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="unitPrice" label="未税单价" width="120" align="right">
                <template #default="{ row }">{{ formatUnitPrice(row.unitPrice) }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" label="含税单价" width="120" align="right">
                <template #default="{ row }">
                  {{ formatTaxIncludedUnitPrice(row) }}
                </template>
              </el-table-column>
              <el-table-column prop="taxRate" label="税率" width="80" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.taxRate != null" size="small"
                          :type="Number(row.taxRate) === 9 ? 'success' : (Number(row.taxRate) === 13 ? 'warning' : 'info')">
                    {{ row.taxRate }}%
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="已发货" width="100" align="right">
                <template #default="{ row }">{{ row.deliveredQuantity || 0 }}</template>
              </el-table-column>
              <!-- T4-D1 (issue #525): 来源仓库 per line item.
                   Backend: sales_order_items.source_warehouse_code (V20260514_01 migration).
                   2026-07-02 fix: shows the warehouse's real DB name (resolved from
                   the loaded warehouse list), not a hardcoded WH-LOG→总仓 label —
                   see utils/warehouse.ts for the LIUSHANMEN "同仓库多名字" incident. -->
              <el-table-column label="来源仓库" width="120">
                <template #default="{ row }">{{ sourceWarehouseLabel(row.sourceWarehouseCode) }}</template>
              </el-table-column>
              <!-- E-4 (六扇门需求矩阵 / PR #737/#749): 同时显示未税净价 + 含税价双值.
                   后端 SalesOrderItem.getLineAmount()       = 未税小计 (折后未税)
                   后端 SalesOrderItem.getLineAmountWithTax() = 含税小计 = 未税 × (1 + taxRate/100)
                   两者均为 @PriceSensitive — 无价格权限时后端返回 null → 显示 "—". -->
              <el-table-column v-if="canViewPrice" label="未税小计" width="130" align="right">
                <template #default="{ row }">
                  <span :title="'未含税金额 (折后)'">
                    {{ row.lineAmount != null ? formatAmount(row.lineAmount) : (row.unitPrice != null ? formatAmount(row.quantity * row.unitPrice) : '—') }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" label="含税小计" width="140" align="right">
                <template #default="{ row }">
                  <template v-if="row.unitPrice != null">
                    <span style="font-weight: 600; color: #303133">{{ formatAmount(displayLineMoney(row).taxIncluded) }}</span>
                    <div v-if="row.taxRate && Number(row.taxRate) > 0" style="font-size: 11px; color: #909399; margin-top: 2px">
                      税额 {{ formatAmount(displayLineMoney(row).tax) }}
                    </div>
                  </template>
                  <span v-else>—</span>
                </template>
              </el-table-column>
            </el-table>

            <!-- E-4 含税/未税汇总条 (仅在有税率数据时展示) -->
            <div v-if="canViewPrice && order.items && order.items.length > 0" class="tax-summary-bar">
              <template v-if="order.items.some((item: TableRow) => item.taxRate != null && Number(item.taxRate) > 0)">
                <span class="tax-summary-item">
                  <span class="tax-summary-label">未税总额</span>
                  <span class="tax-summary-value">
                    {{ formatAmount(displayOrderMoney.untaxed) }}
                  </span>
                </span>
                <span class="tax-summary-sep">+</span>
                <span class="tax-summary-item">
                  <span class="tax-summary-label">税额合计</span>
                  <span class="tax-summary-value tax-value">
                    {{ formatAmount(displayOrderMoney.tax) }}
                  </span>
                </span>
                <span class="tax-summary-sep">=</span>
                <span class="tax-summary-item">
                  <span class="tax-summary-label">含税总额</span>
                  <span class="tax-summary-value tax-total">
                    {{ formatAmount(displayOrderMoney.taxIncluded) }}
                  </span>
                </span>
              </template>
            </div>

            <!-- ─── R14: 税率分组汇总 (Canvas FormulaEngine 驱动) ─── -->
            <div v-if="canViewPrice && taxGroupData" class="tax-group-section">
              <h3 style="margin: 20px 0 12px">税率分组汇总</h3>
              <el-table :data="taxGroupData" border stripe size="small" style="max-width: 400px">
                <el-table-column label="税率" width="120" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.taxRate === 9 ? 'success' : (row.taxRate === 13 ? 'warning' : 'info')" size="small">
                      {{ row.taxRate }}%
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="金额小计" width="160" align="right">
                  <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
                </el-table-column>
              </el-table>
            </div>

            <!-- ─── 审批进度时间线 (V3 P0-11 补强 — 客户金矿截图 49m17s 底部 timeline) ─── -->
            <div class="approval-timeline-section">
              <h3>审批进度</h3>
              <el-descriptions
                v-if="approvalProgress?.hasInstance"
                :column="2"
                border
                class="oa-progress-summary"
              >
                <el-descriptions-item label="OA 状态">
                  {{ approvalProgressStatusLabel(approvalProgress.status) }}
                </el-descriptions-item>
                <el-descriptions-item label="当前节点">
                  {{ approvalProgress.currentNodeNames.join('、') || '流程已结束' }}
                </el-descriptions-item>
                <el-descriptions-item label="待处理角色">
                  {{ approvalProgress.approverRoles.map(role => enumLabel(role)).join('、') || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="待处理人员">
                  {{ approvalProgress.assignees.join('、') || '-' }}
                </el-descriptions-item>
                <el-descriptions-item label="提交时间">
                  {{ formatBusinessDateTime(approvalProgress.initiatedAt) }}
                </el-descriptions-item>
                <el-descriptions-item label="停留时长">
                  {{ approvalProgress.stayMinutes == null ? '-' : `${approvalProgress.stayMinutes} 分钟` }}
                </el-descriptions-item>
                <el-descriptions-item :span="2" label="操作">
                  <el-button link type="primary" @click="goToApprovalProgress">前往个人 OA 查看</el-button>
                </el-descriptions-item>
              </el-descriptions>
              <el-alert
                v-else
                type="info"
                :closable="false"
                show-icon
                :title="approvalProgress?.message || '该订单尚未发起 OA 审批'"
                class="oa-progress-summary"
              />
              <el-empty v-if="approvalTimeline.length === 0" description="暂无审批记录" :image-size="60" />
              <el-timeline v-else>
                <el-timeline-item
                  v-for="(node, idx) in approvalTimeline"
                  :key="idx"
                  :type="node.type"
                  :timestamp="formatBusinessDateTime(node.time)"
                  placement="top"
                  size="large"
                >
                  <el-card shadow="hover" class="timeline-card">
                    <div class="timeline-title">{{ node.title }}</div>
                    <div class="timeline-meta">
                      <el-icon><i class="el-icon-user" /></el-icon>
                      <span>{{ node.user }}</span>
                    </div>
                    <div v-if="node.notes" class="timeline-notes">
                      <div v-for="line in approvalReasonLines(node.notes)" :key="line">{{ line }}</div>
                    </div>
                  </el-card>
                </el-timeline-item>
              </el-timeline>
            </div>
          </el-tab-pane>

          <!-- ─── Tab 2: 自动生成的开票记录 ─── -->
          <el-tab-pane name="invoice">
            <template #label>
              开票记录
              <el-badge v-if="invoices.length" :value="invoices.length" :max="99" class="tab-badge" />
            </template>

            <div class="tab-toolbar">
              <span v-if="canViewPrice" class="tab-hint">订单总额 {{ formatAmount(order.totalAmount) }} / 已开票 {{ formatAmount(order.invoicedAmount || 0) }}</span>
              <span class="tab-hint">全部发货完成后，系统自动按税率分组生成待审核开票记录</span>
            </div>

            <el-table :data="invoices" border stripe style="margin-top: 12px">
              <el-table-column prop="invoiceNumber" label="发票编号" width="180" />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="invoiceStatusMap[row.status]?.type || 'info'" size="small">
                    {{ invoiceStatusMap[row.status]?.text || row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="amount" label="不含税" width="120" align="right">
                <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="taxAmount" label="税额" width="120" align="right">
                <template #default="{ row }">{{ formatAmount(row.taxAmount) }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="totalAmount" label="价税合计" width="130" align="right">
                <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" label="税率分组" min-width="240">
                <template #default="{ row }">
                  <div v-if="row.taxBreakdown && row.taxBreakdown.length" class="tax-breakdown">
                    <el-tag
                      v-for="(group, idx) in row.taxBreakdown"
                      :key="idx"
                      size="small"
                      :type="Number(group.taxRate) === 9 ? 'success' : (Number(group.taxRate) === 13 ? 'warning' : 'info')"
                      style="margin-right: 6px"
                    >
                      {{ group.taxRate }}% × {{ group.lineCount }} 行 = {{ formatAmount(group.taxableAmount) }} (税 {{ formatAmount(group.taxAmount) }})
                    </el-tag>
                  </div>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column prop="requestedAt" label="申请时间" width="160" />
              <el-table-column label="操作" width="200" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="row.status === 'REQUESTED' && canWrite" type="success" link size="small" @click="handleApproveInvoice(row.id)">通过</el-button>
                  <el-button v-if="row.status === 'REQUESTED' && canWrite" type="danger" link size="small" @click="handleRejectInvoice(row.id)">驳回</el-button>
                  <el-button v-if="row.status === 'APPROVED' && canWrite" type="primary" link size="small" @click="openIssueDialog(row.id)">上传发票</el-button>
                  <el-link v-if="row.invoicePdfUrl" :href="row.invoicePdfUrl" target="_blank" type="primary" underline="hover" :download="row.invoiceFileName || ''">
                    下载{{ row.invoiceFileName ? ` (${row.invoiceFileName})` : '' }}
                  </el-link>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- ─── Tab 3: 销售出库 ─── -->
          <el-tab-pane name="delivery">
            <template #label>
              {{ label('delivery') }}记录
              <el-badge v-if="actionableDeliveries" :value="actionableDeliveries" :max="99" class="tab-badge" />
              <span v-else-if="deliveries.length" class="neutral-record-count">（{{ deliveries.length }}）</span>
            </template>

            <div class="tab-toolbar">
              <el-tooltip :content="deliveryAction.tooltip" :disabled="!deliveryAction.tooltip">
                <span><el-button
                  v-if="['CONFIRMED','FINANCE_APPROVED','PROCESSING','PARTIAL_DELIVERED'].includes(order.status) && canWrite"
                  type="primary" :disabled="deliveryAction.disabled" @click="openDeliveryDialog"
                >+ {{ deliveryAction.label }}</el-button></span>
              </el-tooltip>
            </div>

            <el-table
              :data="deliveries"
              border
              stripe
              style="margin-top: 12px"
              :row-class-name="deliveryRowClass"
            >
              <el-table-column label="层级" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.recordRole === 'MASTER' ? 'primary' : 'info'" size="small">
                    {{ row.recordRole === 'MASTER' ? '母发货单' : (row.recordRole === 'SHIPMENT' ? '子发运' : '历史单') }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="deliveryNumber" label="发货/发运单号" min-width="190" class-name="semantic-text-column" />
              <el-table-column prop="deliveryDate" label="发货日期" width="120" />
              <el-table-column label="配送方式" width="110"><template #default="{ row }">{{ ({ LOGISTICS: '物流配送', SELF_DELIVERY: '自送', SELF_PICKUP: '自提' } as Record<string, string>)[row.deliveryMethod] || (row.deliveryMethod ? row.deliveryMethod : '未记录（历史数据）') }}</template></el-table-column>
              <el-table-column label="物流公司" width="140"><template #default="{ row }">{{ row.logisticsCompany || '未填写（历史数据）' }}</template></el-table-column>
              <el-table-column label="运单号" width="170"><template #default="{ row }">{{ row.trackingNumber || '未填写（历史数据）' }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="(delStatusMap[row.status]?.type) || 'info'" size="small">
                    {{ delStatusMap[row.status]?.text || row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="totalAmount" label="金额" width="130" align="right">
                <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
              </el-table-column>
              <el-table-column label="批次分配" width="160" align="center">
                <template #default="{ row }">
                  <span v-if="row.recordRole === 'MASTER'">由子发运单分配</span>
                  <!--
                    整单物料: 后端在「确认发货」时按 FIFO 自动扣减, 没有批次可分。
                    原来这里显示「已分配 0/0.7 kg」, 仓管会以为还欠一步。
                  -->
                  <el-tag v-else-if="allocationSummary[String(row.id)]?.materialOnly" type="success" size="small" effect="plain">
                    物料 · 自动扣减
                  </el-tag>
                  <span v-else-if="allocationSummary[String(row.id)]">
                    已分配 {{ allocationSummary[String(row.id)].allocated }}/{{ allocationSummary[String(row.id)].planned }}
                    {{ allocationSummary[String(row.id)].unit }}
                    <div v-for="batch in allocationSummary[String(row.id)].batches" :key="batch" class="muted-audit">{{ batch }}</div>
                    <span v-if="allocationSummary[String(row.id)].materialLines" style="color: var(--el-text-color-secondary)">
                      (另 {{ allocationSummary[String(row.id)].materialLines }} 行物料自动扣减)
                    </span>
                  </span>
                  <span v-else>0/0</span>
                </template>
              </el-table-column>
              <el-table-column label="数量与操作审计" min-width="250">
                <template #default="{ row }">
                  <div>{{ deliveryQuantitySummary(row) }}</div>
                  <div class="muted-audit">{{ deliveryAuditSummary(row) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="330" align="center">
                <template #default="{ row }">
                  <el-button
                    v-if="row.recordRole === 'MASTER' && !['SHIPPED','DELIVERED','CANCELLED'].includes(row.status) && canWrite"
                    type="primary" link size="small" @click="openShipmentDialog(row)"
                  >新建分批发运</el-button>
                  <!-- 普通订单可由销售预先分配批次；PRESTOCKED 在仓库确认时按订单预留自动匹配。 -->
                  <el-button v-if="!isPrestockedCustomerStock && row.recordRole !== 'MASTER' && ['DRAFT','PENDING_WAREHOUSE_CONFIRM','PICKED'].includes(row.status) && canWrite" type="primary" link size="small" :disabled="submitting || batchAllocLoading" :loading="batchAllocLoading" @click="openBatchAllocDialog(row.id, row.deliveryNumber)">
                    {{ allocationSummary[String(row.id)]?.complete ? '查看/修改批次' : (allocationSummary[String(row.id)]?.allocated ? '继续分配/修改批次' : '分配批次') }}
                  </el-button>
                  <el-tooltip
                    v-if="row.recordRole !== 'MASTER' && ['DRAFT','PENDING_WAREHOUSE_CONFIRM','PICKED'].includes(row.status)"
                    content="库存扣减只能由仓管在 仓储管理 → 出货管理 核对后执行"
                    placement="top"
                  >
                    <el-button
                      type="warning"
                      link
                      size="small"
                      :disabled="!canAccessWarehouse"
                      @click="router.push('/warehouse/shipments')"
                    >等待仓库确认</el-button>
                  </el-tooltip>
                  <el-button v-if="row.status === 'SHIPPED' && canWrite" type="success" link size="small" :disabled="submitting" @click="openSignatureDialog(row)">填写凭证并签收</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- ─── Tab 4: 收款记录 ─── -->
          <el-tab-pane name="payment">
            <template #label>
              收款记录
              <el-badge v-if="payments.length" :value="payments.length" :max="99" class="tab-badge" />
            </template>

            <div class="tab-toolbar">
              <el-button v-if="canWrite && canWriteFinance" type="primary" @click="openPaymentDialog">
                + 登记收款
              </el-button>
              <!-- T-INV (F006 客户反馈): 一键收款 shortcut — only shown when remaining > 0.
                   Auto-fills remaining amount + BANK_TRANSFER + today; bypasses dialog form. -->
              <el-button v-if="canWrite && canWriteFinance && computedRemainingAmount() > 0" type="success"
                         :loading="submitting" @click="handleQuickPayFull">一键收款 (全额)</el-button>
              <span v-if="canViewPrice" class="tab-hint">订单总额 {{ formatAmount(order.totalAmount) }} / 已收 {{ formatAmount(order.paidAmount || 0) }} / 待收 {{ formatAmount(computedRemainingAmount()) }}</span>
            </div>

            <!-- 🔴「查不到」不能长成「没有」: 加载失败时明确说出来 + 给下一步动作, 不再静默空表。 -->
            <el-alert
              v-if="paymentsLoadError"
              type="error"
              :closable="false"
              show-icon
              style="margin-top: 12px"
              title="收款记录未能加载 —— 下方为空不代表这单没有收款"
              :description="`${paymentsLoadError}（若本账号只有只读权限，请联系管理员开通财务收款查看权限后重试）`"
            />
            <el-table
              :data="payments"
              border
              stripe
              style="margin-top: 12px"
              :empty-text="paymentsLoadError ? '未能加载收款记录（见上方提示）' : '暂无收款记录'"
            >
              <el-table-column prop="paymentNumber" label="收款单号" width="180" />
              <el-table-column prop="paymentDate" label="收款日期" width="120" />
              <el-table-column v-if="canViewPrice" prop="amount" label="收款金额" width="130" align="right">
                <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
              </el-table-column>
              <el-table-column prop="paymentMethod" label="收款方式" width="120" align="center">
                <template #default="{ row }">
                  {{ ({ BANK_TRANSFER: '银行转账', CASH: '现金', CHECK: '支票', WECHAT: '微信', ALIPAY: '支付宝', OTHER: '其他' } as Record<string, string>)[row.paymentMethod] || row.paymentMethod }}
                </template>
              </el-table-column>
              <el-table-column prop="paymentReference" label="参考号/凭证" width="180" />
              <el-table-column label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="paymentStatusMap[row.status]?.type || 'info'" size="small">
                    {{ paymentStatusMap[row.status]?.text || row.status }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>

          <!-- ─── Tab 5: 关联采购订单 ─── -->
          <el-tab-pane v-if="canViewLinkedPurchases" name="purchase">
            <template #label>
              关联采购
              <el-badge v-if="purchaseOrders.length" :value="purchaseOrders.length" :max="99" class="tab-badge" />
            </template>

            <div class="tab-hint" style="padding: 12px 0">
              客户原话: 主原料 (贵重料) 必须按销售订单做定点追踪, 防止多采浪费
            </div>

            <el-table :data="purchaseOrders" border stripe>
              <el-table-column prop="orderNumber" label="采购单号" width="180" />
              <el-table-column prop="supplierName" label="供应商" min-width="150" />
              <el-table-column prop="orderDate" label="下单日期" width="120" />
              <el-table-column prop="status" label="状态" width="100" align="center">
                <template #default="{ row }">
                  <el-tag size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="canViewPrice" prop="totalAmount" label="金额" width="130" align="right">
                <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="router.push(`/procurement/orders/${row.id}`)">查看</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty description="暂无关联采购订单" :image-size="80" />
              </template>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-card>

    <el-dialog v-model="deliveryDialogVisible" :title="`创建${label('delivery')}单`" width="980px" destroy-on-close>
      <el-alert
        v-if="deliveryAddressMissing"
        type="warning"
        show-icon
        :closable="false"
        title="客户/订单未维护收货地址"
        description="请为本次发货单手工补充发货地址，空地址禁止创建。"
        style="margin-bottom: 12px"
      />
      <el-form label-width="90px">
        <el-form-item label="发货地址" required>
          <el-input v-model="deliveryForm.deliveryAddress" placeholder="优先使用订单交货地址；订单为空时回退客户主档收货地址" />
        </el-form-item>
        <el-form-item label="配送方式" required>
          <el-radio-group v-model="deliveryForm.deliveryMethod">
            <el-radio value="LOGISTICS">物流配送</el-radio>
            <el-radio value="SELF_DELIVERY">自送</el-radio>
            <el-radio value="SELF_PICKUP">客户自提</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="deliveryForm.deliveryMethod === 'LOGISTICS'" label="物流公司" required><el-input v-model="deliveryForm.logisticsCompany" placeholder="如：顺丰冷链" /></el-form-item>
        <el-form-item v-if="deliveryForm.deliveryMethod === 'LOGISTICS'" label="运单号" required><el-input v-model="deliveryForm.trackingNumber" placeholder="填写物流运单号" /></el-form-item>
        <el-form-item label="发货模式" required>
          <el-radio-group v-model="deliveryForm.deliveryMode">
            <el-radio value="FULL">一次性全量发货（自动建一张全量子单）</el-radio>
            <el-radio value="PARTIAL">分批发货（后续逐批建子单）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-table :data="deliveryForm.items" border style="margin-top: 12px">
        <el-table-column prop="productName" :label="label('product')" min-width="190" class-name="semantic-text-column" />
        <el-table-column label="本次发货数量" width="170">
          <template #default="{ row }">
            <el-input-number v-model="row.deliveredQuantity" :min="0" :max="row.maxDeliverableQuantity" :precision="3" size="small" style="width: 130px" />
            <div class="delivery-capacity-hint">剩余可安排 {{ row.maxDeliverableQuantity }}{{ displayUnit(row.unit) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="单位" width="80" align="center">
          <template #default="{ row }">{{ displayUnit(row.unit) }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" prop="unitPrice" label="未税单价" width="120" align="right">
          <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="本次发货小计（未税）" width="165" align="right">
          <template #default="{ row }">{{ formatAmount(Number(row.deliveredQuantity || 0) * Number(row.unitPrice || 0)) }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="税额" width="110" align="right">
          <template #default="{ row }">{{ formatAmount(Number(row.deliveredQuantity || 0) * Number(row.unitPrice || 0) * Number(row.taxRate || 0) / 100) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="canViewPrice" class="delivery-amount-summary">
        <span>本次未税合计 <strong>{{ formatAmount(deliveryTotals.untaxed) }}</strong></span>
        <span>税额 <strong>{{ formatAmount(deliveryTotals.tax) }}</strong></span>
        <span>本次含税合计 <strong>{{ formatAmount(deliveryTotals.taxIncluded) }}</strong></span>
      </div>
      <template #footer>
        <el-button @click="deliveryDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="deliveryAddressMissing" @click="handleCreateDelivery">创建发货单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shipmentDialogVisible" title="新建分批发运" width="760px" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon title="本步骤只创建子发运安排，不扣库存；子单分配批次并由仓库确认后才实际发货。" style="margin-bottom: 12px" />
      <el-form label-width="110px">
        <el-form-item label="计划发运日期" required>
          <el-date-picker v-model="shipmentForm.plannedShipmentDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="配送方式" required>
          <el-radio-group v-model="shipmentForm.deliveryMethod">
            <el-radio value="LOGISTICS">物流配送</el-radio>
            <el-radio value="SELF_DELIVERY">自送</el-radio>
            <el-radio value="SELF_PICKUP">客户自提</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="物流公司" :required="shipmentForm.deliveryMethod === 'LOGISTICS'"><el-input v-model="shipmentForm.logisticsCompany" :disabled="shipmentForm.deliveryMethod !== 'LOGISTICS'" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="物流/运单号" :required="shipmentForm.deliveryMethod === 'LOGISTICS'"><el-input v-model="shipmentForm.trackingNumber" :disabled="shipmentForm.deliveryMethod !== 'LOGISTICS'" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="发货地址" required><el-input v-model="shipmentForm.deliveryAddress" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="shipmentForm.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <el-table :data="shipmentForm.items" border>
        <el-table-column prop="productName" label="成品" min-width="220" class-name="semantic-text-column" />
        <el-table-column label="本次发运数量" width="180">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0" :max="row.remaining" :precision="3" style="width: 130px" />
          </template>
        </el-table-column>
        <el-table-column label="单位" width="80"><template #default="{ row }">{{ displayUnit(row.unit) }}</template></el-table-column>
        <el-table-column label="母单剩余" width="120"><template #default="{ row }">{{ row.remaining }}{{ displayUnit(row.unit) }}</template></el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="shipmentDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateShipment">创建子发运单</el-button>
      </template>
    </el-dialog>

    <!-- ─── T-SHORT-SHIP (owner 2026-09-04 拍板): 短交结案对话框 —— 镜像采购侧
         PendingPurchaseReceivingPanel.vue 的「少收关闭」, 多一道勾选确认。 ─── -->
    <el-dialog
      v-model="shortCloseDialogVisible"
      :title="`短交结案 — ${order?.orderNumber || ''}`"
      width="min(680px, calc(100vw - 32px))"
      :close-on-click-modal="false"
    >
      <template v-if="order">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="结案后，剩余数量不再生成发货安排；已发货部分的库存/开票不受影响。"
          description="本操作用于客户确认接受少发、或原料确实无法补齐的短交场景。若仍会补发，请取消并使用「补发剩余」。"
          style="margin-bottom: 12px"
        />
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单编号">{{ order.orderNumber }}</el-descriptions-item>
          <el-descriptions-item label="下单数量">{{ shortShipment.ordered }}{{ displayUnit(shortShipment.unit) }}</el-descriptions-item>
          <el-descriptions-item label="已实发数量">{{ shortShipment.shipped }}{{ displayUnit(shortShipment.unit) }}</el-descriptions-item>
          <el-descriptions-item label="结案后不再发货的数量">
            <strong style="color: var(--el-color-danger)">{{ shortShipment.outstanding }}{{ displayUnit(shortShipment.unit) }}</strong>
          </el-descriptions-item>
        </el-descriptions>
        <el-form label-width="110px" style="margin-top: 12px">
          <el-form-item label="结案原因" required>
            <el-select v-model="shortCloseForm.reasonCode" placeholder="请选择标准原因" style="width: 100%">
              <el-option
                v-for="option in SHORT_CLOSE_REASON_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="补充说明" :required="shortCloseForm.reasonCode === 'OTHER'">
            <el-input
              v-model="shortCloseForm.notes"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="可填写客户确认记录、原料短缺依据等"
            />
          </el-form-item>
        </el-form>
        <el-checkbox v-model="shortCloseAcknowledged">
          我确认剩余 {{ shortShipment.outstanding }}{{ displayUnit(shortShipment.unit) }} 不再发货，结案后需重新下单才能补发
        </el-checkbox>
      </template>
      <template #footer>
        <el-button :disabled="shortCloseSubmitting" @click="shortCloseDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="shortCloseSubmitting"
          :disabled="!shortCloseAcknowledged"
          @click="submitShortClose"
        >确认短交结案</el-button>
      </template>
    </el-dialog>

    <!-- ─── T-RTA (issues #531 + #571 business logic): 申请退货 对话框 ─── -->
    <el-dialog v-model="returnDialogVisible" title="申请退货" width="780px" destroy-on-close>
      <el-alert type="info" show-icon :closable="false" style="margin-bottom: 12px"
                title="退货流程说明"
                description="提交后退货单为 DRAFT 草稿. 客户服务 → 销售退货 菜单里点 提交审批, 财务/管理员审批后才会触发库存或退款动作. 退货数量不能超过已发货数量." />
      <el-form label-width="100px">
        <!-- T-RTA business logic (#571): 有食物 / 无食物 分支决定后端 AR冲减 + 库存 时机. -->
        <el-form-item label="退货类型" required>
          <el-radio-group v-model="returnForm.withGoods">
            <el-radio :value="true">有货退回 (入成品仓不良品区 → 完成时 AR 冲减)</el-radio>
            <el-radio :value="false">无食物退款 (审批立即 AR 冲减, 无库存动作)</el-radio>
          </el-radio-group>
          <div style="color: #909399; font-size: 12px; margin-top: 4px;">
            <!-- 🔴 2026-08-19: 原文案「总仓入库」与实际不符 (落的是成品仓的 DEFECTIVE 批次),
                 且没讲「不计入可售库存」——用户因此报「说会退回成品仓但是没有」。 -->
            <span v-if="returnForm.withGoods">实物退货: 仓库收到货 → 入成品仓【不良品区】→ 标记完成时触发 AR 冲减. ⚠️ 不良品不计入可售库存, 需质检合格后方可再次销售.</span>
            <span v-else>无实物: 审批通过即触发 AR/AP 冲减, 不产生库存动作.</span>
          </div>
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="退货日期" required>
              <el-date-picker v-model="returnForm.returnDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="退货原因" required>
              <el-select
                v-model="returnForm.reasonCategory"
                placeholder="请选择退货原因"
                style="width: 100%"
                @change="onReturnReasonCategoryChange"
              >
                <el-option v-for="opt in RETURN_REASON_OPTIONS" :key="opt" :label="opt" :value="opt" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <!-- fool-proof-design Rule 3: 选"其他"才展开自由文本 -->
        <el-form-item v-if="returnForm.reasonCategory === '其他'" label="其他原因说明" required>
          <el-input
            v-model="returnForm.reason"
            type="textarea"
            :rows="2"
            placeholder="请具体说明退货原因"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="returnForm.remark" type="textarea" :rows="2" maxlength="1000" show-word-limit />
        </el-form-item>
        <el-divider content-position="left">退货明细</el-divider>
        <el-table :data="returnForm.items" border size="small">
          <el-table-column width="50" align="center">
            <template #default="{ row }"><el-checkbox v-model="row.selected" /></template>
          </el-table-column>
          <el-table-column label="品名" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.itemName }}</template>
          </el-table-column>
          <el-table-column label="已发货" width="100" align="right">
            <template #default="{ row }">{{ row.maxQuantity }}</template>
          </el-table-column>
          <el-table-column label="退货数量" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row.quantity" :min="0" :max="row.maxQuantity || undefined" :precision="3"
                               :controls="false" size="small" style="width: 100%" :disabled="!row.selected" />
            </template>
          </el-table-column>
          <el-table-column label="行原因" min-width="180">
            <template #default="{ row }">
              <el-input v-model="row.reason" size="small" placeholder="(可选)" maxlength="500" :disabled="!row.selected" />
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <template #footer>
        <el-button @click="returnDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="returnSubmitting" @click="handleCreateReturn">提交退货申请</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="signatureDialogVisible"
      :title="`客户签收 — ${signatureTarget?.deliveryNumber || ''}`"
      width="620px"
      destroy-on-close
      :close-on-click-modal="false"
      :close-on-press-escape="signatureQueue.pending === 0"
      :show-close="signatureQueue.pending === 0"
    >
      <el-alert
        type="warning"
        :closable="false"
        title="先核对发货单，再上传客户签收照片"
        description="必须填写签收人并至少上传 1 张真实签收凭证。照片上传成功后才能确认；资料不一致时请停止，不要先点签收。"
        style="margin-bottom: 16px"
      />
      <el-descriptions :column="2" border class="signature-context">
        <el-descriptions-item label="销售订单">{{ order?.orderNumber || orderId }}</el-descriptions-item>
        <el-descriptions-item label="发货单">{{ signatureTarget?.deliveryNumber || '-' }}</el-descriptions-item>
        <el-descriptions-item label="客户" :span="2">
          {{ order?.customerName || signatureTarget?.customerName || '-' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-width="92px" class="signature-form">
        <el-form-item label="签收人" required>
          <el-input
            v-model="signatureForm.signedByName"
            maxlength="100"
            show-word-limit
            placeholder="填写客户现场实际签收人姓名"
          />
        </el-form-item>
        <el-form-item label="签收凭证" required>
          <div class="signature-upload-field">
            <AttachmentDropZone
              v-if="signatureTarget"
              :key="signatureUploadKey"
              entity-type="SHIPMENT"
              :entity-id="String(signatureTarget.id)"
              :factory-id="factoryId"
              business-tag="CUSTOMER_RECEIPT_SIGNATURE"
              file-category="SIGNATURE"
              accept="image/*"
              :max-size="5 * 1024 * 1024"
              :max-files="3"
              @uploaded="handleSignatureUploaded"
              @deleted="handleSignatureDeleted"
              @queue-change="handleSignatureQueueChange"
            />
            <div class="signature-upload-status" :class="{ 'is-ready': signaturePhotoUrls.length > 0 && signatureQueue.failed === 0 }">
              已关联 {{ signaturePhotoUrls.length }} 张签收照片
              <span v-if="signatureQueue.pending > 0"> · {{ signatureQueue.pending }} 张上传中，请等待</span>
              <span v-if="signatureQueue.failed > 0"> · {{ signatureQueue.failed }} 张失败，请删除后重传</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="签收备注">
          <el-input
            v-model="signatureForm.remark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="可填写外箱、数量或客户现场异常；无异常可留空"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting || signatureQueue.pending > 0" @click="signatureDialogVisible = false">取消</el-button>
        <el-button
          type="success"
          :loading="submitting"
          :disabled="!signatureSubmissionReady"
          @click="submitDeliveredWithSignature"
        >保存凭证并确认签收</el-button>
      </template>
    </el-dialog>

    <!-- ─── 批次分配对话框 (P0-13 强制批次追溯) ─── -->
    <el-dialog v-model="batchAllocDialogVisible" title="成品批次分配" width="880px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        title="系统已按 FIFO (生产日期升序) 预填推荐批次"
        description="每行 '分配合计' 不能超过发货数量才能提交(可以少分配, 用于短发场景)。可手动调整分配数量或选择其他批次。"
        style="margin-bottom: 12px"
      />
      <div v-if="batchAllocForm.deliveryNumber" style="margin-bottom: 8px; color: #606266;">
        发货单: <strong>{{ batchAllocForm.deliveryNumber }}</strong>
      </div>
      <div v-for="(item, idx) in batchAllocForm.items" :key="idx" style="margin-bottom: 20px; padding: 10px; border: 1px solid #ebeef5; border-radius: 4px;">
        <div style="display: flex; justify-content: space-between; margin-bottom: 10px;">
          <span style="font-weight: 500;">{{ item.productName }}</span>
          <span>
            发货数量: <strong>{{ item.deliveredQuantity }}{{ displayUnit(item.unit) }}</strong>
            <span v-if="item.isMaterial" style="margin-left: 16px; color: #67c23a;">物料 · 自动扣减</span>
            <span v-else style="margin-left: 16px;" :style="{ color: Math.abs(sumAllocated(item) - item.deliveredQuantity) < 0.001 ? '#67c23a' : '#f56c6c' }">
              分配合计: <strong>{{ sumAllocated(item) }}{{ displayUnit(item.unit) }}</strong>
            </span>
          </span>
        </div>
        <!-- 🔴 C1 (2026-07-05): 同一产品的成品批次可能记录在不同单位(如同产品一批 kg 一批 盒,
             F006 现场确认) — 可用/分配数量已由后端换算为本行单位(item.unit); 当某批次的入库
             原生单位(batchNativeUnit)与本行单位不同, 额外标注原生单位, 避免仓管/销售误判"批次
             实际按什么单位入库的" (fool-proof Rule 2: 上下文必带身份信息)。 -->
        <!-- 2026-08-12: 物料(原料/辅料/包材)不参与成品批次分配 —— 它的货在物料仓,
             确认发货时按 FIFO 自动扣。这里必须说清楚, 否则会落到下面那条
             「全厂无可发货成品库存, 请先完成生产入库」的空态上, 把仓管指向错误的方向。 -->
        <el-alert v-if="item.isMaterial" type="success" :closable="false" show-icon
          title="物料无需分配批次"
          description="原料 / 辅料 / 包材在确认发货时按先进先出自动扣减物料库存，本行可直接提交。"
        />
        <el-table v-else-if="item.allocations.length > 0" :data="item.allocations" border size="small">
          <el-table-column prop="batchNumber" label="批次号" width="200" />
          <el-table-column prop="productionDate" label="生产日期" width="120" />
          <el-table-column label="可用数量" width="150" align="right">
            <template #default="{ row }">
              {{ row.availableQuantity }}{{ displayUnit(row.unit || item.unit) }}
              <div v-if="row.batchNativeUnit && row.batchNativeUnit !== (row.unit || item.unit)" style="font-size: 12px; color: #909399;">
                (批次入库单位: {{ displayUnit(row.batchNativeUnit) }})
              </div>
            </template>
          </el-table-column>
          <el-table-column label="分配数量" width="200" align="center">
            <template #default="{ row }">
              <el-input-number v-model="row.allocatedQty" :min="0" :max="row.availableQuantity" :precision="2" :step="1" size="small" style="width: 120px" />
              <span style="margin-left: 4px;">{{ displayUnit(row.unit || item.unit) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-alert
          v-else
          :type="item.stockWarehouses.length > 0 ? 'error' : 'warning'"
          :closable="false"
          :title="emptyStateTitle(item)"
          :description="emptyStateDesc(item)"
        />
      </div>
      <template #footer>
        <el-button @click="batchAllocDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleBatchAllocate">确认分配</el-button>
      </template>
    </el-dialog>

    <!-- ─── 上传发票 PDF 对话框 (V3 P0-3c — 4 步开票闭环最后一环) ─── -->
    <el-dialog v-model="issueDialogVisible" title="开具发票 — 上传 PDF 附件" width="520px" destroy-on-close>
      <el-alert
        type="warning"
        :closable="false"
        title="客户原话: 销售从订单页直接下载发票"
        description="财务开具的发票 PDF 必须以附件形式上传到本申请, 否则无法完成开票闭环。"
        style="margin-bottom: 16px"
      />
      <el-upload
        :auto-upload="false"
        :limit="1"
        accept=".pdf,application/pdf"
        :on-change="handleFileChange"
        :on-remove="() => { issuePdfFile = null; }"
        drag
      >
        <el-icon class="el-icon--upload"><i class="el-icon-upload" /></el-icon>
        <div class="el-upload__text">将 PDF 文件拖到此处, 或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">仅支持 .pdf 格式, 单文件 ≤ 10MB</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!issuePdfFile" @click="handleIssueInvoice">确认开具</el-button>
      </template>
    </el-dialog>

    <!-- ─── 登记收款对话框 ─── -->
    <el-dialog v-model="paymentDialogVisible" title="登记收款" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item v-if="canViewPrice" label="收款金额" required>
          <!-- fool-proof-design Rule 1 (block-not-clamp fix): 不绑定 :max — ElementPlus
               InputNumber 会在按键时静默 clamp modelValue 到 max (见 procurement/receives/list.vue
               同款修复注释), 这里改用 paymentOverLimit computed 驱动红框 + 红字 + 禁用提交. -->
          <el-input-number
            v-model="paymentForm.amount"
            :min="0"
            :precision="2"
            style="width: 200px"
            :class="{ 'over-limit-input': paymentOverLimit }"
          />
          <span style="margin-left: 12px; color: #909399">待收 {{ formatAmount(computedRemainingAmount()) }}</span>
          <div v-if="paymentOverLimit" class="over-limit-hint over-limit-hint--danger">
            超出待收余额 {{ formatAmount(computedRemainingAmount()) }}, 请调低
          </div>
        </el-form-item>
        <el-form-item label="收款方式">
          <el-select v-model="paymentForm.paymentMethod" style="width: 200px">
            <el-option label="银行转账" value="BANK_TRANSFER" />
            <el-option label="现金" value="CASH" />
            <el-option label="支票" value="CHECK" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="收款日期">
          <el-date-picker v-model="paymentForm.paymentDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="参考号/凭证">
          <el-input v-model="paymentForm.paymentReference" placeholder="银行流水号 / 凭证号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="paymentForm.remark" type="textarea" :rows="2" placeholder="如: 定金 / 尾款" />
        </el-form-item>
        <el-form-item label="回款凭证">
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".pdf,image/jpeg,image/png,.jpg,.png"
            :on-change="handleReceiptChange"
            :on-remove="() => { receiptFile = null; paymentForm.receiptUrl = ''; }"
          >
            <el-button size="small" :loading="receiptUploading">选择凭证</el-button>
            <template #tip>
              <div class="el-upload__tip">PDF/JPG/PNG, ≤10MB (可选)</div>
            </template>
          </el-upload>
          <span v-if="paymentForm.receiptUrl" style="font-size:12px;color:#67c23a;margin-left:8px">已上传</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="paymentDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!paymentForm.amount || paymentForm.amount <= 0 || paymentOverLimit"
          @click="handleCreatePayment"
        >登记收款</el-button>
      </template>
    </el-dialog>

    <!-- ─── Task 4: 编辑产品行对话框 ─── -->
    <el-dialog
      v-model="editItemsVisible"
      :title="`编辑产品行 — ${order?.orderNumber || ''}`"
      width="820px"
      destroy-on-close
    >
      <OrderItemsEditor v-model:items="editItemsRows" :products="products">
        <template #empty-products>
          <UpstreamMissingHint
            description="本工厂暂无产品类型，无法添加产品行"
            target-module="system"
            action-text="去创建产品类型"
            contact-text="请联系管理员先创建产品类型"
            :require-write="true"
            @action="goCreateProductType"
          />
        </template>
      </OrderItemsEditor>
      <template #footer>
        <el-button @click="editItemsVisible = false">取消</el-button>
        <el-button type="primary" :loading="editItemsSaving" @click="saveEditItems">保存</el-button>
      </template>
    </el-dialog>

    <DocumentTraceDrawer
      v-model="documentTraceVisible"
      title="销售订单单据追踪"
      anchor-label="销售订单"
      :fetcher="loadDocumentTrace"
    />
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; padding: 20px; overflow-y: auto; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: center; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
  }
  .header-right { display: flex; gap: 8px; }
}
.business-tabs { margin-top: 20px; }
.customer-supplied-progress { margin-top: 18px; }
.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
  h3 { margin: 0 0 4px; font-size: 15px; }
  p { margin: 0; color: #606266; font-size: 13px; }
}
.tab-toolbar {
  display: flex; align-items: center; gap: 16px; padding: 4px 0 12px;
  .tab-hint { color: #909399; font-size: 12px; }
}
.tab-badge { :deep(.el-badge__content) { transform: translateY(-2px) translateX(8px); } }
.tax-breakdown { display: flex; flex-wrap: wrap; gap: 4px; }
// E-4 含税/未税汇总条
.tax-summary-bar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-top: 8px; padding: 10px 16px;
  background: #f5f7fa; border-radius: 6px;
  font-size: 13px;
  .tax-summary-item { display: flex; flex-direction: column; align-items: flex-end; }
  .tax-summary-label { font-size: 11px; color: #909399; margin-bottom: 2px; }
  .tax-summary-value { font-weight: 600; color: #303133; }
  .tax-value { color: #e6a23c; }
  .tax-total { color: #409eff; font-size: 15px; }
  .tax-summary-sep { color: #c0c4cc; font-size: 16px; font-weight: 300; align-self: center; }
}

// V3 P0-11 补强 — 审批 timeline
.approval-timeline-section {
  margin-top: 28px;
  padding-top: 16px;
  border-top: 1px dashed #ebeef5;

  h3 { margin: 0 0 16px; font-size: 15px; color: #303133; }

  .timeline-card { padding: 8px 12px;
    .timeline-title { font-weight: 600; color: #303133; font-size: 14px; margin-bottom: 4px; }
    .timeline-meta { color: #606266; font-size: 12px; display: flex; align-items: center; gap: 4px; }
    .timeline-notes { margin-top: 6px; padding: 6px 8px; background: #fafafa; border-radius: 4px;
      color: #909399; font-size: 12px; }
  }
}

// fool-proof-design Rule 1: 收款/超限灰字提示 (正常) / 超限变红 (与 :class 前置拦截 +
// 提交禁用二重防御, 不用 el-input-number :max 静默 clamp)
.over-limit-hint {
  font-size: 11px;
  color: #909399;
  line-height: 1.4;
  margin-top: 2px;
}
.over-limit-hint--danger {
  color: #f56c6c;
}
.delivery-capacity-hint {
  margin-top: 4px;
  color: #909399;
  font-size: 11px;
  white-space: nowrap;
}
.delivery-amount-summary {
  display: flex;
  justify-content: flex-end;
  gap: 24px;
  margin-top: 12px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  color: #606266;
}
:deep(.over-limit-input .el-input__wrapper) {
  box-shadow: 0 0 0 1px #f56c6c inset;
}
/* 刚创建的发货单行 —— 建完自动切到本 tab 后, 让人一眼找到是哪一条 (客户 2026-07-31) */
:deep(.just-created-row > td) {
  background: var(--el-color-primary-light-9) !important;
}
.muted-audit { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
.signature-context { margin-bottom: 16px; }
.signature-form { margin-top: 16px; }
.signature-upload-field { width: 100%; }
.signature-upload-status {
  margin-top: 8px;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.5;
}
.signature-upload-status.is-ready { color: var(--el-color-success); }
</style>
