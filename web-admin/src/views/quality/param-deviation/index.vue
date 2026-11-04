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
import { Refresh, Search } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { handleCatchError } from '@/utils/errorToast';
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
const router = useRouter();
const factoryId = computed(() => authStore.factoryId || '');

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
  productTypeId: string;
  batchNumber: string;
  status: ParamDeviationStatus | '';
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
    productTypeId: filters.productTypeId,
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
  filters.productTypeId = '';
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
    <div class="page-header">
      <h2 class="title">工艺参数偏离</h2>
      <p class="subtitle">
        报工时填的工艺参数，按报工当时的标准判成正常 / 偏高 / 偏低。这里按工序和参数汇总偏离情况，并可查看每一次偏离。
        报工次数只统计「填了该参数」的报工，偏离率 = 偏离次数 ÷ 填了该参数的次数。
      </p>
    </div>

    <el-form :inline="true" class="filters" @submit.prevent="search">
      <el-form-item label="报工日期">
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
      </el-form-item>
      <el-form-item label="工序">
        <el-input v-model="filters.processName" placeholder="工序名称（完整名称）" clearable style="width: 160px" data-testid="pd-process" />
      </el-form-item>
      <el-form-item label="产品">
        <el-select v-model="filters.productTypeId" placeholder="全部产品" clearable filterable style="width: 180px" data-testid="pd-product">
          <el-option v-for="p in productTypes" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="批次号">
        <el-input v-model="filters.batchNumber" placeholder="批次号（包含即可）" clearable style="width: 180px" data-testid="pd-batch" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" placeholder="偏高和偏低" clearable style="width: 130px" data-testid="pd-status">
          <el-option label="偏高" value="HIGH" />
          <el-option label="偏低" value="LOW" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" :disabled="!!rangeError" data-testid="pd-search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="reset">重置</el-button>
      </el-form-item>
    </el-form>
    <p v-if="rangeError" class="range-error" data-testid="pd-range-error">{{ rangeError }}</p>
    <p v-else-if="summary" class="hint" data-testid="pd-window">
      统计范围：{{ summary.window.startDate }} 至 {{ summary.window.endDate }}（报工日期）；
      共 {{ summary.totalChecked }} 条参数记录，其中 {{ summary.totalDeviations }} 条偏离。「状态」只筛下面的明细，上面的汇总始终包含全部。
    </p>

    <h3 class="section">按工序和参数汇总</h3>
    <el-table
      v-loading="summaryLoading"
      :data="summary?.rows ?? []"
      stripe
      :empty-text="summaryEmptyText"
      data-testid="pd-summary-table"
    >
      <el-table-column label="工序" min-width="130">
        <template #default="{ row }">{{ row.processName || '—' }}</template>
      </el-table-column>
      <el-table-column label="参数" min-width="150">
        <template #default="{ row }">{{ row.paramLabel || row.paramKey }}<span v-if="row.unit" class="unit">（{{ row.unit }}）</span></template>
      </el-table-column>
      <el-table-column prop="reportCount" label="填了该参数的次数" width="150" align="right" />
      <el-table-column prop="deviationCount" label="偏离次数" width="100" align="right">
        <template #default="{ row }"><span :class="{ bad: row.deviationCount > 0 }">{{ row.deviationCount }}</span></template>
      </el-table-column>
      <el-table-column prop="highCount" label="偏高" width="80" align="right" />
      <el-table-column prop="lowCount" label="偏低" width="80" align="right" />
      <el-table-column label="偏离率" width="100" align="right">
        <template #default="{ row }"><span :class="{ bad: row.deviationCount > 0 }">{{ row.deviationRate }}%</span></template>
      </el-table-column>
      <el-table-column label="操作" width="130" align="center">
        <template #default="{ row }">
          <el-button v-if="row.processName" link type="primary" @click="onlyThisProcess(row)">只看这道工序</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h3 class="section">偏离明细</h3>
    <el-table
      v-loading="detailLoading"
      :data="details"
      stripe
      :empty-text="detailEmptyText"
      data-testid="pd-detail-table"
    >
      <el-table-column label="报工日期" width="110">
        <template #default="{ row }">{{ row.reportDate || '—' }}</template>
      </el-table-column>
      <el-table-column label="录入时间" width="165">
        <template #default="{ row }">{{ timeText(row.reportedAt) }}</template>
      </el-table-column>
      <el-table-column label="批次号" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <el-button v-if="row.batchId != null" link type="primary" :data-testid="`pd-batch-${row.reportId}`" @click="openBatch(row)">
            {{ row.batchNumber || `批次 ${row.batchId}` }}
          </el-button>
          <span v-else>{{ row.batchNumber || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="产品" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.productName || '—' }}</template>
      </el-table-column>
      <el-table-column label="工序" min-width="110">
        <template #default="{ row }">{{ row.processName || '—' }}</template>
      </el-table-column>
      <el-table-column label="参数" min-width="110">
        <template #default="{ row }">{{ row.paramLabel || row.paramKey }}</template>
      </el-table-column>
      <el-table-column label="实测值" width="110" align="right">
        <template #default="{ row }"><span class="bad">{{ row.measuredValue ?? '—' }} {{ row.unit ?? '' }}</span></template>
      </el-table-column>
      <el-table-column label="标准值" width="100" align="right">
        <template #default="{ row }">{{ row.standardValue ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="合格范围" width="140">
        <template #default="{ row }">{{ rangeText(row) }}</template>
      </el-table-column>
      <el-table-column label="偏离" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status as ParamDeviationStatus]">{{ STATUS_LABEL[row.status as ParamDeviationStatus] ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="报工人" width="110">
        <template #default="{ row }">{{ row.reporterName || (row.reporterId != null ? `用户 ${row.reporterId}` : '—') }}</template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="detailTotal > PAGE_SIZE"
      class="pager"
      background
      layout="total, prev, pager, next"
      :total="detailTotal"
      :page-size="PAGE_SIZE"
      :current-page="detailPage"
      @current-change="onPageChange"
    />
  </div>
</template>

<style scoped>
.pd-page {
  padding: 20px;
}
.page-header {
  margin-bottom: 12px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 4px;
  color: #303133;
}
.page-header .subtitle {
  font-size: 13px;
  color: #606266;
  margin: 0;
  line-height: 1.6;
}
.filters {
  margin-bottom: 4px;
}
.hint {
  font-size: 12px;
  color: #909399;
  margin: 0 0 8px;
}
.range-error {
  font-size: 12px;
  color: #f56c6c;
  margin: 0 0 8px;
}
.section {
  font-size: 15px;
  font-weight: 600;
  margin: 18px 0 8px;
  color: #303133;
}
.unit {
  color: #909399;
}
.bad {
  color: #f56c6c;
  font-weight: 600;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
