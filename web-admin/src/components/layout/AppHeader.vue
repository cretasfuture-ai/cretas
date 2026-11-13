<script setup lang="ts">
import { computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { usePlatformFactoryContextStore } from '@/store/modules/platformFactoryContext';
import { factoryMenuForType, groupedMenuConfig, menuTrail } from './menuConfig';
import { usePinnedMenu } from './pinnedMenu';
import { Star, StarFilled } from '@element-plus/icons-vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { Fold, Expand, User, SwitchButton, Refresh, Moon, Sunny, Box, ArrowLeft, VideoPlay } from '@element-plus/icons-vue';
import ReminderBell from '@/components/notifications/ReminderBell.vue';
import AlertNotificationBell from '@/components/notifications/AlertNotificationBell.vue';

const router = useRouter();
const route = useRoute();
const appStore = useAppStore();
const authStore = useAuthStore();
const factoryView = usePlatformFactoryContextStore();

// 面包屑
//
// 默认由 route.matched 的 meta.title 链拼出来, 即**跟着 URL 前缀走**。
// 但少数页面的 URL 前缀与它在侧边栏里的归属不一致 —— 例如 /system/users「账号管理」
// 挂在侧边栏的「人事管理」下 (建号/改角色是 HR 的活), URL 前缀是历史遗留。
// 这时面包屑跟 URL 走就会和侧边栏各说各话: owner 2026-09-01 在 LIUSHANMEN 上
// 按「系统管理」找账号管理找不到, 从人事管理点进去后面包屑却写着「系统管理」。
//
// ⇒ 路由可以用 meta.breadcrumbParent 显式声明「我在导航里的上一级叫什么」,
//   面包屑以它为准。⛔ 不改 URL(存量深链/书签), 也不搬菜单(业务归属是对的)。
// P2「常用」：当前页是原型分组侧栏里的一个菜单项时，可在面包屑旁加入/移出常用（存本机浏览器）。
const pinnedMenu = usePinnedMenu();
// 只有「当前路由正好是侧栏里看得见的一个菜单叶子」才能钉（详情子路由不算；看不见的不算）。
const pinnablePath = computed<string | null>(() => {
  if (authStore.isPlatform || authStore.currentRole === 'finance_manager' || !pinnedMenu.currentKey.value) return null;
  if (factoryMenuForType(factoryView.context?.factoryType || authStore.factoryType) !== groupedMenuConfig) return null;
  const trail = menuTrail(groupedMenuConfig, route.fullPath);
  const leaf = trail?.[trail.length - 1];
  if (!leaf || leaf.path.split(/[?#]/)[0] !== route.path) return null;
  return pinnedMenu.canPin(leaf.path) ? leaf.path : null;
});
const currentPinned = computed(() => !!pinnablePath.value && pinnedMenu.isPinned(pinnablePath.value));

const breadcrumbs = computed(() => {
  // P2：原型分组侧栏下，面包屑按菜单树取「组 › 项 › 子项」，与用户在侧栏里点的名字一致。
  // 平台侧栏、财务主管专用菜单、餐饮/物流（原分组）仍走路由 meta.title。
  const useGroupedLayout = !authStore.isPlatform
    && authStore.currentRole !== 'finance_manager'
    && factoryMenuForType(factoryView.context?.factoryType || authStore.factoryType) === groupedMenuConfig;
  const trail = useGroupedLayout ? menuTrail(groupedMenuConfig, route.fullPath) : null;
  if (trail && trail.length > 1) {
    const leaf = trail[trail.length - 1];
    const isLeafItself = leaf.path.split(/[?#]/)[0] === route.path;
    // 分组/展示分组不是路由，不可点
    const crumbs = trail.slice(0, -1).map((node) => ({ path: '', title: node.title }));
    if (isLeafItself) return [...crumbs, { path: '', title: leaf.title }];
    // 详情等子路由（/sales/orders/:id）：菜单那一级可点回列表，末项补当前路由标题（审查 #1692 第 1 条）
    const routeTitle = [...route.matched].reverse().find((r) => typeof r.meta.title === 'string' && r.meta.title)?.meta.title as string | undefined;
    const tail = routeTitle && routeTitle !== leaf.title ? [{ path: '', title: routeTitle }] : [];
    return [...crumbs, { path: leaf.path, title: leaf.title }, ...tail];
  }
  const matched = route.matched.filter(r => r.meta.title);
  const override = route.meta.breadcrumbParent as string | undefined;
  if (!override) {
    return matched.map(r => ({ path: r.path, title: r.meta.title as string }));
  }
  // 末级(当前页)保留, 它上面的那一级换成声明的归属。
  const leaf = matched[matched.length - 1];
  return [
    { path: '', title: override },
    { path: leaf.path, title: leaf.meta.title as string },
  ];
});

// 用户信息
const userInfo = computed(() => ({
  name: authStore.user?.fullName || authStore.user?.username || '用户',
  role: authStore.roleMetadata?.displayName || '未知角色',
  factoryId: authStore.factoryId
}));
const showF006Demo = computed(() => authStore.factoryId === 'F006' && authStore.currentRole === 'factory_super_admin' && Number(authStore.user?.id) === 1309);

// 切换侧边栏（桌面折叠 / 移动端打开抽屉）
function toggleSidebar() {
  if (appStore.isMobile) {
    appStore.openMobileMenu();
  } else {
    appStore.toggleSidebar();
  }
}

// 刷新页面
function handleRefresh() {
  router.go(0);
}

function returnToPlatform(): void {
  factoryView.leaveFactoryView();
  void router.push('/platform/factories');
}

// 退出登录
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    });

    await authStore.logout();
    ElMessage.success('已退出登录');
    router.push('/login');
  } catch (e) {
    // 用户取消
  }
}
</script>

<template>
  <header class="app-header" :class="{ 'is-factory-view': factoryView.isViewingFactory }">
    <div class="header-left">
      <!-- 折叠/汉堡按钮 -->
      <el-icon class="collapse-btn" @click="toggleSidebar">
        <Fold v-if="!appStore.sidebarCollapsed && !appStore.isMobile" />
        <Expand v-else />
      </el-icon>

      <!-- 面包屑（移动端隐藏） -->
      <el-breadcrumb v-if="!appStore.isMobile" separator="/">
        <el-breadcrumb-item
          v-for="(item, index) in breadcrumbs"
          :key="`${index}-${item.title}`"
          :to="item.path || undefined"
        >
          {{ item.title }}
        </el-breadcrumb-item>
      </el-breadcrumb>
      <el-tooltip v-if="!appStore.isMobile && pinnablePath" :content="currentPinned ? '从常用移除' : '加入常用（保存在这台电脑的浏览器里）'" placement="bottom">
        <button
          type="button"
          class="pin-toggle"
          :class="{ 'is-pinned': currentPinned }"
          :aria-pressed="currentPinned"
          :aria-label="currentPinned ? '从常用移除' : '加入常用'"
          @click="pinnedMenu.toggle(pinnablePath!)"
        >
          <el-icon><StarFilled v-if="currentPinned" /><Star v-else /></el-icon>
        </button>
      </el-tooltip>
    </div>

    <div class="header-right">
      <div v-if="factoryView.isViewingFactory" class="factory-view-status" role="status">
        <el-icon><Box /></el-icon>
        <span><strong>{{ factoryView.factoryName }}</strong> · 只读查看</span>
        <el-tag size="small" type="warning">平台身份保留</el-tag>
        <el-button size="small" type="primary" plain @click="returnToPlatform">
          <el-icon><ArrowLeft /></el-icon> 返回平台
        </el-button>
      </div>
      <el-tooltip v-if="showF006Demo" content="流程演示" placement="bottom">
        <el-button class="demo-flow-entry" type="primary" plain size="small" @click="router.push('/demo-flow')">
          <el-icon><VideoPlay /></el-icon><span>流程演示</span>
        </el-button>
      </el-tooltip>
      <!-- 暗色模式切换 -->
      <el-tooltip :content="appStore.theme === 'light' ? '暗色模式' : '亮色模式'" placement="bottom">
        <el-icon class="header-action" @click="appStore.toggleTheme()">
          <Moon v-if="appStore.theme === 'light'" />
          <Sunny v-else />
        </el-icon>
      </el-tooltip>

      <!-- 2026-07-11: 站内通知 bell (含餐饮经营体检预警/反回扣推送) -->
      <AlertNotificationBell v-if="!factoryView.isViewingFactory" />

      <!-- Sprint 4 W2 S-REMIND-1: 我的提醒 bell badge -->
      <ReminderBell v-if="!factoryView.isViewingFactory" />

      <!-- 刷新按钮 -->
      <el-tooltip content="刷新页面" placement="bottom">
        <el-icon class="header-action" @click="handleRefresh">
          <Refresh />
        </el-icon>
      </el-tooltip>

      <!-- 用户下拉菜单 -->
      <el-dropdown trigger="click">
        <div class="user-info">
          <el-avatar :size="32" :icon="User" />
          <div class="user-detail">
            <span class="user-name">{{ userInfo.name }}</span>
            <span class="user-role">{{ userInfo.role }}</span>
          </div>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="userInfo.factoryId">
              <el-icon><Box /></el-icon>
              工厂: {{ userInfo.factoryId }}
            </el-dropdown-item>
            <el-dropdown-item divided @click="handleLogout">
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style lang="scss" scoped>
.app-header {
  height: 64px;
  background-color: var(--color-bg-card, rgba(255, 255, 255, 0.85));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border-color-light, #EDF2F7);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  position: sticky;
  top: 0;
  z-index: 99;
}

.pin-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-left: 8px;
  border: 0;
  border-radius: var(--cs-radius-sm, 6px);
  background: transparent;
  color: var(--cs-muted, #526278);
  cursor: pointer;
  &:hover, &:focus-visible { background: var(--cs-primary-soft, #edf4fb); color: var(--cs-primary, #1b65a8); outline: none; }
  &.is-pinned { color: var(--cs-warning, #885910); }
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;

  .collapse-btn {
    font-size: 20px;
    cursor: pointer;
    color: var(--color-text-secondary, #7A8599);
    transition: color 0.2s;
    min-width: 44px;
    min-height: 44px;
    display: flex;
    align-items: center;
    justify-content: center;

    &:hover {
      color: var(--color-primary, #1B65A8);
    }
  }
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;

  .factory-view-status {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    border: 1px solid var(--el-color-warning-light-5, #f5dab1);
    border-radius: 8px;
    background: var(--el-color-warning-light-9, #fdf6ec);
    color: var(--el-color-warning-dark-2, #8d5b13);
    font-size: 13px;
    white-space: nowrap;

    strong { color: var(--el-text-color-primary, #1a2332); }
  }

  .header-action {
    font-size: 18px;
    cursor: pointer;
    color: var(--color-text-secondary, #7A8599);
    padding: 8px;
    border-radius: var(--radius-sm, 6px);
    transition: all 0.2s;

    // 修复: flex 布局下 el-icon 的 svg 只继承到 height:1em, width 塌成 ~2px → 图标不可见 (但可点/hover)。
    //   显式锁定 1em×1em 方形, 保证铃铛/刷新/主题图标真实可见。
    :deep(svg) {
      width: 1em;
      height: 1em;
      flex-shrink: 0;
    }

    &:hover {
      background-color: var(--color-bg-hover, #EDF2F7);
      color: var(--color-primary, #1B65A8);
    }
  }
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-sm, 6px);
  transition: background-color 0.2s;

  &:hover {
    background-color: var(--color-bg-hover, #EDF2F7);
  }

  :deep(.el-avatar) {
    background: linear-gradient(135deg, #1B65A8, #2B7EC1);
    color: #fff;
  }

  .user-detail {
    display: flex;
    flex-direction: column;
    line-height: 1.3;

    .user-name {
      font-size: 14px;
      color: var(--color-text-primary, #1A2332);
    }

    .user-role {
      font-size: 12px;
      color: var(--color-text-secondary, #7A8599);
    }
  }
}

@media (max-width: 768px) {
  .app-header {
    height: var(--header-height, 56px);
    padding: 0 12px;
  }
  .user-detail {
    display: none !important;
  }
  .factory-view-status span,
  .factory-view-status .el-tag {
    display: none;
  }
}
</style>
