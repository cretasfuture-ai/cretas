<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import {
  createSupplier,
  downloadSupplierTemplate,
  listSuppliers,
  updateSupplierStatus,
  type SupplierRecord,
  type SupplierSavePayload,
} from '@/api/supplierManagement';
import SupplierDetailDrawer from './SupplierDetailDrawer.vue';
import SupplierImportDialog from './SupplierImportDialog.vue';
import {
  normalizeSupplierPayload,
  validateSupplierForm,
  supplierProfileComplete,
  supplierStatus,
  supplierStatusLabel,
} from './supplierModel';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const canWrite = computed(() => permissionStore.canWrite('procurement'));

const loading = ref(false);
const allData = ref<SupplierRecord[]>([]);
const pagination = reactive({ page: 1, size: 10 });
const route = useRoute();
const searchKeyword = ref('');
const filterStatus = ref<'' | 'ACTIVE' | 'INACTIVE'>('');
const detailVisible = ref(false);
const selectedSupplier = ref<SupplierRecord | null>(null);
const detailInitialTab = ref('profile');
const importVisible = ref(false);
const createVisible = ref(false);
const creating = ref(false);
const createFormRef = ref<{ submit: () => Promise<void> } | null>(null);
const createForm = reactive<SupplierSavePayload>({
  name: '', shortName: '', contactPerson: '', phone: '', address: '', email: '', bankAccount: '', taxNumber: '', notes: '',
});

// USelect 的选项值不能是空串: 页面内用 ALL 表示不筛
const statusItems = [
  { label: '全部状态', value: 'ALL' },
  { label: '合作中', value: 'ACTIVE' },
  { label: '暂停合作', value: 'INACTIVE' },
];
const statusSelect = computed({
  get: () => filterStatus.value || 'ALL',
  set: (v: string) => { filterStatus.value = v === 'ALL' ? '' : (v as 'ACTIVE' | 'INACTIVE'); },
});
const sizeItems = [10, 20, 50, 100].map((n) => ({ label: `${n} 条/页`, value: n }));
const columns: TableColumn<SupplierRecord>[] = [
  { id: 'code', header: '供应商编号' },
  { id: 'name', header: '供应商名称' },
  { id: 'contact', header: '联系人' },
  { id: 'phone', header: '联系电话' },
  { id: 'address', header: '地址' },
  { id: 'status', header: '状态' },
  { id: 'actions', header: '操作' },
];
function rowMenu(supplier: SupplierRecord) {
  return [[
    { label: '供应原料', onSelect: () => handleRowCommand('materials', supplier) },
    { label: supplierStatus(supplier) === 'ACTIVE' ? '暂停合作' : '恢复合作', onSelect: () => handleRowCommand('status', supplier) },
  ]];
}

const filteredData = computed(() => allData.value.filter((supplier) => {
  if (filterStatus.value && supplierStatus(supplier) !== filterStatus.value) return false;
  const keyword = searchKeyword.value.trim().toLowerCase();
  if (!keyword) return true;
  // 简称必须参与过滤 —— 客户要简称就是为了打简称找供应商
  return [supplier.name, supplier.shortName, supplier.supplierCode, supplier.code, supplier.contactPerson]
    .some((value) => String(value ?? '').toLowerCase().includes(keyword));
}));
const tableData = computed(() => {
  const start = (pagination.page - 1) * pagination.size;
  return filteredData.value.slice(start, start + pagination.size);
});

/**
 * 深链支持（2026-09-01）：采购订单弹窗的物料下拉在「该供应商没配原料」时，
 * 会新标签页打开本页并带 `?keyword=<供应商名>`，让用户直接看到那一家。
 *
 * ⚠️ 加这一段的**理由**：在此之前本页**完全不读** `route.query` ——
 * 那时若从别处传 `keyword` 过来，就是「产出端发了、消费端不读」：
 * 链接看着能点、点完什么也没发生，而 review 时看不出来。
 * ⇒ 要么这边真的读，要么那边别传。选了前者。
 *
 * ⛔ 只播种既有的 `searchKeyword`（客户端过滤），不改任何查询/权限逻辑。
 */
onMounted(() => {
  const keyword = route.query.keyword;
  if (typeof keyword === 'string' && keyword.trim()) {
    searchKeyword.value = keyword.trim();
  }
  loadData();
});

async function loadData(): Promise<void> {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    allData.value = await listSuppliers(factoryId.value);
    if ((pagination.page - 1) * pagination.size >= allData.value.length) pagination.page = 1;
  } finally {
    loading.value = false;
  }
}

function resetFilters(): void {
  searchKeyword.value = '';
  filterStatus.value = '';
  pagination.page = 1;
}

function openCreate(): void {
  // 必须逐字段重置(含 shortName): 漏一个字段, 重开弹窗就带着上次的残留值,
  // 而简称是工厂内唯一的 —— 残留会直接撞 409。
  Object.assign(createForm, {
    name: '', shortName: '', contactPerson: '', phone: '', address: '', email: '', bankAccount: '', taxNumber: '', notes: '',
  });
  createVisible.value = true;
}

async function submitCreate(): Promise<void> {
  // 校验由 UForm 的 validate（validateSupplierForm）在 @submit 之前完成
  creating.value = true;
  try {
    const created = await createSupplier(factoryId.value, normalizeSupplierPayload(createForm));
    ElMessage.success('供应商已创建');
    createVisible.value = false;
    await loadData();
  } finally {
    creating.value = false;
  }
}

function openDetail(supplier: SupplierRecord, tab?: string): void {
  selectedSupplier.value = supplier;
  detailInitialTab.value = tab || 'profile';
  detailVisible.value = true;
}

async function changeStatus(supplier: SupplierRecord): Promise<void> {
  const active = supplierStatus(supplier) !== 'ACTIVE';
  const action = active ? '恢复合作' : '暂停合作';
  try {
    const result = await ElMessageBox.prompt(
      active ? '恢复后可继续创建采购业务，请填写原因。' : '暂停后禁止新增采购业务，历史记录仍可查看，请填写原因。',
      action,
      { inputPlaceholder: `请输入${action}原因`, inputValidator: (value) => Boolean(value?.trim()) || '原因不能为空' },
    );
    await updateSupplierStatus(factoryId.value, supplier, active, result.value);
    ElMessage.success(`${action}成功`);
    await loadData();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') console.error('Supplier status update failed', error);
  }
}

function handleRowCommand(command: string, supplier: SupplierRecord): void {
  if (command === 'materials') openDetail(supplier, 'materials');
  if (command === 'status') void changeStatus(supplier);
}

async function handleDownloadTemplate(): Promise<void> {
  await downloadSupplierTemplate(factoryId.value);
  ElMessage.success('供应商标准模板已下载');
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="供应商"
      here="向我们供货的上游卖家（如肉联厂、包材厂），用于采购订单 / 应付账款"
      other-name="销售管理 → 客户"
      other="向我们采购的下游买家，用于销售订单 / 应收账款"
      other-path="/sales/customers"
    />

    <div class="cs-page-head">
      <div>
        <h2>供应商管理</h2>
        <p>共 {{ filteredData.length }} 条记录</p>
      </div>
      <div v-if="canWrite" class="head-actions">
        <UButton icon="i-lucide-download" color="neutral" variant="outline" @click="handleDownloadTemplate">下载标准模板</UButton>
        <UButton icon="i-lucide-upload" color="neutral" variant="outline" @click="importVisible = true">导入供应商</UButton>
        <UButton icon="i-lucide-plus" @click="openCreate">新增供应商</UButton>
      </div>
    </div>

    <section class="cs-card">
      <div class="cs-card-head">
        <UInput
          v-model="searchKeyword"
          icon="i-lucide-search"
          placeholder="搜索供应商名称/简称/编号/联系人"
          class="w-80"
          @keyup.enter="pagination.page = 1"
        />
        <USelect v-model="statusSelect" :items="statusItems" class="w-40" @update:model-value="pagination.page = 1" />
        <UButton icon="i-lucide-search" @click="pagination.page = 1">搜索</UButton>
        <UButton icon="i-lucide-rotate-ccw" color="neutral" variant="outline" @click="resetFilters">重置</UButton>
      </div>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table">
        <template #empty>暂无供应商</template>
        <template #code-cell="{ row }">{{ row.original.supplierCode || row.original.code || '-' }}</template>
        <template #name-cell="{ row }">
          <div class="name-cell">
            <UBadge v-if="row.original.shortName" color="primary" variant="outline">{{ row.original.shortName }}</UBadge>
            <span :title="row.original.name">{{ row.original.name }}</span>
            <UBadge v-if="!supplierProfileComplete(row.original)" color="warning" variant="outline">资料不完整</UBadge>
          </div>
        </template>
        <template #contact-cell="{ row }">{{ row.original.contactPerson || '资料不完整' }}</template>
        <template #phone-cell="{ row }">{{ row.original.phone || row.original.contactPhone || '资料不完整' }}</template>
        <template #address-cell="{ row }"><span :title="row.original.address || ''">{{ row.original.address || '资料不完整' }}</span></template>
        <template #status-cell="{ row }">
          <UBadge :color="supplierStatus(row.original) === 'ACTIVE' ? 'success' : 'neutral'" variant="soft">{{ supplierStatusLabel(row.original) }}</UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" @click="openDetail(row.original)">详情</UButton>
            <UDropdownMenu v-if="canWrite" :items="rowMenu(row.original)">
              <UButton size="xs" variant="link" color="neutral" icon="i-lucide-ellipsis">更多</UButton>
            </UDropdownMenu>
          </div>
        </template>
      </UTable>

      <div class="pagination-wrapper">
        <span class="total">共 {{ filteredData.length }} 条</span>
        <USelect v-model="pagination.size" :items="sizeItems" class="w-28" @update:model-value="pagination.page = 1" />
        <UPagination v-model:page="pagination.page" :items-per-page="pagination.size" :total="filteredData.length" show-edges :sibling-count="1" />
      </div>
    </section>

    <UModal v-model:open="createVisible" title="新增供应商" description="新增供应商资料" :dismissible="false" :ui="{ footer: 'justify-end', content: 'max-w-[620px]', description: 'sr-only' }">
      <template #body>
        <UAlert color="info" variant="subtle" icon="i-lucide-info" class="mb-4" title="供应商名称、联系人、联系电话和地址为必填项" />
        <UForm ref="createFormRef" :state="createForm" :validate="validateSupplierForm" class="create-form" @submit="submitCreate">
          <UFormField label="供应商名称" name="name" required><UInput v-model="createForm.name" :maxlength="200" class="w-full" /></UFormField>
          <UFormField label="简称" name="shortName" help="留空时下拉显示全称。同一工厂内简称不能重复。">
            <UInput v-model="createForm.shortName" :maxlength="50" placeholder="选填，下拉里优先显示，例如「飞熊」" class="w-full" />
          </UFormField>
          <UFormField label="联系人" name="contactPerson" required><UInput v-model="createForm.contactPerson" :maxlength="100" class="w-full" /></UFormField>
          <UFormField label="联系电话" name="phone" required><UInput v-model="createForm.phone" placeholder="大陆手机号或带区号座机，可含分机" :maxlength="40" class="w-full" /></UFormField>
          <UFormField label="地址" name="address" required><UTextarea v-model="createForm.address" :maxlength="500" :rows="2" class="w-full" /></UFormField>
          <UFormField label="邮箱" name="email"><UInput v-model="createForm.email" :maxlength="100" class="w-full" /></UFormField>
          <UFormField label="银行账户" name="bankAccount"><UInput v-model="createForm.bankAccount" :maxlength="100" class="w-full" /></UFormField>
          <UFormField label="税号" name="taxNumber"><UInput v-model="createForm.taxNumber" :maxlength="50" class="w-full" /></UFormField>
          <UFormField label="备注" name="notes"><UTextarea v-model="createForm.notes" :maxlength="5000" :rows="2" class="w-full" /></UFormField>
        </UForm>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createVisible = false">取消</UButton>
        <UButton :loading="creating" @click="createFormRef?.submit()">创建</UButton>
      </template>
    </UModal>

    <SupplierImportDialog v-model="importVisible" :factory-id="factoryId" @imported="loadData" />
    <SupplierDetailDrawer
      v-model="detailVisible"
      :factory-id="factoryId"
      :supplier="selectedSupplier"
      :can-write="canWrite"
      :initial-tab="detailInitialTab"
      @changed="loadData"
    />
  </div>
</template>

<style scoped>
.page-wrapper { padding: var(--cs-space-4); }
.head-actions { display: flex; gap: var(--cs-space-2); align-items: center; flex-wrap: wrap; }
.row-actions { display: flex; gap: var(--cs-space-1); align-items: center; }
.name-cell { display: flex; align-items: center; gap: var(--cs-space-2); min-width: 0; }
.create-form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.total { color: var(--cs-muted); font-size: var(--cs-font); }
.pagination-wrapper { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
</style>
