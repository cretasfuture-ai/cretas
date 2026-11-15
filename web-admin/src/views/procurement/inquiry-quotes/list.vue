<script setup lang="ts">
/**
 * P-NUCLEAR-1 (28-Backlog #30) — 核价单列表 (PC).
 *
 * 三阶段 pipeline:
 *   DRAFT → INQUIRING → QUOTED → CONVERTED (生成 PO)
 *
 * UX 防呆: 状态用 el-tag 颜色区分, CONVERTED 行点击进 PO 详情.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { parseDraftInquiryPrefill } from './draftInquiryPrefill';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import {
  listInquiries,
  createInquiry,
  type InquiryQuote,
  type InquiryQuoteStatus,
} from '@/api/inquiryQuote';
import { toDateString } from '@/utils/dateFormat';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const rows = ref<InquiryQuote[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });

// Create dialog state
const createDialogVisible = ref(false);
const createForm = ref({
  title: '',
  materialTypeId: '',
  materialName: '',
  specification: '',
  quantity: 0 as number,
  unit: 'kg',
  inquiryDate: toDateString(new Date()),
  requiredDate: '',
  remark: '',
  /** 从采购草稿发起时有值: 选定报价回填这张草稿 */
  sourcePurchaseOrderId: '',
  sourceOrderNumber: '',
});
const createSubmitting = ref(false);
const linkedToDraft = computed(() => Boolean(createForm.value.sourcePurchaseOrderId));

const STATUS_TAG: Record<InquiryQuoteStatus, { type: 'info' | 'warning' | 'primary' | 'success' | 'danger'; label: string }> = {
  DRAFT: { type: 'info', label: '草稿' },
  INQUIRING: { type: 'warning', label: '询价中' },
  QUOTED: { type: 'primary', label: '已报价' },
  SELECTED: { type: 'primary', label: '已选定' },
  CONVERTED: { type: 'success', label: '已转采购' },
  CANCELLED: { type: 'info', label: '已取消' },
};

const columns: TableColumn<InquiryQuote>[] = [
  { id: 'inquiryNumber', header: '核价单号' },
  { id: 'material', header: '物料' },
  { id: 'quantity', header: '数量', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'inquiryDate', header: '询价日期' },
  { id: 'requiredDate', header: '期望到货' },
  { id: 'supplier', header: '中标供应商' },
  { id: 'price', header: '中标价', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'status', header: '状态' },
  { id: 'po', header: '采购单' },
  { id: 'actions', header: '操作' },
];

async function load() {
  if (!factoryId.value) {
    ElMessage.warning('未登录或缺少工厂上下文');
    return;
  }
  loading.value = true;
  try {
    const res = await listInquiries(factoryId.value, {
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res.success && res.data) {
      rows.value = res.data.content;
      pagination.value.total = res.data.totalElements;
    } else {
      rows.value = [];
      pagination.value.total = 0;
    }
  } finally {
    loading.value = false;
  }
}

function goDetail(row: InquiryQuote) {
  router.push({ name: 'InquiryQuoteDetail', params: { id: row.id } });
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  load();
}

function openCreate() {
  createForm.value = {
    title: '',
    materialTypeId: '',
    materialName: '',
    specification: '',
    quantity: 0,
    unit: 'kg',
    inquiryDate: toDateString(new Date()),
    requiredDate: '',
    remark: '',
    sourcePurchaseOrderId: '',
    sourceOrderNumber: '',
  };
  createDialogVisible.value = true;
}

/** 从采购草稿物料行跳过来: 预填并直接打开新建框 */
function openCreateFromDraftQuery() {
  const prefill = parseDraftInquiryPrefill(route.query);
  if (!prefill) return;
  openCreate();
  createForm.value = {
    ...createForm.value,
    materialTypeId: prefill.materialTypeId,
    materialName: prefill.materialName,
    specification: prefill.specification,
    quantity: prefill.quantity,
    unit: prefill.unit,
    sourcePurchaseOrderId: prefill.sourcePurchaseOrderId,
    sourceOrderNumber: prefill.sourceOrderNumber,
    title: prefill.sourceOrderNumber ? `采购草稿 ${prefill.sourceOrderNumber} 比价` : '',
  };
}

async function submitCreate() {
  if (!factoryId.value) return;
  if (!createForm.value.materialTypeId) {
    ElMessage.warning('请填写物料类型 ID');
    return;
  }
  if (!createForm.value.quantity || createForm.value.quantity <= 0) {
    ElMessage.warning('请填写询价数量 (> 0)');
    return;
  }
  if (!createForm.value.unit) {
    ElMessage.warning('请填写单位');
    return;
  }
  if (!createForm.value.inquiryDate) {
    ElMessage.warning('请选择询价日期');
    return;
  }
  createSubmitting.value = true;
  try {
    const res = await createInquiry(factoryId.value, {
      title: createForm.value.title || undefined,
      materialTypeId: createForm.value.materialTypeId,
      materialName: createForm.value.materialName || undefined,
      specification: createForm.value.specification || undefined,
      quantity: createForm.value.quantity,
      unit: createForm.value.unit,
      inquiryDate: createForm.value.inquiryDate,
      requiredDate: createForm.value.requiredDate || undefined,
      remark: createForm.value.remark || undefined,
      sourcePurchaseOrderId: createForm.value.sourcePurchaseOrderId || undefined,
    });
    if (res.success && res.data) {
      ElMessage.success(res.message || `核价单 ${res.data.inquiryNumber} 创建成功`);
      createDialogVisible.value = false;
      if (res.data.sourcePurchaseOrderId) {
        router.push({ name: 'InquiryQuoteDetail', params: { id: res.data.id } });
        return;
      }
      load();
    }
  } finally {
    createSubmitting.value = false;
  }
}

function formatPrice(v: number | null | undefined): string {
  if (v == null) return '-';
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

onMounted(() => {
  load();
  openCreateFromDraftQuery();
});
</script>

<template>
  <div class="inquiry-quote-list">
    <div class="cs-page-head">
      <div>
        <h2>核价单</h2>
        <p>询价 → 核价 → 采购 三阶段 pipeline (P-NUCLEAR-1)</p>
      </div>
      <div class="head-actions">
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="load">刷新</UButton>
        <UButton icon="i-lucide-plus" @click="openCreate">新建核价单</UButton>
      </div>
    </div>

    <section class="cs-card">
      <UTable :data="rows" :columns="columns" :loading="loading" class="cs-table cs-clickable" @select="(_e: unknown, row: { original: InquiryQuote }) => goDetail(row.original)">
        <template #empty>暂无核价单</template>
        <template #inquiryNumber-cell="{ row }">{{ row.original.inquiryNumber }}</template>
        <template #material-cell="{ row }">
          {{ row.original.materialName || row.original.materialTypeId }}
          <span v-if="row.original.specification" class="spec">({{ row.original.specification }})</span>
        </template>
        <template #quantity-cell="{ row }"><span class="num">{{ row.original.quantity }} {{ row.original.unit }}</span></template>
        <template #inquiryDate-cell="{ row }"><span class="num">{{ row.original.inquiryDate }}</span></template>
        <template #requiredDate-cell="{ row }"><span class="num">{{ row.original.requiredDate || '-' }}</span></template>
        <template #supplier-cell="{ row }">{{ row.original.selectedSupplierName || row.original.selectedSupplierId || '-' }}</template>
        <template #price-cell="{ row }"><span class="num">{{ formatPrice(row.original.selectedUnitPrice) }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(STATUS_TAG[row.original.status as InquiryQuoteStatus]?.type || 'info')" variant="soft">
            {{ STATUS_TAG[row.original.status as InquiryQuoteStatus]?.label || row.original.status }}
          </UBadge>
        </template>
        <template #po-cell="{ row }">{{ row.original.purchaseOrderNumber || '-' }}</template>
        <template #actions-cell="{ row }">
          <UButton size="xs" variant="link" @click.stop="goDetail(row.original)">详情</UButton>
        </template>
      </UTable>

      <div v-if="pagination.total > 0" class="pagination-wrapper">
        <span class="total">共 {{ pagination.total }} 条</span>
        <UPagination v-model:page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </div>
    </section>

    <!-- 新建核价单 -->
    <UModal v-model:open="createDialogVisible" title="新建核价单" description="创建询价核价单" :dismissible="false" :ui="{ content: 'max-w-[560px]', description: 'sr-only' }">
      <template #body>
        <UAlert
          v-if="linkedToDraft"
          color="info"
          variant="subtle"
          icon="i-lucide-info"
          class="mb-3"
          :title="`来自采购草稿 ${createForm.sourceOrderNumber || ''}：选定报价会回填这张草稿的本物料行，不另建采购单`"
        />
        <div class="form">
          <UFormField label="标题">
            <UInput v-model="createForm.title" placeholder="选填, 如 Q2 辣椒采购询价" :maxlength="200" class="w-full" />
          </UFormField>
          <UFormField label="物料类型 ID" required>
            <UInput v-model="createForm.materialTypeId" placeholder="raw_material_types.id" :maxlength="191" :disabled="linkedToDraft" class="w-full" />
          </UFormField>
          <UFormField label="物料名称">
            <UInput v-model="createForm.materialName" placeholder="如 辣椒" :maxlength="200" class="w-full" />
          </UFormField>
          <UFormField label="规格">
            <UInput v-model="createForm.specification" placeholder="选填" :maxlength="200" class="w-full" />
          </UFormField>
          <UFormField label="数量 / 单位" required>
            <div class="qty-row">
              <UInputNumber v-model="createForm.quantity" :min="0" :step-snapping="false" :format-options="{ maximumFractionDigits: 4 }" :increment="false" :decrement="false" class="w-full" />
              <UInput v-model="createForm.unit" placeholder="单位(必填)" :disabled="linkedToDraft" class="w-28" />
            </div>
          </UFormField>
          <UFormField label="询价日期" required>
            <UInput v-model="createForm.inquiryDate" type="date" class="w-full" />
          </UFormField>
          <UFormField label="期望到货">
            <UInput v-model="createForm.requiredDate" type="date" class="w-full" />
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="createForm.remark" :rows="2" :maxlength="5000" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createDialogVisible = false">取消</UButton>
        <UButton :loading="createSubmitting" @click="submitCreate">创建</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.inquiry-quote-list { padding: var(--cs-space-4); }
.head-actions { display: flex; gap: var(--cs-space-2); align-items: center; }
.spec { color: var(--cs-muted); font-size: var(--cs-font-small); margin-left: var(--cs-space-1); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.qty-row { display: flex; gap: var(--cs-space-2); }
.total { color: var(--cs-muted); font-size: var(--cs-font); }
.pagination-wrapper { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
</style>
