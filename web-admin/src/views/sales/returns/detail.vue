<script setup lang="ts">
/**
 * 销售退货详情 — T-RTA (issue #531) frontend MVP.
 * Drives backend ReturnOrderController state transitions: submit / approve / reject / complete.
 */
import { ref, computed, watch, onActivated, onDeactivated, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Refresh } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { formatDateTime } from '@/utils/dateFormat';
import { VOUCHER_STATUS_LABEL } from '@/api/voucher';
import type { TableRow } from '@/types/api';
import { ApiError } from '@/types/api';
import { createPeriodRecoveryLocation, type PeriodRecoveryLocation } from '@/utils/accountingPeriodRecovery';
import { getFactoryRecall } from '@/api/factoryRecall';
import { getReturnFinancialFollowUp, resubmitReturnAdjustment, financialStatusText, type ReturnFinancialFollowUp } from '@/api/returnFinancialFollowUp';
import {
  approveReturnRefund,
  approveReturnRefundReversal,
  createReturnRefund,
  getReturnOrderStockLineage,
  getReturnRefund,
  getReturnRefundByRequest,
  markReturnRefundPaid,
  rejectReturnRefund,
  rejectReturnRefundReversal,
  requestReturnRefundReversal,
  submitReturnRefund,
  type ReturnOrderStockLineage,
  type ReturnRefund,
} from '@/api/inventory';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
const canApprove = computed(() => permissionStore.canWrite('sales'));
// 六扇门 Tier0 #16: 退货财审门. 只有财务角色 (finance:read_write) 可财务审批.
const canFinanceApprove = computed(() => permissionStore.canWrite('finance'));
const canReadFinance = computed(() => permissionStore.canAccess?.('finance') ?? canFinanceApprove.value);
const canRefundCreate = computed(() => canApprove.value || canFinanceApprove.value);
const canSeeRefundAmount = computed(() => canViewPrice.value && canRefundCreate.value);
const hasRefundableAmount = computed(() => canSeeRefundAmount.value
  && refund.value?.refundableAmount != null && Number(refund.value.refundableAmount) > 0);
const financial = ref<ReturnFinancialFollowUp | null>(null);
const financialError = ref('');
const stockLineage = ref<ReturnOrderStockLineage | null>(null);
const stockLineageError = ref('');
const refund = ref<ReturnRefund | null>(null);
const refundError = ref('');
const refundPeriodRecovery = ref<{ location: PeriodRecoveryLocation; context: ReturnType<typeof captureRefundContext> } | null>(null);
const refundSubmitting = ref(false);
const refundCreateVisible = ref(false);
const refundAmount = ref<number | null>(null);
const refundRemark = ref('');
const refundPayVisible = ref(false);
const refundPaymentMethod = ref<'CASH' | 'BANK_TRANSFER'>('CASH');
const refundPaymentReference = ref('');
const refundPaymentRemark = ref('');
const refundPromptOpen = ref(false);
const refundLedgerElement = ref<HTMLElement | null>(null);
const reversalDialogVisible = ref(false);
const reversalReason = ref('');
const reversalPaymentReference = ref('');

const returnOrderId = computed(() => route.params.id as string);
const loading = ref(false);
const submitting = ref(false);
const resubmitPromptOpen = ref(false);
const order = ref<TableRow | null>(null);
const recallContext = ref<{ batchId: number; batchNumber: string; unit: string } | null>(null);
const remarkDisplay = computed(() => {
  const remark = String(order.value?.remark || '');
  return /^RECALL_EVENT_ID=\d+;SHIPMENT_ID=[^;]+;$/.test(remark)
    ? '由批次召回登记，原出货关联已由系统保留。' : remark || '-';
});

const lineageByItemId = computed(() => {
  const result = new Map<string, ReturnOrderStockLineage['lines'][number]>();
  for (const line of stockLineage.value?.lines || []) result.set(String(line.returnOrderItemId), line);
  return result;
});

const refundEligibilityLabels: Record<string, string> = {
  ELIGIBLE: '可申请退款',
  PAID: '已登记退款',
  BLOCKED: '暂不可退款',
  PENDING_AR_APPROVAL: '等待应收核对',
  NOT_ELIGIBLE: '暂不可退款',
  ALREADY_REQUESTED: '已有退款申请',
  ALREADY_PAID: '已登记退款',
  REVERSED: '已撤销退款，可重新申请',
  INCONSISTENT: '证据不一致，需核对',
};

const refundRequestStatusLabels: Record<string, string> = {
  PENDING: '待提交',
  FINANCE_REVIEW: '财务审核中',
  APPROVED: '已批准，待登记付款',
  PAID: '已登记退款',
  CANCELLED: '已撤销退款',
  REJECTED: '已驳回',
};

const reversalStatusLabels: Record<string, string> = {
  PENDING: '撤销待审批',
  APPROVED: '退款已撤销',
  REJECTED: '撤销申请已驳回',
};

const voucherStatusLabels: Readonly<Record<string, { text: string }>> = VOUCHER_STATUS_LABEL;
function voucherStatusLabel(status: string): string {
  return voucherStatusLabels[status]?.text || '待核对';
}

function lineageForItem(item: Record<string, unknown>) {
  const id = item.id ?? item.returnOrderItemId ?? item.itemId;
  return id == null ? undefined : lineageByItemId.value.get(String(id));
}

function sourceStatusLabel(status: string | undefined): string {
  if (status === 'VERIFIED') return '已核实';
  if (status === 'LEGACY_SINGLE_LINE') return '旧单行，仅供核对';
  return '待核对';
}

function returnedStockLabel(line: ReturnOrderStockLineage['lines'][number] | undefined): string {
  if (!line) return '待核对';
  if (!line.returnedStock.length) return '暂无退回入库记录';
  return line.returnedStock.map(stock => {
    const quantity = stock.remainingQuantity == null ? '数量待核对' : `${stock.remainingQuantity} ${stock.unit || line.unit || ''}`.trim();
    return `${stock.batchNumber || stock.id} · 剩余 ${quantity}`;
  }).join('；');
}

function refundEligibilityLabel(status: string | undefined, amount?: number | null): string {
  if (status === 'ELIGIBLE' && amount === 0) return '当前没有可退金额';
  return refundEligibilityLabels[status || ''] || status || '待核对';
}

function refundRequestStatusLabel(status: string | undefined): string {
  return refundRequestStatusLabels[status || ''] || status || '待核对';
}

function refundLedgerTypeLabel(type: string | undefined): string {
  if (type === 'AR_REFUND') return '客户退款结清';
  if (type === 'AR_ADJUSTMENT') return '退款撤销调整';
  return type || '待核对';
}

function reversalStatusLabel(status: string | undefined): string {
  return reversalStatusLabels[status || ''] || status || '待核对';
}

function refundLedgerMatchesQuery(request: NonNullable<ReturnRefund['request']>, ledgerId: string): boolean {
  const ids = [request.ledger?.id, request.reversal?.ledger?.id]
    .filter((id): id is string => Boolean(id))
    .map(String);
  return ids.includes(ledgerId);
}

function actorLabel(id: number | null | undefined): string {
  return id == null ? '待核对' : `编号 #${id}`;
}

function moneyLabel(amount: number | null | undefined): string {
  if (!canViewPrice.value) return '无权限查看金额';
  return amount == null ? '金额待核对' : formatAmount(amount);
}

function reversalBalanceLabel(amount: number | null | undefined): string {
  return refund.value?.request?.reversal?.status === 'PENDING'
    ? '待审批（余额未变）' : moneyLabel(amount);
}

function refundableAmountLabel(amount: number | null | undefined): string {
  if (!canViewPrice.value) return '无权限查看金额';
  if (refund.value?.eligibilityStatus === 'PAID' || refund.value?.request?.status === 'PAID') {
    return amount === 0 ? '已结清' : moneyLabel(amount);
  }
  if (refund.value?.eligibilityStatus === 'ELIGIBLE' && amount === 0) {
    return '当前没有可退金额，请先核对原销售单收款和应收调整';
  }
  return moneyLabel(amount);
}

function queryValue(name: string): string | null {
  if (!(name in route.query)) return null;
  const value = route.query[name];
  return typeof value === 'string' ? value.trim() : '';
}

function statusType(s: string) {
  switch (s) {
    case 'DRAFT': return 'info';
    case 'SUBMITTED': return 'warning';
    case 'APPROVED': return 'primary';
    case 'FINANCE_APPROVED': return 'success';
    case 'REJECTED': return 'danger';
    case 'PROCESSING': return 'warning';
    case 'COMPLETED': return 'success';
    default: return 'info';
  }
}

function statusLabel(s: string) {
  const map: Record<string, string> = {
    DRAFT: '草稿', SUBMITTED: '已提交', APPROVED: '已审批',
    FINANCE_APPROVED: '财务已审',
    REJECTED: '已驳回', PROCESSING: '处理中', COMPLETED: '已完成',
  };
  return map[s] || s || '-';
}

let loadSequence = 0;
watch(() => [factoryId.value, returnOrderId.value, route.query.recallId,
  route.query.refundRequestId, route.query.ledgerId, authStore.user?.id], () => {
  if (!route.name || route.name === 'SalesReturnDetail' || route.name === 'FinanceReturnRefundDetail') void loadData();
}, { immediate: true });
onActivated(() => { if (order.value && !loading.value) void loadData(); });
onDeactivated(() => { ++loadSequence; });

async function loadData() {
  const sequence = ++loadSequence;
  order.value = null;
  recallContext.value = null;
  financial.value = null;
  financialError.value = '';
  stockLineage.value = null;
  stockLineageError.value = '';
  refund.value = null;
  refundError.value = '';
  refundPeriodRecovery.value = null;
  refundSubmitting.value = false;
  refundCreateVisible.value = false;
  refundPayVisible.value = false;
  refundPromptOpen.value = false;
  refundAmount.value = null;
  refundRemark.value = '';
  refundPaymentMethod.value = 'CASH';
  refundPaymentReference.value = '';
  refundPaymentRemark.value = '';
  reversalDialogVisible.value = false;
  reversalReason.value = '';
  reversalPaymentReference.value = '';
  loading.value = false;
  if (!factoryId.value || !returnOrderId.value) return;
  const sourceFactory = factoryId.value;
  const sourceOrder = returnOrderId.value;
  const sourceActor = authStore.user?.id;
  const recallId = route.query.recallId;
  const expectedRefundRequestId = queryValue('refundRequestId');
  const expectedLedgerId = queryValue('ledgerId');
  const isCurrent = () => sequence === loadSequence && sourceFactory === factoryId.value
    && sourceOrder === returnOrderId.value && recallId === route.query.recallId
    && expectedRefundRequestId === queryValue('refundRequestId')
    && expectedLedgerId === queryValue('ledgerId')
    && sourceActor === authStore.user?.id;
  loading.value = true;
  try {
    const res = await get(`/${sourceFactory}/return-orders/${sourceOrder}`);
    if (!isCurrent()) return;
    if (res.success && res.data) {
      order.value = res.data;
      if (res.data.returnType === 'SALES_RETURN') {
        try {
          const lineage = await getReturnOrderStockLineage(sourceFactory, sourceOrder);
          if (isCurrent()) stockLineage.value = lineage;
        } catch (error) {
          if (isCurrent()) stockLineageError.value = error instanceof Error ? error.message : '退货来源读取失败，请重新读取';
        }
        if (!isCurrent()) return;
        if (canReadFinance.value || canRefundCreate.value) {
          try {
            const refundView = expectedRefundRequestId
              ? await getReturnRefundByRequest(sourceFactory, expectedRefundRequestId)
              : await getReturnRefund(sourceFactory, sourceOrder);
            if (refundView.returnOrderId !== sourceOrder) {
              throw new Error('退款申请来源与当前退货单不一致，请重新读取');
            }
            if (isCurrent()) {
              const request = refundView.request;
              const queryMatches = (expectedRefundRequestId === null && expectedLedgerId === null)
                || (expectedRefundRequestId !== null
                  && !!request && request.id === expectedRefundRequestId
                  && (expectedLedgerId === null || refundLedgerMatchesQuery(request, expectedLedgerId)));
              if (!queryMatches) {
                refund.value = null;
                refundError.value = '退款链接与当前退款申请或资金流水不一致，请从凭证重新打开';
              } else {
                refund.value = refundView;
                if (expectedLedgerId) {
                  await nextTick();
                  if (isCurrent()) refundLedgerElement.value?.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
                }
              }
            }
          } catch (error) {
            if (isCurrent()) refundError.value = error instanceof Error ? error.message : '退款信息读取失败，请重新读取';
          }
        }
      }
      if (typeof recallId === 'string' && /^[1-9]\d*$/.test(recallId)) {
        const recall = await getFactoryRecall(sourceFactory, Number(recallId));
        if (!isCurrent()) return;
        const shipment = recall.shipments.find(item => item.returnOrders.some(ret => ret.id === sourceOrder));
        if (shipment) recallContext.value = { batchId: recall.batchId, batchNumber: recall.batchNumber, unit: shipment.unit };
      }
      if (isCurrent() && res.data.returnType === 'SALES_RETURN' && canReadFinance.value) {
        try {
          const result = await getReturnFinancialFollowUp(sourceFactory, sourceOrder);
          if (isCurrent()) financial.value = result;
        } catch (error) {
          if (isCurrent()) financialError.value = error instanceof Error ? error.message : '应收处理读取失败';
        }
      }
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载退货单失败');
    }
  } catch { /* interceptor */ }
  finally { if (isCurrent()) loading.value = false; }
}

type RefundActionContext = {
  sequence: number;
  factoryId: string;
  returnOrderId: string;
  actorId: number | string | undefined;
};

function captureRefundContext(): RefundActionContext {
  return {
    sequence: loadSequence,
    factoryId: factoryId.value,
    returnOrderId: returnOrderId.value,
    actorId: authStore.user?.id,
  };
}

function isCurrentRefundContext(context: RefundActionContext): boolean {
  return context.sequence === loadSequence
    && context.factoryId === factoryId.value
    && context.returnOrderId === returnOrderId.value
    && context.actorId === authStore.user?.id
    && order.value?.id === context.returnOrderId;
}

function openRefundCreate() {
  if (!refund.value || !hasRefundableAmount.value
    || !['ELIGIBLE', 'REVERSED'].includes(refund.value.eligibilityStatus)
    || (refund.value.request && !['REJECTED', 'CANCELLED'].includes(refund.value.request.status))) return;
  refundAmount.value = Number(refund.value.refundableAmount);
  refundRemark.value = '';
  refundError.value = '';
  refundCreateVisible.value = true;
}

const canRequestRefundReversal = computed(() => {
  const request = refund.value?.request;
  return canRefundCreate.value && request?.status === 'PAID'
    && (!request.reversal || request.reversal.status === 'REJECTED');
});

const canReviewRefundReversal = computed(() => {
  const request = refund.value?.request;
  const reversal = request?.reversal;
  if (!canFinanceApprove.value || reversal?.status !== 'PENDING') return false;
  const operatorId = reversal.ledger?.operatedBy;
  return operatorId != null && String(operatorId) !== String(authStore.user?.id ?? '');
});

function openRefundReversal() {
  if (!canRequestRefundReversal.value || !refund.value?.request) return;
  reversalReason.value = '';
  reversalPaymentReference.value = '';
  refundError.value = '';
  reversalDialogVisible.value = true;
}

async function submitRefundReversal() {
  const request = refund.value?.request;
  const paymentReference = reversalPaymentReference.value.trim();
  const reason = reversalReason.value.trim();
  if (!request || !canRequestRefundReversal.value || reversalDialogVisible.value === false || refundSubmitting.value) return;
  if (!paymentReference) {
    refundError.value = '请填写款项收回凭据';
    return;
  }
  if (paymentReference.length > 100) {
    refundError.value = '款项收回凭据最多100字';
    return;
  }
  if (!reason) {
    refundError.value = '请填写申请撤销退款的原因';
    return;
  }
  if (reason.length > 400) {
    refundError.value = '撤销退款原因最多400字';
    return;
  }
  const context = captureRefundContext();
  refundSubmitting.value = true;
  refundError.value = '';
  try {
    await requestReturnRefundReversal(context.factoryId, context.returnOrderId, request.id, paymentReference, reason);
    if (!isCurrentRefundContext(context)) return;
    reversalDialogVisible.value = false;
    ElMessage.success('撤销退款申请已提交，等待独立财务确认');
    await loadData();
  } catch (error) {
    if (isCurrentRefundContext(context)) refundError.value = error instanceof Error ? error.message : '申请撤销退款失败，请重试';
  } finally {
    if (isCurrentRefundContext(context)) refundSubmitting.value = false;
  }
}

async function approveRefundReversal() {
  const request = refund.value?.request;
  const reversal = request?.reversal;
  if (!request || !reversal || !canReviewRefundReversal.value || refundSubmitting.value || refundPromptOpen.value) return;
  const context = captureRefundContext();
  refundPromptOpen.value = true;
  try {
    await ElMessageBox.confirm(
      `确认款项已收回并撤销退款申请 ${request.requestNumber}？审批后会生成反向应收调整并更新余额。`,
      '确认款项已收回并撤销',
      { confirmButtonText: '确认撤销', cancelButtonText: '取消', type: 'warning' },
    );
  } catch { return; }
  finally { if (isCurrentRefundContext(context)) refundPromptOpen.value = false; }
  if (!isCurrentRefundContext(context) || refundSubmitting.value) return;
  refundSubmitting.value = true;
  refundError.value = '';
  try {
    await approveReturnRefundReversal(context.factoryId, context.returnOrderId, request.id, reversal.ledger.id, { silent: true });
    if (!isCurrentRefundContext(context)) return;
    ElMessage.success('已确认款项收回，退款已撤销');
    await loadData();
  } catch (error) {
    if (isCurrentRefundContext(context)) {
      refundError.value = error instanceof Error ? error.message : '确认撤销退款失败，请重试';
      const returnTo = `/finance/return-refunds/${encodeURIComponent(context.returnOrderId)}?${new URLSearchParams({ refundRequestId: request.id, ledgerId: reversal.ledger.id })}`;
      const location = error instanceof ApiError && error.status === 400
        ? createPeriodRecoveryLocation(error.actionHint, returnTo, context.factoryId, authStore.user?.id) : null;
      refundPeriodRecovery.value = location ? { location, context } : null;
    }
  } finally {
    if (isCurrentRefundContext(context)) refundSubmitting.value = false;
  }
}

function openRefundPeriodRecovery() {
  const recovery = refundPeriodRecovery.value;
  if (recovery && isCurrentRefundContext(recovery.context)) void router.push(recovery.location);
}

async function rejectRefundReversal() {
  const request = refund.value?.request;
  const reversal = request?.reversal;
  if (!request || !reversal || !canReviewRefundReversal.value || refundSubmitting.value || refundPromptOpen.value) return;
  const context = captureRefundContext();
  refundPromptOpen.value = true;
  try {
    const { value } = await ElMessageBox.prompt(
      '驳回后不会撤销退款，请填写核对原因。',
      '驳回撤销退款申请',
      { inputPlaceholder: '驳回原因（必填，80字以内）', inputValidator: (input: string) => {
        const value = input?.trim() || '';
        return value ? (value.length <= 80 || '驳回原因最多80字') : '请填写驳回原因';
      }, confirmButtonText: '确认驳回', cancelButtonText: '取消' },
    );
    if (!isCurrentRefundContext(context) || refundSubmitting.value) return;
    refundSubmitting.value = true;
    refundError.value = '';
    await rejectReturnRefundReversal(context.factoryId, context.returnOrderId, request.id, reversal.ledger.id, value.trim());
    if (!isCurrentRefundContext(context)) return;
    ElMessage.success('撤销退款申请已驳回');
    await loadData();
  } catch (error) {
    if (isCurrentRefundContext(context) && error !== 'cancel' && error !== 'close') {
      refundError.value = error instanceof Error ? error.message : '驳回撤销退款申请失败，请重试';
    }
  } finally {
    if (isCurrentRefundContext(context)) {
      refundPromptOpen.value = false;
      refundSubmitting.value = false;
    }
  }
}

async function submitRefundCreate() {
  if (!refund.value || !canSeeRefundAmount.value || refundCreateVisible.value === false || refundSubmitting.value) return;
  const approvedAmount = refund.value.refundableAmount;
  if (approvedAmount == null) {
    refundError.value = '核定可退金额尚未返回，暂不能创建退款申请，请刷新后重试';
    return;
  }
  if (!Number.isFinite(Number(approvedAmount)) || Number(approvedAmount) <= 0) {
    refundError.value = '当前没有可退金额，请先核对原销售单收款和应收调整';
    return;
  }
  const amount = Number(approvedAmount);
  refundAmount.value = amount;
  const remark = refundRemark.value.trim();
  if (!remark) {
    refundError.value = '请填写退款说明';
    return;
  }
  const context = captureRefundContext();
  refundSubmitting.value = true;
  refundError.value = '';
  try {
    await createReturnRefund(context.factoryId, context.returnOrderId, amount, remark);
    if (!isCurrentRefundContext(context)) return;
    refundCreateVisible.value = false;
    refundSubmitting.value = false;
    ElMessage.success('退款申请已创建，请提交后等待财务审核');
    if (route.query.refundRequestId != null || route.query.ledgerId != null) {
      const nextQuery = { ...route.query };
      delete nextQuery.refundRequestId;
      delete nextQuery.ledgerId;
      await router.replace({ query: nextQuery });
    }
    await loadData();
  } catch (error) {
    if (isCurrentRefundContext(context)) refundError.value = error instanceof Error ? error.message : '创建退款申请失败，请重试';
  } finally {
    if (isCurrentRefundContext(context)) refundSubmitting.value = false;
  }
}

async function submitRefundRequest() {
  if (!refund.value?.request || refund.value.request.status !== 'PENDING' || !canRefundCreate.value || refundSubmitting.value) return;
  const context = captureRefundContext();
  refundSubmitting.value = true;
  refundError.value = '';
  try {
    await submitReturnRefund(context.factoryId, context.returnOrderId);
    if (!isCurrentRefundContext(context)) return;
    refundSubmitting.value = false;
    ElMessage.success('退款申请已提交，等待财务审核');
    await loadData();
  } catch (error) {
    if (isCurrentRefundContext(context)) refundError.value = error instanceof Error ? error.message : '提交退款申请失败，请重试';
  } finally {
    if (isCurrentRefundContext(context)) refundSubmitting.value = false;
  }
}

async function approveRefundRequest() {
  const request = refund.value?.request;
  if (!request || request.status !== 'FINANCE_REVIEW' || !canFinanceApprove.value
    || String(request.createdBy ?? '') === String(authStore.user?.id ?? '') || refundSubmitting.value) return;
  const context = captureRefundContext();
  refundPromptOpen.value = true;
  try {
    const { value } = await ElMessageBox.prompt(
      '请先核对退货应收冲减和收款证据，再批准退款。',
      '批准客户退款',
      { inputPlaceholder: '可填写核对说明', confirmButtonText: '确认批准', cancelButtonText: '取消' },
    );
    if (!isCurrentRefundContext(context)) return;
    refundSubmitting.value = true;
    refundError.value = '';
    await approveReturnRefund(context.factoryId, context.returnOrderId, value.trim());
    if (!isCurrentRefundContext(context)) return;
    refundPromptOpen.value = false;
    refundSubmitting.value = false;
    ElMessage.success('退款申请已批准，可登记实际退款');
    await loadData();
  } catch (error) {
    if (isCurrentRefundContext(context) && error !== 'cancel' && error !== 'close') {
      refundError.value = error instanceof Error ? error.message : '批准退款申请失败，请重试';
    }
  } finally {
    refundPromptOpen.value = false;
    if (isCurrentRefundContext(context)) refundSubmitting.value = false;
  }
}

function openRefundPay() {
  const request = refund.value?.request;
  if (!request || request.status !== 'APPROVED' || !canFinanceApprove.value
    || String(request.createdBy ?? '') === String(authStore.user?.id ?? '')) return;
  refundPaymentMethod.value = 'CASH';
  refundPaymentReference.value = '';
  refundPaymentRemark.value = '';
  refundError.value = '';
  refundPayVisible.value = true;
}

async function submitRefundPaid() {
  const request = refund.value?.request;
  if (!request || request.status !== 'APPROVED' || !canFinanceApprove.value || refundPayVisible.value === false || refundSubmitting.value) return;
  const paymentReference = refundPaymentReference.value.trim();
  const remark = refundPaymentRemark.value.trim();
  if (!paymentReference) {
    refundError.value = '请填写退款付款凭据编号；本地演练请注明模拟';
    return;
  }
  if (!remark) {
    refundError.value = '请填写付款说明';
    return;
  }
  const context = captureRefundContext();
  refundSubmitting.value = true;
  refundError.value = '';
  try {
    await markReturnRefundPaid(context.factoryId, context.returnOrderId, refundPaymentMethod.value, paymentReference, remark);
    if (!isCurrentRefundContext(context)) return;
    refundPayVisible.value = false;
    refundSubmitting.value = false;
    ElMessage.success('已登记人工退款；不会发起转账');
    await loadData();
  } catch (error) {
    if (isCurrentRefundContext(context)) refundError.value = error instanceof Error ? error.message : '登记退款失败，请重试';
  } finally {
    if (isCurrentRefundContext(context)) refundSubmitting.value = false;
  }
}

async function rejectRefundRequest() {
  const request = refund.value?.request;
  if (!request || (request.status !== 'PENDING' && request.status !== 'FINANCE_REVIEW') || !canFinanceApprove.value || refundSubmitting.value) return;
  const context = captureRefundContext();
  refundPromptOpen.value = true;
  try {
    const { value } = await ElMessageBox.prompt(
      '驳回后不会登记退款，请填写核对原因。',
      '驳回客户退款',
      { inputPlaceholder: '驳回原因（必填）', inputValidator: (input: string) => Boolean(input?.trim()) || '请填写驳回原因', confirmButtonText: '确认驳回', cancelButtonText: '取消' },
    );
    if (!isCurrentRefundContext(context)) return;
    refundSubmitting.value = true;
    refundError.value = '';
    await rejectReturnRefund(context.factoryId, context.returnOrderId, value.trim());
    if (!isCurrentRefundContext(context)) return;
    refundPromptOpen.value = false;
    refundSubmitting.value = false;
    ElMessage.success('退款申请已驳回');
    await loadData();
  } catch (error) {
    if (isCurrentRefundContext(context) && error !== 'cancel' && error !== 'close') {
      refundError.value = error instanceof Error ? error.message : '驳回退款申请失败，请重试';
    }
  } finally {
    refundPromptOpen.value = false;
    if (isCurrentRefundContext(context)) refundSubmitting.value = false;
  }
}

function openRefundVoucher(voucherId: string) {
  const context = captureRefundContext();
  if (!isCurrentRefundContext(context)) return;
  void router.push(`/finance/voucher/${encodeURIComponent(voucherId)}`);
}

async function handleAction(action: 'submit' | 'approve' | 'finance-approve' | 'reject' | 'complete') {
  if (loading.value || submitting.value || !order.value || order.value.id !== returnOrderId.value || !factoryId.value) return;
  const sourceOrder = returnOrderId.value;
  const sourceFactory = factoryId.value;
  const sourceActor = authStore.user?.id;
  // T-RTA business logic (#571): messages reflect actual backend branching by withGoods.
  // Old confirm message LIED ("approve triggers inbound") — fixed to match real flow.
  const withGoods = order.value?.withGoods !== false; // null/undefined defaults to true (legacy + new primary)
  // 防呆: 确认框带单据上下文 (单号 + 金额).
  const ctx = order.value
    ? `退货单 ${order.value.returnNumber || '-'}　金额 ¥${order.value.totalAmount ?? '-'}\n`
    : '';
  const approveMsg = withGoods
    ? '审批通过后等待财务审批，确认实物收货后再提交应收或应付调整。确认审批？'
    : '审批通过后提交应收或应付调整，无库存动作。后续仍需财务审批。确认审批？';
  // 六扇门 Tier0 #16: 退货跟钱有关, 业务审批后必须财务审批才能完成/出货.
  const financeMsg = '确认退货财务审批？通过后可办理退货收货，应收调整仍按财务流程处理。';
  // Bug 1 fix (2026-07): issue #571 Phase C 库存自动入库已实现 (completeReturnOrder 自动创建
  // 不良品批次入成品仓 WH-FG). 旧文案说"暂未自动实现, 需仓管员手动入库"是过时信息 —— 若仓管员按
  // 旧文案手动补录一笔, 会与系统自动生成的批次重复计数 (double-count). 改为如实告知"已自动完成".
  const completeMsg = withGoods
    ? (order.value?.returnType === 'PURCHASE_RETURN'
        ? '确认原料已退回供应商？完成后系统自动扣减对应库存，并提交应付调整，无需再手动扣库存。'
        // 🔴 2026-08-19: 原文案说「入库总仓」, 实际落的是【成品仓】的不良品批次;
        //    且没讲它不计入可售库存 —— 用户去成品可用库存里找不到就报「说会退回却没有」。
        : '确认已经收到本单退回的实物？完成后将自动进入成品仓隔离区，不能直接销售，无需再手动入库。应收调整将提交财务处理。')
    : '确认完成本单？此操作不改变库存，应收或应付调整仍按财务流程处理。';
  const messages: Record<string, string> = {
    submit: '确认提交本退货单进入审批?',
    approve: approveMsg,
    'finance-approve': financeMsg,
    reject: '驳回后退货单状态置为已驳回, 不再处理. 确认驳回?',
    complete: completeMsg,
  };
  try {
    await ElMessageBox.confirm(ctx + messages[action], '退货单操作', { type: 'warning' });
  } catch { return; }
  if (sourceOrder !== returnOrderId.value || sourceFactory !== factoryId.value
    || sourceActor !== authStore.user?.id || order.value?.id !== sourceOrder) return;
  submitting.value = true;
  try {
    const res = await post(`/${sourceFactory}/return-orders/${sourceOrder}/${action}`, {});
    if (sourceOrder !== returnOrderId.value || sourceFactory !== factoryId.value
      || sourceActor !== authStore.user?.id || order.value?.id !== sourceOrder) return;
    if (res.success) {
      // Bug 1 follow-through: 后端 completeReturnOrder 会算出 completionHint (如"余额冲减待
      // 财务审批")但此前前端从未展示过 —— 补上, 避免 4 位一体铁律 (b) "toast 文案含真实业务提示"缺失.
      const hint = (res.data as TableRow | undefined)?.completionHint;
      ElMessage({
        message: hint ? `操作成功. ${hint}` : '操作成功',
        type: 'success',
        duration: hint ? 6000 : 3000,
        showClose: !!hint,
      });
      await loadData();
    }
  } catch { /* interceptor 透传后端 message (含 409 状态机/防呆 hint) */ }
  finally { submitting.value = false; }
}

function openReturnFinance(pending = false) {
  if (!order.value || order.value.id !== returnOrderId.value) return;
  void router.push({ path: pending ? '/finance/adjustments' : '/finance/ar-ap', query: { returnOrderId: returnOrderId.value } });
}

async function resubmitAdjustment() {
  if (!financial.value?.canResubmit || financial.value.status === 'PENDING'
    || submitting.value || resubmitPromptOpen.value || !canFinanceApprove.value) return;
  const sourceOrder = returnOrderId.value;
  const sourceFactory = factoryId.value;
  const sourceActor = authStore.user?.id;
  const sourceRecallId = route.query.recallId;
  const sequence = loadSequence;
  const isCurrentResubmitContext = () => sequence === loadSequence
    && sourceOrder === returnOrderId.value
    && sourceFactory === factoryId.value
    && sourceActor === authStore.user?.id
    && sourceRecallId === route.query.recallId
    && order.value?.id === sourceOrder;
  resubmitPromptOpen.value = true;
  try {
    const { value } = await ElMessageBox.prompt(
      `退货单 ${financial.value.returnNumber}：按原退货金额重新提交应收调整。不会再次入库，也不会退款；仍需另一位财务人员审批。`,
      '重新提交应收调整', { inputPlaceholder: '填写核对结果或重提原因', confirmButtonText: '确认重提', cancelButtonText: '取消',
        inputValidator: (value: string) => Boolean(value?.trim()) && value.length <= 200 || '请填写200字以内的重提原因' },
    );
    if (!isCurrentResubmitContext()) return;
    submitting.value = true;
    await resubmitReturnAdjustment(sourceFactory, sourceOrder, value.trim());
    if (!isCurrentResubmitContext()) return;
    ElMessage.success('已重新提交，等待独立财务审批；库存和余额未改变');
    await loadData();
  } catch (error) {
    if (isCurrentResubmitContext() && error !== 'cancel' && error !== 'close' && error instanceof Error) ElMessage.error(error.message);
  } finally {
    resubmitPromptOpen.value = false;
    submitting.value = false;
  }
}

function goBack() {
  if (route.name === 'FinanceReturnRefundDetail') {
    void router.push('/finance/adjustments');
  } else if (recallContext.value) {
    void router.push({ path: '/quality/recalls', query: { batchId: String(recallContext.value.batchId) } });
  } else void router.push('/sales/returns');
}
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card v-if="!loading && !order" shadow="never">
      <el-empty description="退货单不存在">
        <el-button @click="goBack">返回列表</el-button>
      </el-empty>
    </el-card>

    <template v-if="order">
      <div class="detail-header">
        <div class="header-left">
          <el-button :icon="ArrowLeft" @click="goBack">{{ recallContext ? '返回批次召回' : '返回' }}</el-button>
          <h2 class="title">{{ order.returnNumber || '-' }}</h2>
          <el-tag :type="statusType(order.status)" size="large">{{ statusLabel(order.status) }}</el-tag>
        </div>
        <div style="display: flex; gap: 8px;">
          <el-button :icon="Refresh" @click="loadData">刷新</el-button>
          <el-button v-if="order.status === 'DRAFT'" type="primary"
                     :disabled="!canApprove || submitting" :loading="submitting" @click="handleAction('submit')">提交审批</el-button>
          <template v-if="order.status === 'SUBMITTED' && canApprove">
            <el-button type="success" :loading="submitting" @click="handleAction('approve')">审批通过</el-button>
            <el-button type="danger" :loading="submitting" @click="handleAction('reject')">驳回</el-button>
          </template>
          <!-- 六扇门 Tier0 #16: APPROVED 必须先财务审批 (仅财务角色) 才能完成. -->
          <el-button v-if="order.status === 'APPROVED' && canFinanceApprove" type="primary"
                     :loading="submitting" @click="handleAction('finance-approve')">财务审批</el-button>
          <el-tag v-else-if="order.status === 'APPROVED'" type="info" size="large">等待财务审批</el-tag>
          <!-- FINANCE_APPROVED / PROCESSING: 财务已审, 仓管可完成出货. -->
          <el-button v-if="canApprove && (order.status === 'FINANCE_APPROVED' || order.status === 'PROCESSING')"
                     type="primary" :loading="submitting" @click="handleAction('complete')">{{ order.returnType === 'SALES_RETURN' && order.withGoods !== false ? '确认退货收货' : '标记完成' }}</el-button>
        </div>
      </div>

      <el-card v-if="order.returnType === 'SALES_RETURN' && canReadFinance" shadow="never" class="detail-card">
        <template #header><span class="section-title">退货后的应收处理</span></template>
        <el-alert v-if="financialError" :title="financialError" type="error" :closable="false" />
        <template v-if="financial">
          <p>{{ financialStatusText(financial.status) }}</p>
          <p>退货会计凭证：
            <el-button v-if="financial.voucher" link type="primary" @click="openRefundVoucher(financial.voucher.id)">
              {{ financial.voucher.voucherNumber }} · {{ financial.voucher.status === 'POSTED' ? '已过账' : financial.voucher.status === 'DRAFT' ? '草稿，尚未过账' : '待财务处理' }}
            </el-button>
            <span v-else>尚未生成凭证</span>。应收冲减、现金退款和凭证过账分别办理。
          </p>
          <el-button @click="openReturnFinance(false)">查看退货应收调整</el-button>
          <el-button v-if="financial.status === 'PENDING' && canFinanceApprove" type="primary" @click="openReturnFinance(true)">去审批本单调整</el-button>
          <el-button v-if="financial.canResubmit && financial.status !== 'PENDING' && canFinanceApprove" type="warning" :disabled="resubmitPromptOpen" :loading="submitting" @click="resubmitAdjustment">核对后重新提交</el-button>
        </template>
      </el-card>

      <el-card v-if="order.returnType === 'SALES_RETURN' && (canReadFinance || canRefundCreate)" shadow="never" class="detail-card">
        <template #header><span class="section-title">客户现金退款（人工登记）</span></template>
        <el-alert v-if="refundError" :title="refundError" type="error" :closable="false" />
        <div v-if="refundError && refundPeriodRecovery" class="refund-note">
          <p>本次撤销未生效，余额、流水和凭证保持原状。请由财务核对原退款所属期间，反结账后返回本单重新审批。</p>
          <el-button type="primary" @click="openRefundPeriodRecovery">处理锁账并返回本单</el-button>
        </div>
        <template v-if="refund">
          <p class="refund-note">退货冲减货款与登记已退款分别办理。退款会结清已退货的多付款项，不再冲减销售额；人工登记已退款，不会发起转账。</p>
          <div ref="refundLedgerElement" class="refund-ledger-anchor">
          <el-descriptions :column="3" border>
            <el-descriptions-item label="退款资格">{{ refundEligibilityLabel(refund.eligibilityStatus, refund.refundableAmount) }}</el-descriptions-item>
            <el-descriptions-item label="当前可退金额">{{ refundableAmountLabel(refund.refundableAmount) }}</el-descriptions-item>
            <el-descriptions-item v-if="refund.reason" label="处理说明">{{ refund.reason }}</el-descriptions-item>
            <template v-if="refund.request">
              <el-descriptions-item label="退款申请号">{{ refund.request.requestNumber }}</el-descriptions-item>
              <el-descriptions-item label="申请状态">{{ refundRequestStatusLabel(refund.request.status) }}</el-descriptions-item>
              <el-descriptions-item label="申请金额">{{ moneyLabel(refund.request.amount) }}</el-descriptions-item>
              <el-descriptions-item v-if="refund.request.paymentMethod" label="付款方式">{{ refund.request.paymentMethod === 'CASH' ? '现金' : refund.request.paymentMethod === 'BANK_TRANSFER' ? '银行转账' : refund.request.paymentMethod }}</el-descriptions-item>
              <el-descriptions-item v-if="refund.request.paymentReference" label="付款凭证号">{{ refund.request.paymentReference }}</el-descriptions-item>
              <el-descriptions-item v-if="refund.request.voucher" label="退款凭证">
                <el-button link type="primary" @click="openRefundVoucher(refund.request.voucher.id)">
                  {{ refund.request.voucher.voucherNumber }}（{{ voucherStatusLabel(refund.request.voucher.status) }}）
                </el-button>
              </el-descriptions-item>
              <template v-if="refund.request.ledger">
                <el-descriptions-item label="资金流水号">{{ refund.request.ledger.transactionNumber }}</el-descriptions-item>
                <el-descriptions-item label="流水类型">{{ refundLedgerTypeLabel(refund.request.ledger.transactionType) }}</el-descriptions-item>
                <el-descriptions-item label="流水金额">{{ moneyLabel(refund.request.ledger.amount) }}</el-descriptions-item>
                <el-descriptions-item label="客户余额（登记前）">{{ moneyLabel(refund.request.ledger.balanceBefore) }}</el-descriptions-item>
                <el-descriptions-item label="客户余额（登记后）">{{ moneyLabel(refund.request.ledger.balanceAfter) }}</el-descriptions-item>
                <el-descriptions-item label="付款凭据号">{{ refund.request.ledger.paymentReference || '待核对' }}</el-descriptions-item>
                <el-descriptions-item label="批准人">{{ refund.request.ledger.approvedByName ? `${refund.request.ledger.approvedByName}（${actorLabel(refund.request.ledger.approvedBy)}）` : actorLabel(refund.request.ledger.approvedBy) }}</el-descriptions-item>
                <el-descriptions-item label="批准时间">{{ refund.request.ledger.approvedAt || '待核对' }}</el-descriptions-item>
                <el-descriptions-item label="登记人">{{ refund.request.ledger.operatedByName ? `${refund.request.ledger.operatedByName}（${actorLabel(refund.request.ledger.operatedBy)}）` : actorLabel(refund.request.ledger.operatedBy) }}</el-descriptions-item>
                <el-descriptions-item label="登记日期">{{ refund.request.ledger.transactionDate || '待核对' }}</el-descriptions-item>
              </template>
              <template v-if="refund.request.reversal">
                <el-descriptions-item label="退款撤销状态">
                  <el-tag :type="refund.request.reversal.status === 'APPROVED' ? 'success' : refund.request.reversal.status === 'PENDING' ? 'warning' : 'info'" size="small">
                    {{ reversalStatusLabel(refund.request.reversal.status) }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="撤销申请日期">{{ formatDateTime(refund.request.reversal.requestedAt) }}</el-descriptions-item>
                <el-descriptions-item v-if="refund.request.reversal.reason" label="撤销原因" :span="2">{{ refund.request.reversal.reason }}</el-descriptions-item>
                <el-descriptions-item label="收回款项凭据">{{ refund.request.reversal.ledger.paymentReference || '待核对' }}</el-descriptions-item>
                <el-descriptions-item label="撤销流水号">{{ refund.request.reversal.ledger.transactionNumber || refund.request.reversal.ledger.id }}</el-descriptions-item>
                <el-descriptions-item label="撤销流水类型">{{ refundLedgerTypeLabel(refund.request.reversal.ledger.transactionType) }}</el-descriptions-item>
                <el-descriptions-item label="撤销金额">{{ moneyLabel(refund.request.reversal.ledger.amount) }}</el-descriptions-item>
                <el-descriptions-item label="客户余额（撤销前）">{{ reversalBalanceLabel(refund.request.reversal.ledger.balanceBefore) }}</el-descriptions-item>
                <el-descriptions-item label="客户余额（撤销后）">{{ reversalBalanceLabel(refund.request.reversal.ledger.balanceAfter) }}</el-descriptions-item>
                <el-descriptions-item label="发起人">{{ refund.request.reversal.ledger.operatedByName ? `${refund.request.reversal.ledger.operatedByName}（${actorLabel(refund.request.reversal.ledger.operatedBy)}）` : actorLabel(refund.request.reversal.ledger.operatedBy) }}</el-descriptions-item>
                <el-descriptions-item label="批准人">{{ refund.request.reversal.ledger.approvedByName ? `${refund.request.reversal.ledger.approvedByName}（${actorLabel(refund.request.reversal.ledger.approvedBy)}）` : actorLabel(refund.request.reversal.ledger.approvedBy) }}</el-descriptions-item>
                <el-descriptions-item label="批准时间">{{ refund.request.reversal.ledger.approvedAt || '待审批' }}</el-descriptions-item>
                <el-descriptions-item label="撤销流水日期">{{ refund.request.reversal.ledger.transactionDate || '待核对' }}</el-descriptions-item>
                <el-descriptions-item v-if="refund.request.reversal.voucher" label="撤销凭证">
                  <el-button link type="primary" @click="openRefundVoucher(refund.request.reversal.voucher.id)">
                    {{ refund.request.reversal.voucher.voucherNumber }}（{{ voucherStatusLabel(refund.request.reversal.voucher.status) }}）
                  </el-button>
                </el-descriptions-item>
              </template>
            </template>
          </el-descriptions>
          </div>
          <el-alert
            v-if="refund.request?.status === 'PAID' && !refund.request.ledger"
            title="已登记退款，资金流水待核对"
            type="warning"
            :closable="false"
            class="refund-ledger-warning"
          />
          <div class="refund-actions">
            <el-button
              v-if="(!refund.request || refund.request.status === 'REJECTED' || refund.request.status === 'CANCELLED') && ['ELIGIBLE', 'REVERSED'].includes(refund.eligibilityStatus)"
              type="primary"
              :disabled="!hasRefundableAmount || refundSubmitting"
              @click="openRefundCreate"
            >新建退款申请</el-button>
            <el-button
              v-if="refund.request?.status === 'PENDING'"
              type="primary"
              :disabled="!canRefundCreate || refundSubmitting"
              :loading="refundSubmitting"
              @click="submitRefundRequest"
            >提交退款申请</el-button>
            <el-button
              v-if="refund.request?.status === 'FINANCE_REVIEW'"
              type="success"
              :disabled="!canFinanceApprove || String(refund.request.createdBy ?? '') === String(authStore.user?.id ?? '') || refundSubmitting || refundPromptOpen"
              :loading="refundSubmitting"
              @click="approveRefundRequest"
            >批准退款</el-button>
            <el-button
              v-if="refund.request && (refund.request.status === 'PENDING' || refund.request.status === 'FINANCE_REVIEW')"
              type="danger"
              plain
              :disabled="!canFinanceApprove || String(refund.request.createdBy ?? '') === String(authStore.user?.id ?? '') || refundSubmitting || refundPromptOpen"
              @click="rejectRefundRequest"
            >驳回退款</el-button>
            <el-button
              v-if="refund.request?.status === 'APPROVED'"
              type="primary"
              :disabled="!canFinanceApprove || String(refund.request.createdBy ?? '') === String(authStore.user?.id ?? '') || refundSubmitting"
              @click="openRefundPay"
            >登记已退款</el-button>
            <el-button
              v-if="canRequestRefundReversal"
              type="warning"
              plain
              :disabled="refundSubmitting || reversalDialogVisible"
              @click="openRefundReversal"
            >申请撤销退款</el-button>
            <el-button
              v-if="refund.request?.reversal?.status === 'PENDING'"
              type="success"
              :disabled="!canReviewRefundReversal || refundSubmitting || refundPromptOpen"
              :loading="refundSubmitting"
              @click="approveRefundReversal"
            >确认款项已收回并撤销</el-button>
            <el-button
              v-if="refund.request?.reversal?.status === 'PENDING'"
              type="danger"
              plain
              :disabled="!canReviewRefundReversal || refundSubmitting || refundPromptOpen"
              @click="rejectRefundReversal"
            >驳回</el-button>
            <el-text v-if="!canSeeRefundAmount && !refund.request" type="info">需要价格查看权限才能查看核定退款金额</el-text>
            <el-text v-else-if="!hasRefundableAmount && !refund.request && refund.refundableAmount === 0" type="warning">当前没有可退金额，请先核对原销售单收款和应收调整</el-text>
          </div>
        </template>
        <el-empty v-else description="退款信息暂不可用，请刷新后重试" :image-size="60" />
      </el-card>

      <el-card v-if="order.returnType === 'SALES_RETURN'" shadow="never" class="detail-card">
        <template #header><span class="section-title">多行来源与退回入库</span></template>
        <el-alert v-if="stockLineageError" :title="stockLineageError" type="error" :closable="false" />
        <el-table v-if="stockLineage" :data="stockLineage.lines" border stripe>
          <el-table-column prop="sourceBatchNumber" label="来源批次" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.sourceBatchNumber || '待核对' }}</template>
          </el-table-column>
          <el-table-column label="来源状态" width="140">
            <template #default="{ row }">{{ sourceStatusLabel(row.sourceStatus) }}</template>
          </el-table-column>
          <el-table-column label="退货数量" width="120" align="right">
            <template #default="{ row }">{{ row.quantity == null ? '待核对' : `${row.quantity} ${row.unit || ''}` }}</template>
          </el-table-column>
          <el-table-column label="对应退回入库" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ returnedStockLabel(row) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!stockLineageError" description="暂无可靠来源记录，不能按列表顺序推测" :image-size="60" />
      </el-card>

      <el-card shadow="never" class="detail-card">
        <template #header><span class="section-title">基本信息</span></template>
        <el-alert v-if="recallContext" :title="`召回批次：${recallContext.batchNumber}；本单数量单位：${recallContext.unit}`" type="info" :closable="false" />
        <el-descriptions :column="3" border>
          <el-descriptions-item label="退货类型">{{ order.returnType === 'SALES_RETURN' ? '销售退货' : '采购退货' }}</el-descriptions-item>
          <!-- T-RTA business logic (#571): 有食物/无食物 drives 审批/完成 时机 + 库存动作. -->
          <el-descriptions-item label="退货分支">
            <el-tag :type="order.withGoods === false ? 'info' : 'warning'" size="small">
              {{ order.withGoods === false ? '仅退款（无实物）' : '实物退货（隔离入库）' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="客户/对方">{{ order.counterpartyName || order.counterpartyId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="源订单号">{{ order.sourceOrderNumber || order.sourceOrderId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="退货日期">{{ order.returnDate || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="退货金额(未税)">{{ formatAmount(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice && Number(order.taxAmount) > 0" label="销项税额">{{ formatAmount(order.taxAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice && Number(order.taxAmount) > 0" label="应收冲减(含税)">{{ formatAmount(order.receivableAmount) }}</el-descriptions-item>
          <el-descriptions-item label="审批人">{{ order.approvedByName || (order.approvedBy != null ? `编号 #${order.approvedBy}` : '-') }}</el-descriptions-item>
          <el-descriptions-item label="原因" :span="3">{{ order.reason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ remarkDisplay }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(order.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatDateTime(order.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="detail-card">
        <template #header><span class="section-title">退货明细</span></template>
        <el-table :data="order.items || []" border stripe>
          <el-table-column label="品名" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.itemName || row.productName || row.materialName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="batchNumber" label="批次号" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.batchNumber || '-' }}</template>
          </el-table-column>
          <el-table-column label="实际来源" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <template v-if="lineageForItem(row)">
                {{ lineageForItem(row)?.sourceBatchNumber || '待核对' }}（{{ sourceStatusLabel(lineageForItem(row)?.sourceStatus) }}）
              </template>
              <span v-else>待核对</span>
            </template>
          </el-table-column>
          <el-table-column label="退回入库对应" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ returnedStockLabel(lineageForItem(row)) }}</template>
          </el-table-column>
          <el-table-column label="退货数量" width="130" align="right">
            <template #default="{ row }">{{ row.quantity ?? '-' }} {{ lineageForItem(row)?.unit || row.unit || '' }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="单价" width="110" align="right">
            <template #default="{ row }">{{ formatAmount(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" label="小计" width="120" align="right">
            <template #default="{ row }">{{ formatAmount((Number(row.quantity) || 0) * (Number(row.unitPrice) || 0)) }}</template>
          </el-table-column>
          <el-table-column label="行原因" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.reason || '-' }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-dialog v-model="refundCreateVisible" title="新建客户退款申请" width="520px" :close-on-click-modal="false">
        <el-alert v-if="refundError" :title="refundError" type="error" :closable="false" />
        <el-alert title="先核对退货应收冲减和已收款证据。退款会结清已退货多付款项，不再冲减销售额。" type="info" :closable="false" />
        <el-form label-position="top" class="refund-form">
          <el-form-item label="退款金额（元，核定额）" required>
            <el-input :model-value="refundAmount == null ? '' : formatAmount(refundAmount)" readonly />
            <div class="refund-form-hint">按核定金额一次结清，不能拆分付款。</div>
          </el-form-item>
          <el-form-item label="退款说明" required>
            <el-input v-model="refundRemark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="填写退货冲减货款的核对说明" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="refundCreateVisible = false">取消</el-button>
          <el-button type="primary" :loading="refundSubmitting" @click="submitRefundCreate">创建申请</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="refundPayVisible" title="登记客户已退款" width="520px" :close-on-click-modal="false">
        <el-alert v-if="refundError" :title="refundError" type="error" :closable="false" />
        <el-alert title="这里登记人工已完成的退款，不会发起转账。付款凭证号必填；本地演练请注明模拟。" type="info" :closable="false" />
        <el-form label-position="top" class="refund-form">
          <el-form-item label="付款方式" required>
            <el-select v-model="refundPaymentMethod" style="width: 100%">
              <el-option label="现金" value="CASH" />
              <el-option label="银行转账" value="BANK_TRANSFER" />
            </el-select>
          </el-form-item>
          <el-form-item label="付款凭证号" required>
            <el-input v-model="refundPaymentReference" maxlength="100" placeholder="填写人工退款凭据编号" />
          </el-form-item>
          <el-form-item label="付款说明" required>
            <el-input v-model="refundPaymentRemark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="填写实际退款说明" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="refundPayVisible = false">取消</el-button>
          <el-button type="primary" :loading="refundSubmitting" @click="submitRefundPaid">确认登记</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="reversalDialogVisible" title="申请撤销退款" width="520px" :close-on-click-modal="false">
        <el-alert v-if="refundError" :title="refundError" type="error" :closable="false" />
        <el-alert title="请先确认款项已经收回。提交后由另一位财务人员审批，审批前不会改变客户余额。" type="info" :closable="false" />
        <el-form label-position="top" class="refund-form">
          <el-form-item label="款项收回凭据" required>
            <el-input v-model="reversalPaymentReference" maxlength="100" placeholder="银行流水号、现金收据号或其他收回凭据" />
          </el-form-item>
          <el-form-item label="撤销原因" required>
            <el-input v-model="reversalReason" type="textarea" :rows="3" maxlength="400" show-word-limit placeholder="填写退款撤销原因及核对说明（400字以内）" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="reversalDialogVisible = false">取消</el-button>
          <el-button type="warning" :loading="refundSubmitting" @click="submitRefundReversal">提交撤销申请</el-button>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { padding: 20px; height: 100%; overflow-y: auto; }
.detail-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;
  .header-left { display: flex; align-items: center; gap: 12px;
    .title { font-size: 18px; font-weight: 600; margin: 0; color: #303133; }
  }
}
.detail-card { margin-bottom: 16px;
  :deep(.el-card__header) { padding: 14px 18px; border-bottom: 1px solid #ebeef5; }
}
.section-title { font-size: 15px; font-weight: 600; color: #303133; }
.refund-note { color: #606266; font-size: 13px; line-height: 1.6; margin: 0 0 14px; }
.refund-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 14px; }
.refund-ledger-warning { margin-top: 12px; }
.refund-form { margin-top: 16px; }
.refund-form-hint { color: #909399; font-size: 12px; line-height: 1.5; margin-top: 4px; }
</style>
