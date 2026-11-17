<script setup lang="ts">
/**
 * 销售运营报价管理 — V3 P0-4 (Round 2 Agent B 8.5/10 设计落地)
 *
 * 客户原话 (会议 1670-1750s):
 * "样品过了以后, 像类似于提交一个审批, 对应的人员去报价的;
 *  运营报完价以后调过去审批审批完 ok 没问题, 然后给到销售这边."
 *
 * 状态机 (3 段式):
 *   PENDING_QUOTE → 销售运营录价 → PENDING_APPROVAL → 主管审批 → APPROVED
 *                                                              ↘ REJECTED → revise → PENDING_QUOTE
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { ElMessage } from 'element-plus';
import { formatAmount } from '@/utils/tableFormatters';
import { handleCatchError } from '@/utils/errorToast';
import type { TableRow } from '@/types/api';
import { useRouter } from 'vue-router';
import { toDateString } from '@/utils/dateFormat';
import { canOrderFromQuote } from './quoteOrderContract';
import type { CsTableColumn } from '@/types/csTable';
import SalesConfirmModal from '../components/SalesConfirmModal.vue';
import { useSalesPickers } from '../components/useSalesPickers';

const authStore = useAuthStore();
const router = useRouter();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('sales'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const pickers = useSalesPickers(() => authStore.factoryId);
const loading = ref(false);
const submitting = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const statusFilter = ref<string>('');

// 状态枚举
const statusMap: Record<string, { text: string; type: 'neutral' | 'warning' | 'success' | 'error' }> = {
  DRAFT: { text: '草稿', type: 'neutral' },
  PENDING_QUOTE: { text: '待录价', type: 'warning' },
  PENDING_APPROVAL: { text: '待审批', type: 'warning' },
  APPROVED: { text: '已批准', type: 'success' },
  REJECTED: { text: '已驳回', type: 'error' },
  EXPIRED: { text: '已过期', type: 'neutral' },
};
const STATUS_FILTER_ALL = 'ALL';
const STATUS_FILTER_ITEMS = [
  { label: '全部状态', value: STATUS_FILTER_ALL },
  { label: '待录价', value: 'PENDING_QUOTE' },
  { label: '待审批', value: 'PENDING_APPROVAL' },
  { label: '已批准', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已过期', value: 'EXPIRED' },
];
// USelect 的选项值不能是空串: 页面内用 ALL 表示不筛, 请求时再还原成空
const statusSelect = computed({
  get: () => statusFilter.value || STATUS_FILTER_ALL,
  set: (v: string) => { statusFilter.value = v === STATUS_FILTER_ALL ? '' : v; handleStatusChange(); },
});
const QUOTE_TYPE_ITEMS = [
  { label: '固定报价', value: 'FIXED' },
  { label: '可议价', value: 'NEGOTIABLE' },
];
const R = { class: { th: 'text-right', td: 'text-right' } };
// 单价 / 毛利率两列按 canViewPrice 整列去掉 (遮蔽时不显示 0, 也不留空列)
const columns = computed<CsTableColumn<TableRow>[]>(() => [
  { accessorKey: 'quoteNo', header: '报价单号' },
  { id: 'status', header: '状态' },
  { accessorKey: 'customerId', header: '客户' },
  { accessorKey: 'productTypeId', header: '产品' },
  { id: 'quoteType', header: '报价类型' },
  ...(canViewPrice.value ? [{ id: 'unitPrice', header: '单价', meta: R }, { id: 'marginRate', header: '毛利率', meta: R }] : []),
  { id: 'quotedByName', header: '录价人' },
  { id: 'approverName', header: '审批人' },
  { id: 'validUntil', header: '有效期至' },
  { id: 'revision', header: '修订' },
  { id: 'actions', header: '操作' },
]);

// 创建报价对话框
const createDialogVisible = ref(false);
const createForm = ref({
  sampleId: '',
  customerId: '',
  productTypeId: '',
  bomId: '',
  quotedByUserId: null as number | null,
  quotedByName: '',
  remarks: '',
});

// 录价对话框
const submitPriceDialogVisible = ref(false);
const submitPriceForm = ref({
  id: '',
  quoteNo: '',
  quoteType: 'FIXED',
  unitPrice: 0,
  unit: 'kg',
  minOrderQty: 0,
  costPrice: 0,
  validUntil: '',
  remarks: '',
});

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: TableRow = {
      page: pagination.value.page,
      size: pagination.value.size,
    };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/quotes`, { params });
    if (res.success) {
      const data = res.data || {};
      tableData.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '加载报价列表失败');
  } finally {
    loading.value = false;
  }
}

function onPageUpdate(p: number) {
  if (p === pagination.value.page) return;
  handlePageChange(p);
}

function handleStatusChange() {
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

// ──────────────────────────────────────────────
// 创建报价 (研发或销售触发)
// ──────────────────────────────────────────────
function openCreateDialog() {
  createForm.value = {
    sampleId: '',
    customerId: '',
    productTypeId: '',
    bomId: '',
    quotedByUserId: null,
    quotedByName: '',
    remarks: '',
  };
  createDialogVisible.value = true;
  if (!pickers.customerItems.value.length || !pickers.productItems.value.length) void pickers.load();
}

async function handleCreate() {
  if (submitting.value) return;
  if (!createForm.value.customerId || !createForm.value.productTypeId) {
    return ElMessage.warning('客户和产品必填');
  }
  if (!createForm.value.quotedByName) {
    return ElMessage.warning('销售运营报价人必填 (客户原话: 必须指定到人不是岗位)');
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/quotes`, createForm.value);
    if (res.success) {
      ElMessage.success(`报价单已创建: ${res.data?.quoteNo}, 已分配给 ${createForm.value.quotedByName}`);
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '创建失败');
    }
  } catch (e) {
    // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
    // fallback only for network errors (避免双 toast).
    handleCatchError(e, '创建失败, 请检查网络');
  } finally {
    submitting.value = false;
  }
}

// ──────────────────────────────────────────────
// 录价 (PENDING_QUOTE → PENDING_APPROVAL)
// ──────────────────────────────────────────────
function openSubmitPriceDialog(row: TableRow) {
  submitPriceForm.value = {
    id: String(row.id),
    quoteNo: String(row.quoteNo),
    quoteType: String(row.quoteType || 'FIXED'),
    unitPrice: Number(row.unitPrice || 0),
    unit: String(row.unit || 'kg'),
    minOrderQty: Number(row.minOrderQty || 0),
    costPrice: Number(row.costPrice || 0),
    validUntil: row.validUntil ? String(row.validUntil) : '',
    remarks: String(row.remarks || ''),
  };
  submitPriceDialogVisible.value = true;
}

async function handleSubmitPrice() {
  if (submitting.value) return;
  if (!submitPriceForm.value.unitPrice || submitPriceForm.value.unitPrice <= 0) {
    return ElMessage.warning('单价必须 > 0');
  }
  submitting.value = true;
  try {
    const { id, quoteNo: _qn, ...body } = submitPriceForm.value;
    const res = await put(`/${factoryId.value}/quotes/${id}/submit-price`, {
      ...body,
      validUntil: body.validUntil || null,
    });
    if (res.success) {
      const margin = res.data?.marginRate ? `(毛利率 ${(Number(res.data.marginRate) * 100).toFixed(2)}%)` : '';
      ElMessage.success(`报价已提交审批 ${margin}`);
      submitPriceDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(res.message || '提交失败');
    }
  } catch (e) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  } finally {
    submitting.value = false;
  }
}

// ──────────────────────────────────────────────
// 审批 / 驳回 / 修订
// ──────────────────────────────────────────────
async function handleApprove(row: TableRow) {
  if (submitting.value) return;
  const comment = await confirmRef.value!.ask({
    title: '主管审批',
    message: `审批通过报价 ${row.quoteNo}? 单价 ${formatAmount(Number(row.unitPrice))}/${row.unit}, 可选填备注:`,
    confirmText: '通过',
    input: { label: '备注', placeholder: '(选填)', type: 'textarea' },
  });
  if (comment === null) return;
  submitting.value = true;
  try {
    const res = await put(`/${factoryId.value}/quotes/${row.id}/approve`, {
      approverName: '主管',
      comment: comment || '',
    });
    if (res.success) {
      ElMessage.success('已审批通过, 销售可下单');
      loadData();
    } else {
      ElMessage.error(res.message || '审批失败');
    }
  } catch (e) { console.error('[失败]', e); }
  finally { submitting.value = false; }
}

async function handleReject(row: TableRow) {
  if (submitting.value) return;
  const reason = await confirmRef.value!.ask({
    title: '主管驳回',
    message: `驳回报价 ${row.quoteNo}`,
    confirmText: '驳回',
    danger: true,
    input: { label: '驳回原因', placeholder: '请填写驳回原因', required: true, errorText: '驳回原因必填', type: 'textarea' },
  });
  if (reason === null) return;
  submitting.value = true;
  try {
    const res = await put(`/${factoryId.value}/quotes/${row.id}/reject`, {
      approverName: '主管',
      reason,
    });
    if (res.success) {
      ElMessage.success('已驳回');
      loadData();
    } else {
      ElMessage.error(res.message || '驳回失败');
    }
  } catch (e) { console.error('[失败]', e); }
  finally { submitting.value = false; }
}

async function handleRevise(row: TableRow) {
  if (submitting.value) return;
  const reason = await confirmRef.value!.ask({
    title: '修订报价',
    message: '客户砍价后重新报价? 填写修订原因:',
    confirmText: '确认修订',
    input: { label: '修订原因', required: true, errorText: '修订原因必填', type: 'textarea' },
  });
  if (reason === null) return;
  submitting.value = true;
  try {
    const res = await put(`/${factoryId.value}/quotes/${row.id}/revise`, { reason });
    if (res.success) {
      ElMessage.success(`已发起重新报价 (第 ${res.data?.revisionCount || 1} 次修订)`);
      loadData();
    } else {
      ElMessage.error(res.message || '修订失败');
    }
  } catch (e) { console.error('[失败]', e); }
  finally { submitting.value = false; }
}
</script>

<template>
  <div class="page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>销售运营报价</h2>
        <span class="count">共 {{ pagination.total }} 条</span>
        <div class="head-actions">
          <USelect v-model="statusSelect" :items="STATUS_FILTER_ITEMS" class="w-40" data-testid="quote-status-filter" />
          <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="loading" @click="loadData">刷新</UButton>
          <UButton v-if="canWrite" icon="i-lucide-plus" data-testid="quote-create" @click="openCreateDialog">新建报价</UButton>
        </div>
      </header>

      <div class="body">
        <UAlert
          color="info"
          variant="subtle"
          title="客户原话: 销售运营部统一报价, 必须指定到人不是岗位"
          description="3 段式: 提交 PENDING_QUOTE → 录价 PENDING_APPROVAL → 主管审批 APPROVED → 销售可下单"
          class="mb-3"
        />

        <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="quote-table">
          <template #empty>暂无报价单</template>
          <template #status-cell="{ row }">
            <UBadge :color="statusMap[row.original.status as string]?.type || 'neutral'" variant="soft">{{ statusMap[row.original.status as string]?.text || row.original.status }}</UBadge>
          </template>
          <template #quoteType-cell="{ row }">
            <UBadge v-if="row.original.quoteType === 'FIXED'" color="success" variant="soft">固定</UBadge>
            <UBadge v-else-if="row.original.quoteType === 'NEGOTIABLE'" color="warning" variant="soft">议价</UBadge>
            <span v-else>—</span>
          </template>
          <template #unitPrice-cell="{ row }">
            <span v-if="row.original.unitPrice" class="num">{{ formatAmount(row.original.unitPrice as number) }}/{{ row.original.unit }}</span>
            <span v-else class="muted">未报价</span>
          </template>
          <template #marginRate-cell="{ row }">
            <span v-if="row.original.marginRate" class="num" :class="Number(row.original.marginRate) >= 0.3 ? 'good' : 'warn'">{{ (Number(row.original.marginRate) * 100).toFixed(1) }}%</span>
            <span v-else>—</span>
          </template>
          <template #quotedByName-cell="{ row }">{{ row.original.quotedByName || '—' }}</template>
          <template #approverName-cell="{ row }">{{ row.original.approverName || '—' }}</template>
          <template #validUntil-cell="{ row }">{{ row.original.validUntil || '—' }}</template>
          <template #revision-cell="{ row }">
            <UBadge v-if="row.original.revisionCount && (row.original.revisionCount as number) > 0" color="warning" variant="soft">v{{ row.original.revisionCount }}</UBadge>
            <span v-else>—</span>
          </template>
          <template #actions-cell="{ row }">
            <div class="row-actions">
              <UButton v-if="canWrite && canOrderFromQuote(row.original, toDateString(new Date()))" variant="link" size="xs"
                       @click="router.push({ path: '/sales/orders/new', query: { quoteId: String(row.original.id) } })">按报价下单</UButton>
              <UButton v-if="row.original.status === 'PENDING_QUOTE' && canWrite" variant="link" size="xs"
                       @click="openSubmitPriceDialog(row.original)">录价</UButton>
              <UButton v-if="row.original.status === 'PENDING_APPROVAL' && canWrite" color="success" variant="link" size="xs"
                       @click="handleApprove(row.original)">通过</UButton>
              <UButton v-if="row.original.status === 'PENDING_APPROVAL' && canWrite" color="error" variant="link" size="xs"
                       @click="handleReject(row.original)">驳回</UButton>
              <UButton v-if="['REJECTED','APPROVED'].includes(row.original.status as string) && canWrite" color="warning" variant="link" size="xs"
                       @click="handleRevise(row.original)">修订</UButton>
            </div>
          </template>
        </UTable>

        <div class="pager">
          <UPagination :page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="onPageUpdate" />
        </div>
      </div>
    </section>

    <!-- ─── 创建报价对话框 ─── -->
    <UModal v-model:open="createDialogVisible" title="新建销售运营报价" description="创建报价单并指定录价人" :ui="{ content: 'max-w-[600px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form">
          <UFormField label="客户" required>
            <USelectMenu :model-value="createForm.customerId || undefined" value-key="value" :items="pickers.customerItems.value" :loading="pickers.loading.value" placeholder="请选择客户" class="w-full" data-testid="quote-customer" @update:model-value="createForm.customerId = String($event ?? '')" />
          </UFormField>
          <UFormField label="产品" required>
            <USelectMenu :model-value="createForm.productTypeId || undefined" value-key="value" :items="pickers.productItems.value" :loading="pickers.loading.value" placeholder="请选择产品（成品 / 半成品）" class="w-full" data-testid="quote-product" @update:model-value="createForm.productTypeId = String($event ?? '')" />
          </UFormField>
          <UFormField label="样品 ID"><UInput v-model="createForm.sampleId" placeholder="可选, 报价由样品驱动" class="w-full" /></UFormField>
          <UFormField label="BOM ID"><UInput v-model="createForm.bomId" placeholder="可选, 用于成本参考" class="w-full" /></UFormField>
          <UFormField label="录价人" required><UInput v-model="createForm.quotedByName" placeholder="销售运营部具体的人 (不是岗位)" class="w-full" data-testid="quote-quoted-by" /></UFormField>
          <UFormField label="录价人 ID">
            <UInputNumber v-model="createForm.quotedByUserId" :min="0" :increment="false" :decrement="false" placeholder="user_id" class="w-48" />
          </UFormField>
          <UFormField label="备注"><UTextarea v-model="createForm.remarks" :rows="3" placeholder="如: 客户老王肉制品厂咨询" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createDialogVisible = false">取消</UButton>
        <UButton :loading="submitting" data-testid="quote-create-submit" @click="handleCreate">提交</UButton>
      </template>
    </UModal>

    <!-- ─── 录价对话框 ─── -->
    <UModal v-model:open="submitPriceDialogVisible" :title="`录价 — ${submitPriceForm.quoteNo}`" description="录入单价并提交审批" :ui="{ content: 'max-w-[560px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form">
          <UFormField label="报价类型"><USelect v-model="submitPriceForm.quoteType" :items="QUOTE_TYPE_ITEMS" class="w-48" /></UFormField>
          <UFormField v-if="canViewPrice" label="单价" required>
            <div class="inline">
              <UInputNumber v-model="submitPriceForm.unitPrice" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ maximumFractionDigits: 2 }" class="w-48" data-testid="quote-unit-price" />
              <UInput v-model="submitPriceForm.unit" placeholder="单位" class="w-20" />
            </div>
          </UFormField>
          <UFormField label="最小起订量">
            <UInputNumber v-model="submitPriceForm.minOrderQty" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ maximumFractionDigits: 3 }" class="w-48" />
          </UFormField>
          <UFormField v-if="canViewPrice" label="成本单价">
            <div class="inline">
              <UInputNumber v-model="submitPriceForm.costPrice" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ maximumFractionDigits: 2 }" class="w-48" />
              <span class="muted small">内部参考, 不展示给客户</span>
            </div>
          </UFormField>
          <UFormField label="有效期至"><UInput v-model="submitPriceForm.validUntil" type="date" class="w-48" /></UFormField>
          <UFormField label="备注"><UTextarea v-model="submitPriceForm.remarks" :rows="2" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="submitPriceDialogVisible = false">取消</UButton>
        <UButton :loading="submitting" data-testid="quote-submit-price" @click="handleSubmitPrice">提交审批</UButton>
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
.body { padding: var(--cs-space-4) var(--cs-space-5); }
.pager { display: flex; justify-content: flex-end; margin-top: var(--cs-space-4); }
.form { display: grid; gap: var(--cs-space-3); }
.inline { display: flex; align-items: center; gap: var(--cs-space-2); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); flex-wrap: wrap; }
.num { font-variant-numeric: tabular-nums; }
.muted { color: var(--cs-muted); }
.small { font-size: var(--cs-font-small); }
.good { color: var(--cs-success); }
.warn { color: var(--cs-warning); }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
