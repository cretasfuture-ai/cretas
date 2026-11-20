<script setup lang="ts">
import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
/**
 * 调整审批 — PENDING ar_ap_transactions adjustment 队列
 *
 * R28 P2 (R23 P5 deferred): admin UI for the PENDING_APPROVAL workflow added
 * in R23 (commits ee78b0495 + V20260426_01 migration). Backend supports:
 *   - GET  /api/mobile/{factoryId}/finance/adjustments/pending  (list, with R29 P1 filters)
 *   - POST /api/mobile/{factoryId}/finance/adjustment/{txnId}/approve
 *   - POST /api/mobile/{factoryId}/finance/adjustment/{txnId}/reject
 *   - POST /api/mobile/{factoryId}/finance/adjustments/batch-approve  (R29 P2)
 *   - POST /api/mobile/{factoryId}/finance/adjustments/batch-reject   (R29 P2)
 *
 * R29 P1+P2 additions:
 *   - Filter bar: counterpartyType / amount range / date range
 *   - Multi-select + bulk approve / bulk reject (≤100 rows per call)
 *   - Per-id outcome rendering (succeeded count + failures list)
 *
 * 4 眼原则: backend rejects approve when approver === submitter (ArApServiceImpl
 * line 364-366). FE additionally hides single-row 审批 button when row.operatedBy
 * === current userId. Bulk approve relies on backend per-id reject (4-eye
 * violations show up in failures list with reason).
 */
import { ref, computed, watch, onBeforeUnmount, onActivated, onDeactivated } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { getReturnFinancialFollowUp, validReturnOrderId } from '@/api/returnFinancialFollowUp';
import { getReturnRefundByRequest } from '@/api/inventory';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { CsTableColumn } from '@/types/csTable';
// 通用确认弹窗 (UModal, 取代 ElMessageBox: Element 的 MessageBox 在 Nuxt UI 弹层之上点不了)。
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import { useSelfApprovalSwitch } from '@/composables/useSelfApprovalSwitch';
import { isSelfApproval, validateSelfApprovalReason } from '@/utils/selfApproval';
import SelfApprovalReasonField from '@/components/approval/SelfApprovalReasonField.vue';

interface AdjustmentRow {
  id: string;
  factoryId: string;
  transactionType: 'AR_ADJUSTMENT' | 'AP_ADJUSTMENT';
  counterpartyType: 'CUSTOMER' | 'SUPPLIER';
  counterpartyId: string;
  counterpartyName?: string;
  amount: number | null;
  balanceAfter: number | null;
  approvalStatus: 'PENDING' | 'APPROVED' | 'REJECTED';
  operatedBy: number;
  remark?: string;
  sourceType?: string;
  sourceId?: string;
  createdAt: string;
}

interface BatchOutcome {
  requested: number;
  approvedCount?: number;
  rejectedCount?: number;
  failedCount: number;
  failures: Array<{ id: string; reason: string }>;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const currentUserId = computed(() => authStore.user?.id);
// 红线 4: 本厂开了「允许本人审批」时, 提交人可以审批自己的调整, 但必须写原因 (后端仍按开关把关)
const { allowSelfApproval } = useSelfApprovalSwitch(factoryId);
// 本人审批原因对话框 (防呆 Rule 3: 常见原因下拉, 选「其他」才必须补充)
const selfReasonDialog = ref<{ open: boolean; row: AdjustmentRow | null; reason: string; error: string }>(
  { open: false, row: null, reason: '', error: '' });
let resolveSelfReason: ((reason: string | null) => void) | null = null;
function askSelfApprovalReason(row: AdjustmentRow): Promise<string | null> {
  selfReasonDialog.value = { open: true, row, reason: '', error: '' };
  return new Promise(resolve => { resolveSelfReason = resolve; });
}
function closeSelfReasonDialog(reason: string | null) {
  selfReasonDialog.value.open = false;
  const resolve = resolveSelfReason;
  resolveSelfReason = null;
  resolve?.(reason);
}
function confirmSelfReason() {
  const invalid = validateSelfApprovalReason(selfReasonDialog.value.reason);
  if (invalid) {
    selfReasonDialog.value.error = invalid;
    return;
  }
  closeSelfReasonDialog(selfReasonDialog.value.reason.trim());
}
const canApprove = computed(() => permissionStore.canWrite('finance'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const route = useRoute();
const router = useRouter();
const hasReturnContext = computed(() => route?.query.returnOrderId != null);
const sourceReturnId = computed(() => validReturnOrderId(route?.query.returnOrderId));
const sourceReturnNumber = ref('');
const sourceError = ref('');
function openSourceReturn(id: unknown) {
  const valid = validReturnOrderId(id);
  if (valid) void router.push(`/finance/return-refunds/${encodeURIComponent(valid)}`);
}
function openSourceLedger() {
  if (sourceReturnId.value) void router.push({ path: '/finance/ar-ap', query: { returnOrderId: sourceReturnId.value } });
}

function isRefundReversalRow(row: AdjustmentRow): boolean {
  return row.sourceType === 'RETURN_REFUND_REVERSAL';
}

function validRefundRequestId(id: unknown): string | null {
  if (Array.isArray(id)) return null;
  const value = String(id ?? '').trim();
  return value && value !== 'undefined' && value !== 'null' ? value : null;
}

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const loading = ref(false);
const tableData = ref<AdjustmentRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const selectedRows = ref<AdjustmentRow[]>([]);

// R29 P1 filter state (USelect 不允许空字符串值, 「全部」用哨兵值 ALL)
const ALL = 'ALL';
const TYPE_ITEMS = [
  { label: '全部对手方', value: ALL },
  { label: '客户 (AR)', value: 'CUSTOMER' },
  { label: '供应商 (AP)', value: 'SUPPLIER' },
];
const PAGE_SIZE_ITEMS = [20, 50, 100].map(n => ({ label: `${n} 条/页`, value: n }));
const filterType = ref<string>(ALL);
const filterMinAmount = ref<number | null>(null);
const filterMaxAmount = ref<number | null>(null);
const filterFromDate = ref('');
const filterToDate = ref('');

// A pending row contains a submission snapshot, not a posted balance.
// Account/factory switches and newer list requests invalidate older responses.
let contextVersion = 0;
let listRequestVersion = 0;
let sourceNavigationVersion = 0;
let active = true;
let approvalConfirmationOpen = false;
function requestContext() {
  return { factoryId: factoryId.value, userId: currentUserId.value, version: contextVersion };
}
function isCurrentContext(context: ReturnType<typeof requestContext>) {
  return active && context.version === contextVersion && context.factoryId === factoryId.value && context.userId === currentUserId.value;
}

async function openSourceRefund(row: AdjustmentRow) {
  const requestId = validRefundRequestId(row.sourceId);
  const ledgerId = String(row.id || '').trim();
  const context = requestContext();
  const sequence = ++sourceNavigationVersion;
  if (!requestId || !ledgerId || !context.factoryId || !isRefundReversalRow(row)) {
    ElMessage.error('撤销退款来源无效，请从原退款详情重新进入');
    return;
  }
  try {
    const refundView = await getReturnRefundByRequest(context.factoryId, requestId);
    if (!isCurrentContext(context) || sequence !== sourceNavigationVersion) return;
    if (refundView.returnOrderId == null || !String(refundView.returnOrderId).trim()) {
      throw new Error('退款申请缺少退货单，无法定位撤销详情');
    }
    if (refundView.request?.id !== requestId) {
      throw new Error('退款申请与当前撤销流水不一致，请重新读取');
    }
    if (refundView.request.reversal?.ledger?.id !== ledgerId) {
      throw new Error('撤销流水与当前待审记录不一致，请重新读取');
    }
    await router.push({
      path: `/finance/return-refunds/${encodeURIComponent(refundView.returnOrderId)}`,
      query: { refundRequestId: requestId, ledgerId },
    });
  } catch (error) {
    if (isCurrentContext(context) && sequence === sourceNavigationVersion) {
      ElMessage.error(error instanceof Error ? error.message : '撤销退款申请读取失败，请重试');
    }
  }
}

async function loadData() {
  const context = requestContext();
  const requestVersion = ++listRequestVersion;
  if (!context.factoryId) return;
  loading.value = true;
  try {
    if (hasReturnContext.value) {
      const returnId = sourceReturnId.value;
      if (!returnId) throw new Error('退货单链接无效，请从原单据重新进入');
      const followUp = await getReturnFinancialFollowUp(context.factoryId, returnId);
      if (!isCurrentContext(context) || requestVersion !== listRequestVersion) return;
      sourceError.value = '';
      sourceReturnNumber.value = followUp.returnNumber;
      const rows = followUp.adjustments.filter(row => row.approvalStatus === 'PENDING');
      const begin = (pagination.value.page - 1) * pagination.value.size;
      tableData.value = rows.slice(begin, begin + pagination.value.size);
      pagination.value.total = rows.length;
      return;
    }
    const params: Record<string, unknown> = { page: pagination.value.page, size: pagination.value.size };
    if (filterType.value && filterType.value !== ALL) params.counterpartyType = filterType.value;
    if (filterMinAmount.value != null) params.minAmount = filterMinAmount.value;
    if (filterMaxAmount.value != null) params.maxAmount = filterMaxAmount.value;
    if (filterFromDate.value) params.fromDate = filterFromDate.value;
    if (filterToDate.value) params.toDate = filterToDate.value;
    const res = await get<{ content: AdjustmentRow[]; totalElements: number }>(
      `/${context.factoryId}/finance/adjustments/pending`,
      { params }
    );
    if (isCurrentContext(context) && requestVersion === listRequestVersion && res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
    }
  } catch (error) {
    if (hasReturnContext.value && isCurrentContext(context) && requestVersion === listRequestVersion) {
      sourceError.value = error instanceof Error ? error.message : '退货待审调整读取失败';
      tableData.value = [];
      pagination.value.total = 0;
    }
    // Interceptor toasts; this catch silences the unhandled-promise log.
  } finally {
    if (isCurrentContext(context) && requestVersion === listRequestVersion) {
      loading.value = false;
      selectedRows.value = [];
    }
  }
}

function handleResetFilters() {
  filterType.value = ALL;
  filterMinAmount.value = null;
  filterMaxAmount.value = null;
  filterFromDate.value = '';
  filterToDate.value = '';
  pagination.value.page = 1;
  loadData();
}

function handleApplyFilter() {
  pagination.value.page = 1;
  loadData();
}

async function handleApprove(row: AdjustmentRow) {
  const context = requestContext();
  if (isRefundReversalRow(row)) {
    ElMessage.warning('请从原退款详情审核');
    return;
  }
  if (!context.factoryId || row.factoryId !== context.factoryId || !canShowApproveBtn(row) || row.approvalStatus !== 'PENDING') return;
  try {
    approvalConfirmationOpen = true;
    const financialDetails = canViewPrice.value
      ? `\n金额: ${formatAmount(row.amount, row.counterpartyType)}\n提交时余额: ${formatBalance(row.balanceAfter)}`
      : '';
    const confirmed = await confirmRef.value!.ask({
      title: '审批通过',
      message: `确认审批通过该调整？\n状态: 待审批，审批前未计入余额\n对手方: ${row.counterpartyName || row.counterpartyId}${financialDetails}\n余额以审批结果为准`,
      confirmText: '确认审批',
    });
    approvalConfirmationOpen = false;
    if (confirmed === null || !isCurrentContext(context) || !canShowApproveBtn(row)) return;
    let body: { selfApprovalReason: string } | undefined;
    if (isSelfApproval(row.operatedBy, currentUserId.value)) {
      const selfReason = await askSelfApprovalReason(row);
      if (selfReason === null || !isCurrentContext(context) || !canShowApproveBtn(row)) return;
      body = { selfApprovalReason: selfReason };
    }
    const approveUrl = `/${context.factoryId}/finance/adjustment/${row.id}/approve`;
    // 不是本人审批时请求与改动前完全一致 (不带 body)
    const res = body
      ? await post<Partial<AdjustmentRow>>(approveUrl, body)
      : await post<Partial<AdjustmentRow>>(approveUrl);
    if (!isCurrentContext(context)) return;
    const result = res.data;
    if (res.success && result?.id === row.id && result.approvalStatus === 'APPROVED' &&
        (result.factoryId == null || result.factoryId === context.factoryId)) {
      ElMessage.success(canViewPrice.value
        ? `已审批通过，本次审批后余额: ${formatBalance(result.balanceAfter)}`
        : '已审批通过');
    } else {
      ElMessage.warning('审批结果待刷新核验，请勿重复提交');
    }
    loadData();
  } catch (e) {
    if (!isCurrentContext(context)) return;
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && !err.actionHint)) {
      ElMessage.error('审批失败');
    }
    if (err?.status === 409) loadData();
  } finally {
    approvalConfirmationOpen = false;
  }
}

async function handleReject(row: AdjustmentRow) {
  const context = requestContext();
  if (isRefundReversalRow(row)) {
    ElMessage.warning('请从原退款详情审核');
    return;
  }
  if (!context.factoryId || row.factoryId !== context.factoryId || !canApprove.value || row.approvalStatus !== 'PENDING') return;
  try {
    const reason = await confirmRef.value!.ask({
      title: '驳回调整', message: '请输入驳回原因', confirmText: '确认驳回', danger: true,
      input: { label: '驳回原因', placeholder: '请输入驳回原因', required: true, pattern: /.+/, errorText: '请输入驳回原因' },
    });
    if (reason === null || !isCurrentContext(context) || isRefundReversalRow(row)) return;
    await post(`/${context.factoryId}/finance/adjustment/${row.id}/reject?reason=${encodeURIComponent(reason)}`);
    if (!isCurrentContext(context)) return;
    ElMessage.success('已驳回');
    loadData();
  } catch (e) {
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && !err.actionHint)) {
      ElMessage.error('驳回失败');
    }
    if (err?.status === 409) loadData();
  }
}

// R29 P2 bulk operations
async function handleBatchApprove() {
  const context = requestContext();
  if (!context.factoryId || selectedRows.value.length === 0) return;
  const reservedRows = selectedRows.value.filter(isRefundReversalRow);
  if (reservedRows.length > 0) {
    selectedRows.value = selectedRows.value.filter(row => !isRefundReversalRow(row));
    ElMessage.warning('请从原退款详情审核');
  }
  if (selectedRows.value.length === 0) return;
  // Pre-filter own submissions client-side (4-eye) to avoid known-failures cluttering result
  const eligibleRows = selectedRows.value.filter(r => r.factoryId === context.factoryId && r.approvalStatus === 'PENDING');
  const ownIds = eligibleRows.filter(r => r.operatedBy === currentUserId.value).map(r => r.id);
  const eligibleIds = eligibleRows.filter(r => r.operatedBy !== currentUserId.value).map(r => r.id);
  if (eligibleIds.length === 0) {
    ElMessage.warning(`选中的 ${selectedRows.value.length} 条均为您本人提交,无法审批 (4 眼原则)`);
    return;
  }
  let confirmMsg = `确认批量审批通过 ${eligibleIds.length} 条调整？`;
  if (ownIds.length > 0) confirmMsg += `\n(已自动跳过 ${ownIds.length} 条您本人提交)`;
  try {
    const confirmed = await confirmRef.value!.ask({ title: '批量审批', message: confirmMsg, confirmText: '确认批量审批' });
    if (confirmed === null || !isCurrentContext(context)) return;
    const res = await post<BatchOutcome>(
      `/${context.factoryId}/finance/adjustments/batch-approve`,
      { ids: eligibleIds }
    );
    if (res.success && res.data) {
      const { approvedCount, failedCount, failures } = res.data;
      if (failedCount === 0) {
        ElMessage.success(`批量审批成功: ${approvedCount} 条`);
      } else {
        showBatchResult('批量审批部分失败', approvedCount ?? 0, failedCount, failures);
      }
      loadData();
    }
  } catch (e) {
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && !err.actionHint)) {
      ElMessage.error('批量审批失败');
    }
  }
}

async function handleBatchReject() {
  const context = requestContext();
  if (!context.factoryId || selectedRows.value.length === 0) return;
  const reservedRows = selectedRows.value.filter(isRefundReversalRow);
  if (reservedRows.length > 0) {
    selectedRows.value = selectedRows.value.filter(row => !isRefundReversalRow(row));
    ElMessage.warning('请从原退款详情审核');
  }
  const eligibleRows = selectedRows.value.filter(r => r.factoryId === context.factoryId && r.approvalStatus === 'PENDING');
  if (eligibleRows.length === 0) return;
  const ids = eligibleRows.map(r => r.id);
  try {
    const reason = await confirmRef.value!.ask({
      title: '批量驳回', message: `请输入批量驳回原因 (将应用于全部 ${ids.length} 条)`, confirmText: '确认批量驳回', danger: true,
      input: { label: '驳回原因', placeholder: '请输入驳回原因', required: true, pattern: /.+/, errorText: '请输入驳回原因' },
    });
    if (reason === null || !isCurrentContext(context)) return;
    const res = await post<BatchOutcome>(
      `/${context.factoryId}/finance/adjustments/batch-reject`,
      { ids, reason }
    );
    if (res.success && res.data) {
      const { rejectedCount, failedCount, failures } = res.data;
      if (failedCount === 0) {
        ElMessage.success(`批量驳回成功: ${rejectedCount} 条`);
      } else {
        showBatchResult('批量驳回部分失败', rejectedCount ?? 0, failedCount, failures);
      }
      loadData();
    }
  } catch (e) {
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && !err.actionHint)) {
      ElMessage.error('批量驳回失败');
    }
  }
}

function isSelectable(row: AdjustmentRow): boolean {
  return !isRefundReversalRow(row);
}
function isSelected(row: AdjustmentRow): boolean {
  return selectedRows.value.some(r => r.id === row.id);
}
function toggleRow(row: AdjustmentRow, on: boolean) {
  if (!isSelectable(row)) { ElMessage.warning('请从原退款详情审核'); return; }
  selectedRows.value = on
    ? (isSelected(row) ? selectedRows.value : [...selectedRows.value, row])
    : selectedRows.value.filter(r => r.id !== row.id);
}
const selectableRows = computed(() => tableData.value.filter(isSelectable));
const allSelected = computed(() => selectableRows.value.length > 0 && selectableRows.value.every(isSelected));
function toggleAll(on: boolean) {
  selectedRows.value = on ? [...selectableRows.value] : [];
}

// 批量操作部分失败明细 (取代 ElMessageBox.alert)
const batchResult = ref<{ open: boolean; title: string; ok: number; failed: number; lines: string[] }>(
  { open: false, title: '', ok: 0, failed: 0, lines: [] });
function showBatchResult(title: string, ok: number, failed: number, failures: Array<{ id: string; reason: string }>) {
  batchResult.value = { open: true, title, ok, failed, lines: failures.map(f => `${f.id.slice(0, 8)}: ${f.reason}`) };
}

const columns = computed<CsTableColumn<AdjustmentRow>[]>(() => {
  const cols: CsTableColumn<AdjustmentRow>[] = [
    { id: 'select', header: '' },
    { id: 'id', header: 'ID' },
    { id: 'type', header: '类型' },
    { id: 'counterparty', header: '对手方' },
  ];
  // 金额 / 余额: 无查看价格权限整列去掉 (不显示 0)
  if (canViewPrice.value) {
    cols.push({ id: 'amount', header: '金额', meta: { class: { th: 'text-right', td: 'text-right' } } });
    cols.push({ id: 'balanceAfter', header: '提交时余额 (待审批)', meta: { class: { th: 'text-right', td: 'text-right' } } });
  }
  cols.push(
    { id: 'source', header: '来源单据' },
    { id: 'remark', header: '备注' },
    { id: 'operatedBy', header: '提交人 ID' },
    { id: 'createdAt', header: '提交时间' },
    { id: 'actions', header: '操作' },
  );
  return cols;
});

// 4-eye gate: hide approve button if current user is the submitter —— 除非本厂开了「允许本人审批」(须写原因)。
function canShowApproveBtn(row: AdjustmentRow): boolean {
  return canApprove.value && !isRefundReversalRow(row)
    && (row.operatedBy !== currentUserId.value || allowSelfApproval.value);
}

function isKnownAmount(amount: unknown): amount is number {
  return typeof amount === 'number' && Number.isFinite(amount);
}

function formatBalance(amount: unknown): string {
  return isKnownAmount(amount) ? `${amount.toFixed(2)} 元` : '未知';
}

function formatAmount(amount: number | null, type: 'CUSTOMER' | 'SUPPLIER'): string {
  const label = type === 'CUSTOMER' ? '应收' : '应付';
  if (!isKnownAmount(amount)) return `${label} 未知`;
  const sign = amount >= 0 ? '+' : '';
  return `${label} ${sign}${amount.toFixed(2)} 元`;
}

function formatType(t: string): string {
  return t === 'AR_ADJUSTMENT' ? 'AR调整' : 'AP调整';
}

function formatDate(s: string): string {
  if (!s) return '';
  return new Date(s).toLocaleString('zh-CN', { hour12: false });
}

function handleSizeChange(val: unknown) { pagination.value.size = Number(val); pagination.value.page = 1; loadData(); }
function handlePageChange(val: number) { pagination.value.page = val; loadData(); }

watch([factoryId, currentUserId, () => route?.query.returnOrderId], () => {
  ++contextVersion;
  ++listRequestVersion;
  if (approvalConfirmationOpen) confirmRef.value?.close();
  tableData.value = [];
  selectedRows.value = [];
  pagination.value.page = 1;
  pagination.value.total = 0;
  sourceReturnNumber.value = '';
  sourceError.value = '';
  loading.value = false;
  loadData();
}, { immediate: true, flush: 'sync' });

let activations = 0;
onActivated(() => {
  active = true;
  if (activations++ > 0) void loadData();
});
onDeactivated(() => {
  active = false;
  ++contextVersion;
  ++listRequestVersion;
  if (approvalConfirmationOpen) confirmRef.value?.close();
});

onBeforeUnmount(() => {
  active = false;
  ++contextVersion;
  if (approvalConfirmationOpen) confirmRef.value?.close();
});
</script>

<template>
  <div class="page-wrapper" data-testid="finance-adjustments-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>调整审批</h2>
        <span class="count">待审批: {{ pagination.total }} 条</span>
        <div class="head-actions">
          <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="loading" data-testid="adjustment-refresh" @click="loadData">刷新</UButton>
        </div>
      </header>

      <UAlert
        color="warning" variant="soft" icon="i-lucide-triangle-alert" class="mx-5 mt-3"
        title="4 眼原则"
        description="审批人必须与提交人不同。系统会自动隐藏您自己提交的调整记录的「审批」按钮 (但驳回仍可操作)。批量审批时,您本人的记录会被自动跳过,不计入失败。"
      />

      <UAlert v-if="hasReturnContext" class="mx-5 mt-3" :color="sourceError ? 'error' : 'info'" variant="soft" icon="i-lucide-info"
        :title="sourceError || `仅显示退货单 ${sourceReturnNumber || '正在核对'} 的待审批调整`">
        <template #description>
          <div class="row-actions">
            <UButton v-if="sourceReturnId" variant="link" size="xs" @click="openSourceReturn(sourceReturnId)">返回原退货单</UButton>
            <UButton v-if="sourceReturnId" variant="link" size="xs" @click="openSourceLedger">查看本单全部调整记录</UButton>
            <UButton variant="link" size="xs" color="neutral" @click="router.replace({ query: {} })">查看全部待审调整</UButton>
          </div>
        </template>
      </UAlert>

      <div v-if="!hasReturnContext" class="filter-bar">
        <USelect v-model="filterType" :items="TYPE_ITEMS" class="w-40" data-testid="adjustment-type-filter" />
        <UInputNumber v-model="filterMinAmount" placeholder="最小金额" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ minimumFractionDigits: 0, maximumFractionDigits: 2 }" class="w-36" data-testid="adjustment-min-amount" />
        <span class="muted">至</span>
        <UInputNumber v-model="filterMaxAmount" placeholder="最大金额" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ minimumFractionDigits: 0, maximumFractionDigits: 2 }" class="w-36" data-testid="adjustment-max-amount" />
        <UInput v-model="filterFromDate" type="date" class="w-40" data-testid="adjustment-from-date" />
        <span class="muted">至</span>
        <UInput v-model="filterToDate" type="date" class="w-40" data-testid="adjustment-to-date" />
        <UButton data-testid="adjustment-filter" @click="handleApplyFilter">筛选</UButton>
        <UButton color="neutral" variant="outline" @click="handleResetFilters">重置</UButton>
      </div>

      <div v-if="selectedRows.length > 0" class="bulk-bar" data-testid="adjustment-bulk-bar">
        已选中 <strong>{{ selectedRows.length }}</strong> 条
        <UButton size="sm" color="success" icon="i-lucide-check" :disabled="!canApprove" data-testid="adjustment-batch-approve" @click="handleBatchApprove">批量审批</UButton>
        <UButton size="sm" color="error" icon="i-lucide-x" :disabled="!canApprove" data-testid="adjustment-batch-reject" @click="handleBatchReject">批量驳回</UButton>
      </div>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="adjustment-table">
        <template #empty><ListEmptyExplain empty-title="没有待审批的调整" empty-hint="成本、价格或库存调整提交后会出现在这里等待审批。" /></template>
        <template #select-header>
          <UCheckbox :model-value="allSelected" :disabled="selectableRows.length === 0" data-testid="adjustment-select-all" @update:model-value="toggleAll(!!$event)" />
        </template>
        <template #select-cell="{ row }">
          <UCheckbox :model-value="isSelected(row.original)" :disabled="!isSelectable(row.original)" data-testid="adjustment-select-row" @update:model-value="toggleRow(row.original, !!$event)" />
        </template>
        <template #id-cell="{ row }"><span class="mono">{{ row.original.id.slice(0, 8) }}</span></template>
        <template #type-cell="{ row }">
          <UBadge :color="row.original.transactionType === 'AR_ADJUSTMENT' ? 'success' : 'warning'" variant="soft">
            {{ isRefundReversalRow(row.original) ? '退款撤销调整' : formatType(row.original.transactionType) }}
          </UBadge>
        </template>
        <template #counterparty-cell="{ row }">{{ row.original.counterpartyName || row.original.counterpartyId }}</template>
        <template #amount-cell="{ row }">
          <span :class="isKnownAmount(row.original.amount) && row.original.amount > 0 ? 'amt-pos' : 'amt-neg'">{{ formatAmount(row.original.amount, row.original.counterpartyType) }}</span>
        </template>
        <template #balanceAfter-cell="{ row }">{{ formatBalance(row.original.balanceAfter) }}</template>
        <template #source-cell="{ row }">
          <UButton v-if="row.original.sourceType === 'RETURN_ORDER' && validReturnOrderId(row.original.sourceId)" variant="link" size="xs" @click="openSourceReturn(row.original.sourceId)">查看原退货单</UButton>
          <UButton v-else-if="isRefundReversalRow(row.original) && validRefundRequestId(row.original.sourceId)" variant="link" size="xs" @click="openSourceRefund(row.original)">查看撤销退款</UButton>
          <span v-else>-</span>
        </template>
        <template #remark-cell="{ row }">{{ row.original.remark || '' }}</template>
        <template #operatedBy-cell="{ row }">
          <span :class="row.original.operatedBy === currentUserId ? 'own' : ''">
            {{ row.original.operatedBy }}
            <small v-if="row.original.operatedBy === currentUserId" class="own-note">(您本人)</small>
          </span>
        </template>
        <template #createdAt-cell="{ row }">{{ formatDate(row.original.createdAt) }}</template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton v-if="canShowApproveBtn(row.original)" size="xs" color="success" icon="i-lucide-check" data-testid="adjustment-approve" @click="handleApprove(row.original)">审批</UButton>
            <UButton v-if="canApprove && !isRefundReversalRow(row.original)" size="xs" color="error" icon="i-lucide-x" data-testid="adjustment-reject" @click="handleReject(row.original)">驳回</UButton>
            <span v-if="isRefundReversalRow(row.original)" class="warn-text">待审批；请从原退款详情审核</span>
            <span v-if="!canApprove && !isRefundReversalRow(row.original)" class="muted-small">无权限</span>
          </div>
        </template>
      </UTable>

      <footer v-if="pagination.total > 0" class="pager">
        <span class="muted">共 {{ pagination.total }} 条</span>
        <USelect :model-value="pagination.size" :items="PAGE_SIZE_ITEMS" class="w-28" data-testid="adjustment-page-size" @update:model-value="handleSizeChange" />
        <UPagination :page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </footer>
    </section>

    <!-- 本人审批原因 (防呆 Rule 3) -->
    <UModal
      :open="selfReasonDialog.open"
      :title="`本人审批 — ${selfReasonDialog.row?.counterpartyName || selfReasonDialog.row?.counterpartyId || ''}`"
      description="本人审批须写明原因"
      :ui="{ content: 'max-w-[520px]', description: 'sr-only', footer: 'justify-end' }"
      @update:open="(o: boolean) => { if (!o) closeSelfReasonDialog(null); }"
    >
      <template #body>
        <div data-testid="adjustment-self-approval-dialog">
          <SelfApprovalReasonField v-model="selfReasonDialog.reason" action-label="审批" />
          <div v-if="selfReasonDialog.error" class="err">{{ selfReasonDialog.error }}</div>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="closeSelfReasonDialog(null)">取消</UButton>
        <UButton data-testid="adjustment-self-approval-confirm" @click="confirmSelfReason">确认本人审批</UButton>
      </template>
    </UModal>

    <!-- 批量操作部分失败明细 -->
    <UModal
      v-model:open="batchResult.open"
      :title="batchResult.title"
      description="批量操作结果"
      :ui="{ content: 'max-w-[520px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div data-testid="adjustment-batch-result">
          <p>成功: {{ batchResult.ok }} 条</p>
          <p>失败: {{ batchResult.failed }} 条</p>
          <p class="mt-2">失败明细:</p>
          <ul class="fail-list">
            <li v-for="(l, i) in batchResult.lines" :key="i">{{ l }}</li>
          </ul>
        </div>
      </template>
      <template #footer>
        <UButton @click="batchResult.open = false">知道了</UButton>
      </template>
    </UModal>

    <SalesConfirmModal ref="confirmRef" />
  </div>
</template>

<style scoped>
.page-wrapper { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-2); }
.filter-bar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-2); padding: var(--cs-space-3) var(--cs-space-5); }
.bulk-bar { display: flex; align-items: center; gap: 8px; margin: 0 var(--cs-space-5) 12px; padding: 8px 12px; border: 1px solid var(--cs-border); border-radius: 4px; background: var(--cs-surface-subtle); }
.pager { display: flex; align-items: center; justify-content: flex-end; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
.row-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--cs-space-2); }
.mono { font-family: monospace; font-size: 12px; }
.muted { color: var(--cs-muted); }
.muted-small { color: var(--cs-muted); font-size: 12px; }
.amt-pos { color: var(--cs-success); font-weight: 600; }
.amt-neg { color: var(--cs-danger); font-weight: 600; }
.own, .own-note { color: var(--cs-warning); }
.own-note { display: block; }
.warn-text { color: var(--cs-warning); font-size: 12px; }
.err { color: var(--cs-danger); margin-top: 8px; }
.fail-list { margin: 4px 0 0; padding-left: 18px; font-size: 13px; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 4px; color: var(--cs-text); border-color: var(--cs-border); }
</style>
