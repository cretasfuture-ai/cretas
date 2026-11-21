<script setup lang="ts">
import { CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatSelect } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

import { ref, onMounted, computed, h } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';

import { Plus, Search, Refresh, ChatDotRound } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import type { TableRow } from '@/types/api';
import { RowActionMenu } from '@/components/list';
import { computeRowActions } from '@/composables/useRowActions';
import { safePrint } from '@/api/printApi';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { PROCESS_TASK_CONFIG } from '@/components/ai-entry/types';
import { useTableColumnWidth } from '@/composables/useTableColumnWidth';
import { plannedBasisOf, plannedColumnLabel, plannedQuantityOrNull } from './plannedQuantityBasis';

const router = useRouter();

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

type BatchTableRow = TableRow & {
  isSyntheticPlanGroup?: boolean;
  children?: BatchTableRow[];
  sourcePlanId?: string | null;
  sourcePlanNumber?: string | null;
  sourceProcessOrder?: number | null;
  sourceProcessCode?: string | null;
};

function batchRowClassName({ row }: { row: BatchTableRow }): string {
  return row.isSyntheticPlanGroup ? 'plan-group-row' : '';
}

function rowActionsFor(row: TableRow) {
  // #751: 删除 dropdown 中的 'view-detail' (页面已有独立"查看" button, 避免重复 button 跳同页)
  // fool-proof-design Rule 5: 'edit'/'lock' 没有真实后端能力 (无批次编辑表单/接口,
  // 无锁定 API) — 之前点了才弹 toast (click-then-toast, dead-end)。改用
  // forceDisabled: 灰显 + hover 提示原因, 用户不用点一次才知道不可用。
  const all = computeRowActions(
    'processTask',
    { status: String(row.status || 'IN_PROGRESS'), id: String(row.id || '') },
    {
      canViewPrice: canViewPrice.value,
      forceDisabled: {
        edit: '批次编辑功能暂未上线 (无独立编辑表单)，如需修改请联系管理员',
        lock: '锁定功能后端 API 对接中，暂不可用',
      },
    }
  );
  return all.filter((a) => a.id !== 'view-detail');
}
function handleRowActionClick(actionId: string, row: TableRow) {
  switch (actionId) {
    case 'view-detail': router.push(`/production/batches/${row.id}`); break;
    // #751: 编辑跳详情页 + ?mode=edit hint — 'edit' 已在 rowActionsFor() 用
    // forceDisabled 灰显 (无编辑表单), 这个 case 保留只作防御 (元素被禁用后
    // el-dropdown 不会派发 command)。
    case 'edit': router.push(`/production/batches/${row.id}?mode=edit`); break;
    case 'print-pdf': void safePrint('production-task', factoryId.value, String(row.id), { fileName: `生产批次_${row.batchNumber || row.id}` }); break;
    case 'lock':
      // 'lock' 已在 rowActionsFor() 用 forceDisabled 灰显; 保留仅作防御。
      ElMessage.info(t('production.batches_list.message_461f4ba2b59e'));
      break;
    default: ElMessage.warning(`该操作暂不支持: ${actionId}`);
  }
}
// AI 智能创建生产批次 (Day 9, Issue #780.3)
const aiEntryVisible = ref(false);
function openAiForRow(_row: TableRow) {
  // Row-context AI is future scope; current Day 9 opens drawer for CREATE flow
  aiEntryVisible.value = true;
}
function openAiCreate() {
  aiEntryVisible.value = true;
}
async function handleAiFill(params: Record<string, unknown>) {
  // Load product types if not already cached so name-matching can work
  if (productTypes.value.length === 0 && factoryId.value) {
    try {
      const res = await get(`/${factoryId.value}/product-types/active`);
      if (res.success) {
        productTypes.value = res.data || [];
      }
    } catch (e: unknown) {
      console.error('加载产品类型失败:', e);
    }
  }

  const productName = String(params.productTypeName || '');
  const matched = productTypes.value.find(
    (p) => {
      const name = String(p.name || p.productName || '');
      return name.includes(productName) || productName.includes(name);
    }
  );

  createForm.value = {
    batchNumber: generateBatchNumber(),
    productTypeId: matched ? String(matched.id) : '',
    plannedQuantity: Number(params.plannedQuantity || 0) || null,
    unit: String(params.unit || 'kg'),
    notes: String(params.notes || ''),
    isTrial: false,
    trialSampleId: '',
  };

  if (!matched && productName) {
    ElMessage.warning(`未找到匹配的产品类型 "${productName}"，请手动选择`);
  }

  createDialogVisible.value = true;
}

const loading = ref(false);
const tableData = ref<BatchTableRow[]>([]);
// B4: 「计划数量」列的真实含义 (理论产能 / 计划数量), 见 plannedQuantityBasis.ts
const plannedColumnText = computed(() => plannedColumnLabel(tableData.value));
const plannedMixed = computed(() => plannedColumnText.value.includes('/'));
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  batchNumber: '',
  status: ''
});

// 缺陷 50: 后端 /batches-with-clerk-wip 不接受 batchNumber 参数 (见
// ProcessingController#getBatchesWithClerkWip / ProcessingServiceImpl#getBatches* 全链路),
// Spring 会静默丢弃这个查询参数 —— 服务端从来没有按批次号过滤过。之前的表现是:
// 搜一个「存在但不在当前页」的批次号, 页面照样把当前页的全部(分组后)数据显示出来,
// 用户会读成「查到的就是这些」, 误以为搜的批次不存在。
// 这里只能在前端对「已经加载到本页的数据」做诚实的关键词过滤 —— 命中就只显示命中的
// 行/分组, 不命中就显示明确的空态(而不是退回未过滤列表)。跨页的真正后端关键词检索
// 需要后端加一个 batchNumber/keyword 查询参数, 现有 API 没有, 不在本次改动范围内。
const appliedBatchKeyword = ref('');

function normalizeBatchSearchKeyword(value: string): string {
  return value.trim().toLowerCase();
}

function rowMatchesBatchKeyword(row: BatchTableRow, keyword: string): boolean {
  if (String(row.batchNumber || '').toLowerCase().includes(keyword)) return true;
  // CLERK_WIP 行归组时用 sourcePlanNumber/sourcePlanId 当分组标题 (见 displayTableData),
  // 按计划号搜也应该命中该计划下的批次, 不只是按批次自己的编号搜。
  if (row.batchType === 'CLERK_WIP') {
    if (String(row.sourcePlanNumber || '').toLowerCase().includes(keyword)) return true;
    if (String(row.sourcePlanId || '').toLowerCase().includes(keyword)) return true;
  }
  return false;
}

function sumQuantity(rows: BatchTableRow[], key: string) {
  let total = 0;
  let hasValue = false;
  for (const row of rows) {
    const value = Number(row[key]);
    if (Number.isFinite(value)) {
      total += value;
      hasValue = true;
    }
  }
  return hasValue ? Number(total.toFixed(3)) : null;
}

const displayTableData = computed<BatchTableRow[]>(() => {
  const result: BatchTableRow[] = [];
  const groups = new Map<string, BatchTableRow>();

  const keyword = appliedBatchKeyword.value;
  const sourceRows = keyword
    ? tableData.value.filter((row) => rowMatchesBatchKeyword(row, keyword))
    : tableData.value;

  for (const row of sourceRows) {
    const sourcePlanKey = row.batchType === 'CLERK_WIP'
      ? String(row.sourcePlanId || row.sourcePlanNumber || '')
      : '';

    if (!sourcePlanKey) {
      result.push(row);
      continue;
    }

    let group = groups.get(sourcePlanKey);
    if (!group) {
      group = {
        id: `source-plan-${sourcePlanKey}`,
        batchNumber: row.sourcePlanNumber || row.sourcePlanId || '未归属生产计划',
        productTypeName: row.productTypeName || row.productName || '',
        status: 'SOURCE_PLAN_GROUP',
        isSyntheticPlanGroup: true,
        sourcePlanId: row.sourcePlanId,
        sourcePlanNumber: row.sourcePlanNumber,
        children: []
      };
      groups.set(sourcePlanKey, group);
      result.push(group);
    }

    group.children?.push(row);
  }

  for (const group of groups.values()) {
    const children = group.children || [];
    children.sort((a, b) => {
      const orderA = Number(a.sourceProcessOrder ?? Number.MAX_SAFE_INTEGER);
      const orderB = Number(b.sourceProcessOrder ?? Number.MAX_SAFE_INTEGER);
      if (orderA !== orderB) return orderA - orderB;
      return String(a.batchNumber || '').localeCompare(String(b.batchNumber || ''));
    });
    group.plannedQuantity = sumQuantity(children, 'plannedQuantity');
    group.actualQuantity = sumQuantity(children, 'actualQuantity');
  }

  return result;
});

// 命中 0 行时, 必须明确说「没搜到」而不是回退成暂无数据/未过滤列表 —— 后者
// 会被用户读成「这批次不存在」(缺陷 50)。空态回显关键词, 让用户能核对自己输对了没有。
const tableEmptyText = computed(() => {
  if (appliedBatchKeyword.value && displayTableData.value.length === 0) {
    return `没有匹配「${searchForm.value.batchNumber.trim()}」的批次`;
  }
  return '暂无数据';
});

/**
 * 排序表头 —— 与出成率总览 (YieldCardTable) 同一套做法, per PR #1991。
 *
 * el-table 自带的 `sortable` 只渲染一对没有名字的箭头, 屏幕阅读器读到的是
 * 「一串没名字的控件」。这里换成一个真按钮, 自带 aria-label 说明按哪一列排;
 * 视觉上完全继承单元格字体与颜色, 不额外套壳 (客户原话:「每个表头套了独立边框盒子」)。
 */
function sortableHeader({ column }: { column: { label?: string } }) {
  const label = column.label || '本列';
  return h(
    'button',
    {
      type: 'button',
      class: 'batch-sort-trigger',
      'aria-label': `按${label}排序，连续操作可切换升序和降序`,
      title: t('production.batches_list.title_c1f50de56eac'),
    },
    label,
  );
}

/**
 * 列宽记忆 (客户 Sheet Row 13: 调好的列宽刷新就没了)。
 * defaults 即这张表原本写死的宽度 —— 没拖过时渲染结果与以前一致;
 * `计划/批次号` 与 `产品类型` 用 min-width 自适应, 刻意不入 defaults。
 */
const BATCH_LIST_DEFAULT_COLUMN_WIDTHS = {
  plannedQuantity: 120,
  actualQuantity: 120,
  sourceProcess: 160,
  status: 120,
  supervisorName: 120,
  createdAt: 190,
} as const;

const { columnWidth, handleHeaderDragend, resetColumnWidths, hasStoredColumnWidths } = useTableColumnWidth({
  pageKey: 'production.batches.list',
  scope: factoryId,
  defaults: BATCH_LIST_DEFAULT_COLUMN_WIDTHS,
});

type TableSortRow = Record<string, unknown>;

function compareNullableNumber(left: TableSortRow, right: TableSortRow, prop: string) {
  const leftValue = Number(left[prop]);
  const rightValue = Number(right[prop]);
  const leftValid = Number.isFinite(leftValue);
  const rightValid = Number.isFinite(rightValue);
  if (!leftValid && !rightValid) return 0;
  if (!leftValid) return 1;
  if (!rightValid) return -1;
  return leftValue - rightValue;
}

function compareNullableDate(left: TableSortRow, right: TableSortRow, prop: string) {
  const leftValue = Date.parse(String(left[prop] || ''));
  const rightValue = Date.parse(String(right[prop] || ''));
  const leftValid = Number.isFinite(leftValue);
  const rightValid = Number.isFinite(rightValue);
  if (!leftValid && !rightValid) return 0;
  if (!leftValid) return 1;
  if (!rightValid) return -1;
  return leftValue - rightValue;
}

function productNameOf(row: BatchTableRow) {
  return String(row.productTypeName || row.productName || row.productTypeId || '-');
}

function sourceProcessOf(row: BatchTableRow) {
  if (row.isSyntheticPlanGroup) return '生产计划';
  if (row.batchType === 'CLERK_WIP' && row.sourceProcessOrder) {
    return `第${row.sourceProcessOrder}道${row.sourceProcessCode ? ` / ${row.sourceProcessCode}` : ''}`;
  }
  return '-';
}

const productFilters = computed(() => Array.from(new Set(tableData.value.map(productNameOf)))
  .filter((value) => value !== '-')
  .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  .map((value) => ({ text: value, value })));

const sourceProcessFilters = computed(() => Array.from(new Set(displayTableData.value.flatMap((row) => [
  sourceProcessOf(row),
  ...(row.children || []).map(sourceProcessOf)
])))
  .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  .map((value) => ({ text: value, value })));

const supervisorFilters = computed(() => Array.from(new Set(tableData.value
  .map((row) => String(row.supervisorName || '-'))))
  .sort((a, b) => a.localeCompare(b, 'zh-CN'))
  .map((value) => ({ text: value, value })));

const statusFilters = [
  { text: '待生产', value: 'PLANNED' },
  { text: '生产中', value: 'IN_PROGRESS' },
  { text: '已暂停', value: 'PAUSED' },
  { text: '已完成', value: 'COMPLETED' },
  { text: '已取消', value: 'CANCELLED' },
  { text: '计划归组', value: 'SOURCE_PLAN_GROUP' }
];

function openSourcePlan(row: BatchTableRow) {
  if (row.sourcePlanId) {
    router.push({ path: '/production/plans', query: { openProcessEntryPlan: String(row.sourcePlanId) } });
    return;
  }
  router.push('/production/plans');
}

// 创建批次
const createDialogVisible = ref(false);
const creating = ref(false);
const productTypes = ref<TableRow[]>([]);
const LEGACY_CREATE_UNITS = ['kg', '箱', '件', '吨'] as const;
const createForm = ref({
  batchNumber: '',
  productTypeId: '',
  plannedQuantity: null as number | null,
  unit: 'kg',
  notes: '',
  // SP10: 试制批次标记
  isTrial: false,
  trialSampleId: '',
});

function declaredProductUnit(product: TableRow | undefined): string {
  return String(product?.unit || product?.quantityUnit || '').trim();
}

const createUnitOptions = computed(() => {
  const selected = productTypes.value.find((product) => String(product.id) === String(createForm.value.productTypeId));
  const declared = declaredProductUnit(selected);
  return Array.from(new Set([...(declared ? [declared] : []), ...LEGACY_CREATE_UNITS]));
});

function handleCreateProductTypeChange(productTypeId: string | number) {
  const selected = productTypes.value.find((product) => String(product.id) === String(productTypeId));
  const declared = declaredProductUnit(selected);
  if (declared) createForm.value.unit = declared;
}

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/processing/batches-with-clerk-wip`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        ...searchForm.value
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载数据失败');
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  appliedBatchKeyword.value = normalizeBatchSearchKeyword(searchForm.value.batchNumber);
  loadData();
}

function handleReset() {
  searchForm.value = { batchNumber: '', status: '' };
  handleSearch();
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

function generateBatchNumber() {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  const rand = Math.random().toString(36).substring(2, 7).toUpperCase();
  return `PB-${y}${m}${d}-${rand}`;
}

async function handleCreate() {
  createForm.value = { batchNumber: generateBatchNumber(), productTypeId: '', plannedQuantity: null, unit: 'kg', notes: '', isTrial: false, trialSampleId: '' };
  createDialogVisible.value = true;
  // Load product types for dropdown
  if (productTypes.value.length === 0 && factoryId.value) {
    try {
      const res = await get(`/${factoryId.value}/product-types/active`);
      if (res.success) {
        productTypes.value = res.data || [];
      }
    } catch (e: unknown) {
      console.error('加载产品类型失败:', e);
      const err = e as { actionHint?: unknown };
      if (!err?.actionHint) ElMessage.error(t('production.batches_list.message_781dfe4b709b'));
    }
  }
}

async function submitCreate() {
  if (!factoryId.value) return;
  if (!createForm.value.batchNumber) {
    ElMessage.warning(t('production.batches_list.message_c198c1041667'));
    return;
  }
  if (!createForm.value.productTypeId) {
    ElMessage.warning(t('production.batches_list.message_7ddb27f93df1'));
    return;
  }
  if (!createForm.value.plannedQuantity || createForm.value.plannedQuantity <= 0) {
    ElMessage.warning(t('production.batches_list.message_c560d0f388a2'));
    return;
  }

  const selectedProduct = productTypes.value.find((p) => p.id === createForm.value.productTypeId);
  creating.value = true;
  try {
    const response = await post(`/${factoryId.value}/processing/batches`, {
      batchNumber: createForm.value.batchNumber,
      productTypeId: createForm.value.productTypeId,
      productName: selectedProduct?.name || selectedProduct?.productName || '',
      plannedQuantity: createForm.value.plannedQuantity,
      quantity: createForm.value.plannedQuantity,
      unit: createForm.value.unit,
      notes: createForm.value.notes,
      // SP10: 试制批次字段
      isTrial: createForm.value.isTrial || false,
      trialSampleId: createForm.value.isTrial && createForm.value.trialSampleId ? createForm.value.trialSampleId : null
    });
    if (response.success) {
      ElMessage.success(t('production.batches_list.message_2b7123a11244'));
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error: unknown) {
    const e = error as { actionHint?: unknown; response?: { data?: { message?: string } } };
    if (!e?.actionHint) ElMessage.error(e?.response?.data?.message || '创建失败');
  } finally {
    creating.value = false;
  }
}

// ProductionBatchStatus.java real values: PLANNED/PLANNING/IN_PROGRESS/PRODUCING/
// PAUSED/COMPLETED/CANCELLED. PENDING kept for back-compat display only (not a
// real value); PLANNING/PRODUCING are legacy aliases of PLANNED/IN_PROGRESS.
function getStatusType(status: string) {
  const map: Record<string, string> = {
    PLANNED: 'info',
    PLANNING: 'info',
    PENDING: 'info',
    IN_PROGRESS: 'warning',
    PRODUCING: 'warning',
    PAUSED: 'warning',
    COMPLETED: 'success',
    CANCELLED: 'danger'
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    PLANNED: '待生产',
    PLANNING: '待生产',
    PENDING: '待生产',
    IN_PROGRESS: '生产中',
    PRODUCING: '生产中',
    PAUSED: '已暂停',
    COMPLETED: '已完成',
    CANCELLED: '已取消'
  };
  return map[status?.toUpperCase()] || status;
}
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="生产批次"
      :here="`已开工的实际「批次」（${getStatusText('IN_PROGRESS')} / ${getStatusText('COMPLETED')}，记录实际产量、消耗、报工）`"
      other-name="生产管理 → 生产计划"
      :other="`未来要做什么的「计划」（${getStatusText('PENDING')}状态，可调整数量、日期）`"
      other-path="/production/plans"
    />
    <UCard class="page-card" >
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ t('production.batches_list.text_f5d7b262e90c') }}</span>
            <span class="data-count">{{ t('production.batches_list.text_76e547a8fa54') }} {{ pagination.total }} {{ t('production.batches_list.text_6016bf63b0ff') }}</span>
          </div>
          <div class="header-right">
            <!-- 列宽已被记住时才出现 —— 拖乱了要有一条明确的退路 (fool-proof Rule 5) -->
            <UButton v-if="hasStoredColumnWidths" variant="link" color="primary" @click="resetColumnWidths">
              {{ t('production.batches_list.action_95902143ec62') }}
            </UButton>
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="openAiCreate">
              {{ t('production.batches_list.action_ec9ee97de0c8') }}
            </el-button>
            <el-button v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate">
              {{ t('production.batches_list.action_9fbdaca2499f') }}
            </el-button>
          </div>
        </div>
      </template>

      <!-- 搜索区域 -->
      <div class="search-bar">
        <el-input
          v-model="searchForm.batchNumber"
          :placeholder="t('production.batches_list.placeholder_cb5386f1a98a')"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
        <CompatSelect v-model="searchForm.status" :placeholder="t('production.batches_list.placeholder_0a379c1e7398')" clearable style="width: 150px">
          <!-- Bug fix (module-verify 2026-07-03, same root cause as scheduling/plans/create.vue):
               ProductionBatchStatus 枚举没有 PENDING, 传给后端会 400 (IllegalArgumentException).
               PLANNED 才是真实"待生产"状态值。 -->
          <CompatOption :label="t('production.batches_list.label_a8ea49009cbf')" value="PLANNED" />
          <CompatOption :label="t('production.batches_list.label_efc42eb363bc')" value="IN_PROGRESS" />
          <CompatOption :label="t('production.batches_list.label_eb0c326b60ae')" value="PAUSED" />
          <CompatOption :label="t('production.batches_list.label_f28461bb49c8')" value="COMPLETED" />
          <CompatOption :label="t('production.batches_list.label_a37778f17c5f')" value="CANCELLED" />
        </CompatSelect>
        <el-button type="primary" :icon="Search" @click="handleSearch">{{ t('production.batches_list.action_44ce7ae909bb') }}</el-button>
        <el-button :icon="Refresh" @click="handleReset">{{ t('production.batches_list.action_cb5d682bac3d') }}</el-button>
      </div>

      <!-- 数据表格 -->
      <el-table
        :data="displayTableData"
        v-loading="loading"
        row-key="id"
        :tree-props="{ children: 'children' }"
        default-expand-all
        :empty-text="tableEmptyText"
        stripe
        border
        table-layout="fixed"
        class="batch-data-table resizable-column-table"
        :row-class-name="batchRowClassName"
        style="width: 100%"
        @header-dragend="handleHeaderDragend"
      >
        <el-table-column
          prop="batchNumber"
          :label="t('production.batches_list.label_97035a3a38e5')"
          min-width="280"
          :width="columnWidth('batchNumber')"
          sortable
          :render-header="sortableHeader"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <div v-if="row.isSyntheticPlanGroup" class="plan-group-cell">
              <span class="plan-group-title">{{ t('production.batches_list.text_d8393f4287a5') }} {{ row.sourcePlanNumber || row.sourcePlanId || '-' }}</span>
              <UBadge variant="soft" size="sm" color="success">{{ t('production.batches_list.text_c8bd786bb842') }}</UBadge>
            </div>
            <span v-else>{{ row.batchNumber || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="productTypeName"
          :label="t('production.batches_list.label_0b3894ed2a38')"
          min-width="260"
          :width="columnWidth('productTypeName')"
          show-overflow-tooltip
          :filters="productFilters"
          :filter-method="(value: string, row: BatchTableRow) => productNameOf(row) === value"
        >
          <template #default="{ row }">
            <span v-if="row.isSyntheticPlanGroup">{{ row.children?.length || 0 }} {{ t('production.batches_list.text_f214aa5e42cc') }}</span>
            <span v-else>{{ row.productTypeName || row.productName || row.productTypeId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="plannedQuantity"
          :label="plannedColumnText"
          :width="columnWidth('plannedQuantity')"
          align="right"
          sortable
          :render-header="sortableHeader"
          :sort-method="(a: TableSortRow, b: TableSortRow) => compareNullableNumber(a, b, 'plannedQuantity')"
        >
          <template #default="{ row }">
            <!-- B4: 无计划数量时空着, 不显示 0; 混合列头时在理论产能的行上标明 -->
            <template v-if="plannedQuantityOrNull(row) != null">
              {{ plannedQuantityOrNull(row) }}<span
                v-if="plannedMixed && plannedBasisOf(row) === 'THEORETICAL_CAPACITY'"
                class="planned-basis-note"
                data-testid="planned-basis-note"
              >{{ t('production.batches_list.text_92f7d4dac8fc') }}</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column
          prop="actualQuantity"
          :label="t('production.batches_list.label_dbf1981b4fdb')"
          :width="columnWidth('actualQuantity')"
          align="right"
          sortable
          :render-header="sortableHeader"
          :sort-method="(a: TableSortRow, b: TableSortRow) => compareNullableNumber(a, b, 'actualQuantity')"
        />
        <el-table-column
          :label="t('production.batches_list.label_60ad8bace335')"
          column-key="sourceProcess"
          :width="columnWidth('sourceProcess')"
          align="center"
          :filters="sourceProcessFilters"
          :filter-method="(value: string, row: BatchTableRow) => sourceProcessOf(row) === value"
        >
          <template #default="{ row }">
            <span v-if="row.isSyntheticPlanGroup">{{ t('production.batches_list.text_d8393f4287a5') }}</span>
            <span v-else-if="row.batchType === 'CLERK_WIP' && row.sourceProcessOrder">
            <!--
              只显示真名, 不显示 archetype 码。
              sourceProcessCode 是内部码 (xiuyou/chaoshui/shuzhi/...), 只用来决定录入表格显示哪几列,
              而且是多对一的 —— 匹配不上关键词的工序一律归到 'chaoshui', 所以它多数时候
              并不是「焯水」。2026-08-02 六膳门 prod 实测这里显示「第2道 / chaoshui」,
              而该工厂 20 个工序名里没有一个叫这个。
              后端解析不出真名时返回 null, 这里就只显示「第N道」—— 不编名字。
            -->
              {{ `第${row.sourceProcessOrder}道` }}{{ row.sourceProcessName ? ` / ${row.sourceProcessName}` : '' }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="status"
          :label="t('production.batches_list.label_6320b4a8722a')"
          :width="columnWidth('status')"
          align="center"
          :filters="statusFilters"
          :filter-method="(value: string, row: BatchTableRow) => String(row.status || '').toUpperCase() === value"
        >
          <template #default="{ row }">
            <UBadge variant="soft" v-if="row.isSyntheticPlanGroup" color="info" size="sm">
              {{ t('production.batches_list.text_7ecf934c6ca3') }}
            </UBadge>
            <el-tag v-else :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="supervisorName"
          :label="t('production.batches_list.label_a525346277ed')"
          :width="columnWidth('supervisorName')"
          :filters="supervisorFilters"
          :filter-method="(value: string, row: BatchTableRow) => String(row.supervisorName || '-') === value"
        >
          <template #default="{ row }">{{ row.isSyntheticPlanGroup ? '-' : (row.supervisorName || '-') }}</template>
        </el-table-column>
        <el-table-column
          prop="createdAt"
          :label="t('production.batches_list.label_07ec86e0f1d4')"
          :width="columnWidth('createdAt')"
          sortable
          :render-header="sortableHeader"
          :sort-method="(a: TableSortRow, b: TableSortRow) => compareNullableDate(a, b, 'createdAt')"
          :formatter="formatDateTimeCell"
        />
        <el-table-column :label="t('production.batches_list.label_ed31fbb483ee')" width="220" fixed="right" align="center">
          <template #default="{ row }">
            <template v-if="row.isSyntheticPlanGroup">
              <UButton color="primary" variant="link" size="sm" @click="openSourcePlan(row)">{{ t('production.batches_list.action_aed5b2af529b') }}</UButton>
            </template>
            <template v-else>
            <!-- #751: 查看 vs 编辑 区分 — 详情页消费 ?mode=edit 决定 read-only / editable.
                 fool-proof-design Rule 5: 批次没有独立编辑表单, 之前点了才发现是死路
                 (click-then-toast) — 灰显 + title 说明原因, 而不是让用户点一次才知道。 -->
            <UButton color="primary" variant="link" size="sm" @click="router.push(`/production/batches/${row.id}`)">{{ t('production.batches_list.action_db8db0530432') }}</UButton>
            <UButton
              v-if="canWrite"
              color="warning"
              variant="link"
              size="sm"
              disabled
              :title="t('production.batches_list.title_cfeaa9ca6bad')"
            >{{ t('production.batches_list.action_051836569928') }}</UButton>
            <RowActionMenu
              :actions="rowActionsFor(row)"
              button-label="更多"
              @action-click="(id: string) => handleRowActionClick(id, row)"
              @ai-trigger="() => openAiForRow(row)"
            />
            </template>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrapper">
        <CompatPagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </UCard>

    <!-- 创建批次对话框 -->
    <CompatDialog v-model="createDialogVisible" :title="t('production.batches_list.title_38e87cfc936e')" width="500px" :close-on-click-modal="false" destroy-on-close>
      <CompatForm :model="createForm" label-width="100px">
        <CompatFormItem :label="t('production.batches_list.label_cb5386f1a98a')" required>
          <UInput v-model="createForm.batchNumber" :placeholder="t('production.batches_list.placeholder_2bbce91f25e7')" />
        </CompatFormItem>
        <CompatFormItem :label="t('production.batches_list.label_0b3894ed2a38')" required>
          <CompatSelect
            v-model="createForm.productTypeId"
            :placeholder="t('production.batches_list.placeholder_7ddb27f93df1')"
            filterable
            style="width: 100%"
            @change="handleCreateProductTypeChange"
          >
            <CompatOption
              v-for="pt in productTypes"
              :key="pt.id"
              :label="pt.name || pt.productName"
              :value="pt.id"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('production.batches_list.label_19a908ffd1ae')" required>
          <el-input-number v-model="createForm.plannedQuantity" :min="1" :precision="2" style="width: 200px" />
          <CompatSelect v-model="createForm.unit" style="width: 80px; margin-left: 8px">
            <CompatOption v-for="unit in createUnitOptions" :key="unit" :label="unit" :value="unit" />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('production.batches_list.label_daede9881787')">
          <el-input v-model="createForm.notes" type="textarea" :rows="3" :placeholder="t('production.batches_list.placeholder_d9cb05cfe837')" />
        </CompatFormItem>
        <!-- SP10: 试制批次 -->
        <CompatFormItem :label="t('production.batches_list.label_42f47e8c7f9e')">
          <UCheckbox v-model="createForm.isTrial"><template #label>{{ t('production.batches_list.text_61d6b5c58041') }}</template></UCheckbox>
        </CompatFormItem>
        <CompatFormItem v-if="createForm.isTrial" :label="t('production.batches_list.label_5c080d8e9622')">
          <el-input
            v-model="createForm.trialSampleId"
            :placeholder="t('production.batches_list.placeholder_d1d7094bdb10')"
            clearable
            style="width:280px"
          />
          <el-text type="info" style="margin-left:8px;font-size:12px">{{ t('production.batches_list.text_8f4d54a421e1') }}</el-text>
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createDialogVisible = false">{{ t('production.batches_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="creating" @click="submitCreate">{{ t('production.batches_list.action_cde2cd071d25') }}</UButton>
      </template>
    </CompatDialog>

    <!-- AI 智能创建生产批次抽屉 (Day 9, Issue #780.3) -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PROCESS_TASK_CONFIG"
      @fill-form="handleAiFill"
    />
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, var(--cs-border));
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, var(--cs-text));
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, var(--cs-muted));
    }
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

/*
  通栏表头 —— 与出成率总览 (YieldCardTable, PR #1991) 同一套观感。
  客户原话「每个表头套了独立边框盒子」: 盒子感来自 加深的表头底色 + 逐格加深的右边框
  + 每格一个自带外壳的控件, 三层叠加。三层都不再覆盖 (交回 el-table 默认),
  排序入口保留为无外壳的 .batch-sort-trigger。
*/
.batch-data-table {
  --el-table-border-color: #dce3ec;
  --el-table-row-hover-bg-color: #f6faff;

  :deep(.el-table__header th.el-table__cell) {
    height: 46px;
    padding: 0;
  }

  :deep(.el-table__body td.el-table__cell) {
    height: 54px;
    padding: 0;
  }

  :deep(.el-table__cell .cell) {
    padding: 0 12px;
    line-height: 20px;
  }

  :deep(.el-table__column-filter-trigger) {
    margin-left: 6px;
    color: #697586;
  }

  /* 按钮只提供点击热区与无障碍名字, 视觉上完全等同于普通表头文字 */
  :deep(.batch-sort-trigger) {
    display: inline-flex;
    align-items: center;
    justify-content: inherit;
    min-height: 32px;
    padding: 0;
    border: 0;
    background: transparent;
    color: inherit;
    font: inherit;
    font-weight: inherit;
    cursor: pointer;
    touch-action: manipulation;
  }

  :deep(.batch-sort-trigger:hover) {
    color: var(--el-color-primary, var(--cs-primary));
  }

  :deep(.batch-sort-trigger:focus-visible) {
    outline: 2px solid var(--el-color-primary, var(--cs-primary));
    outline-offset: 2px;
  }
}

.plan-group-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.plan-group-title {
  font-weight: 600;
  color: var(--text-color-primary, var(--cs-text));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.el-table__body tr.plan-group-row > td.el-table__cell .cell) {
  font-weight: 600;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, var(--cs-border));
  margin-top: 16px;
}
</style>
