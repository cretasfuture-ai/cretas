<script setup lang="ts">
/**
 * Sprint4-H Q-RETURN-1 — 质检退回单列表 (PC).
 *
 * 状态机操作:
 *   - DRAFT       → 编辑 / 确认 / 撤销
 *   - CONFIRMED   → 发出 (填物流单号)
 *   - SHIPPED     → 只读
 *
 * 区分 T-RTA 客户销售退货 (/sales/returns) — 此处是上游退回.
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import iconReturns from '@/assets/quality/icon-returns.webp';
import {
  listReturns,
  createReturn,
  confirmReturn,
  shipReturn,
  cancelReturn,
  RETURN_STATUS_LABELS,
  RETURN_TARGET_LABELS,
  type QualityReturnOrder,
  type QualityReturnStatus,
  type QualityReturnTargetType,
} from '@/api/qualityReturn';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const rows = ref<QualityReturnOrder[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const filterStatus = ref<QualityReturnStatus | undefined>(undefined);
const filterTarget = ref<QualityReturnTargetType | undefined>(undefined);

const showCreateDialog = ref(false);
const showShipDialog = ref(false);
const submitting = ref(false);
const currentOrder = ref<QualityReturnOrder | null>(null);

const createForm = ref({
  qualityInspectionId: '',
  targetType: 'SUPPLIER' as QualityReturnTargetType,
  targetId: '',
  targetName: '',
  materialId: '',
  quantity: 1,
  unit: '',
  reason: '',
});

const shipForm = ref({
  shippingTrackingNo: '',
});

const STATUS_OPTIONS: QualityReturnStatus[] = ['DRAFT', 'CONFIRMED', 'SHIPPED'];
const TARGET_OPTIONS: QualityReturnTargetType[] = ['SUPPLIER', 'SUBCONTRACT'];
const STATUS_ITEMS = STATUS_OPTIONS.map((s) => ({ label: RETURN_STATUS_LABELS[s], value: s }));
const TARGET_ITEMS = TARGET_OPTIONS.map((t) => ({ label: RETURN_TARGET_LABELS[t], value: t }));
const TARGET_RADIO_ITEMS = [
  { label: '退回供应商', value: 'SUPPLIER' },
  { label: '退回委外', value: 'SUBCONTRACT' },
];
const R = { class: { th: 'text-right', td: 'text-right' } };
const columns: TableColumn<QualityReturnOrder>[] = [
  { accessorKey: 'returnNumber', header: '单号' },
  { id: 'targetType', header: '退回类型' },
  { id: 'targetName', header: '接收方' },
  { accessorKey: 'quantity', header: '数量', meta: R },
  { accessorKey: 'unit', header: '单位' },
  { accessorKey: 'reason', header: '原因' },
  { id: 'status', header: '状态' },
  { id: 'shippingTrackingNo', header: '物流单号' },
  { accessorKey: 'createdAt', header: '创建时间' },
  { id: 'actions', header: '操作' },
];

async function load() {
  if (!factoryId.value) {
    ElMessage.warning('未登录或缺少工厂上下文');
    return;
  }
  loading.value = true;
  try {
    const res = await listReturns(factoryId.value, {
      status: filterStatus.value || undefined,
      targetType: filterTarget.value || undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res.success && res.data) {
      rows.value = res.data.content;
      pagination.value.total = res.data.totalElements;
    }
  } finally {
    loading.value = false;
  }
}

function statusTagType(s: QualityReturnStatus): 'success' | 'warning' | 'info' {
  if (s === 'SHIPPED') return 'success';
  if (s === 'CONFIRMED') return 'warning';
  return 'info';
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  load();
}

function clearFilters() {
  filterStatus.value = undefined;
  filterTarget.value = undefined;
  handleFilterChange();
}

function handleFilterChange() {
  pagination.value.page = 1;
  load();
}

function openCreateDialog() {
  createForm.value = {
    qualityInspectionId: '',
    targetType: 'SUPPLIER',
    targetId: '',
    targetName: '',
    materialId: '',
    quantity: 1,
    unit: '',
    reason: '',
  };
  showCreateDialog.value = true;
}

async function handleCreateSubmit() {
  if (!createForm.value.qualityInspectionId.trim()) {
    ElMessage.warning('关联质检记录 ID 必填');
    return;
  }
  if (!createForm.value.targetId.trim()) {
    ElMessage.warning('接收方 ID 必填');
    return;
  }
  if (createForm.value.quantity <= 0) {
    ElMessage.warning('退回数量必须 > 0');
    return;
  }
  submitting.value = true;
  try {
    const res = await createReturn(factoryId.value, {
      qualityInspectionId: createForm.value.qualityInspectionId.trim(),
      targetType: createForm.value.targetType,
      targetId: createForm.value.targetId.trim(),
      targetName: createForm.value.targetName.trim() || null,
      materialId: createForm.value.materialId.trim() || null,
      quantity: createForm.value.quantity,
      unit: createForm.value.unit.trim() || null,
      reason: createForm.value.reason.trim() || null,
    });
    if (res.success) {
      ElMessage.success(`已创建退回单 ${res.data?.returnNumber || ''}`);
      showCreateDialog.value = false;
      load();
    }
  } finally {
    submitting.value = false;
  }
}

async function handleConfirm(row: QualityReturnOrder) {
  try {
    await ElMessageBox.confirm(
      `确认退回单 ${row.returnNumber} (DRAFT → CONFIRMED)?`,
      '确认退回单',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  const res = await confirmReturn(factoryId.value, row.id);
  if (res.success) {
    ElMessage.success('已确认');
    load();
  }
}

function openShipDialog(row: QualityReturnOrder) {
  currentOrder.value = row;
  shipForm.value.shippingTrackingNo = '';
  showShipDialog.value = true;
}

async function handleShipSubmit() {
  if (!currentOrder.value) return;
  if (!shipForm.value.shippingTrackingNo.trim()) {
    ElMessage.warning('物流单号必填');
    return;
  }
  submitting.value = true;
  try {
    const res = await shipReturn(
      factoryId.value,
      currentOrder.value.id,
      shipForm.value.shippingTrackingNo.trim(),
    );
    if (res.success) {
      ElMessage.success('已发出');
      showShipDialog.value = false;
      load();
    }
  } finally {
    submitting.value = false;
  }
}

async function handleCancel(row: QualityReturnOrder) {
  try {
    await ElMessageBox.confirm(
      `撤销退回单草稿 ${row.returnNumber}? 操作不可恢复.`,
      '撤销草稿',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  const res = await cancelReturn(factoryId.value, row.id);
  if (res.success) {
    ElMessage.success('已撤销');
    load();
  }
}

onMounted(load);
</script>

<template>
  <div class="return-list">
    <div class="cs-page-head">
      <div class="head-main">
        <img :src="iconReturns" alt="" class="head-icon" width="56" height="56">
        <div>
          <h2>质检退回单</h2>
          <p>退回供应商 / 委外加工厂 — 状态机 DRAFT → CONFIRMED → SHIPPED</p>
        </div>
      </div>
      <div class="head-actions">
        <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="loading" @click="load">刷新</UButton>
        <UButton icon="i-lucide-plus" @click="openCreateDialog">新建退回单</UButton>
      </div>
    </div>

    <section class="cs-card">
      <div class="filters">
        <USelect v-model="filterStatus" :items="STATUS_ITEMS" placeholder="状态" class="w-40" @update:model-value="handleFilterChange" />
        <USelect v-model="filterTarget" :items="TARGET_ITEMS" placeholder="退回类型" class="w-44" @update:model-value="handleFilterChange" />
        <UButton v-if="filterStatus || filterTarget" color="neutral" variant="link" size="xs" @click="clearFilters">清除筛选</UButton>
      </div>
      <UTable :data="rows" :columns="columns" :loading="loading" class="cs-table">
        <template #empty><div class="cs-empty-art"><img :src="iconReturns" alt="" width="96"><span>暂无退回单</span></div></template>
        <template #targetType-cell="{ row }">
          <UBadge :color="row.original.targetType === 'SUPPLIER' ? 'primary' : 'warning'" variant="soft">
            {{ RETURN_TARGET_LABELS[String(row.original.targetType) as QualityReturnTargetType] }}
          </UBadge>
        </template>
        <template #targetName-cell="{ row }">{{ row.original.targetName || row.original.targetId }}</template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(statusTagType(row.original.status))" variant="soft">{{ RETURN_STATUS_LABELS[String(row.original.status) as QualityReturnStatus] || row.original.status }}</UBadge>
        </template>
        <template #shippingTrackingNo-cell="{ row }">{{ row.original.shippingTrackingNo || '-' }}</template>
        <template #actions-cell="{ row }">
          <UButton v-if="row.original.status === 'DRAFT'" color="primary" variant="link" size="xs" @click="handleConfirm(row.original)">确认</UButton>
          <UButton v-if="row.original.status === 'DRAFT'" color="error" variant="link" size="xs" @click="handleCancel(row.original)">撤销</UButton>
          <UButton v-if="row.original.status === 'CONFIRMED'" color="success" variant="link" size="xs" @click="openShipDialog(row.original)">发出</UButton>
        </template>
      </UTable>
      <div v-if="pagination.total > 0" class="pager">
        <UPagination :page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </div>
    </section>

    <!-- 新建退回单 -->
    <UModal
      v-model:open="showCreateDialog"
      title="新建质检退回单"
      description="填写退回对象与数量，创建后为草稿"
      :ui="{ content: 'max-w-[560px]', description: 'sr-only' }"
    >
      <template #body>
        <div class="form-grid">
          <UFormField label="关联质检记录 ID" required>
            <UInput v-model="createForm.qualityInspectionId" placeholder="quality_inspections.id" class="w-full" />
          </UFormField>
          <UFormField label="退回类型" required>
            <URadioGroup v-model="createForm.targetType" orientation="horizontal" :items="TARGET_RADIO_ITEMS" />
          </UFormField>
          <UFormField label="接收方 ID" required>
            <UInput v-model="createForm.targetId" placeholder="supplier id 或 subcontract id" class="w-full" />
          </UFormField>
          <UFormField label="接收方名称 (快照)">
            <UInput v-model="createForm.targetName" placeholder="可选" class="w-full" />
          </UFormField>
          <UFormField label="物料 ID (可选)">
            <UInput v-model="createForm.materialId" placeholder="raw_material_types.id" class="w-full" />
          </UFormField>
          <UFormField label="退回数量" required>
            <div class="qty-row">
              <UInputNumber v-model="createForm.quantity" :min="0" :step-snapping="false" :format-options="{ maximumFractionDigits: 4 }" :increment="false" :decrement="false" class="w-48" />
              <UInput v-model="createForm.unit" placeholder="单位 (kg / 件 / 箱)" class="w-40" />
            </div>
          </UFormField>
          <UFormField label="退回原因">
            <UTextarea v-model="createForm.reason" :rows="2" :maxlength="500" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="showCreateDialog = false">取消</UButton>
        <UButton :loading="submitting" @click="handleCreateSubmit">创建草稿</UButton>
      </template>
    </UModal>

    <!-- 发出 -->
    <UModal
      v-model:open="showShipDialog"
      title="发出退回单"
      description="填写物流单号后发出"
      :ui="{ content: 'max-w-[460px]', description: 'sr-only' }"
    >
      <template #body>
        <UFormField label="物流单号 / 运单号" required>
          <UInput v-model="shipForm.shippingTrackingNo" placeholder="必填" class="w-full" />
        </UFormField>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="showShipDialog = false">取消</UButton>
        <UButton :loading="submitting" @click="handleShipSubmit">确认发出</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.return-list { padding: var(--cs-space-4); }
.head-main { flex: 1; min-width: 0; display: flex; gap: var(--cs-space-3); align-items: center; }
.head-icon { flex: none; border-radius: var(--cs-radius); }
.head-actions { display: flex; gap: var(--cs-space-2); }
.filters { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-4) var(--cs-space-5); }
.form-grid { display: grid; gap: var(--cs-space-3); }
.qty-row { display: flex; gap: var(--cs-space-3); }
</style>
