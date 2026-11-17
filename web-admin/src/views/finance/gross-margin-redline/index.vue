<script setup lang="ts">
/**
 * SP5 W6 — 毛利红线配置页 — FactoryGrossMarginConfig CRUD
 *
 * 后端: /api/mobile/{factoryId}/pricing/gross-margin-configs
 * productTypeId = null → 工厂级全局默认红线
 *
 * 毛利红线 (目标毛利率) 用于报价/下单前判断报价是否低于最低可接受价 (#693)。
 * 此前只能 SQL 直写，本页提供财务/超管的可视化配置。
 *
 * RBAC: 读写均需 finance:read_write — 非财务/超管不显示编辑按钮 (canWrite('finance'))。
 *
 * 防呆设计 (fool-proof-design.md):
 * - Rule 2: 新建/编辑 dialog 带上下文标题 (产品名/全局)
 * - Rule 2: 删除前 confirm 带条目名称 + 后果说明
 * - 4-in-1: sticky error toast (duration:0 + showClose)，原样显示后端 message
 *
 * 口径: targetGrossMargin 存储为 0-1 小数 (0.30)，UI 以百分比 (30%) 展示/录入。
 */
import { ref, computed, onMounted, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import type { CsTableColumn } from '@/types/csTable';
import { formatDateTime } from '@/utils/dateFormat';
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import { get } from '@/api/request';
import type { ApiResponse } from '@/types/api';
import {
  listGrossMarginConfigs,
  createGrossMarginConfig,
  updateGrossMarginConfig,
  deleteGrossMarginConfig,
  type FactoryGrossMarginConfig,
} from '@/api/grossMarginConfig';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();

/** 仅财务/超管 (finance:read_write) 可写。控制新建/编辑/删除按钮显隐。 */
const canWrite = computed(() => permissionStore.canWrite('finance'));

// ============================================================================
// 产品类型选项
// ============================================================================

interface ProductTypeOption {
  id: string;
  name: string;
  code?: string | null;
}
const productTypes = ref<ProductTypeOption[]>([]);

async function loadProductTypes() {
  try {
    const res = await get<ProductTypeOption[]>(`/${factoryId.value}/product-types/active`);
    if (res.success && res.data) productTypes.value = res.data;
  } catch {
    // 非阻塞: 产品下拉加载失败不影响列表
  }
}

function productTypeName(id: string | null): string {
  if (id == null) return '全局默认 (所有产品)';
  const found = productTypes.value.find((p) => String(p.id) === String(id));
  // 产品已停用/不在启用列表时不外露内部 ID
  return found ? found.name : '未知产品（可能已停用）';
}

/** 小数 → 百分比展示 (0.3 → 30). */
function toPercent(decimal: number): number {
  return Math.round(decimal * 10000) / 100;
}

// ============================================================================
// 列表
// ============================================================================

const configs = ref<FactoryGrossMarginConfig[]>([]);
const listLoading = ref(false);
const loadFailed = ref(false);

async function loadList() {
  if (!factoryId.value) return;
  listLoading.value = true;
  loadFailed.value = false;
  try {
    const res = await listGrossMarginConfigs(factoryId.value);
    if (res.success && res.data) {
      configs.value = res.data;
    } else {
      loadFailed.value = true;
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: any) {
    loadFailed.value = true;
    const msg = e?.response?.data?.message || '网络错误，请重试';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    listLoading.value = false;
  }
}

// ============================================================================
// 创建 / 编辑 Dialog
// ============================================================================

type DialogMode = 'create' | 'edit';

interface FormData {
  productTypeId: string | null;
  /** UI 录入为百分比 (30 = 30%)，提交前换算为小数 (0.30)。 */
  marginPercent: number;
  isActive: boolean;
  description: string;
}

const dialogVisible = ref(false);
const dialogMode = ref<DialogMode>('create');
const editingId = ref<string | null>(null);
const submitting = ref(false);

const formData = reactive<FormData>({
  productTypeId: null,
  marginPercent: 30,
  isActive: true,
  description: '',
});

const dialogTitle = computed(() => {
  const scopeLabel = formData.productTypeId == null
    ? '全局默认'
    : productTypeName(formData.productTypeId);
  return dialogMode.value === 'create'
    ? `新建毛利红线 — ${scopeLabel}`
    : `编辑毛利红线 — ${scopeLabel}`;
});

function openCreate() {
  dialogMode.value = 'create';
  editingId.value = null;
  formData.productTypeId = null;
  formData.marginPercent = 30;
  formData.isActive = true;
  formData.description = '';
  dialogVisible.value = true;
}

function openEdit(row: FactoryGrossMarginConfig) {
  dialogMode.value = 'edit';
  editingId.value = row.id;
  formData.productTypeId = row.productTypeId;
  formData.marginPercent = toPercent(row.targetGrossMargin);
  formData.isActive = row.isActive;
  formData.description = row.description ?? '';
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (formData.marginPercent <= 0 || formData.marginPercent >= 100) {
    ElMessage({ message: '目标毛利率须在 0%~100% 之间 (不含端点)', type: 'warning', duration: 3000 });
    return;
  }
  // 百分比 → 小数, 4 位精度对齐后端 scale=4
  const targetGrossMargin = Math.round((formData.marginPercent / 100) * 10000) / 10000;
  submitting.value = true;
  try {
    let res: ApiResponse<FactoryGrossMarginConfig>;
    if (dialogMode.value === 'create') {
      res = await createGrossMarginConfig(factoryId.value, {
        productTypeId: formData.productTypeId,
        targetGrossMargin,
        isActive: formData.isActive,
        description: formData.description || null,
      });
    } else {
      res = await updateGrossMarginConfig(factoryId.value, editingId.value!, {
        targetGrossMargin,
        isActive: formData.isActive,
        description: formData.description || null,
      });
    }
    if (res.success) {
      ElMessage.success(dialogMode.value === 'create' ? '已创建毛利红线' : '已更新毛利红线');
      dialogVisible.value = false;
      await loadList();
    } else {
      ElMessage({ message: res.message || '操作失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: any) {
    // 4-in-1: 原样显示后端 message (e.g. 409 "工厂全局默认 的毛利红线已存在")
    const msg = e?.response?.data?.message || '网络错误，请重试';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    submitting.value = false;
  }
}

// ============================================================================
// 删除
// ============================================================================

async function handleDelete(row: FactoryGrossMarginConfig) {
  const scopeLabel = productTypeName(row.productTypeId);
  const ok = await confirmRef.value!.ask({
    title: '删除确认',
    message: `确认删除「${scopeLabel}」的毛利红线 (${toPercent(row.targetGrossMargin)}%)？` +
      `删除后该范围将跳过红线检查，报价不再触发低于红线预警。`,
    confirmText: '确认删除',
    danger: true,
  });
  if (ok === null) return;
  try {
    const res = await deleteGrossMarginConfig(factoryId.value, row.id);
    if (res.success) {
      ElMessage.success('已删除');
      await loadList();
    } else {
      ElMessage({ message: res.message || '删除失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: any) {
    const msg = e?.response?.data?.message || '网络错误，请重试';
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  }
}

// 适用产品下拉: 「全局默认」用哨兵值 (下拉值不能为空串), 提交前换回 null
const GLOBAL = '__GLOBAL__';
const productItems = computed(() => [
  { label: '全局默认 (所有产品)', value: GLOBAL },
  ...productTypes.value.map((pt) => ({ label: pt.name, value: String(pt.id) })),
]);
const scopeValue = computed(() => formData.productTypeId ?? GLOBAL);
function onScopePick(v: unknown) {
  const s = String(v ?? GLOBAL);
  formData.productTypeId = s === GLOBAL ? null : s;
}

const columns = computed<CsTableColumn<FactoryGrossMarginConfig>[]>(() => {
  const cols: CsTableColumn<FactoryGrossMarginConfig>[] = [
    { id: 'scope', header: '适用范围' },
    { id: 'margin', header: '目标毛利率', meta: { class: { th: 'text-right', td: 'text-right' } } },
    { id: 'isActive', header: '状态' },
    { id: 'description', header: '备注' },
    { id: 'updatedAt', header: '更新时间' },
  ];
  if (canWrite.value) cols.push({ id: 'actions', header: '操作' });
  return cols;
});

// ============================================================================
// 初始化
// ============================================================================

onMounted(async () => {
  await Promise.all([loadProductTypes(), loadList()]);
});
</script>

<template>
  <div class="page" data-testid="gross-margin-page">
    <UAlert
      color="info"
      variant="soft"
      icon="i-lucide-info"
      title="毛利红线控制报价/下单前的低毛利预警 — 适用产品选「全局默认」表示工厂级默认红线。产品级配置优先级高于全局。仅财务经理 / 工厂超管可配置。"
      class="mb-4"
    />
    <UAlert v-if="loadFailed" color="error" variant="soft" icon="i-lucide-circle-alert" class="mb-4" data-testid="redline-load-error"
      title="毛利红线配置暂时没取到，请点「刷新」重试。" />

    <section class="cs-card">
      <header class="cs-card-head">
        <h2>毛利红线配置</h2>
        <span class="count">共 {{ configs.length }} 条</span>
        <div class="head-actions">
          <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="listLoading" data-testid="redline-refresh" @click="loadList">刷新</UButton>
          <UButton v-if="canWrite" icon="i-lucide-plus" data-testid="redline-create" @click="openCreate">新建红线</UButton>
        </div>
      </header>

      <UTable :data="configs" :columns="columns" :loading="listLoading" class="cs-table" data-testid="redline-table">
        <template #empty>暂无毛利红线配置，点击右上角「新建红线」添加</template>
        <template #scope-cell="{ row }">
          <UBadge v-if="row.original.productTypeId == null" color="warning" variant="soft">全局默认</UBadge>
          <span v-else>{{ productTypeName(row.original.productTypeId) }}</span>
        </template>
        <template #margin-cell="{ row }"><strong class="num">{{ toPercent(row.original.targetGrossMargin) }}%</strong></template>
        <template #isActive-cell="{ row }">
          <UBadge :color="row.original.isActive ? 'success' : 'neutral'" variant="soft">{{ row.original.isActive ? '启用' : '禁用' }}</UBadge>
        </template>
        <template #description-cell="{ row }">{{ row.original.description || '—' }}</template>
        <template #updatedAt-cell="{ row }">{{ formatDateTime(row.original.updatedAt ?? row.original.createdAt) }}</template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton variant="link" size="xs" icon="i-lucide-pencil" data-testid="redline-edit" @click="openEdit(row.original)">编辑</UButton>
            <UButton color="error" variant="link" size="xs" icon="i-lucide-trash-2" data-testid="redline-delete" @click="handleDelete(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <!-- 新建/编辑弹窗 -->
    <UModal
      v-model:open="dialogVisible"
      :title="dialogTitle"
      description="配置目标毛利率"
      :dismissible="false"
      :ui="{ content: 'max-w-[540px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="form">
          <UFormField label="适用产品" :help="dialogMode === 'edit' ? '编辑时不可更改适用范围' : '选「全局默认」表示工厂级默认红线，优先级低于产品级配置。'">
            <USelectMenu
              :model-value="scopeValue"
              value-key="value"
              :items="productItems"
              :disabled="dialogMode === 'edit'"
              placeholder="全局默认 (所有产品)"
              class="w-full"
              data-testid="redline-scope"
              @update:model-value="onScopePick"
            />
          </UFormField>
          <UFormField label="目标毛利率 (%)" required help="报价对应毛利低于此值时触发「低于红线」预警 (非阻断)。须在 0%~100% 之间。">
            <UInputNumber v-model="formData.marginPercent" :min="0.01" :max="99.99" :step="1" :step-snapping="false" :format-options="{ maximumFractionDigits: 2 }" class="w-48" data-testid="redline-percent" />
          </UFormField>
          <UFormField label="状态">
            <USwitch v-model="formData.isActive" :label="formData.isActive ? '启用' : '禁用'" />
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="formData.description" :rows="2" :maxlength="500" placeholder="可选说明 (如: 镇级商务餐厅主力品类红线)" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" :disabled="submitting" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="submitting" data-testid="redline-submit" @click="handleSubmit">{{ dialogMode === 'create' ? '创建' : '保存' }}</UButton>
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
.form { display: grid; gap: var(--cs-space-3); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.num { font-variant-numeric: tabular-nums; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
