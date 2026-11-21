<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { applyFactoryChartResponsive } from '@/utils/factory-chart-responsive';

const props = withDefaults(defineProps<{
  option: Record<string, unknown>;
  height?: number;
  fullCategoryLabels?: boolean;
  productCategoryLabels?: boolean;
}>(), { height: 320 });
const emit = defineEmits<{
  click: [params: { name?: string; seriesName?: string; dataIndex?: number }];
  ready: [];
}>();
const chartEl = ref<HTMLElement | null>(null);
const viewportEl = ref<HTMLElement | null>(null);
const chartError = ref('');
const canvasHeight = ref(props.height);
const canvasMinWidth = ref(0);
type ChartInstance = { setOption: (option: Record<string, unknown>, opts?: Record<string, unknown>) => void; resize: () => void; dispose: () => void; on?: (event: string, handler: (params: { name?: string; seriesName?: string; dataIndex?: number }) => void) => void; off?: (event: string) => void };
let instance: ChartInstance | null = null;
let resizeObserver: ResizeObserver | null = null;
let renderVersion = 0;
let viewportWidth = 0;

type ChartOption = Record<string, unknown>;
const labelFontSize = 12;
const labelLineHeight = 18;

function record(value: unknown): ChartOption | undefined {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as ChartOption : undefined;
}

function productAxisLabels(labels: string[]): Map<string, string> {
  const separator = ' · ';
  const codesByName = new Map<string, Set<string>>();
  const products = labels.map(label => {
    // The final separator belongs to the source's code, even when the name
    // itself contains a middle dot. A code-only category remains usable.
    const divider = label.lastIndexOf(separator);
    const name = divider > 0 ? label.slice(0, divider) : label;
    const code = divider > 0 ? label.slice(divider + separator.length) : '';
    const codes = codesByName.get(name) ?? new Set<string>();
    codes.add(code);
    codesByName.set(name, codes);
    return { label, name, code, codes };
  });
  return new Map(products.map(({ label, name, code, codes }) => {
    if (!code || codes.size === 1) return [label, name];
    const peers = [...codes].filter(other => other !== code);
    const characters = Array.from(code);
    let length = 1;
    while (length < characters.length && peers.some(other => other.endsWith(characters.slice(-length).join('')))) {
      length += 1;
    }
    return [label, `${name}（${characters.slice(-length).join('')}）`];
  }));
}

function wrapCategories(option: ChartOption, availableWidth: number) {
  let height = props.height;
  let minWidth = 0;
  if (!availableWidth || Array.isArray(option.grid)) return { height, minWidth };

  for (const key of ['yAxis', 'xAxis']) {
    const axis = record(option[key]);
    const axisLabel = record(axis?.axisLabel);
    if (!axis || axis.type !== 'category' || axisLabel?.show === false || !Array.isArray(axis.data)
      || !axis.data.length || !axis.data.every(label => typeof label === 'string')) continue;
    const labels: string[] = axis.data;
    const horizontal = key === 'xAxis';
    if (horizontal && labels.every(label => /^\d{4}[-/]\d{2}[-/]\d{2}$/.test(label))) continue;

    const fontSize = typeof axisLabel?.fontSize === 'number' ? axisLabel.fontSize : labelFontSize;
    const lineHeight = Math.max(labelLineHeight, fontSize + 4);
    const displayLabels = props.productCategoryLabels ? productAxisLabels(labels) : undefined;
    const labelLengths = labels.map(label =>
      (displayLabels?.get(label) ?? label).split('\n').map(line => Array.from(line).length));
    const longest = Math.max(...labelLengths.map(lines => Math.max(...lines)));
    // Keep room for the plot on phones. Dense horizontal axes can scroll
    // inside the card, with a slot wide enough to wrap each tick's text.
    if (horizontal) minWidth = Math.ceil(labels.length * 24 / 0.55);
    const width = horizontal
      ? Math.floor(Math.max(availableWidth, minWidth) * 0.55 / labels.length) - 4
      : Math.min(200, Math.floor(availableWidth * 0.4), longest * fontSize + 4);
    const columns = Math.max(1, Math.floor(width / fontSize));
    const maxLines = Math.max(...labelLengths.map(lengths =>
      lengths.reduce((lines, length) => lines + Math.max(1, Math.ceil(length / columns)), 0)
    ));
    height = horizontal
      ? height + (maxLines - 1) * lineHeight
      : Math.max(height, labels.length * (maxLines * lineHeight + 8) + 112);
    option[key] = {
      ...axis,
      axisLabel: {
        ...axisLabel, interval: 0, hideOverlap: false, overflow: 'breakAll',
        width, fontSize, lineHeight, rotate: 0,
        // Keep axis.data intact for tooltip headers and point drill-down.
        ...(displayLabels && { formatter: (label: string) => displayLabels.get(label) ?? label }),
      },
    };
    option.grid = {
      ...record(option.grid), containLabel: true,
      ...(!horizontal && axis.position !== 'right' ? { left: 12 } : {}),
    };
  }
  return { height, minWidth };
}

async function render() {
  const version = ++renderVersion;
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
    if (!chartEl.value || version !== renderVersion) return;
    const processed = processEChartsOptions(props.option);
    viewportWidth = viewportEl.value?.clientWidth ?? 0;
    const responsive = applyFactoryChartResponsive(processed, viewportWidth);
    const layout = props.fullCategoryLabels
      ? wrapCategories(responsive, viewportWidth)
      : { height: props.height, minWidth: 0 };
    canvasHeight.value = layout.height;
    canvasMinWidth.value = layout.minWidth;
    await nextTick();
    if (!chartEl.value || version !== renderVersion) return;
    if (!instance) instance = echarts.init(chartEl.value, 'cretas') as unknown as ChartInstance;
    instance.resize();
    instance.setOption(responsive, { notMerge: true });
    instance.off?.('click');
    instance.on?.('click', (params) => emit('click', params));
    if (version === renderVersion) emit('ready');
  } catch {
    if (version !== renderVersion) return;
    instance?.dispose();
    instance = null;
    chartError.value = '图表绘制失败，请刷新重试；下方规则结论仍可核对';
  }
}
onMounted(async () => {
  await nextTick(); void render();
  if (viewportEl.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      if (viewportEl.value && viewportEl.value.clientWidth !== viewportWidth) void render();
    });
    resizeObserver.observe(viewportEl.value);
  }
});
watch(() => [props.option, props.height, props.fullCategoryLabels, props.productCategoryLabels],
  () => { void render(); }, { deep: true });
onBeforeUnmount(() => { renderVersion += 1; resizeObserver?.disconnect(); resizeObserver = null; instance?.dispose(); instance = null; });
</script>
<template>
  <div ref="viewportEl" :class="{ 'factory-chart-viewport--scroll': canvasMinWidth > 0 }">
    <p v-if="chartError" role="alert">{{ chartError }}</p>
    <div v-show="!chartError" ref="chartEl" class="factory-chart-canvas"
      :style="{ height: `${canvasHeight}px`, minWidth: canvasMinWidth ? `${canvasMinWidth}px` : undefined }"
      role="img" aria-label="工厂推荐图表" />
  </div>
</template>
<style scoped>
.factory-chart-viewport--scroll { overflow-x: auto; }
.factory-chart-canvas { width: 100%; min-height: 220px; }
</style>
