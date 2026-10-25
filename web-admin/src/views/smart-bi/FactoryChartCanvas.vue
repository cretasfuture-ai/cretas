<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';

const props = withDefaults(defineProps<{ option: Record<string, unknown>; height?: number }>(), { height: 320 });
const chartEl = ref<HTMLElement | null>(null);
const chartError = ref('');
type ChartInstance = { setOption: (option: Record<string, unknown>, opts?: Record<string, unknown>) => void; resize: () => void; dispose: () => void };
let instance: ChartInstance | null = null;
let resizeObserver: ResizeObserver | null = null;

async function render() {
  chartError.value = '';
  try {
  if (!chartEl.value || !props.option || Object.keys(props.option).length === 0) return;
  const [{ default: echarts }, { processEChartsOptions }] = await Promise.all([
    import('@/utils/echarts'), import('@/utils/echarts-fmt'),
  ]);
  if ('parallelAxis' in props.option) {
    const { ParallelChart } = await import('echarts/charts');
    echarts.use([ParallelChart]);
  }
  if (!chartEl.value) return;
  if (!instance) instance = echarts.init(chartEl.value, 'cretas') as unknown as ChartInstance;
  instance.setOption(processEChartsOptions(props.option), { notMerge: true });
  instance.resize();
  } catch {
    instance?.dispose();
    instance = null;
    chartError.value = '图表绘制失败，请刷新重试；下方规则结论仍可核对';
  }
}
onMounted(async () => {
  await nextTick(); void render();
  if (chartEl.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => instance?.resize()); resizeObserver.observe(chartEl.value);
  }
});
watch(() => props.option, () => { void render(); }, { deep: true });
onBeforeUnmount(() => { resizeObserver?.disconnect(); resizeObserver = null; instance?.dispose(); instance = null; });
</script>
<template><div><p v-if="chartError" role="alert">{{ chartError }}</p><div v-show="!chartError" ref="chartEl" class="factory-chart-canvas" :style="{ height: `${height}px` }" role="img" aria-label="工厂推荐图表" /></div></template>
<style scoped>.factory-chart-canvas { width: 100%; min-height: 220px; }</style>
