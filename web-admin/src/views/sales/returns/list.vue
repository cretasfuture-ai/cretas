<script setup lang="ts">
import { CompatOption, CompatPagination, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

/**
 * 销售退货管理 — T-RTA (issue #531) frontend MVP.
 *
 * F006 客户反馈 (第四次会议 line 956-1037):
 * > 可能会涉及到一个退货... 售后的话申请, 有食物的话库存入库到总仓...
 * > 无食物的话就退款, 财务审批
 *
 * Backend ReturnOrderController has existed since 2026-Q1 (covers both 采购退货
 * + 销售退货 via returnType=SALES_RETURN | PURCHASE_RETURN filter). F006 coverage
 * push (PR #517 / #527) flagged this as 真 gap because no frontend view existed.
 *
 * This view filters returnType=SALES_RETURN by default (sales menu context).
 * Downstream linkage (inventory inbound on 有食物 / 财务退款 on 无食物) is
 * handled by backend ReturnOrderService.approveReturnOrder / completeReturnOrder
 * — UI just records the request + drives state transitions.
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { submitReturnOrder, approveReturnOrder, rejectReturnOrder } from '@/api/returnOrder';

import { Refresh, Search, View } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { formatDateTime } from '@/utils/dateFormat';
import type { TableRow } from '@/types/api';
import { RowActionMenu, TableFooter } from '@/components/list';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';
import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
import { loadErrorText } from '@/utils/listEmptyState';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);
// 六扇门 Tier0 #16: 销售退货业务审批 (approve/reject) 需 sales 写权限.
const canApprove = computed(() => permissionStore.canWrite('sales'));

function rowActionsFor(row: TableRow) {
  return computeRowActions(
    'returnOrder',
    { status: String(row.status || ''), id: String(row.id || '') },
    { canViewPrice: canViewPrice.value }
  );
}

// Bug fix (2026-07): 'submit'/'approve'/'reject' row actions (from rowActionsConfig
// returnOrder DRAFT/SUBMITTED/PENDING_APPROVAL) fell through to the `default` no-op
// branch below — no network call, no state change, return order stuck in DRAFT
// forever. 采购退货 (procurement/returns/list.vue) wires these to real endpoints;
// mirror that here using the same typed api/returnOrder.ts client.
const submittingAction = ref(false);

async function runRowAction(
  row: TableRow,
  actionLabel: string,
  confirmText: string,
  fn: (factoryId: string, returnOrderId: string) => Promise<unknown>,
) {
  try {
    await ElMessageBox.confirm(
      `退货单 ${row.returnNumber || '-'}　金额 ¥${row.totalAmount ?? '-'}\n${confirmText}`,
      `${actionLabel}`,
      { type: 'warning' },
    );
  } catch {
    return; // 用户取消
  }
  submittingAction.value = true;
  try {
    await fn(factoryId.value, String(row.id));
    ElMessage.success(`${actionLabel}成功`);
    await loadData();
  } catch (err: unknown) {
    // 4位一体: 原样透传后端 message (含状态机/防呆 hint), sticky 不自动消失.
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      || (err as Error)?.message || `${actionLabel}失败`;
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    submittingAction.value = false;
  }
}

function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': router.push(`/sales/returns/${row.id}`); break;
    // Sales return 复用 sales-order 模板 (后端 RBAC 仍校验 sales:read)
    case 'print-pdf': void safePrint('sales-order', factoryId.value, String(row.id), { fileName: `销售退货单_${row.returnNumber || row.id}` }); break;
    case 'submit':
      void runRowAction(row, '提交审核', '确认提交本退货单进入审批?', submitReturnOrder);
      break;
    case 'approve':
      if (!canApprove.value) { ElMessage({ message: t('sales.returns_list.message_1f0fb0613229'), type: 'error', duration: 0, showClose: true }); break; }
      void runRowAction(row, '审批通过', '审批通过后, 退货单状态推进. 确认审批?', approveReturnOrder);
      break;
    case 'reject':
      if (!canApprove.value) { ElMessage({ message: t('sales.returns_list.message_1f0fb0613229'), type: 'error', duration: 0, showClose: true }); break; }
      void runRowAction(row, '驳回', '驳回后退货单状态置为已驳回, 不再处理. 确认驳回?', rejectReturnOrder);
      break;
    // 'edit'/'delete' (DRAFT/REJECTED 状态可配置项): 后端暂无对应写接口 — Rule 5
    // (dead-end 改导航) 引导用户到详情页处理, 不留静默无效按钮.
    case 'edit':
    case 'delete':
      ElMessage.info(t('sales.returns_list.message_b91294ca2bff'));
      router.push(`/sales/returns/${row.id}`);
      break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
  }
}
function openAiForRow(row: TableRow) {
  ElMessage.info(`AIChat: returnOrder/${row.returnNumber || row.id} — 接 AiEntryDrawer 待 Day 9`);
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });

const filterStatus = ref('');
const loadError = ref('');

function clearFilters() { filterStatus.value = ''; handleSearch(); }

// U-FOOTER-1
// KPI-vs-list drift fix (2026-07): filterStatus MUST be declared BEFORE this
// computed (TDZ — see sales/shipments/list.vue Issue #716). Previously this
// was a static `{ filterConditions: {} }` so the footer "共" always showed
// the ALL-status total while loadData() sends `params.status = filterStatus`
// to /return-orders — footer and paginator would diverge as soon as a
// status filter was applied. Fix: mirror the same status filter.
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: filterStatus.value ? { status: filterStatus.value } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('returnOrder', summaryRequest);

const STATUS_OPTIONS = [
  { value: '', label: t('sales.returns_list.label_0a379c1e7398') },
  { value: 'DRAFT', label: t('sales.returns_list.label_2a2fd29bd27a') },
  { value: 'SUBMITTED', label: t('sales.returns_list.label_bc37a6110a07') },
  { value: 'APPROVED', label: t('sales.returns_list.label_180fcc4a4eec') },
  { value: 'FINANCE_APPROVED', label: t('sales.returns_list.label_f16c1a6e1a4d') },
  { value: 'REJECTED', label: t('sales.returns_list.label_4dbcdec8693b') },
  { value: 'PROCESSING', label: t('sales.returns_list.label_694b71bc8013') },
  { value: 'COMPLETED', label: t('sales.returns_list.label_f28461bb49c8') },
];

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
  const o = STATUS_OPTIONS.find((x) => x.value === s);
  return o ? o.label : s || '-';
}

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const params: TableRow = {
      returnType: 'SALES_RETURN',
      page: pagination.value.page,
      size: pagination.value.size,
    };
    if (filterStatus.value) params.status = filterStatus.value;
    const res = await get(`/${factoryId.value}/return-orders`, { params });
    if (res.success && res.data) {
      tableData.value = res.data.content || res.data.list || [];
      pagination.value.total = res.data.totalElements || res.data.total || 0;
    } else if (res.success === false) {
      tableData.value = [];
      loadError.value = res.message || '加载退货单失败';
      ElMessage.error(loadError.value);
    }
  } catch (e) { tableData.value = []; loadError.value = loadErrorText(e, '加载退货单失败'); /* interceptor 已弹 toast */ }
  finally { loading.value = false; }
}

function handlePageChange(page: number) { pagination.value.page = page; loadData(); }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; loadData(); }
function handleSearch() { pagination.value.page = 1; loadData(); }
function viewDetail(row: TableRow) { router.push(`/sales/returns/${row.id}`); }
</script>

<template>
  <div class="page-wrapper">
    <UCard class="page-card" >
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ t('sales.returns_list.text_08e6ba1e5529') }}</span>
            <span class="data-count">{{ t('sales.returns_list.text_76e547a8fa54') }} {{ pagination.total }} {{ t('sales.returns_list.text_26092b300f84') }}</span>
          </div>
          <div class="header-right">
            <CompatSelect v-model="filterStatus" :placeholder="t('sales.returns_list.placeholder_9a9acd39e536')" clearable style="width: 160px" @change="handleSearch">
              <CompatOption v-for="opt in STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
            </CompatSelect>
            <el-button :icon="Search" type="primary" @click="handleSearch">{{ t('sales.returns_list.action_bcd6771e08ec') }}</el-button>
            <el-button :icon="Refresh" @click="loadData">{{ t('sales.returns_list.action_aee887434131') }}</el-button>
          </div>
        </div>
      </template>

      <CompatTable :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <template #empty>
          <ListEmptyExplain
            :load-error="loadError"
            :filtered="!!filterStatus"
            empty-title="还没有销售退货单"
            empty-hint="客户退货时，在「销售订单」详情里登记退货，登记后会出现在这里，随后走提交、审批、处理流程。"
            @retry="loadData"
            @clear-filter="clearFilters"
          />
        </template>
        <CompatTableColumn prop="returnNumber" :label="t('sales.returns_list.label_07c4a05db006')" width="200" />
        <CompatTableColumn :label="t('sales.returns_list.label_ad4af312dbc2')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.counterpartyName || row.customerName || row.counterpartyId || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.returns_list.label_af1726ab36bb')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.sourceOrderNumber || row.sourceOrderId || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="returnDate" :label="t('sales.returns_list.label_2934964f31bf')" width="120" />
        <CompatTableColumn v-if="canViewPrice" :label="t('sales.returns_list.label_6f8d27fcc358')" width="130" align="right">
          <template #default="{ row }">{{ formatAmount(row.totalAmount) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.returns_list.label_6320b4a8722a')" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.returns_list.label_f029d9cb4a76')" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reason || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.returns_list.label_07ec86e0f1d4')" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('sales.returns_list.label_ed31fbb483ee')" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button :icon="View" link type="primary" @click="viewDetail(row)">{{ t('sales.returns_list.action_db8db0530432') }}</el-button>
            <RowActionMenu
              :actions="rowActionsFor(row)"
              button-label="更多"
              @action-click="(id: string) => handleRowActionClick(id, row)"
              @ai-trigger="() => openAiForRow(row)"
            />
          </template>
        </CompatTableColumn>
      </CompatTable>

      <TableFooter
        price-gate="both"
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前退货单${formatSummaryForAI(footerSummary)}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <CompatPagination
          v-model:current-page="pagination.page" v-model:page-size="pagination.size"
          :total="pagination.total" :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange" @size-change="handleSizeChange" />
      </div>
    </UCard>
  </div>
</template>

<style lang="scss" scoped>
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
  .header-right { display: flex; gap: 8px; align-items: center; }
}
.pagination-wrapper { display: flex; justify-content: flex-end; padding-top: 16px; border-top: 1px solid #ebeef5; margin-top: 16px; }
</style>
