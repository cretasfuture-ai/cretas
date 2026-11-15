<script setup lang="ts">
/**
 * 采购退货管理 — D-BUG3 frontend.
 *
 * 客户红线 (catalog 行2399-2413): "退货跟钱有关, 必须先财务审批"。
 *
 * 退货单状态机 (ReturnOrderStatus, 六扇门 Tier0 #16 财审门):
 *   DRAFT → SUBMITTED → APPROVED → FINANCE_APPROVED → COMPLETED
 *   退货跟资金相关, 业务审批 (APPROVED) 后必须先财务审批 (FINANCE_APPROVED, 仅 finance:read_write)
 *   才能交仓管完成 / 出货。
 *
 * 采购退货单来源:
 *   - 采购入库异常 (procurement/exceptions/list.vue) 选 "退货(拒收,退回供应商)" = RETURN_OVER 决策
 *     → 后端 PurchaseExceptionServiceImpl.decideException 自动创建 PURCHASE_RETURN ReturnOrder (DRAFT)。
 *   - 本页负责后续 提交 → 业务审批 → 财务审批 → 完成 的状态推进。
 *
 * 复用 returnOrder.ts API client (后端 ReturnOrderController, returnType=PURCHASE_RETURN 过滤),
 * 不重建退货实体。销售退货另有 sales/returns/list.vue (returnType=SALES_RETURN)。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listReturnOrders,
  submitReturnOrder,
  approveReturnOrder,
  financeApproveReturnOrder,
  financeRejectReturnOrder,
  rejectReturnOrder,
  completeReturnOrder,
  type ReturnOrder,
  type ReturnOrderStatus,
} from '@/api/returnOrder';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
// 采购业务审批: procurement 写权限。财务审批: finance 写权限 (后端 finance:read_write 把关)。
const canProcurementWrite = computed(() => permissionStore.canWrite('procurement'));
const canFinanceWrite = computed(() => permissionStore.canWrite('finance'));

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<ReturnOrder[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const filterStatus = ref<ReturnOrderStatus | ''>('');

const STATUS_OPTIONS: Array<{ value: ReturnOrderStatus | ''; label: string }> = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'APPROVED', label: '已审批 (待财务)' },
  { value: 'FINANCE_APPROVED', label: '财务已审' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'COMPLETED', label: '已完成' },
];

function statusType(s: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (s) {
    case 'DRAFT': return 'info';
    case 'SUBMITTED': return 'warning';
    case 'APPROVED': return 'primary';
    case 'FINANCE_APPROVED': return 'success';
    case 'REJECTED': return 'danger';
    case 'COMPLETED': return 'success';
    default: return 'info';
  }
}

// USelect 的选项值不能是空串: 页面内用 ALL 表示不筛
const statusItems = STATUS_OPTIONS.map((o) => ({ label: o.label, value: o.value === '' ? 'ALL' : o.value }));
const statusSelect = computed({
  get: () => filterStatus.value || 'ALL',
  set: (v: string) => { filterStatus.value = v === 'ALL' ? '' : (v as ReturnOrderStatus); },
});
const sizeItems = [10, 20, 50, 100].map((n) => ({ label: `${n} 条/页`, value: n }));
const columns = computed<TableColumn<ReturnOrder>[]>(() => [
  { id: 'returnNumber', header: '退货单号' },
  { id: 'supplier', header: '供应商' },
  { id: 'source', header: '源采购单' },
  { id: 'returnDate', header: '退货日期' },
  ...(canViewPrice.value ? [{ id: 'amount', header: '退货金额', meta: { class: { th: 'text-right', td: 'text-right' } } }] : []),
  { id: 'status', header: '状态' },
  { id: 'reason', header: '退货原因' },
  { id: 'createdAt', header: '创建时间' },
  { id: 'actions', header: '操作' },
]);

function statusLabel(s: string) {
  const o = STATUS_OPTIONS.find((x) => x.value === s);
  return o ? o.label : s || '-';
}

function formatAmount(v: number | null | undefined): string {
  if (v == null) return '—';
  return `¥${Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function formatDateTime(dt: string | null | undefined): string {
  if (!dt) return '—';
  return new Date(dt).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const page = await listReturnOrders(factoryId.value, {
      returnType: 'PURCHASE_RETURN',
      status: filterStatus.value || undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    tableData.value = page.content;
    pagination.value.total = page.totalElements;
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      || (err as Error)?.message || '加载采购退货单失败';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function handlePageChange(p: number) { pagination.value.page = p; loadData(); }
function handleSizeChange(s: number) { pagination.value.size = s; pagination.value.page = 1; loadData(); }
function handleSearch() { pagination.value.page = 1; loadData(); }

// ─── 状态推进操作 (Fool-proof Rule 2: 确认框带单号 + context) ──────────────

async function runAction(
  row: ReturnOrder,
  actionLabel: string,
  confirmText: string,
  fn: (factoryId: string, id: string) => Promise<ReturnOrder>,
) {
  try {
    await ElMessageBox.confirm(confirmText, `${actionLabel} — ${row.returnNumber}`, {
      confirmButtonText: actionLabel,
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return; // 用户取消
  }
  submitting.value = true;
  try {
    const updated = await fn(factoryId.value, row.id);
    if (updated.completionHint) {
      ElMessage({ message: updated.completionHint, type: 'warning', duration: 0, showClose: true });
    } else {
      ElMessage({ message: `${actionLabel}成功`, type: 'success', duration: 3000 });
    }
    loadData();
  } catch (err: unknown) {
    // 4-in-1 sticky error: 原样显示后端 message (含财审门 hint)
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      || (err as Error)?.message || `${actionLabel}失败`;
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    submitting.value = false;
  }
}

function doSubmit(row: ReturnOrder) {
  runAction(row, '提交', `确认提交采购退货单「${row.returnNumber}」进入审批流程？`, submitReturnOrder);
}
function doApprove(row: ReturnOrder) {
  runAction(row, '业务审批', `确认通过采购退货单「${row.returnNumber}」的业务审批？审批后还需财务审批方可完成。`, approveReturnOrder);
}
function doFinanceApprove(row: ReturnOrder) {
  runAction(
    row, '财务审批',
    `退货金额 ${formatAmount(row.totalAmount)}。退货跟资金相关，确认财务审批通过采购退货单「${row.returnNumber}」？`,
    financeApproveReturnOrder,
  );
}
function doFinanceReject(row: ReturnOrder) {
  runAction(row, '财务驳回', `确认财务驳回采购退货单「${row.returnNumber}」？`, financeRejectReturnOrder);
}
function doReject(row: ReturnOrder) {
  runAction(row, '驳回', `确认驳回采购退货单「${row.returnNumber}」？`, rejectReturnOrder);
}
function doComplete(row: ReturnOrder) {
  runAction(row, '完成', `确认完成采购退货单「${row.returnNumber}」并交仓管出货？`, completeReturnOrder);
}
</script>

<template>
  <div class="page-wrapper">
    <div class="cs-page-head">
      <div>
        <h2>采购退货</h2>
        <p>共 {{ pagination.total }} 单</p>
      </div>
    </div>

    <UAlert
      color="info"
      variant="subtle"
      icon="i-lucide-info"
      class="mb-3"
      title="采购退货流程"
      description="采购退货单来自「采购管理 → 入库异常」选「退货(拒收,退回供应商)」自动生成。退货跟资金相关，需走 提交 → 业务审批 → 财务审批 → 完成，财务审批通过后才能交仓管出货。"
    />

    <section class="cs-card">
      <div class="cs-card-head">
        <USelect v-model="statusSelect" :items="statusItems" placeholder="状态筛选" class="w-48" @update:model-value="handleSearch" />
        <UButton icon="i-lucide-search" @click="handleSearch">查询</UButton>
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="loadData">刷新</UButton>
      </div>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table">
        <template #empty>暂无采购退货单</template>
        <template #returnNumber-cell="{ row }">{{ row.original.returnNumber }}</template>
        <template #supplier-cell="{ row }">{{ row.original.counterpartyId || '-' }}</template>
        <template #source-cell="{ row }">{{ row.original.sourceOrderId || '-' }}</template>
        <template #returnDate-cell="{ row }"><span class="num">{{ row.original.returnDate }}</span></template>
        <template #amount-cell="{ row }"><span class="num">{{ formatAmount(row.original.totalAmount) }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(statusType(row.original.status))" variant="soft">{{ statusLabel(row.original.status) }}</UBadge>
        </template>
        <template #reason-cell="{ row }">{{ row.original.reason || '-' }}</template>
        <template #createdAt-cell="{ row }"><span class="muted">{{ formatDateTime(row.original.createdAt) }}</span></template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <!-- DRAFT → 提交 (采购写权限) -->
            <template v-if="row.original.status === 'DRAFT' && canProcurementWrite">
              <UButton size="xs" variant="link" :loading="submitting" @click="doSubmit(row.original)">提交</UButton>
            </template>
            <!-- SUBMITTED → 业务审批 / 驳回 (采购写权限) -->
            <template v-else-if="row.original.status === 'SUBMITTED' && canProcurementWrite">
              <UButton size="xs" variant="link" :loading="submitting" @click="doApprove(row.original)">业务审批</UButton>
              <UButton size="xs" variant="link" color="error" :loading="submitting" @click="doReject(row.original)">驳回</UButton>
            </template>
            <!-- APPROVED → 财务审批 / 财务驳回 (财务写权限; 客户红线: 退货必须先财务审批) -->
            <template v-else-if="row.original.status === 'APPROVED'">
              <template v-if="canFinanceWrite">
                <UButton size="xs" variant="link" color="success" :loading="submitting" @click="doFinanceApprove(row.original)">财务审批</UButton>
                <UButton size="xs" variant="link" color="error" :loading="submitting" @click="doFinanceReject(row.original)">财务驳回</UButton>
              </template>
              <span v-else class="muted" title="退货跟资金相关，需财务角色 (finance) 审批">待财务审批</span>
            </template>
            <!-- FINANCE_APPROVED → 完成 (采购写权限) -->
            <template v-else-if="row.original.status === 'FINANCE_APPROVED' && canProcurementWrite">
              <UButton size="xs" variant="link" :loading="submitting" @click="doComplete(row.original)">完成出货</UButton>
            </template>
            <span v-else class="muted">—</span>
          </div>
        </template>
      </UTable>

      <div class="pagination-wrapper">
        <span class="total">共 {{ pagination.total }} 条</span>
        <USelect v-model="pagination.size" :items="sizeItems" class="w-28" @update:model-value="handleSizeChange" />
        <UPagination v-model:page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.page-wrapper { padding: var(--cs-space-4); }
.row-actions { display: flex; gap: var(--cs-space-1); align-items: center; }
.muted { color: var(--cs-muted); }
.total { color: var(--cs-muted); font-size: var(--cs-font); }
.pagination-wrapper { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
</style>
