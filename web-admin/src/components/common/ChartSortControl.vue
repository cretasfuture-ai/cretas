<script setup lang="ts">
/**
 * 柱状图排列方式切换（问题 50）：数值降序 / 数值升序 / 按名称 / 原始顺序。
 * 只管选哪种，排序本身用 utils/chartSort 的 sortChartRows，图表组件自己 setOption。
 */
import { CHART_SORT_OPTIONS, type ChartSortMode } from '@/utils/chartSort';

const model = defineModel<ChartSortMode>({ required: true });
withDefaults(defineProps<{ size?: 'small' | 'default' }>(), { size: 'small' });
</script>

<template>
  <el-radio-group v-model="model" :size="size" class="chart-sort-control" aria-label="排列方式">
    <el-radio-button v-for="opt in CHART_SORT_OPTIONS" :key="opt.value" :value="opt.value">
      {{ opt.label }}
    </el-radio-button>
  </el-radio-group>
</template>

<style scoped>
.chart-sort-control {
  flex-wrap: wrap;
}
</style>
