<script setup lang="ts">
/**
 * 生产分析 —— 生产管理菜单收拢 (owner 拍板, 2026-09-02, LIUSHANMEN)
 *
 * 合并此前 6 个各自独立的分析页为一个入口，用 el-tabs 承载:
 *   车间实时生产报表 / 生产数据分析 / BOM达成率分析 / 工序投入产出对比 /
 *   人效双口径对比 / 成品出厂核算
 *
 * 每个 tab 内嵌的是**原样未改动**的页面组件 —— 本 hub 只做容器编排，不改任何一个
 * 子视图的查询逻辑/接口/字段。
 *
 * 车间报表和成品出厂核算沿用原有业态门控。生产数据分析也向已确认有外部生产
 * 来源的工厂开放，见 productionAnalysisTab.ts。
 *
 * 旧路径仍可直接访问 (redirect 保书签，见 router/index.ts +
 * router/productionHubRedirect.ts): '/analytics/production-report'
 * '/production-analytics/production' '/production/bom-achievement'
 * '/production/process-io' '/production/labor-efficiency'
 * '/production-analytics/yield-cost'
 */
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { useExternalProduction } from '@/composables/useProductionGold';
import ProductionReport from '@/views/analytics/production-report/index.vue';
import PAProduction from '@/views/production-analytics/ProductionAnalysis.vue';
import BomAchievement from '@/views/production/BomAchievementView.vue';
import ProcessIOComparison from '@/views/production/ProcessIOComparison.vue';
import LaborEfficiency from '@/views/production/labor-efficiency/index.vue';
import PAYieldCost from '@/views/production-analytics/M67YieldCost.vue';
import {
  canSeeFactoryTypeGatedTabs, canSeeProductionDataAnalysis, resolveProductionAnalysisTab,
} from '../productionAnalysisTab';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const factoryType = computed(() => authStore.factoryType);
const showAnalyticsTabs = computed(() => canSeeFactoryTypeGatedTabs(factoryType.value));
const external = useExternalProduction(factoryId, true);
const externalSource = ref<'external' | 'native' | 'failed' | null>(null);

watch([factoryId, showAnalyticsTabs], async ([id, showAll], _, onCleanup) => {
  externalSource.value = null;
  if (!id || showAll) return;

  let cancelled = false;
  onCleanup(() => { cancelled = true; });
  const source = await external.load({});
  if (!cancelled && source !== 'obsolete') externalSource.value = source;
}, { immediate: true });

const hasExternalSource = computed(() => externalSource.value === 'external');
const showDataAnalysis = computed(() => canSeeProductionDataAnalysis(factoryType.value, hasExternalSource.value));
const activeTab = ref(resolveProductionAnalysisTab(route.query, factoryType.value));
watch(
  [() => route.query.tab, factoryType, hasExternalSource],
  () => { activeTab.value = resolveProductionAnalysisTab(route.query, factoryType.value, hasExternalSource.value); },
);

function syncQuery(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="production-analysis-hub">
    <el-alert
      v-if="!showAnalyticsTabs && externalSource === 'failed'"
      class="external-source-warning"
      title="暂时无法核实外部生产数据"
      type="warning"
      :closable="false"
      show-icon
    />
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane v-if="showAnalyticsTabs" label="车间实时生产报表" name="workshop-report" lazy>
        <ProductionReport />
      </el-tab-pane>
      <el-tab-pane v-if="showDataAnalysis" label="生产数据分析" name="data-analysis" lazy>
        <PAProduction />
      </el-tab-pane>
      <el-tab-pane label="BOM用量对比" name="bom-achievement" lazy>
        <BomAchievement />
      </el-tab-pane>
      <el-tab-pane label="工序投入产出对比" name="process-io" lazy>
        <ProcessIOComparison />
      </el-tab-pane>
      <el-tab-pane label="人效双口径对比" name="labor-efficiency" lazy>
        <LaborEfficiency />
      </el-tab-pane>
      <el-tab-pane v-if="showAnalyticsTabs" label="成品出厂核算" name="yield-cost" lazy>
        <PAYieldCost />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.production-analysis-hub {
  padding: 0;
}

.hub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.external-source-warning {
  margin-bottom: 12px;
}
</style>
