<template>
  <div class="reusable-containers-page">
    <section class="cs-card">
      <header class="cs-card-head header">
        <h2>周转耗材管理 (周转框)</h2>
        <UButton data-testid="container-create" @click="openCreate">新建周转耗材</UButton>
      </header>

      <UTable :data="rows" :columns="columns" :loading="loading" class="cs-table" data-testid="container-table">
        <template #empty>暂无周转耗材</template>
        <template #unitPrice-cell="{ row }"><span class="num">{{ row.original.unitPrice }}</span></template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" color="neutral" variant="outline" @click="openShipOut(row.original)">发出</UButton>
            <UButton size="xs" color="success" variant="outline" @click="openReturnIn(row.original)">归还</UButton>
            <UButton size="xs" color="warning" variant="outline" @click="openLoss(row.original)">报损</UButton>
            <UButton size="xs" variant="link" @click="viewTx(row.original)">流水</UButton>
          </div>
        </template>
      </UTable>

      <div class="pager">
        <span class="total">共 {{ total }} 条</span>
        <UPagination v-model:page="page" :items-per-page="size" :total="total" show-edges :sibling-count="1" @update:page="loadList" />
      </div>
    </section>

    <!-- 新建 -->
    <UModal v-model:open="createVisible" title="新建周转耗材" description="登记新的周转耗材" :ui="{ content: 'max-w-[520px]', description: 'sr-only' }">
      <template #body>
        <div class="form">
          <UFormField label="编号"><UInput v-model="createForm.containerCode" class="w-full" /></UFormField>
          <UFormField label="名称"><UInput v-model="createForm.containerName" class="w-full" /></UFormField>
          <UFormField label="规格"><UInput v-model="createForm.specification" placeholder="60*40*30 cm / 25kg" class="w-full" /></UFormField>
          <UFormField v-if="canViewPrice" label="单价/赔偿价">
            <UInputNumber :step-snapping="false" v-model="createForm.unitPrice" :min="0" :format-options="MONEY_FORMAT" :increment="false" :decrement="false" class="w-full" />
          </UFormField>
          <UFormField label="初始数量">
            <UInputNumber :step-snapping="false" v-model="createForm.totalQuantity" :min="0" :increment="false" :decrement="false" class="w-full" />
          </UFormField>
          <UFormField label="备注"><UTextarea v-model="createForm.remark" :rows="3" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createVisible = false">取消</UButton>
        <UButton @click="submitCreate">确认创建</UButton>
      </template>
    </UModal>

    <!-- 发出 / 归还 / 报损 通用 -->
    <UModal v-model:open="actionVisible" :title="actionTitle" description="周转耗材发出、归还或报损登记" :ui="{ content: 'max-w-[500px]', description: 'sr-only' }">
      <template #body>
        <div class="form">
          <UFormField label="数量">
            <UInputNumber :step-snapping="false" v-model="actionForm.quantity" :min="1" :increment="false" :decrement="false" class="w-full" />
          </UFormField>
          <UFormField label="客户ID"><UInput v-model="actionForm.customerId" class="w-full" /></UFormField>
          <UFormField label="客户名称"><UInput v-model="actionForm.customerName" class="w-full" /></UFormField>
          <UFormField v-if="actionType === 'shipOut'" label="发货单ID"><UInput v-model="actionForm.salesDeliveryId" class="w-full" /></UFormField>
          <UFormField v-if="actionType === 'loss' && canViewPrice" label="赔偿金额">
            <UInputNumber :step-snapping="false" v-model="actionForm.compensationAmount" :min="0" :format-options="MONEY_FORMAT" :increment="false" :decrement="false" class="w-full" />
          </UFormField>
          <UFormField label="备注"><UTextarea v-model="actionForm.remark" :rows="3" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="actionVisible = false">取消</UButton>
        <UButton @click="submitAction">确认</UButton>
      </template>
    </UModal>

    <!-- 流水 -->
    <UModal v-model:open="txVisible" title="流水记录" description="周转耗材收发流水" :ui="{ content: 'max-w-[780px]', description: 'sr-only' }">
      <template #body>
        <UTable :data="txRows" :columns="txColumns" :loading="txLoading" class="cs-table" data-testid="container-tx-table">
          <template #empty>暂无流水</template>
        </UTable>
      </template>
    </UModal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from 'vue';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { get, post } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';

// 组件全部是 Nuxt UI (UTable / UModal / UFormField / UInput / UInputNumber / UPagination) + --cs-*；
// 三个弹窗整体 UModal，不混用 Element 控件。提示仍用 ElMessage（命令式服务）。
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canViewPrice = computed(() => permissionStore.canViewPrice);

const MONEY_FORMAT = { minimumFractionDigits: 2, maximumFractionDigits: 2 };

const loading = ref(false);
const rows = ref<any[]>([]);
const page = ref(1);
const size = ref(20);
const total = ref(0);

type Row = Record<string, any>;
const columns = computed<TableColumn<Row>[]>(() => [
  { accessorKey: 'containerCode', header: '编号' },
  { accessorKey: 'containerName', header: '名称' },
  { accessorKey: 'specification', header: '规格' },
  ...(canViewPrice.value ? [{ id: 'unitPrice', header: '单价/赔偿价' } as TableColumn<Row>] : []),
  { accessorKey: 'totalQuantity', header: '总持有' },
  { accessorKey: 'inWarehouseQuantity', header: '在库' },
  { accessorKey: 'inTransitQuantity', header: '在客户处' },
  { id: 'actions', header: '操作' },
]);
const txColumns = computed<TableColumn<Row>[]>(() => [
  { accessorKey: 'createdAt', header: '时间' },
  { accessorKey: 'transactionType', header: '类型' },
  { accessorKey: 'quantity', header: '数量' },
  { accessorKey: 'customerName', header: '客户' },
  ...(canViewPrice.value ? [{ accessorKey: 'compensationAmount', header: '赔偿金额' } as TableColumn<Row>] : []),
  { accessorKey: 'remark', header: '备注' },
]);

const baseUrl = computed(() => `/${factoryId.value}/reusable-containers`);

async function loadList() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res: any = await get(baseUrl.value, {
      params: { page: page.value - 1, size: size.value },
    });
    const data = res?.data ?? res;
    rows.value = data?.content || [];
    total.value = data?.totalElements || 0;
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败');
  } finally {
    loading.value = false;
  }
}

// 创建
const createVisible = ref(false);
const createForm = ref<any>({});
function openCreate() {
  createForm.value = { totalQuantity: 0, unitPrice: 0 };
  createVisible.value = true;
}
async function submitCreate() {
  // 前端必填校验 — 防止静默提交空表单
  if (!createForm.value.containerCode || !createForm.value.containerCode.trim()) {
    ElMessage.warning('请填写编号');
    return;
  }
  if (!createForm.value.containerName || !createForm.value.containerName.trim()) {
    ElMessage.warning('请填写名称');
    return;
  }
  if (!factoryId.value) {
    ElMessage.error('工厂信息未就绪，请重新登录');
    return;
  }
  try {
    const res: any = await post(baseUrl.value, createForm.value);
    if (res && res.success === false) {
      throw new Error(res.message || '创建失败');
    }
    ElMessage.success('创建成功');
    createVisible.value = false;
    // 重置到首页以确保新建行可见 (按 createdAt desc 排序时)
    page.value = 1;
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.message || e?.response?.data?.message || '创建失败');
  }
}

// 发出/归还/报损
const actionVisible = ref(false);
const actionType = ref<'shipOut' | 'returnIn' | 'loss'>('shipOut');
const actionForm = ref<any>({});
const currentRow = ref<any>(null);
const actionTitle = computed(() => ({
  shipOut: '发出周转框',
  returnIn: '客户归还',
  loss: '登记丢失/赔偿',
}[actionType.value]));

function openShipOut(row: any) { currentRow.value = row; actionType.value = 'shipOut'; actionForm.value = { quantity: 1 }; actionVisible.value = true; }
function openReturnIn(row: any) { currentRow.value = row; actionType.value = 'returnIn'; actionForm.value = { quantity: 1 }; actionVisible.value = true; }
function openLoss(row: any) { currentRow.value = row; actionType.value = 'loss'; actionForm.value = { quantity: 1, compensationAmount: row.unitPrice || 0 }; actionVisible.value = true; }

async function submitAction() {
  try {
    const path = { shipOut: 'ship-out', returnIn: 'return-in', loss: 'loss' }[actionType.value];
    await post(`${baseUrl.value}/${currentRow.value.id}/${path}`, actionForm.value);
    ElMessage.success('操作成功');
    actionVisible.value = false;
    await loadList();
  } catch (e: any) {
    ElMessage.error(e?.message || '操作失败');
  }
}

// 流水
const txVisible = ref(false);
const txRows = ref<any[]>([]);
const txLoading = ref(false);
async function viewTx(row: any) {
  txVisible.value = true;
  txLoading.value = true;
  try {
    const res: any = await get(`${baseUrl.value}/${row.id}/transactions`);
    txRows.value = res?.data ?? res ?? [];
  } catch (e: any) {
    ElMessage.error(e?.message || '加载流水失败');
  } finally {
    txLoading.value = false;
  }
}

onMounted(loadList);
</script>

<style scoped>
.reusable-containers-page { padding: var(--cs-space-4); }
.header { justify-content: space-between; }
.header h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.row-actions { display: flex; gap: var(--cs-space-1); align-items: center; }
.pager { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); }
.total { color: var(--cs-muted); font-size: var(--cs-font-small); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
</style>
