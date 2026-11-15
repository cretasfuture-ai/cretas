<script setup lang="ts">
/**
 * 工艺参数偏离 (质量 → 工艺参数偏离)。
 *
 * 报工时填的工艺参数（油温、炒制时间……）会按「当时的标准」判成 正常 / 偏高 / 偏低 并存下来；
 * 这里把这些判定按工序 + 参数汇总，再下钻到每一次偏离。App 报工和 web 工序表两条入口的数据都在。
 *
 *   - 汇总：每个「工序 + 参数」一行。报工次数 = 填了该参数的次数（不是该工序的全部报工次数）；
 *     偏离率 = 偏离次数 / 报工次数。汇总不受「状态」筛选影响（筛掉正常的，分母就没了）。
 *   - 明细：每一次偏离，批次号可点开批次详情；「状态」筛选只作用于这里。
 *
 * 空状态分两种，不能混：
 *   - 有参数记录但没偏离  → 「这段时间没有偏离」
 *   - 一条参数记录都没有  → 「工序没配参数标准 / 报工没填参数，所以没有数据」
 */
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import iconCcp from '@/assets/quality/icon-ccp.webp';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { handleCatchError } from '@/utils/errorToast';
import ProcessParamCasePanel from './ProcessParamCasePanel.vue';
import {
  getParamDeviationDetails,
  getParamDeviationSummary,
  type ParamDeviationDetail,
  type ParamDeviationStatus,
  type ParamDeviationSummary,
  type ParamDeviationSummaryRow,
} from '@/api/processParamDeviation';

interface ProductTypeOption {
  id: string;
  name: string;
}

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId || '');
const canWrite = computed(() => permissionStore.canWrite('quality') || permissionStore.canWrite('production'));

/** 本地日历日 YYYY-MM-DD（⛔ 不用 toISOString 截断：东八区凌晨会差一天）。 */
function localDate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}
function defaultRange(): [string, string] {
  const end = new Date();
  const start = new Date();
  start.setDate(end.getDate() - 29);
  return [localDate(start), localDate(end)];
}

const MAX_SPAN_DAYS = 92;
const PAGE_SIZE = 20;

const filters = reactive<{
  range: [string, string] | null;
  processName: string;
  productTypeId: string | undefined;
  batchNumber: string;
  status: ParamDeviationStatus | '' | undefined;
}>({ range: defaultRange(), processName: '', productTypeId: '', batchNumber: '', status: '' });

const productTypes = ref<ProductTypeOption[]>([]);
const summary = ref<ParamDeviationSummary | null>(null);
const summaryLoading = ref(false);
const details = ref<ParamDeviationDetail[]>([]);
const detailTotal = ref(0);
const detailPage = ref(1);
const detailLoading = ref(false);
const loadFailed = ref(false);
// 连点「查询」时只认最后一次请求的结果
let requestSeq = 0;

const STATUS_ITEMS = [{ label: '偏高', value: 'HIGH' }, { label: '偏低', value: 'LOW' }];
const R = { class: { th: 'text-right', td: 'text-right' } };
const SUMMARY_COLUMNS: TableColumn<ParamDeviationSummaryRow>[] = [
  { id: 'process', header: '工序' },
  { id: 'param', header: '参数' },
  { accessorKey: 'reportCount', header: '填了该参数的次数', meta: R },
  { id: 'deviationCount', header: '偏离次数', meta: R },
  { accessorKey: 'highCount', header: '偏高', meta: R },
  { accessorKey: 'lowCount', header: '偏低', meta: R },
  { id: 'rate', header: '偏离率', meta: R },
  { id: 'actions', header: '操作' },
];
const DETAIL_COLUMNS: TableColumn<ParamDeviationDetail>[] = [
  { id: 'reportDate', header: '报工日期' },
  { id: 'reportedAt', header: '录入时间' },
  { id: 'batch', header: '批次号', meta: { class: { td: 'max-w-56 truncate' } } },
  { id: 'product', header: '产品', meta: { class: { td: 'max-w-36 truncate' } } },
  { id: 'process', header: '工序', meta: { class: { td: 'max-w-40 truncate' } } },
  { id: 'param', header: '参数' },
  { id: 'measured', header: '实测值', meta: R },
  { id: 'standard', header: '标准值', meta: R },
  { id: 'range', header: '合格范围' },
  { id: 'status', header: '偏离' },
  { id: 'reporter', header: '报工人' },
];
const STATUS_LABEL: Record<ParamDeviationStatus, string> = { HIGH: '偏高', LOW: '偏低' };
const STATUS_TAG: Record<ParamDeviationStatus, 'danger' | 'warning'> = { HIGH: 'danger', LOW: 'warning' };

/** 选了超过后端上限的跨度时先在前端说清楚，不等后端拒绝。 */
const rangeError = computed(() => {
  if (!filters.range) return '';
  const [s, e] = filters.range;
  const days = Math.round((Date.parse(`${e}T00:00:00`) - Date.parse(`${s}T00:00:00`)) / 86400000) + 1;
  return days > MAX_SPAN_DAYS ? `时间范围最多 ${MAX_SPAN_DAYS} 天，当前选了 ${days} 天，请缩短` : '';
});

/** 空状态种类：null = 有偏离可看；none = 没有任何参数数据；clean = 有参数数据但都在范围内。 */
const emptyKind = computed<'none' | 'clean' | null>(() => {
  if (!summary.value || loadFailed.value) return null;
  if (summary.value.totalChecked === 0) return 'none';
  if (summary.value.totalDeviations === 0) return 'clean';
  return null;
});
const summaryEmptyText = computed(() => {
  if (loadFailed.value) return '加载失败，请重试';
  if (emptyKind.value === 'none') {
    return '这段时间没有任何工艺参数数据：工序没有配置参数的标准范围（上下限），或报工时没有填参数，所以没有可以统计的内容。';
  }
  return '没有符合条件的记录';
});
const detailEmptyText = computed(() => {
  if (loadFailed.value) return '加载失败，请重试';
  if (emptyKind.value === 'none') return '工序没配参数标准或报工没填参数，所以没有数据';
  if (emptyKind.value === 'clean') {
    return `这段时间没有偏离：${summary.value?.totalChecked ?? 0} 条参数记录都在标准范围内`;
  }
  // 汇总里有偏离，但当前「状态」筛选把明细筛空了
  return '当前状态下没有偏离记录';
});

function rangeText(r: ParamDeviationDetail): string {
  if (r.rangeMin == null && r.rangeMax == null) return '—';
  return `${r.rangeMin ?? ''} ~ ${r.rangeMax ?? ''} ${r.unit ?? ''}`.trim();
}
function timeText(t: string | null): string {
  return t ? t.replace('T', ' ').substring(0, 19) : '—';
}
function baseQuery() {
  return {
    startDate: filters.range?.[0],
    endDate: filters.range?.[1],
    processName: filters.processName.trim(),
    productTypeId: filters.productTypeId || '',
    batchNumber: filters.batchNumber.trim(),
  };
}

async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    const res = await get<ProductTypeOption[]>(`/${factoryId.value}/product-types/active`);
    if (res.success && Array.isArray(res.data)) productTypes.value = res.data;
  } catch {
    // 非阻塞：产品下拉加载失败不影响查询（可以不按产品筛）
  }
}

async function loadSummary(seq: number) {
  summaryLoading.value = true;
  try {
    const res = await getParamDeviationSummary(factoryId.value, baseQuery());
    if (seq !== requestSeq) return;
    if (!res.success || !res.data) throw new Error(res.message || '汇总加载失败');
    summary.value = res.data;
  } finally {
    if (seq === requestSeq) summaryLoading.value = false;
  }
}

async function loadDetails(seq: number) {
  detailLoading.value = true;
  try {
    const res = await getParamDeviationDetails(factoryId.value, {
      ...baseQuery(),
      status: filters.status || undefined,
      page: detailPage.value,
      size: PAGE_SIZE,
    });
    if (seq !== requestSeq) return;
    if (!res.success || !res.data) throw new Error(res.message || '明细加载失败');
    details.value = res.data.content ?? [];
    detailTotal.value = res.data.totalElements ?? 0;
  } finally {
    if (seq === requestSeq) detailLoading.value = false;
  }
}

async function reload() {
  if (!factoryId.value || rangeError.value) return;
  const seq = ++requestSeq;
  loadFailed.value = false;
  try {
    await Promise.all([loadSummary(seq), loadDetails(seq)]);
  } catch (e) {
    if (seq !== requestSeq) return;
    // 失败就是失败：清掉旧数据，不留着上一次的数字冒充这一次的结果
    loadFailed.value = true;
    summary.value = null;
    details.value = [];
    detailTotal.value = 0;
    handleCatchError(e, '工艺参数偏离加载失败');
  } finally {
    if (seq === requestSeq) {
      summaryLoading.value = false;
      detailLoading.value = false;
    }
  }
}

function search() {
  detailPage.value = 1;
  void reload();
}
function reset() {
  filters.range = defaultRange();
  filters.processName = '';
  filters.productTypeId = undefined;
  filters.batchNumber = '';
  filters.status = '';
  search();
}
function onlyThisProcess(row: ParamDeviationSummaryRow) {
  filters.processName = row.processName ?? '';
  search();
}
async function onPageChange(p: number) {
  detailPage.value = p;
  const seq = ++requestSeq;
  try {
    await loadDetails(seq);
  } catch (e) {
    if (seq !== requestSeq) return;
    handleCatchError(e, '明细加载失败');
  }
}
function openBatch(row: ParamDeviationDetail) {
  if (row.batchId != null) void router.push(`/production/batches/${row.batchId}`);
}

onMounted(() => {
  void loadProductTypes();
  void reload();
});
</script>

<template>
  <div class="pd-page">
    <div class="cs-page-head">
      <div class="head-main">
        <img :src="iconCcp" alt="" class="head-icon" width="56" height="56">
        <div>
          <h2>工艺参数偏离</h2>
          <p>
            报工时填的工艺参数，按报工当时的标准判成正常 / 偏高 / 偏低。这里按工序和参数汇总偏离情况，并可查看每一次偏离。
            报工次数只统计「填了该参数」的报工，偏离率 = 偏离次数 ÷ 填了该参数的次数。
          </p>
        </div>
      </div>
    </div>

    <section class="cs-card">
      <form class="filters" @submit.prevent="search">
        <label class="field">
          <span>报工日期</span>
          <el-date-picker
            v-model="filters.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            data-testid="pd-range"
          />
        </label>
        <label class="field">
          <span>工序</span>
          <UInput v-model="filters.processName" placeholder="工序名称（完整名称）" class="w-44" data-testid="pd-process" />
        </label>
        <label class="field">
          <span>产品</span>
          <USelectMenu v-model="filters.productTypeId" :items="productTypes" value-key="id" label-key="name" placeholder="全部产品" clear class="w-48" data-testid="pd-product" />
        </label>
        <label class="field">
          <span>批次号</span>
          <UInput v-model="filters.batchNumber" placeholder="批次号（包含即可）" class="w-48" data-testid="pd-batch" />
        </label>
        <label class="field">
          <span>状态</span>
          <USelectMenu v-model="filters.status" :items="STATUS_ITEMS" value-key="value" placeholder="偏高和偏低" clear :search-input="false" class="w-36" data-testid="pd-status" />
        </label>
        <div class="field actions">
          <UButton icon="i-lucide-search" :disabled="!!rangeError" data-testid="pd-search" @click="search">查询</UButton>
          <UButton color="neutral" variant="outline" icon="i-lucide-rotate-ccw" @click="reset">重置</UButton>
        </div>
      </form>
      <p v-if="rangeError" class="range-error" data-testid="pd-range-error">{{ rangeError }}</p>
      <p v-else-if="summary" class="hint" data-testid="pd-window">
        统计范围：{{ summary.window.startDate }} 至 {{ summary.window.endDate }}（报工日期）；
        共 {{ summary.totalChecked }} 条参数记录，其中 {{ summary.totalDeviations }} 条偏离。「状态」只筛下面的明细，上面的汇总始终包含全部。
      </p>
    </section>

    <section class="cs-card">
      <header class="cs-card-head"><h3>按工序和参数汇总</h3></header>
      <UTable :data="summary?.rows ?? []" :columns="SUMMARY_COLUMNS" :loading="summaryLoading" class="cs-table" data-testid="pd-summary-table">
        <template #empty>{{ summaryEmptyText }}</template>
        <template #process-cell="{ row }">{{ row.original.processName || '—' }}</template>
        <template #param-cell="{ row }">{{ row.original.paramLabel || row.original.paramKey }}<span v-if="row.original.unit" class="unit">（{{ row.original.unit }}）</span></template>
        <template #deviationCount-cell="{ row }"><span :class="{ bad: row.original.deviationCount > 0 }">{{ row.original.deviationCount }}</span></template>
        <template #rate-cell="{ row }"><span :class="{ bad: row.original.deviationCount > 0 }">{{ row.original.deviationRate }}%</span></template>
        <template #actions-cell="{ row }">
          <UButton v-if="row.original.processName" color="primary" variant="link" size="xs" @click="onlyThisProcess(row.original)">只看这道工序</UButton>
        </template>
      </UTable>
    </section>

    <section class="cs-card">
      <header class="cs-card-head"><h3>偏离明细</h3></header>
      <UTable :data="details" :columns="DETAIL_COLUMNS" :loading="detailLoading" class="cs-table" data-testid="pd-detail-table">
        <template #empty>{{ detailEmptyText }}</template>
        <template #reportDate-cell="{ row }">{{ row.original.reportDate || '—' }}</template>
        <template #reportedAt-cell="{ row }">{{ timeText(row.original.reportedAt) }}</template>
        <template #batch-cell="{ row }">
          <UButton v-if="row.original.batchId != null" color="primary" variant="link" size="xs" :data-testid="`pd-batch-${row.original.reportId}`" @click="openBatch(row.original)">
            {{ row.original.batchNumber || `批次 ${row.original.batchId}` }}
          </UButton>
          <span v-else>{{ row.original.batchNumber || '—' }}</span>
        </template>
        <template #product-cell="{ row }">{{ row.original.productName || '—' }}</template>
        <template #process-cell="{ row }">{{ row.original.processName || '—' }}</template>
        <template #param-cell="{ row }">{{ row.original.paramLabel || row.original.paramKey }}</template>
        <template #measured-cell="{ row }"><span class="bad num">{{ row.original.measuredValue ?? '—' }} {{ row.original.unit ?? '' }}</span></template>
        <template #standard-cell="{ row }"><span class="num">{{ row.original.standardValue ?? '—' }}</span></template>
        <template #range-cell="{ row }">{{ rangeText(row.original) }}</template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(STATUS_TAG[row.original.status as ParamDeviationStatus])" variant="soft">{{ STATUS_LABEL[row.original.status as ParamDeviationStatus] ?? row.original.status }}</UBadge>
        </template>
        <template #reporter-cell="{ row }">{{ row.original.reporterName || (row.original.reporterId != null ? `用户 ${row.original.reporterId}` : '—') }}</template>
      </UTable>
      <div v-if="detailTotal > PAGE_SIZE" class="pager">
        <UPagination :page="detailPage" :items-per-page="PAGE_SIZE" :total="detailTotal" show-edges :sibling-count="1" @update:page="onPageChange" />
      </div>
    </section>

    <ProcessParamCasePanel :factory-id="factoryId" :can-write="canWrite" />
  </div>
</template>

<style scoped>
.pd-page { padding: var(--cs-space-4); }
.head-main { flex: 1; min-width: 0; display: flex; gap: var(--cs-space-3); align-items: center; }
.head-icon { flex: none; border-radius: var(--cs-radius); }
.filters { display: flex; flex-wrap: wrap; gap: var(--cs-space-3); align-items: flex-end; padding: var(--cs-space-4) var(--cs-space-5) var(--cs-space-2); }
.field { display: flex; flex-direction: column; gap: 4px; font-size: var(--cs-font-small); color: var(--cs-muted); }
.field.actions { flex-direction: row; gap: var(--cs-space-2); }
.hint { font-size: var(--cs-font-small); color: var(--cs-muted); margin: 0; padding: 0 var(--cs-space-5) var(--cs-space-3); }
.range-error { font-size: var(--cs-font-small); color: var(--cs-danger); margin: 0; padding: 0 var(--cs-space-5) var(--cs-space-3); }
.cs-card-head h3 { margin: 0; font-size: var(--cs-font); font-weight: 600; color: var(--cs-text); }
.unit { color: var(--cs-muted); }
.bad { color: var(--cs-danger); font-weight: 600; }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-4) var(--cs-space-5); }
</style>
