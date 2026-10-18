<script setup lang="ts">
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
import { ElMessage, ElMessageBox } from 'element-plus';
import { Check, Close, Refresh } from '@element-plus/icons-vue';

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

const loading = ref(false);
const tableData = ref<AdjustmentRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const selectedRows = ref<AdjustmentRow[]>([]);

// R29 P1 filter state
const filterType = ref<'CUSTOMER' | 'SUPPLIER' | ''>('');
const filterMinAmount = ref<number | null>(null);
const filterMaxAmount = ref<number | null>(null);
const filterDateRange = ref<[string, string] | null>(null);

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
    if (filterType.value) params.counterpartyType = filterType.value;
    if (filterMinAmount.value != null) params.minAmount = filterMinAmount.value;
    if (filterMaxAmount.value != null) params.maxAmount = filterMaxAmount.value;
    if (filterDateRange.value && filterDateRange.value[0]) params.fromDate = filterDateRange.value[0];
    if (filterDateRange.value && filterDateRange.value[1]) params.toDate = filterDateRange.value[1];
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
  filterType.value = '';
  filterMinAmount.value = null;
  filterMaxAmount.value = null;
  filterDateRange.value = null;
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
    await ElMessageBox.confirm(
      `确认审批通过该调整？\n状态: 待审批，审批前未计入余额\n对手方: ${row.counterpartyName || row.counterpartyId}${financialDetails}\n余额以审批结果为准`,
      '审批通过',
      { type: 'warning', confirmButtonText: '确认审批', cancelButtonText: '取消' }
    );
    approvalConfirmationOpen = false;
    if (!isCurrentContext(context) || !canShowApproveBtn(row)) return;
    const res = await post<Partial<AdjustmentRow>>(`/${context.factoryId}/finance/adjustment/${row.id}/approve`);
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
    if (!isCurrentContext(context) || e === 'cancel' || e === 'close') return;
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
    const { value: reason } = await ElMessageBox.prompt('请输入驳回原因', '驳回调整', {
      confirmButtonText: '确认驳回', cancelButtonText: '取消',
      inputPattern: /.+/, inputErrorMessage: '请输入驳回原因'
    });
    if (!isCurrentContext(context) || isRefundReversalRow(row)) return;
    await post(`/${context.factoryId}/finance/adjustment/${row.id}/reject?reason=${encodeURIComponent(reason)}`);
    if (!isCurrentContext(context)) return;
    ElMessage.success('已驳回');
    loadData();
  } catch (e) {
    if (e === 'cancel') return;
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
    await ElMessageBox.confirm(confirmMsg, '批量审批', {
      type: 'warning', confirmButtonText: '确认批量审批', cancelButtonText: '取消'
    });
    if (!isCurrentContext(context)) return;
    const res = await post<BatchOutcome>(
      `/${context.factoryId}/finance/adjustments/batch-approve`,
      { ids: eligibleIds }
    );
    if (res.success && res.data) {
      const { approvedCount, failedCount, failures } = res.data;
      if (failedCount === 0) {
        ElMessage.success(`批量审批成功: ${approvedCount} 条`);
      } else {
        ElMessageBox.alert(
          `成功: ${approvedCount} 条\n失败: ${failedCount} 条\n\n失败明细:\n${failures.map(f => `  - ${f.id.slice(0, 8)}: ${f.reason}`).join('\n')}`,
          '批量审批部分失败',
          { type: 'warning', customClass: 'whitespace-pre' }
        );
      }
      loadData();
    }
  } catch (e) {
    if (e === 'cancel') return;
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
    const { value: reason } = await ElMessageBox.prompt(
      `请输入批量驳回原因 (将应用于全部 ${ids.length} 条)`,
      '批量驳回',
      { confirmButtonText: '确认批量驳回', cancelButtonText: '取消',
        inputPattern: /.+/, inputErrorMessage: '请输入驳回原因' }
    );
    if (!isCurrentContext(context)) return;
    const res = await post<BatchOutcome>(
      `/${context.factoryId}/finance/adjustments/batch-reject`,
      { ids, reason }
    );
    if (res.success && res.data) {
      const { rejectedCount, failedCount, failures } = res.data;
      if (failedCount === 0) {
        ElMessage.success(`批量驳回成功: ${rejectedCount} 条`);
      } else {
        ElMessageBox.alert(
          `成功: ${rejectedCount} 条\n失败: ${failedCount} 条\n\n失败明细:\n${failures.map(f => `  - ${f.id.slice(0, 8)}: ${f.reason}`).join('\n')}`,
          '批量驳回部分失败',
          { type: 'warning' }
        );
      }
      loadData();
    }
  } catch (e) {
    if (e === 'cancel') return;
    const err = e as { status?: number; actionHint?: string | null } | undefined;
    if (!err || (err.status !== 409 && err.status !== 403 && !err.actionHint)) {
      ElMessage.error('批量驳回失败');
    }
  }
}

function handleSelectionChange(rows: AdjustmentRow[]) {
  const reservedRows = rows.filter(isRefundReversalRow);
  selectedRows.value = rows.filter(row => !isRefundReversalRow(row));
  if (reservedRows.length > 0) ElMessage.warning('请从原退款详情审核');
}

function isSelectable(row: AdjustmentRow): boolean {
  return !isRefundReversalRow(row);
}

// 4-eye gate: hide approve button if current user is the submitter.
function canShowApproveBtn(row: AdjustmentRow): boolean {
  return canApprove.value && !isRefundReversalRow(row) && row.operatedBy !== currentUserId.value;
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

function handleSizeChange(val: number) { pagination.value.size = val; pagination.value.page = 1; loadData(); }
function handlePageChange(val: number) { pagination.value.page = val; loadData(); }

watch([factoryId, currentUserId, () => route?.query.returnOrderId], () => {
  ++contextVersion;
  ++listRequestVersion;
  if (approvalConfirmationOpen) ElMessageBox.close();
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
  if (approvalConfirmationOpen) ElMessageBox.close();
});

onBeforeUnmount(() => {
  active = false;
  ++contextVersion;
  if (approvalConfirmationOpen) ElMessageBox.close();
});
</script>

<template>
  <div class="page-wrapper" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span style="font-size:16px;font-weight:600">调整审批</span>
          <div style="display:flex;gap:8px;align-items:center">
            <span style="color:#999;font-size:13px">待审批: {{ pagination.total }} 条</span>
            <el-button :icon="Refresh" @click="loadData">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="warning" show-icon :closable="false"
        style="margin-bottom:12px"
        title="4 眼原则"
        description="审批人必须与提交人不同。系统会自动隐藏您自己提交的调整记录的「审批」按钮 (但驳回仍可操作)。批量审批时,您本人的记录会被自动跳过,不计入失败。"
      />

      <!-- R29 P1 filter bar -->
      <el-alert v-if="hasReturnContext" :title="sourceError || `仅显示退货单 ${sourceReturnNumber || '正在核对'} 的待审批调整`" :type="sourceError ? 'error' : 'info'" :closable="false" style="margin-bottom:12px">
        <el-button v-if="sourceReturnId" link type="primary" @click="openSourceReturn(sourceReturnId)">返回原退货单</el-button>
        <el-button v-if="sourceReturnId" link type="primary" @click="openSourceLedger">查看本单全部调整记录</el-button>
        <el-button link @click="router.replace({ query: {} })">查看全部待审调整</el-button>
      </el-alert>
      <div v-if="!hasReturnContext" class="filter-bar">
        <el-select v-model="filterType" placeholder="对手方类型" clearable style="width:140px">
          <el-option label="客户 (AR)" value="CUSTOMER" />
          <el-option label="供应商 (AP)" value="SUPPLIER" />
        </el-select>
        <el-input-number
          v-model="filterMinAmount" placeholder="最小金额"
          :min="0" :precision="2" :controls="false"
          style="width:130px"
        />
        <span style="color:#999">至</span>
        <el-input-number
          v-model="filterMaxAmount" placeholder="最大金额"
          :min="0" :precision="2" :controls="false"
          style="width:130px"
        />
        <el-date-picker
          v-model="filterDateRange"
          type="daterange" range-separator="至"
          start-placeholder="提交开始日"
          end-placeholder="结束日"
          value-format="YYYY-MM-DD"
          style="width:280px"
        />
        <el-button type="primary" @click="handleApplyFilter">筛选</el-button>
        <el-button @click="handleResetFilters">重置</el-button>
      </div>

      <!-- R29 P2 bulk action bar -->
      <div v-if="selectedRows.length > 0" class="bulk-bar">
        已选中 <strong>{{ selectedRows.length }}</strong> 条
        <el-button
          type="success" size="small" :icon="Check"
          :disabled="!canApprove"
          @click="handleBatchApprove"
        >批量审批</el-button>
        <el-button
          type="danger" size="small" :icon="Close"
          :disabled="!canApprove"
          @click="handleBatchReject"
        >批量驳回</el-button>
      </div>

      <el-table
        :data="tableData" empty-text="暂无待审批调整" stripe border style="width:100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="40" :selectable="isSelectable" />
        <el-table-column prop="id" label="ID" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="font-family:monospace;font-size:12px">{{ row.id.slice(0, 8) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="row.transactionType === 'AR_ADJUSTMENT' ? 'success' : 'warning'" size="small">
              {{ isRefundReversalRow(row) ? '退款撤销调整' : formatType(row.transactionType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对手方" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterpartyName || row.counterpartyId }}</template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" label="金额" width="140" align="right">
          <template #default="{ row }">
            <span :style="{ color: isKnownAmount(row.amount) && row.amount > 0 ? '#67C23A' : '#F56C6C', fontWeight: 600 }">
              {{ formatAmount(row.amount, row.counterpartyType) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="canViewPrice" prop="balanceAfter" label="提交时余额 (待审批)" width="170" align="right">
          <template #default="{ row }">{{ formatBalance(row.balanceAfter) }}</template>
        </el-table-column>
        <el-table-column label="来源单据" width="130"><template #default="{ row }"><el-button v-if="row.sourceType === 'RETURN_ORDER' && validReturnOrderId(row.sourceId)" link type="primary" @click="openSourceReturn(row.sourceId)">查看原退货单</el-button><el-button v-else-if="isRefundReversalRow(row) && validRefundRequestId(row.sourceId)" link type="primary" @click="openSourceRefund(row)">查看撤销退款</el-button><span v-else>-</span></template></el-table-column>
        <el-table-column prop="remark" label="备注" min-width="200" show-overflow-tooltip />
        <el-table-column prop="operatedBy" label="提交人 ID" width="100" align="center">
          <template #default="{ row }">
            <span :style="{ color: row.operatedBy === currentUserId ? '#E6A23C' : '#606266' }">
              {{ row.operatedBy }}
              <small v-if="row.operatedBy === currentUserId" style="display:block;color:#E6A23C">(您本人)</small>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canShowApproveBtn(row)"
              type="success" size="small" :icon="Check"
              @click="handleApprove(row)"
            >审批</el-button>
            <el-button
              v-if="canApprove && !isRefundReversalRow(row)"
              type="danger" size="small" :icon="Close"
              @click="handleReject(row)"
            >驳回</el-button>
            <el-text v-if="isRefundReversalRow(row)" type="warning" size="small">待审批；请从原退款详情审核</el-text>
            <span v-if="!canApprove && !isRefundReversalRow(row)" style="color:#999;font-size:12px">无权限</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="pagination.total > 0"
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top:16px;justify-content:flex-end"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </el-card>
  </div>
</template>

<style scoped>
.page-wrapper {
  padding: 16px;
}
.filter-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.bulk-bar {
  background: #f0f9ff;
  border: 1px solid #d9ecff;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
