<script setup lang="ts">
/**
 * 领料 —— 生产管理菜单收拢 (owner 拍板, 2026-09-02, LIUSHANMEN)
 *
 * 合并此前 2 个各自独立的物料页为一个入口，用 el-tabs 承载:
 *   领料单 (factory/material-requisitions/list.vue) /
 *   退料记录 (factory/material-returns/list.vue)
 *
 * 每个 tab 内嵌的是**原样未改动**的页面组件 —— 本 hub 只做容器编排，不改任何一个
 * 子视图的查询逻辑/接口/字段 (⛔ views/factory/material-requisitions/** 内容不改，
 * 只搬菜单入口)。
 *
 * 旧路径仍可直接访问 (redirect 保书签，见 router/index.ts +
 * router/productionHubRedirect.ts): '/production/material-requisitions'
 * '/production/material-returns'
 */
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import FactoryMaterialRequisitions from '@/views/factory/material-requisitions/list.vue';
import ProductionMaterialReturns from '@/views/factory/material-returns/list.vue';
import { resolveMaterialFlowTab, type MaterialFlowTab } from '../materialFlowTab';

const route = useRoute();
const router = useRouter();

const activeTab = ref<MaterialFlowTab>(resolveMaterialFlowTab(route.query));

watch(
  () => route.query.tab,
  () => { activeTab.value = resolveMaterialFlowTab(route.query); },
);

function syncQuery(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="material-flow-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane label="领料单" name="requisitions" lazy>
        <FactoryMaterialRequisitions />
      </el-tab-pane>
      <el-tab-pane label="退料记录" name="returns" lazy>
        <ProductionMaterialReturns />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.material-flow-hub {
  padding: 0;
}

.hub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
