<script setup lang="ts">
/**
 * 报工审批 —— 生产管理菜单收拢 (owner 拍板, 2026-09-02, LIUSHANMEN)
 *
 * 合并此前 2 个各自独立的审批页为一个入口，用 el-tabs 承载:
 *   报工审批 (ProcessWorkReportingController#getPendingApprovals) /
 *   撤回审批 (ReportReversalController#listReversals)
 *
 * 每个 tab 内嵌的是**原样未改动**的页面组件 —— 本 hub 只做容器编排，不改任何一个
 * 子视图的查询逻辑/接口/字段。两个视角的行为与合并前逐字节相同。
 *
 * ⛔ 路径不变，仍是 /production/approval —— 见 approvalQueryTab.ts 头部说明
 * (reportApprovalMenuMatchesBackendRoles.spec.ts 按这个精确路径查找菜单项)。
 * 旧路径 '/production/reversals' 重定向进来 (保书签，见 router/index.ts +
 * router/productionHubRedirect.ts)。
 */
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ProductionApproval from '@/views/production/approval/list.vue';
import ProductionReversals from '@/views/production/reversals/list.vue';
import { resolveApprovalQueryTab, type ApprovalQueryTab } from '../approvalQueryTab';

const route = useRoute();
const router = useRouter();

const activeTab = ref<ApprovalQueryTab>(resolveApprovalQueryTab(route.query));

watch(
  () => route.query.tab,
  () => { activeTab.value = resolveApprovalQueryTab(route.query); },
);

function syncQuery(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="approval-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane label="报工审批" name="approval" lazy>
        <ProductionApproval />
      </el-tab-pane>
      <el-tab-pane label="撤回审批" name="reversal" lazy>
        <ProductionReversals />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.approval-hub {
  padding: 0;
}

.hub-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
</style>
