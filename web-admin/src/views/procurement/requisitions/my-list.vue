<script setup lang="ts">
/**
 * 我的请购 — Sprint 6 W2-A.
 *
 * 列表 filter by current user (requesterId = authStore.user.id).
 * 给生产员/仓管员看自己发起的请购单 (any status).
 *
 * Round 12 §B.2 X2 客户原话:
 *   "我能不能从生产员发请购单上来" — 此页就是生产员发完之后回头看草稿和审批进度的地方.
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import {
  listRequisitions,
  submitRequisition,
  deleteRequisition,
  REQUISITION_STATUS_MAP,
  type PurchaseRequisition,
  type PurchaseRequisitionStatus,
} from '@/api/purchaseRequisition';
import CreateRequisitionDialog from './CreateRequisitionDialog.vue';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const currentUserId = computed(() => authStore.user?.id ?? null);
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT');
const canManageOwnDraft = computed(() => {
  if (permissionStore.canWrite('procurement')) return true;
  if (!isRestaurant.value || !permissionStore.canAccess('procurement')) return false;
  return ['warehouse_manager', 'restaurant_manager'].includes(authStore.currentRole);
});
const pageTitle = computed(() => (isRestaurant.value ? '我的报货/采购计划' : '我的请购'));
const createButtonText = computed(() => (isRestaurant.value ? '新建报货单' : '新建请购单'));
const submitButtonText = computed(() => (isRestaurant.value ? '提交采购计划' : '提交审批'));
const emptyText = computed(() => (isRestaurant.value ? '您还没有发起任何报货单' : '您还没有发起任何请购单'));
const expectedDateLabel = computed(() => (isRestaurant.value ? '希望到货' : '期望交货'));

const tableData = ref<PurchaseRequisition[]>([]);
const loading = ref(false);
const statusFilter = ref<PurchaseRequisitionStatus | ''>('');
const pagination = ref({ page: 1, size: 20, total: 0 });
const createDialogVisible = ref(false);
const columns = computed<TableColumn<PurchaseRequisition>[]>(() => [
  { id: 'requisitionNumber', header: '单号' },
  { id: 'items', header: '明细' },
  { id: 'expectedDate', header: expectedDateLabel.value },
  { id: 'status', header: '状态' },
  { id: 'createdAt', header: '创建时间' },
  { id: 'actions', header: '操作' },
]);

// USelect 的选项值不能是空串: 页面内用 ALL 表示不筛
const statusItems = [
  { label: '全部状态', value: 'ALL' },
  ...Object.entries(REQUISITION_STATUS_MAP).map(([value, v]) => ({ label: v.text, value })),
];
const statusSelect = computed({
  get: () => statusFilter.value || 'ALL',
  set: (v: string) => { statusFilter.value = v === 'ALL' ? '' : (v as PurchaseRequisitionStatus); },
});
const sizeItems = [10, 20, 50].map((n) => ({ label: `${n} 条/页`, value: n }));
function statusBadge(status: string) {
  return badgeColor(REQUISITION_STATUS_MAP[status as PurchaseRequisitionStatus]?.type || 'info');
}
function statusText(status: string) {
  return REQUISITION_STATUS_MAP[status as PurchaseRequisitionStatus]?.text || status;
}
function formatCreatedAt(v: unknown) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '-';
}
function itemSummary(row: PurchaseRequisition) {
  const items = (row.requestedItems ?? []) as Array<{ materialName?: string }>;
  return {
    count: items.length,
    names: items.slice(0, 2).map((it) => it.materialName || '-').join(', ') + (items.length > 2 ? ' …' : ''),
  };
}

async function loadData() {
  if (!factoryId.value || currentUserId.value == null) return;
  loading.value = true;
  try {
    const res = await listRequisitions(factoryId.value, {
      requesterId: currentUserId.value,
      status: statusFilter.value || undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res?.success && res.data) {
      tableData.value = res.data.content ?? [];
      pagination.value.total = res.data.totalElements ?? 0;
    }
  } catch (e) {
    console.error('[我的请购加载失败]', e);
  } finally {
    loading.value = false;
  }
}

onMounted(loadData);

function handlePageChange(p: number) {
  pagination.value.page = p;
  loadData();
}
function handleSizeChange(s: number) {
  pagination.value.size = s;
  pagination.value.page = 1;
  loadData();
}
function handleStatusChange() {
  pagination.value.page = 1;
  loadData();
}
function handleRefresh() {
  statusFilter.value = '';
  pagination.value.page = 1;
  loadData();
}
function goDetail(id: string) {
  router.push(`/procurement/requisitions/${id}`);
}

async function handleSubmit(row: PurchaseRequisition) {
  // Fool-proof Rule 2: dialog 标题含单号 + 物料行数
  const lines = Array.isArray(row.requestedItems) ? row.requestedItems.length : 0;
  const entityName = isRestaurant.value ? '报货单' : '请购单';
  const nextStep = isRestaurant.value ? '提交给采购汇总/审批' : '提交审批';
  try {
    await ElMessageBox.confirm(
      `确认${nextStep}: ${row.requisitionNumber} (${lines} 行明细)?`,
      isRestaurant.value ? '提交采购计划' : '提交审批',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  if (!factoryId.value) return;
  try {
    const res = await submitRequisition(factoryId.value, row.id);
    if (res?.success) {
      ElMessage.success(isRestaurant.value ? `${entityName}已提交给采购` : '已提交审批');
      await loadData();
    }
  } catch (e) {
    console.error('[提交失败]', e);
  }
}

async function handleDelete(row: PurchaseRequisition) {
  // Fool-proof Rule 2: dialog 含单号 + 行数; Rule 5: backend 仅 DRAFT 可删 → UI 也 hide
  const lines = Array.isArray(row.requestedItems) ? row.requestedItems.length : 0;
  try {
    await ElMessageBox.confirm(
      `确认删除请购单 ${row.requisitionNumber} (${lines} 行明细)?\n\n此操作不可恢复.`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  if (!factoryId.value) return;
  try {
    const res = await deleteRequisition(factoryId.value, row.id);
    if (res?.success) {
      ElMessage.success('请购单已删除');
      await loadData();
    }
  } catch (e) {
    // Interceptor 已展示 sticky toast (含 backend actionHint); dedupe fallback log
    if (e !== 'cancel') console.error('[删除失败]', e);
  }
}

function onCreated() {
  pagination.value.page = 1;
  loadData();
}
</script>

<template>
  <div class="page-wrapper">
    <div class="cs-page-head">
      <div>
        <h2>{{ pageTitle }}</h2>
        <p>共 {{ pagination.total }} 条</p>
      </div>
      <div class="head-actions">
        <UButton v-if="canManageOwnDraft" icon="i-lucide-plus" @click="createDialogVisible = true">{{ createButtonText }}</UButton>
      </div>
    </div>

    <section class="cs-card">
      <div class="cs-card-head">
        <USelect v-model="statusSelect" :items="statusItems" placeholder="按状态筛选" class="w-44" @update:model-value="handleStatusChange" />
        <UButton icon="i-lucide-rotate-ccw" color="neutral" variant="outline" @click="handleRefresh">重置</UButton>
      </div>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table">
        <template #empty>{{ emptyText }}</template>
        <template #requisitionNumber-cell="{ row }">{{ row.original.requisitionNumber }}</template>
        <template #items-cell="{ row }">
          <span v-if="!row.original.requestedItems || row.original.requestedItems.length === 0">-</span>
          <span v-else>{{ itemSummary(row.original).count }} 项 · <span class="muted">{{ itemSummary(row.original).names }}</span></span>
        </template>
        <template #expectedDate-cell="{ row }"><span class="num">{{ row.original.expectedDate }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="statusBadge(row.original.status)" variant="soft">{{ statusText(row.original.status) }}</UBadge>
        </template>
        <template #createdAt-cell="{ row }"><span class="muted">{{ formatCreatedAt(row.original.createdAt) }}</span></template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" @click="goDetail(row.original.id)">详情</UButton>
            <UButton v-if="row.original.status === 'DRAFT' && canManageOwnDraft" size="xs" variant="link" color="warning" @click="handleSubmit(row.original)">
              {{ submitButtonText }}
            </UButton>
            <UButton v-if="row.original.status === 'DRAFT' && canManageOwnDraft" size="xs" variant="link" color="error" @click="handleDelete(row.original)">
              删除
            </UButton>
          </div>
        </template>
      </UTable>

      <div class="pagination-wrapper">
        <span class="total">共 {{ pagination.total }} 条</span>
        <USelect v-model="pagination.size" :items="sizeItems" class="w-28" @update:model-value="handleSizeChange" />
        <UPagination v-model:page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </div>
    </section>

    <CreateRequisitionDialog
      v-model="createDialogVisible"
      @created="onCreated"
    />
  </div>
</template>

<style scoped>
.page-wrapper { padding: var(--cs-space-4); }
.head-actions { display: flex; gap: var(--cs-space-2); align-items: center; }
.row-actions { display: flex; gap: var(--cs-space-1); align-items: center; }
.muted { color: var(--cs-muted); }
.total { color: var(--cs-muted); font-size: var(--cs-font); }
.pagination-wrapper { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
</style>
