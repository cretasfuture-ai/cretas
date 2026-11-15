<!--
  客户档案「价格」页 (客户价目表最小闭环, 2026-11-14 DECISIONS 十九):
  列出并维护该客户的专属售价。这里维护的价, 就是销售开单选客户+产品时自动带出的价
  (来源标「客户专属价」, 订单行可回查)。采购价目不在此页。价格列无权限时后端置 null, 显示「—」。
  组件: Nuxt UI (UTable / UBadge / UButton / UModal / UFormField / UInputNumber / USelectMenu / UEmpty / UAlert) + --cs-*。
  新增/改价弹窗与删除确认整体都是 UModal, 里面没有 Element 控件。
-->
<template>
  <div class="price-tab">
    <div class="toolbar">
      <span class="title">客户专属价目</span>
      <span class="hint">开单选这个客户和对应产品时，会自动带出下面的价，并标注来源「客户专属价」。</span>
      <UButton v-if="canEdit" icon="i-lucide-plus" @click="openAdd">新增专属价</UButton>
    </div>

    <div v-if="state === 'loading'" class="skeleton" data-testid="customer-price-loading">
      <USkeleton v-for="i in 4" :key="i" class="h-9 w-full" />
    </div>
    <UAlert v-else-if="state === 'error'" color="error" variant="subtle" :title="errorMsg" :actions="[{ label: '重试', color: 'error', variant: 'outline', onClick: fetchList }]" />
    <UEmpty v-else-if="rows.length === 0" icon="i-lucide-tag" description="该客户还没有专属价，开单时按统一价或最近成交价" class="empty" />
    <UTable v-else :data="rows" :columns="columns" class="cs-table" data-testid="customer-price-table">
      <template #price-cell="{ row }"><span class="num">{{ row.original.standardPrice == null ? '—' : Number(row.original.standardPrice).toFixed(2) }}</span></template>
      <template #state-cell="{ row }">
        <UBadge :color="row.original.effective ? 'success' : 'neutral'" variant="soft">{{ row.original.effective ? '生效中' : '未生效' }}</UBadge>
      </template>
      <template #actions-cell="{ row }">
        <UButton variant="link" size="xs" @click="openEdit(row.original)">改价</UButton>
        <UButton variant="link" color="error" size="xs" @click="askRemove(row.original)">删除</UButton>
      </template>
    </UTable>

    <UModal v-model:open="dialogVisible" :title="editing ? '修改专属价' : '新增专属价'" description="维护该客户的专属未税单价" :ui="{ content: 'max-w-[440px]', description: 'sr-only' }">
      <template #body>
        <div class="form">
          <UFormField label="产品">
            <USelectMenu
              v-model="form.productTypeId"
              :items="productOptions"
              value-key="id"
              label-key="label"
              :disabled="editing"
              placeholder="选择成品"
              class="w-full"
              data-testid="price-product-select"
            />
          </UFormField>
          <UFormField label="未税单价">
            <UInputNumber v-model="form.standardPrice" :min="0.0001" :step="0.0001" :format-options="{ minimumFractionDigits: 0, maximumFractionDigits: 4 }" :increment="false" :decrement="false" class="w-full" data-testid="price-input" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="saving" :disabled="!form.productTypeId || !(Number(form.standardPrice) > 0)" @click="save">保存</UButton>
      </template>
    </UModal>

    <UModal v-model:open="confirmVisible" title="删除专属价" description="删除后开单按统一价或最近成交价" :ui="{ content: 'max-w-[440px]', description: 'sr-only' }">
      <template #body>
        <p class="confirm">删除「{{ pending?.itemName || pending?.productTypeId }}」的专属价？删除后开单按统一价或最近成交价。</p>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="confirmVisible = false">取消</UButton>
        <UButton color="error" :loading="removing" @click="doRemove">删除</UButton>
      </template>
    </UModal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import {
  listCustomerPrices,
  saveCustomerPrice,
  deleteCustomerPrice,
  type CustomerPriceItem,
} from '@/api/customerPriceList';
import { isSalesOrderProduct, type SalesProductOption } from '@/views/sales/orders/salesOrderProductSearch';

const props = defineProps<{ customerId: string }>();
const { factoryId } = storeToRefs(useAuthStore());
const permissionStore = usePermissionStore();
const canEdit = computed(() => permissionStore.canWrite('sales'));

const state = ref<'loading' | 'ready' | 'error'>('loading');
const errorMsg = ref('');
const rows = ref<CustomerPriceItem[]>([]);
const productOptions = ref<{ id: string; label: string }[]>([]);
const dialogVisible = ref(false);
const confirmVisible = ref(false);
const removing = ref(false);
const pending = ref<CustomerPriceItem | null>(null);
const R = { class: { th: 'text-right', td: 'text-right' } };
const columns = computed<TableColumn<CustomerPriceItem>[]>(() => [
  { accessorKey: 'itemName', header: '产品' },
  { accessorKey: 'unit', header: '单位' },
  { id: 'price', header: '未税单价', meta: R },
  { id: 'state', header: '状态' },
  { accessorKey: 'effectiveFrom', header: '生效日期' },
  ...(canEdit.value ? [{ id: 'actions', header: '操作' }] : []),
]);
const editing = ref(false);
const saving = ref(false);
const form = ref<{ productTypeId: string; standardPrice: number | null }>({ productTypeId: '', standardPrice: null });

async function fetchList() {
  if (!factoryId.value) return;
  state.value = 'loading';
  try {
    rows.value = await listCustomerPrices(factoryId.value, props.customerId);
    state.value = 'ready';
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '加载失败';
    state.value = 'error';
  }
}

async function loadProducts() {
  if (productOptions.value.length || !factoryId.value) return;
  const res = await get<SalesProductOption[]>(`/${factoryId.value}/product-types/sellable`);
  const list = res.success && Array.isArray(res.data) ? res.data : [];
  productOptions.value = list
    .filter(isSalesOrderProduct)
    .map((p) => ({ id: String(p.id), label: String(p.name || p.productName || p.id) }));
}

async function openAdd() {
  editing.value = false;
  form.value = { productTypeId: '', standardPrice: null };
  dialogVisible.value = true;
  await loadProducts();
}

async function openEdit(row: CustomerPriceItem) {
  editing.value = true;
  form.value = { productTypeId: row.productTypeId, standardPrice: Number(row.standardPrice ?? 0) };
  if (!productOptions.value.some((p) => p.id === row.productTypeId)) {
    productOptions.value.push({ id: row.productTypeId, label: row.itemName || row.productTypeId });
  }
  dialogVisible.value = true;
}

async function save() {
  if (!factoryId.value) return;
  saving.value = true;
  try {
    await saveCustomerPrice(factoryId.value, props.customerId, {
      productTypeId: form.value.productTypeId,
      standardPrice: Number(form.value.standardPrice),
    });
    ElMessage.success('已保存，开单将按此价带出');
    dialogVisible.value = false;
    await fetchList();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

function askRemove(row: CustomerPriceItem) {
  pending.value = row;
  confirmVisible.value = true;
}

async function doRemove() {
  const row = pending.value;
  if (!row || !factoryId.value) return;
  removing.value = true;
  try {
    await deleteCustomerPrice(factoryId.value, props.customerId, row.itemId);
    ElMessage.success('已删除');
    confirmVisible.value = false;
    await fetchList();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  } finally {
    removing.value = false;
  }
}

onMounted(fetchList);
</script>

<style scoped>
.price-tab { padding: 12px 0; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.title { font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
.hint { flex: 1; color: var(--cs-muted); font-size: var(--cs-font-small); }
.skeleton { display: grid; gap: 8px; }
.form { display: grid; gap: var(--cs-space-4); }
.confirm { margin: 0; color: var(--cs-text); }
.num { font-variant-numeric: tabular-nums; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
