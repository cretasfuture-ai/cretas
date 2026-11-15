<script setup lang="ts">
/**
 * 生产看板 —— 生产管理菜单收拢 (owner 拍板, 2026-09-02, LIUSHANMEN)
 *
 * 合并此前 3 个各自独立的看板页为一个入口，用 el-tabs 承载:
 *   未完成计划队列 (production/plans/PendingQueue.vue) /
 *   备货看板 (production/restock-board/index.vue) /
 *   交货预警 (production/delivery-warnings/list.vue)
 *
 * 每个 tab 内嵌的是**原样未改动**的页面组件 —— 本 hub 只做容器编排，不改任何一个
 * 子视图的查询逻辑/接口/字段。备货看板自己读 route.query.startDate/endDate —— 与
 * inventory-query hub 的「盘点」子视图同理，useRoute() 在子组件里仍读到同一个 route
 * (本 hub 不是独立的嵌套路由，只是容器组件)，深链参数原样透传。
 *
 * 旧路径仍可直接访问 (redirect 保书签，见 router/index.ts +
 * router/productionHubRedirect.ts): '/production/pending-queue'
 * '/production/restock-board' '/production/delivery-warnings'
 */
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ProductionPendingQueue from '@/views/production/plans/PendingQueue.vue';
import RestockBoard from '@/views/production/restock-board/index.vue';
import ProductionDeliveryWarnings from '@/views/production/delivery-warnings/list.vue';
import { resolveProductionKanbanTab, type ProductionKanbanTab } from '../productionKanbanTab';

const route = useRoute();
const router = useRouter();

const activeTab = ref<ProductionKanbanTab>(resolveProductionKanbanTab(route.query));

watch(
  () => route.query.tab,
  () => { activeTab.value = resolveProductionKanbanTab(route.query); },
);

function syncQuery(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="production-kanban-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane label="未完成计划队列" name="pending-queue" lazy>
        <ProductionPendingQueue />
      </el-tab-pane>
      <el-tab-pane label="备货看板" name="restock-board" lazy>
        <RestockBoard />
      </el-tab-pane>
      <el-tab-pane label="交货预警" name="delivery-warnings" lazy>
        <ProductionDeliveryWarnings />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.production-kanban-hub {
  padding: 0;
}

.hub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
