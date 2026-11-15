<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  createManufacturer,
  deleteManufacturer,
  listManufacturers,
  updateManufacturer,
  type ManufacturerPayload,
  type ManufacturerRegistry,
} from '@/api/manufacturer';

// 组件全部是 Nuxt UI (UTable / UModal / UFormField / UInput / UTextarea / USwitch / UBadge) + --cs-*；
// 弹窗整体 UModal (含停用确认)，里面不放 Element 控件。提示仍用 ElMessage（命令式服务）。
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

const loading = ref(false);
const tableData = ref<ManufacturerRegistry[]>([]);
const keyword = ref('');
const activeOnly = ref(false);

const dialogVisible = ref(false);
const dialogTitle = ref('新增厂商登记');
const saving = ref(false);
const editingId = ref<string | null>(null);
const form = reactive<ManufacturerPayload>({
  code: '',
  name: '',
  originPlace: '',
  isActive: true,
  remark: '',
});
// 必填校验（原 el-form rules）：提交时检查，错误显示在字段下方
const submitted = ref(false);
const codeError = computed(() => (submitted.value && !String(form.code || '').trim() ? '请输入厂号编码' : undefined));
const nameError = computed(() => (submitted.value && !String(form.name || '').trim() ? '请输入厂商名称' : undefined));

const deleteTarget = ref<ManufacturerRegistry | null>(null);
const deleteVisible = ref(false);
const deleting = ref(false);

const columns = computed<TableColumn<ManufacturerRegistry>[]>(() => [
  { accessorKey: 'code', header: '厂号' },
  { accessorKey: 'name', header: '厂商名称' },
  { id: 'originPlace', header: '默认产地' },
  { id: 'status', header: '状态' },
  { id: 'remark', header: '备注' },
  ...(canWrite.value ? [{ id: 'actions', header: '操作' } as TableColumn<ManufacturerRegistry>] : []),
]);

const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return tableData.value;
  return tableData.value.filter((row) =>
    [row.code, row.name, row.originPlace, row.remark]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(q))
  );
});

onMounted(loadData);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listManufacturers(factoryId.value, activeOnly.value);
    tableData.value = res.success && Array.isArray(res.data) ? res.data : [];
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  Object.assign(form, {
    code: '',
    name: '',
    originPlace: '',
    isActive: true,
    remark: '',
  });
  submitted.value = false;
}

function handleCreate() {
  editingId.value = null;
  dialogTitle.value = '新增厂商登记';
  resetForm();
  dialogVisible.value = true;
}

function handleEdit(row: ManufacturerRegistry) {
  editingId.value = row.id;
  dialogTitle.value = '编辑厂商登记';
  resetForm();
  Object.assign(form, {
    code: row.code,
    name: row.name,
    originPlace: row.originPlace || '',
    isActive: row.isActive,
    remark: row.remark || '',
  });
  dialogVisible.value = true;
}

async function handleSubmit() {
  if (!factoryId.value) return;
  submitted.value = true;
  if (codeError.value || nameError.value) return;
  saving.value = true;
  try {
    const payload = {
      ...form,
      code: String(form.code || '').trim(),
      name: String(form.name || '').trim(),
      originPlace: form.originPlace ? String(form.originPlace).trim() : null,
      remark: form.remark ? String(form.remark).trim() : null,
    };
    const res = editingId.value
      ? await updateManufacturer(factoryId.value, editingId.value, payload)
      : await createManufacturer(factoryId.value, payload);
    if (res.success) {
      ElMessage.success(editingId.value ? '厂商登记已更新' : '厂商登记已创建');
      dialogVisible.value = false;
      await loadData();
    }
  } catch (error) {
    const message = String((error as Error)?.message || '厂商登记保存失败');
    ElMessage({ message, type: 'error', duration: 0, showClose: true });
  } finally {
    saving.value = false;
  }
}

function handleDelete(row: ManufacturerRegistry) {
  deleteTarget.value = row;
  deleteVisible.value = true;
}

async function confirmDelete() {
  if (!factoryId.value || !deleteTarget.value) return;
  deleting.value = true;
  try {
    const res = await deleteManufacturer(factoryId.value, deleteTarget.value.id);
    if (res.success) {
      ElMessage.success('厂商登记已停用');
      deleteVisible.value = false;
      await loadData();
    }
  } catch (error) {
    const message = String((error as Error)?.message || '停用失败');
    ElMessage({ message, type: 'error', duration: 0, showClose: true });
  } finally {
    deleting.value = false;
  }
}
</script>

<template>
  <div class="page-container">
    <div class="cs-page-head">
      <div>
        <h2>厂商登记表</h2>
        <p>维护工厂级厂号编码、厂商名称和默认产地</p>
      </div>
      <UButton v-if="canWrite" icon="i-lucide-plus" data-testid="manufacturer-create" @click="handleCreate">新增厂商</UButton>
    </div>

    <section class="cs-card">
      <div class="toolbar">
        <UInput v-model="keyword" icon="i-lucide-search" placeholder="搜索厂号、厂商、产地" class="w-80" data-testid="manufacturer-keyword">
          <template v-if="keyword" #trailing>
            <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="keyword = ''" />
          </template>
        </UInput>
        <UCheckbox v-model="activeOnly" label="仅显示启用" @update:model-value="loadData" />
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="loadData">刷新</UButton>
      </div>

      <UTable :data="filteredRows" :columns="columns" :loading="loading" class="cs-table" data-testid="manufacturer-table">
        <template #empty>暂无厂商登记</template>
        <template #originPlace-cell="{ row }">{{ row.original.originPlace || '-' }}</template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(row.original.isActive ? 'success' : 'info')" variant="soft">{{ row.original.isActive ? '启用' : '停用' }}</UBadge>
        </template>
        <template #remark-cell="{ row }">{{ row.original.remark || '' }}</template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" @click="handleEdit(row.original)">编辑</UButton>
            <UButton size="xs" variant="link" color="error" @click="handleDelete(row.original)">停用</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <UModal v-model:open="dialogVisible" :title="dialogTitle" description="维护厂号编码、厂商名称与默认产地" :ui="{ content: 'max-w-[520px]', description: 'sr-only' }">
      <template #body>
        <div class="form">
          <UFormField label="厂号" required :error="codeError">
            <UInput v-model="form.code" :maxlength="64" class="w-full" data-testid="manufacturer-code" />
          </UFormField>
          <UFormField label="厂商名称" required :error="nameError">
            <UInput v-model="form.name" :maxlength="200" class="w-full" data-testid="manufacturer-name" />
          </UFormField>
          <UFormField label="默认产地">
            <UInput v-model="form.originPlace" :maxlength="200" class="w-full" />
          </UFormField>
          <UFormField label="状态">
            <USwitch v-model="form.isActive" :label="form.isActive ? '启用' : '停用'" />
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="form.remark" :rows="3" :maxlength="500" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="saving" @click="handleSubmit">保存</UButton>
      </template>
    </UModal>

    <UModal v-model:open="deleteVisible" title="停用厂商登记" description="确认停用厂号" :ui="{ content: 'max-w-[440px]', description: 'sr-only' }">
      <template #body>
        <p class="confirm">确认停用厂号 {{ deleteTarget?.code }}？</p>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="deleteVisible = false">取消</UButton>
        <UButton color="warning" :loading="deleting" data-testid="manufacturer-delete-confirm" @click="confirmDelete">停用</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.page-container { padding: var(--cs-space-4); }
.toolbar { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.row-actions { display: flex; gap: var(--cs-space-1); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.confirm { margin: 0; color: var(--cs-text); }
</style>
