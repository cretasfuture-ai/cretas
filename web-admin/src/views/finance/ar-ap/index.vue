<script setup lang="ts">
import { ref, computed, watch, onDeactivated, onActivated } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import { enumLabel, PAYMENT_METHOD_LABELS } from '@/utils/enumDisplay';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
import type { CsTableColumn } from '@/types/csTable';
// 通用确认弹窗 (UModal, 取代 Element 的命令式确认框: 它 在 Nuxt UI 弹层之上点不了)。
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import { getReturnFinancialFollowUp, validReturnOrderId } from '@/api/returnFinancialFollowUp';
import { getReturnRefundByRequest } from '@/api/inventory';

const route = useRoute();
const router = useRouter();
const hasReturnContext = computed(() => route?.query.returnOrderId != null);
const sourceReturnId = computed(() => validReturnOrderId(route?.query.returnOrderId));
const sourceReturnNumber = ref('');
const sourceError = ref('');
let transactionRequest = 0;
let refundNavigationRequest = 0;
function openSourceReturn(id: unknown) {
  const valid = validReturnOrderId(id);
  if (valid) void router.push(`/finance/return-refunds/${encodeURIComponent(valid)}`);
}

function validRefundRequestId(id: unknown): string | null {
  if (Array.isArray(id)) return null;
  const value = String(id ?? '').trim();
  return value && value !== 'undefined' && value !== 'null' ? value : null;
}

async function openSourceRefund(row: TableRow) {
  const requestId = validRefundRequestId(row.sourceId);
  const clickedLedgerId = String(row.id ?? '').trim();
  if (!requestId || !clickedLedgerId || !factoryId.value) {
    ElMessage.error('退款申请号无效，无法定位退款流水');
    return;
  }
  const sequence = ++refundNavigationRequest;
  const sourceFactory = factoryId.value;
  const sourceUser = authStore.user?.id;
  const sourceRoute = route.fullPath;
  try {
    const refundView = await getReturnRefundByRequest(sourceFactory, requestId);
    if (sequence !== refundNavigationRequest || sourceFactory !== factoryId.value
      || sourceUser !== authStore.user?.id || sourceRoute !== route.fullPath) return;
    if (!refundView.returnOrderId || !refundView.request || refundView.request.id !== requestId) {
      throw new Error('退款申请来源与当前请求不一致，请重新读取');
    }
    if (!refundView.request.ledger?.id) {
      throw new Error('退款申请尚未形成资金流水，暂不能定位详情');
    }
    const linkedLedgerIds = [refundView.request.ledger?.id, refundView.request.reversal?.ledger?.id]
      .filter((id): id is string => Boolean(id)).map(String);
    if (!linkedLedgerIds.includes(clickedLedgerId)) {
      throw new Error('退款资金流水与当前应收流水不一致，请重新读取');
    }
    await router.push({
      path: `/finance/return-refunds/${encodeURIComponent(refundView.returnOrderId)}`,
      query: { refundRequestId: refundView.request.id, ledgerId: clickedLedgerId },
    });
  } catch (error) {
    if (sequence === refundNavigationRequest && sourceFactory === factoryId.value
      && sourceUser === authStore.user?.id && sourceRoute === route.fullPath) {
      ElMessage.error(error instanceof Error ? error.message : '退款申请读取失败，请重试');
    }
  }
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
const canWriteFinance = computed(() => permissionStore.canWrite('finance'));

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const settleDialogVisible = ref(false);
const settleSubmitting = ref(false);
const settleRow = ref<TableRow | null>(null);
const settleForm = ref({
  amount: 0,
  paymentMethod: 'BANK_TRANSFER',
  paymentReference: '',
  remark: '',
  idempotencyKey: '',
});

function payableOutstanding(row: TableRow): number {
  const value = Number(row.outstandingAmount);
  return Number.isFinite(value) ? value : 0;
}

function canSettlePayable(row: TableRow): boolean {
  return canWriteFinance.value
    && row.transactionType === 'AP_INVOICE'
    && Boolean(row.id && row.counterpartyId)
    && payableOutstanding(row) > 0
    && row.paymentStatus !== 'NEEDS_RECONCILIATION';
}

/**
 * 2026-08-23 E2E contract repair: the legacy procurement PaymentRequest write URLs
 * are explicit HTTP 410 tombstones. Payment execution must start from this audited
 * AP_INVOICE open item and use PayableSettlementController, never create an
 * unallocated payment from a purchase page.
 */
function openSettleDialog(row: TableRow) {
  settleRow.value = row;
  settleForm.value = {
    amount: payableOutstanding(row),
    paymentMethod: 'BANK_TRANSFER',
    paymentReference: '',
    remark: '',
    idempotencyKey: `web-ap-${row.id}-${Date.now()}`,
  };
  settleDialogVisible.value = true;
}

async function settlePayable() {
  const row = settleRow.value;
  if (!factoryId.value || !row?.id || !row.counterpartyId || settleSubmitting.value) return;
  if (!settleForm.value.paymentReference.trim()) {
    ElMessage.warning('请填写付款凭证号');
    return;
  }
  if (settleForm.value.amount <= 0 || settleForm.value.amount > payableOutstanding(row)) {
    ElMessage.warning('付款金额必须大于 0 且不能超过未清金额');
    return;
  }

  const confirmed = await confirmRef.value!.ask({
    title: '确认付款核销',
    message: `确认向 ${row.counterpartyName || '该供应商'} 付款 ${formatAmount(settleForm.value.amount)} 并核销此应付？`,
    confirmText: '确认付款',
  });
  if (confirmed === null) return;

  settleSubmitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/payables/${row.id}/settlements`, {
      supplierId: row.counterpartyId,
      amount: settleForm.value.amount,
      currencyCode: row.currencyCode || 'CNY',
      paymentMethod: settleForm.value.paymentMethod,
      paymentReference: settleForm.value.paymentReference.trim(),
      idempotencyKey: settleForm.value.idempotencyKey,
      remark: settleForm.value.remark.trim() || undefined,
    });
    if (res.success) {
      ElMessage.success(res.message || '付款核销成功');
      settleDialogVisible.value = false;
      await Promise.all([loadTransactions(), loadOverview()]);
    } else {
      ElMessage.error(res.message || '付款核销失败');
    }
  } finally {
    settleSubmitting.value = false;
  }
}

const activeTab = ref('overview');
const loading = ref(false);
const overviewLoading = ref(false);
const overviewError = ref('');
let overviewRequest = 0;

// 概览数据
const overview = ref<TableRow | null>(null);
// 交易记录
const transactions = ref<TableRow[]>([]);
const txPagination = ref({ page: 1, size: 10, total: 0 });
const txTypeFilter = ref('');
// 账龄
const agingData = ref<TableRow[]>([]);
const agingType = ref('CUSTOMER');
const agingLoading = ref(false);
const agingError = ref('');
let agingRequest = 0;

const txTypeMap: Record<string, { text: string; type: string }> = {
  AR_INVOICE: { text: '应收挂账', type: 'warning' },
  AR_PAYMENT: { text: '客户回款', type: 'success' },
  AR_ADJUSTMENT: { text: '应收调整', type: 'info' },
  AR_REFUND: { text: '客户退款结清', type: 'warning' },
  AR_CREDIT_NOTE: { text: '应收冲减', type: 'info' },
  AP_CREDIT_NOTE: { text: '应付冲减', type: 'info' },
  RETURN_REFUND_REVERSAL: { text: '退款撤销调整', type: 'info' },
  AP_INVOICE: { text: '应付挂账', type: 'danger' },
  AP_PAYMENT: { text: '供应商付款', type: 'success' },
  AP_ADJUSTMENT: { text: '应付调整', type: 'info' },
};

const SETTLE_METHOD_ITEMS = ['BANK_TRANSFER', 'CASH', 'WECHAT', 'ALIPAY', 'CHECK', 'OTHER']
  .map(value => ({ value, label: PAYMENT_METHOD_LABELS[value] }));
const PAGE_SIZE_ITEMS = [10, 20, 50].map(value => ({ value, label: `${value} 条/页` }));
const methodText = (code: unknown) => (code ? enumLabel(code, PAYMENT_METHOD_LABELS) : '-');


function finiteNumber(value: unknown): number {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function overdueTotal(value: TableRow | null): number {
  return finiteNumber(value?.overdueReceivable) + finiteNumber(value?.overduePayable);
}

function overdueCount(value: TableRow | null): number {
  return finiteNumber(value?.overdueARCount) + finiteNumber(value?.overdueAPCount);
}

// 🟡 F006 采购 audit fix (Bug 3): AR/AP_ADJUSTMENT 行在 approval_status=PENDING 时
// balanceAfter 有意保持"提交前快照"不变 (dual-control, 见 ArApServiceImpl.recordAdjustment
// 注释) — 审批通过后才会被 approveAdjustment() 重算. 但本页此前完全不显示 approvalStatus,
// 用户看到"余额没变"会误以为是 bug. 加状态标签让"待审批"一目了然 (fool-proof-design Rule 2:
// 上下文必带身份信息). 非调整类型交易 approval_status 恒为 APPROVED (自动过账), 不需要显眼展示.
const approvalStatusMap: Record<string, { text: string; type: string }> = {
  PENDING: { text: '待审批', type: 'warning' },
  APPROVED: { text: '已审批', type: 'success' },
  REJECTED: { text: '已驳回', type: 'danger' },
};
function isAdjustmentRow(row: TableRow): boolean {
  return row.transactionType === 'AR_ADJUSTMENT' || row.transactionType === 'AP_ADJUSTMENT'
    || row.sourceType === 'RETURN_REFUND_REVERSAL';
}

function refreshFinancialData() {
  if (hasReturnContext.value) {
    txTypeFilter.value = 'CUSTOMER';
    void loadTransactions();
    return;
  }
  void loadOverview();
  void loadTransactions();
  void loadAging();
}

watch(() => [factoryId.value, authStore.user?.id, route?.query.returnOrderId], () => {
  ++transactionRequest;
  ++overviewRequest;
  ++agingRequest;
  transactions.value = [];
  overview.value = null;
  overviewError.value = '';
  agingData.value = [];
  agingError.value = '';
  agingLoading.value = false;
  sourceReturnNumber.value = '';
  sourceError.value = '';
  txPagination.value.page = 1;
  txPagination.value.total = 0;
  if (hasReturnContext.value) {
    activeTab.value = 'receivable';
    txTypeFilter.value = 'CUSTOMER';
    void loadTransactions();
  } else if (activeTab.value === 'receivable' || activeTab.value === 'payable') {
    txTypeFilter.value = activeTab.value === 'receivable' ? 'CUSTOMER' : 'SUPPLIER';
    void loadTransactions();
  } else if (activeTab.value === 'overview') {
    void loadOverview();
  } else if (activeTab.value === 'aging') {
    void loadAging();
  }
}, { immediate: true });
onDeactivated(() => {
  ++transactionRequest;
  ++overviewRequest;
  ++agingRequest;
  ++refundNavigationRequest;
  overview.value = null;
  overviewError.value = '';
  overviewLoading.value = false;
  agingData.value = [];
  agingError.value = '';
  agingLoading.value = false;
});
let activations = 0;
onActivated(() => {
  if (activations++ === 0) return;
  if (hasReturnContext.value || activeTab.value === 'receivable' || activeTab.value === 'payable') void loadTransactions();
  else if (activeTab.value === 'aging') void loadAging();
  else void loadOverview();
});

watch(activeTab, (tab) => {
  if (hasReturnContext.value) return;
  if (tab === 'receivable') {
    txTypeFilter.value = 'CUSTOMER';
    txPagination.value.page = 1;
    loadTransactions();
  } else if (tab === 'payable') {
    txTypeFilter.value = 'SUPPLIER';
    txPagination.value.page = 1;
    loadTransactions();
  } else if (tab === 'aging') {
    void loadAging();
  } else if (tab === 'overview') {
    loadOverview();
  }
}, { immediate: false });

async function loadOverview() {
  const sequence = ++overviewRequest;
  const sourceFactory = factoryId.value;
  const sourceUser = authStore.user?.id;
  const sourceReturnId = route?.query.returnOrderId;
  const current = () => sequence === overviewRequest
    && sourceFactory === factoryId.value
    && sourceUser === authStore.user?.id
    && sourceReturnId === route?.query.returnOrderId;
  overview.value = null;
  overviewError.value = '';
  if (!sourceFactory) {
    overviewLoading.value = false;
    return;
  }
  overviewLoading.value = true;
  try {
    const res = await get(`/${sourceFactory}/finance/overview`);
    if (!current()) return;
    if (res.success) overview.value = res.data;
    else if (res.success === false) overviewError.value = res.message || '加载财务概览失败';
  } catch (error) {
    if (current()) overviewError.value = error instanceof Error ? error.message : '加载财务概览失败';
  } finally {
    if (current()) overviewLoading.value = false;
  }
}

async function loadTransactions() {
  if (!factoryId.value) return;
  const sequence = ++transactionRequest;
  const sourceFactory = factoryId.value;
  const sourceUser = authStore.user?.id;
  const contextValue = route?.query.returnOrderId;
  const current = () => sequence === transactionRequest && sourceFactory === factoryId.value
    && sourceUser === authStore.user?.id && contextValue === route?.query.returnOrderId;
  loading.value = true;
  sourceError.value = '';
  try {
    if (hasReturnContext.value) {
      const returnId = sourceReturnId.value;
      if (!returnId) throw new Error('退货单链接无效，请从原单据重新进入');
      const followUp = await getReturnFinancialFollowUp(sourceFactory, returnId);
      if (!current()) return;
      sourceReturnNumber.value = followUp.returnNumber;
      const begin = (txPagination.value.page - 1) * txPagination.value.size;
      transactions.value = followUp.adjustments.slice(begin, begin + txPagination.value.size).map(row => ({ ...row }));
      txPagination.value.total = followUp.adjustments.length;
      return;
    }
    const params: TableRow = { page: txPagination.value.page, size: txPagination.value.size };
    if (txTypeFilter.value) params.counterpartyType = txTypeFilter.value;
    const res = await get(`/${factoryId.value}/finance/transactions`, { params });
    if (current() && res.success && res.data) {
      transactions.value = res.data.content || [];
      txPagination.value.total = res.data.totalElements || 0;
    } else if (current() && res.success === false) {
      ElMessage.error(res.message || '加载交易记录失败');
    }
  } catch (error) {
    if (current()) {
      transactions.value = [];
      txPagination.value.total = 0;
      if (hasReturnContext.value) sourceError.value = error instanceof Error ? error.message : '退货应收流水读取失败';
    }
  }
  finally { if (current()) loading.value = false; }
}

async function loadAging() {
  const sequence = ++agingRequest;
  const sourceFactory = factoryId.value;
  const sourceUser = authStore.user?.id;
  const sourceReturnId = route?.query.returnOrderId;
  const sourceAgingType = agingType.value;
  const current = () => sequence === agingRequest
    && sourceFactory === factoryId.value
    && sourceUser === authStore.user?.id
    && sourceReturnId === route?.query.returnOrderId
    && sourceAgingType === agingType.value;
  agingData.value = [];
  agingError.value = '';
  if (!sourceFactory || hasReturnContext.value) {
    agingLoading.value = false;
    return;
  }
  agingLoading.value = true;
  try {
    const res = await get(`/${sourceFactory}/finance/aging`, { params: { counterpartyType: sourceAgingType } });
    if (!current()) return;
    if (res.success) agingData.value = Array.isArray(res.data) ? res.data : [];
    else if (res.success === false) agingError.value = res.message || '加载账龄数据失败';
  } catch (error) {
    if (current()) agingError.value = error instanceof Error ? error.message : '加载账龄数据失败';
  } finally {
    if (current()) agingLoading.value = false;
  }
}

function handleTxPageChange(page: number) { txPagination.value.page = page; loadTransactions(); }
function handleTxSizeChange(size: number) { txPagination.value.size = size; txPagination.value.page = 1; loadTransactions(); }
function handleTxTypeChange() { txPagination.value.page = 1; loadTransactions(); }
function handleAgingTypeChange() { void loadAging(); }
const TAB_ITEMS = computed(() => [
  ...(hasReturnContext.value ? [] : [{ label: '财务概览', value: 'overview', slot: 'overview' as const }]),
  { label: '应收账款', value: 'receivable', slot: 'receivable' as const },
  ...(hasReturnContext.value ? [] : [
    { label: '应付账款', value: 'payable', slot: 'payable' as const },
    { label: '账龄分析', value: 'aging', slot: 'aging' as const },
  ]),
]);

type TxRow = TableRow;
function typeKey(row: TxRow): string {
  return String(row.sourceType === 'RETURN_REFUND_REVERSAL' ? 'RETURN_REFUND_REVERSAL' : row.transactionType);
}
const typeText = (row: TxRow) => txTypeMap[typeKey(row)]?.text || enumLabel(row.transactionType);
const typeColor = (row: TxRow) => badgeColor((txTypeMap[String(row.transactionType)]?.type || 'info') as ElementTagType);
const approvalText = (row: TxRow) => approvalStatusMap[String(row.approvalStatus)]?.text || enumLabel(row.approvalStatus);
const approvalColor = (row: TxRow) => badgeColor((approvalStatusMap[String(row.approvalStatus)]?.type || 'info') as ElementTagType);

const RIGHT = { class: { th: 'text-right', td: 'text-right' } };
function txColumns(counterparty: string, withActions: boolean): CsTableColumn<TxRow>[] {
  const cols: CsTableColumn<TxRow>[] = [
    { accessorKey: 'transactionNumber', header: '交易编号' },
    { accessorKey: 'counterpartyName', header: counterparty },
    { id: 'transactionType', header: '类型' },
    { id: 'amount', header: '金额', meta: RIGHT },
  ];
  if (!overview.value?.scopeApplied) cols.push({ id: 'balanceAfter', header: '交易后余额（历史快照）', meta: RIGHT });
  if (transactions.value.some(r => isAdjustmentRow(r))) cols.push({ id: 'approval', header: '审批状态' });
  cols.push(
    { id: 'paymentMethod', header: '支付方式' },
    { accessorKey: 'transactionDate', header: '日期' },
  );
  if (!withActions) cols.push({ accessorKey: 'dueDate', header: '到期日' });
  cols.push({ id: 'source', header: '来源单据' }, { accessorKey: 'remark', header: '备注' });
  if (withActions) cols.push({ id: 'actions', header: '操作' });
  return cols;
}

const agingColumns = computed<CsTableColumn<TxRow>[]>(() => [
  { accessorKey: 'counterpartyName', header: agingType.value === 'CUSTOMER' ? '客户' : '供应商' },
  { id: 'total', header: '未清合计', meta: RIGHT },
  { id: 'current', header: '未到期', meta: RIGHT },
  { id: 'days1_30', header: '1-30天', meta: RIGHT },
  { id: 'days31_60', header: '31-60天', meta: RIGHT },
  { id: 'days61_90', header: '61-90天', meta: RIGHT },
  { id: 'days91_120', header: '91-120天', meta: RIGHT },
  { id: 'days120plus', header: '120天以上', meta: RIGHT },
]);
</script>

<template>
  <div class="page" data-testid="finance-arap-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>应收应付管理</h2>
        <div class="head-actions">
          <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" data-testid="arap-refresh" @click="refreshFinancialData">刷新</UButton>
        </div>
      </header>

      <div class="body">
        <UAlert v-if="hasReturnContext" :color="sourceError ? 'error' : 'info'" variant="soft" icon="i-lucide-info" class="mb-3" data-testid="arap-return-context"
          :title="sourceError || `仅显示退货单 ${sourceReturnNumber || '正在核对'} 的应收调整，保留历次驳回和重提记录`">
          <template #description>
            <div class="alert-actions">
              <UButton v-if="sourceReturnId" variant="link" size="xs" @click="openSourceReturn(sourceReturnId)">返回原退货单</UButton>
              <UButton color="neutral" variant="link" size="xs" @click="router.replace({ query: {} })">查看全部流水</UButton>
            </div>
          </template>
        </UAlert>
        <UAlert v-if="canViewPrice && overview?.scopeApplied" color="info" variant="soft" icon="i-lucide-info" class="mb-3"
          title="仅显示当前授权范围内的账目与金额，不代表全工厂余额。" />

        <UTabs v-if="canViewPrice" v-model="activeTab" :items="TAB_ITEMS" variant="link" :ui="{ trigger: 'px-[10px] py-[8px] text-[13px]', content: 'pt-3' }" data-testid="arap-tabs">
          <!-- 概览 -->
          <template #overview>
            <UAlert v-if="overviewLoading" color="info" variant="soft" icon="i-lucide-info" title="正在读取财务概览…" />
            <UAlert v-else-if="overviewError" color="error" variant="soft" icon="i-lucide-circle-alert" :title="overviewError" />
            <div v-else-if="overview" class="stat-cards" data-testid="arap-overview">
              <div class="stat-card ar">
                <div class="stat-label">应收总额</div>
                <div class="stat-value">{{ formatAmount(overview.totalReceivable) }}</div>
                <!-- 🟡 F006 采购 audit fix (Bug 2): 后端按交易对手(客户/供应商)聚合欠款笔数, 非单据/发票行数
                     (ar_ap_transactions 是流水式台账, 无法干净拆出"未结发票行"概念). 文案改"家"避免误导. -->
                <div class="stat-sub">{{ overview.receivableCount || 0 }} 家客户未结</div>
              </div>
              <div class="stat-card ap">
                <div class="stat-label">应付总额</div>
                <div class="stat-value">{{ formatAmount(overview.totalPayable) }}</div>
                <div class="stat-sub">{{ overview.payableCount || 0 }} 家供应商未结</div>
              </div>
              <div class="stat-card net">
                <div class="stat-label">净额 (应收-应付)</div>
                <div class="stat-value">{{ formatAmount((overview.totalReceivable || 0) - (overview.totalPayable || 0)) }}</div>
                <div class="stat-sub">{{ (overview.totalReceivable || 0) > (overview.totalPayable || 0) ? '净应收' : '净应付' }}</div>
              </div>
              <div class="stat-card overdue">
                <div class="stat-label">逾期金额（应收+应付）</div>
                <div class="stat-value">{{ formatAmount(overdueTotal(overview)) }}</div>
                <div class="stat-sub">{{ overdueCount(overview) }} 笔逾期（应收+应付）</div>
              </div>
            </div>
            <div v-else class="empty">暂无数据</div>
          </template>

          <!-- 应收 -->
          <template #receivable>
            <div class="tab-toolbar">
              <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" @click="txTypeFilter = 'CUSTOMER'; loadTransactions()">刷新</UButton>
            </div>
            <UTable :data="transactions" :columns="txColumns('客户', false)" :loading="loading" class="cs-table" data-testid="arap-receivable-table">
              <template #empty>暂无数据</template>
              <template #transactionType-cell="{ row }">
                <UBadge :color="typeColor(row.original)" variant="soft">{{ typeText(row.original) }}</UBadge>
              </template>
              <template #amount-cell="{ row }"><span class="num">{{ formatAmount(row.original.amount as number) }}</span></template>
              <template #balanceAfter-cell="{ row }">
                <span class="num">{{ formatAmount(row.original.balanceAfter as number) }}</span>
                <UIcon v-if="isAdjustmentRow(row.original) && row.original.approvalStatus === 'PENDING'" name="i-lucide-triangle-alert" class="warn-icon"
                  title="调整待审批, 余额为提交前快照; 审批通过后才会更新" />
              </template>
              <template #approval-cell="{ row }">
                <UBadge v-if="isAdjustmentRow(row.original)" :color="approvalColor(row.original)" variant="soft">{{ approvalText(row.original) }}</UBadge>
                <span v-if="row.original.sourceType === 'RETURN_REFUND_REVERSAL'" class="warn-text">请从退款详情审核</span>
                <span v-if="!isAdjustmentRow(row.original)">-</span>
              </template>
              <template #paymentMethod-cell="{ row }">{{ methodText(row.original.paymentMethod) }}</template>
              <template #source-cell="{ row }">
                <UButton v-if="row.original.sourceType === 'RETURN_ORDER' && validReturnOrderId(row.original.sourceId)" variant="link" size="xs" @click="openSourceReturn(row.original.sourceId)">查看原退货单</UButton>
                <UButton v-else-if="(row.original.sourceType === 'RETURN_REFUND' || row.original.sourceType === 'RETURN_REFUND_REVERSAL') && validRefundRequestId(row.original.sourceId)" variant="link" size="xs" @click="openSourceRefund(row.original)">{{ row.original.sourceType === 'RETURN_REFUND_REVERSAL' ? '查看撤销退款' : '查看退款与流水' }}</UButton>
                <span v-else>-</span>
              </template>
            </UTable>
          </template>

          <!-- 应付 -->
          <template #payable>
            <UTable :data="transactions" :columns="txColumns('供应商', true)" :loading="loading" class="cs-table" data-testid="arap-payable-table">
              <template #empty>暂无数据</template>
              <template #transactionType-cell="{ row }">
                <UBadge :color="typeColor(row.original)" variant="soft">{{ typeText(row.original) }}</UBadge>
              </template>
              <template #amount-cell="{ row }"><span class="num">{{ formatAmount(row.original.amount as number) }}</span></template>
              <template #balanceAfter-cell="{ row }">
                <span class="num">{{ formatAmount(row.original.balanceAfter as number) }}</span>
                <UIcon v-if="isAdjustmentRow(row.original) && row.original.approvalStatus === 'PENDING'" name="i-lucide-triangle-alert" class="warn-icon"
                  title="调整待审批, 余额为提交前快照; 审批通过后才会更新" />
              </template>
              <template #approval-cell="{ row }">
                <UBadge v-if="isAdjustmentRow(row.original)" :color="approvalColor(row.original)" variant="soft">{{ approvalText(row.original) }}</UBadge>
                <span v-if="row.original.sourceType === 'RETURN_REFUND_REVERSAL'" class="warn-text">请从退款详情审核</span>
                <span v-if="!isAdjustmentRow(row.original)">-</span>
              </template>
              <template #paymentMethod-cell="{ row }">{{ methodText(row.original.paymentMethod) }}</template>
              <template #source-cell="{ row }">
                <UButton v-if="row.original.sourceType === 'RETURN_ORDER' && validReturnOrderId(row.original.sourceId)" variant="link" size="xs" @click="openSourceReturn(row.original.sourceId)">查看原退货单</UButton>
                <UButton v-else-if="(row.original.sourceType === 'RETURN_REFUND' || row.original.sourceType === 'RETURN_REFUND_REVERSAL') && validRefundRequestId(row.original.sourceId)" variant="link" size="xs" @click="openSourceRefund(row.original)">{{ row.original.sourceType === 'RETURN_REFUND_REVERSAL' ? '查看撤销退款' : '查看退款与流水' }}</UButton>
                <span v-else>-</span>
              </template>
              <template #actions-cell="{ row }">
                <UButton v-if="canSettlePayable(row.original)" variant="link" size="xs" data-testid="arap-settle" @click="openSettleDialog(row.original)">付款并核销</UButton>
                <span v-else>-</span>
              </template>
            </UTable>
          </template>

          <!-- 账龄 -->
          <template #aging>
            <div class="tab-toolbar">
              <UButton :variant="agingType === 'CUSTOMER' ? 'solid' : 'outline'" :color="agingType === 'CUSTOMER' ? 'primary' : 'neutral'" data-testid="aging-customer" @click="agingType = 'CUSTOMER'; handleAgingTypeChange()">应收账龄</UButton>
              <UButton :variant="agingType === 'SUPPLIER' ? 'solid' : 'outline'" :color="agingType === 'SUPPLIER' ? 'primary' : 'neutral'" data-testid="aging-supplier" @click="agingType = 'SUPPLIER'; handleAgingTypeChange()">应付账龄</UButton>
            </div>
            <UAlert v-if="agingLoading" color="info" variant="soft" icon="i-lucide-info" class="mb-3" title="正在读取账龄分析…" />
            <UAlert v-else-if="agingError" color="error" variant="soft" icon="i-lucide-circle-alert" class="mb-3" :title="agingError" />
            <UAlert v-else color="info" variant="soft" icon="i-lucide-info" class="mb-3" title="账龄按发票剩余未清金额统计；与含退款及撤销的净应收不同。" />
            <UTable v-if="!agingLoading && !agingError" :data="agingData" :columns="agingColumns" class="cs-table" data-testid="arap-aging-table">
              <template #empty>暂无数据</template>
              <template #total-cell="{ row }"><span class="num">{{ formatAmount(row.original.total as number) }}</span></template>
              <template #current-cell="{ row }"><span class="num">{{ formatAmount(row.original.current as number) }}</span></template>
              <template #days1_30-cell="{ row }"><span class="num">{{ formatAmount(row.original.days1_30 as number) }}</span></template>
              <template #days31_60-cell="{ row }"><span class="num">{{ formatAmount(row.original.days31_60 as number) }}</span></template>
              <template #days61_90-cell="{ row }"><span class="num">{{ formatAmount(row.original.days61_90 as number) }}</span></template>
              <template #days91_120-cell="{ row }"><span class="num">{{ formatAmount(row.original.days91_120 as number) }}</span></template>
              <template #days120plus-cell="{ row }">
                <span class="num" :class="{ bad: Number(row.original.days120plus) > 0 }">{{ formatAmount(row.original.days120plus as number) }}</span>
              </template>
            </UTable>
          </template>
        </UTabs>

        <div v-else class="empty" data-testid="arap-no-permission">您没有查看价格/财务数据的权限</div>
      </div>

      <!-- 交易记录分页 -->
      <footer v-if="canViewPrice && (activeTab === 'receivable' || activeTab === 'payable')" class="pager">
        <span class="count">共 {{ txPagination.total }} 条</span>
        <USelect :model-value="txPagination.size" :items="PAGE_SIZE_ITEMS" class="w-28" data-testid="arap-page-size" @update:model-value="(v: unknown) => handleTxSizeChange(Number(v))" />
        <UPagination :page="txPagination.page" :items-per-page="txPagination.size" :total="txPagination.total" show-edges :sibling-count="1" @update:page="handleTxPageChange" />
      </footer>
    </section>

    <!-- 付款并核销 -->
    <UModal
      v-model:open="settleDialogVisible"
      title="付款并核销未清应付"
      description="向供应商付款并核销未清应付"
      :dismissible="false"
      :ui="{ content: 'max-w-[520px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="form">
          <dl v-if="settleRow" class="desc" data-testid="settle-summary">
            <dt>供应商</dt><dd>{{ settleRow.counterpartyName || '-' }}</dd>
            <dt>采购单</dt><dd>{{ settleRow.purchaseOrderId || '-' }}</dd>
            <dt>未清金额</dt><dd>{{ formatAmount(payableOutstanding(settleRow)) }}</dd>
          </dl>
          <UFormField label="付款金额" required>
            <!-- 不绑 :max / 不吸附步长: 超限由 settlePayable 校验并提示 -->
            <UInputNumber v-model="settleForm.amount" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ minimumFractionDigits: 2, maximumFractionDigits: 2 }" class="w-full" data-testid="settle-amount" />
          </UFormField>
          <UFormField label="付款方式" required>
            <USelect v-model="settleForm.paymentMethod" :items="SETTLE_METHOD_ITEMS" class="w-full" />
          </UFormField>
          <UFormField label="付款凭证号" required>
            <UInput v-model="settleForm.paymentReference" :maxlength="100" placeholder="银行流水号或付款凭证号" class="w-full" data-testid="settle-reference" />
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="settleForm.remark" :rows="2" :maxlength="500" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="settleDialogVisible = false">取消</UButton>
        <UButton :loading="settleSubmitting" data-testid="settle-submit" @click="settlePayable">确认付款</UButton>
      </template>
    </UModal>

    <SalesConfirmModal ref="confirmRef" />
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-2); }
.body { padding: var(--cs-space-3) var(--cs-space-5) var(--cs-space-4); }
.alert-actions { display: flex; gap: var(--cs-space-2); }
.pager { display: flex; align-items: center; justify-content: flex-end; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
.form { display: grid; gap: var(--cs-space-3); }
.desc { display: grid; grid-template-columns: 80px 1fr; gap: 6px var(--cs-space-3); margin: 0; font-size: 13px; }
.desc dt { color: var(--cs-muted); }
.desc dd { margin: 0; color: var(--cs-text); word-break: break-all; }
.stat-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--cs-space-4); margin-bottom: var(--cs-space-4); }
.stat-card { text-align: center; padding: var(--cs-space-5); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); background: var(--cs-surface); }
.stat-label { font-size: 13px; color: var(--cs-muted); margin-bottom: 8px; }
.stat-value { font-size: 24px; font-weight: 700; margin-bottom: 4px; font-variant-numeric: tabular-nums; }
.stat-sub { font-size: 12px; color: var(--cs-muted); }
.stat-card.ar .stat-value { color: var(--cs-warning); }
.stat-card.ap .stat-value { color: var(--cs-danger); }
.stat-card.net .stat-value { color: var(--cs-primary); }
.stat-card.overdue .stat-value { color: var(--cs-danger); }
.tab-toolbar { display: flex; gap: var(--cs-space-2); margin-bottom: var(--cs-space-3); }
.empty { padding: 40px 0; text-align: center; color: var(--cs-muted); font-size: 13px; }
.num { font-variant-numeric: tabular-nums; }
.bad { color: var(--cs-danger); }
.warn-icon { color: var(--cs-warning); margin-left: 4px; vertical-align: middle; }
.warn-text { color: var(--cs-warning); font-size: 12px; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
