<template>
  <el-drawer
    v-model="visible"
    :title="drawerTitle"
    direction="rtl"
    size="60%"
    :destroy-on-close="true"
    @close="onClose"
  >
    <div v-if="loading" class="loading-block">
      <el-skeleton :rows="6" animated />
    </div>

    <div v-else-if="loadError" class="error-block">
      <el-result
        icon="error"
        :title="'加载失败'"
        :sub-title="loadError"
      >
        <template #extra>
          <el-button type="primary" @click="loadDetail">重试</el-button>
        </template>
      </el-result>
    </div>

    <div v-else-if="detail" class="detail-content">
      <!-- 顶部: 基础信息 + 操作 -->
      <div class="meta-section">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="编码">
            <code class="code-text">{{ detail.code }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="分类">
            <el-tag size="small">{{ categoryText(detail.category) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="单位">
            {{ detail.unit || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.isActive ? 'success' : 'info'" size="small">
              {{ detail.isActive ? '启用' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="计算策略">
            {{ strategyText(detail.computeStrategy) }}
          </el-descriptions-item>
          <el-descriptions-item label="缓存 TTL">
            {{ detail.cacheTtlSeconds ? `${detail.cacheTtlSeconds} 秒` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="说明" :span="2">
            {{ detail.description || '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="action-bar">
          <el-button
            v-if="canRecompute"
            type="primary"
            :loading="recomputing"
            :icon="Refresh"
            @click="onRecompute"
          >
            强制重算
          </el-button>
          <el-button :icon="Refresh" @click="refreshDrawer">刷新</el-button>
        </div>

        <div v-if="formulaDefinition" class="formula-panel" data-testid="formula-basis">
          <div class="formula-panel-header">
            <span class="section-title">业务口径</span>
            <el-tag size="small" type="success">{{ formulaDefinition.sourceLabel }}</el-tag>
          </div>
          <div class="formula-line"><span class="formula-label">计算公式</span>{{ formulaDefinition.formula }}</div>
          <div class="formula-note">{{ formulaDefinition.basisNote }}；历史表格保留所选期间的旧口径记录，图表仅使用本页当前口径记录。</div>
        </div>

        <div v-if="isActualFlowMetric" class="period-selector" data-testid="period-selector">
          <span class="period-label">统计期间</span>
          <el-radio-group v-model="periodPreset" size="small">
            <el-radio-button label="today">今天</el-radio-button>
            <el-radio-button label="month">本月</el-radio-button>
            <el-radio-button label="custom">自定义</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-if="periodPreset === 'custom'"
            v-model="customPeriodRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            size="small"
          />
          <span class="period-hint">{{ periodLabel }}</span>
        </div>
      </div>

      <!-- 当前值 + 阈值色带 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <span class="section-title">当前指标值</span>
        </template>
        <ThresholdGauge
          :current-value="currentValue"
          :unit="detail.unit"
          :thresholds="thresholds"
          :alert-level="currentAlertLevel"
          :loading="valueLoading || thresholdsLoading"
        />
        <p v-if="!valueLoading" class="muted" data-testid="indicator-evidence">
          {{ valueError || valueStatusText }}
          <template v-if="currentValueData">
            · 来源：{{ valueSourceText }}
            <template v-if="currentValueData.computedAt"> · 计算于 {{ formatDateTime(currentValueData.computedAt) }}</template>
          </template>
        </p>
        <p v-if="thresholdsError" class="muted">{{ thresholdsError }}</p>
        <p
          v-if="detail.code === 'RESTAURANT_DISH_MARGIN' && currentValueData?.source?.startsWith('PYTHON_ENDPOINT:')"
          class="muted"
        >
          使用当前成本快照计算，不代表历史成本还原；成本未完整覆盖时不出值。
        </p>
      </el-card>

      <!-- 历史快照 -->
      <el-card class="section-card" shadow="never">
        <template #header>
          <div class="section-header-flex">
            <span class="section-title">{{ isActualFlowMetric ? '所选期间的计算记录' : '历史趋势' }}</span>
            <el-radio-group v-model="historyView" size="small">
              <el-radio-button label="chart">图表</el-radio-button>
              <el-radio-button label="table">表格</el-radio-button>
            </el-radio-group>
          </div>
        </template>

        <div v-if="versionsLoading" class="loading-block">
          <el-skeleton :rows="3" animated />
        </div>

        <el-alert v-else-if="versionsError" :title="versionsError" type="error" :closable="false" />
        <el-empty
          v-else-if="versions.length === 0"
          :image-size="60"
          :description="isActualFlowMetric ? '该统计期间暂无计算记录' : '暂无历史快照'"
        />

        <div v-else>
          <!-- ECharts 折线图 (>5 条用图表, ≤5 用表格) -->
          <div
            v-show="historyView === 'chart' && chartVersions.length > 5"
            ref="chartRef"
            class="history-chart"
          />
          <!-- U6: useChartInsight — TREND insight on historical versions -->
          <ChartInsight
            v-if="!isActualFlowMetric"
            :insight="versionsInsight"
            :loading="versionsInsightLoading"
            depth="detailed"
          />

          <!-- 表格视图 (始终可切换, 数据少时默认显示) -->
          <el-table
            v-show="historyView === 'table' || chartVersions.length <= 5"
            :data="versions"
            size="small"
            border
            stripe
            max-height="320"
          >
            <el-table-column prop="computedAt" label="计算时间" min-width="160">
              <template #default="{ row }">
                {{ formatDateTime(row.computedAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="periodStart" label="期间起" min-width="100">
              <template #default="{ row }">
                {{ row.periodStart }}
              </template>
            </el-table-column>
            <el-table-column prop="periodEnd" label="期间止" min-width="100">
              <template #default="{ row }">
                {{ row.periodEnd }}
              </template>
            </el-table-column>
            <el-table-column prop="value" label="数值" min-width="100" align="right">
              <template #default="{ row }">
                <span class="version-value">{{ formatValue(row.value) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="isActualFlowMetric" label="计算口径" min-width="180">
              <template #default="{ row }">
                {{ formulaSourceText(row.computeSource) }}
              </template>
            </el-table-column>
            <el-table-column prop="alertLevel" label="告警" min-width="80" align="center">
              <template #default="{ row }">
                <el-tag
                  v-if="row.alertLevel"
                  :type="alertTagType(row.alertLevel)"
                  size="small"
                  effect="dark"
                >
                  {{ alertLevelText(row.alertLevel) }}
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-if="versionsTotal > pageSize"
            class="version-pagination"
            small
            background
            layout="prev, pager, next, total"
            :total="versionsTotal"
            :page-size="pageSize"
            :current-page="page + 1"
            @current-change="onPageChange"
          />
        </div>
      </el-card>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * IndicatorDetailDrawer — slide-in drawer 显示指标详情 + 阈值色带 + 历史快照.
 *
 * v-model:visible 控制开关. props.code 切换时自动 reload.
 */
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import { usePermissionStore } from '@/store/modules/permission';
import { useChartInsight } from '@/composables/useChartInsight';
import ChartInsight from '@/views/smart-bi/components/ChartInsight.vue';
import {
  getIndicatorDetail,
  getIndicatorValue,
  getIndicatorThresholds,
  listIndicatorVersions,
  recomputeIndicator,
  type IndicatorDetailResponse,
  type IndicatorValueResponse,
  type ThresholdResponse,
  type IndicatorVersionResponse,
  type AlertLevel,
} from '@/api/indicator';
import ThresholdGauge from '@/components/indicator/ThresholdGauge.vue';
import { isValidPeriod } from './actualFlowPeriod';

interface Props {
  modelValue: boolean;
  factoryId: string;
  code: string | null;
  initialPeriod?: [string, string] | null;
  actorKey?: string;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  (e: 'recomputed', code: string): void;
}>();

const permissionStore = usePermissionStore();
const canRecompute = computed(() => permissionStore.canWrite('analytics'));

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
});

const detail = ref<IndicatorDetailResponse | null>(null);
const currentValueData = ref<IndicatorValueResponse | null>(null);
const thresholds = ref<ThresholdResponse[]>([]);
const versions = ref<IndicatorVersionResponse[]>([]);
const versionsTotal = ref(0);

const loading = ref(false);
const valueLoading = ref(false);
const valueError = ref<string | null>(null);
const thresholdsLoading = ref(false);
const thresholdsError = ref<string | null>(null);
const versionsLoading = ref(false);
const versionsError = ref<string | null>(null);
const recomputing = ref(false);
const loadError = ref<string | null>(null);

const page = ref(0);
const pageSize = 10;
const historyView = ref<'chart' | 'table'>('chart');
type PeriodPreset = 'today' | 'month' | 'custom';
type DateRange = [string, string];
const periodPreset = ref<PeriodPreset>('today');
const customPeriodRange = ref<DateRange | null>(null);
const calendarToday = ref(formatDateOnly(new Date()));

function applyInitialPeriod(period?: DateRange | null) {
  calendarToday.value = formatDateOnly(new Date());
  if (period === undefined) { periodPreset.value = 'today'; customPeriodRange.value = null; return; }
  if (!isValidPeriod(period)) { periodPreset.value = 'custom'; customPeriodRange.value = null; return; }
  const today = formatDateOnly(new Date());
  if (period[0] === today && period[1] === today) { periodPreset.value = 'today'; customPeriodRange.value = null; return; }
  const now = new Date();
  const monthStart = formatDateOnly(new Date(now.getFullYear(), now.getMonth(), 1));
  if (period[0] === monthStart && period[1] === today) { periodPreset.value = 'month'; customPeriodRange.value = null; return; }
  periodPreset.value = 'custom'; customPeriodRange.value = [...period];
}

const FORMULA_DEFINITIONS: Record<string, { formula: string; sourceLabel: string; basisNote: string }> = {
  FACTORY_LU_YIELD_RATE: {
    formula: '成品合格产出量（kg）÷实际首工序投入量（kg）×100%',
    sourceLabel: '真实业务 · 实际流',
    basisNote: '统计已完工且已结单的生产单；旧样本缺少报工时暂无数据',
  },
  FACTORY_GOOD_OUTPUT_RATE: {
    formula: '合格产出量（kg）÷（合格产出量（kg）+已确认不良量（kg））×100%',
    sourceLabel: '真实业务 · 实际流',
    basisNote: '统计已完工且已结单的生产单；旧样本缺少报工时暂无数据',
  },
  FACTORY_LU_UNIT_COST: {
    formula: '完整实际采集成本（元）÷合格产出量（kg）',
    sourceLabel: '真实业务 · 实际流',
    basisNote: '统计已完工且已结单的生产单；旧样本缺少报工时暂无数据',
  },
  FACTORY_ACTUAL_TOTAL_COST: {
    formula: '完整实际采集成本（元）',
    sourceLabel: '真实业务 · 实际流',
    basisNote: '统计已完工且已结单的生产单；旧样本缺少报工时暂无数据',
  },
};
const ACTUAL_FLOW_SOURCE = (code: string) => `REAL_BUSINESS:${code}:actual-flow-v2`;

const chartRef = ref<HTMLElement | null>(null);
let chartInst: echarts.ECharts | null = null;
let generation = 0;

const drawerTitle = computed(() => {
  if (loading.value) return '指标详情';
  return detail.value ? `${detail.value.name} - 指标详情` : '指标详情';
});

const formulaDefinition = computed(() =>
  props.code ? FORMULA_DEFINITIONS[props.code] ?? null : null,
);
const isActualFlowMetric = computed(() => formulaDefinition.value !== null);
const chartVersions = computed(() => {
  if (!isActualFlowMetric.value) return versions.value;
  const range = selectedPeriod.value;
  return versions.value.filter((version) => version.computeSource === ACTUAL_FLOW_SOURCE(props.code!)
    && (!range || (version.periodStart === range[0] && version.periodEnd === range[1])));
});

function formatDateOnly(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

const selectedPeriod = computed<DateRange | null>(() => {
  const todayText = calendarToday.value;
  if (periodPreset.value === 'today') return [todayText, todayText];
  if (periodPreset.value === 'month') {
    return [`${todayText.slice(0, 7)}-01`, todayText];
  }
  return isValidPeriod(customPeriodRange.value) ? customPeriodRange.value : null;
});
const periodLabel = computed(() => {
  const range = selectedPeriod.value;
  if (!range) return '请选择完整日期';
  return range[0] === range[1] ? range[0] : `${range[0]} 至 ${range[1]}`;
});

// The requested current value owns this slot. Explicit null or a failed query
// cannot be replaced with a different historical snapshot from the detail API.
const currentValue = computed(() => currentValueData.value?.value ?? null);
const valueStatusText = computed(() => {
  const state = currentValueData.value?.status;
  if (state === 'NOT_COMPUTED') return '未计算';
  if (state === 'FAILED') return '计算失败';
  if (state === 'NO_DATA') return '暂无业务数据';
  return currentValue.value == null ? '暂无可用值' : '当前值';
});
const valueSourceText = computed(() => {
  const source = currentValueData.value?.source || '';
  if (source.startsWith('REAL_BUSINESS:')) return '业务数据';
  if (source.startsWith('PYTHON_ENDPOINT:')) return '数据分析服务';
  if (source === 'cache') return '已保存的计算快照';
  if (source === 'precomputed') return '预计算快照';
  return '来源未提供';
});

function periodQuery(): { periodStart: string; periodEnd: string } | undefined {
  if (!isActualFlowMetric.value) return undefined;
  const range = selectedPeriod.value;
  return range ? { periodStart: range[0], periodEnd: range[1] } : undefined;
}

function versionsWindowQuery(): { windowStart: string; windowEnd: string; exactPeriod: true } | undefined {
  const range = periodQuery();
  return range ? { windowStart: range.periodStart, windowEnd: range.periodEnd, exactPeriod: true } : undefined;
}

const currentAlertLevel = computed<AlertLevel>(() => {
  return currentValueData.value?.alertLevel ?? null;
});
function requestIdentity(): string {
  const range = selectedPeriod.value;
  return `${props.factoryId}|${props.actorKey || ''}|${props.code || ''}|${props.modelValue}|${range?.[0] || ''}|${range?.[1] || ''}`;
}
let alive = true;
const requestSequences = { detail: 0, value: 0, thresholds: 0, versions: 0, recompute: 0 };
function requestGuard(slot: keyof typeof requestSequences) {
  const identity = requestIdentity();
  const context = generation;
  const sequence = ++requestSequences[slot];
  return () => alive && props.modelValue && context === generation
    && identity === requestIdentity() && sequence === requestSequences[slot];
}

async function loadDetail() {
  if (!props.code) return;
  const isCurrent = requestGuard('detail');
  loadError.value = null;
  loading.value = true;
  try {
    const result = await getIndicatorDetail(props.factoryId, props.code);
    if (isCurrent()) detail.value = result;
  } catch (e) {
    if (isCurrent()) loadError.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    if (isCurrent()) loading.value = false;
  }
}

async function loadValue() {
  if (!props.code) return;
  const isCurrent = requestGuard('value');
  valueLoading.value = true;
  valueError.value = null;
  currentValueData.value = null;
  const query = periodQuery();
  if (isActualFlowMetric.value && !query) {
    valueError.value = '请选择完整日期';
    valueLoading.value = false;
    return;
  }
  try {
    const result = await getIndicatorValue(props.factoryId, props.code, query);
    if (isCurrent()) currentValueData.value = result;
  } catch (e) {
    if (isCurrent()) valueError.value = e instanceof Error ? e.message : '指标值查询失败，请重试';
  } finally {
    if (isCurrent()) valueLoading.value = false;
  }
}

async function loadThresholds() {
  if (!props.code) return;
  const isCurrent = requestGuard('thresholds');
  thresholdsLoading.value = true;
  thresholdsError.value = null;
  try {
    const result = await getIndicatorThresholds(props.factoryId, props.code);
    if (isCurrent()) thresholds.value = result;
  } catch (e) {
    console.warn('[IndicatorDetailDrawer] thresholds load failed:', e);
    if (isCurrent()) {
      thresholds.value = [];
      thresholdsError.value = '预警规则读取失败，请刷新重试';
    }
  } finally {
    if (isCurrent()) thresholdsLoading.value = false;
  }
}

async function loadVersions() {
  if (!props.code) return;
  const isCurrent = requestGuard('versions');
  versionsLoading.value = true;
  versionsError.value = null;
  versions.value = [];
  if (isActualFlowMetric.value && !periodQuery()) {
    versionsError.value = '请选择完整日期后查看计算记录';
    versionsLoading.value = false;
    return;
  }
  try {
    const windowQuery = versionsWindowQuery();
    const res = await listIndicatorVersions(props.factoryId, props.code, {
      page: page.value + 1,
      size: pageSize,
      ...windowQuery,
    });
    if (!isCurrent()) return;
    const content = res.content || [];
    if (windowQuery && content.some((v) =>
      v.periodStart !== windowQuery.windowStart || v.periodEnd !== windowQuery.windowEnd)) {
      throw new Error('返回记录与所选统计期间不一致，请刷新后重试');
    }
    versions.value = content;
    versionsTotal.value = res.totalElements ?? content.length;
    nextTick(() => { if (isCurrent()) renderChart(); });
  } catch (e) {
    console.warn('[IndicatorDetailDrawer] versions load failed:', e);
    if (isCurrent()) {
      versions.value = []; versionsTotal.value = 0;
      versionsError.value = e instanceof Error ? `计算记录读取失败：${e.message}` : '计算记录读取失败，请刷新重试';
    }
  } finally {
    if (isCurrent()) versionsLoading.value = false;
  }
}

async function loadAll() {
  generation += 1;
  detail.value = null;
  currentValueData.value = null;
  thresholds.value = [];
  versions.value = [];
  versionsTotal.value = 0;
  await Promise.all([loadDetail(), loadValue(), loadThresholds(), loadVersions()]);
}
function syncCalendarToday(): boolean {
  const before = selectedPeriod.value?.join('|');
  calendarToday.value = formatDateOnly(new Date());
  return before !== selectedPeriod.value?.join('|');
}
async function refreshDrawer() {
  if (syncCalendarToday()) await nextTick();
  else await loadAll();
}

function onPageChange(newPage: number) {
  page.value = newPage - 1;
  loadVersions();
}

async function onRecompute() {
  if (!props.code || !props.modelValue || !canRecompute.value || recomputing.value) return;
  if (syncCalendarToday()) await nextTick();
  if (!props.code || !props.modelValue || !canRecompute.value || recomputing.value) return;
  const isCurrent = requestGuard('recompute');
  const query = periodQuery();
  if (isActualFlowMetric.value && !query) {
    valueError.value = '请选择完整日期';
    return;
  }
  ++requestSequences.value;
  valueLoading.value = false;
  recomputing.value = true;
  try {
    const result = await recomputeIndicator(props.factoryId, props.code, query);
    if (!isCurrent()) return;
    currentValueData.value = result;
    valueError.value = null;
    if (result.status === 'FAILED') {
      ElMessage.error('指标重算失败，请检查数据源后重试');
      return;
    }
    if (result.status !== 'AVAILABLE' || result.value == null || !Number.isFinite(result.value)) {
      ElMessage.warning(result.status === 'NO_DATA' ? '暂无业务数据，未生成新的计算快照' : '暂无可用计算结果');
      return;
    }
    ElMessage.success(`重算完成: ${result.value ?? '—'}`);
    if (!isCurrent()) return;
    emit('recomputed', props.code);
    // 重新加载详情 + 历史 (新增了一条 version)
    await Promise.all([loadDetail(), loadVersions()]);
  } catch (e) {
    if (isCurrent()) {
      currentValueData.value = null;
      valueError.value = e instanceof Error ? e.message : '指标重算失败，请重试';
    }
    // ApiError 已被 request.ts 拦截展示 toast, 此处只 console.
    console.warn('[IndicatorDetailDrawer] recompute failed:', e);
  } finally {
    // The POST cannot be cancelled. Closing/reopening must not release its single-flight lock.
    recomputing.value = false;
  }
}

function onClose() {
  generation += 1;
  loading.value = false;
  valueLoading.value = false;
  thresholdsLoading.value = false;
  versionsLoading.value = false;
  versionsError.value = null;
  // reset 状态便于下次打开
  detail.value = null;
  currentValueData.value = null;
  valueError.value = null;
  thresholds.value = [];
  versions.value = [];
  versionsTotal.value = 0;
  page.value = 0;
  loadError.value = null;
  if (chartInst) {
    chartInst.dispose();
    chartInst = null;
  }
}

function renderChart() {
  if (historyView.value !== 'chart' || chartVersions.value.length <= 5) return;
  if (!chartRef.value) return;

  if (!chartInst) {
    chartInst = echarts.init(chartRef.value);
  }

  // versions 后端默认 page=0 DESC; 反转给图表展示 (旧→新)
  const sorted = [...chartVersions.value].sort(
    (a, b) => new Date(a.computedAt).getTime() - new Date(b.computedAt).getTime()
  );
  const xData = sorted.map(v => formatDateTime(v.computedAt));
  const yData = sorted.map(v => ({
    value: v.value,
    itemStyle: {
      color: alertColor(v.alertLevel),
    },
  }));

  const option: echarts.EChartsOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 60 },
    xAxis: {
      type: 'category',
      data: xData,
      axisLabel: { rotate: 30, fontSize: 10 },
    },
    yAxis: {
      type: 'value',
      name: detail.value?.unit || '',
      axisLabel: { fontSize: 10 },
    },
    series: [
      {
        name: detail.value?.name || '值',
        type: 'line',
        data: yData,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { color: '#5470c6', width: 2 },
      },
    ],
  };

  chartInst.setOption(option);
}

// ==================== Chart Insight ====================
// LINE over time: versions (sorted asc by computedAt) → TREND family.
// Driven off the same `versions` ref used by renderChart().
const indicatorVersionsSource = () => {
  if (chartVersions.value.length < 2) return null;
  const sorted = [...chartVersions.value].sort(
    (a, b) => new Date(a.computedAt).getTime() - new Date(b.computedAt).getTime(),
  );
  return {
    chart: {
      chartType: 'LINE',
      meta: { xDim: 'time' as const, yMetric: 'quantity' as const, aggregation: 'avg' as const, domain: 'factory' as const },
      config: {
        xAxis: { data: sorted.map((v) => v.computedAt.slice(0, 10)) },
        series: [{ type: 'line', data: sorted.map((v) => Number(v.value)) }],
      },
    },
  };
};

const { insight: versionsInsight, loading: versionsInsightLoading } = useChartInsight(
  indicatorVersionsSource,
  () => ({ canViewFinance: false }),
  { factoryId: () => props.factoryId, autoTier2: true },
);

watch(historyView, () => {
  nextTick(() => renderChart());
});

watch(
  () => [props.modelValue, props.code, props.factoryId, props.actorKey, props.initialPeriod?.[0], props.initialPeriod?.[1]],
  () => { applyInitialPeriod(props.initialPeriod); },
  { immediate: true },
);

watch(
  () => [props.modelValue, props.code, props.factoryId, props.actorKey, selectedPeriod.value?.[0], selectedPeriod.value?.[1]],
  () => {
    if (!props.modelValue || !props.code) { onClose(); return; }
    page.value = 0;
    void loadAll();
  },
  { immediate: true },
);
onBeforeUnmount(() => { alive = false; onClose(); });

// ============ helpers ============

function categoryText(c: string): string {
  const map: Record<string, string> = {
    FACTORY: '工厂',
    RESTAURANT: '餐饮',
    QUALITY: '质量',
  };
  return map[c] || c;
}

function strategyText(s: string): string {
  const map: Record<string, string> = {
    REALTIME: '实时计算',
    CACHED: '缓存 (TTL)',
    PRECOMPUTED: '预计算',
  };
  return map[s] || s;
}

function alertLevelText(level: string): string {
  return level === 'GREEN' ? '正常' : level === 'YELLOW' ? '关注' : level === 'RED' ? '告警' : level;
}

function alertTagType(level: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (level) {
    case 'GREEN': return 'success';
    case 'YELLOW': return 'warning';
    case 'RED': return 'danger';
    default: return 'info';
  }
}

function alertColor(level: AlertLevel): string {
  switch (level) {
    case 'GREEN': return '#67c23a';
    case 'YELLOW': return '#e6a23c';
    case 'RED': return '#f56c6c';
    default: return '#909399';
  }
}

function formatValue(v: number): string {
  if (Math.abs(v) < 1000) {
    return Number.isInteger(v) ? v.toString() : v.toFixed(2);
  }
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

function formulaSourceText(source?: string): string {
  if (source === ACTUAL_FLOW_SOURCE(props.code || '') && formulaDefinition.value) {
    return `${formulaDefinition.value.sourceLabel}：${formulaDefinition.value.formula}`;
  }
  return '旧口径，仅供查看';
}

function formatDateTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}
</script>

<style scoped>
.loading-block,
.error-block {
  padding: 24px;
}

.detail-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 0 4px;
}

.meta-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.code-text {
  background: var(--el-fill-color);
  padding: 2px 6px;
  border-radius: 3px;
  font-family: var(--el-font-family-monospace, monospace);
}

.action-bar {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.formula-panel {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--el-color-success-light-5);
  border-radius: 4px;
  background: var(--el-color-success-light-9);
}

.formula-panel-header,
.period-selector {
  display: flex;
  align-items: center;
  gap: 10px;
}

.formula-line {
  color: var(--el-text-color-primary);
  font-size: 13px;
  line-height: 1.6;
}

.formula-label,
.period-label {
  color: var(--el-text-color-regular);
  font-size: 12px;
  margin-right: 6px;
}

.formula-note,
.period-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.period-selector {
  flex-wrap: wrap;
}

.period-selector .el-date-editor {
  width: 250px;
}

.section-card {
  border: 1px solid var(--el-border-color-lighter);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
}

.section-header-flex {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.history-chart {
  width: 100%;
  height: 280px;
}

.version-value {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.version-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.muted {
  color: var(--el-text-color-placeholder);
}
</style>
