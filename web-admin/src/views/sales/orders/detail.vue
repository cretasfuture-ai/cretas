<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import { get, post, put } from '@/api/request';
import OrderItemsEditor, { type OrderItemRow } from './components/OrderItemsEditor.vue';
import { initialTaxIncludedUnitPrice } from './salesOrderNewLineContract';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import AiUploadHint from '@/components/common/AiUploadHint.vue';
import DocumentTraceDrawer from '@/components/DocumentTraceDrawer.vue';
import AttachmentDropZone from '@/components/attachment/AttachmentDropZone.vue';
import DeliverySignatureReceipt from './components/DeliverySignatureReceipt.vue';
import InvoicePdfDownload from '@/components/finance/InvoicePdfDownload.vue';
import type { Attachment } from '@/api/attachment';
import { getSalesOrderDocumentTrace } from '@/api/documentTrace';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { ElMessage, ElMessageBox } from 'element-plus';
import { confirmPackagingConversion, mismatchConfirmLines } from '@/views/sales/packagingConversion';
import CsDateField from '@/components/cs/CsDateField.vue';
import { badgeColor, type CsBadgeColor, type ElementTagType } from '@/utils/csBadge';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
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
import { formatUnitPrice, salesOrderDisplayMoney, salesOrderReceivableAmount, salesOrderRemainingAmount, warehouseNameById, type WarehouseIdNameLike } from './salesOrderDisplay';
import { createAutoFullChildShipment } from './deliveryAutoChild';
import { knownUnitPrice, withKnownUnitPrice } from './deliveryPricePayload';
import { allocationNoticeLines, allocationNoticesOf, needsPackagingConfirmation, type ShipmentAllocationNotice } from './shipmentAllocationNotices';
import { useNativeDemoAdapters } from '@/demo/f006NativeBridge';
import { priceSourceLabel } from '@/api/customerPriceList';
import { commerceAdapter, entityFromResponse } from '@/demo/nativeCommerceSteps';

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
const signatureReceiptTarget = ref<TableRow | null>(null);
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
  orderId.value,
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
type AllocRow = { finishedGoodsBatchId: string; batchNumber: string; productionDate: string; availableQuantity: number; allocatedQty: number; unit: string; batchNativeUnit: string; packagingMismatch?: boolean; batchPackagingLabel?: string; linePackagingLabel?: string };
// 🔴 G1: sourceWarehouseCode (发货行声明的来源仓, 空=未声明) + stockWarehouses (该产品实际有货的仓库 code,
// 仅在无推荐批次时查, 用于诚实空态提示「成品在 X 仓」而非误导的「请先生产」)。
type AllocItem = { deliveryItemId: string; productName: string; productTypeId: string; deliveredQuantity: number; unit: string; sourceWarehouseCode: string; allocations: AllocRow[]; stockWarehouses: string[]; isMaterial: boolean; ineligibleNotice: string };
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

// ── Nuxt UI 版: 徽标映射 / 表格列 / 页签 / 选项 ──────────────────────────────────
type StatusMapEntry = { text: string; type: string };
/** 状态码 → 徽标色; type 为 '' 的是默认蓝 (primary), 查不到的走 fallback。 */
function mapColor(map: Record<string, StatusMapEntry>, key: unknown, fallback: CsBadgeColor = 'neutral'): CsBadgeColor {
  const hit = map[String(key ?? '')];
  return hit ? badgeColor((hit.type || 'primary') as ElementTagType) : fallback;
}
/** 状态码 → 中文; 查不到只显示「未知状态」, 不把后端枚举原文露给用户。 */
function mapText(map: Record<string, StatusMapEntry>, key: unknown): string {
  return map[String(key ?? '')]?.text ?? '未知状态';
}

const R = { class: { th: 'text-right', td: 'text-right' } };
const C = { class: { th: 'text-center', td: 'text-center' } };
const QTY3 = { minimumFractionDigits: 0, maximumFractionDigits: 3 };
const DELIVERY_METHOD_ITEMS = [
  { label: '物流配送', value: 'LOGISTICS' },
  { label: '自送', value: 'SELF_DELIVERY' },
  { label: '客户自提', value: 'SELF_PICKUP' },
];
const DELIVERY_MODE_ITEMS = [
  { label: '一次性全量发货（自动建一张全量子单）', value: 'FULL' },
  { label: '分批发货（后续逐批建子单）', value: 'PARTIAL' },
];
const PAYMENT_METHOD_ITEMS = [
  { label: '银行转账', value: 'BANK_TRANSFER' },
  { label: '现金', value: 'CASH' },
  { label: '支票', value: 'CHECK' },
  { label: '微信', value: 'WECHAT' },
  { label: '支付宝', value: 'ALIPAY' },
  { label: '其他', value: 'OTHER' },
];
const RETURN_TYPE_ITEMS = [
  { label: '有货退回 (入成品仓不良品区 → 完成时 AR 冲减)', value: 'goods' },
  { label: '无食物退款 (审批立即 AR 冲减, 无库存动作)', value: 'refund' },
];
const RETURN_REASON_ITEMS = [...RETURN_REASON_OPTIONS];
// URadioGroup 不收布尔值: 用字符串选项桥接 returnForm.withGoods
const returnWithGoodsChoice = computed({
  get: () => (returnForm.value.withGoods ? 'goods' : 'refund'),
  set: (v: string) => { returnForm.value.withGoods = v === 'goods'; },
});

const tabItems = computed(() => {
  const badge = (n: number) => (n > 0 ? { label: n > 99 ? '99+' : String(n), size: 'sm' as const, variant: 'soft' as const } : undefined);
  return [
    { label: '订单详情', value: 'detail', slot: 'detail' },
    { label: '开票记录', value: 'invoice', slot: 'invoice', badge: badge(invoices.value.length) },
    {
      label: `${label('delivery')}记录${!actionableDeliveries.value && deliveries.value.length ? `（${deliveries.value.length}）` : ''}`,
      value: 'delivery', slot: 'delivery', badge: badge(actionableDeliveries.value),
    },
    { label: '收款记录', value: 'payment', slot: 'payment', badge: badge(payments.value.length) },
    ...(canViewLinkedPurchases.value
      ? [{ label: '关联采购', value: 'purchase', slot: 'purchase', badge: badge(purchaseOrders.value.length) }]
      : []),
  ];
});

const reservationColumns: TableColumn<SalesOrderReservation>[] = [
  { id: 'product', accessorFn: (r) => r.productName || r.productTypeId, header: '成品' },
  { accessorKey: 'batchNumber', header: '精确批次' },
  { id: 'reserved', header: '已预留数量', meta: R },
  { id: 'state', header: '状态', meta: C },
];
const suppliedColumns: TableColumn<CustomerSuppliedMaterialRequirement>[] = [
  { id: 'material', header: '物料分类 / 名称' },
  { id: 'arrival', header: '预计到货' },
  { id: 'warehouse', header: '目标仓库' },
  { id: 'progress', header: '收货进度' },
  { id: 'state', header: '状态' },
];
const itemColumns = computed<TableColumn<TableRow>[]>(() => {
  const price = canViewPrice.value;
  const cols: (TableColumn<TableRow> | false)[] = [
    { id: 'productName', accessorFn: (r) => r.productName || '—', header: label('product') },
    { id: 'specification', accessorFn: (r) => r.specification || '—', header: '规格' },
    { id: 'quantity', accessorFn: (r) => r.quantity ?? '—', header: '订单数量', meta: R },
    { id: 'unit', accessorFn: (r) => displayUnit(r.unit) || '—', header: '单位', meta: C },
    { id: 'boxQuantity', accessorFn: (r) => r.boxQuantity || '—', header: '箱数', meta: R },
    price && { id: 'unitPrice', accessorFn: (r) => formatUnitPrice(r.unitPrice), header: '未税单价', meta: R },
    price && { id: 'taxIncludedPrice', accessorFn: (r) => formatTaxIncludedUnitPrice(r), header: '含税单价', meta: R },
    price && { id: 'priceSource', header: '价格来源' },
    { id: 'taxRate', header: '税率', meta: C },
    { id: 'delivered', accessorFn: (r) => r.deliveredQuantity || 0, header: '已发货', meta: R },
    { id: 'sourceWarehouse', accessorFn: (r) => sourceWarehouseLabel(r.sourceWarehouseCode) || '—', header: '来源仓库' },
    price && { id: 'lineAmount', header: '未税小计', meta: R },
    price && { id: 'lineTaxIncluded', header: '含税小计', meta: R },
  ];
  return cols.filter((c): c is TableColumn<TableRow> => c !== false);
});
const taxGroupColumns: TableColumn<{ taxRate: number; amount: number }>[] = [
  { id: 'rate', header: '税率', meta: C },
  { id: 'groupSum', header: '金额小计', meta: R },
];
const invoiceColumns = computed<TableColumn<TableRow>[]>(() => {
  const price = canViewPrice.value;
  const cols: (TableColumn<TableRow> | false)[] = [
    { accessorKey: 'invoiceNumber', header: '发票编号' },
    { id: 'status', header: '状态', meta: C },
    price && { id: 'amount', header: '不含税', meta: R },
    price && { id: 'taxAmount', header: '税额', meta: R },
    price && { id: 'totalAmount', header: '价税合计', meta: R },
    price && { id: 'taxBreakdown', header: '税率分组' },
    { id: 'requestedAt', accessorFn: (r) => r.requestedAt || '—', header: '申请时间' },
    { id: 'actions', header: '操作', meta: C },
  ];
  return cols.filter((c): c is TableColumn<TableRow> => c !== false);
});
const deliveryColumns = computed<TableColumn<TableRow>[]>(() => {
  const cols: (TableColumn<TableRow> | false)[] = [
    { id: 'role', header: '层级' },
    { accessorKey: 'deliveryNumber', header: '发货/发运单号' },
    { id: 'deliveryDate', accessorFn: (r) => r.deliveryDate || '—', header: '发货日期' },
    {
      id: 'method', header: '配送方式',
      accessorFn: (r) => ({ LOGISTICS: '物流配送', SELF_DELIVERY: '自送', SELF_PICKUP: '自提' } as Record<string, string>)[r.deliveryMethod] || (r.deliveryMethod ? '其他' : '未记录（历史数据）'),
    },
    { id: 'logistics', accessorFn: (r) => r.logisticsCompany || '未填写（历史数据）', header: '物流公司' },
    { id: 'tracking', accessorFn: (r) => r.trackingNumber || '未填写（历史数据）', header: '运单号' },
    { id: 'status', header: '状态', meta: C },
    canViewPrice.value && { id: 'amount', header: '金额', meta: R },
    { id: 'allocation', header: '批次分配', meta: C },
    { id: 'audit', header: '数量与操作审计' },
    { id: 'actions', header: '操作', meta: C },
  ];
  return cols.filter((c): c is TableColumn<TableRow> => c !== false);
});
/** 刚创建的发货单行高亮 (原 el-table :row-class-name)。 */
const deliveryTableMeta = { class: { tr: (row: { original: TableRow }) => deliveryRowClass({ row: row.original }) } };
const paymentColumns = computed<TableColumn<TableRow>[]>(() => {
  const cols: (TableColumn<TableRow> | false)[] = [
    { accessorKey: 'paymentNumber', header: '收款单号' },
    { id: 'paymentDate', accessorFn: (r) => r.paymentDate || '—', header: '收款日期' },
    canViewPrice.value && { id: 'amount', header: '收款金额', meta: R },
    { id: 'method', header: '收款方式', meta: C },
    { id: 'paymentReference', accessorFn: (r) => r.paymentReference || '—', header: '参考号/凭证' },
    { id: 'status', header: '状态', meta: C },
    { id: 'remark', accessorFn: (r) => r.remark || '—', header: '备注' },
  ];
  return cols.filter((c): c is TableColumn<TableRow> => c !== false);
});
const purchaseColumns = computed<TableColumn<TableRow>[]>(() => {
  const cols: (TableColumn<TableRow> | false)[] = [
    { accessorKey: 'orderNumber', header: '采购单号' },
    { id: 'supplierName', accessorFn: (r) => r.supplierName || '—', header: '供应商' },
    { id: 'orderDate', accessorFn: (r) => r.orderDate || '—', header: '下单日期' },
    { id: 'status', header: '状态', meta: C },
    canViewPrice.value && { id: 'amount', header: '金额', meta: R },
    { id: 'actions', header: '操作', meta: C },
  ];
  return cols.filter((c): c is TableColumn<TableRow> => c !== false);
});
const deliveryItemColumns = computed<TableColumn<TableRow>[]>(() => {
  const price = canViewPrice.value;
  const cols: (TableColumn<TableRow> | false)[] = [
    { id: 'productName', accessorFn: (r) => r.productName || '—', header: label('product') },
    { id: 'qty', header: '本次发货数量' },
    { id: 'unit', header: '单位', meta: C },
    price && { id: 'unitPrice', header: '未税单价', meta: R },
    price && { id: 'subtotal', header: '本次发货小计（未税）', meta: R },
    price && { id: 'tax', header: '税额', meta: R },
  ];
  return cols.filter((c): c is TableColumn<TableRow> => c !== false);
});
const shipmentItemColumns: TableColumn<TableRow>[] = [
  { id: 'productName', accessorFn: (r) => r.productName || '—', header: '成品' },
  { id: 'qty', header: '本次发运数量' },
  { id: 'unit', header: '单位' },
  { id: 'remaining', header: '母单剩余' },
];
const returnItemColumns: TableColumn<ReturnDialogItem>[] = [
  { id: 'selected', header: '' },
  { id: 'itemName', accessorFn: (r) => r.itemName || '—', header: '品名' },
  { id: 'maxQuantity', accessorFn: (r) => r.maxQuantity, header: '已发货', meta: R },
  { id: 'qty', header: '退货数量' },
  { id: 'reason', header: '行原因' },
];

// 回款凭证 / 发票 PDF: 原 el-upload 换成原生文件选择 (弹层整体是 UModal, 里面不放 Element 控件)
function onReceiptPicked(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) void handleReceiptChange({ raw: file });
  else { receiptFile.value = null; paymentForm.value.receiptUrl = ''; }
}
function onIssuePdfPicked(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (file) handleFileChange({ raw: file });
  else issuePdfFile.value = null;
}
function onIssuePdfDropped(e: DragEvent) {
  const file = e.dataTransfer?.files?.[0];
  if (file) handleFileChange({ raw: file });
}
/**
 * Nuxt UI 的模态层关闭动画结束前, body 带 pointer-events:none, 此时弹 ElMessageBox 点不了。
 * 在关弹层后立刻要弹 ElMessageBox 的地方先等它解除 (最多 1.2 秒)。
 */
async function waitOverlayReleased(): Promise<void> {
  await nextTick();
  for (let i = 0; i < 24 && document.body.style.pointerEvents === 'none'; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

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
      quotedGrossUnitPrice: it.quotedGrossUnitPrice,
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
        unitPrice: knownUnitPrice(it.unitPrice),
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
      items: filteredItems.map((it) => withKnownUnitPrice(it)),
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
          onAllocationNotices: (notices) => { void promptAllocationNotices(notices, null); },
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
  if (['DELIVERED', 'SIGNED'].includes(String(row.status || ''))) {
    if (row.recordRole === 'MASTER') return '签收汇总：以子发运签收记录为准';
    return `客户签收：${row.signedByName || '未记录签收人'} · ${row.signedAt ? formatBusinessDateTime(row.signedAt) : '未记录签收时间'}`;
  }
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
      const notices = allocationNoticesOf(res.data);
      if (notices.length > 0) {
        await promptAllocationNotices(notices, res.data as { id?: string; deliveryNumber?: string } | null);
      }
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

/**
 * Q3: 子发运单建成了, 但有的行没能自动分配批次 (只剩规格不一致的批次要确认换算等)。把每行的真实原因
 * 告诉用户; 知道是哪张子单且需要确认换算时, 直接打开它的「分配批次」(那里逐批确认换算, 防呆 Rule 5)。
 */
async function promptAllocationNotices(
  notices: ShipmentAllocationNotice[],
  shipment: { id?: string; deliveryNumber?: string } | null,
) {
  const number = shipment?.deliveryNumber ? ` ${shipment.deliveryNumber}` : '';
  const text = `子发运单${number}已创建，下面这些行没有自动分配批次：\n${allocationNoticeLines(notices).join('\n')}`;
  if (!shipment?.id || !needsPackagingConfirmation(notices)) {
    ElMessage({
      type: 'warning',
      message: `${text}\n请在「发货记录」里这张子发运单上点「分配批次」处理。`,
      duration: 0,
      showClose: true,
    });
    return;
  }
  await waitOverlayReleased();
  try {
    await ElMessageBox.confirm(text, '批次没有自动分配', {
      type: 'warning',
      confirmButtonText: '去分配批次并确认换算',
      cancelButtonText: '稍后在发货记录处理',
      customStyle: { whiteSpace: 'pre-line' },
    });
  } catch {
    return; // 稍后处理: 发货记录里这张子单仍显示「分配批次」
  }
  await openBatchAllocDialog(String(shipment.id), String(shipment.deliveryNumber || shipment.id));
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
      let ineligibleNotice = '';
      // 物料行不查成品批次 —— 查了必然是空, 然后显示一句误导仓管的空态。
      const isMaterial = materialIdSet.value.has(String(productTypeId));
      if (!isMaterial && productTypeId && deliveredQuantity > 0) {
        const existingRes = await get<Array<TableRow>>(
          `/${factoryId.value}/sales-deliveries/items/${deliveryItemId}/batch-allocations`
        );
        // ERP-S3-TOLL-DELIVERY-PREFILL-COMPANY-FG-001: 已有分配可能是按旧规则写的
        // (代加工订单被预填了公司库存)。后端逐条用仓库确认同一道所有权闸判 eligible;
        // 有任何一条不能发, 就不再展示这组旧分配, 改走下面的推荐 (只会给本订单能发的批次),
        // 并说清楚原来那几个批次为什么不能发 —— 否则用户只能看着一个注定被拒的批次。
        const existingRows = existingRes.success && Array.isArray(existingRes.data) ? existingRes.data : [];
        const ineligibleRows = existingRows.filter(r => r.eligible === false);
        if (ineligibleRows.length > 0) {
          ineligibleNotice = `原分配的批次 ${ineligibleRows.map(r => String(r.batchNumber || '')).join('、')} 不能用于本订单：`
            + `${String(ineligibleRows[0].ineligibleReason || '库存所有权不符')}。已按本订单可发的批次重新推荐，确认分配后替换原分配。`;
        } else if (existingRows.length > 0) {
          allocations = existingRows.map(r => ({
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
              packagingMismatch: r.packagingMismatch === true,
              batchPackagingLabel: String(r.batchPackagingLabel || ''),
              linePackagingLabel: String(r.linePackagingLabel || ''),
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
      items.push({ deliveryItemId, productName, productTypeId, deliveredQuantity, unit, sourceWarehouseCode, allocations, stockWarehouses, isMaterial, ineligibleNotice });
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
  // O-309-1: 规格不一致的批次在提交前一次问清, 确认过的行才带上确认标记
  const mismatchLines = activeItems.flatMap(item => item.isMaterial ? [] : mismatchConfirmLines(item.productName, item.allocations));
  if (mismatchLines.length > 0 && !(await confirmPackagingConversion(mismatchLines))) return;
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
        .map(a => ({
          finishedGoodsBatchId: a.finishedGoodsBatchId,
          allocatedQty: Number(a.allocatedQty),
          ...(a.packagingMismatch ? { packagingConversionConfirmed: true } : {}),
        }));
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
  return salesOrderRemainingAmount(order.value);
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

useNativeDemoAdapters('sales-order-detail', [
  commerceAdapter('sales-order-submit', () => canWrite.value, async ({ state, ui }) => {
    const response = await ui.capture({ method: 'POST', path: /\/sales\/orders\/[^/]+\/(confirm|submit-for-review)/ }, async () => {
      await ui.click('[data-testid="sales-order-submit"]', '原生提交销售审批');
      await ui.click('.el-message-box__btns .el-button--primary', '确认提交销售审批');
    });
    await ui.hold('销售提交后');
    const created = entityFromResponse(response);
    let entity = entityFromResponse(await ui.read(`/sales/orders/${created?.id || state.entities.salesOrder?.id || orderId.value}`));
    if(entity?.status==='CONFIRMED'){
      await ui.capture({method:'POST',path:`/sales/orders/${entity.id}/submit-for-review`},async()=>{
        await ui.click('[data-testid="sales-order-resubmit"]','提交原生销售 OA 审批');
        await ui.click('.el-message-box__btns .el-button--primary','确认提交销售 OA 审批');
      });
      entity=entityFromResponse(await ui.read(`/sales/orders/${entity.id}`));
    }
    if (entity) ui.remember('salesOrder', entity);
    return entity;
  },async ({ui})=>{await ui.waitFor('[data-testid="sales-order-submit"]')}),
  commerceAdapter('sales-delivery-create', () => canWrite.value, async ({ state, ui }) => {
    const response = await ui.capture({ method: 'POST', path: '/sales/deliveries' }, () => ui.click('[data-testid="sales-delivery-create"]', '原生创建发货单'));
    await ui.hold('销售发货单创建后');
    const created = entityFromResponse(response);
    if (!created?.id) throw new Error('原生发货单响应缺少 ID');
    const entity = entityFromResponse(await ui.read(`/sales/deliveries/${created.id}`));
    if (entity) ui.remember('salesDelivery', entity);
    return entity;
  }, async ({ state, ui }) => {
    await loadOrder();
    await loadDeliveries();
    await ui.until(()=>String(order.value?.id)===String(state.entities.salesOrder?.id),'本轮销售订单详情加载');
    openDeliveryDialog();
    deliveryForm.value.deliveryMode = 'PARTIAL';
    await ui.fill('发货地址', () => { deliveryForm.value.deliveryAddress = '演示·上海连锁餐饮收货中心' });
    await ui.fill('物流公司', () => { deliveryForm.value.logisticsCompany = '演示冷链配送' });
    await ui.fill('运单号', () => { deliveryForm.value.trackingNumber = state.runKey });
    for (const item of deliveryForm.value.items) item.deliveredQuantity = Number(item.maxDeliverableQuantity);
    if (!deliveryDialogVisible.value) throw new Error('原生销售发货弹窗未打开');
  }),
  commerceAdapter('sales-shipment-create', () => canWrite.value, async ({ state, ui }) => {
    const response = await ui.capture({ method: 'POST', path: /\/sales\/deliveries\/[^/]+\/shipments/ }, () => ui.click('[data-testid="sales-shipment-create"]', '原生创建子发运单'));
    await ui.hold('子发运单创建后');
    const created = entityFromResponse(response);
    if (!created?.id) throw new Error('原生子发运单响应缺少 ID');
    const entity = entityFromResponse(await ui.read(`/sales/deliveries/${created.id}`));
    if (entity) ui.remember('salesShipment', entity);
    return entity;
  }, async ({ state, context, ui }) => {
    const parent = state.entities.salesDelivery;
    if (!parent?.id) throw new Error('缺少本次销售发货单 ID');
    await ui.until(()=>deliveries.value.some(row=>String(row.id)===String(parent.id)),'本轮母发货单加载');
    const source = deliveries.value.find(row => String(row.id) === String(parent.id))!;
    openShipmentDialog(source);
    await ui.fill('物流公司', () => { shipmentForm.value.logisticsCompany = '演示冷链配送' });
    await ui.fill('运单号', () => { shipmentForm.value.trackingNumber = state.runKey });
    await ui.fill('收货地址', () => { shipmentForm.value.deliveryAddress = '演示·上海连锁餐饮收货中心' });
    for (const item of shipmentForm.value.items) item.quantity = Number(item.remaining);
    if (!shipmentDialogVisible.value) throw new Error('原生子发运弹窗未打开');
  }),
  commerceAdapter('sales-batch-allocate', () => canWrite.value, async ({ state, ui }) => {
    await ui.capture({ method: 'POST', path: /\/sales-deliveries\/items\/[^/]+\/batch-allocations/ }, () => ui.click('[data-testid="sales-batch-allocate"]', '原生确认批次分配'));
    await ui.hold('批次分配后');
    const delivery = state.entities.salesShipment || state.entities.salesDelivery;
    if (!delivery?.id) throw new Error('缺少本次发运单 ID');
    const detail = entityFromResponse(await ui.read(`/sales/deliveries/${delivery.id}`));
    const lines = Array.isArray(detail?.items) ? detail.items as Array<{ id?: string }> : [];
    const allocations = (await Promise.all(lines.map(line => ui.read(`/sales-deliveries/items/${line.id}/batch-allocations`)))).flatMap(value => Array.isArray(value) ? value : []);
    const result = { id: String(delivery.id), allocations };
    ui.remember('salesBatchAllocation', result);
    return result;
  }, async ({ state,ui }) => {
    await ui.until(()=>String(order.value?.id)===String(state.entities.salesOrder?.id),'本轮销售订单详情加载');
    const delivery = state.entities.salesShipment;
    if (!delivery?.id) throw new Error('缺少本次销售子发运单 ID');
    await openBatchAllocDialog(String(delivery.id), String(delivery.deliveryNumber || delivery.orderNumber || delivery.id));
    if (!batchAllocDialogVisible.value) throw new Error('原生批次分配弹窗未打开');
  }),
])

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
  <div class="page-wrapper">
    <section class="cs-card page-card">
      <header class="cs-card-head card-header">
        <div class="header-left">
          <UButton color="neutral" variant="outline" icon="i-lucide-arrow-left" @click="router.push('/sales/orders')">返回</UButton>
          <h2 class="page-title">{{ label('salesOrder') }}详情</h2>
          <UButton v-if="order" color="neutral" variant="outline" @click="documentTraceVisible = true">单据追溯</UButton>
          <UBadge v-if="order" :color="mapColor(statusMap, order.status)" variant="soft" size="lg">
            {{ mapText(statusMap, order.status) }}
          </UBadge>
          <UBadge v-if="order" :color="mapColor(orderPaymentStatusMap, order.paymentStatus, 'neutral')" variant="soft">
            收款: {{ orderPaymentStatusMap[order.paymentStatus]?.text || '待收款' }}
          </UBadge>
          <UBadge v-if="order" :color="mapColor(orderInvoiceStatusMap, order.invoiceStatus, 'neutral')" variant="soft">
            开票: {{ orderInvoiceStatusMap[order.invoiceStatus]?.text || '待开票' }}
          </UBadge>
          <UBadge v-if="order" :color="mapColor(orderTransportStatusMap, order.transportPlanStatus, 'neutral')" variant="soft">
            运输: {{ orderTransportStatusMap[transportDisplayStatus]?.text || '待出厂' }}
          </UBadge>
        </div>
        <div v-if="order && canWrite" class="header-right">
          <UButton data-testid="sales-order-submit" v-if="order.status === 'DRAFT'" color="success" :loading="submitting" @click="handleAction('confirm')">确认并提交 OA 审批</UButton>
          <UButton data-testid="sales-order-resubmit" v-if="order.status === 'CONFIRMED'" color="warning" :loading="submitting" @click="handleAction('submit-for-review')">提交 OA 审批</UButton>
          <UButton v-if="order.status === 'PENDING_FINANCE_REVIEW'" variant="outline" @click="goToApprovalProgress">前往 OA 审批中心</UButton>
          <!-- 提示放 title (原生), 不用 UTooltip: 它未包 UApp 会整页渲染失败 -->
          <span
            v-if="order.status === 'FINANCE_APPROVED'"
            :title="productionAction.tooltip"
          >
            <UButton
              :loading="productionPlansLoading"
              :disabled="productionAction.disabled"
              @click="handleStartProduction"
            >{{ productionAction.label }}</UButton>
          </span>
          <UButton
            v-if="productionAction.relatedId"
            variant="link"
            @click="router.push(`/production/plans?salesOrderId=${orderId}`)"
          >查看生产计划</UButton>
          <!-- 销售只创建发货任务；库存扣减固定由仓库出货管理确认。 -->
          <span
            v-if="['CONFIRMED','FINANCE_APPROVED','PROCESSING','PARTIAL_DELIVERED'].includes(order.status)"
            :title="deliveryAction.tooltip"
          >
            <UButton
              :loading="deliveriesLoading"
              :disabled="deliveryAction.disabled"
              @click="openDeliveryDialog"
            >{{ deliveryAction.label }}</UButton>
          </span>
          <UButton v-if="isOrderDrivenCustomerSuppliedOrder" color="warning" variant="outline" @click="goWarehouseReceiving">前往仓储收货任务</UButton>
          <!-- T-SHORT-SHIP (owner 2026-09-04 拍板): 短交必须同时支持「补发」和「关单/短交
               结案」两条路, 且都是显式确认点击。两个按钮只在部分发货且实发有缺口时并排出现,
               不做任何默认/自动的那一条。 -->
          <UButton
            v-if="showShortShipmentActions"
            variant="outline"
            @click="handleReshipOutstanding"
          >补发剩余 {{ shortShipment.outstanding }}{{ displayUnit(shortShipment.unit) }}</UButton>
          <UButton
            v-if="showShortShipmentActions"
            color="error"
            variant="outline"
            @click="openShortCloseDialog"
          >短交结案</UButton>
          <!-- T-RTA (issue #531): F006 客户反馈 第四次会议 956-1037 — 申请退货 入口.
               Opens dialog that builds CreateReturnOrderRequest with returnType=SALES_RETURN. -->
          <UButton v-if="['PARTIAL_DELIVERED','DELIVERED','COMPLETED'].includes(order.status)"
                   color="warning" :loading="submitting" @click="openReturnDialog">申请退货</UButton>
          <UButton v-if="['DRAFT','CONFIRMED'].includes(order.status)" color="error" variant="outline" :disabled="submitting" @click="handleAction('cancel')">取消</UButton>
          <!-- Sprint 4 Wave 2 S-PROFIT-DETAIL-1: per-product profit drill-down.
               Only meaningful for non-DRAFT orders with line items + visible to price-cleared roles. -->
          <!-- 🔴 2026-08-22: 加 canViewProfitReport。这一页调 /reports/sales/profit-detail/,
               后端要 finance|procurement ⇒ 只看 canViewPrice 会让 sales_manager 点出 403。
               ▎「看得到金额」不等于「进得去利润报表」—— 两个判据不是一回事。 -->
          <UButton
            v-if="canViewPrice && canViewProfitReport && order.status !== 'DRAFT'"
            color="neutral"
            variant="outline"
            @click="router.push(`/sales/orders/${orderId}/profit`)"
          >产品级利润分析</UButton>
        </div>
        <div v-else-if="order && isOrderDrivenCustomerSuppliedOrder" class="header-right">
          <UButton color="warning" variant="outline" @click="goWarehouseReceiving">前往仓储收货任务</UButton>
        </div>
      </header>

      <div class="cs-card-body page-body">
      <div v-if="loading && !order" class="loading-state" data-testid="sales-order-loading">正在读取订单…</div>

      <!-- R18-ext #283: explicit empty state for 404 / not-found -->
      <UEmpty
        v-if="notFound"
        icon="i-lucide-file-x"
        :description="`订单 ${orderId} 不存在或已被删除`"
        :actions="[{ label: '返回订单列表', onClick: () => router.push('/sales/orders') }, { label: '返回上页', color: 'neutral', variant: 'outline', onClick: () => router.back() }]"
      />

      <template v-if="order && !notFound">
        <!-- 订单头部摘要 (4 状态联动) -->
        <dl class="desc-grid cols-4">
          <div><dt>订单编号</dt><dd>{{ order.orderNumber }}</dd></div>
          <div><dt>{{ label('customer') }}</dt><dd>{{ order.customerName || order.customer?.name || order.customerId }}</dd></div>
          <div><dt>下单日期</dt><dd>{{ order.orderDate || '—' }}</dd></div>
          <div><dt>业务员</dt><dd>{{ order.salesperson || '—' }}</dd></div>
          <div><dt>加工方式</dt><dd>{{ processingModeLabel(order.processingMode) }}</dd></div>
          <div>
            <dt>物料供应方式</dt>
            <dd>
              <UBadge :color="order.materialSupplyMode === 'CUSTOMER_SUPPLIED' ? 'warning' : 'neutral'" variant="soft">
                {{ materialSupplyModeLabel(order.materialSupplyMode) }}
              </UBadge>
            </dd>
          </div>
          <div v-if="canViewPrice"><dt>未税总额</dt><dd class="num">{{ formatAmount(order.totalAmount) }}</dd></div>
          <div v-if="canViewPrice"><dt>已发货金额</dt><dd class="num">{{ formatAmount(order.actualShippedAmount || 0) }}</dd></div>
          <div v-if="canViewPrice"><dt>已开票</dt><dd class="num">{{ formatAmount(order.invoicedAmount || 0) }}</dd></div>
          <div v-if="canViewPrice"><dt>已收款</dt><dd class="num">{{ formatAmount(order.paidAmount || 0) }}</dd></div>
        </dl>

        <!-- 4 tab 业务中心 (V3 P0-11 — 金矿截图 49m17s) -->
        <UTabs v-model="activeTab" :items="tabItems" variant="link" class="business-tabs" :ui="{ content: 'pt-3' }" data-testid="sales-order-tabs">

          <!-- ─── Tab 1: 订单详情 ─── -->
          <template #detail>
            <dl class="desc-grid cols-3">
              <div><dt>交货日期</dt><dd>{{ order.requiredDeliveryDate || '—' }}</dd></div>
              <div><dt>下单箱数</dt><dd>{{ order.boxQuantity || '—' }}</dd></div>
              <div v-if="canViewPrice"><dt>折扣</dt><dd class="num">{{ order.discountAmount ? formatAmount(order.discountAmount) : '—' }}</dd></div>
              <div><dt>含运费</dt><dd>{{ order.shippingIncluded ? '是' : '否' }}</dd></div>
              <div v-if="canViewPrice"><dt>运费</dt><dd class="num">{{ order.shippingFee ? formatAmount(order.shippingFee) : '—' }}</dd></div>
              <div v-if="canViewPrice"><dt>预估利润</dt><dd class="num">{{ order.estimatedProfit ? formatAmount(order.estimatedProfit) : '—' }}</dd></div>
              <div class="wide"><dt>交货地址</dt><dd>{{ order.deliveryAddress || '—' }}</dd></div>
              <div class="wide"><dt>备注</dt><dd>{{ order.remark || '—' }}</dd></div>
            </dl>

            <section v-if="isPrestockedCustomerStock" class="customer-supplied-progress" aria-label="客户已有成品库存预留">
              <div class="section-heading">
                <div>
                  <h3>客户已有成品库存</h3>
                  <p>以下批次已精确预留给本销售订单；销售创建发货单后，由仓管核对实发数量并确认出库。</p>
                </div>
                <UButton
                  v-if="canAccessWarehouse"
                  color="warning"
                  variant="outline"
                  @click="router.push('/warehouse/shipments')"
                >前往仓库确认出库</UButton>
              </div>
              <UAlert
                v-if="reservationsFailed"
                color="error"
                variant="subtle"
                icon="i-lucide-circle-x"
                title="预留批次加载失败，请刷新后再创建发货单。"
              />
              <UTable v-else :data="activeReservations" :columns="reservationColumns" :loading="reservationsLoading" class="cs-table">
                <template #empty>当前没有生效中的客户成品预留</template>
                <template #reserved-cell="{ row }"><span class="num">{{ row.original.reservedQuantity }} {{ displayUnit(row.original.unit) }}</span></template>
                <template #state-cell><UBadge color="success" variant="soft">已预留</UBadge></template>
              </UTable>
            </section>

            <section v-if="isOrderDrivenCustomerSuppliedOrder" class="customer-supplied-progress" aria-label="客户自带物料收货进度">
              <div class="section-heading">
                <div>
                  <h3>客户自带物料需求</h3>
                  <p>销售侧仅查看预计来料和收货进度；实收、批次及库存只能由仓储任务处理。</p>
                </div>
                <UButton color="warning" variant="outline" @click="goWarehouseReceiving">前往仓储收货任务</UButton>
              </div>
              <UAlert
                v-if="customerSuppliedRequirements.length === 0"
                color="warning"
                variant="subtle"
                icon="i-lucide-triangle-alert"
                title="该代加工订单尚未返回结构化客供料需求，请在订单草稿中补齐后再提交。"
              />
              <UTable v-else :data="customerSuppliedRequirements" :columns="suppliedColumns" class="cs-table">
                <template #material-cell="{ row }"><UBadge color="neutral" variant="outline" class="mr-1">{{ customerMaterialCategory(row.original) }}</UBadge>{{ row.original.materialName || row.original.materialTypeId }}</template>
                <template #arrival-cell="{ row }">{{ formatBusinessDateTime(row.original.expectedArrivalAt) }}</template>
                <template #warehouse-cell="{ row }">{{ targetWarehouseLabel(row.original) }}</template>
                <template #progress-cell="{ row }">{{ customerMaterialProgress(row.original) }}</template>
                <template #state-cell="{ row }"><UBadge color="warning" variant="soft">{{ customerMaterialReceivingStatusLabel(row.original.status) }}</UBadge></template>
              </UTable>
            </section>

            <h3 class="sub-title">{{ label('product') }}明细</h3>
            <!-- Task 4: DRAFT 草稿可编辑产品行 -->
            <div v-if="isDraft && canWrite" class="mb-2">
              <UButton size="sm" @click="openEditItems">编辑产品行</UButton>
            </div>
            <UAlert
              v-else-if="!isDraft && !(order.items && order.items.length)"
              color="info"
              variant="subtle"
              title="该订单非草稿状态且无产品行，请联系销售在草稿阶段补充。"
            />
            <!-- T3-13 fix (issue #524): widen 规格 + 产品 columns + scroll-x fallback to prevent 成品详情规格列盖住. -->
            <UTable :data="order.items || []" :columns="itemColumns" class="cs-table" data-testid="sales-order-items">
              <template #empty>暂无产品明细</template>
              <template #priceSource-cell="{ row }">
                <span data-testid="price-source">{{ priceSourceLabel(row.original.priceSource) }}</span>
                <span v-if="row.original.priceRef" class="price-source-ref"> · {{ row.original.priceRef }}</span>
              </template>
              <template #taxRate-cell="{ row }">
                <UBadge v-if="row.original.taxRate != null" variant="soft" :color="Number(row.original.taxRate) === 9 ? 'success' : (Number(row.original.taxRate) === 13 ? 'warning' : 'neutral')">
                  {{ row.original.taxRate }}%
                </UBadge>
                <span v-else>—</span>
              </template>
              <!-- E-4 (六扇门需求矩阵 / PR #737/#749): 同时显示未税净价 + 含税价双值.
                   后端 SalesOrderItem.getLineAmount()       = 未税小计 (折后未税)
                   后端 SalesOrderItem.getLineAmountWithTax() = 含税小计 = 未税 × (1 + taxRate/100)
                   两者均为 @PriceSensitive — 无价格权限时后端返回 null → 显示 "—". -->
              <template #lineAmount-cell="{ row }">
                <span class="num" :title="'未含税金额 (折后)'">
                  {{ row.original.lineAmount != null ? formatAmount(row.original.lineAmount) : (row.original.unitPrice != null ? formatAmount(row.original.quantity * row.original.unitPrice) : '—') }}
                </span>
              </template>
              <template #lineTaxIncluded-cell="{ row }">
                <template v-if="row.original.unitPrice != null">
                  <span class="num strong">{{ formatAmount(displayLineMoney(row.original).taxIncluded) }}</span>
                  <div v-if="row.original.taxRate && Number(row.original.taxRate) > 0" class="sub-note">
                    税额 {{ formatAmount(displayLineMoney(row.original).tax) }}
                  </div>
                </template>
                <span v-else>—</span>
              </template>
            </UTable>

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
              <h3 class="sub-title">税率分组汇总</h3>
              <div class="tax-group-wrap"><UTable :data="taxGroupData" :columns="taxGroupColumns" class="cs-table">
                <template #rate-cell="{ row }">
                  <UBadge variant="soft" :color="row.original.taxRate === 9 ? 'success' : (row.original.taxRate === 13 ? 'warning' : 'neutral')">
                    {{ row.original.taxRate }}%
                  </UBadge>
                </template>
                <template #groupSum-cell="{ row }"><span class="num">{{ formatAmount(row.original.amount) }}</span></template>
              </UTable></div>
            </div>

            <!-- ─── 审批进度时间线 (V3 P0-11 补强 — 客户金矿截图 49m17s 底部 timeline) ─── -->
            <div class="approval-timeline-section">
              <h3>审批进度</h3>
              <dl v-if="approvalProgress?.hasInstance" class="desc-grid cols-2 oa-progress-summary">
                <div><dt>OA 状态</dt><dd>{{ approvalProgressStatusLabel(approvalProgress.status) }}</dd></div>
                <div><dt>当前节点</dt><dd>{{ approvalProgress.currentNodeNames.join('、') || '流程已结束' }}</dd></div>
                <div><dt>待处理角色</dt><dd>{{ approvalProgress.approverRoles.map(role => enumLabel(role)).join('、') || '—' }}</dd></div>
                <div><dt>待处理人员</dt><dd>{{ approvalProgress.assignees.join('、') || '—' }}</dd></div>
                <div><dt>提交时间</dt><dd>{{ formatBusinessDateTime(approvalProgress.initiatedAt) }}</dd></div>
                <div><dt>停留时长</dt><dd>{{ approvalProgress.stayMinutes == null ? '—' : `${approvalProgress.stayMinutes} 分钟` }}</dd></div>
                <div class="wide"><dt>操作</dt><dd><UButton variant="link" size="xs" @click="goToApprovalProgress">前往个人 OA 查看</UButton></dd></div>
              </dl>
              <UAlert
                v-else
                color="info"
                variant="subtle"
                icon="i-lucide-info"
                :title="approvalProgress?.message || '该订单尚未发起 OA 审批'"
                class="oa-progress-summary"
              />
              <UEmpty v-if="approvalTimeline.length === 0" icon="i-lucide-history" description="暂无审批记录" />
              <ol v-else class="cs-timeline">
                <li v-for="(node, idx) in approvalTimeline" :key="idx" class="cs-timeline-item">
                  <span class="cs-timeline-dot" :class="`is-${node.type}`" />
                  <div class="timeline-time">{{ formatBusinessDateTime(node.time) }}</div>
                  <div class="timeline-card">
                    <div class="timeline-title">{{ node.title }}</div>
                    <div class="timeline-meta">
                      <UIcon name="i-lucide-user" />
                      <span>{{ node.user }}</span>
                    </div>
                    <div v-if="node.notes" class="timeline-notes">
                      <div v-for="line in approvalReasonLines(node.notes)" :key="line">{{ line }}</div>
                    </div>
                  </div>
                </li>
              </ol>
            </div>
          </template>

          <!-- ─── Tab 2: 自动生成的开票记录 ─── -->
          <template #invoice>
            <div class="tab-toolbar">
              <span v-if="canViewPrice" class="tab-hint">订单未税总额 {{ formatAmount(order.totalAmount) }} / 已开票（含税）{{ formatAmount(order.invoicedAmount || 0) }}</span>
              <span class="tab-hint">全部发货完成后，系统自动按税率分组生成待审核开票记录</span>
            </div>

            <UTable :data="invoices" :columns="invoiceColumns" class="cs-table">
              <template #empty>暂无开票记录</template>
              <template #status-cell="{ row }">
                <UBadge :color="mapColor(invoiceStatusMap, row.original.status, 'neutral')" variant="soft">{{ mapText(invoiceStatusMap, row.original.status) }}</UBadge>
              </template>
              <template #amount-cell="{ row }"><span class="num">{{ formatAmount(row.original.amount) }}</span></template>
              <template #taxAmount-cell="{ row }"><span class="num">{{ formatAmount(row.original.taxAmount) }}</span></template>
              <template #totalAmount-cell="{ row }"><span class="num">{{ formatAmount(row.original.totalAmount) }}</span></template>
              <template #taxBreakdown-cell="{ row }">
                <div v-if="row.original.taxBreakdown && row.original.taxBreakdown.length" class="tax-breakdown">
                  <UBadge
                    v-for="(group, idx) in row.original.taxBreakdown"
                    :key="idx"
                    variant="soft"
                    :color="Number(group.taxRate) === 9 ? 'success' : (Number(group.taxRate) === 13 ? 'warning' : 'neutral')"
                  >
                    {{ group.taxRate }}% × {{ group.lineCount }} 行 = {{ formatAmount(group.taxableAmount) }} (税 {{ formatAmount(group.taxAmount) }})
                  </UBadge>
                </div>
                <span v-else>—</span>
              </template>
              <template #actions-cell="{ row }">
                <div class="row-actions">
                  <UButton v-if="row.original.status === 'REQUESTED' && canWrite" color="success" variant="link" size="xs" @click="handleApproveInvoice(row.original.id)">通过</UButton>
                  <UButton v-if="row.original.status === 'REQUESTED' && canWrite" color="error" variant="link" size="xs" @click="handleRejectInvoice(row.original.id)">驳回</UButton>
                  <UButton v-if="row.original.status === 'APPROVED' && canWrite" variant="link" size="xs" @click="openIssueDialog(row.original.id)">上传发票</UButton>
                  <InvoicePdfDownload v-if="row.original.invoicePdfUrl" :factory-id="factoryId" :invoice-id="row.original.id" :file-name="row.original.invoiceFileName" />
                </div>
              </template>
            </UTable>
          </template>

          <!-- ─── Tab 3: 销售出库 ─── -->
          <template #delivery>
            <div class="tab-toolbar">
              <span :title="deliveryAction.tooltip">
                <UButton
                  v-if="['CONFIRMED','FINANCE_APPROVED','PROCESSING','PARTIAL_DELIVERED'].includes(order.status) && canWrite"
                  icon="i-lucide-plus" :disabled="deliveryAction.disabled" @click="openDeliveryDialog"
                >{{ deliveryAction.label }}</UButton>
              </span>
            </div>

            <UTable :data="deliveries" :columns="deliveryColumns" :meta="deliveryTableMeta" class="cs-table">
              <template #empty>暂无发货记录</template>
              <template #role-cell="{ row }">
                <UBadge :color="row.original.recordRole === 'MASTER' ? 'primary' : 'neutral'" variant="soft">
                  {{ row.original.recordRole === 'MASTER' ? '母发货单' : (row.original.recordRole === 'SHIPMENT' ? '子发运' : '历史单') }}
                </UBadge>
              </template>
              <template #status-cell="{ row }">
                <UBadge :color="mapColor(delStatusMap, row.original.status, 'neutral')" variant="soft">{{ mapText(delStatusMap, row.original.status) }}</UBadge>
              </template>
              <template #amount-cell="{ row }"><span class="num">{{ formatAmount(row.original.totalAmount) }}</span></template>
              <template #allocation-cell="{ row }">
                <span v-if="row.original.recordRole === 'MASTER'">由子发运单分配</span>
                <!--
                  整单物料: 后端在「确认发货」时按 FIFO 自动扣减, 没有批次可分。
                  原来这里显示「已分配 0/0.7 kg」, 仓管会以为还欠一步。
                -->
                <UBadge v-else-if="allocationSummary[String(row.original.id)]?.materialOnly" color="success" variant="outline">
                  物料 · 自动扣减
                </UBadge>
                <span v-else-if="allocationSummary[String(row.original.id)]">
                  已分配 {{ allocationSummary[String(row.original.id)].allocated }}/{{ allocationSummary[String(row.original.id)].planned }}
                  {{ allocationSummary[String(row.original.id)].unit }}
                  <div v-for="batch in allocationSummary[String(row.original.id)].batches" :key="batch" class="muted-audit">{{ batch }}</div>
                  <span v-if="allocationSummary[String(row.original.id)].materialLines" class="muted-audit">
                    (另 {{ allocationSummary[String(row.original.id)].materialLines }} 行物料自动扣减)
                  </span>
                </span>
                <span v-else>0/0</span>
              </template>
              <template #audit-cell="{ row }">
                <div>{{ deliveryQuantitySummary(row.original) }}</div>
                <div class="muted-audit">{{ deliveryAuditSummary(row.original) }}</div>
              </template>
              <template #actions-cell="{ row }">
                <div class="row-actions">
                  <UButton
                    v-if="row.original.recordRole === 'MASTER' && !['SHIPPED','DELIVERED','CANCELLED'].includes(row.original.status) && canWrite"
                    variant="link" size="xs" @click="openShipmentDialog(row.original)"
                  >新建分批发运</UButton>
                  <!-- 普通订单可由销售预先分配批次；PRESTOCKED 在仓库确认时按订单预留自动匹配。 -->
                  <UButton v-if="!isPrestockedCustomerStock && row.original.recordRole !== 'MASTER' && ['DRAFT','PENDING_WAREHOUSE_CONFIRM','PICKED'].includes(row.original.status) && canWrite" variant="link" size="xs" :disabled="submitting || batchAllocLoading" :loading="batchAllocLoading" @click="openBatchAllocDialog(row.original.id, row.original.deliveryNumber)">
                    {{ allocationSummary[String(row.original.id)]?.complete ? '查看/修改批次' : (allocationSummary[String(row.original.id)]?.allocated ? '继续分配/修改批次' : '分配批次') }}
                  </UButton>
                  <span
                    v-if="row.original.recordRole !== 'MASTER' && ['DRAFT','PENDING_WAREHOUSE_CONFIRM','PICKED'].includes(row.original.status)"
                    title="库存扣减只能由仓管在 仓储管理 → 出货管理 核对后执行"
                  >
                    <UButton
                      color="warning"
                      variant="link"
                      size="xs"
                      :disabled="!canAccessWarehouse"
                      @click="router.push('/warehouse/shipments')"
                    >等待仓库确认</UButton>
                  </span>
                  <UButton v-if="row.original.status === 'SHIPPED' && canWrite" color="success" variant="link" size="xs" :disabled="submitting" @click="openSignatureDialog(row.original)">填写凭证并签收</UButton>
                  <UButton v-if="['DELIVERED', 'SIGNED'].includes(String(row.original.status)) && row.original.recordRole !== 'MASTER'"
                    variant="link" size="xs" @click="signatureReceiptTarget = row.original">查看签收凭证</UButton>
                </div>
              </template>
            </UTable>
          </template>

          <!-- ─── Tab 4: 收款记录 ─── -->
          <template #payment>
            <div class="tab-toolbar">
              <UButton v-if="canWrite && canWriteFinance" icon="i-lucide-plus" @click="openPaymentDialog">
                登记收款
              </UButton>
              <!-- T-INV (F006 客户反馈): 一键收款 shortcut — only shown when remaining > 0.
                   Auto-fills remaining amount + BANK_TRANSFER + today; bypasses dialog form. -->
              <UButton v-if="canWrite && canWriteFinance && computedRemainingAmount() > 0" color="success"
                       :loading="submitting" @click="handleQuickPayFull">一键收款 (全额)</UButton>
              <span v-if="canViewPrice" class="tab-hint">应收总额（含税）{{ formatAmount(salesOrderReceivableAmount(order)) }} / 已收 {{ formatAmount(order.paidAmount || 0) }} / 待收 {{ formatAmount(computedRemainingAmount()) }}</span>
            </div>

            <!-- 🔴「查不到」不能长成「没有」: 加载失败时明确说出来 + 给下一步动作, 不再静默空表。 -->
            <UAlert
              v-if="paymentsLoadError"
              color="error"
              variant="subtle"
              icon="i-lucide-circle-x"
              class="mb-3"
              title="收款记录未能加载 —— 下方为空不代表这单没有收款"
              :description="`${paymentsLoadError}（若本账号只有只读权限，请联系管理员开通财务收款查看权限后重试）`"
            />
            <UTable :data="payments" :columns="paymentColumns" class="cs-table">
              <template #empty>{{ paymentsLoadError ? '未能加载收款记录（见上方提示）' : '暂无收款记录' }}</template>
              <template #amount-cell="{ row }"><span class="num">{{ formatAmount(row.original.amount) }}</span></template>
              <template #method-cell="{ row }">
                {{ ({ BANK_TRANSFER: '银行转账', CASH: '现金', CHECK: '支票', WECHAT: '微信', ALIPAY: '支付宝', OTHER: '其他' } as Record<string, string>)[row.original.paymentMethod] || '其他' }}
              </template>
              <template #status-cell="{ row }">
                <UBadge :color="mapColor(paymentStatusMap, row.original.status, 'neutral')" variant="soft">{{ mapText(paymentStatusMap, row.original.status) }}</UBadge>
              </template>
            </UTable>
          </template>

          <!-- ─── Tab 5: 关联采购订单 ─── -->
          <template #purchase>
            <div class="tab-hint pad-y">
              客户原话: 主原料 (贵重料) 必须按销售订单做定点追踪, 防止多采浪费
            </div>

            <UTable :data="purchaseOrders" :columns="purchaseColumns" class="cs-table">
              <template #empty>暂无关联采购订单</template>
              <template #status-cell="{ row }"><UBadge color="neutral" variant="soft">{{ enumLabel(row.original.status) }}</UBadge></template>
              <template #amount-cell="{ row }"><span class="num">{{ formatAmount(row.original.totalAmount) }}</span></template>
              <template #actions-cell="{ row }">
                <UButton variant="link" size="xs" @click="router.push(`/procurement/orders/${row.original.id}`)">查看</UButton>
              </template>
            </UTable>
          </template>
        </UTabs>
      </template>
      </div>
    </section>

    <UModal v-model:open="deliveryDialogVisible" :title="`创建${label('delivery')}单`" description="销售创建发货任务单，库存由仓库确认后扣减" :ui="{ content: 'max-w-[1000px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form-stack">
          <UAlert
            v-if="deliveryAddressMissing"
            color="warning"
            variant="subtle"
            icon="i-lucide-triangle-alert"
            title="客户/订单未维护收货地址"
            description="请为本次发货单手工补充发货地址，空地址禁止创建。"
          />
          <UFormField label="发货地址" required>
            <UInput v-model="deliveryForm.deliveryAddress" placeholder="优先使用订单交货地址；订单为空时回退客户主档收货地址" class="w-full" />
          </UFormField>
          <UFormField label="配送方式" required>
            <URadioGroup v-model="deliveryForm.deliveryMethod" orientation="horizontal" :items="DELIVERY_METHOD_ITEMS" />
          </UFormField>
          <UFormField v-if="deliveryForm.deliveryMethod === 'LOGISTICS'" label="物流公司" required><UInput v-model="deliveryForm.logisticsCompany" placeholder="如：顺丰冷链" class="w-full" /></UFormField>
          <UFormField v-if="deliveryForm.deliveryMethod === 'LOGISTICS'" label="运单号" required><UInput v-model="deliveryForm.trackingNumber" placeholder="填写物流运单号" class="w-full" /></UFormField>
          <UFormField label="发货模式" required>
            <URadioGroup v-model="deliveryForm.deliveryMode" :items="DELIVERY_MODE_ITEMS" />
          </UFormField>
          <UTable :data="deliveryForm.items" :columns="deliveryItemColumns" class="cs-table">
            <template #qty-cell="{ row }">
              <UInputNumber v-model="row.original.deliveredQuantity" :min="0" :max="row.original.maxDeliverableQuantity" :format-options="QTY3" :increment="false" :decrement="false" size="sm" class="w-32" :step-snapping="false" />
              <div class="delivery-capacity-hint">剩余可安排 {{ row.original.maxDeliverableQuantity }}{{ displayUnit(row.original.unit) }}</div>
            </template>
            <template #unit-cell="{ row }">{{ displayUnit(row.original.unit) }}</template>
            <template #unitPrice-cell="{ row }"><span class="num">{{ formatAmount(row.original.unitPrice) }}</span></template>
            <template #subtotal-cell="{ row }"><span class="num">{{ formatAmount(Number(row.original.deliveredQuantity || 0) * Number(row.original.unitPrice || 0)) }}</span></template>
            <template #tax-cell="{ row }"><span class="num">{{ formatAmount(deliveryMoney([row.original]).tax) }}</span></template>
          </UTable>
          <div v-if="canViewPrice" class="delivery-amount-summary">
            <span>本次未税合计 <strong>{{ formatAmount(deliveryTotals.untaxed) }}</strong></span>
            <span>税额 <strong>{{ formatAmount(deliveryTotals.tax) }}</strong></span>
            <span>本次含税合计 <strong>{{ formatAmount(deliveryTotals.taxIncluded) }}</strong></span>
          </div>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="deliveryDialogVisible = false">取消</UButton>
        <UButton data-testid="sales-delivery-create" :loading="submitting" :disabled="deliveryAddressMissing" @click="handleCreateDelivery">创建发货单</UButton>
      </template>
    </UModal>

    <UModal v-model:open="shipmentDialogVisible" title="新建分批发运" description="只创建子发运安排，不扣库存" :ui="{ content: 'max-w-[780px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form-stack">
          <UAlert color="info" variant="subtle" icon="i-lucide-info" title="本步骤只创建子发运安排，不扣库存；子单分配批次并由仓库确认后才实际发货。" />
          <UFormField label="计划发运日期" required>
            <CsDateField v-model="shipmentForm.plannedShipmentDate" />
          </UFormField>
          <UFormField label="配送方式" required>
            <URadioGroup v-model="shipmentForm.deliveryMethod" orientation="horizontal" :items="DELIVERY_METHOD_ITEMS" />
          </UFormField>
          <div class="form-row">
            <UFormField label="物流公司" :required="shipmentForm.deliveryMethod === 'LOGISTICS'"><UInput v-model="shipmentForm.logisticsCompany" :disabled="shipmentForm.deliveryMethod !== 'LOGISTICS'" class="w-full" /></UFormField>
            <UFormField label="物流/运单号" :required="shipmentForm.deliveryMethod === 'LOGISTICS'"><UInput v-model="shipmentForm.trackingNumber" :disabled="shipmentForm.deliveryMethod !== 'LOGISTICS'" class="w-full" /></UFormField>
          </div>
          <UFormField label="发货地址" required><UInput v-model="shipmentForm.deliveryAddress" class="w-full" /></UFormField>
          <UFormField label="备注"><UTextarea v-model="shipmentForm.remark" :rows="2" class="w-full" /></UFormField>
          <UTable :data="shipmentForm.items" :columns="shipmentItemColumns" class="cs-table">
            <template #qty-cell="{ row }">
              <UInputNumber v-model="row.original.quantity" :min="0" :max="row.original.remaining" :format-options="QTY3" :increment="false" :decrement="false" class="w-32" :step-snapping="false" />
            </template>
            <template #unit-cell="{ row }">{{ displayUnit(row.original.unit) }}</template>
            <template #remaining-cell="{ row }">{{ row.original.remaining }}{{ displayUnit(row.original.unit) }}</template>
          </UTable>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="shipmentDialogVisible = false">取消</UButton>
        <UButton data-testid="sales-shipment-create" :loading="submitting" @click="handleCreateShipment">创建子发运单</UButton>
      </template>
    </UModal>

    <!-- ─── T-SHORT-SHIP (owner 2026-09-04 拍板): 短交结案对话框 —— 镜像采购侧
         PendingPurchaseReceivingPanel.vue 的「少收关闭」, 多一道勾选确认。 ─── -->
    <UModal
      v-model:open="shortCloseDialogVisible"
      :title="`短交结案 — ${order?.orderNumber || ''}`"
      description="结案后剩余数量不再生成发货安排"
      :dismissible="false"
      :ui="{ content: 'max-w-[700px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div v-if="order" class="form-stack">
          <UAlert
            color="warning"
            variant="subtle"
            icon="i-lucide-triangle-alert"
            title="结案后，剩余数量不再生成发货安排；已发货部分的库存/开票不受影响。"
            description="本操作用于客户确认接受少发、或原料确实无法补齐的短交场景。若仍会补发，请取消并使用「补发剩余」。"
          />
          <dl class="desc-grid cols-2">
            <div><dt>订单编号</dt><dd>{{ order.orderNumber }}</dd></div>
            <div><dt>下单数量</dt><dd>{{ shortShipment.ordered }}{{ displayUnit(shortShipment.unit) }}</dd></div>
            <div><dt>已实发数量</dt><dd>{{ shortShipment.shipped }}{{ displayUnit(shortShipment.unit) }}</dd></div>
            <div><dt>结案后不再发货的数量</dt><dd><strong class="danger-text">{{ shortShipment.outstanding }}{{ displayUnit(shortShipment.unit) }}</strong></dd></div>
          </dl>
          <UFormField label="结案原因" required>
            <USelectMenu v-model="shortCloseForm.reasonCode" :items="SHORT_CLOSE_REASON_OPTIONS" value-key="value" :search-input="false" placeholder="请选择标准原因" class="w-full" />
          </UFormField>
          <UFormField label="补充说明" :required="shortCloseForm.reasonCode === 'OTHER'">
            <UTextarea
              v-model="shortCloseForm.notes"
              :rows="3"
              :maxlength="500"
              placeholder="可填写客户确认记录、原料短缺依据等"
              class="w-full"
            />
          </UFormField>
          <UCheckbox
            v-model="shortCloseAcknowledged"
            :label="`我确认剩余 ${shortShipment.outstanding}${displayUnit(shortShipment.unit)} 不再发货，结案后需重新下单才能补发`"
          />
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" :disabled="shortCloseSubmitting" @click="shortCloseDialogVisible = false">取消</UButton>
        <UButton
          color="error"
          :loading="shortCloseSubmitting"
          :disabled="!shortCloseAcknowledged"
          @click="submitShortClose"
        >确认短交结案</UButton>
      </template>
    </UModal>

    <!-- ─── T-RTA (issues #531 + #571 business logic): 申请退货 对话框 ─── -->
    <UModal v-model:open="returnDialogVisible" title="申请退货" description="提交退货申请，生成草稿退货单" :ui="{ content: 'max-w-[800px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form-stack">
          <UAlert color="info" variant="subtle" icon="i-lucide-info"
                  title="退货流程说明"
                  description="提交后退货单为 DRAFT 草稿. 客户服务 → 销售退货 菜单里点 提交审批, 财务/管理员审批后才会触发库存或退款动作. 退货数量不能超过已发货数量." />
          <!-- T-RTA business logic (#571): 有食物 / 无食物 分支决定后端 AR冲减 + 库存 时机. -->
          <UFormField label="退货类型" required>
            <URadioGroup v-model="returnWithGoodsChoice" :items="RETURN_TYPE_ITEMS" />
            <div class="form-hint">
              <!-- 🔴 2026-08-19: 原文案「总仓入库」与实际不符 (落的是成品仓的 DEFECTIVE 批次),
                   且没讲「不计入可售库存」——用户因此报「说会退回成品仓但是没有」。 -->
              <span v-if="returnForm.withGoods">实物退货: 仓库收到货 → 入成品仓【不良品区】→ 标记完成时触发 AR 冲减. ⚠️ 不良品不计入可售库存, 需质检合格后方可再次销售.</span>
              <span v-else>无实物: 审批通过即触发 AR/AP 冲减, 不产生库存动作.</span>
            </div>
          </UFormField>
          <div class="form-row">
            <UFormField label="退货日期" required>
              <CsDateField v-model="returnForm.returnDate" />
            </UFormField>
            <UFormField label="退货原因" required>
              <USelectMenu
                v-model="returnForm.reasonCategory"
                :items="RETURN_REASON_ITEMS"
                :search-input="false"
                placeholder="请选择退货原因"
                class="w-full"
                @update:model-value="onReturnReasonCategoryChange"
              />
            </UFormField>
          </div>
          <!-- fool-proof-design Rule 3: 选"其他"才展开自由文本 -->
          <UFormField v-if="returnForm.reasonCategory === '其他'" label="其他原因说明" required>
            <UTextarea v-model="returnForm.reason" :rows="2" placeholder="请具体说明退货原因" :maxlength="500" class="w-full" />
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="returnForm.remark" :rows="2" :maxlength="1000" class="w-full" />
          </UFormField>
          <h4 class="sub-title">退货明细</h4>
          <UTable :data="returnForm.items" :columns="returnItemColumns" class="cs-table">
            <template #selected-cell="{ row }"><UCheckbox v-model="row.original.selected" aria-label="选择该行退货" /></template>
            <template #qty-cell="{ row }">
              <UInputNumber v-model="row.original.quantity" :min="0" :max="row.original.maxQuantity || undefined" :format-options="QTY3" :increment="false" :decrement="false" size="sm" class="w-32" :disabled="!row.original.selected" :step-snapping="false" />
            </template>
            <template #reason-cell="{ row }">
              <UInput v-model="row.original.reason" size="sm" placeholder="(可选)" :maxlength="500" class="w-full" :disabled="!row.original.selected" />
            </template>
          </UTable>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="returnDialogVisible = false">取消</UButton>
        <UButton :loading="returnSubmitting" @click="handleCreateReturn">提交退货申请</UButton>
      </template>
    </UModal>

    <!-- ─── 客户签收对话框: 内含 AttachmentDropZone (Element 专有上传组件), 整弹层保留 Element ─── -->
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

    <!-- ─── 签收凭证查看: DeliverySignatureReceipt 是 Element 组件, 整弹层保留 Element ─── -->
    <el-dialog :model-value="Boolean(signatureReceiptTarget)" title="查看签收凭证" width="620px"
      destroy-on-close @update:model-value="(value: boolean) => { if (!value) signatureReceiptTarget = null; }">
      <DeliverySignatureReceipt v-if="signatureReceiptTarget" :key="String(signatureReceiptTarget.id)"
        :factory-id="factoryId" :delivery-id="String(signatureReceiptTarget.id)"
        :photo-urls="existingSignaturePhotoUrls(signatureReceiptTarget.signaturePhotoUrls)"
        :signed-by-name="String(signatureReceiptTarget.signedByName || '')"
        :signed-at="String(signatureReceiptTarget.signedAt || '')"
        :remark="String(signatureReceiptTarget.signatureRemark || '')" />
      <template #footer><el-button @click="signatureReceiptTarget = null">关闭</el-button></template>
    </el-dialog>

    <!-- ─── 批次分配对话框 (P0-13 强制批次追溯): 提交时会调 ElMessageBox 确认规格换算, Nuxt UI 模态层下点不了, 整弹层保留 Element ─── -->
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
        <!-- 旧分配不满足库存所有权 (ineligibleNotice 只在成品行上设置): 说明原因, 下面展示重新推荐的批次 -->
        <el-alert v-if="item.ineligibleNotice" type="warning" :closable="false" show-icon
          style="margin-bottom: 8px;" title="原分配批次不能发出" :description="item.ineligibleNotice" />
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
              <div v-if="row.packagingMismatch" style="font-size: 12px; color: #e6a23c;">
                规格不一致: 批次 {{ row.batchPackagingLabel }}, 本行 {{ row.linePackagingLabel }}
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
        <el-button data-testid="sales-batch-allocate" type="primary" :loading="submitting" @click="handleBatchAllocate">确认分配</el-button>
      </template>
    </el-dialog>

    <!-- ─── 上传发票 PDF 对话框 (V3 P0-3c — 4 步开票闭环最后一环) ─── -->
    <UModal v-model:open="issueDialogVisible" title="开具发票 — 上传 PDF 附件" description="上传财务开具的发票 PDF" :ui="{ content: 'max-w-[540px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form-stack">
          <UAlert
            color="warning"
            variant="subtle"
            icon="i-lucide-triangle-alert"
            title="客户原话: 销售从订单页直接下载发票"
            description="财务开具的发票 PDF 必须以附件形式上传到本申请, 否则无法完成开票闭环。"
          />
          <label class="drop-zone" @dragover.prevent @drop.prevent="onIssuePdfDropped">
            <UIcon name="i-lucide-upload" class="drop-icon" />
            <span v-if="issuePdfFile" class="drop-file" data-testid="issue-pdf-name">{{ issuePdfFile.name }}</span>
            <span v-else>将 PDF 文件拖到此处, 或<em>点击选择</em></span>
            <input type="file" accept=".pdf,application/pdf" class="sr-only" data-testid="issue-pdf-input" @change="onIssuePdfPicked">
          </label>
          <div class="form-hint">仅支持 .pdf 格式, 单文件 ≤ 10MB</div>
          <AiUploadHint kind="pdf" />
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="issueDialogVisible = false">取消</UButton>
        <UButton :loading="submitting" :disabled="!issuePdfFile" @click="handleIssueInvoice">确认开具</UButton>
      </template>
    </UModal>

    <!-- ─── 登记收款对话框 ─── -->
    <UModal v-model:open="paymentDialogVisible" title="登记收款" description="登记一笔回款" :ui="{ content: 'max-w-[540px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form-stack">
          <UFormField v-if="canViewPrice" label="收款金额" required>
            <!-- fool-proof-design Rule 1 (block-not-clamp fix): 不绑定 :max — 数字输入框
                 会在按键时静默 clamp modelValue 到 max (见 procurement/receives/list.vue
                 同款修复注释), 这里改用 paymentOverLimit computed 驱动红框 + 红字 + 禁用提交. -->
            <div class="amount-line">
              <UInputNumber
                v-model="paymentForm.amount"
                :min="0"
                :format-options="{ minimumFractionDigits: 2, maximumFractionDigits: 2 }"
                :increment="false"
                :decrement="false"
                :color="paymentOverLimit ? 'error' : 'primary'"
                :highlight="paymentOverLimit"
                class="w-52" :step-snapping="false" />
              <span class="form-hint">待收 {{ formatAmount(computedRemainingAmount()) }}</span>
            </div>
            <div v-if="paymentOverLimit" class="over-limit-hint over-limit-hint--danger">
              超出待收余额 {{ formatAmount(computedRemainingAmount()) }}, 请调低
            </div>
          </UFormField>
          <UFormField label="收款方式">
            <USelectMenu v-model="paymentForm.paymentMethod" :items="PAYMENT_METHOD_ITEMS" value-key="value" :search-input="false" class="w-52" />
          </UFormField>
          <UFormField label="收款日期">
            <CsDateField v-model="paymentForm.paymentDate" />
          </UFormField>
          <UFormField label="参考号/凭证">
            <UInput v-model="paymentForm.paymentReference" placeholder="银行流水号 / 凭证号" class="w-full" />
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="paymentForm.remark" :rows="2" placeholder="如: 定金 / 尾款" class="w-full" />
          </UFormField>
          <UFormField label="回款凭证" hint="PDF/JPG/PNG, ≤10MB (可选)">
            <div class="amount-line">
              <input type="file" accept=".pdf,image/jpeg,image/png,.jpg,.png" class="file-input" data-testid="receipt-input" :disabled="receiptUploading" @change="onReceiptPicked">
              <span v-if="receiptUploading" class="form-hint">上传中…</span>
              <span v-else-if="paymentForm.receiptUrl" class="ok-text">已上传</span>
            </div>
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="paymentDialogVisible = false">取消</UButton>
        <UButton
          :loading="submitting"
          :disabled="!paymentForm.amount || paymentForm.amount <= 0 || paymentOverLimit"
          @click="handleCreatePayment"
        >登记收款</UButton>
      </template>
    </UModal>

    <!-- ─── Task 4: 编辑产品行对话框: OrderItemsEditor 是 Element 行内编辑组件 (其它页也在用), 整弹层保留 Element ─── -->
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
.page-card { flex: 1; display: flex; flex-direction: column; }
.page-body { flex: 1; overflow-y: auto; }
.card-header { justify-content: space-between;
  .header-left { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
  .page-title { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
  .header-right { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
}
.loading-state { padding: var(--cs-space-4); color: var(--cs-muted); }
.desc-grid { display: grid; margin: 0; background: var(--cs-surface); border-top: 1px solid var(--cs-border); border-left: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); overflow: hidden;
  > div { display: flex; gap: var(--cs-space-3); padding: 10px 14px; background: var(--cs-surface); min-width: 0; box-shadow: inset -1px -1px 0 0 var(--cs-border); }
  dt { flex: 0 0 auto; min-width: 76px; color: var(--cs-muted); font-size: var(--cs-font-small); line-height: 22px; }
  dd { margin: 0; color: var(--cs-text); font-size: 13px; line-height: 22px; min-width: 0; overflow-wrap: anywhere; }
  .wide { grid-column: 1 / -1; }
}
.desc-grid.cols-2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.desc-grid.cols-3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.desc-grid.cols-4 { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.business-tabs { margin-top: 20px; }
.sub-title { margin: 20px 0 12px; font-size: 15px; font-weight: 600; color: var(--cs-text); }
.customer-supplied-progress { margin-top: 18px; }
.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
  h3 { margin: 0 0 4px; font-size: 15px; }
  p { margin: 0; color: var(--cs-muted); font-size: 13px; }
}
.tab-toolbar {
  display: flex; align-items: center; gap: 16px; padding: 4px 0 12px; flex-wrap: wrap;
}
.tab-hint { color: var(--cs-muted); font-size: 12px; }
.pad-y { padding: 12px 0; }
.mb-2 { margin-bottom: 8px; }
.mb-3 { margin-bottom: 12px; }
.mr-1 { margin-right: 4px; }
.tax-breakdown { display: flex; flex-wrap: wrap; gap: 4px; }
.row-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 2px; }
.sub-note { font-size: 11px; color: var(--cs-muted); margin-top: 2px; }
.strong { font-weight: 600; color: var(--cs-text); }
.danger-text { color: var(--cs-danger); }
.ok-text { font-size: 12px; color: var(--cs-success); }
.tax-group-wrap { max-width: 400px; }
// E-4 含税/未税汇总条
.tax-summary-bar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-top: 8px; padding: 10px 16px;
  background: var(--cs-surface-subtle); border-radius: var(--cs-radius-sm);
  font-size: 13px;
  .tax-summary-item { display: flex; flex-direction: column; align-items: flex-end; }
  .tax-summary-label { font-size: 11px; color: var(--cs-muted); margin-bottom: 2px; }
  .tax-summary-value { font-weight: 600; color: var(--cs-text); }
  .tax-value { color: var(--cs-warning); }
  .tax-total { color: var(--cs-primary); font-size: 15px; }
  .tax-summary-sep { color: var(--cs-muted); font-size: 16px; font-weight: 300; align-self: center; }
}

// V3 P0-11 补强 — 审批 timeline
.approval-timeline-section {
  margin-top: 28px;
  padding-top: 16px;
  border-top: 1px dashed var(--cs-border);

  h3 { margin: 0 0 16px; font-size: 15px; color: var(--cs-text); }
  .oa-progress-summary { margin-bottom: 16px; }
}
.cs-timeline { list-style: none; margin: 0; padding: 0 0 0 8px; border-left: 2px solid var(--cs-border); }
.cs-timeline-item { position: relative; padding: 0 0 16px 20px; }
.cs-timeline-dot { position: absolute; left: -14px; top: 4px; width: 10px; height: 10px; border-radius: 50%; background: var(--cs-muted); box-shadow: 0 0 0 3px var(--cs-surface);
  &.is-success { background: var(--cs-success); }
  &.is-warning { background: var(--cs-warning); }
  &.is-danger { background: var(--cs-danger); }
  &.is-primary { background: var(--cs-primary); }
}
.timeline-time { color: var(--cs-muted); font-size: 12px; margin-bottom: 6px; }
.timeline-card { padding: 8px 12px; border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface);
  .timeline-title { font-weight: 600; color: var(--cs-text); font-size: 14px; margin-bottom: 4px; }
  .timeline-meta { color: var(--cs-muted); font-size: 12px; display: flex; align-items: center; gap: 4px; }
  .timeline-notes { margin-top: 6px; padding: 6px 8px; background: var(--cs-surface-subtle); border-radius: var(--cs-radius-sm); color: var(--cs-muted); font-size: 12px; }
}

.form-stack { display: flex; flex-direction: column; gap: var(--cs-space-4); }
.form-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-4); }
.form-hint { margin-top: 4px; color: var(--cs-muted); font-size: 12px; }
.amount-line { display: flex; align-items: center; gap: 12px; }
.file-input { font-size: 13px; color: var(--cs-text); }
.drop-zone { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; padding: 28px 16px; border: 1px dashed var(--cs-border); border-radius: var(--cs-radius-sm); background: var(--cs-surface-subtle); color: var(--cs-muted); font-size: 13px; cursor: pointer;
  &:hover { border-color: var(--cs-primary); }
  em { color: var(--cs-primary); font-style: normal; }
  .drop-icon { width: 24px; height: 24px; }
  .drop-file { color: var(--cs-text); font-weight: 500; }
}
// fool-proof-design Rule 1: 收款/超限灰字提示 (正常) / 超限变红 (与 :color 前置拦截 +
// 提交禁用二重防御, 不用 :max 静默 clamp)
.over-limit-hint {
  font-size: 11px;
  color: var(--cs-muted);
  line-height: 1.4;
  margin-top: 2px;
}
.over-limit-hint--danger {
  color: var(--cs-danger);
}
.delivery-capacity-hint {
  margin-top: 4px;
  color: var(--cs-muted);
  font-size: 11px;
  white-space: nowrap;
}
.delivery-amount-summary {
  display: flex;
  justify-content: flex-end;
  gap: 24px;
  padding: 10px 12px;
  background: var(--cs-surface-subtle);
  border-radius: var(--cs-radius-sm);
  color: var(--cs-text);
}
/* 刚创建的发货单行 —— 建完自动切到本 tab 后, 让人一眼找到是哪一条 (客户 2026-07-31) */
:deep(.just-created-row > td) {
  background: var(--cs-primary-soft) !important;
}
.muted-audit { margin-top: 4px; color: var(--cs-muted); font-size: 12px; }
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
