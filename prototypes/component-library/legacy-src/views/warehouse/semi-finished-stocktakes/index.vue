<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, View, Edit, Check, Close } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';
// 盲盘录入契约 —— 账面数/差异只能经这几个出口露出来, 模板里不要直接读 row.systemQty
import {
  commitRowFromServer,
  countUncountedRows,
  isRowLocked,
  revealedDifference,
  revealedSystemQty,
  type BlindCountRow,
} from '../stocktakes/stocktakeCount';

// ────────────────────────────────────────────────────────────────────────────
// 半成品盘点 (Semi-finished stock-take) — SP7 式 + 审批
// 镜像 warehouse/stocktakes/index.vue, 目标改为 SemiFinishedInventory (半成品 WIP)。
// 无仓库维度 (半成品是工厂级 WIP); 明细行以 工序批次号 / 产品类型 / 单位 展示。
// ────────────────────────────────────────────────────────────────────────────
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const statusFilter = ref('');

const statusMap: Record<string, { label: string; type: string }> = {
  INITIATED: { label: '已发起', type: 'info' },
  COUNTING: { label: '盘点中', type: 'warning' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已审批', type: 'success' },
  APPLIED: { label: '已应用', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
};

// ────────────────────────────────────────────────────────────────────────────
// Load list
// ────────────────────────────────────────────────────────────────────────────
async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {
      page: Math.max(0, pagination.value.page - 1), // el-pagination 1-based; backend 0-based
      size: pagination.value.size,
    };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/semi-finished-stocktakes`, { params });
    if (res.success && res.data) {
      const d = res.data;
      tableData.value = d.content || d || [];
      pagination.value.total = d.totalElements ?? tableData.value.length;
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}

// ────────────────────────────────────────────────────────────────────────────
// Monthly prompt
// ────────────────────────────────────────────────────────────────────────────
const currentMonth = computed(() => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
});
const hasThisMonthTask = computed(() =>
  tableData.value.some((row) => String(row.periodMonth || '').startsWith(currentMonth.value))
);

// ────────────────────────────────────────────────────────────────────────────
// Initiate dialog
// ────────────────────────────────────────────────────────────────────────────
const initiateDialogVisible = ref(false);
const initiateLoading = ref(false);
const initiateForm = reactive({ periodMonth: currentMonth.value, notes: '' });

function openInitiateDialog() {
  initiateForm.periodMonth = currentMonth.value;
  initiateForm.notes = '';
  initiateDialogVisible.value = true;
}

async function submitInitiate() {
  if (!initiateForm.periodMonth) {
    ElMessage({ message: '请选择盘点月份', type: 'warning', duration: 3000 });
    return;
  }
  initiateLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/semi-finished-stocktakes`, {
      periodMonth: initiateForm.periodMonth,
      notes: initiateForm.notes,
    });
    if (res.success) {
      ElMessage({ message: '半成品盘点任务已发起', type: 'success', duration: 3000 });
      initiateDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '发起失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '发起失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    initiateLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Detail dialog
// ────────────────────────────────────────────────────────────────────────────
const detailDialogVisible = ref(false);
const detailData = ref<TableRow | null>(null);
const diffPreview = ref<TableRow[]>([]);
const diffLoading = ref(false);

async function openDetail(row: TableRow) {
  detailData.value = row;
  detailDialogVisible.value = true;
  diffPreview.value = [];
  if (row.status === 'APPROVED' || row.status === 'APPLIED') {
    await loadDiffPreview(String(row.id));
  }
}

async function loadDiffPreview(stocktakeId: string) {
  diffLoading.value = true;
  try {
    const res = await get(`/${factoryId.value}/semi-finished-stocktakes/${stocktakeId}/diff-preview`);
    if (res.success && res.data) {
      diffPreview.value = res.data.diffLines || [];
    }
  } catch { /* silent */ } finally {
    diffLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Count entry dialog (录入实盘数量)
// ────────────────────────────────────────────────────────────────────────────
const countDialogVisible = ref(false);
const countLoading = ref(false);
// 盲盘录入契约与原料盘点共用一份实现 (../stocktakes/stocktakeCount)，
// ⛔ 不在这里复制第二份 —— 同一条纪律有两份, 它一定会漂。
interface SemiFinishedCountRow extends BlindCountRow {
  id: string;
  batchNo: string;
  productTypeId: string;
  unit: string;
  notes: string;
}
const countItems = ref<SemiFinishedCountRow[]>([]);
const countStocktakeId = ref('');
const countStocktakeNo = ref('');
/** 本次会话里已提交过至少一行 —— 决定关闭时要不要刷新列表 */
const countCommittedAny = ref(false);
/** 正在提交的行 id, 只转这一行的 loading */
const countSubmittingRowId = ref('');
// 撤销小结告警点 (盘点期间被撤销过小结的半成品/成品批次, 提示核实实物)
const countWarnings = ref<Array<{ batchNo: string; message: string }>>([]);

async function openCountDialog(row: TableRow) {
  countStocktakeId.value = String(row.id);
  countStocktakeNo.value = String(row.stocktakeNo || row.id);
  countLoading.value = true;
  countDialogVisible.value = true;
  countWarnings.value = [];
  try {
    const res = await get(`/${factoryId.value}/semi-finished-stocktakes/${row.id}`);
    if (res.success && res.data) {
      countWarnings.value = (res.data.warnings || []) as Array<{ batchNo: string; message: string }>;
      const items = res.data.items || [];
      countItems.value = items.map((item: TableRow) => ({
        id: String(item.id),
        batchNo: String(item.intermediateBatchNo || ''),
        productTypeId: String(item.productTypeId || ''),
        unit: String(item.unit || ''),
        systemQty: Number(item.systemQty ?? 0),
        actualQty: item.actualQty != null ? Number(item.actualQty) : null,
        notes: String(item.notes || ''),
        // 服务端已有实盘数 = 这行上一轮就提交过 ⇒ 已锁定, 账面/差异可见
        committed: item.actualQty != null,
        differenceQty: item.differenceQty != null ? Number(item.differenceQty) : null,
        differenceType: item.differenceType != null ? String(item.differenceType) : null,
      }));
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    countLoading.value = false;
  }
}

const countUncounted = computed(() => countUncountedRows(countItems.value));

/**
 * 盲盘: **逐行提交**。提交 = 落库 + 锁定 + 揭示账面/差异, 三件事同时发生。
 *
 * ⛔ 不做批量「保存暂存」—— 一次提交多行等于让人先把整张表填完再一起看差异,
 *    中途任何一行的差异都会污染后面几行的判断。逐行提交才保住行与行的独立性。
 *
 * backend PUT /items 收的是 { itemId, actualQty, notes } 的数组, actualQty
 * 服务端 @NotNull 且对**整个数组**校验, 所以这里只发这一行。
 */
async function submitCountRow(row: SemiFinishedCountRow) {
  if (isRowLocked(row)) return;
  if (row.actualQty == null) {
    ElMessage({ message: '请先填写本行实盘数量再提交', type: 'warning', duration: 3000 });
    return;
  }
  countSubmittingRowId.value = row.id;
  try {
    const res = await put(
      `/${factoryId.value}/semi-finished-stocktakes/${countStocktakeId.value}/items`,
      [{ itemId: row.id, actualQty: row.actualQty, notes: row.notes }],
    );
    if (!res.success) {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true });
      return;
    }
    // 差异回读服务端算的那一份 (SemiFinishedStocktakeItem.differenceQty)。
    // ⛔ 不在前端做 actualQty - systemQty。
    const detail = await get(`/${factoryId.value}/semi-finished-stocktakes/${countStocktakeId.value}`);
    const serverItem = ((detail.success && detail.data?.items) || [])
      .find((it: TableRow) => String(it.id) === row.id);
    if (!serverItem) {
      ElMessage({
        message: '已提交，但没能读回服务端算的差异；请关闭后重新打开录入核对。',
        type: 'warning', duration: 0, showClose: true,
      });
      return;
    }
    const index = countItems.value.findIndex((it) => it.id === row.id);
    if (index >= 0) {
      countItems.value[index] = commitRowFromServer(countItems.value[index], {
        actualQty: serverItem.actualQty != null ? Number(serverItem.actualQty) : null,
        differenceQty: serverItem.differenceQty != null ? Number(serverItem.differenceQty) : null,
        differenceType: serverItem.differenceType != null ? String(serverItem.differenceType) : null,
      });
    }
    countCommittedAny.value = true;
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '提交失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    countSubmittingRowId.value = '';
  }
}

function closeCountDialog() {
  countDialogVisible.value = false;
  if (countCommittedAny.value) {
    countCommittedAny.value = false;
    loadData();
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Submit for approval
// ────────────────────────────────────────────────────────────────────────────
const submitLoading = ref(false);

async function submitForApproval(row: TableRow) {
  await ElMessageBox.confirm(
    `确认提交半成品盘点任务 ${row.stocktakeNo || row.id} 审批？\n月份：${row.periodMonth}`,
    '提交审批',
    { confirmButtonText: '确认提交', cancelButtonText: '取消', type: 'warning' }
  ).catch(() => { throw new Error('cancel'); });

  submitLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/semi-finished-stocktakes/${row.id}/submit`, {});
    if (res.success) {
      ElMessage({ message: '已提交审批', type: 'success', duration: 3000 });
      loadData();
    } else {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    if (String((e as Error).message) !== 'cancel') {
      ElMessage({ message: String((e as Error).message || '提交失败'), type: 'error', duration: 0, showClose: true });
    }
  } finally {
    submitLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Approve dialog (财务审批)
// ────────────────────────────────────────────────────────────────────────────
const approveDialogVisible = ref(false);
const approveLoading = ref(false);
const approveRow = ref<TableRow | null>(null);
const approveNote = ref('');

function openApproveDialog(row: TableRow) {
  approveRow.value = row;
  approveNote.value = '';
  approveDialogVisible.value = true;
}

async function submitApprove() {
  if (!approveRow.value) return;
  approveLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/semi-finished-stocktakes/${approveRow.value.id}/approve`, { notes: approveNote.value });
    if (res.success) {
      ElMessage({ message: '审批通过', type: 'success', duration: 3000 });
      approveDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '审批失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '审批失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    approveLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Reject dialog
// ────────────────────────────────────────────────────────────────────────────
const rejectDialogVisible = ref(false);
const rejectLoading = ref(false);
const rejectRow = ref<TableRow | null>(null);
const rejectReasonSelect = ref('');
const rejectReasonOther = ref('');

const rejectReasonOptions = [
  { value: 'DATA_ERROR', label: '数据填写有误' },
  { value: 'INCOMPLETE', label: '盘点不完整' },
  { value: 'WRONG_PERIOD', label: '月份不符' },
  { value: 'RECOUNT_NEEDED', label: '需要重盘' },
  { value: 'OTHER', label: '其他' },
];

function openRejectDialog(row: TableRow) {
  rejectRow.value = row;
  rejectReasonSelect.value = '';
  rejectReasonOther.value = '';
  rejectDialogVisible.value = true;
}

async function submitReject() {
  if (!rejectRow.value) return;
  const reason = rejectReasonSelect.value === 'OTHER'
    ? rejectReasonOther.value.trim()
    : rejectReasonOptions.find((o) => o.value === rejectReasonSelect.value)?.label || '';
  if (!reason) {
    ElMessage({ message: '请选择驳回原因', type: 'warning', duration: 3000 });
    return;
  }
  rejectLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/semi-finished-stocktakes/${rejectRow.value.id}/reject`, { reason });
    if (res.success) {
      ElMessage({ message: '已驳回', type: 'success', duration: 3000 });
      rejectDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '驳回失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '驳回失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    rejectLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Apply diff (审批后)
// ────────────────────────────────────────────────────────────────────────────
const applyLoading = ref(false);

async function applyDiff(row: TableRow) {
  await ElMessageBox.confirm(
    `确认将半成品盘点 ${row.stocktakeNo || row.id} 的差异应用到库存？\n月份：${row.periodMonth}\n⚠️ 此操作会把半成品可用量校准为实盘数，不可撤销。`,
    '应用盘点差异',
    { confirmButtonText: '确认应用', cancelButtonText: '取消', type: 'warning' }
  ).catch(() => { throw new Error('cancel'); });

  applyLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/semi-finished-stocktakes/${row.id}/apply`, {});
    if (res.success) {
      ElMessage({ message: '盘点差异已应用到半成品库存', type: 'success', duration: 3000 });
      loadData();
    } else {
      ElMessage({ message: res.message || '应用失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    if (String((e as Error).message) !== 'cancel') {
      ElMessage({ message: String((e as Error).message || '应用失败'), type: 'error', duration: 0, showClose: true });
    }
  } finally {
    applyLoading.value = false;
  }
}

onMounted(() => loadData());
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <h2>半成品盘点</h2>
      <div class="header-actions">
        <el-select
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          style="width: 160px; margin-right: 12px"
          @change="handleFilterChange"
        >
          <el-option v-for="(v, k) in statusMap" :key="k" :label="v.label" :value="k" />
        </el-select>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openInitiateDialog">
          发起盘点
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!hasThisMonthTask && !loading"
      :title="`本月 (${currentMonth}) 尚未创建半成品盘点任务，建议每月发起一次盘点。`"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
    />

    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="半成品实盘数录入后暂存，审批通过后才正式把可用量校准为实盘数。"
      style="margin-bottom: 16px"
    />

    <el-card>
      <el-table v-loading="loading" :data="tableData" row-key="id" stripe>
        <el-table-column label="盘点单号" prop="stocktakeNo" min-width="180" />
        <el-table-column label="盘点月份" prop="periodMonth" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="(statusMap[String(row.status)]?.type as 'info' | 'warning' | 'success' | 'danger') || 'info'" size="small">
              {{ statusMap[String(row.status)]?.label || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起人" prop="initiatedBy" width="110" />
        <el-table-column label="发起时间" prop="initiatedAt" min-width="160">
          <template #default="{ row }">
            {{ row.initiatedAt ? String(row.initiatedAt).replace('T', ' ').slice(0, 16) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="审批人" prop="approvedBy" width="110">
          <template #default="{ row }">{{ row.approvedBy || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="View" @click="openDetail(row)">查看</el-button>
            <el-button
              v-if="canWrite && (row.status === 'INITIATED' || row.status === 'COUNTING')"
              size="small"
              type="primary"
              :icon="Edit"
              @click="openCountDialog(row)"
            >录入</el-button>
            <el-button
              v-if="canWrite && (row.status === 'INITIATED' || row.status === 'COUNTING')"
              size="small"
              type="warning"
              :loading="submitLoading"
              @click="submitForApproval(row)"
            >提交审批</el-button>
            <el-button
              v-if="canWrite && row.status === 'PENDING_APPROVAL'"
              size="small"
              type="success"
              :icon="Check"
              @click="openApproveDialog(row)"
            >审批</el-button>
            <el-button
              v-if="canWrite && row.status === 'PENDING_APPROVAL'"
              size="small"
              type="danger"
              :icon="Close"
              @click="openRejectDialog(row)"
            >驳回</el-button>
            <el-button
              v-if="canWrite && row.status === 'APPROVED'"
              size="small"
              type="danger"
              :loading="applyLoading"
              @click="applyDiff(row)"
            >应用差异</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <el-pagination
          v-model:current-page="pagination.page"
          :page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <!-- ──────────────── Initiate Dialog ──────────────── -->
    <el-dialog v-model="initiateDialogVisible" title="发起半成品盘点任务" width="480px">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="发起后系统自动快照当前全部「可用」半成品行的账面数量，供逐行录入实盘数。"
        style="margin-bottom: 16px"
      />
      <el-form label-width="100px">
        <el-form-item label="盘点月份" required>
          <el-input v-model="initiateForm.periodMonth" placeholder="YYYY-MM" style="width: 160px" />
          <span style="margin-left: 8px; color: #909399; font-size: 12px">格式: 2026-06</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="initiateForm.notes" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="initiateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="initiateLoading" @click="submitInitiate">确认发起</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Detail Dialog ──────────────── -->
    <el-dialog v-model="detailDialogVisible" :title="`半成品盘点详情 — ${detailData?.stocktakeNo || ''}`" width="760px">
      <template v-if="detailData">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="盘点单号">{{ detailData.stocktakeNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="(statusMap[String(detailData.status)]?.type as 'info' | 'warning' | 'success' | 'danger') || 'info'" size="small">
              {{ statusMap[String(detailData.status)]?.label || detailData.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="盘点月份">{{ detailData.periodMonth }}</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ detailData.initiatedBy }}</el-descriptions-item>
          <el-descriptions-item label="发起时间">{{ detailData.initiatedAt ? String(detailData.initiatedAt).replace('T', ' ').slice(0, 16) : '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detailData.approvedBy" label="审批人">{{ detailData.approvedBy }}</el-descriptions-item>
          <el-descriptions-item v-if="detailData.rejectReason" label="驳回原因" :span="2">
            <el-text type="danger">{{ detailData.rejectReason }}</el-text>
          </el-descriptions-item>
          <el-descriptions-item v-if="detailData.notes" label="备注" :span="2">{{ detailData.notes }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="detailData.status === 'APPROVED' || detailData.status === 'APPLIED'" style="margin-top: 20px">
          <div style="font-weight: 600; margin-bottom: 8px; color: #1B65A8">
            盘点差异预览
            <el-text type="warning" style="font-size: 12px; margin-left: 8px">（已审批，应用后校准库存）</el-text>
          </div>
          <el-table v-loading="diffLoading" :data="diffPreview" size="small" border>
            <el-table-column label="工序批次号" prop="intermediateBatchNo" min-width="150" />
            <el-table-column label="产品类型" prop="productTypeId" min-width="110" />
            <el-table-column label="单位" prop="unit" width="70" />
            <el-table-column label="系统库存" prop="systemQty" width="100" align="right" />
            <el-table-column label="实盘数量" prop="actualQty" width="100" align="right">
              <template #default="{ row }">{{ row.actualQty ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="差异" prop="differenceQty" width="90" align="right">
              <template #default="{ row }">
                <span :style="{ color: Number(row.differenceQty) < 0 ? '#f56c6c' : Number(row.differenceQty) > 0 ? '#67c23a' : '' }">
                  {{ row.differenceQty != null ? (Number(row.differenceQty) > 0 ? '+' : '') + row.differenceQty : '—' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="类型" prop="differenceType" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.differenceType === 'SURPLUS'" type="success" size="small">盘盈</el-tag>
                <el-tag v-else-if="row.differenceType === 'SHORTAGE'" type="danger" size="small">盘亏</el-tag>
                <el-tag v-else-if="row.differenceType === 'MATCH'" type="info" size="small">平衡</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Count Entry Dialog ──────────────── -->
    <el-dialog v-model="countDialogVisible" :title="`录入实盘数量 — ${countStocktakeNo}`" width="820px">
      <!-- 撤销小结告警点: 本期间被撤销过小结的半成品/成品批次, 提示核实实物 -->
      <el-alert
        v-if="countWarnings.length > 0"
        type="warning"
        show-icon
        :closable="false"
        title="本期间有撤销过小结的批次，请核实实物："
        style="margin-bottom: 12px"
      >
        <ul style="margin: 4px 0 0; padding-left: 18px;">
          <li v-for="(w, i) in countWarnings" :key="i">{{ w.message }}</li>
        </ul>
      </el-alert>
      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="盲盘：先数实物、填数量、点“提交”，提交后才显示账面数量和差异，且该行不可再改。提交后仍为暂存，审批并批准后才把半成品可用量校准为实盘数。"
        style="margin-bottom: 16px"
      />
      <div style="margin-bottom: 12px">
        <el-text :type="countUncounted > 0 ? 'warning' : 'success'">
          {{ countUncounted > 0 ? `尚有${countUncounted}行未提交` : '全部行已提交' }}
        </el-text>
      </div>
      <el-table v-loading="countLoading" :data="countItems" size="small" border>
        <el-table-column label="工序批次号" prop="batchNo" min-width="150" />
        <el-table-column label="产品类型" prop="productTypeId" min-width="100" />
        <!-- 账面数量: 本行提交之后才露出来 (盲盘)。⛔ 不要改回直接读 row.systemQty -->
        <el-table-column label="账面数量" width="120" align="right">
          <template #default="{ row }">
            <span v-if="revealedSystemQty(row) !== null">{{ revealedSystemQty(row) }} {{ row.unit }}</span>
            <span v-else style="color: #c0c4cc">提交后显示</span>
          </template>
        </el-table-column>
        <el-table-column label="实盘数量" width="140">
          <template #default="{ row }">
            <el-input-number
              v-model="row.actualQty"
              :min="0"
              :precision="2"
              size="small"
              style="width: 120px"
              placeholder="实盘数量"
              :disabled="isRowLocked(row)"
            />
          </template>
        </el-table-column>
        <!-- 差异: 取服务端算好的 differenceQty; 前端不再做 actualQty - systemQty -->
        <el-table-column label="差异" width="110" align="right">
          <template #default="{ row }">
            <span v-if="revealedDifference(row) !== null">
              <span :style="{ color: Number(revealedDifference(row)) < 0 ? '#f56c6c' : Number(revealedDifference(row)) > 0 ? '#67c23a' : '' }">
                {{ Number(revealedDifference(row)) > 0 ? '+' : '' }}{{ revealedDifference(row) }}
              </span>
            </span>
            <span v-else style="color: #c0c4cc">提交后显示</span>
          </template>
        </el-table-column>
        <el-table-column label="提交" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="isRowLocked(row)" type="success" size="small" disable-transitions>已提交锁定</el-tag>
            <el-button
              v-else
              size="small"
              type="primary"
              :loading="countSubmittingRowId === row.id"
              :disabled="row.actualQty == null"
              :aria-label="`提交 ${row.batchNo} 的实盘数量`"
              @click="submitCountRow(row)"
            >提交</el-button>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="150">
          <template #default="{ row }">
            <!-- 备注与实盘数一起提交, 所以锁定后同样不可改 -->
            <el-input v-model="row.notes" size="small" placeholder="可选备注" :disabled="isRowLocked(row)" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <!-- 逐行提交, 没有批量保存 —— 关闭只是关闭, 不会再落任何数据 -->
        <el-button type="primary" @click="closeCountDialog">关闭</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Approve Dialog ──────────────── -->
    <el-dialog v-model="approveDialogVisible" title="审批半成品盘点任务" width="480px">
      <template v-if="approveRow">
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item label="盘点单号">{{ approveRow.stocktakeNo }}</el-descriptions-item>
          <el-descriptions-item label="盘点月份">{{ approveRow.periodMonth }}</el-descriptions-item>
          <el-descriptions-item label="发起人">{{ approveRow.initiatedBy }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="80px">
          <el-form-item label="审批备注">
            <el-input v-model="approveNote" type="textarea" :rows="2" placeholder="可选" />
          </el-form-item>
        </el-form>
        <el-alert type="warning" show-icon :closable="false" title="审批后差异数据锁定，可进一步「应用差异」把可用量校准为实盘数。" />
      </template>
      <template #footer>
        <el-button @click="approveDialogVisible = false">取消</el-button>
        <el-button type="success" :loading="approveLoading" @click="submitApprove">确认审批通过</el-button>
      </template>
    </el-dialog>

    <!-- ──────────────── Reject Dialog ──────────────── -->
    <el-dialog v-model="rejectDialogVisible" title="驳回半成品盘点任务" width="480px">
      <template v-if="rejectRow">
        <el-descriptions :column="2" border style="margin-bottom: 16px">
          <el-descriptions-item label="盘点单号">{{ rejectRow.stocktakeNo }}</el-descriptions-item>
          <el-descriptions-item label="盘点月份">{{ rejectRow.periodMonth }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="100px">
          <el-form-item label="驳回原因" required>
            <el-select v-model="rejectReasonSelect" placeholder="选择驳回原因" style="width: 100%">
              <el-option v-for="opt in rejectReasonOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="rejectReasonSelect === 'OTHER'" label="补充说明" required>
            <el-input v-model="rejectReasonOther" type="textarea" :rows="2" placeholder="请填写具体原因" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejectLoading" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
  background: #F4F6F9;
  min-height: 100%;
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1B65A8;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.el-card {
  border-radius: 10px;
}
</style>
