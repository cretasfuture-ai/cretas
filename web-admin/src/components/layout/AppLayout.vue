<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  queryWithoutReturnScroll,
  readReturnScroll,
  restoreAppScroll,
} from '@/utils/returnNavigation';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { useWorkspaceStore, workspaceRouteKey } from '@/store/modules/workspace';
import AppSidebar from './AppSidebar.vue';
import AppHeader from './AppHeader.vue';
import WorkspaceTabs from './WorkspaceTabs.vue';
import WorkspaceReferencePane from './WorkspaceReferencePane.vue';
import WorkspaceRouteCache from './WorkspaceRouteCache.vue';
import InlineCustomerService from '@/components/support/InlineCustomerService.vue';
import ReturnBanner from './ReturnBanner.vue';
import F006SandboxBanner from './F006SandboxBanner.vue';
import { recoverActiveSandbox, sandboxSession, selectSandboxActor } from '@/demo/f006Sandbox';
import request from '@/api/request';
import F006NativeToolbar from './F006NativeToolbar.vue';
import { nativePlayback } from '@/demo/f006NativeRuntime';
import { serializeSandboxLifecycle } from '@/demo/f006SandboxLifecycle';

const appStore = useAppStore();
const authStore = useAuthStore();
const workspace = useWorkspaceStore();
const route = useRoute();
const router = useRouter();
const workspaceTabs = ref<InstanceType<typeof WorkspaceTabs>>();
const workspaceScope = computed(() => sandboxSession.value ? `F006:sandbox:${sandboxSession.value.sessionId}` : `${authStore.factoryId || authStore.currentRole || 'authenticated'}:${authStore.user?.id || ''}`);
const sandboxBootstrapDone = ref(false);
const sandboxBootstrapError = ref('');
let sandboxBootstrapGeneration = 0;
const sandboxReady = computed(() => (authStore.factoryId !== 'F006' || sandboxBootstrapDone.value) && (!sandboxSession.value || sandboxSession.value.status === 'READY'));
async function bootstrapSandbox(): Promise<void> {
  const generation = ++sandboxBootstrapGeneration;
  sandboxBootstrapDone.value = false;
  selectSandboxActor(authStore.user?.id, authStore.factoryId);
  sandboxBootstrapError.value = '';
  if (authStore.factoryId === 'F006' && !authStore.user?.id) return;
  if (authStore.factoryId !== 'F006' || Number(authStore.user?.id) !== 1309) { sandboxBootstrapDone.value = true; return; }
  try {
    await serializeSandboxLifecycle(() => recoverActiveSandbox(request, () => generation === sandboxBootstrapGeneration));
    if (generation !== sandboxBootstrapGeneration) return;
    sandboxBootstrapDone.value = true;
  } catch { if (generation === sandboxBootstrapGeneration) sandboxBootstrapError.value = '无法核对演示状态，请重试'; }
}
watch(() => [authStore.factoryId, authStore.user?.id, authStore.currentRole], () => void bootstrapSandbox(), { immediate: true });

function closeReference(): void {
  workspace.closeReference();
  void workspaceTabs.value?.focusActive();
}

// Sprint 4 W1 C-INLINE-CS-1: 在线客服入口 URL. 后续接入实际客服系统时改 env / runtime config.
const customerServiceUrl = (import.meta.env.VITE_CUSTOMER_SERVICE_URL as string | undefined) ?? '';

onMounted(() => appStore.initResponsive());

const isReferenceFrame = computed(() => String(route.query._workspaceReference || '') === '1');
const workspaceCacheKeys = computed(() => workspace.tabs.map(tab => workspace.cacheKeyFor(tab.key)));

watch(
  () => workspaceScope.value,
  (scope) => workspace.setScope(scope),
  { immediate: true },
);

/**
 * 标签台账 + 新鲜度判据的**唯一**入口。
 *
 * ⚠️ `module` 这一项不是可选的装饰: 它是路由自己声明的业务域(`meta.module`, 19 种取值),
 * 写操作之后 `workspace.invalidateModules(['finance', ...])` 就是靠它认出「哪些标签该失效」。
 * 这里不传 —— 每个标签的 module 都是空 —— 失效机制会一个标签都匹配不到, 而 store 的单测
 * **照样全绿**。本仓称之为「机制在, 没接上」, 所以有一条源码闸钉着这一行
 * (`__tests__/appLayoutTabFreshness.source.spec.ts`)。
 *
 * 这个 watch 同时也是「切回一个已打开标签」的时机: openRoute 内部据此做时间兜底,
 * 且它以 flush:'pre' 跑在渲染之前 —— 缓存键在同一帧内就是新的, 组件当场重新 mount。
 */
watch(
  () => [route.fullPath, authStore.factoryId, workspaceScope.value],
  () => workspace.openRoute({
    fullPath: route.fullPath,
    path: route.path,
    name: route.name,
    title: String(route.meta.title || ''),
    module: String(route.meta.module || ''),
    query: route.query,
  }),
  { immediate: true },
);

/**
 * 「返回原页面」之后, 落回原来的上下滑动位置 (客户 2026-07-21 复测)。
 *
 * 放在这里是因为**滚动容器是这一层的 `.app-content`**, 不是各个页面自己 ——
 * 页面组件够不着它, 而且标签之间共用同一个滚动容器。
 *
 * ⚠️ 必须等内容撑开: 标签被关过时组件会重新 mount 并重新取数(见下面 keep-alive
 * 的 cacheKeyFor 注释), 这一刻设 scrollTop 会被浏览器夹成 0 —— 一次看起来
 * 成功的失败。restoreAppScroll 因此是有界重试并**返回是否真的到位**。
 * 恢复完把参数摘掉, 否则用户自己往上滚之后一刷新又被拽回去。
 */
watch(
  () => route.fullPath,
  () => {
    const top = readReturnScroll(route.query);
    if (!top) return;
    void restoreAppScroll(top).finally(() => {
      void router.replace({ path: route.path, query: queryWithoutReturnScroll(route.query) });
    });
  },
  { immediate: true },
);

const mainStyle = computed(() => ({
  marginLeft: isReferenceFrame.value || appStore.isMobile ? '0px' : `${appStore.currentSidebarWidth}px`,
  transition: 'margin-left 0.3s ease-in-out'
}));

function pinDraggedReference(event: DragEvent): void {
  event.preventDefault();
  workspace.pinReference(event.dataTransfer?.getData('text/plain') || workspace.draggingKey);
}
</script>

<template>
  <div class="app-layout">
    <!-- 侧边栏 -->
    <AppSidebar v-if="sandboxReady && !isReferenceFrame" />

    <!-- 主内容区 -->
    <div class="app-main" :style="mainStyle">
      <!-- 顶部栏 -->
      <AppHeader v-if="sandboxReady && !isReferenceFrame" />
      <F006NativeToolbar v-if="nativePlayback.enabled && authStore.factoryId === 'F006' && Number(authStore.user?.id) === 1309 && authStore.currentRole === 'factory_super_admin'" :bootstrap-ready="sandboxBootstrapDone" />
      <F006SandboxBanner v-if="sandboxSession && !nativePlayback.enabled" />

      <WorkspaceTabs v-if="sandboxReady && !isReferenceFrame" ref="workspaceTabs" />

      <!-- FK_BLOCK 防呆导航浮条: 从关联页跳转处理完毕后引导返回 -->
      <ReturnBanner v-if="!isReferenceFrame" />

      <!-- 内容区 -->
      <div class="workspace-content-shell" :class="{ 'has-reference': workspace.referenceTab && !isReferenceFrame }">
        <main class="app-content" :class="{ 'reference-frame-content': isReferenceFrame }">
          <el-result v-if="!sandboxReady" icon="info" title="正在核对演示状态" :sub-title="sandboxBootstrapError || '沙盒准备或清理完成后，即可继续。'"><template v-if="sandboxBootstrapError" #extra><el-button @click="bootstrapSandbox">重试</el-button></template></el-result>
          <router-view v-else v-slot="{ Component }">
            <!--
              每个活标签保留一个缓存实例；关闭或失效后旧缓存连同实例一起释放。
              cacheKeyFor 保留原有的刷新代次；工厂变化清空整个缓存边界。
              参考 iframe 没有任务标签，直接渲染自己的只读路由。
            -->
            <component v-if="isReferenceFrame" :is="Component" />
            <WorkspaceRouteCache
              v-else
              :key="workspaceScope"
              :component="Component"
              :cache-keys="workspaceCacheKeys"
              :active-key="workspace.cacheKeyFor(workspaceRouteKey({ path: route.path, query: route.query }))"
            />
          </router-view>
        </main>
        <WorkspaceReferencePane
          v-if="workspace.referenceTab && !isReferenceFrame"
          :tab="workspace.referenceTab"
          @close="closeReference"
        />
        <div
          v-if="workspace.draggingKey && !isReferenceFrame"
          class="reference-drop-zone"
          @dragover.prevent
          @drop="pinDraggedReference"
        >
          <div><strong>拖到这里，同屏参考</strong><span>松开后只读展示参考页，当前任务保持不变</span></div>
        </div>
      </div>
    </div>

    <!-- Sprint 4 W1 C-INLINE-CS-1: 在线客服浮动入口 (固定右下角) -->
    <InlineCustomerService v-if="!isReferenceFrame" :service-url="customerServiceUrl" />
  </div>
</template>

<style lang="scss" scoped>

.app-layout {
  min-height: 100vh;
  width: 100%;
  background-color: var(--bg-color-page, #F4F6F9);
}

.app-main {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

@media (prefers-reduced-motion: reduce) {
  .app-main { transition: none !important; }
}

.workspace-content-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  position: relative;
}

.app-content {
  flex: 1;
  padding: var(--page-padding, 20px);
  overflow-y: auto;
  background-color: var(--bg-color-page, #F4F6F9);
  min-width: 0;

  // 确保内容区域正确展示
  > * {
    min-height: 100%;
    width: 100%;
  }
}

.reference-frame-content { padding: 12px; min-height: 100vh; }

.reference-drop-zone {
  position: absolute;
  z-index: 30;
  inset: 12px 12px 12px auto;
  width: min(38%, 520px);
  border: 2px dashed #1b65a8;
  border-radius: 12px;
  background: rgba(238, 247, 255, 0.96);
  display: grid;
  place-items: center;
  color: #1b65a8;
  box-shadow: 0 12px 36px rgba(27, 101, 168, 0.16);
}

.reference-drop-zone div { display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center; }
.reference-drop-zone strong { font-size: 18px; }
.reference-drop-zone span { color: #63758a; }

@media (max-width: 1100px) {
  .workspace-content-shell.has-reference { flex-direction: column; }
  .has-reference > .app-content { flex: none; height: min(45dvh, 560px); overflow: auto; }
}

@media (max-width: 768px) {
  .app-main {
    margin-left: 0 !important;
  }
  .app-content {
    padding: var(--page-padding, 12px);
  }
}
</style>
