<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';
import {
  PROCESS_SECRET_LABEL,
  isProcessSecretHidden,
  isProcessSecretRow,
  processSecretMessage,
} from '@/utils/processSecretField';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');

// 对话框
const dialogVisible = ref(false);
const dialogLoading = ref(false);
const isEdit = ref(false);
const conversionForm = ref({
  id: '',
  materialTypeId: '',
  productTypeId: '',
  conversionRate: 0,
  wastageRate: 0,
  notes: ''
});
const columns: TableColumn<TableRow>[] = [
  { accessorKey: 'materialTypeName', header: '原料类型' },
  { id: 'product', header: '产品类型' },
  { id: 'rate', header: '转换率' },
  { id: 'wastage', header: '损耗率' },
  { accessorKey: 'notes', header: '备注' },
  { accessorKey: 'updatedAt', header: '更新时间' },
  { id: 'actions', header: '操作' },
];
const sizeItems = [10, 20, 50, 100].map(n => ({ label: `${n} 条/页`, value: n }));
const deleteVisible = ref(false);
const deleting = ref(false);
const deleteTarget = ref<TableRow | null>(null);
const materialTypes = ref<TableRow[]>([]);
const productTypes = ref<TableRow[]>([]);

onMounted(() => {
  loadData();
  loadMaterialTypes();
  loadProductTypes();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/conversions`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadMaterialTypes() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/raw-material-types/active`);
    if (response.success && response.data) {
      materialTypes.value = Array.isArray(response.data) ? response.data : (response.data.content || []);
    }
  } catch (error) {
    console.error('加载原料类型失败:', error);
  }
}

async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/product-types`);
    if (response.success && response.data) {
      productTypes.value = response.data.content || response.data || [];
    }
  } catch (error) {
    console.error('加载产品类型失败:', error);
  }
}

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchKeyword.value = '';
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function handleCreate() {
  isEdit.value = false;
  conversionForm.value = {
    id: '',
    materialTypeId: '',
    productTypeId: '',
    conversionRate: 0,
    wastageRate: 0,
    notes: ''
  };
  dialogVisible.value = true;
}

function handleEdit(row: TableRow) {
  isEdit.value = true;
  conversionForm.value = {
    id: row.id,
    materialTypeId: row.materialTypeId,
    productTypeId: row.productTypeId,
    conversionRate: row.conversionRate,
    wastageRate: row.wastageRate || 0,
    notes: row.notes || ''
  };
  dialogVisible.value = true;
}

async function submitForm() {
  // 防呆 Rule 2: 逐字段提示缺什么, 不再用 generic "请填写完整信息"
  if (!conversionForm.value.materialTypeId) {
    ElMessage.warning('请选择原料类型');
    return;
  }
  if (!conversionForm.value.productTypeId) {
    ElMessage.warning('请选择产品类型');
    return;
  }
  if (!conversionForm.value.conversionRate) {
    ElMessage.warning('请输入转换率（需大于 0）');
    return;
  }

  dialogLoading.value = true;
  try {
    let response;
    if (isEdit.value) {
      response = await put(`/${factoryId.value}/conversions/${conversionForm.value.id}`, conversionForm.value);
    } else {
      response = await post(`/${factoryId.value}/conversions`, conversionForm.value);
    }
    if (response.success) {
      ElMessage.success(isEdit.value ? '更新成功' : '创建成功');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast; debug-only log.
    console.error('[提交失败]', error);
  } finally {
    dialogLoading.value = false;
  }
}

function handleDelete(row: TableRow) {
  deleteTarget.value = row;
  deleteVisible.value = true;
}

async function doDelete() {
  if (!deleteTarget.value) return;
  deleting.value = true;
  try {
    const response = await del(`/${factoryId.value}/conversions/${deleteTarget.value.id}`);
    if (response.success) {
      ElMessage.success('删除成功');
      deleteVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '删除失败');
    }
  } catch (error) {
    // Interceptor shows specific toast; debug-only log.
    console.error('[失败]', error);
  } finally {
    deleting.value = false;
  }
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="转换率配置"
      here="单一原料 → 单一成品的「出成率」（如 1kg 冻猪蹄 → 600g 卤猪蹄，60%）"
      other-name="生产管理 → BOM 成本管理"
      other="一个成品需要哪些原料、各多少量、成本如何拆分（多对多 + 成本核算）"
      other-path="/production/bom"
    />

    <div class="cs-page-head">
      <div>
        <h2>转换率配置</h2>
        <p>共 {{ pagination.total }} 条记录</p>
      </div>
      <UButton v-if="canWrite" icon="i-lucide-plus" data-testid="conversion-create" @click="handleCreate">新增配置</UButton>
    </div>

    <section class="cs-card">
      <div class="cs-card-head">
        <UInput
          v-model="searchKeyword"
          icon="i-lucide-search"
          placeholder="搜索原料/产品名称"
          class="w-72"
          @keyup.enter="handleSearch"
        />
        <UButton icon="i-lucide-search" @click="handleSearch">搜索</UButton>
        <UButton icon="i-lucide-rotate-ccw" color="neutral" variant="outline" @click="handleRefresh">重置</UButton>
      </div>

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table">
        <template #empty>暂无数据</template>
        <template #product-cell="{ row }">{{ row.original.productTypeName || row.original.productName || row.original.productTypeId || '-' }}</template>
        <template #rate-cell="{ row }">
          <span v-if="isProcessSecretHidden(row.original, 'conversionRate')" :title="processSecretMessage(row.original)">
            <UBadge color="neutral" variant="outline">{{ PROCESS_SECRET_LABEL }}</UBadge>
          </span>
          <span v-else class="num">{{ (row.original.conversionRate * 100).toFixed(1) }}%</span>
        </template>
        <template #wastage-cell="{ row }">
          <span v-if="isProcessSecretHidden(row.original, 'wastageRate')" :title="processSecretMessage(row.original)">
            <UBadge color="neutral" variant="outline">{{ PROCESS_SECRET_LABEL }}</UBadge>
          </span>
          <span v-else class="num">{{ ((row.original.wastageRate || 0) * 100).toFixed(1) }}%</span>
        </template>
        <template #actions-cell="{ row }">
          <!-- 工艺保密: 转换率已隐藏的行不能在这里改 (否则会把空值 / 0 存回去), 需保密负责人授权 -->
          <span v-if="canWrite && isProcessSecretRow(row.original)" :title="processSecretMessage(row.original)">
            <UBadge color="neutral" variant="outline">需授权才能修改</UBadge>
          </span>
          <div v-else-if="canWrite" class="row-actions">
            <UButton size="xs" variant="link" icon="i-lucide-pencil" @click="handleEdit(row.original)">编辑</UButton>
            <UButton size="xs" variant="link" color="error" icon="i-lucide-trash-2" @click="handleDelete(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>

      <div class="pagination-wrapper">
        <span class="total">共 {{ pagination.total }} 条</span>
        <USelect v-model="pagination.size" :items="sizeItems" class="w-28" @update:model-value="handleSizeChange" />
        <UPagination v-model:page="pagination.page" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </div>
    </section>

    <!-- 编辑弹窗 -->
    <UModal v-model:open="dialogVisible" :title="isEdit ? '编辑转换率' : '新增转换率'" description="维护单一原料到单一成品的转换率" :ui="{ content: 'max-w-[500px]', description: 'sr-only' }">
      <template #body>
        <div class="form">
          <UFormField label="原料类型" required>
            <USelectMenu v-model="conversionForm.materialTypeId" :items="materialTypes" value-key="id" label-key="name" placeholder="选择原料类型" class="w-full" />
          </UFormField>
          <UFormField label="产品类型" required>
            <USelectMenu v-model="conversionForm.productTypeId" :items="productTypes" value-key="id" label-key="name" placeholder="选择产品类型" class="w-full" />
          </UFormField>
          <UFormField label="转换率" required help="0-1之间的小数，如0.85表示85%">
            <UInputNumber v-model="conversionForm.conversionRate" :min="0" :max="1" :step="0.01" :format-options="{ minimumFractionDigits: 2, maximumFractionDigits: 2 }" :increment="false" :decrement="false" class="w-full" />
          </UFormField>
          <UFormField label="损耗率">
            <UInputNumber v-model="conversionForm.wastageRate" :min="0" :max="1" :step="0.01" :format-options="{ minimumFractionDigits: 2, maximumFractionDigits: 2 }" :increment="false" :decrement="false" class="w-full" />
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="conversionForm.notes" :rows="3" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="dialogLoading" data-testid="conversion-submit" @click="submitForm">确定</UButton>
      </template>
    </UModal>

    <UModal v-model:open="deleteVisible" title="删除转换率配置" description="确认删除" :ui="{ content: 'max-w-[440px]', description: 'sr-only' }">
      <template #body>
        <p class="confirm">确定删除此转换率配置？</p>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="deleteVisible = false">取消</UButton>
        <UButton color="error" :loading="deleting" @click="doDelete">删除</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.page-wrapper { padding: var(--cs-space-4); }
.row-actions { display: flex; gap: var(--cs-space-1); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.pagination-wrapper { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
.total { color: var(--cs-muted); font-size: var(--cs-font); }
.confirm { margin: 0; color: var(--cs-text); }
</style>
