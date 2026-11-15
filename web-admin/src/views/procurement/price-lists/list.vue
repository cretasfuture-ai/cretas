<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, del } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const allData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const dialogVisible = ref(false);
const searchKeyword = ref('');

const priceTypeMap: Record<string, string> = {
  PURCHASE_PRICE: '采购价',
  SELLING_PRICE: '销售价',
  TRANSFER_PRICE: '调拨价',
};

// 筛选 (实时按当前数据里实际存在的 类型/状态 生成选项, 非固定枚举; 目录条目有限, 客户端筛选/分页)
const filterPriceType = ref('');
const filterActive = ref<'' | 'ACTIVE' | 'INACTIVE'>('');

const priceTypeOptions = computed(() => {
  const set = new Set(allData.value.map(r => r.priceType).filter((v): v is string => !!v));
  return Array.from(set).sort();
});
const activeOptions = computed(() => {
  const opts: Array<{ value: 'ACTIVE' | 'INACTIVE'; label: string }> = [];
  const hasActive = allData.value.some(r => r.isActive);
  const hasInactive = allData.value.some(r => !r.isActive);
  if (hasActive) opts.push({ value: 'ACTIVE', label: '生效中' });
  if (hasInactive) opts.push({ value: 'INACTIVE', label: '未生效' });
  return opts;
});

const filteredData = computed(() => allData.value.filter(row => {
  if (filterPriceType.value && row.priceType !== filterPriceType.value) return false;
  if (filterActive.value === 'ACTIVE' && !row.isActive) return false;
  if (filterActive.value === 'INACTIVE' && row.isActive) return false;
  return true;
}));

const tableData = computed(() => {
  const start = (pagination.value.page - 1) * pagination.value.size;
  return filteredData.value.slice(start, start + pagination.value.size);
});

function handleFilterChange() {
  pagination.value.page = 1;
}

function handleFilterReset() {
  filterPriceType.value = '';
  filterActive.value = '';
  pagination.value.page = 1;
}

// USelect 的选项值不能是空串: 页面内用 ALL 表示不筛
const typeItems = computed(() => [
  { label: '全部类型', value: 'ALL' },
  ...priceTypeOptions.value.map((pt) => ({ label: priceTypeMap[pt] || pt, value: pt })),
]);
const activeItems = computed(() => [{ label: '全部状态', value: 'ALL' }, ...activeOptions.value]);
const typeSelect = computed({
  get: () => filterPriceType.value || 'ALL',
  set: (v: string) => { filterPriceType.value = v === 'ALL' ? '' : v; },
});
const activeSelect = computed({
  get: () => filterActive.value || 'ALL',
  set: (v: string) => { filterActive.value = v === 'ALL' ? '' : (v as 'ACTIVE' | 'INACTIVE'); },
});
const sizeItems = [10, 20, 50].map((n) => ({ label: `${n} 条/页`, value: n }));
const priceTypeItems = Object.entries(priceTypeMap).map(([value, label]) => ({ value, label }));
const priceFormat = { minimumFractionDigits: 2, maximumFractionDigits: 2 };
const expanded = ref<Record<string, boolean>>({});
const columns: TableColumn<TableRow>[] = [
  { id: 'expand', header: '', meta: { class: { th: 'w-12', td: 'w-12' } } },
  { id: 'name', header: '价格表名称' },
  { id: 'priceType', header: '类型' },
  { id: 'effectiveFrom', header: '生效日期' },
  { id: 'effectiveTo', header: '失效日期' },
  { id: 'isActive', header: '状态' },
  { id: 'actions', header: '操作' },
];
const itemColumns: TableColumn<TableRow>[] = [
  { id: 'itemName', accessorKey: 'itemName', header: '项目名称' },
  { id: 'unit', accessorKey: 'unit', header: '单位' },
  { id: 'standardPrice', header: '标准价', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'minPrice', header: '最低价', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'maxPrice', header: '最高价', meta: { class: { th: 'text-right', td: 'text-right' } } },
];

const form = ref({
  name: '',
  priceType: 'PURCHASE_PRICE',
  effectiveFrom: '',
  effectiveTo: '',
  items: [{ itemName: '', unit: 'kg', standardPrice: 0, minPrice: 0, maxPrice: 0 }],
});

onMounted(() => loadData());

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // 全量拉取 (价格表目录条目数有限, 非高基数表) 供筛选下拉的"当前有的"选项 + 客户端筛选/分页用。
    const params: TableRow = { page: 1, size: 500 };
    const kw = searchKeyword.value.trim();
    if (kw) params.keyword = kw;
    const res = await get(`/${factoryId.value}/price-lists`, { params });
    if (res.success && res.data) {
      allData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
      pagination.value.page = 1;
    } else if (res.success === false) {
      ElMessage.error(res.message || '加载价格表失败');
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

function addItem() {
  form.value.items.push({ itemName: '', unit: 'kg', standardPrice: 0, minPrice: 0, maxPrice: 0 });
}

function removeItem(idx: number) {
  if (form.value.items.length > 1) form.value.items.splice(idx, 1);
}

async function handleCreate() {
  if (!form.value.name) return ElMessage.warning('请输入价格表名称');
  if (!form.value.priceType) return ElMessage.warning('请选择价格类型');
  if (!form.value.effectiveFrom) return ElMessage.warning('请选择生效日期');
  if (!Array.isArray(form.value.items) || form.value.items.length === 0) {
    return ElMessage.warning('请至少添加一行价格明细');
  }
  const badPriceIdx = form.value.items.findIndex(i => i.standardPrice == null);
  if (badPriceIdx >= 0) {
    return ElMessage.warning(`第 ${badPriceIdx + 1} 行请填写标准价`);
  }
  try {
    const res = await post(`/${factoryId.value}/price-lists`, form.value);
    if (res.success) { ElMessage.success('创建成功'); dialogVisible.value = false; loadData(); }
    else { ElMessage.error(res.message || '创建失败'); }
  } catch { /* axios interceptor already displayed error toast */ }
}

async function handleDelete(id: string) {
  try {
    await ElMessageBox.confirm('确认删除此价格表？', '提示');
    const res = await del(`/${factoryId.value}/price-lists/${id}`);
    if (res.success) { ElMessage.success('删除成功'); loadData(); }
    else { ElMessage.error(res.message || '删除失败'); }
  } catch (error) { /* axios interceptor handles API errors; cancel from MessageBox is silent */ }
}

function handlePageChange(page: number) { pagination.value.page = page; }
function handleSizeChange(size: number) { pagination.value.size = size; pagination.value.page = 1; }
function handleSearch() { pagination.value.page = 1; loadData(); }
function handleSearchClear() { searchKeyword.value = ''; handleSearch(); }
</script>

<template>
  <div class="page-wrapper">
    <div class="cs-page-head">
      <div>
        <h2>价格表管理</h2>
        <p>共 {{ pagination.total }} 条</p>
      </div>
      <div class="head-actions">
        <UButton v-if="canWrite && canViewPrice" icon="i-lucide-plus" @click="dialogVisible = true">新建价格表</UButton>
      </div>
    </div>

    <section v-if="canViewPrice" class="cs-card">
      <div class="cs-card-head">
        <UInput
          v-model="searchKeyword"
          icon="i-lucide-search"
          placeholder="搜索价格表名称"
          class="w-60"
          @keyup.enter="handleSearch"
        />
        <UButton icon="i-lucide-search" @click="handleSearch">搜索</UButton>
        <!-- 筛选: 按当前数据里实际存在的 类型/状态 生成选项, 非固定枚举 -->
        <USelect v-model="typeSelect" :items="typeItems" class="w-36" @update:model-value="handleFilterChange" />
        <USelect v-model="activeSelect" :items="activeItems" class="w-36" @update:model-value="handleFilterChange" />
        <UButton icon="i-lucide-rotate-ccw" color="neutral" variant="outline" @click="handleFilterReset">重置</UButton>
      </div>

      <UTable v-model:expanded="expanded" :data="tableData" :columns="columns" :loading="loading" class="cs-table" :get-row-id="(r: TableRow) => String(r.id)">
        <template #empty>暂无数据</template>
        <template #expand-cell="{ row }">
          <UButton
            size="xs"
            variant="ghost"
            color="neutral"
            :icon="row.getIsExpanded() ? 'i-lucide-chevron-down' : 'i-lucide-chevron-right'"
            :aria-label="row.getIsExpanded() ? '收起明细' : '展开明细'"
            @click="row.toggleExpanded()"
          />
        </template>
        <template #name-cell="{ row }"><span :title="row.original.name">{{ row.original.name }}</span></template>
        <template #priceType-cell="{ row }">
          <UBadge color="primary" variant="soft">{{ priceTypeMap[row.original.priceType] || row.original.priceType }}</UBadge>
        </template>
        <template #effectiveFrom-cell="{ row }"><span class="num">{{ row.original.effectiveFrom }}</span></template>
        <template #effectiveTo-cell="{ row }"><span class="num">{{ row.original.effectiveTo }}</span></template>
        <template #isActive-cell="{ row }">
          <UBadge :color="row.original.isActive ? 'success' : 'neutral'" variant="soft">{{ row.original.isActive ? '生效中' : '未生效' }}</UBadge>
        </template>
        <template #actions-cell="{ row }">
          <UButton v-if="canWrite" size="xs" variant="link" color="error" @click="handleDelete(row.original.id)">删除</UButton>
        </template>
        <template #expanded="{ row }">
          <div class="expanded">
            <UTable :data="row.original.items || []" :columns="itemColumns" class="cs-table">
              <template #empty>暂无明细</template>
              <template #standardPrice-cell="{ row: item }"><span class="num">{{ formatAmount(item.original.standardPrice) }}</span></template>
              <template #minPrice-cell="{ row: item }"><span class="num">{{ formatAmount(item.original.minPrice) }}</span></template>
              <template #maxPrice-cell="{ row: item }"><span class="num">{{ formatAmount(item.original.maxPrice) }}</span></template>
            </UTable>
          </div>
        </template>
      </UTable>

      <div class="pagination-wrapper">
        <span class="total">共 {{ filteredData.length }} 条</span>
        <USelect v-model="pagination.size" :items="sizeItems" class="w-28" @update:model-value="handleSizeChange" />
        <UPagination v-model:page="pagination.page" :items-per-page="pagination.size" :total="filteredData.length" show-edges :sibling-count="1" @update:page="handlePageChange" />
      </div>
    </section>
    <section v-else class="cs-card">
      <div class="no-perm">您没有查看价格数据的权限</div>
    </section>

    <UModal v-if="canViewPrice" v-model:open="dialogVisible" title="新建价格表" description="新建采购/销售/调拨价格表" :dismissible="false" :ui="{ content: 'max-w-[760px]', description: 'sr-only' }">
      <template #body>
        <div class="form">
          <UFormField label="名称" required><UInput v-model="form.name" placeholder="例如:2025 Q2 采购标准价" class="w-full" /></UFormField>
          <UFormField label="类型" required>
            <URadioGroup v-model="form.priceType" orientation="horizontal" :items="priceTypeItems" />
          </UFormField>
          <div class="date-row">
            <UFormField label="生效日期" required class="flex-1"><UInput v-model="form.effectiveFrom" type="date" class="w-full" /></UFormField>
            <UFormField label="失效日期" class="flex-1"><UInput v-model="form.effectiveTo" type="date" class="w-full" /></UFormField>
          </div>
          <USeparator label="价格明细" />
          <div class="item-row item-header">
            <span class="c-name">名称</span>
            <span class="c-unit">单位</span>
            <span class="c-price"><span class="req-star">*</span>标准价</span>
            <span class="c-price">最低</span>
            <span class="c-price">最高</span>
            <span class="c-act"></span>
          </div>
          <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
            <UInput v-model="item.itemName" placeholder="名称" class="c-name" />
            <UInput v-model="item.unit" placeholder="单位" class="c-unit" />
            <UInputNumber v-model="item.standardPrice" :min="0" :step-snapping="false" placeholder="标准价" :format-options="priceFormat" :increment="false" :decrement="false" class="c-price" />
            <UInputNumber v-model="item.minPrice" :min="0" :step-snapping="false" placeholder="最低" :format-options="priceFormat" :increment="false" :decrement="false" class="c-price" />
            <UInputNumber v-model="item.maxPrice" :min="0" :step-snapping="false" placeholder="最高" :format-options="priceFormat" :increment="false" :decrement="false" class="c-price" />
            <UButton variant="link" color="error" class="c-act" :disabled="form.items.length <= 1" @click="removeItem(idx)">删除</UButton>
          </div>
          <UButton color="neutral" variant="outline" block icon="i-lucide-plus" @click="addItem">添加行</UButton>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton @click="handleCreate">创建</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.page-wrapper { padding: var(--cs-space-4); }
.head-actions { display: flex; gap: var(--cs-space-2); align-items: center; }
.expanded { padding: var(--cs-space-3) var(--cs-space-6); background: var(--cs-surface-subtle); }
.no-perm { padding: var(--cs-space-6); text-align: center; color: var(--cs-muted); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.date-row { display: flex; gap: var(--cs-space-3); }
.flex-1 { flex: 1; min-width: 0; }
.item-row { display: flex; gap: var(--cs-space-2); align-items: center; }
.item-header { font-size: var(--cs-font-small); color: var(--cs-muted); font-weight: 500; }
.c-name { width: 170px; flex: none; }
.c-unit { width: 80px; flex: none; }
.c-price { width: 120px; flex: none; }
.c-act { width: 56px; flex: none; }
.req-star { color: var(--cs-danger); margin-right: 2px; }
.total { color: var(--cs-muted); font-size: var(--cs-font); }
.pagination-wrapper { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
</style>
