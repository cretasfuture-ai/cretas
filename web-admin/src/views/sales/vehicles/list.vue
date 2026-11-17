<script setup lang="ts">
/**
 * 车辆字典管理 (vehicles)
 * audit P1 fix: 后端 /vehicles 全 CRUD 齐全, 前端零页面.
 * 出货时应该能选车牌, 之前 dropdown 都没有 → 配车信息丢失.
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { TableRow } from '@/types/api';
import type { CsTableColumn } from '@/types/csTable';
import SalesConfirmModal from '../components/SalesConfirmModal.vue';
import { enumLabel } from '@/utils/enumDisplay';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const loading = ref(false);
const tableData = ref<TableRow[]>([]);

onMounted(loadData);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/vehicles`);
    if (res.success && res.data) {
      tableData.value = Array.isArray(res.data) ? res.data : (res.data.content || []);
    }
  } catch (e) { console.error(e); }
  finally { loading.value = false; }
}

const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const form = ref({
  plateNumber: '',
  driver: '',
  phone: '',
  vehicleType: '货车',
  capacity: 0,
  status: 'available',
  notes: '',
});
const dialogTitle = computed(() => (editingId.value ? '编辑车辆' : '新建车辆'));
const submitting = ref(false);

function openCreate() {
  editingId.value = null;
  form.value = { plateNumber: '', driver: '', phone: '', vehicleType: '货车', capacity: 0, status: 'available', notes: '' };
  dialogVisible.value = true;
}

function openEdit(row: TableRow) {
  editingId.value = String(row.id || '');
  form.value = {
    plateNumber: String(row.plateNumber || ''),
    driver: String(row.driver || ''),
    phone: String(row.phone || ''),
    vehicleType: String(row.vehicleType || '货车'),
    capacity: Number(row.capacity || 0),
    status: String(row.status || 'available'),
    notes: String(row.notes || ''),
  };
  dialogVisible.value = true;
}

async function handleSave() {
  if (!form.value.plateNumber) return ElMessage.warning('请填写车牌号');
  submitting.value = true;
  try {
    if (editingId.value) {
      const res = await put(`/${factoryId.value}/vehicles/${editingId.value}`, form.value);
      if (res.success) ElMessage.success('更新成功');
    } else {
      const res = await post(`/${factoryId.value}/vehicles`, form.value);
      if (res.success) ElMessage.success('创建成功');
    }
    dialogVisible.value = false;
    loadData();
  } catch (e) { console.error(e); }
  finally { submitting.value = false; }
}

async function handleDelete(row: TableRow) {
  const ok = await confirmRef.value!.ask({ title: '删除确认', message: `确定删除车辆「${row.plateNumber}」?`, confirmText: '删除', danger: true });
  if (ok === null) return;
  try {
    const res = await del(`/${factoryId.value}/vehicles/${row.id}`);
    if (res.success) { ElMessage.success('删除成功'); loadData(); }
  } catch (e) { console.error(e); }
}

/**
 * 车辆状态 —— 取值必须与后端 `Vehicle.VehicleStatus` 枚举**逐字一致**。
 *
 * 客户 2026-07-17 (复测 07-20 / 07-21 未解决):「未经翻译直接引用后端英文」,
 * 点名了车辆字典。这张表以前写的是
 *   available / loading / in_use / maintenance / retired
 * 而后端枚举是
 *   available / loading / dispatched / maintenance
 *
 * ⇒ 两个方向同时坏:
 *   ① 后端真会返回的 `dispatched` 不在表里 → 下面的 `|| row.status` 把**英文原码**
 *      直接显示给用户, 就是客户报的那条;
 *   ② 表里的 `in_use` / `retired` 后端**根本没有**, 选了它们
 *      `VehicleStatus.valueOf()` 会抛 → 400。
 *
 * 错的那份是从后端自己的报错文案抄来的 —— `VehicleServiceImpl` 的
 * `.withHint("请使用合法的状态值: …")` 同样漂了(它和自己的枚举不一致)。
 * ✅ 2026-08-19 已修: 那句话改成由 `Vehicle.VehicleStatus.legalValuesHint()` 生成,
 * 后端不再存在手写的第二份; 由 `VehicleStatusHintContractTest` 钉住。
 *
 * 权威来源 3 处已核对一致: Java 枚举 `Vehicle.VehicleStatus`、
 * RN `vehicleApiClient.ts` 的 `Vehicle['status']` 联合类型、本表。
 */
const statusMap: Record<string, { text: string; type: 'success' | 'warning' | 'neutral' }> = {
  available: { text: '可用', type: 'success' },
  loading: { text: '装货中', type: 'warning' },  // 出货时被锁定
  dispatched: { text: '已发车', type: 'warning' },
  maintenance: { text: '维护中', type: 'neutral' },
};
const STATUS_ITEMS = Object.entries(statusMap).map(([value, meta]) => ({ label: meta.text, value }));
const columns: CsTableColumn<TableRow>[] = [
  { accessorKey: 'plateNumber', header: '车牌号' },
  { accessorKey: 'vehicleType', header: '车型' },
  { accessorKey: 'driver', header: '司机' },
  { accessorKey: 'phone', header: '联系电话' },
  { id: 'capacity', header: '载重 (kg)', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'currentLoad', header: '当前载重 (kg)', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'status', header: '状态' },
  { id: 'actions', header: '操作' },
];
function statusColor(status: unknown) { return statusMap[String(status)]?.type ?? 'neutral'; }
</script>

<template>
  <div class="page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>车辆字典</h2>
        <span class="count">出货 / 调拨时可选的车辆列表</span>
        <div class="head-actions">
          <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="loading" data-testid="vehicle-refresh" @click="loadData">刷新</UButton>
          <UButton v-if="canWrite" icon="i-lucide-plus" data-testid="vehicle-create" @click="openCreate">新建车辆</UButton>
        </div>
      </header>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="vehicle-table">
        <template #empty>暂无车辆</template>
        <template #capacity-cell="{ row }"><span class="num">{{ row.original.capacity ?? '—' }}</span></template>
        <template #currentLoad-cell="{ row }"><span class="num">{{ row.original.currentLoad ?? '—' }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="statusColor(row.original.status)" variant="soft">{{ statusMap[row.original.status as string]?.text || enumLabel(row.original.status) }}</UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton v-if="canWrite" variant="link" size="xs" icon="i-lucide-pencil" @click="openEdit(row.original)">编辑</UButton>
            <UButton v-if="canWrite" color="error" variant="link" size="xs" icon="i-lucide-trash-2" @click="handleDelete(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <UModal v-model:open="dialogVisible" :title="dialogTitle" description="车辆信息" :ui="{ content: 'max-w-[500px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form">
          <UFormField label="车牌号" required><UInput v-model="form.plateNumber" placeholder="如 沪 A12345" class="w-full" data-testid="vehicle-plate" /></UFormField>
          <UFormField label="车型"><UInput v-model="form.vehicleType" placeholder="如 厢式货车 / 冷藏车" class="w-full" /></UFormField>
          <UFormField label="司机姓名"><UInput v-model="form.driver" class="w-full" /></UFormField>
          <UFormField label="司机电话"><UInput v-model="form.phone" class="w-full" /></UFormField>
          <UFormField label="载重 (kg)">
            <UInputNumber v-model="form.capacity" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ maximumFractionDigits: 2 }" class="w-full" />
          </UFormField>
          <UFormField label="状态">
            <!-- 选项由 statusMap 生成 —— 那张表已经和后端 VehicleStatus 枚举对齐,
                 手写第二份必然再漂一次(上一次就是这么坏的)。 -->
            <USelect v-model="form.status" :items="STATUS_ITEMS" class="w-full" />
          </UFormField>
          <UFormField label="备注"><UTextarea v-model="form.notes" :rows="2" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="submitting" data-testid="vehicle-save" @click="handleSave">保存</UButton>
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
