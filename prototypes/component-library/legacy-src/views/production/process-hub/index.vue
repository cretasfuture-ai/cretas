<script setup lang="ts">
/**
 * 工序 —— 生产管理菜单收拢 (owner 拍板, 2026-09-02, LIUSHANMEN)
 *
 * 合并此前 2 个各自独立的配置页为一个入口，用 el-tabs 承载:
 *   工序管理 (system/work-processes/index.vue) /
 *   产品-工序配置 (system/product-processes/index.vue)
 *
 * 每个 tab 内嵌的是**原样未改动**的页面组件 —— 本 hub 只做容器编排，不改任何一个
 * 子视图的查询逻辑/接口/字段。⛔ 不碰 WorkProcess 实体/Service/DTO (与在飞的
 * FEATURE-F006-R2-WORKPROCESS-DEDUP-001 不冲突，那条任务锁的是后端代码)。
 *
 * 旧路径仍可直接访问 (redirect 保书签，见 router/index.ts +
 * router/productionHubRedirect.ts): '/system/work-processes' '/system/product-processes'
 */
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import WorkProcesses from '@/views/system/work-processes/index.vue';
import ProductProcesses from '@/views/system/product-processes/index.vue';
import { resolveProcessConfigTab, type ProcessConfigTab } from '../processConfigTab';

const route = useRoute();
const router = useRouter();

const activeTab = ref<ProcessConfigTab>(resolveProcessConfigTab(route.query));

watch(
  () => route.query.tab,
  () => { activeTab.value = resolveProcessConfigTab(route.query); },
);

function syncQuery(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="process-config-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane label="工序管理" name="work-processes" lazy>
        <WorkProcesses />
      </el-tab-pane>
      <el-tab-pane label="产品-工序配置" name="product-processes" lazy>
        <ProductProcesses />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.process-config-hub {
  padding: 0;
}

.hub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
