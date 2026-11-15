<script setup lang="ts">
/**
 * 盘点 —— 仓储「库存盘点」菜单收拢 (owner 拍板, 2026-09-01, LIUSHANMEN)
 *
 * owner 原话:「半成品盘点跟盘点与期初库存可以放在一起。」
 *
 * 合并此前 2 个各自独立的盘点页为一个入口，用 el-tabs 承载:
 *   盘点与期初库存 (SP7 六扇门 ERP-lite 盘点任务管理) /
 *   半成品盘点 (半成品 WIP 周期盘点校准)
 *
 * 每个 tab 内嵌的是**原样未改动**的页面组件 —— 本 hub 只做容器编排，不改任何一个
 * 子视图的查询逻辑/接口/字段。
 *
 * useRoute() 在子组件里仍读到同一个 route (本 hub 不是独立的嵌套路由，只是容器
 * 组件)，所以「盘点与期初库存」子视图自己读的 `route.query.openEntry` /
 * `route.query.stocktakeId` 深链参数原样透传 —— 见 warehouseHubRedirect.ts 的
 * `{ ...to.query, tab }` 保留了全部 incoming query。
 *
 * 旧路径仍可直接访问 (redirect 保书签，见 router/index.ts + warehouseHubRedirect.ts):
 *   '/warehouse/stocktakes' '/warehouse/semi-finished-stocktakes'
 */
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import WarehouseStocktakes from '@/views/warehouse/stocktakes/index.vue';
import WarehouseSemiFinishedStocktakes from '@/views/warehouse/semi-finished-stocktakes/index.vue';
import { resolveStocktakeQueryTab, type StocktakeQueryTab } from '../stocktakeQueryTab';

const route = useRoute();
const router = useRouter();

const activeTab = ref<StocktakeQueryTab>(resolveStocktakeQueryTab(route.query));

// 外部 query 变化 (e.g. 旧路径 redirect 带入 ?tab=) → 同步激活 tab。
watch(
  () => route.query.tab,
  () => { activeTab.value = resolveStocktakeQueryTab(route.query); },
);

function syncQuery(name: string | number) {
  // replace 而非 push — tab 切换不污染浏览器返回栈。
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="stocktake-query-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane label="盘点与期初库存" name="main" lazy>
        <WarehouseStocktakes />
      </el-tab-pane>
      <el-tab-pane label="半成品盘点" name="semi-finished" lazy>
        <WarehouseSemiFinishedStocktakes />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.stocktake-query-hub {
  padding: 0;
}

.hub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
