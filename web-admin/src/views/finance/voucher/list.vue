<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listVouchers,
  postVoucher,
  batchPostVouchers,
  voidVoucher,
  VOUCHER_STATUS_LABEL,
  VOUCHER_TYPE_LABEL,
  SOURCE_BUSINESS_TYPE_LABEL,
  type Voucher,
  type VoucherStatus,
  type VoucherType,
  type SourceBusinessType,
  type VoucherBatchPostResponse,
  type VoucherBatchPostResult,
} from '@/api/voucher';
import { formatAmount } from '@/utils/tableFormatters';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
import { enumLabel, voucherTypeText } from '@/utils/enumDisplay';
import { formatDateTime } from '@/utils/dateFormat';
import type { CsTableColumn } from '@/types/csTable';
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId ?? '');
const canWrite = computed(() => permissionStore.canWrite('finance'));

// ==================== State ====================

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const loading = ref(false);
const loadFailed = ref(false);
const tableData = ref<Voucher[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });

const ALL = 'ALL';
const filterStatus = ref<string>(ALL);
const filterType = ref<string>(ALL);
const filterSourceBusinessType = ref<string>(ALL);
const filterVoucherNumber = ref('');

const voidDialogVisible = ref(false);
const voidTargetId = ref('');
const voidTargetRow = ref<Voucher | null>(null); // Rule 2: 携带凭证上下文
const voidReason = ref('');
const voidOtherText = ref(''); // Rule 3: "其他" 时补充文本
const voiding = ref(false);

// 防呆 Rule 4: 过账是财务写操作(不可逆), 无 loading 保护时双击可能重复提交过账请求。
const postingIds = ref<Set<string>>(new Set());

// 批量过账 (Part 2 follow-up to #1228): 人工审核制下逐张过账负担太大, 支持多选批量过账。
const selectedIds = ref<Set<string>>(new Set());
const selectedRows = computed(() => tableData.value.filter((r) => selectedIds.value.has(r.id)));
const batchPosting = ref(false);
const batchResultVisible = ref(false);
const batchResult = ref<VoucherBatchPostResponse | null>(null);

// Rule 3: 作废原因标准选项
const VOID_REASON_OPTIONS = [
  { value: '录入有误', label: '录入有误' },
  { value: '业务撤销', label: '业务撤销' },
  { value: '重复生成', label: '重复生成' },
  { value: '科目错误', label: '科目错误' },
  { value: '_other', label: '其他原因' },
];

const STATUS_ITEMS = [{ label: '全部状态', value: ALL }, ...Object.entries(VOUCHER_STATUS_LABEL).map(([value, v]) => ({ label: v.text, value }))];
const TYPE_ITEMS = [{ label: '全部类型', value: ALL }, ...Object.entries(VOUCHER_TYPE_LABEL).map(([value, label]) => ({ label, value }))];
const SOURCE_ITEMS = [{ label: '全部来源业务', value: ALL }, ...Object.entries(SOURCE_BUSINESS_TYPE_LABEL).map(([value, label]) => ({ label, value }))];
// 没登记的来源业务不外露英文码, 统一写「其他业务」
const sourceText = (t: unknown) => SOURCE_BUSINESS_TYPE_LABEL[t as SourceBusinessType] ?? (t ? '其他业务' : '—');
const typeText = voucherTypeText;

const columns: CsTableColumn<Voucher>[] = [
  { id: 'select', header: '选择', enableSorting: false },
  { accessorKey: 'voucherNumber', header: '凭证字号' },
  { id: 'voucherType', header: '类型' },
  { accessorKey: 'voucherDate', header: '凭证日期' },
  { id: 'totalDebit', header: '借方合计', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'totalCredit', header: '贷方合计', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'sourceBusinessType', header: '来源业务' },
  { id: 'status', header: '状态' },
  { id: 'createdAt', header: '创建时间' },
  { id: 'actions', header: '操作' },
];
const failureColumns: CsTableColumn<VoucherBatchPostResult>[] = [
  { id: 'voucherNumber', header: '凭证字号' },
  { id: 'message', header: '失败原因' },
];
const failedResults = computed(() => (batchResult.value?.results ?? []).filter((r) => !r.success));

// ==================== Load ====================

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    const result = await listVouchers(factoryId.value, {
      status: filterStatus.value === ALL ? undefined : (filterStatus.value as VoucherStatus),
      type: filterType.value === ALL ? undefined : (filterType.value as VoucherType),
      sourceBusinessType: filterSourceBusinessType.value === ALL ? undefined : (filterSourceBusinessType.value as SourceBusinessType),
      page: pagination.value.page,
      size: pagination.value.size,
    });
    let rows = result.content ?? [];
    // 凭证字号前端过滤 (后端 list 端点暂无 keyword 参数)
    const kw = filterVoucherNumber.value.trim();
    if (kw) {
      const lower = kw.toLowerCase();
      rows = rows.filter(
        (r) =>
          String(r.voucherNumber || '').toLowerCase().includes(lower) ||
          String(r.sourceBusinessId || '').toLowerCase().includes(lower),
      );
    }
    tableData.value = rows;
    selectedIds.value = new Set(); // 换页/筛选后选择清空 (与原 el-table 行为一致)
    pagination.value.total = result.totalElements ?? 0;
  } catch (e: unknown) {
    loadFailed.value = true;
    ElMessage({
      message: e instanceof Error ? e.message : '凭证列表加载失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleReset() {
  filterStatus.value = ALL;
  filterType.value = ALL;
  filterSourceBusinessType.value = ALL;
  filterVoucherNumber.value = '';
  handleSearch();
}

function handlePageChange(p: number) {
  pagination.value.page = p;
  loadData();
}

// ==================== Actions ====================

function handleViewDetail(id: string) {
  router.push(`/finance/voucher/${id}`);
}

async function handlePost(id: string) {
  if (postingIds.value.has(id)) return;
  const ok = await confirmRef.value!.ask({ title: '确认过账', message: '确认将该凭证过账？过账后不可直接编辑。', confirmText: '确认过账' });
  if (ok === null) return;
  try {
    postingIds.value = new Set(postingIds.value).add(id);
    await postVoucher(factoryId.value, id);
    ElMessage.success('凭证已过账');
    loadData();
  } catch (e) {
    ElMessage({
      message: e instanceof Error ? e.message : '过账失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    const next = new Set(postingIds.value);
    next.delete(id);
    postingIds.value = next;
  }
}

// ==================== 批量过账 (Part 2 follow-up to #1228) ====================

/** 只有 DRAFT 凭证可被选中批量过账 (POSTED/VOID/REVERSED 不可再过账). */
function isRowSelectable(row: Voucher) {
  return row.status === 'DRAFT';
}

function toggleRow(row: Voucher, on: boolean) {
  const next = new Set(selectedIds.value);
  if (on) next.add(row.id); else next.delete(row.id);
  selectedIds.value = next;
}

const selectableRows = computed(() => tableData.value.filter(isRowSelectable));
const allSelected = computed(() => selectableRows.value.length > 0 && selectableRows.value.every((r) => selectedIds.value.has(r.id)));
function toggleAll(on: boolean) {
  selectedIds.value = on ? new Set(selectableRows.value.map((r) => r.id)) : new Set();
}

// Rule 1 (fool-proof-design): 预先显示边界 — 借方合计, 让财务人员过账前就知道总金额.
const selectedTotalDebit = computed(() =>
  selectedRows.value.reduce((sum, r) => sum + Number(r.totalDebit || 0), 0),
);

async function handleBatchPost() {
  if (selectedRows.value.length === 0 || batchPosting.value) return;
  // Rule 2 (fool-proof-design): 携带数量 + 金额 context, 不是笼统的"确认批量过账?"
  const ok = await confirmRef.value!.ask({
    title: '批量过账确认',
    message: `确认批量过账选中的 ${selectedRows.value.length} 张凭证 (借方合计 ${formatAmount(selectedTotalDebit.value)})？过账后不可直接编辑, 已过账的凭证会自动跳过。`,
    confirmText: '确认过账',
  });
  if (ok === null) return; // 用户取消
  batchPosting.value = true;
  try {
    const ids = selectedRows.value.map((r) => r.id);
    const result = await batchPostVouchers(factoryId.value, ids);
    batchResult.value = result;
    batchResultVisible.value = true;
    selectedIds.value = new Set();
    loadData();
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '批量过账失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    batchPosting.value = false;
  }
}

function openVoidDialog(row: Voucher) {
  voidTargetId.value = row.id;
  voidTargetRow.value = row; // Rule 2: 保存完整行供 dialog 显示
  voidReason.value = '';
  voidOtherText.value = '';
  voidDialogVisible.value = true;
}

async function submitVoid() {
  if (!voidReason.value) {
    ElMessage.warning('请选择作废原因');
    return;
  }
  if (voidReason.value === '_other' && !voidOtherText.value.trim()) {
    ElMessage.warning('请填写具体作废原因');
    return;
  }
  const finalReason =
    voidReason.value === '_other' ? voidOtherText.value.trim() : voidReason.value;
  voiding.value = true;
  try {
    await voidVoucher(factoryId.value, voidTargetId.value, finalReason);
    ElMessage.success('凭证已作废');
    voidDialogVisible.value = false;
    loadData();
  } catch (e: unknown) {
    ElMessage({
      message: e instanceof Error ? e.message : '作废失败，请检查网络',
      type: 'error',
      duration: 0,
      showClose: true,
    });
  } finally {
    voiding.value = false;
  }
}

onMounted(() => loadData());
</script>

<template>
  <div class="page" data-testid="voucher-list-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>凭证列表</h2>
        <span class="count">共 {{ pagination.total }} 张</span>
        <div class="head-actions">
          <UButton
            v-if="canWrite"
            color="success"
            icon="i-lucide-check-check"
            :disabled="selectedRows.length === 0"
            :loading="batchPosting"
            data-testid="voucher-batch-post"
            @click="handleBatchPost"
          >批量过账{{ selectedRows.length > 0 ? `(${selectedRows.length})` : '' }}</UButton>
        </div>
      </header>

      <div class="filter-bar">
        <UInput v-model="filterVoucherNumber" placeholder="搜索凭证字号 / 来源单号" class="w-52" data-testid="voucher-keyword" @keyup.enter="handleSearch" />
        <USelect v-model="filterStatus" :items="STATUS_ITEMS" class="w-36" data-testid="voucher-status-filter" @update:model-value="handleSearch" />
        <USelect v-model="filterType" :items="TYPE_ITEMS" class="w-40" data-testid="voucher-type-filter" @update:model-value="handleSearch" />
        <USelect v-model="filterSourceBusinessType" :items="SOURCE_ITEMS" class="w-44" data-testid="voucher-source-filter" @update:model-value="handleSearch" />
        <UButton data-testid="voucher-search" @click="handleSearch">搜索</UButton>
        <UButton color="neutral" variant="outline" @click="handleReset">重置</UButton>
        <UCheckbox v-if="canWrite" :model-value="allSelected" :disabled="selectableRows.length === 0" label="全选本页草稿" data-testid="voucher-select-all" @update:model-value="toggleAll(!!$event)" />
      </div>

      <UAlert v-if="loadFailed" color="error" variant="soft" icon="i-lucide-circle-alert" class="mx-5 mb-3" data-testid="voucher-load-error"
        title="凭证列表暂时没取到，请点「搜索」重试。" />

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="voucher-table">
        <template #empty>暂无凭证数据。可通过业务单据（销售/采购/报销等）自动生成凭证，或检查搜索条件。</template>
        <template #select-cell="{ row }">
          <UCheckbox v-if="isRowSelectable(row.original)" :model-value="selectedIds.has(row.original.id)" data-testid="voucher-select-row" @update:model-value="toggleRow(row.original, !!$event)" />
        </template>
        <template #voucherType-cell="{ row }">{{ typeText(row.original.voucherType) }}</template>
        <template #totalDebit-cell="{ row }"><span class="num">{{ formatAmount(row.original.totalDebit) }}</span></template>
        <template #totalCredit-cell="{ row }"><span class="num">{{ formatAmount(row.original.totalCredit) }}</span></template>
        <template #createdAt-cell="{ row }">{{ formatDateTime(row.original.createdAt) }}</template>
        <template #sourceBusinessType-cell="{ row }"><span class="muted">{{ sourceText(row.original.sourceBusinessType) }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(VOUCHER_STATUS_LABEL[row.original.status as VoucherStatus]?.type as ElementTagType)" variant="soft">
            {{ VOUCHER_STATUS_LABEL[row.original.status as VoucherStatus]?.text || enumLabel(row.original.status) }}
          </UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton variant="link" size="xs" data-testid="voucher-view" @click="handleViewDetail(row.original.id)">查看</UButton>
            <UButton v-if="canWrite && row.original.status === 'DRAFT'" color="success" variant="link" size="xs" :loading="postingIds.has(row.original.id)" data-testid="voucher-post" @click="handlePost(row.original.id)">过账</UButton>
            <UButton v-if="canWrite && (row.original.status === 'DRAFT' || row.original.status === 'POSTED')" color="error" variant="link" size="xs" data-testid="voucher-void" @click="openVoidDialog(row.original)">作废</UButton>
          </div>
        </template>
      </UTable>

      <footer v-if="pagination.total > pagination.size" class="pager">
        <UPagination :page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </footer>
    </section>

    <!-- 作废凭证 (Rule 2: 凭证号+类型+金额 context; Rule 3: 原因下拉) -->
    <UModal
      v-model:open="voidDialogVisible"
      :title="`作废凭证 — ${voidTargetRow?.voucherNumber || '—'}`"
      description="作废后不可恢复"
      :ui="{ content: 'max-w-[500px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="form">
          <dl v-if="voidTargetRow" class="desc">
            <dt>凭证字号</dt><dd>{{ voidTargetRow.voucherNumber }}</dd>
            <dt>类型</dt><dd>{{ typeText(voidTargetRow.voucherType) }}</dd>
            <dt>凭证日期</dt><dd>{{ voidTargetRow.voucherDate || '—' }}</dd>
            <dt>借方合计</dt><dd>{{ formatAmount(voidTargetRow.totalDebit) }}</dd>
          </dl>
          <UAlert color="warning" variant="soft" icon="i-lucide-triangle-alert" title="凭证作废后状态变为「已作废」，不可恢复。" />
          <UFormField label="作废原因" required>
            <USelect v-model="voidReason" :items="VOID_REASON_OPTIONS" placeholder="请选择作废原因" class="w-full" data-testid="voucher-void-reason" />
          </UFormField>
          <UFormField v-if="voidReason === '_other'" label="具体原因" required>
            <UTextarea v-model="voidOtherText" :rows="2" :maxlength="200" placeholder="请填写具体作废原因" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="voidDialogVisible = false">取消</UButton>
        <UButton color="error" :loading="voiding" :disabled="!voidReason || (voidReason === '_other' && !voidOtherText.trim())" data-testid="voucher-void-submit" @click="submitVoid">确认作废</UButton>
      </template>
    </UModal>

    <!-- 批量过账结果 (N 成功, M 失败 + 原因) -->
    <UModal v-model:open="batchResultVisible" title="批量过账结果" description="批量过账的成功与失败明细" :ui="{ content: 'max-w-[580px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div v-if="batchResult" class="form">
          <UAlert
            :color="batchResult.failureCount === 0 ? 'success' : 'warning'"
            variant="soft"
            :title="`共 ${batchResult.total} 张：${batchResult.successCount} 成功，${batchResult.failureCount} 失败`"
            data-testid="voucher-batch-summary"
          />
          <template v-if="batchResult.failureCount > 0">
            <div class="muted">失败明细：</div>
            <UTable :data="failedResults" :columns="failureColumns" class="cs-table" data-testid="voucher-batch-failures">
              <template #voucherNumber-cell="{ row }">{{ row.original.voucherNumber || '—' }}</template>
              <template #message-cell="{ row }">{{ row.original.message || '—' }}</template>
            </UTable>
          </template>
          <div v-if="batchResult.results.some((r) => r.skipped)" class="muted">
            {{ batchResult.results.filter((r) => r.skipped).length }} 张已过账，已自动跳过。
          </div>
        </div>
      </template>
      <template #footer>
        <UButton @click="batchResultVisible = false">关闭</UButton>
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
.filter-bar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-2); padding: var(--cs-space-3) var(--cs-space-5); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
.form { display: grid; gap: var(--cs-space-3); }
.desc { display: grid; grid-template-columns: 6em 1fr 6em 1fr; gap: var(--cs-space-2) var(--cs-space-3); margin: 0; font-size: 13px; padding: var(--cs-space-3); background: var(--cs-surface-subtle); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.desc dt { color: var(--cs-muted); }
.desc dd { margin: 0; color: var(--cs-text); }
.muted { color: var(--cs-muted); font-size: var(--cs-font-small); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.num { font-variant-numeric: tabular-nums; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
