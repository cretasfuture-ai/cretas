<script setup lang="ts">
import { computed, ref, watch, watchEffect, onBeforeUnmount } from 'vue';
import echarts from '@/utils/echarts';
import type { ExternalProductionData } from '@/api/smartbi/factoryGold';
import BlacklakeProductionSource from './BlacklakeProductionSource.vue';
import BlacklakeBarChart from './BlacklakeBarChart.vue';
import BlacklakeChartAnalysis from './BlacklakeChartAnalysis.vue';
import { blacklakeInsightChart } from './blacklakeChartData';

const props = defineProps<{ data: ExternalProductionData; factoryId: string; dateRange?: string }>();
const FINISHED = '成品入库';
const unit = ref('');
const process = ref('');
const productCode = ref<string | undefined>();
const rankingVersion = computed(() => JSON.stringify([
  props.data.snapshotCollectedAt, props.dateRange, unit.value,
]));
const dailyVersion = computed(() => JSON.stringify([
  props.data.snapshotCollectedAt, props.dateRange, unit.value, process.value, productCode.value,
]));
const chartElement = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined;
let resize: ResizeObserver | undefined;

const knownRows = computed(() => props.data.rows.filter(row => row.unit && row.processName));
const hasUnclassifiedRows = computed(() => props.data.rows.length > knownRows.value.length);
const units = computed(() => [...new Set(knownRows.value.map(row => row.unit))]);
const unitRows = computed(() => knownRows.value.filter(row => row.unit === unit.value));
const processes = computed(() => [...new Set(unitRows.value.map(row => row.processName))]);
const processRows = computed(() => unitRows.value.filter(row => row.processName === process.value));
const products = computed(() => new Map(processRows.value.map(row => [row.productCode, row.productName])));

watch(units, items => {
  if (!items.includes(unit.value)) unit.value = items[0] ?? '';
}, { immediate: true });
watch(processes, items => {
  if (!items.includes(process.value)) process.value = items.includes(FINISHED) ? FINISHED : items[0] ?? '';
}, { immediate: true });
watch(products, items => {
  if (productCode.value !== undefined && !items.has(productCode.value)) productCode.value = undefined;
});

const daily = computed(() => {
  const byDate = new Map<string, { day: string; good: number; defect: number }>();
  for (const row of processRows.value) {
    if (productCode.value !== undefined && row.productCode !== productCode.value) continue;
    const total = byDate.get(row.reportDate) ?? { day: row.reportDate, good: 0, defect: 0 };
    total.good += row.goodQty;
    total.defect += row.defectQty;
    byDate.set(row.reportDate, total);
  }
  return [...byDate.values()].sort((a, b) => a.day.localeCompare(b.day));
});

const ranking = computed(() => {
  const byCode = new Map<string, { code: string; name: string; good: number }>();
  for (const row of unitRows.value) {
    if (row.processName !== FINISHED || !row.productCode) continue;
    const total = byCode.get(row.productCode) ?? { code: row.productCode, name: row.productName, good: 0 };
    total.good += row.goodQty;
    if (row.productName) total.name = row.productName;
    byCode.set(row.productCode, total);
  }
  return [...byCode.values()].sort((a, b) => b.good - a.good || a.code.localeCompare(b.code));
});

const quantity = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 });
const percent = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 2 });
const productLabel = (code: string, name: string) => {
  if (code && name && code !== name) return `${name} · ${code}`;
  return name || code || '未登记产品';
};
const dailyMetric = computed(() => `${process.value}每日良品（${unit.value}）`);
const dailyInsight = computed(() => blacklakeInsightChart(dailyMetric.value, 'line', 'quantity',
  daily.value.map(row => ({ label: row.day, value: row.good }))));
const rankingPoints = computed(() => ranking.value.map(row => ({
  label: productLabel(row.code, row.name), value: row.good,
})));
const reportBasis = '仅分析黑湖报工数量，不同工序和单位分别统计。报工来源未提供投入量或制造成本。';

watchEffect(() => {
  const element = chartElement.value;
  if (!element) return;
  if (!chart || chart.getDom() !== element) {
    chart?.dispose();
    resize?.disconnect();
    chart = echarts.init(element, 'cretas');
    if (typeof ResizeObserver !== 'undefined') {
      resize = new ResizeObserver(() => chart?.resize());
      resize.observe(element);
    }
  }
  chart.setOption({
    tooltip: { trigger: 'axis', confine: true, renderMode: 'richText' },
    legend: { bottom: 0 },
    grid: { top: 36, right: 24, bottom: 54, left: 24, containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: daily.value.map(row => row.day) },
    yAxis: { type: 'value', name: unit.value },
    series: [
      { name: '良品数量', type: 'line', data: daily.value.map(row => row.good) },
      { name: '不良数量', type: 'line', data: daily.value.map(row => row.defect) },
    ],
  });
}, { flush: 'post' });

onBeforeUnmount(() => {
  resize?.disconnect();
  chart?.dispose();
});
</script>

<template>
  <section aria-label="黑湖生产数据分析">
    <BlacklakeProductionSource :data="data" />
    <p v-if="units.length"><RouterLink
      :to="{ path: '/smart-bi/analysis', query: { tab: 'query', mode: 'factory', source: 'business' } }">自由问数出图</RouterLink></p>
    <el-empty v-if="!units.length" :description="data.rows.length ? '所选期间没有工序和单位均明确的黑湖记录' : '所选期间暂无黑湖生产报工'" />
    <template v-else>
      <el-card shadow="never" class="blacklake-card">
        <template #header>
          <h3>每日报工趋势</h3>
          <div class="scope-controls">
            <label>
              单位
              <el-select v-model="unit" aria-label="单位" class="scope-control">
                <el-option v-for="item in units" :key="item" :label="item" :value="item" />
              </el-select>
            </label>
            <label>
              工序
              <el-select v-model="process" aria-label="工序" class="scope-control">
                <el-option v-for="item in processes" :key="item" :label="item" :value="item" />
              </el-select>
            </label>
            <label>
              产品
              <el-select v-model="productCode" aria-label="产品" clearable filterable placeholder="全部产品" class="scope-product">
                <el-option v-for="[code, name] in products" :key="code" :label="productLabel(code, name)" :value="code" />
              </el-select>
            </label>
          </div>
        </template>
        <p class="basis">按单一单位、单一工序统计。不良率为不良数量 ÷（良品数量＋不良数量）。</p>
        <p v-if="hasUnclassifiedRows" class="basis">缺少工序或单位的报工未计入汇总。</p>
        <div ref="chartElement" class="daily-chart" role="img" aria-label="每日良品与不良数量趋势图" />
        <BlacklakeChartAnalysis :chart="dailyInsight" :factory-id="factoryId" :metric="dailyMetric"
          :basis="reportBasis" :data-version="dailyVersion" />
        <el-table :data="daily" max-height="320" stripe empty-text="所选产品暂无报工">
          <el-table-column prop="day" label="日期" min-width="112" />
          <el-table-column :label="`良品（${unit}）`" align="right" min-width="100">
            <template #default="{ row }">{{ quantity.format(row.good) }}</template>
          </el-table-column>
          <el-table-column :label="`不良（${unit}）`" align="right" min-width="100">
            <template #default="{ row }">{{ quantity.format(row.defect) }}</template>
          </el-table-column>
          <el-table-column label="不良率" align="right" min-width="96">
            <template #default="{ row }">{{ row.good + row.defect > 0 ? percent.format(row.defect / (row.good + row.defect)) : '—' }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="blacklake-card">
        <template #header><h3>成品入库良品排行（{{ unit }}）</h3></template>
        <BlacklakeBarChart :points="rankingPoints" :title="`成品入库良品排行（${unit}）`"
          :factory-id="factoryId" :basis="`${reportBasis} 仅纳入有产品编码的成品入库记录。`"
          :data-version="rankingVersion" />
        <el-table :data="ranking" max-height="320" stripe empty-text="该单位暂无有编码的成品入库记录">
          <el-table-column type="index" label="名次" width="68" />
          <el-table-column label="产品" min-width="175">
            <template #default="{ row }">{{ productLabel(row.code, row.name) }}</template>
          </el-table-column>
          <el-table-column :label="`良品（${unit}）`" align="right" min-width="108">
            <template #default="{ row }">{{ quantity.format(row.good) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </section>
</template>

<style scoped>
.blacklake-card { margin-bottom: 16px; border-radius: 10px; }
.blacklake-card h3 { margin: 0; }
.scope-controls { display: flex; flex-wrap: wrap; gap: 12px 20px; margin-top: 12px; }
.scope-controls label { display: flex; align-items: center; gap: 8px; }
.scope-control { width: 132px; }
.scope-product { width: 240px; max-width: 58vw; }
.basis { margin: 0 0 8px; color: var(--el-text-color-secondary); font-size: 13px; }
.daily-chart { height: 256px; }
</style>
