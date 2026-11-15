<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'
import { state } from '../state'
const emit = defineEmits<{ select: [value: { name: string; seriesName: string; dataIndex: number }] }>()
const props = defineProps<{ option: EChartsOption; height?: number; label: string }>()
const root = ref<HTMLElement>()
let chart: echarts.ECharts | undefined, observer: ResizeObserver | undefined
function render() { chart?.setOption({ ...props.option, animation: state.motion !== 'off', animationDuration: state.duration }, true) }
onMounted(() => { if (!root.value) return; chart = echarts.init(root.value); chart.on('click', params => emit('select', { name: String(params.name || ''), seriesName: String(params.seriesName || ''), dataIndex: params.dataIndex ?? 0 })); render(); observer = new ResizeObserver(() => chart?.resize()); observer.observe(root.value) })
watch(() => [props.option, state.motion, state.duration], render, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose(); chart = undefined })
</script>
<template><div ref="root" class="chart-panel" role="img" :aria-label="label" :style="{ height: `${height || 290}px` }" /></template>
