<script setup lang="ts">
import { ref, onMounted, reactive, computed } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { CsTableColumn } from '@/types/csTable';
import SalesConfirmModal from '../components/SalesConfirmModal.vue';
import { handleCatchError } from '@/utils/errorToast';
import {
  listSalesNeeds,
  createSalesNeed,
  confirmSalesNeed,
  convertSalesNeed,
  cancelSalesNeed,
  type SalesNeed,
  type SalesNeedStatus,
  type SalesNeedPriority,
  type SalesNeedCreateRequest,
} from '@/api/salesNeed';

const router = useRouter();

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const list = ref<SalesNeed[]>([]);
const total = ref(0);
const loading = ref(false);
const page = ref(0);
const size = ref(20);
const statusFilter = ref<string>('');

const dialogVisible = ref(false);
// <input type=date> 的空值是空串, 接口要 undefined
const expectedDate = computed({
  get: () => form.expectedDeliveryDate ?? '',
  set: (v: string) => { form.expectedDeliveryDate = v || undefined; },
});
const submitting = ref(false);
const form = reactive<SalesNeedCreateRequest>({
  customerId: '',
  customerName: '',
  productId: '',
  productName: '',
  qtyDemand: 0,
  unit: '',
  expectedDeliveryDate: undefined,
  priority: 'NORMAL',
  remark: '',
});

async function load(): Promise<void> {
  loading.value = true;
  try {
    const res = await listSalesNeeds({
      status: statusFilter.value || undefined,
      page: page.value,
      size: size.value,
    });
    list.value = res?.data?.content ?? [];
    total.value = res?.data?.totalElements ?? 0;
  } catch (e) {
    // UX polish (2026-05-20): interceptor already shows specific 4xx/5xx message;
    // only fire fallback for pure network errors.
    handleCatchError(e, '加载失败,请检查网络');
  } finally {
    loading.value = false;
  }
}

function openCreate(): void {
  Object.assign(form, {
    customerId: '',
    customerName: '',
    productId: '',
    productName: '',
    qtyDemand: 0,
    unit: '',
    expectedDeliveryDate: undefined,
    priority: 'NORMAL',
    remark: '',
  });
  dialogVisible.value = true;
}

async function submitCreate(): Promise<void> {
  if (!form.customerId || !form.productId || !form.qtyDemand || !form.unit) {
    ElMessage.warning('请填写客户/产品/数量/单位');
    return;
  }
  submitting.value = true;
  try {
    await createSalesNeed(form);
    ElMessage.success('创建成功');
    dialogVisible.value = false;
    page.value = 0;
    load();
  } catch (e) {
    // UX polish (2026-05-20): interceptor surfaces backend message + actionHint;
    // local fallback only fires when no HTTP status (pure network error).
    handleCatchError(e, '创建失败,请检查网络');
  } finally {
    submitting.value = false;
  }
}

async function handleConfirm(row: SalesNeed): Promise<void> {
  const ok = await confirmRef.value!.ask({ title: '确认', message: `确认需求 ${row.id.slice(0, 8)}? 确认后可转销售订单.` });
  if (ok === null) return;
  try {
    await confirmSalesNeed(row.id);
    ElMessage.success('已确认');
    load();
  } catch (e) {
    handleCatchError(e, '确认失败,请检查网络');
  }
}

async function handleConvert(row: SalesNeed): Promise<void> {
  const ok = await confirmRef.value!.ask({
    title: '一键转销售单',
    message: `将需求转为销售订单? 产品: ${row.productName ?? row.productId}, 数量: ${row.qtyDemand} ${row.unit}.`,
    confirmText: '转换',
  });
  if (ok === null) return;
  try {
    const res = await convertSalesNeed(row.id);
    ElMessage.success('已转换, 跳转到销售订单');
    router.push(`/sales/orders/${res.data.id}`);
  } catch (e) {
    handleCatchError(e, '转换失败,请检查网络');
  }
}

async function handleCancel(row: SalesNeed): Promise<void> {
  const ok = await confirmRef.value!.ask({ title: '确认', message: '取消此需求?', confirmText: '取消需求', danger: true });
  if (ok === null) return;
  try {
    await cancelSalesNeed(row.id);
    ElMessage.success('已取消');
    load();
  } catch (e) {
    handleCatchError(e, '取消失败,请检查网络');
  }
}

type BadgeColor = 'neutral' | 'warning' | 'success' | 'error';
function statusTag(s: SalesNeedStatus): { type: BadgeColor; label: string } {
  if (s === 'DRAFT') return { type: 'neutral', label: '草稿' };
  if (s === 'CONFIRMED') return { type: 'warning', label: '已确认' };
  if (s === 'CONVERTED_TO_SO') return { type: 'success', label: '已转销售单' };
  return { type: 'error', label: '已取消' };
}

function priorityTag(p: SalesNeedPriority): { type: BadgeColor; label: string } {
  if (p === 'URGENT') return { type: 'error', label: '紧急' };
  if (p === 'HIGH') return { type: 'warning', label: '高' };
  if (p === 'LOW') return { type: 'neutral', label: '低' };
  return { type: 'neutral', label: '普通' };
}

const STATUS_FILTERS = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'CONVERTED_TO_SO', label: '已转单' },
  { value: 'CANCELLED', label: '已取消' },
];
const PRIORITY_ITEMS = [
  { label: '低', value: 'LOW' },
  { label: '普通', value: 'NORMAL' },
  { label: '高', value: 'HIGH' },
  { label: '紧急', value: 'URGENT' },
];
const columns: CsTableColumn<SalesNeed>[] = [
  { id: 'customer', header: '客户' },
  { id: 'product', header: '产品' },
  { id: 'qty', header: '数量' },
  { accessorKey: 'expectedDeliveryDate', header: '期望交货日' },
  { id: 'priority', header: '优先级' },
  { id: 'status', header: '状态' },
  { id: 'actions', header: '操作' },
];
function pickStatus(v: string): void {
  if (statusFilter.value === v) return;
  statusFilter.value = v;
  onStatusChange();
}

function onPageChange(p: number): void {
  if (p - 1 === page.value) return;
  page.value = p - 1;
  load();
}

function onStatusChange(): void {
  page.value = 0;
  load();
}

onMounted(load);
</script>

<template>
  <div class="page sales-needs">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>客户需求</h2>
        <span class="count">共 {{ total }} 条</span>
        <div class="head-actions">
          <div class="seg" data-testid="need-status-filter">
            <UButton
              v-for="f in STATUS_FILTERS"
              :key="f.value"
              size="sm"
              :color="statusFilter === f.value ? 'primary' : 'neutral'"
              :variant="statusFilter === f.value ? 'solid' : 'outline'"
              @click="pickStatus(f.value)"
            >{{ f.label }}</UButton>
          </div>
          <UButton icon="i-lucide-plus" data-testid="need-create" @click="openCreate">新建需求</UButton>
        </div>
      </header>

      <UTable :data="list" :columns="columns" :loading="loading" class="cs-table" data-testid="need-table">
        <template #empty>暂无客户需求</template>
        <template #customer-cell="{ row }">{{ row.original.customerName ?? row.original.customerId }}</template>
        <template #product-cell="{ row }">{{ row.original.productName ?? row.original.productId }}</template>
        <template #qty-cell="{ row }"><span class="num">{{ row.original.qtyDemand }} {{ row.original.unit }}</span></template>
        <template #priority-cell="{ row }">
          <UBadge :color="priorityTag(row.original.priority).type" variant="soft">{{ priorityTag(row.original.priority).label }}</UBadge>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="statusTag(row.original.status).type" variant="soft">{{ statusTag(row.original.status).label }}</UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton v-if="row.original.status === 'DRAFT'" variant="link" size="xs" @click="handleConfirm(row.original)">确认</UButton>
            <UButton v-if="row.original.status === 'CONFIRMED'" color="success" variant="link" size="xs" @click="handleConvert(row.original)">一键转销售单</UButton>
            <UButton v-if="row.original.status === 'CONVERTED_TO_SO' && row.original.convertedSalesOrderId" variant="link" size="xs" @click="router.push(`/sales/orders/${row.original.convertedSalesOrderId}`)">查看销售单</UButton>
            <UButton v-if="row.original.status === 'DRAFT' || row.original.status === 'CONFIRMED'" color="error" variant="link" size="xs" @click="handleCancel(row.original)">取消</UButton>
          </div>
        </template>
      </UTable>

      <div v-if="total > 0" class="pager">
        <UPagination :page="page + 1" :items-per-page="size" :total="total" show-edges :sibling-count="1" @update:page="onPageChange" />
      </div>
    </section>

    <UModal v-model:open="dialogVisible" title="新建客户需求" description="录入客户需求" :ui="{ content: 'max-w-[600px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form">
          <UFormField label="客户ID" required><UInput v-model="form.customerId" placeholder="客户 ID (UUID)" class="w-full" data-testid="need-customer-id" /></UFormField>
          <UFormField label="客户名称"><UInput v-model="form.customerName" placeholder="客户名称 (冗余, 用于显示)" class="w-full" /></UFormField>
          <UFormField label="产品ID" required><UInput v-model="form.productId" placeholder="ProductType ID" class="w-full" data-testid="need-product-id" /></UFormField>
          <UFormField label="产品名称"><UInput v-model="form.productName" placeholder="产品名称 (冗余)" class="w-full" /></UFormField>
          <UFormField label="数量" required>
            <UInputNumber v-model="form.qtyDemand" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ maximumFractionDigits: 4 }" class="w-48" />
          </UFormField>
          <UFormField label="单位" required><UInput v-model="form.unit" placeholder="如 kg / 箱 / 件" class="w-48" /></UFormField>
          <UFormField label="期望交货日"><UInput v-model="expectedDate" type="date" class="w-48" /></UFormField>
          <UFormField label="优先级"><USelect v-model="form.priority" :items="PRIORITY_ITEMS" class="w-48" /></UFormField>
          <UFormField label="备注"><UTextarea v-model="form.remark" :rows="3" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="submitting" data-testid="need-submit" @click="submitCreate">提交</UButton>
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
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-3); }
.seg { display: flex; gap: var(--cs-space-1); }
.form { display: grid; gap: var(--cs-space-3); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-4) var(--cs-space-5); }
.num { font-variant-numeric: tabular-nums; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
