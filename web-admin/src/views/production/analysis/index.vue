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
 * 🔴 业态门控原样保留 (合并前 3 个「analytics 系」tab 各自 hideForFactoryTypes:
 * ['RESTAURANT','FACTORY']，另 3 个「production 系」tab 无此限制) —— 路由级只能
 * 挂一份 meta，所以业态门控挪到这里按 tab 各自判断，见 productionAnalysisTab.ts
 * 头部说明。showAnalyticsTabs 为 false 时，那 3 个 el-tab-pane 连 DOM 都不渲染
 * (v-if，不是 CSS 隐藏)，与合并前「路由守卫直接 403」的最终用户可见效果一致——
 * 用户到达不了那 3 个视角的数据，只是拦截点从路由层挪到了组件层。
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
import ProductionReport from '@/views/analytics/production-report/index.vue';
import PAProduction from '@/views/production-analytics/ProductionAnalysis.vue';
import BomAchievement from '@/views/production/BomAchievementView.vue';
import ProcessIOComparison from '@/views/production/ProcessIOComparison.vue';
import LaborEfficiency from '@/views/production/labor-efficiency/index.vue';
import PAYieldCost from '@/views/production-analytics/M67YieldCost.vue';
import { resolveProductionAnalysisTab, canSeeFactoryTypeGatedTabs, type ProductionAnalysisTab } from '../productionAnalysisTab';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const showAnalyticsTabs = computed(() => canSeeFactoryTypeGatedTabs(authStore.factoryType));

const activeTab = ref<ProductionAnalysisTab>(resolveProductionAnalysisTab(route.query, authStore.factoryType));

watch(
  () => route.query.tab,
  () => { activeTab.value = resolveProductionAnalysisTab(route.query, authStore.factoryType); },
);

function syncQuery(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="production-analysis-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane v-if="showAnalyticsTabs" label="车间实时生产报表" name="workshop-report" lazy>
        <ProductionReport />
      </el-tab-pane>
      <el-tab-pane v-if="showAnalyticsTabs" label="生产数据分析" name="data-analysis" lazy>
        <PAProduction />
      </el-tab-pane>
      <el-tab-pane label="BOM达成率分析" name="bom-achievement" lazy>
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
</style>
