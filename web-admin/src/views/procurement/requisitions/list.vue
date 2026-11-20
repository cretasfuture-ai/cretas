<script setup lang="ts">
import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
/**
 * 全部请购单 — Sprint 6 W2-A.
 *
 * 列表无 requesterId filter, 显示工厂全部请购单 (any user).
 * 用于全局视图 + 审计 + 跨人查询. Status filter 可选.
 *
 * Sprint 6 W4 enhancements (deferred):
 *   - requester 名字解析 (当前显示 #ID)
 *   - 关联 PO 链接 (convertedPoId → 跳 detail)
 *   - 导出 / 批量审批
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useUserDirectory } from '@/composables/useUserDirectory';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import {
  listRequisitions,
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
const canWrite = computed(() => permissionStore.canWrite('procurement'));

const tableData = ref<PurchaseRequisition[]>([]);
const loading = ref(false);
const statusFilter = ref<PurchaseRequisitionStatus | ''>('');
const requesterIdFilter = ref<number | null>(null);
const pagination = ref({ page: 1, size: 20, total: 0 });
const createDialogVisible = ref(false);
// 请购人 ID 输入框是文本（UInput type=number），请求里仍用 number | null
const requesterIdText = computed({
  get: () => (requesterIdFilter.value == null ? '' : String(requesterIdFilter.value)),
  set: (v: string) => {
    const n = Number(v);
    requesterIdFilter.value = v !== '' && Number.isFinite(n) && n >= 1 ? n : null;
  },
});
const columns: TableColumn<PurchaseRequisition>[] = [
  { id: 'requisitionNumber', header: '单号' },
  { id: 'requester', header: '请购人' },
  { id: 'items', header: '明细' },
  { id: 'expectedDate', header: '期望交货' },
  { id: 'status', header: '状态' },
  { id: 'po', header: '关联PO' },
  { id: 'createdAt', header: '创建时间' },
  { id: 'actions', header: '操作' },
];

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
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listRequisitions(factoryId.value, {
      status: statusFilter.value || undefined,
      requesterId: requesterIdFilter.value ?? undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res?.success && res.data) {
      tableData.value = res.data.content ?? [];
      pagination.value.total = res.data.totalElements ?? 0;
    }
  } catch (e) {
    console.error('[全部请购单加载失败]', e);
  } finally {
    loading.value = false;
  }
}


// 缺陷 5 (2026-09-01, LIUSHANMEN): 「请购人」列原来渲染 `#{{ row.requesterId }}`,
// 生产上显示 `#1638` —— 一个数据库主键, 采购主管看不出是谁提的单。
// ⛔ 不为此开 hr 权限; 解析口径与回退规则见 `@/composables/useUserDirectory` 头部。
const { load: loadUserDirectory, personLabel } = useUserDirectory();

onMounted(() => {
  loadData();
  loadUserDirectory(factoryId.value);
});

function handlePageChange(p: number) {
  pagination.value.page = p;
  loadData();
}
function handleSizeChange(s: number) {
  pagination.value.size = s;
  pagination.value.page = 1;
  loadData();
}
function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}
function handleRefresh() {
  statusFilter.value = '';
  requesterIdFilter.value = null;
  pagination.value.page = 1;
  loadData();
}
function goDetail(id: string) {
  router.push(`/procurement/requisitions/${id}`);
}
function goPO(poId: string) {
  router.push(`/procurement/orders/${poId}`);
}

function onCreated() {
  ElMessage.success('请购单已创建, 状态为草稿. 请在"我的请购"或本页编辑后提交审批.');
  pagination.value.page = 1;
  loadData();
}

async function handleDelete(row: PurchaseRequisition) {
  // Fool-proof Rule 2: dialog 含单号 + 行数; Rule 5: backend 仅 DRAFT + requester 本人可删
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
</script>

<template>
  <div class="page-wrapper">
    <div class="cs-page-head">
      <div>
        <h2>全部请购单</h2>
        <p>共 {{ pagination.total }} 条</p>
      </div>
      <div class="head-actions">
        <UButton v-if="canWrite" icon="i-lucide-plus" @click="createDialogVisible = true">新建请购单</UButton>
      </div>
    </div>

    <section class="cs-card">
      <div class="cs-card-head">
        <USelect v-model="statusSelect" :items="statusItems" placeholder="按状态筛选" class="w-44" @update:model-value="handleFilterChange" />
        <UInput
          v-model="requesterIdText"
          type="number"
          min="1"
          placeholder="按请购人 ID"
          class="w-44"
          @change="handleFilterChange"
        />
        <UButton icon="i-lucide-rotate-ccw" color="neutral" variant="outline" @click="handleRefresh">重置</UButton>
      </div>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table">
        <template #empty><ListEmptyExplain empty-title="还没有请购单" empty-hint="车间或仓库需要采购物料时，点右上角新建请购单，审批后转采购订单。" /></template>
        <template #requisitionNumber-cell="{ row }">{{ row.original.requisitionNumber }}</template>
        <template #requester-cell="{ row }">{{ personLabel(row.original.requesterId) }}</template>
        <template #items-cell="{ row }">
          <span v-if="!row.original.requestedItems || row.original.requestedItems.length === 0">-</span>
          <span v-else>{{ itemSummary(row.original).count }} 项 · <span class="muted">{{ itemSummary(row.original).names }}</span></span>
        </template>
        <template #expectedDate-cell="{ row }"><span class="num">{{ row.original.expectedDate }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="statusBadge(row.original.status)" variant="soft">{{ statusText(row.original.status) }}</UBadge>
        </template>
        <template #po-cell="{ row }">
          <UButton v-if="row.original.convertedPoId" size="xs" variant="link" @click="goPO(row.original.convertedPoId)">查看PO</UButton>
          <span v-else class="muted">—</span>
        </template>
        <template #createdAt-cell="{ row }"><span class="muted">{{ formatCreatedAt(row.original.createdAt) }}</span></template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" @click="goDetail(row.original.id)">详情</UButton>
            <UButton
              v-if="row.original.status === 'DRAFT' && canWrite && currentUserId !== null && row.original.requesterId === currentUserId"
              size="xs"
              variant="link"
              color="error"
              @click="handleDelete(row.original)"
            >
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
