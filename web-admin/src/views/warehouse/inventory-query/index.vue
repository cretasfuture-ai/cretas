<script setup lang="ts">
/**
 * 库存查询 —— 仓储「库存盘点」菜单收拢 (owner 拍板, 2026-09-01, LIUSHANMEN)
 *
 * owner 原话:「这个分仓库存查询跟总库存查询还有库存批次查询我觉得可以放在一起，
 * 然后半成品库存也可以放在一起。」
 *
 * 合并此前 4 个各自独立的库存查询页为一个入口，用 el-tabs 承载:
 *   分仓库存查询 (跨仓 dropdown 查询) / 总库存查询 (按物料聚合) /
 *   库存批次查询 (批次级 + 工作流/AI摘要) / 半成品库存 (半成品重量快照)
 *
 * 每个 tab 内嵌的是**原样未改动**的页面组件 —— 本 hub 只做容器编排，不改任何一个
 * 子视图的查询逻辑/接口/字段。四个视角的行为与合并前逐字节相同 (仍是同一份源码，
 * 同一个组件实例)。
 *
 * 旧路径仍可直接访问 (redirect 保书签，见 router/index.ts + warehouseHubRedirect.ts):
 *   '/inventory/by-warehouse' '/warehouse/inventory-total'
 *   '/warehouse/inventory' '/warehouse/semi-finished'
 */
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import InventoryByWarehouse from '@/views/inventory/by-warehouse/index.vue';
import WarehouseInventoryTotal from '@/views/warehouse/inventory-total/index.vue';
import WarehouseInventoryBatch from '@/views/warehouse/inventory/index.vue';
import WarehouseSemiFinished from '@/views/warehouse/semi-finished/list.vue';
import FinishedGoodsInventory from '@/views/sales/finished-goods/list.vue';
import { resolveInventoryQueryTab, type InventoryQueryTab } from '../inventoryQueryTab';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
// Preserve the existing finished-goods route's restaurant exclusion.
const showFinishedGoods = computed(() => authStore.factoryType !== 'RESTAURANT');
function resolveVisibleTab(): InventoryQueryTab {
  const tab = resolveInventoryQueryTab(route.query);
  return tab === 'finished-goods' && !showFinishedGoods.value ? 'by-warehouse' : tab;
}

const activeTab = ref<InventoryQueryTab>(resolveVisibleTab());

// 外部 query 变化 (e.g. 旧路径 redirect 带入 ?tab=) → 同步激活 tab。
watch(
  () => [route.query.tab, showFinishedGoods.value],
  () => { activeTab.value = resolveVisibleTab(); },
);

function syncQuery(name: string | number) {
  // replace 而非 push — tab 切换不污染浏览器返回栈 (与 BusinessAnalysisHub 同模式)。
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="inventory-query-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane label="分仓库存查询" name="by-warehouse" lazy>
        <InventoryByWarehouse />
      </el-tab-pane>
      <el-tab-pane label="总库存查询" name="total" lazy>
        <WarehouseInventoryTotal />
      </el-tab-pane>
      <el-tab-pane label="库存批次查询" name="batch" lazy>
        <WarehouseInventoryBatch />
      </el-tab-pane>
      <el-tab-pane label="半成品库存" name="semi-finished" lazy>
        <WarehouseSemiFinished />
      </el-tab-pane>
      <el-tab-pane v-if="showFinishedGoods" label="成品库存" name="finished-goods" lazy>
        <FinishedGoodsInventory />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.inventory-query-hub {
  padding: 0;
}

.hub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
