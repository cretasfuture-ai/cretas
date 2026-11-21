<script setup lang="ts">
import { computed } from 'vue';
import ChartInsightProvider from '@/views/smart-bi/components/ChartInsightProvider.vue';
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';

const props = defineProps<{
  chart: ChartWithMeta | null;
  factoryId: string;
  metric: string;
  basis: string;
  dataVersion: string;
  canViewFinance?: boolean;
}>();
const permissions = computed(() => ({ canViewFinance: props.canViewFinance === true }));
const chart = computed(() => props.factoryId ? props.chart : null);
</script>

<template>
  <section class="blacklake-chart-analysis" :aria-label="`${metric}图表分析`">
    <p class="basis">分析指标：{{ metric }}。{{ basis }}</p>
    <ChartInsightProvider :chart="chart" :factory-id="factoryId" :perms="permissions"
      require-tier2 depth="detailed" :data-version="dataVersion"
      :unavailable-text="chart ? '分析暂不可用，请稍后重试' : '暂无可分析数据'" />
  </section>
</template>

<style scoped>
.blacklake-chart-analysis { margin: 8px 0 12px; }
.basis { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
</style>
