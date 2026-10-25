<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { Refresh, TrendCharts } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import PeriodSelector, { type PeriodSelection } from '@/components/smartbi/PeriodSelector.vue';
import { getUploadHistory, type UploadHistoryItem } from '@/api/smartbi/upload';
import {
  getFactoryChartRecommendations,
  getFactoryChartSelection,
  saveFactoryChartSelection as saveFactoryChartSelectionRemote,
  type FactoryChartSource,
  type FactoryChartExportFormat,
  type FactoryRecommendation,
  type FactoryRecommendationsData,
} from '@/api/smartbi/factory-charts';
import { buildFactoryChartExportPayload, captureFactoryChartImages, downloadFactoryChartExport } from './factoryChartExport';
import FactoryChartCanvas from './FactoryChartCanvas.vue';
import {
  factoryChartRequestFromSelection,
  loadFactoryChartSelectionIntent,
  selectionForEligibleIds,
  saveFactoryChartSelection,
} from './factoryChartState';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

function queryUploadId(): number | null {
  const value = route.query.upload_id;
  const raw = Array.isArray(value) ? value[0] : value;
  const id = Number(raw);
  return Number.isInteger(id) && id > 0 ? id : null;
}

const source = ref<FactoryChartSource>(queryUploadId() ? 'upload' : 'business');
const uploadId = ref<number | null>(queryUploadId());
const dataSources = ref<UploadHistoryItem[]>([]);
const periodSelection = ref<PeriodSelection>({
  type: 'year',
  year: new Date().getFullYear(),
  value: String(new Date().getFullYear()),
  compareEnabled: false,
  allHistory: true,
});
const includeOptional = ref(true);
const useAi = ref(true);
const selectedIds = ref<string[]>([]);
const domainFilter = ref('all');
const exportLoading = ref<FactoryChartExportFormat | ''>('');
const exportError = ref('');
const selectionSyncState = ref<'loading' | 'local' | 'synced' | 'fallback' | 'saving' | 'error'>('loading');
const loading = ref(false);
const loadingSources = ref(false);
const error = ref('');
const sourceLoadError = ref('');
const data = ref<FactoryRecommendationsData | null>(null);
let initialLoadDone = false;
let requestVersion = 0;
let selectionReady = false;
let suppressSelectionWatch = false;
let sourceLoadVersion = 0;
let initialRequestScheduled = false;
let selectionScopeKey = '';
const selectionIntentByScope = new Map<string, string[]>();
let selectionPreferenceRequestVersion = 0;
let selectionDirtyVersion = 0;
let selectionSaveQueue: Promise<void> = Promise.resolve();

const recommendations = computed(() => data.value?.recommendations ?? []);
const displayedRecommendations = computed(() => recommendations.value.filter(item => selectedIds.value.includes(item.id)));
const domains = computed(() => [...new Set(recommendations.value.map(item => item.domain).filter(Boolean))]);
const filteredDisplayedRecommendations = computed(() => displayedRecommendations.value.filter(item => domainFilter.value === 'all' || item.domain === domainFilter.value));
const hasNoSelectedCharts = computed(() => recommendations.value.length > 0 && displayedRecommendations.value.length === 0);
const sourceLabel = computed(() => source.value === 'business' ? '业务系统' : '上传报表');
const uploadLabel = (item: UploadHistoryItem) => `${item.originalFileName || item.fileName}${item.sheetName ? ` · ${item.sheetName}` : ''}`;
const chartTypeNames: Record<string, string> = {
  line: '折线', bar: '柱形', bar_horizontal: '横条', area: '面积', pie: '饼图', donut: '环形',
  nested_donut: '双环', scatter: '散点', waterfall: '瀑布', sankey: '流向', bullet: '子弹图',
  dual_axis: '双轴', budget_comparison: '计划对比', combination: '组合', funnel: '漏斗', gantt: '时间安排',
  gauge: '仪表', pareto: '帕累托', radar: '雷达', slope: '两期变化', sunburst: '旭日', treemap: '矩形树图',
  boxplot: '箱线', heatmap: '热力', matrix_heatmap: '矩阵热力', correlation_matrix: '相关矩阵', parallel: '平行坐标',
};
const chartTypeLabel = (item: FactoryRecommendation) => chartTypeNames[item.chart.chartType] || '财务图表';

function errorText(value: unknown, fallback: string): string {
  const serverMessage = (value as { serverMessage?: unknown } | null)?.serverMessage;
  return typeof serverMessage === 'string' && serverMessage ? serverMessage : value instanceof Error ? value.message : fallback;
}

async function loadDataSources() {
  if (!factoryId.value) return;
  const version = ++sourceLoadVersion;
  const loadingFactoryId = factoryId.value;
  loadingSources.value = true;
  sourceLoadError.value = '';
  try {
    const result = await getUploadHistory();
    if (version !== sourceLoadVersion || loadingFactoryId !== factoryId.value) return;
    if (!result.success) {
      dataSources.value = [];
      sourceLoadError.value = '上传报表列表加载失败，请重试';
      return;
    }
    dataSources.value = result.data;
    if (source.value === 'upload' && !uploadId.value) uploadId.value = dataSources.value[0]?.id ?? null;
  } catch {
    if (version === sourceLoadVersion && loadingFactoryId === factoryId.value) {
      dataSources.value = [];
      sourceLoadError.value = '上传报表列表加载失败，请重试';
    }
  }
  finally {
    if (version === sourceLoadVersion && loadingFactoryId === factoryId.value) loadingSources.value = false;
  }
}

function resetVisibleData() {
  data.value = null;
  selectionReady = false;
  error.value = '';
  exportError.value = '';
}

function applySelectionIntent(key: string, eligibleIds: string[]) {
  const intent = selectionIntentByScope.get(key) ?? null;
  const defaults = data.value?.defaultSelection ?? eligibleIds.slice(0, 6);
  const visible = selectionForEligibleIds(eligibleIds, intent);
  selectedIds.value = intent === null || (intent.length > 0 && visible.length === 0)
    ? selectionForEligibleIds(eligibleIds, defaults) : visible;
}

function loadSelectionPreference(eligibleIds: string[], _capturedRequestVersion: number) {
  if (!factoryId.value) return;
  const key = `${factoryId.value}:${source.value}:${uploadId.value ?? 'business'}`;
  if (selectionScopeKey !== key) {
    selectionScopeKey = key;
    selectionSyncState.value = 'loading';
    const requestId = ++selectionPreferenceRequestVersion;
    const dirtyAtRequest = selectionDirtyVersion;
    const localIntent = selectionIntentByScope.get(key) ?? loadFactoryChartSelectionIntent(factoryId.value, source.value);
    if (localIntent !== null) selectionIntentByScope.set(key, [...localIntent]);
    applySelectionIntent(key, eligibleIds);
    void getFactoryChartSelection(factoryId.value, source.value).then(result => {
      if (requestId !== selectionPreferenceRequestVersion || selectionScopeKey !== key || dirtyAtRequest !== selectionDirtyVersion) return;
      if (result.success && result.data.factoryId === factoryId.value && result.data.source === source.value) {
        if (result.data.selectedIds !== null) selectionIntentByScope.set(key, [...result.data.selectedIds]);
        applySelectionIntent(key, recommendations.value.map(item => item.id));
        selectionSyncState.value = result.data.selectedIds === null ? 'local' : 'synced';
        return;
      }
      throw new Error(result.message || '偏好同步失败');
    }).catch(() => {
      if (requestId !== selectionPreferenceRequestVersion || selectionScopeKey !== key || dirtyAtRequest !== selectionDirtyVersion) return;
      selectionSyncState.value = 'fallback';
    });
    return;
  }
  // Derive the current view from the retained intent; never persist filtering.
  applySelectionIntent(key, eligibleIds);
}

async function loadRecommendations() {
  requestVersion += 1;
  const version = requestVersion;
  const capturedRequestVersion = version;
  resetVisibleData();
  loading.value = false;
  if (!factoryId.value) {
    error.value = '当前账号没有所属工厂，无法加载工厂图表';
    return;
  }
  const query = factoryChartRequestFromSelection({
    factoryId: factoryId.value,
    source: source.value,
    uploadId: uploadId.value,
    period: periodSelection.value,
    includeOptional: includeOptional.value,
    useAi: useAi.value,
  });
  if ('error' in query) {
    error.value = query.error;
    return;
  }
  loading.value = true;
  try {
    const result = await getFactoryChartRecommendations(query);
    if (version !== requestVersion) return;
    if (!result.success || !result.data) {
      error.value = result.message || '工厂图表推荐加载失败';
      return;
    }
    if (result.data.factoryId !== query.factory_id || result.data.source !== query.source) {
      error.value = '返回的数据源与当前工厂不一致，请刷新重试';
      return;
    }
    data.value = result.data;
    if (result.data.recommendations.length === 0) selectionScopeKey = '';
    if (result.data.recommendations.length > 0) {
      loadSelectionPreference(result.data.recommendations.map(item => item.id), capturedRequestVersion);
    }
    selectionReady = result.data.recommendations.length > 0;
  } catch (err) {
    if (version === requestVersion) error.value = errorText(err, '工厂图表推荐加载失败');
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}

function refresh() { void loadRecommendations(); }

function onSourceChange(next: FactoryChartSource) {
  source.value = next;
  selectionScopeKey = '';
  selectedIds.value = [];
  if (next === 'business') uploadId.value = null;
  else if (!uploadId.value) uploadId.value = dataSources.value[0]?.id ?? null;
}

function onFactoryChanged() {
  requestVersion += 1;
  sourceLoadVersion += 1;
  dataSources.value = [];
  loading.value = false;
  loadingSources.value = false;
  suppressSelectionWatch = true;
  uploadId.value = null;
  selectedIds.value = [];
  resetVisibleData();
  selectionScopeKey = '';
  suppressSelectionWatch = false;
  if (initialLoadDone) {
    void loadDataSources();
    if (source.value === 'business') void loadRecommendations();
  }
}

watch(factoryId, onFactoryChanged);
watch([source, uploadId, includeOptional, useAi], () => {
  if (initialLoadDone && !suppressSelectionWatch) {
    const query = { ...route.query };
    if (source.value === 'upload' && uploadId.value) query.upload_id = String(uploadId.value);
    else delete query.upload_id;
    if (query.upload_id !== route.query.upload_id) void router.replace({ query });
    void loadRecommendations();
  }
}, { deep: true });
async function persistUserSelection() {
  if (!selectionReady || !factoryId.value) return;
  const saveFactoryId = factoryId.value;
  const saveSource = source.value;
  const saveScopeKey = `${saveFactoryId}:${saveSource}:${uploadId.value ?? 'business'}`;
  const saveIds = [...selectedIds.value];
  selectionIntentByScope.set(saveScopeKey, saveIds);
  saveFactoryChartSelection(saveFactoryId, saveSource, saveIds);
  selectionSyncState.value = 'saving';
  const dirtyVersion = ++selectionDirtyVersion;
  const key = saveScopeKey;
  selectionSaveQueue = selectionSaveQueue.then(async () => {
    try {
      const result = await saveFactoryChartSelectionRemote(saveFactoryId, saveSource, saveIds);
      if (dirtyVersion === selectionDirtyVersion && key === `${factoryId.value}:${source.value}:${uploadId.value ?? 'business'}`) selectionSyncState.value = result.success ? 'synced' : 'error';
    } catch {
      if (dirtyVersion === selectionDirtyVersion && key === `${factoryId.value}:${source.value}:${uploadId.value ?? 'business'}`) selectionSyncState.value = 'error';
    }
  });
  await selectionSaveQueue;
}

async function exportSelected(format: FactoryChartExportFormat) {
  if (!data.value || !filteredDisplayedRecommendations.value.length) return;
  exportLoading.value = format;
  exportError.value = '';
  try {
    const items = filteredDisplayedRecommendations.value;
    const periodQuery = factoryChartRequestFromSelection({
      factoryId: factoryId.value, source: source.value, uploadId: uploadId.value,
      period: periodSelection.value, includeOptional: includeOptional.value, useAi: useAi.value,
    });
    const images = await captureFactoryChartImages(items);
    const payload = buildFactoryChartExportPayload(items, images, {
      factoryId: factoryId.value,
      source: source.value,
      sourceLabel: data.value.sourceLabel || sourceLabel.value,
      periodLabel: data.value.period?.label || '当前期间',
      selectedIds: selectedIds.value,
      ...( 'error' in periodQuery ? {} : {
        year: periodQuery.year,
        periodType: periodQuery.period_type,
        startMonth: periodQuery.start_month,
        endMonth: periodQuery.end_month,
      }),
    });
    const ext = format === 'excel' ? 'xlsx' : format === 'ppt' ? 'pptx' : 'pdf';
    await downloadFactoryChartExport(format, payload, `工厂图表推荐_${data.value.period?.label || '当前期间'}.${ext}`);
  } catch (err) {
    exportError.value = errorText(err, '导出失败，请稍后重试');
  } finally {
    exportLoading.value = '';
  }
}

function onPeriodChange(value: PeriodSelection) {
  periodSelection.value = value;
  if (initialLoadDone && !initialRequestScheduled) void loadRecommendations();
}

onMounted(() => {
  initialLoadDone = true;
  if (!factoryId.value) {
    error.value = '当前账号没有所属工厂，无法加载工厂图表';
    return;
  }
  void loadDataSources();
  initialRequestScheduled = true;
  void nextTick(() => {
    initialRequestScheduled = false;
    void loadRecommendations();
  });
});

function findingsFor(item: FactoryRecommendation) {
  return [
    { key: 'good', title: '做得好的', items: item.findings?.good ?? [], type: 'success' },
    { key: 'attention', title: '需要关注', items: item.findings?.attention ?? [], type: 'warning' },
    { key: 'suggestions', title: '建议', items: item.findings?.suggestions ?? [], type: 'info' },
  ].filter(section => section.items.length > 0);
}
</script>

<template>
  <div class="factory-chart-recommendations">
    <div class="page-header">
      <div>
        <h2>工厂图表推荐</h2>
        <p class="page-subtitle">按本厂当前数据推荐图表，缺少的数据会明确列出</p>
      </div>
      <el-button type="primary" :icon="Refresh" :loading="loading" :disabled="!factoryId" data-testid="factory-chart-refresh" @click="refresh">刷新</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <div class="filter-item">
          <span class="filter-label">数据源</span>
          <el-select :model-value="source" data-testid="factory-chart-source" style="width: 150px" @update:model-value="onSourceChange">
            <el-option label="业务系统" value="business" />
            <el-option label="上传报表" value="upload" />
          </el-select>
        </div>
        <div v-if="source === 'upload'" class="filter-item upload-source">
          <span class="filter-label">报表</span>
          <el-select v-model="uploadId" :loading="loadingSources" placeholder="选择已上传报表" data-testid="factory-chart-upload" style="min-width: 240px">
            <el-option v-for="item in dataSources" :key="item.id" :label="uploadLabel(item)" :value="item.id">
              <span>{{ uploadLabel(item) }}</span><small>{{ item.rowCount }} 行</small>
            </el-option>
          </el-select>
        </div>
        <el-checkbox v-model="includeOptional" data-testid="factory-chart-optional">包含可选图表</el-checkbox>
        <el-checkbox v-model="useAi" data-testid="factory-chart-ai">请求 AI 排序（仅调整顺序）</el-checkbox>
      </div>
      <div class="period-row">
        <span class="filter-label">期间</span>
        <PeriodSelector v-model="periodSelection" :show-all-history="true" :show-compare="false" data-testid="factory-chart-period" @change="onPeriodChange" />
      </div>
      <div class="scope-note">此浏览器记住本厂的显示选择（按工厂和数据源分别保存）。</div>
    </el-card>

    <el-alert v-if="sourceLoadError" type="warning" show-icon :closable="false" class="state-alert" data-testid="factory-chart-source-load-error">
      <template #title>{{ sourceLoadError }}</template>
      <el-button size="small" type="primary" @click="loadDataSources">重试</el-button>
    </el-alert>

    <el-alert v-if="error" type="error" show-icon :closable="false" class="state-alert" data-testid="factory-chart-error">
      <template #title>{{ error }}</template>
      <el-button size="small" type="primary" @click="refresh">重试</el-button>
    </el-alert>

    <el-alert v-if="data?.sourceErrors?.length" type="warning" show-icon :closable="false" class="state-alert" data-testid="factory-chart-source-errors">
      <template #title>{{ sourceLabel }}部分数据源读取失败</template>
      <ul class="source-errors"><li v-for="item in data.sourceErrors" :key="`${item.source}-${item.message}`">{{ item.message }}</li></ul>
      <el-button size="small" type="primary" @click="refresh">重试</el-button>
    </el-alert>

    <div v-if="loading" class="loading-state" data-testid="factory-chart-loading">
      <el-skeleton :rows="4" animated /><el-skeleton :rows="4" animated /><el-skeleton :rows="4" animated />
    </div>

    <template v-else-if="data">
      <div class="result-meta">
        <span>{{ data.sourceLabel || sourceLabel }} · {{ data.period?.label || '当前期间' }}</span>
        <el-tag :type="data.ranking.mode === 'ai' ? 'success' : 'info'" size="small" data-testid="factory-chart-ranking">
          {{ data.ranking.mode === 'ai' ? 'AI 已排序' : '规则排序（未经AI排序）' }}
        </el-tag>
        <span class="ranking-reason">{{ data.ranking.reason }}</span>
      </div>

      <div v-if="recommendations.length" class="export-toolbar">
        <el-select v-model="domainFilter" size="small" style="width: 160px" data-testid="factory-chart-domain-filter">
          <el-option label="全部领域" value="all" />
          <el-option v-for="domain in domains" :key="domain" :label="domain" :value="domain" />
        </el-select>
        <el-button size="small" :loading="exportLoading === 'excel'" :disabled="!filteredDisplayedRecommendations.length" @click="exportSelected('excel')">导出当前显示图表 Excel</el-button>
        <el-button size="small" :loading="exportLoading === 'pdf'" :disabled="!filteredDisplayedRecommendations.length" @click="exportSelected('pdf')">导出当前显示图表 PDF</el-button>
        <el-button size="small" :loading="exportLoading === 'ppt'" :disabled="!filteredDisplayedRecommendations.length" @click="exportSelected('ppt')">导出当前显示图表 PPT</el-button>
        <span class="sync-state" :class="`sync-state--${selectionSyncState}`">{{ selectionSyncState === 'synced' ? '选择已同步' : selectionSyncState === 'local' ? '尚未保存选择' : selectionSyncState === 'loading' ? '正在读取已保存选择…' : selectionSyncState === 'saving' ? '正在同步选择…' : '选择同步失败，已使用本机缓存' }}</span>
        <el-button v-if="selectionSyncState === 'local' || selectionSyncState === 'error' || selectionSyncState === 'fallback'" size="small" :disabled="!selectionReady" data-testid="factory-chart-save-selection" @click="persistUserSelection">保存当前选择</el-button>
      </div>
      <el-alert v-if="exportError" type="error" show-icon :closable="false" class="state-alert" data-testid="factory-chart-export-error">{{ exportError }}</el-alert>

      <div v-if="recommendations.length" class="selection-panel">
        <div class="selection-title">显示图表 <span>（{{ displayedRecommendations.length }}/{{ recommendations.length }}）</span></div>
        <el-checkbox-group v-model="selectedIds" data-testid="factory-chart-selection" @change="persistUserSelection">
          <el-checkbox v-for="item in recommendations" :key="item.id" :value="item.id">{{ item.title }} · {{ chartTypeLabel(item) }}</el-checkbox>
        </el-checkbox-group>
      </div>
      <el-empty v-if="hasNoSelectedCharts" description="已隐藏全部推荐图表，请勾选要显示的图表" data-testid="factory-chart-no-selection" />

      <div v-if="filteredDisplayedRecommendations.length" class="recommendation-grid">
        <el-card v-for="item in filteredDisplayedRecommendations" :key="item.id" shadow="never" class="recommendation-card" :data-testid="`factory-chart-card-${item.id}`">
          <template #header>
            <div class="card-header"><div><h3>{{ item.title }}</h3><span class="metric-label">{{ chartTypeLabel(item) }} · {{ item.metric }} · {{ item.unit }}</span></div><el-tag :type="item.priority === 'required' ? 'primary' : 'warning'" size="small">{{ item.priority === 'required' ? '必需' : '可选' }}</el-tag></div>
          </template>
          <div class="rule-reason"><strong>规则依据</strong><span>{{ item.reason }}</span></div>
          <p class="scope-note">来源：{{ item.source }}</p>
          <FactoryChartCanvas :option="item.chart.echartsOption" />
          <table class="source-details" aria-label="图表来源详情"><caption>来源详情</caption><tbody><tr><th>工厂</th><td>{{ factoryId }}</td><th>数据源</th><td>{{ item.source || data.sourceLabel }}</td></tr><tr><th>期间</th><td>{{ data.period?.label || '当前期间' }}</td><th>单位</th><td>{{ item.unit || '—' }}</td></tr></tbody></table>
          <div class="kpi-grid"><div v-for="kpi in item.kpis" :key="`${item.id}-${kpi.label}`" class="kpi-item"><span>{{ kpi.label }}</span><strong>{{ kpi.value ?? '—' }} <small>{{ kpi.unit }}</small></strong></div></div>
          <div v-for="section in findingsFor(item)" :key="section.key" class="finding-section"><div class="finding-title" :class="`finding-title--${section.type}`">{{ section.title }}</div><ul><li v-for="text in section.items" :key="text">{{ text }}</li></ul></div>
          <div v-if="item.aiReason" class="ai-reason"><el-tag type="success" size="small">AI 补充</el-tag><span>{{ item.aiReason }}</span></div>
        </el-card>
      </div>

      <el-card v-if="data.unavailable?.length" shadow="never" class="unavailable-card" data-testid="factory-chart-unavailable">
        <template #header><div class="unavailable-title"><el-icon><TrendCharts /></el-icon>当前数据暂不具备的图表</div></template>
        <div v-for="item in data.unavailable" :key="item.id" class="unavailable-item"><strong>{{ item.title }}</strong><span>缺少：{{ item.missing.join('、') }}</span></div>
      </el-card>
      <el-empty v-if="!recommendations.length && !data.unavailable?.length && !data.sourceErrors?.length" description="当前期间没有合资格的工厂图表" data-testid="factory-chart-empty" />
    </template>
  </div>
</template>

<style scoped lang="scss">
.factory-chart-recommendations { padding: 8px 0 24px; }
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
h2 { margin: 0; color: var(--el-text-color-primary, #1a2332); font-size: 20px; }
.page-subtitle { margin: 6px 0 0; color: var(--el-text-color-secondary, #7a8599); font-size: 13px; }
.filter-card, .recommendation-card, .unavailable-card { border-radius: 10px; }
.filter-row { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 20px; }
.filter-item, .period-row { display: flex; align-items: center; gap: 8px; }
.filter-label { color: var(--el-text-color-secondary, #7a8599); font-size: 13px; white-space: nowrap; }
.upload-source small { margin-left: auto; color: var(--el-text-color-secondary, #909399); }
.period-row { align-items: flex-start; margin-top: 14px; }
.period-row :deep(.period-selector) { flex: 1; min-width: 0; }
.scope-note { margin-top: 12px; color: var(--el-text-color-secondary, #7a8599); font-size: 12px; }
.state-alert { margin-top: 16px; }
.source-errors { margin: 6px 0; padding-left: 18px; }
.loading-state { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-top: 16px; }
.result-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 18px 0 10px; color: var(--el-text-color-secondary, #7a8599); font-size: 13px; }
.ranking-reason { flex-basis: 100%; font-size: 12px; }
.export-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.sync-state { color: var(--el-text-color-secondary, #909399); font-size: 12px; }
.sync-state--error, .sync-state--fallback { color: var(--el-color-warning, #e6a23c); }
.source-details { width: 100%; margin-top: 10px; border-collapse: collapse; color: var(--el-text-color-regular, #606266); font-size: 12px; }
.source-details caption { margin-bottom: 4px; color: var(--el-text-color-primary, #303133); text-align: left; font-weight: 600; }
.source-details th, .source-details td { padding: 5px 8px; border: 1px solid var(--el-border-color-lighter, #ebeef5); text-align: left; }
.source-details th { color: var(--el-text-color-secondary, #909399); font-weight: 500; }
.selection-panel { padding: 12px 14px; border: 1px solid var(--el-border-color-lighter, #ebeef5); border-radius: 8px; background: var(--el-fill-color-extra-light, #fafafa); }
.selection-title { margin-bottom: 8px; color: var(--el-text-color-primary, #303133); font-weight: 600; }
.selection-title span { color: var(--el-text-color-secondary, #909399); font-weight: 400; }
.selection-panel :deep(.el-checkbox-group) { display: flex; flex-wrap: wrap; gap: 4px 16px; }
.recommendation-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 16px; }
.card-header { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }
.card-header h3 { margin: 0; color: var(--el-text-color-primary, #303133); font-size: 16px; }
.metric-label { display: block; margin-top: 4px; color: var(--el-text-color-secondary, #909399); font-size: 12px; }
.rule-reason, .ai-reason { display: flex; gap: 8px; align-items: flex-start; margin-bottom: 10px; color: var(--el-text-color-regular, #606266); font-size: 13px; line-height: 1.55; }
.rule-reason strong { color: var(--el-text-color-primary, #303133); white-space: nowrap; }
.ai-reason { padding: 8px; border-radius: 6px; background: #f0f9eb; color: #529b2e; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 8px; margin-top: 8px; }
.kpi-item { padding: 8px; border-radius: 6px; background: var(--el-fill-color-light, #f5f7fa); }
.kpi-item span { display: block; color: var(--el-text-color-secondary, #909399); font-size: 12px; }
.kpi-item strong { display: block; margin-top: 4px; color: var(--el-text-color-primary, #303133); font-size: 16px; }
.kpi-item small { font-size: 11px; font-weight: 400; }
.finding-section { margin-top: 12px; }
.finding-title { margin-bottom: 4px; font-size: 13px; font-weight: 600; }
.finding-title--success { color: #67c23a; }.finding-title--warning { color: #e6a23c; }.finding-title--info { color: #409eff; }
.finding-section ul { margin: 0; padding-left: 18px; color: var(--el-text-color-regular, #606266); font-size: 12px; line-height: 1.6; }
.unavailable-card { margin-top: 16px; }
.unavailable-title { display: flex; align-items: center; gap: 6px; color: var(--el-text-color-primary, #303133); font-weight: 600; }
.unavailable-item { display: flex; flex-wrap: wrap; gap: 8px 14px; padding: 8px 0; border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5); color: var(--el-text-color-regular, #606266); font-size: 13px; }
.unavailable-item:last-child { border-bottom: 0; }
@media (max-width: 800px) { .recommendation-grid, .loading-state { grid-template-columns: 1fr; } .page-header { align-items: flex-start; } .filter-row { align-items: flex-start; flex-direction: column; } .filter-item { width: 100%; } .filter-item :deep(.el-select) { flex: 1; } }
</style>
