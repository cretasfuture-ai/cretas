<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watchEffect } from 'vue';
import echarts from '@/utils/echarts';
import { blacklakeInsightChart } from './blacklakeChartData';
import BlacklakeChartAnalysis from './BlacklakeChartAnalysis.vue';

const props = defineProps<{
  points: Array<{ label: string; value: number; axisLabel?: string }>;
  analysisPoints?: Array<{ label: string; value: number }>;
  title: string;
  factoryId: string;
  basis: string;
  cost?: boolean;
}>();
const order = ref<'desc' | 'asc' | 'name'>('desc');
const element = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined;
let resize: ResizeObserver | undefined;
const sliderWidth = 28;
const sliderRight = 4;
const axisGutter = 12;
const byName = new Intl.Collator('zh-CN', { numeric: true });
const sorted = computed(() => [...props.points].sort((a, b) => {
  const byValue = order.value === 'asc' ? a.value - b.value : b.value - a.value;
  return (order.value === 'name' ? 0 : byValue) || byName.compare(a.label, b.label);
}));
// Changing the presentation order does not change the underlying insight.
const insight = computed(() => blacklakeInsightChart(
  props.title, 'bar', props.cost ? 'cost' : 'quantity', props.analysisPoints ?? props.points,
));

watchEffect(() => {
  if (!element.value) {
    resize?.disconnect();
    chart?.dispose();
    chart = undefined;
    return;
  }
  if (!chart) {
    chart = echarts.init(element.value, 'cretas');
    if (typeof ResizeObserver !== 'undefined') {
      resize = new ResizeObserver(() => chart?.resize());
      resize.observe(element.value);
    }
  }
  const points = sorted.value;
  const showSlider = points.length > 10;
  const axisLabels = new Map(points.map(point => [point.label, point.axisLabel ?? point.label]));
  chart.setOption({
    tooltip: { trigger: 'axis', confine: true, renderMode: 'richText' },
    grid: {
      top: 10, right: axisGutter + (showSlider ? sliderWidth + sliderRight : 0), bottom: 28, left: 12,
      outerBoundsMode: 'same', outerBoundsContain: 'axisLabel',
    },
    yAxis: { type: 'category', inverse: true, data: points.map(point => point.label),
      axisLabel: { width: 180, overflow: 'truncate', formatter: (label: string) => axisLabels.get(label) ?? label } },
    xAxis: { type: 'value' },
    dataZoom: showSlider
      ? [{ type: 'slider', yAxisIndex: 0, startValue: 0, endValue: 9, width: sliderWidth, right: sliderRight }]
      : [],
    series: [{ type: 'bar', name: props.title, data: points.map(point => point.value), barMaxWidth: 26 }],
  }, true);
}, { flush: 'post' });

onBeforeUnmount(() => {
  resize?.disconnect();
  chart?.dispose();
});
</script>

<template>
  <template v-if="points.length">
    <el-select v-model="order" aria-label="柱状图排序" size="small" class="bar-order">
      <el-option label="按数值降序" value="desc" />
      <el-option label="按数值升序" value="asc" />
      <el-option label="按名称排序" value="name" />
    </el-select>
    <div ref="element" class="bar-chart" role="img" :aria-label="title" />
    <BlacklakeChartAnalysis :chart="insight" :factory-id="factoryId" :metric="title"
      :basis="basis" :can-view-finance="cost" />
  </template>
</template>

<style scoped>
.bar-order { width: 145px; }
.bar-chart { height: 268px; }
</style>
