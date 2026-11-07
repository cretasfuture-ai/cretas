<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';
import { useNavigationVisibilityStore } from '@/store/modules/navigationVisibility';
import DecisionHistoryPanel from './components/DecisionHistoryPanel.vue';
import {
  isEligibleFactoryWorkspaceContext,
  resolveWorkspaceFactoryTypeFromUser,
  visibleWorkspaceLinks,
  workspaceByPath,
  type WorkspaceVisibilityContext,
} from '@/config/workspaces';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const navigationVisibility = useNavigationVisibilityStore();

function canVisitRoute(
  path: string,
  context: WorkspaceVisibilityContext,
  expectedModule: ModuleName,
): boolean {
  const resolved = router.resolve(path);
  const canonicalPath = path.split('?')[0];
  if (resolved.matched.length === 0 || resolved.path !== canonicalPath) return false;

  const meta = resolved.meta as {
    hidden?: boolean;
    module?: string;
    roles?: unknown;
    hideForFactoryTypes?: unknown;
  };
  if (meta.hidden === true || meta.module !== expectedModule) return false;
  if (Array.isArray(meta.roles) && !meta.roles.includes(context.role)) return false;
  if (Array.isArray(meta.hideForFactoryTypes) && meta.hideForFactoryTypes.includes(context.factoryType)) return false;
  return true;
}

function isPathVisible(path: string, context: WorkspaceVisibilityContext): boolean {
  if (!context.factoryId) return false;
  return navigationVisibility.isPathVisible(path.split('?')[0], {
    factoryId: context.factoryId,
    role: context.role,
    factoryType: context.factoryType,
    isPlatform: context.isPlatform,
  });
}

const visibilityContext = computed<WorkspaceVisibilityContext>(() => ({
  role: authStore.currentRole,
  factoryType: resolveWorkspaceFactoryTypeFromUser(authStore.user),
  factoryId: authStore.factoryId,
  isPlatform: authStore.isPlatform,
  canAccess: (module) => permissionStore.canAccess(module),
  canVisitRoute,
  isPathVisible,
}));

const workspace = computed(() => workspaceByPath(route.path));
const isEligible = computed(() => isEligibleFactoryWorkspaceContext(visibilityContext.value));
const navigationStatus = computed(() => navigationVisibility.status);
const canReviewHistory = computed(() => (
  route.path === '/workspaces/trace-review'
  && isEligible.value
  && navigationStatus.value === 'ready'
  && ['factory_super_admin', 'permission_admin'].includes(authStore.currentRole)
  && permissionStore.canAccess('system')
));
const visibleLinks = computed(() => {
  if (!workspace.value || !isEligible.value || navigationStatus.value !== 'ready') return [];
  return visibleWorkspaceLinks(workspace.value, visibilityContext.value);
});

function openLink(path: string): void {
  void router.push(path);
}
</script>

<template>
  <main class="factory-workspace page-container" data-testid="factory-workspace">
    <section v-if="!workspace || !isEligible" class="workspace-denied" data-testid="workspace-denied">
      <h1>无权访问工作区</h1>
      <p>当前账号未处于受支持的工厂管理上下文。</p>
    </section>
    <template v-else>
      <header class="workspace-header">
        <div>
          <p class="workspace-eyebrow">工厂管理工作区</p>
          <h1>{{ workspace.title }}</h1>
          <p class="workspace-question">{{ workspace.question }}</p>
          <p class="workspace-description">{{ workspace.description }}</p>
        </div>
        <el-tag type="info" effect="plain">工厂管理</el-tag>
      </header>

      <section class="workspace-links" aria-label="可用业务入口">
        <div v-if="navigationStatus === 'loading'" class="workspace-state" data-testid="workspace-loading">
          正在读取当前工厂可用入口…
        </div>
        <div v-else-if="navigationStatus === 'error'" class="workspace-state workspace-state-error" data-testid="workspace-error">
          当前工厂入口权限暂时无法读取，请稍后重试。
        </div>
        <div v-else-if="navigationStatus === 'unavailable'" class="workspace-state" data-testid="workspace-unavailable">
          当前工厂导航上下文未就绪。
        </div>
        <template v-else-if="visibleLinks.length">
          <button
            v-for="link in visibleLinks"
            :key="link.path"
            class="workspace-link"
            data-testid="workspace-link"
            type="button"
            :aria-label="`${link.title}：${link.description}`"
            @click="openLink(link.path)"
          >
            <span class="workspace-link-copy">
              <strong>{{ link.title }}</strong>
              <span>{{ link.description }}</span>
            </span>
            <span class="workspace-link-arrow" aria-hidden="true">→</span>
          </button>
        </template>
        <el-empty
          v-else
          data-testid="workspace-empty"
          description="当前权限范围内暂无可用入口"
        />
      </section>
      <DecisionHistoryPanel
        v-if="canReviewHistory"
        :factory-id="authStore.factoryId || ''"
        :role="authStore.currentRole"
      />
    </template>
  </main>
</template>

<style scoped lang="scss">
.factory-workspace {
  min-height: calc(100vh - 104px);
  padding: 20px;
  background: #f4f6f9;
}

.workspace-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}

.workspace-denied {
  max-width: 560px;
  margin: 40px auto;
  padding: 32px;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  background: #fff;
  text-align: center;
}

.workspace-denied h1 {
  font-size: 20px;
}

.workspace-denied p {
  margin: 12px 0 0;
  color: #7a8599;
}

.workspace-eyebrow {
  margin: 0 0 8px;
  color: #7a8599;
  font-size: 13px;
}

h1 {
  margin: 0;
  color: #1a2332;
  font-size: 24px;
  line-height: 1.35;
}

.workspace-question {
  margin: 8px 0 0;
  color: #1a2332;
  font-size: 16px;
  font-weight: 600;
}

.workspace-description {
  max-width: 720px;
  margin: 8px 0 0;
  color: #7a8599;
  line-height: 1.6;
}

.workspace-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}

.workspace-state {
  grid-column: 1 / -1;
  padding: 32px;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  background: #fff;
  color: #7a8599;
  text-align: center;
}

.workspace-state-error {
  color: #b42318;
}

.workspace-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 92px;
  padding: 16px;
  border: 1px solid #edf2f7;
  border-radius: 10px;
  background: #fff;
  color: #1a2332;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.workspace-link:hover,
.workspace-link:focus-visible {
  border-color: #1b65a8;
  box-shadow: 0 4px 14px rgb(27 101 168 / 12%);
  outline: none;
}

.workspace-link-copy {
  display: grid;
  gap: 6px;
}

.workspace-link-copy span {
  color: #7a8599;
  font-size: 13px;
  line-height: 1.5;
}

.workspace-link-arrow {
  margin-left: 12px;
  color: #1b65a8;
  font-size: 20px;
}

@media (max-width: 640px) {
  .factory-workspace {
    padding: 16px;
  }

  .workspace-header {
    display: block;
  }

  .workspace-header :deep(.el-tag) {
    margin-top: 12px;
  }
}
</style>
