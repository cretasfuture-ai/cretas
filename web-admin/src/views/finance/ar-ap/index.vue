<script setup lang="ts">
import { ref, computed, watch, onDeactivated, onActivated } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, WarningFilled } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import { enumLabel } from '@/utils/enumDisplay';
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

  try {
    await ElMessageBox.confirm(
      `确认向 ${row.counterpartyName || '该供应商'} 付款 ${formatAmount(settleForm.value.amount)} 并核销此应付？`,
      '确认付款核销',
      { confirmButtonText: '确认付款', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }

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
  RETURN_REFUND_REVERSAL: { text: '退款撤销调整', type: 'info' },
  AP_INVOICE: { text: '应付挂账', type: 'danger' },
  AP_PAYMENT: { text: '供应商付款', type: 'success' },
  AP_ADJUSTMENT: { text: '应付调整', type: 'info' },
};

const paymentMethodMap: Record<string, string> = {
  CASH: '现金', BANK_TRANSFER: '银行转账', WECHAT: '微信', ALIPAY: '支付宝',
  CHECK: '支票', CREDIT: '赊账', POS: 'POS', OTHER: '其他',
};

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
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="page-title">应收应付管理</span>
          <el-button :icon="Refresh" @click="refreshFinancialData">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="hasReturnContext" :title="sourceError || `仅显示退货单 ${sourceReturnNumber || '正在核对'} 的应收调整，保留历次驳回和重提记录`" :type="sourceError ? 'error' : 'info'" :closable="false">
        <el-button v-if="sourceReturnId" link type="primary" @click="openSourceReturn(sourceReturnId)">返回原退货单</el-button>
        <el-button link @click="router.replace({ query: {} })">查看全部流水</el-button>
      </el-alert>
      <el-alert v-if="canViewPrice && overview?.scopeApplied" type="info" :closable="false" show-icon
        title="仅显示当前授权范围内的账目与金额，不代表全工厂余额。" />
      <el-tabs v-if="canViewPrice" v-model="activeTab">
        <!-- 概览 Tab -->
        <el-tab-pane v-if="!hasReturnContext" label="财务概览" name="overview">
          <el-alert v-if="overviewLoading" title="正在读取财务概览…" type="info" :closable="false" show-icon />
          <el-alert v-else-if="overviewError" :title="overviewError" type="error" :closable="false" show-icon />
          <div class="stat-cards" v-else-if="overview">
            <el-card class="stat-card ar" shadow="hover">
              <div class="stat-label">应收总额</div>
              <div class="stat-value">{{ formatAmount(overview.totalReceivable) }}</div>
              <!-- 🟡 F006 采购 audit fix (Bug 2): 后端按交易对手(客户/供应商)聚合欠款笔数, 非单据/发票行数
                   (ar_ap_transactions 是流水式台账, 无法干净拆出"未结发票行"概念). 文案改"家"避免误导. -->
              <div class="stat-sub">{{ overview.receivableCount || 0 }} 家客户未结</div>
            </el-card>
            <el-card class="stat-card ap" shadow="hover">
              <div class="stat-label">应付总额</div>
              <div class="stat-value">{{ formatAmount(overview.totalPayable) }}</div>
              <div class="stat-sub">{{ overview.payableCount || 0 }} 家供应商未结</div>
            </el-card>
            <el-card class="stat-card net" shadow="hover">
              <div class="stat-label">净额 (应收-应付)</div>
              <div class="stat-value">{{ formatAmount((overview.totalReceivable || 0) - (overview.totalPayable || 0)) }}</div>
              <div class="stat-sub">{{ (overview.totalReceivable || 0) > (overview.totalPayable || 0) ? '净应收' : '净应付' }}</div>
            </el-card>
            <el-card class="stat-card overdue" shadow="hover">
              <div class="stat-label">逾期金额（应收+应付）</div>
              <div class="stat-value">{{ formatAmount(overdueTotal(overview)) }}</div>
              <div class="stat-sub">{{ overdueCount(overview) }} 笔逾期（应收+应付）</div>
            </el-card>
          </div>
          <el-empty v-else description="暂无数据" />
        </el-tab-pane>

        <!-- 应收 Tab -->
        <el-tab-pane label="应收账款" name="receivable">
          <div class="tab-toolbar">
            <el-button :icon="Refresh" @click="txTypeFilter = 'CUSTOMER'; loadTransactions()">刷新</el-button>
          </div>
          <el-table empty-text="暂无数据" :data="transactions" border stripe v-loading="loading">
            <el-table-column prop="transactionNumber" label="交易编号" width="150" />
            <el-table-column prop="counterpartyName" label="客户" min-width="140" show-overflow-tooltip />
            <el-table-column prop="transactionType" label="类型" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="(txTypeMap[row.transactionType]?.type) || 'info'" size="small">
                  {{ txTypeMap[row.sourceType === 'RETURN_REFUND_REVERSAL' ? 'RETURN_REFUND_REVERSAL' : row.transactionType]?.text || enumLabel(row.transactionType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="130" align="right">
              <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
            </el-table-column>
            <el-table-column v-if="!overview?.scopeApplied" prop="balanceAfter" label="交易后余额（历史快照）" width="170" align="right">
              <template #default="{ row }">
                {{ formatAmount(row.balanceAfter) }}
                <el-tooltip v-if="isAdjustmentRow(row) && row.approvalStatus === 'PENDING'"
                  content="调整待审批, 余额为提交前快照; 审批通过后才会更新" placement="top">
                  <el-icon style="color:#E6A23C;margin-left:4px;vertical-align:middle"><WarningFilled /></el-icon>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column v-if="transactions.some((r) => isAdjustmentRow(r))" label="审批状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag v-if="isAdjustmentRow(row)" :type="approvalStatusMap[row.approvalStatus]?.type || 'info'" size="small">
                  {{ approvalStatusMap[row.approvalStatus]?.text || enumLabel(row.approvalStatus) }}
                </el-tag>
                <el-text v-if="row.sourceType === 'RETURN_REFUND_REVERSAL'" type="warning" size="small">请从退款详情审核</el-text>
                <span v-if="!isAdjustmentRow(row)">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="paymentMethod" label="支付方式" width="110" align="center">
              <template #default="{ row }">{{ row.paymentMethod ? paymentMethodMap[row.paymentMethod] || enumLabel(row.paymentMethod) : '-' }}</template>
            </el-table-column>
            <el-table-column prop="transactionDate" label="日期" width="120" />
            <el-table-column prop="dueDate" label="到期日" width="120" />
            <el-table-column label="来源单据" width="150"><template #default="{ row }"><el-button v-if="row.sourceType === 'RETURN_ORDER' && validReturnOrderId(row.sourceId)" link type="primary" @click="openSourceReturn(row.sourceId)">查看原退货单</el-button><el-button v-else-if="(row.sourceType === 'RETURN_REFUND' || row.sourceType === 'RETURN_REFUND_REVERSAL') && validRefundRequestId(row.sourceId)" link type="primary" @click="openSourceRefund(row)">{{ row.sourceType === 'RETURN_REFUND_REVERSAL' ? '查看撤销退款' : '查看退款与流水' }}</el-button><span v-else>-</span></template></el-table-column>
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <!-- 应付 Tab -->
        <el-tab-pane v-if="!hasReturnContext" label="应付账款" name="payable">
          <el-table :data="transactions" border stripe v-loading="loading">
            <el-table-column prop="transactionNumber" label="交易编号" width="150" />
            <el-table-column prop="counterpartyName" label="供应商" min-width="140" show-overflow-tooltip />
            <el-table-column prop="transactionType" label="类型" width="120" align="center">
              <template #default="{ row }">
                <el-tag :type="(txTypeMap[row.transactionType]?.type) || 'info'" size="small">
                  {{ txTypeMap[row.sourceType === 'RETURN_REFUND_REVERSAL' ? 'RETURN_REFUND_REVERSAL' : row.transactionType]?.text || enumLabel(row.transactionType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="amount" label="金额" width="130" align="right">
              <template #default="{ row }">{{ formatAmount(row.amount) }}</template>
            </el-table-column>
            <el-table-column v-if="!overview?.scopeApplied" prop="balanceAfter" label="交易后余额（历史快照）" width="170" align="right">
              <template #default="{ row }">
                {{ formatAmount(row.balanceAfter) }}
                <el-tooltip v-if="isAdjustmentRow(row) && row.approvalStatus === 'PENDING'"
                  content="调整待审批, 余额为提交前快照; 审批通过后才会更新" placement="top">
                  <el-icon style="color:#E6A23C;margin-left:4px;vertical-align:middle"><WarningFilled /></el-icon>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column v-if="transactions.some((r) => isAdjustmentRow(r))" label="审批状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag v-if="isAdjustmentRow(row)" :type="approvalStatusMap[row.approvalStatus]?.type || 'info'" size="small">
                  {{ approvalStatusMap[row.approvalStatus]?.text || enumLabel(row.approvalStatus) }}
                </el-tag>
                <el-text v-if="row.sourceType === 'RETURN_REFUND_REVERSAL'" type="warning" size="small">请从退款详情审核</el-text>
                <span v-if="!isAdjustmentRow(row)">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="paymentMethod" label="支付方式" width="110" align="center">
              <template #default="{ row }">{{ row.paymentMethod ? paymentMethodMap[row.paymentMethod] || enumLabel(row.paymentMethod) : '-' }}</template>
            </el-table-column>
            <el-table-column prop="transactionDate" label="日期" width="120" />
            <el-table-column label="来源单据" width="150"><template #default="{ row }"><el-button v-if="row.sourceType === 'RETURN_ORDER' && validReturnOrderId(row.sourceId)" link type="primary" @click="openSourceReturn(row.sourceId)">查看原退货单</el-button><el-button v-else-if="(row.sourceType === 'RETURN_REFUND' || row.sourceType === 'RETURN_REFUND_REVERSAL') && validRefundRequestId(row.sourceId)" link type="primary" @click="openSourceRefund(row)">{{ row.sourceType === 'RETURN_REFUND_REVERSAL' ? '查看撤销退款' : '查看退款与流水' }}</el-button><span v-else>-</span></template></el-table-column>
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            <el-table-column label="操作" width="140" align="center" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="canSettlePayable(row)"
                  type="primary"
                  link
                  size="small"
                  @click="openSettleDialog(row)"
                >付款并核销</el-button>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 账龄 Tab -->
        <el-tab-pane v-if="!hasReturnContext" label="账龄分析" name="aging">
          <div class="tab-toolbar">
            <el-radio-group v-model="agingType" @change="handleAgingTypeChange">
              <el-radio-button value="CUSTOMER">应收账龄</el-radio-button>
              <el-radio-button value="SUPPLIER">应付账龄</el-radio-button>
            </el-radio-group>
          </div>
          <el-alert v-if="agingLoading" title="正在读取账龄分析…" type="info" :closable="false" show-icon style="margin-bottom:12px" />
          <el-alert v-else-if="agingError" :title="agingError" type="error" :closable="false" show-icon style="margin-bottom:12px" />
          <el-alert v-else title="账龄按发票剩余未清金额统计；与含退款及撤销的净应收不同。" type="info" :closable="false" show-icon style="margin-bottom:12px" />
          <el-table v-if="!agingLoading && !agingError" :data="agingData" border stripe>
            <el-table-column prop="counterpartyName" :label="agingType === 'CUSTOMER' ? '客户' : '供应商'" min-width="160" />
            <el-table-column prop="total" label="未清合计" width="130" align="right">
              <template #default="{ row }">{{ formatAmount(row.total) }}</template>
            </el-table-column>
            <el-table-column prop="current" label="未到期" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.current) }}</template>
            </el-table-column>
            <el-table-column prop="days1_30" label="1-30天" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.days1_30) }}</template>
            </el-table-column>
            <el-table-column prop="days31_60" label="31-60天" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.days31_60) }}</template>
            </el-table-column>
            <el-table-column prop="days61_90" label="61-90天" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.days61_90) }}</template>
            </el-table-column>
            <el-table-column prop="days91_120" label="91-120天" width="110" align="right">
              <template #default="{ row }">{{ formatAmount(row.days91_120) }}</template>
            </el-table-column>
            <el-table-column prop="days120plus" label="120天以上" width="110" align="right">
              <template #default="{ row }">
                <span :style="{ color: row.days120plus > 0 ? '#f56c6c' : '' }">{{ formatAmount(row.days120plus) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>

      <el-empty v-else description="您没有查看价格/财务数据的权限" />

      <el-dialog
        v-model="settleDialogVisible"
        title="付款并核销未清应付"
        width="520px"
        :close-on-click-modal="false"
      >
        <el-descriptions v-if="settleRow" :column="1" border style="margin-bottom: 16px">
          <el-descriptions-item label="供应商">{{ settleRow.counterpartyName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="采购单">{{ settleRow.purchaseOrderId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="未清金额">{{ formatAmount(payableOutstanding(settleRow)) }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="100px">
          <el-form-item label="付款金额" required>
            <el-input-number
              v-model="settleForm.amount"
              :min="0.01"
              :max="payableOutstanding(settleRow || {})"
              :precision="2"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="付款方式" required>
            <el-select v-model="settleForm.paymentMethod" style="width: 100%">
              <el-option label="银行转账" value="BANK_TRANSFER" />
              <el-option label="现金" value="CASH" />
              <el-option label="微信" value="WECHAT" />
              <el-option label="支付宝" value="ALIPAY" />
              <el-option label="支票" value="CHECK" />
              <el-option label="其他" value="OTHER" />
            </el-select>
          </el-form-item>
          <el-form-item label="付款凭证号" required>
            <el-input v-model="settleForm.paymentReference" maxlength="100" placeholder="银行流水号或付款凭证号" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="settleForm.remark" type="textarea" :rows="2" maxlength="500" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="settleDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="settleSubmitting" @click="settlePayable">确认付款</el-button>
        </template>
      </el-dialog>

      <!-- 交易记录分页 -->
      <div v-if="canViewPrice && (activeTab === 'receivable' || activeTab === 'payable')" class="pagination-wrapper">
        <el-pagination v-model:current-page="txPagination.page" v-model:page-size="txPagination.size"
          :page-sizes="[10, 20, 50]" :total="txPagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handleTxPageChange" @size-change="handleTxSizeChange" />
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 20px; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .page-title { font-size: 16px; font-weight: 600; color: #303133; }
}
.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  text-align: center;
  padding: 20px;
  border-radius: 8px;
  .stat-label { font-size: 13px; color: #909399; margin-bottom: 8px; }
  .stat-value { font-size: 24px; font-weight: 700; margin-bottom: 4px; }
  .stat-sub { font-size: 12px; color: #b1b3b8; }
  &.ar .stat-value { color: #e6a23c; }
  &.ap .stat-value { color: #f56c6c; }
  &.net .stat-value { color: #409eff; }
  &.overdue .stat-value { color: #f56c6c; }
}
.tab-toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
</style>
