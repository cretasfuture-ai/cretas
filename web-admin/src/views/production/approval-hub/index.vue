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
 *
 * 🔴 2026-09-02 二次修正 (coordinator 复核 #487 指出的回归): 「报工审批」tab 本身
 * 按角色门控 (APPROVAL_TAB_ROLES, 见 approvalQueryTab.ts) —— dispatcher 这类
 * 有 production 模块权限但不在 9 角色白名单里的角色，进入 hub 后**看不到「报工审批」
 * tab，但仍看得到「撤回审批」tab**(与合并前逐字节等价)。菜单项本身的可见性
 * (menuConfig.ts 的 module:'production'，不再叠 roles[]) 现在等于两个 tab 可见性的
 * 并集 —— 「撤回审批」的可见集合已经是「报工审批」9 角色的超集，菜单层不需要
 * 再收窄。默认激活 tab 按角色算 (resolveApprovalQueryTab 的第二个参数)，
 * ⛔ 不会落到一个被隐藏的空白 tab 上。
 */
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { usePermissionStore } from '@/store/modules/permission';
import ProductionApproval from '@/views/production/approval/list.vue';
import ProductionReversals from '@/views/production/reversals/list.vue';
import { resolveApprovalQueryTab, canSeeApprovalTab, type ApprovalQueryTab } from '../approvalQueryTab';

const route = useRoute();
const router = useRouter();
const permissionStore = usePermissionStore();

const showApprovalTab = computed(() => canSeeApprovalTab(permissionStore.currentRole));

const activeTab = ref<ApprovalQueryTab>(resolveApprovalQueryTab(route.query, permissionStore.currentRole));

watch(
  () => route.query.tab,
  () => { activeTab.value = resolveApprovalQueryTab(route.query, permissionStore.currentRole); },
);

function syncQuery(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } });
}
</script>

<template>
  <div class="approval-hub">
    <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
      <el-tab-pane v-if="showApprovalTab" label="报工审批" name="approval" lazy>
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
