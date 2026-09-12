<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Plus } from '@element-plus/icons-vue';
import { useWorkspaceStore } from '@/store/modules/workspace';

const router = useRouter();
const workspace = useWorkspaceStore();
const tabBar = ref<HTMLElement>();

const canDuplicate = computed(() => Boolean(workspace.activeTab));
const tabLabels = computed(() => Object.fromEntries(workspace.tabs.map(tab => {
  const base = new URL(tab.fullPath, 'https://workspace.local').searchParams.get('_taskBaseTitle');
  const suffix = base && tab.title.startsWith(`${base} `) ? tab.title.slice(base.length) : '';
  return [tab.key, /^ [1-9]\d*$/.test(suffix)
    ? { text: base || tab.title, suffix }
    : { text: tab.title, suffix: '' }] as const;
})));

function activate(key: string): void {
  const tab = workspace.tabs.find((item) => item.key === key);
  if (tab) void router.push(tab.fullPath);
}

async function focusActive(): Promise<void> {
  await nextTick();
  const buttons = tabBar.value?.querySelectorAll<HTMLButtonElement>('.workspace-tab');
  Array.from(buttons || []).find(button => button.dataset.workspaceKey === workspace.activeKey)?.focus({ preventScroll: true });
}

async function close(key: string): Promise<void> {
  const wasActive = workspace.activeKey === key;
  const fallback = workspace.closeTab(key);
  if (wasActive && fallback) await router.push(fallback.fullPath);
  await focusActive();
}

defineExpose({ focusActive });

function duplicateCurrent(): void {
  const target = workspace.duplicateRoute();
  if (target) void router.push(target);
}

function onDragStart(event: DragEvent, key: string): void {
  workspace.beginDrag(key);
  event.dataTransfer?.setData('text/plain', key);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copyMove';
}

function onDrop(event: DragEvent, beforeKey: string): void {
  event.preventDefault();
  event.stopPropagation();
  workspace.moveTab(workspace.draggingKey, beforeKey);
  workspace.endDrag();
}
</script>

<template>
  <nav ref="tabBar" class="workspace-tabs" aria-label="已打开任务">
    <div class="workspace-tab-scroll">
      <div
        v-for="tab in workspace.tabs"
        :key="tab.key"
        class="workspace-tab-container"
        :class="{ active: workspace.activeKey === tab.key }"
        draggable="true"
        :title="`${tab.title} · 可拖到右侧作为只读参考`"
        @dragstart="onDragStart($event, tab.key)"
        @dragend="workspace.endDrag"
        @dragover.prevent
        @drop="onDrop($event, tab.key)"
      >
        <button type="button" class="workspace-tab" :data-workspace-key="tab.key" @click="activate(tab.key)">
        <span v-if="tab.dirty" class="task-dot" aria-label="未完成任务" />
        <span class="workspace-tab-title"><span class="workspace-tab-text">{{ tabLabels[tab.key]?.text }}</span><span v-if="tabLabels[tab.key]?.suffix" class="workspace-tab-number">{{ tabLabels[tab.key]?.suffix }}</span></span>
        </button>
        <button
          v-if="workspace.tabs.length > 1"
          class="workspace-tab-close"
          type="button"
          aria-label="关闭任务"
          @click.stop="close(tab.key)"
          @dragstart.stop.prevent
        >×</button>
      </div>
      <el-tooltip content="复制当前页面为一个新任务；打开其他页面请直接使用左侧菜单" placement="bottom">
        <el-button
          class="new-task-button"
          :icon="Plus"
          :disabled="!canDuplicate"
          aria-label="复制当前页面为新任务"
          @click="duplicateCurrent"
        />
      </el-tooltip>
    </div>
    <span class="workspace-hint">拖动标签可排序，拖到右侧可同屏参考</span>
  </nav>
</template>

<style scoped lang="scss">
.workspace-tabs {
  min-height: 46px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  background: #edf2f8;
  border-bottom: 1px solid #dfe7f1;

  /*
   * 常驻悬浮在顶部 (2026-08-21 owner 实测: 页面往下滑, 标签栏跟着滑走了)。
   *
   * top: 64px = AppHeader 的高度 —— 它已经是 sticky/top:0/z-index:99, 标签栏要贴在它正下方。
   * ⚠️ 这两个数是**耦合**的: 改 .app-header 的 height 就必须同步改这里, 否则会重叠或留缝。
   *
   * z-index 取 98 (比 header 的 99 低一级): 滚动时内容从标签栏下面穿过去,
   * 而标签栏自己不会盖住 header 的下拉菜单。
   *
   * ⛔ 治标不治本: 根因是 .app-layout/.app-main 用了 `min-height: 100vh` 而不是
   *    `height: 100vh` —— 内容一长就把 .app-main 撑破视口, 于是 **window** 在滚,
   *    而 .app-content 的 `overflow-y: auto` 从来没生效过。
   *    根治要改那两处高度, 但实测 551 个 view 里有 32 个自己用了 100vh, 改了可能被裁,
   *    需要单独一轮逐个验。当前先用 sticky 顶住 —— header 本来就是这么做的。
   */
  position: sticky;
  top: 64px;
  z-index: 98;
}

.workspace-tab-scroll {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: stretch;
  overflow-x: auto;
  scrollbar-width: thin;
}

.workspace-tab-container {
  min-width: 112px;
  max-width: 210px;
  min-height: 46px;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #53647a;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: grab;
  white-space: nowrap;
}

.workspace-tab-container:hover { background: rgba(255, 255, 255, 0.6); color: #1b65a8; }
.workspace-tab-container.active { color: #1b65a8; background: #fff; border-bottom-color: #1b65a8; font-weight: 600; }
.workspace-tab-container:active { cursor: grabbing; }
.workspace-tab { min-width: 0; min-height: 42px; flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 0; border: 0; background: transparent; color: inherit; font: inherit; cursor: inherit; }
.workspace-tab:focus-visible, .workspace-tab-close:focus-visible { outline: 2px solid #1b65a8; outline-offset: -2px; }
.workspace-tab-title { min-width: 0; display: inline-flex; overflow: hidden; }
.workspace-tab-text { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.workspace-tab-number { flex: 0 0 auto; white-space: pre; }
.task-dot { width: 8px; height: 8px; flex: 0 0 8px; border-radius: 50%; background: #d89222; box-shadow: 0 0 0 3px rgba(216, 146, 34, 0.12); }
.workspace-tab-close { width: 24px; height: 24px; flex: 0 0 24px; padding: 0; border: 0; background: transparent; border-radius: 6px; display: inline-grid; place-items: center; color: #8a98aa; font-size: 17px; font-weight: 400; cursor: pointer; }
.workspace-tab-close:hover { color: #d84a4a; background: #fff0f0; }
.new-task-button { align-self: center; margin: 6px 8px; min-width: 34px; }
.workspace-hint { flex: 0 0 auto; color: #8795a8; font-size: 12px; }

@media (max-width: 980px) {
  .workspace-hint { display: none; }
  .workspace-tabs { padding-inline: 8px; }
}
</style>
