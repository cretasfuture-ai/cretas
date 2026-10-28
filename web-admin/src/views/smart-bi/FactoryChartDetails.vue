<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { getFactoryChartDetails, getFactoryChartRecommendations, type FactoryChartDetailsData, type FactoryRecommendation } from '@/api/smartbi/factory-charts';
import { appendDetailPage, detailColumns, FACTORY_DETAIL_PAGE_SIZE } from './factoryChartDetails';
import { refreshedDetailCandidate, type FactoryDetailRefreshScope } from './factoryChartDetailRefresh';

const props = defineProps<{ modelValue: boolean; candidate: FactoryRecommendation | null; point?: { name: string; seriesName?: string; dataIndex?: number }; refreshScope?: FactoryDetailRefreshScope | null }>();
const emit = defineEmits<{ 'update:modelValue': [boolean] }>();
const loading = ref(false); const error = ref(''); const data = ref<FactoryChartDetailsData | null>(null); const rows = ref<Record<string, unknown>[]>([]); const page = ref(1); const requestVersion = ref(0); const mode = ref<'continuous' | 'paged'>('continuous');
const activeCandidate = ref<FactoryRecommendation | null>(null);
const columns = computed(() => detailColumns(data.value).map((column) => ({
  ...column,
  cellRenderer: ({ cellData }: { cellData: unknown }) => h('span', { class: 'el-table-v2__cell-text', title: display(cellData) }, display(cellData)),
})));
const viewport = ref({ width: 1024, height: 720 });
const width = computed(() => Math.max(280, Math.min(900, Math.floor(viewport.value.width * 0.9 - 24))));
const height = computed(() => Math.min(620, Math.max(280, viewport.value.height - 260)));
const point = ref<{ name: string; seriesName?: string; dataIndex?: number }>();
const hasMore = computed(() => Boolean(data.value?.hasMore));
const filterInputs = reactive<Record<string, string>>({});
const displayedRows = computed(() => rows.value.map((row, index) => ({ ...row, __detailRowKey: `${props.candidate?.detailContext?.id ?? 'context'}:${(page.value - 1) * FACTORY_DETAIL_PAGE_SIZE + index}` })));
const filterList = computed(() => data.value?.availableFilters ?? []);
function close() { emit('update:modelValue', false); }
function apiError(result: { message?: string }, fallback: string) { return result.message || fallback; }
async function load(targetPage = 1, reset = true) {
  const context = activeCandidate.value?.detailContext;
  if (!context) { error.value = props.candidate?.detailStatus?.message || '当前图表没有可用的原始明细'; return; }
  if (!Number.isFinite(Date.parse(context.expiresAt)) || Date.parse(context.expiresAt) <= Date.now()) { error.value = '原始明细已过期，请重新获取；数据变化时需要重新分析'; return; }
  const version = ++requestVersion.value; if (reset) { rows.value = []; data.value = null; }
  loading.value = true; error.value = '';
  try {
    const filters = Object.entries(filterInputs).filter(([, value]) => value.trim()).map(([field, value]) => ({ field, value: value.trim() }));
    const detailPoint = context.drilldown === false ? undefined : point.value;
    const result = await getFactoryChartDetails({ context_id: context.id, page: targetPage, page_size: FACTORY_DETAIL_PAGE_SIZE, filters, point: detailPoint });
    if (version !== requestVersion.value) return;
    if (!result.success || !result.data) { error.value = apiError(result, '原始明细加载失败'); return; }
    if (result.data.candidateId !== activeCandidate.value?.id || result.data.source !== activeCandidate.value?.source
      || (props.refreshScope && (result.data.factoryId !== props.refreshScope.request.factory_id || result.data.period !== props.refreshScope.periodLabel))) {
      error.value = '明细与原分析范围不一致，已拒绝加载'; return;
    }
    page.value = result.data.page; data.value = result.data; rows.value = reset ? result.data.rows : appendDetailPage(rows.value, result);
  } catch (err) { if (version === requestVersion.value) error.value = err instanceof Error && err.message ? err.message : '原始明细加载失败，请稍后重试'; }
  finally { if (version === requestVersion.value) loading.value = false; }
}
function loadMore() { if (!loading.value && hasMore.value) void load(page.value + 1, false); }
function loadPrevious() { if (!loading.value && page.value > 1) void load(page.value - 1, true); }
function loadNext() { if (!loading.value && hasMore.value) void load(page.value + 1, true); }
function applyFilters() { void load(1, true); }
function clearFilters() { Object.keys(filterInputs).forEach((key) => { delete filterInputs[key]; }); void load(1, true); }
function onEndReached() { loadMore(); }
async function refreshDetails() {
  const scope = props.refreshScope; const original = props.candidate;
  if (!scope || !original || loading.value) return;
  const version = ++requestVersion.value; const identity = scope.identity;
  loading.value = true; error.value = '';
  try {
    const result = await getFactoryChartRecommendations({ ...scope.request, use_ai: false, detail_candidate_ids: [original.id] });
    if (version !== requestVersion.value || !props.modelValue || identity !== props.refreshScope?.identity) return;
    if (!result.success || !result.data) throw new Error(result.message || '无法恢复明细，请稍后重试');
    activeCandidate.value = refreshedDetailCandidate(original, result.data, scope);
    loading.value = false;
    await load(1, true);
  } catch (err) { if (version === requestVersion.value) error.value = err instanceof Error ? err.message : '明细恢复失败'; }
  finally { if (version === requestVersion.value) loading.value = false; }
}
function openAt(candidate: FactoryRecommendation | null) {
  activeCandidate.value = candidate; point.value = props.point;
  if (candidate?.detailSnapshotKey && !candidate.detailContext && props.refreshScope && candidate.detailStatus?.state !== 'capacity') void refreshDetails();
  else void load(1, true);
}
watch([() => props.modelValue, () => props.candidate?.id, () => props.candidate?.detailContext?.id, () => props.point?.name, () => props.point?.dataIndex], ([open]) => { requestVersion.value += 1; loading.value = false; if (open) openAt(props.candidate); else { rows.value = []; data.value = null; } }, { immediate: true });
watch(() => props.refreshScope?.identity, () => { requestVersion.value += 1; rows.value = []; data.value = null; loading.value = false; });
function updateViewport() { viewport.value = { width: window.innerWidth, height: window.innerHeight }; }
onMounted(() => { updateViewport(); window.addEventListener('resize', updateViewport); });
onBeforeUnmount(() => { requestVersion.value += 1; window.removeEventListener('resize', updateViewport); });
function display(value: unknown) { return value === null || value === undefined ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value); }
</script>
<template>
  <el-drawer :model-value="modelValue" direction="rtl" size="min(960px, 96vw)" :title="candidate ? `原始明细 · ${candidate.title}` : '原始明细'" @close="close">
    <div class="details-scope" v-if="candidate">
      <div class="details-meta">{{ data?.source || candidate.source }} · {{ data?.period || '当前期间' }} · 共 {{ data?.total ?? 0 }} 行</div>
      <div v-if="point && activeCandidate?.detailContext?.drilldown !== false" class="point-context">当前点位：{{ point.name }}<span v-if="point.seriesName"> · {{ point.seriesName }}</span></div>
      <div v-else-if="point" class="point-context">显示当前图表的全部明细</div>
      <div class="details-controls"><el-radio-group v-model="mode" size="small"><el-radio-button label="continuous" value="continuous">连续加载</el-radio-button><el-radio-button label="paged" value="paged">分页</el-radio-button></el-radio-group><el-button v-if="data?.appliedFilters?.length" size="small" @click="clearFilters">清除筛选</el-button></div>
      <div v-if="filterList.length" class="details-filters"><span v-for="filter in filterList" :key="filter.field" class="filter-control"><label>{{ filter.label }}</label><el-input v-model="filterInputs[filter.field]" size="small" :placeholder="filter.kind" @keyup.enter="applyFilters" /></span><el-button size="small" type="primary" @click="applyFilters">应用筛选</el-button></div>
      <div v-if="data?.appliedFilters?.length" class="applied-filters">已应用：<el-tag v-for="filter in data.appliedFilters" :key="filter.field" size="small">{{ filter.field }}={{ filter.value }}</el-tag></div>
      <el-alert v-if="error" type="error" show-icon :closable="false">{{ error }}</el-alert>
      <el-button v-if="error && refreshScope" class="details-refresh" :loading="loading" @click="refreshDetails">重新获取原始明细</el-button>
      <el-empty v-else-if="!loading && !rows.length" description="没有可用原始行" />
      <div v-else class="details-table-wrap">
        <el-table-v2 v-if="columns.length" :columns="columns" :data="displayedRows" :width="width" :height="height" :row-height="44" :header-height="44" row-key="__detailRowKey" :on-end-reached="mode === 'continuous' ? onEndReached : undefined" />
      </div>
      <div class="details-actions"><template v-if="mode === 'paged'"><el-button :disabled="page <= 1 || loading" @click="loadPrevious">上一页</el-button><span>第 {{ page }} 页</span><el-button :disabled="!hasMore || loading" @click="loadNext">下一页</el-button></template><el-button v-else-if="hasMore" :loading="loading" @click="loadMore">加载更多</el-button><span v-if="loading">正在读取原始明细…</span><span v-else-if="!hasMore && rows.length">已加载全部 {{ rows.length }} 行</span></div>
    </div>
  </el-drawer>
</template>
<style scoped>
.details-meta { margin-bottom: 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.details-table-wrap { overflow-x: auto; min-height: 280px; }
.details-actions { display:flex; justify-content:center; align-items:center; gap:12px; padding-top:12px; color:var(--el-text-color-secondary); font-size:12px; }
.point-context,.applied-filters { margin: 8px 0; color: var(--el-text-color-secondary); font-size: 12px; }
.details-controls,.details-filters { display:flex; align-items:center; flex-wrap:wrap; gap:8px; margin: 10px 0; }
.filter-control { display:flex; align-items:center; gap:4px; width: min(240px, 100%); }
.filter-control label { white-space:nowrap; font-size:12px; color:var(--el-text-color-secondary); }
@media (max-width: 768px) { .details-table-wrap { margin: 0 -12px; } .details-meta { line-height:1.6; } }
</style>
