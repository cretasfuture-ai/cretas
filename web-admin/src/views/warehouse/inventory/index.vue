<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { displayUnit } from '@/utils/unitPricing';
import { reservedHoldLabel, shouldShowReservedHold } from './reservedHold';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
/* 组件全部是 Nuxt UI (UTable / UModal / UFormField / UInput / USelectMenu / UTextarea / UBadge / UPagination) + --cs-*；弹窗里不放 Element 控件。提示仍用 ElMessage；附件列头提示用原生 title（UTooltip 需 UApp）。 */
// Sprint 6 W3-A — inline 3-chip link counter (文件 / 图片 / 合同).
import LinkChipCell from '@/components/list/LinkChipCell.vue';
import { useLinkChipCounts } from '@/composables/useLinkChipCounts';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import type { TableRow } from '@/types/api';
import { TableFooter } from '@/components/list';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { STOCKTAKING_CONFIG } from '@/components/ai-entry/types';
import { toDateString } from '@/utils/dateFormat';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

// U-NAV-1 业务流程图导航 (Sprint 2 Track G + FU Chat 3 bucket-filter)
const { stats: workflowStats, loading: workflowLoading } = useWorkflowStats(factoryId, 'inventory');

// Sprint 6 W3-A — inline 3-chip 链接计数 (文件 / 图片 / 合同).
const { fetchLinkChipCounts, countsFor: linkCountsFor } =
  useLinkChipCounts(factoryId, 'INVENTORY');
function handleWorkflowNodeClick(nodeId: string) {
  const primary = getBucketPrimaryStatus('inventory', nodeId);
  if (!primary) return;
  statusFilter.value = primary;
  pagination.value.page = 1;
  loadData();
  ElMessage.success(`已切到 "${getBucketLabel('inventory', nodeId)}" (显示状态: ${primary}). bucket 含多个状态, 想看其他请打开状态下拉切换.`);
}
// AI 盘点调整 (Day 7) — Issue #780.1
const aiEntryVisible = ref(false);
function handleWorkflowAITrigger() {
  aiEntryVisible.value = true;
}
async function handleAiFill(params: Record<string, unknown>) {
  const batchNumber = String(params.batchNumber || '');
  // 1) Try current loaded page first
  let matched = tableData.value.find(
    (r) => String(r.batchNumber || '').toUpperCase() === batchNumber.toUpperCase()
  );
  // 2) 兜底: 批次可能存在但不在当前分页 → 按批次号调 API 查, 解析真实 batchId。
  //    (修复 405: 之前 batchId='' 直接开弹窗, 提交时 POST /material-batches//adjust
  //     路径塌成 /material-batches/adjust → 撞 /{batchId} 的 GET/PUT/DELETE → 405)
  if (!matched && batchNumber) {
    try {
      const resp = await get(
        `/${factoryId.value}/material-batches?keyword=${encodeURIComponent(batchNumber)}&size=20`
      );
      const list = Array.isArray(resp.data) ? resp.data : (resp.data?.content || []);
      matched = list.find(
        (r: TableRow) => String(r.batchNumber || '').toUpperCase() === batchNumber.toUpperCase()
      );
    } catch { /* 查失败按未找到处理 */ }
  }
  // 3) 仍未找到 → 不开弹窗(避免畸形请求 405), 给可执行提示
  if (!matched) {
    ElMessage.warning(
      `批次 ${batchNumber} 不在库存中，无法调整。请确认批次号，或改用「采购入库」新建该批次(期初入库走采购入库)。`
    );
    return;
  }
  const aiReason = String(params.reason || '');
  // AI 抽取的原因是自由文本, 映射进标准下拉: 命中已知选项用对应 value, 否则落 OTHER + 保留原文本。
  const matchedOption = adjustReasonOptions.find(
    (o) => o.value !== 'OTHER' && (o.label === aiReason || o.value === aiReason)
  );
  adjustForm.value = {
    batchId: String(matched.id),
    batchNumber: String(matched.batchNumber),
    materialName: String(matched.materialName || matched.materialTypeName || ''),
    currentQuantity: Number(matched.currentQuantity ?? matched.quantity ?? 0),
    adjustQuantity: Number(params.adjustQuantity || 0),
    reason: matchedOption ? matchedOption.value : (aiReason ? 'OTHER' : ''),
    reasonOther: matchedOption ? '' : aiReason,
  };
  adjustDialogVisible.value = true;
}

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const searchKeyword = ref('');
const statusFilter = ref('');

// U-FOOTER-1
// KPI-vs-list drift fix (2026-07): previously static `{ filterConditions: {} }`
// so footer "共"/"可用数量"/"总价值" always reflected ALL batches while
// loadData() sends `params.status = statusFilter` to /material-batches —
// footer and paginator would diverge as soon as a status filter was applied.
// (低库存预警 stat is separately sourced from loadStatistics(), which is
// intentionally factory-wide/unfiltered — see MaterialBatchServiceImpl
// getLowStockWarnings; not affected by this filter mirror.)
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: statusFilter.value ? { status: statusFilter.value } : {},
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('inventory', summaryRequest);

// 库存统计
const statistics = ref({
  totalBatches: 0,
  totalQuantity: 0,
  lowStockCount: 0,
  expiringCount: 0
});

// 调整库存对话框
const adjustDialogVisible = ref(false);
const adjustLoading = ref(false);
const adjustForm = ref({
  batchId: '',
  batchNumber: '',
  materialName: '',
  currentQuantity: 0,
  adjustQuantity: 0,
  reason: '',
  reasonOther: '',
});

// Bug 5 (fool-proof Rule 3): 调整原因改标准下拉 (与报损原因一致的约束选择, 不是自由 textarea)
const adjustReasonOptions = [
  { value: 'STOCKTAKE_DIFF', label: '盘点差异' },
  { value: 'WASTAGE', label: '损耗' },
  { value: 'DAMAGED', label: '破损' },
  { value: 'EXPIRED', label: '过期' },
  { value: 'OTHER', label: '其他' },
];

// 批次详情对话框
const detailDialogVisible = ref(false);
const detailData = ref<TableRow | null>(null);
const detailLoading = ref(false);

const adjustedQuantity = computed(() => (Number(adjustForm.value.currentQuantity) || 0) + (Number(adjustForm.value.adjustQuantity) || 0));
const R = { class: { th: 'text-right', td: 'text-right' } };
const C = { class: { th: 'text-center', td: 'text-center' } };
const columns: TableColumn<TableRow>[] = [
  { accessorKey: 'batchNumber', header: '批次号' },
  { id: 'material', header: '材料类型' },
  { id: 'quantity', header: '当前数量', meta: R },
  { id: 'unit', header: '单位', meta: C },
  { id: 'storageLocation', header: '存储位置' },
  { id: 'status', header: '状态', meta: C },
  { id: 'expire', header: '过期日期' },
  { id: 'updatedAt', header: '更新时间' },
  { id: 'attachments', header: '附件', meta: C },
  { id: 'actions', header: '操作', meta: C },
];
const historyColumns: TableColumn<TableRow>[] = [
  { accessorKey: 'adjustmentType', header: '类型' },
  { id: 'adjustmentQuantity', header: '数量', meta: R },
  { id: 'quantityBefore', header: '调前', meta: R },
  { id: 'quantityAfter', header: '调后', meta: R },
  { accessorKey: 'reason', header: '原因' },
  { accessorKey: 'adjustmentTime', header: '时间' },
];
const sizeItems = [10, 20, 50, 100].map((v) => ({ label: `${v} 条/页`, value: v }));
const adjustHistory = ref<TableRow[]>([]);

// 状态选项
const statusOptions = [
  { value: 'AVAILABLE', label: '可用' },
  { value: 'FRESH', label: '鲜品' },
  { value: 'FROZEN', label: '冻品' },
  { value: 'RESERVED', label: '已预留' },
  { value: 'INSPECTING', label: '质检中' },
  { value: 'DEPLETED', label: '已耗尽' },
  { value: 'EXPIRED', label: '已过期' },
  { value: 'SCRAPPED', label: '已报废' },
];
// Nuxt UI 选项值不能是空串：ALL 表示「全部状态」，回写 statusFilter 仍是 ''
const statusItems = [{ value: 'ALL', label: '全部状态' }, ...statusOptions];
const statusModel = computed({
  get: () => statusFilter.value || 'ALL',
  set: (v: string) => { statusFilter.value = v === 'ALL' ? '' : v; handleSearch(); },
});
const pageModel = computed({
  get: () => pagination.value.page,
  set: (v: number) => handlePageChange(v),
});
const sizeModel = computed({
  get: () => pagination.value.size,
  set: (v: number) => handleSizeChange(v),
});

onMounted(() => {
  loadData();
  loadStatistics();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const params: Record<string, any> = {
      page: pagination.value.page,
      size: pagination.value.size,
    };
    if (searchKeyword.value) params.keyword = searchKeyword.value;
    if (statusFilter.value) params.status = statusFilter.value;

    const response = await get(`/${factoryId.value}/material-batches`, { params });
    if (response.success && response.data) {
      const rows = response.data.content || [];
      tableData.value = rows;
      pagination.value.total = response.data.totalElements || 0;

      // Sprint 6 W3-A — fire-and-forget batch 3-chip counts (文件/图片/合同).
      // EntityType=INVENTORY → 库存批次附件 (入库单据 / 抄码单 / 质检证明).
      void fetchLinkChipCounts(rows.map((r: TableRow) => String(r.id)).filter(Boolean));
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadStatistics() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/material-batches/inventory/statistics`);
    if (response.success && response.data) {
      statistics.value = response.data;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载统计数据失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载统计失败:', error);
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
  statusFilter.value = '';
  pagination.value.page = 1;
  loadData();
  loadStatistics();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function handleAdjust(row: TableRow) {
  adjustForm.value = {
    batchId: row.id,
    batchNumber: row.batchNumber,
    // Bug 2 fix: MaterialBatchDTO 字段是 materialName, materialTypeName 在该 DTO 上不存在
    // (恒 undefined) — 保留 materialTypeName 兜底以防其他来源行数据带这个别名。
    materialName: row.materialName || row.materialTypeName || '',
    currentQuantity: row.currentQuantity ?? row.quantity ?? 0,
    adjustQuantity: 0,
    reason: '',
    reasonOther: '',
  };
  adjustDialogVisible.value = true;
}

async function submitAdjust() {
  // 修复 405: batchId 为空时禁止提交 (否则 POST /material-batches//adjust → 405)。
  if (!adjustForm.value.batchId) {
    ElMessage.error('未匹配到该批次的库存记录，无法调整。请确认批次号，或改用「采购入库」新建该批次。');
    return;
  }
  if (!adjustForm.value.adjustQuantity || !adjustForm.value.reason) {
    ElMessage.warning('请填写调整数量和原因');
    return;
  }
  // Bug 5 (fool-proof Rule 3): 选"其他"必须补充说明, 否则后端/审计看到的只有一个"其他"无信息量
  if (adjustForm.value.reason === 'OTHER' && !adjustForm.value.reasonOther.trim()) {
    ElMessage.warning('请填写具体的调整原因');
    return;
  }

  // W-03 fix (Round 7): UI "调整数量" is a delta (正数增加/负数减少). Backend
  // /material-batches/{id}/adjust takes newQuantity as the final absolute value,
  // not a delta. Prior FE submitted delta → negative caused backend 500, positive
  // silently reset batch quantity. Now compute newQuantity = current + delta
  // client-side and send the final value. Guard negative result to avoid 500 on
  // out-of-range adjustments.
  const delta = Number(adjustForm.value.adjustQuantity) || 0;
  const current = Number(adjustForm.value.currentQuantity) || 0;
  const newQuantity = current + delta;
  if (newQuantity < 0) {
    ElMessage.warning(`调整后数量不能为负 (当前 ${current}, 调整 ${delta}, 结果 ${newQuantity})`);
    return;
  }

  // Bug 5: 后端 reason 是自由字符串字段 (无 enum 校验) — 下拉选"其他"时把具体说明拼进去,
  // 否则落库/审计只看到裸标签 "其他"。
  const selectedReasonLabel = adjustReasonOptions.find((o) => o.value === adjustForm.value.reason)?.label || adjustForm.value.reason;
  const finalReason = adjustForm.value.reason === 'OTHER'
    ? adjustForm.value.reasonOther.trim()
    : selectedReasonLabel;

  adjustLoading.value = true;
  try {
    const response = await post(`/${factoryId.value}/material-batches/${adjustForm.value.batchId}/adjust`, {
      quantity: newQuantity,
      reason: finalReason
    });
    if (response.success) {
      ElMessage.success('调整成功');
      adjustDialogVisible.value = false;
      loadData();
      loadStatistics();
    } else {
      ElMessage.error(response.message || '调整失败');
    }
  } catch (error) {
    ElMessage.error('调整失败');
  } finally {
    adjustLoading.value = false;
  }
}

async function handleViewDetail(row: TableRow) {
  detailData.value = row;
  detailDialogVisible.value = true;
  adjustHistory.value = [];
  detailLoading.value = true;
  try {
    const response = await get(`/${factoryId.value}/material-batches/${row.id}/usage-history`);
    if (response.success && response.data) {
      adjustHistory.value = Array.isArray(response.data) ? response.data : (response.data.content || []);
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载使用记录失败');
    }
  } catch (error) {
    console.error('加载使用记录失败:', error);
    ElMessage.error('加载使用记录失败');
  } finally {
    detailLoading.value = false;
  }
}

async function handleExport() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/material-batches/export`, {
      responseType: 'blob'
    });
    const raw = (response as TableRow).data ?? response;
    const blob = raw instanceof Blob
      ? raw
      : new Blob([raw], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `库存批次报告_${toDateString(new Date())}.xlsx`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(url);
    ElMessage.success('导出成功');
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

// 过期日期高亮
function getExpireDateClass(row: TableRow): string {
  if (!row.expirationDate && !row.expireDate) return '';
  const dateStr = row.expirationDate || row.expireDate;
  const expDate = new Date(dateStr);
  const now = new Date();
  const diffDays = Math.ceil((expDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
  if (diffDays < 0) return 'expire-expired';
  if (diffDays <= 30) return 'expire-danger';
  if (diffDays <= 60) return 'expire-warning';
  return '';
}

const STATUS_MAP: Record<string, { text: string; type: string }> = {
  IN_STOCK: { text: '库存中', type: 'success' },
  AVAILABLE: { text: '可用', type: 'success' },
  FRESH: { text: '鲜品', type: '' },
  FROZEN: { text: '冻品', type: 'info' },
  DEPLETED: { text: '已耗尽', type: 'info' },
  USED_UP: { text: '已用完', type: 'info' },
  EXPIRED: { text: '已过期', type: 'danger' },
  INSPECTING: { text: '质检中', type: 'warning' },
  SCRAPPED: { text: '已报废', type: 'danger' },
  RESERVED: { text: '已预留', type: 'warning' },
};

function getStatusType(status: string) {
  return STATUS_MAP[status]?.type || 'info';
}

function getStatusText(status: string) {
  return STATUS_MAP[status]?.text || status;
}
</script>

<template>
  <div class="page-wrapper" data-testid="inventory-page">
    <!-- U-NAV-1 业务流程图导航 (Sprint 2 Track G) -->
    <WorkflowBar
      :nodes="workflowStats?.nodes ?? []"
      :loading="workflowLoading"
      title="库存批次状态"
      :ai-trigger-enabled="true"
      @node-click="handleWorkflowNodeClick"
      @ai-trigger="handleWorkflowAITrigger"
    />
    <!-- 统计卡片 -->
    <div class="statistics-row">
      <div class="cs-card stat-card">
        <div class="stat-value">{{ statistics.totalBatches }}</div>
        <div class="stat-label">批次总数</div>
      </div>
      <div class="cs-card stat-card">
        <div class="stat-value">{{ statistics.totalQuantity }}</div>
        <div class="stat-label">库存总量</div>
      </div>
      <div class="cs-card stat-card warning">
        <div class="stat-value">{{ statistics.lowStockCount }}</div>
        <div class="stat-label">低库存预警</div>
      </div>
      <div class="cs-card stat-card danger">
        <div class="stat-value">{{ statistics.expiringCount }}</div>
        <div class="stat-label">即将过期</div>
      </div>
    </div>

    <section class="cs-card">
      <header class="cs-card-head">
        <span class="page-title">库存批次查询</span>
        <span class="data-count">共 {{ pagination.total }} 条记录</span>
        <div class="header-right">
          <UButton v-if="canWrite" color="success" icon="i-lucide-message-circle" @click="aiEntryVisible = true">AI 盘点</UButton>
          <UButton color="neutral" variant="outline" icon="i-lucide-download" @click="handleExport">导出报告</UButton>
          <UButton color="neutral" variant="outline" icon="i-lucide-chart-column" @click="loadStatistics">刷新统计</UButton>
        </div>
      </header>

      <div class="search-bar">
        <UInput v-model="searchKeyword" icon="i-lucide-search" placeholder="搜索批次号/材料名称" class="w-70" data-testid="inventory-keyword" @keyup.enter="handleSearch">
          <template v-if="searchKeyword" #trailing>
            <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="searchKeyword = ''" />
          </template>
        </UInput>
        <USelectMenu v-model="statusModel" value-key="value" :items="statusItems" :search-input="false" class="w-40" data-testid="inventory-status" />
        <UButton icon="i-lucide-search" @click="handleSearch">搜索</UButton>
        <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" @click="handleRefresh">重置</UButton>
      </div>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="inventory-table">
        <template #empty>暂无数据</template>
        <template #batchNumber-cell="{ row }">{{ row.original.batchNumber }}</template>
        <!-- Bug 2 fix: MaterialBatchDTO 返回字段是 materialName, 不是 materialTypeName (后者在
             该 DTO 上不存在, 恒 undefined → 之前列永远显 "-", 仓管员看不到具体物料) -->
        <template #material-cell="{ row }">{{ row.original.materialName || row.original.materialTypeName || '-' }}</template>
        <!-- 🔴 2026-08-19: 「当前数量」的后端口径是 收货量 − 已用 − 已占用。
             在此之前 reserved_quantity 全库恒 0(prod 实测 65 个在用批次 0 个有值),
             这一列等价于 收货量 − 已用。「报工即占用」上线后, 已提交未结单的报工会给
             车间批次写上占用量 ⇒ 同一列会**变小**, 而界面上没有任何东西解释它为什么变小。
             ▎仓管看到的就是「库存凭空少了」。所以把被扣掉的那部分显式说出来。 -->
        <template #quantity-cell="{ row }">
          <div class="num">{{ row.original.currentQuantity ?? row.original.quantity ?? '-' }}</div>
          <div v-if="shouldShowReservedHold(row.original)" class="reserved-hold-hint">
            {{ reservedHoldLabel(row.original) }}
          </div>
        </template>
        <template #unit-cell="{ row }">{{ displayUnit(row.original.quantityUnit || row.original.unit) || '-' }}</template>
        <template #storageLocation-cell="{ row }"><span class="clip" :title="row.original.storageLocation || ''">{{ row.original.storageLocation || '' }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(getStatusType(row.original.status) as ElementTagType)" variant="soft">
            {{ getStatusText(row.original.status) }}
          </UBadge>
        </template>
        <template #expire-cell="{ row }">
          <span :class="getExpireDateClass(row.original)">
            {{ row.original.expirationDate || row.original.expireDate || '-' }}
          </span>
        </template>
        <template #updatedAt-cell="{ row }">
          {{ row.original.updatedAt ? row.original.updatedAt.replace('T', ' ').substring(0, 19) : '-' }}
        </template>
        <!--
          Sprint 6 W3-A — 行内 3-chip 链接计数 (文件 / 图片 / 合同).
          库存批次附件: 入库单据 / 抄码单 (DOCUMENT) + 现场照片 (PHOTO) + 质检报告 (CONTRACT).
          数据源: POST /attachments/batch-3chip-counts (batch, 避免 N+1).
        -->
        <template #attachments-header>
          <span class="attach-head" title="文件: 入库单据 / 抄码单；图片: 现场照片 / 视频；合同: 质检报告 / 证明。点查看可看附件清单">
            附件 <UIcon name="i-lucide-circle-help" class="attach-help" />
          </span>
        </template>
        <template #attachments-cell="{ row }">
          <LinkChipCell :counts="linkCountsFor(row.original.id)" />
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" icon="i-lucide-eye" @click="handleViewDetail(row.original)">查看</UButton>
            <UButton
              v-if="canWrite && (row.original.status === 'AVAILABLE' || row.original.status === 'IN_STOCK' || row.original.status === 'FRESH' || row.original.status === 'FROZEN')"
              size="xs"
              variant="link"
              color="warning"
              icon="i-lucide-pencil"
              @click="handleAdjust(row.original)"
            >调整</UButton>
          </div>
        </template>
      </UTable>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前库存${formatSummaryForAI(footerSummary)}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <span class="data-count">共 {{ pagination.total }} 条</span>
        <USelectMenu v-model="sizeModel" value-key="value" :items="sizeItems" :search-input="false" class="w-28" />
        <UPagination v-model:page="pageModel" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" />
      </div>
    </section>

    <!-- 调整库存对话框 -->
    <!-- Bug 2 (fool-proof Rule 2 上下文): 标题带物料名, 用户一眼看清在调整哪个物料的库存,
         而不是只看到一串批次号数字。 -->
    <UModal
      v-model:open="adjustDialogVisible"
      :title="adjustForm.materialName ? `调整库存 — ${adjustForm.materialName} (${adjustForm.batchNumber})` : '调整库存'"
      description="调整批次库存数量"
      :dismissible="false"
      :ui="{ content: 'max-w-[460px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="form">
          <UFormField label="物料名称">
            <UInput :model-value="adjustForm.materialName || '-'" disabled class="w-full" />
          </UFormField>
          <UFormField label="批次号">
            <UInput v-model="adjustForm.batchNumber" disabled class="w-full" />
          </UFormField>
          <UFormField label="当前数量">
            <UInput v-model.number="adjustForm.currentQuantity" type="number" disabled class="w-full" />
          </UFormField>
          <UFormField label="调整数量" required>
            <UInput v-model.number="adjustForm.adjustQuantity" type="number" step="1" class="w-full" data-testid="inventory-adjust-qty" />
            <div class="form-tip">
              正数增加，负数减少 ·
              调整后数量:
              <strong :style="{ color: adjustedQuantity < 0 ? 'var(--cs-danger)' : 'inherit' }">
                {{ adjustedQuantity }}
              </strong>
            </div>
            <div class="form-tip">
              "当前数量"在对话框打开时读取，若有其他用户同时调整，实际结果可能偏差。如需保证一致性请先刷新列表。
            </div>
          </UFormField>
          <!-- Bug 5 (fool-proof Rule 3): 调整原因改标准下拉, 与报损原因一致的约束选择,
               不是自由 textarea (之前用户可瞎写无统计价值)。 -->
          <UFormField label="调整原因" required>
            <USelectMenu v-model="adjustForm.reason" value-key="value" :items="adjustReasonOptions" :search-input="false" placeholder="选择调整原因" class="w-full" data-testid="inventory-adjust-reason" />
          </UFormField>
          <UFormField v-if="adjustForm.reason === 'OTHER'" label="补充说明" required>
            <UTextarea v-model="adjustForm.reasonOther" :rows="2" placeholder="请填写具体调整原因" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="adjustDialogVisible = false">取消</UButton>
        <UButton :loading="adjustLoading" :disabled="adjustedQuantity < 0" data-testid="inventory-adjust-submit" @click="submitAdjust">确定</UButton>
      </template>
    </UModal>

    <!-- 批次详情对话框 -->
    <UModal v-model:open="detailDialogVisible" title="批次详情" description="批次基础信息与调整/使用记录" :ui="{ content: 'max-w-[680px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div v-if="detailData" class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">批次号</span>
            <span class="detail-value">{{ detailData.batchNumber }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">材料类型</span>
            <span class="detail-value">{{ detailData.materialName || detailData.materialTypeName || '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">供应商</span>
            <span class="detail-value">{{ detailData.supplierName || '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">状态</span>
            <span><UBadge :color="badgeColor(getStatusType(detailData.status) as ElementTagType)" variant="soft">{{ getStatusText(detailData.status) }}</UBadge></span>
          </div>
          <div class="detail-item">
            <span class="detail-label">入库数量</span>
            <span class="detail-value">{{ detailData.receiptQuantity ?? detailData.inboundQuantity ?? '-' }} {{ displayUnit(detailData.quantityUnit || detailData.unit) }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">当前数量</span>
            <span class="detail-value">{{ detailData.currentQuantity ?? detailData.quantity ?? '-' }} {{ displayUnit(detailData.quantityUnit || detailData.unit) }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">已用数量</span>
            <span class="detail-value">{{ detailData.usedQuantity ?? '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">预留数量</span>
            <span class="detail-value">{{ detailData.reservedQuantity ?? '-' }}</span>
          </div>
          <div v-if="canViewPrice" class="detail-item">
            <span class="detail-label">单价</span>
            <span class="detail-value">{{ detailData.unitPrice != null ? `¥${detailData.unitPrice}` : '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">存储位置</span>
            <span class="detail-value">{{ detailData.storageLocation || '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">入库日期</span>
            <span class="detail-value">{{ detailData.receiptDate || detailData.inboundDate || '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">生产日期</span>
            <span class="detail-value">{{ detailData.productionDate || '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">过期日期</span>
            <span class="detail-value" :class="getExpireDateClass(detailData)">{{ detailData.expirationDate || detailData.expireDate || '-' }}</span>
          </div>
          <div class="detail-item wide">
            <span class="detail-label">备注</span>
            <span class="detail-value">{{ detailData.notes || '-' }}</span>
          </div>
        </div>

        <!-- 调整历史 -->
        <div v-if="adjustHistory.length > 0" class="history">
          <div class="history-title">调整/使用记录</div>
          <UTable :data="adjustHistory" :columns="historyColumns" class="cs-table history-table" data-testid="inventory-history">
            <template #adjustmentQuantity-cell="{ row }"><span class="num">{{ row.original.adjustmentQuantity }}</span></template>
            <template #quantityBefore-cell="{ row }"><span class="num">{{ row.original.quantityBefore }}</span></template>
            <template #quantityAfter-cell="{ row }"><span class="num">{{ row.original.quantityAfter }}</span></template>
          </UTable>
        </div>
        <div v-else-if="detailLoading" class="history-loading">加载中...</div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="detailDialogVisible = false">关闭</UButton>
      </template>
    </UModal>

    <!-- AI 盘点调整对话框 (Day 7, Issue #780.1) -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="STOCKTAKING_CONFIG"
      @fill-form="handleAiFill"
    />
  </div>
</template>

<style scoped>
.page-wrapper { display: flex; flex-direction: column; gap: var(--cs-space-3); padding: var(--cs-space-4); width: 100%; }
.statistics-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--cs-space-3); }
.stat-card { text-align: center; padding: var(--cs-space-3) 0; }
.cs-card + .cs-card { margin-top: 0; }
.stat-value { font-size: 28px; font-weight: 600; color: var(--cs-primary); }
.stat-label { font-size: var(--cs-font); color: var(--cs-muted); margin-top: 8px; }
.stat-card.warning .stat-value { color: var(--cs-warning); }
.stat-card.danger .stat-value { color: var(--cs-danger); }
.cs-card-head { padding-block: var(--cs-space-4); }
.page-title { font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.data-count { font-size: var(--cs-font-small); color: var(--cs-muted); font-weight: 400; }
.header-right { display: flex; gap: var(--cs-space-2); margin-left: auto; }
.search-bar { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.row-actions { display: flex; justify-content: center; gap: var(--cs-space-1); }
.pagination-wrapper { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
.clip { display: inline-block; max-width: 130px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
.attach-head { display: inline-flex; align-items: center; gap: 4px; }
.attach-help { cursor: help; color: var(--cs-muted); font-size: 12px; }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.form-tip { font-size: var(--cs-font-small); color: var(--cs-muted); margin-top: 4px; }
.expire-expired { color: var(--cs-danger); font-weight: 600; }
.expire-danger { color: var(--cs-warning); font-weight: 500; }
.expire-warning { color: var(--cs-muted); }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-4) var(--cs-space-6, 24px); }
.detail-item { display: flex; flex-direction: column; gap: 4px; }
.detail-item.wide { grid-column: span 2; }
.detail-label { font-size: var(--cs-font-small); color: var(--cs-muted); }
.detail-value { font-size: var(--cs-font); color: var(--cs-text); }
.history { margin-top: var(--cs-space-5); }
.history-title { font-weight: 600; margin-bottom: var(--cs-space-2); }
.history-table { max-height: 250px; overflow: auto; }
.history-loading { text-align: center; padding: var(--cs-space-5); color: var(--cs-muted); }
/* 占用说明: 比主数字弱一档, 但 ⛔ 不许弱到看不见 —— 它解释的正是主数字为什么变小。
   本仓栽过「把元素裁成 0 宽 → IntersectionObserver 恒不相交 → 9 个眉题从来没显形」,
   所以这里不用 height/opacity 做隐藏, 只降字号与灰度。 */
.reserved-hold-hint { margin-top: 2px; font-size: 12px; line-height: 1.3; color: var(--cs-warning); white-space: normal; }
</style>
