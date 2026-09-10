<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue';
import { Close } from '@element-plus/icons-vue';
import type { WorkspaceTab } from '@/store/modules/workspace';

const props = defineProps<{ tab: WorkspaceTab }>();
defineEmits<{ close: [] }>();

const frame = ref<HTMLIFrameElement>();
const synchronized = ref(false);

function referenceScroller(): Element | null {
  try { return frame.value?.contentDocument?.scrollingElement ?? null; }
  catch { return null; }
}

function scrollReference(event: WheelEvent): void {
  if (event.ctrlKey) return;
  const target = referenceScroller();
  if (!target) return;
  event.preventDefault();
  const factor = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? target.clientHeight : 1;
  target.scrollTop += event.deltaY * factor;
  target.scrollLeft += event.deltaX * factor;
}

function scrollWithKeyboard(event: KeyboardEvent): void {
  const target = referenceScroller();
  if (!target) return;
  const steps: Record<string, number> = { ArrowDown: 40, ArrowUp: -40, PageDown: target.clientHeight, PageUp: -target.clientHeight };
  if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault();
    target.scrollTop = event.key === 'Home' ? 0 : target.scrollHeight;
  } else if (steps[event.key] !== undefined) {
    event.preventDefault();
    target.scrollTop += steps[event.key]!;
  }
}

function syncScroll(event?: Event): void {
  if (!synchronized.value) return;
  const source = event?.target instanceof Element && event.target.classList.contains('app-content')
    ? event.target : document.scrollingElement;
  const target = referenceScroller();
  if (!source || !target) return;
  const range = source.scrollHeight - source.clientHeight;
  target.scrollTop = range > 0 ? source.scrollTop / range * (target.scrollHeight - target.clientHeight) : 0;
}

function toggleSync(): void {
  synchronized.value = !synchronized.value;
  syncScroll();
}

onMounted(() => document.addEventListener('scroll', syncScroll, true));
onBeforeUnmount(() => document.removeEventListener('scroll', syncScroll, true));

const referenceUrl = computed(() => {
  const url = new URL(props.tab.fullPath, window.location.origin);
  url.searchParams.set('_workspaceReference', '1');
  return `${url.pathname}${url.search}${url.hash}`;
});
</script>

<template>
  <aside class="reference-pane" aria-label="同屏只读参考">
    <header>
      <div>
        <strong>同屏参考 · {{ tab.title }}</strong>
        <span>只读展示，左侧当前任务不会被替换</span>
      </div>
      <el-button text :aria-pressed="synchronized" @click="toggleSync">{{ synchronized ? '同步滚动' : '独立滚动' }}</el-button>
      <el-button :icon="Close" text aria-label="关闭同屏参考" @click="$emit('close')" />
    </header>
    <div class="reference-frame-wrap">
      <iframe ref="frame" :src="referenceUrl" :title="`只读参考：${tab.title}`" tabindex="-1" @load="syncScroll()" />
      <div class="readonly-shield" role="region" tabindex="0" aria-label="滚动只读参考，可使用方向键和翻页键" @wheel="scrollReference" @keydown="scrollWithKeyboard" />
    </div>
  </aside>
</template>

<style scoped lang="scss">
.reference-pane {
  position: sticky;
  top: 110px;
  align-self: flex-start;
  height: calc(100dvh - 110px);
  min-width: 340px;
  width: 38%;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-left: 1px solid #dfe7f1;
  box-shadow: -8px 0 24px rgba(31, 55, 83, 0.07);
}

header {
  min-height: 66px;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #edf2f7;
}

header div { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
header strong { color: #1a2332; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
header span { color: #7a8599; font-size: 12px; }
.reference-frame-wrap { flex: 1; min-height: 0; position: relative; overflow: hidden; background: #f4f6f9; }
iframe { width: 100%; height: 100%; border: 0; background: #f4f6f9; }
.readonly-shield { position: absolute; inset: 0; cursor: default; background: transparent; }
.readonly-shield:focus-visible { outline: 2px solid #1b65a8; outline-offset: -2px; }

@media (max-width: 1100px) {
  .reference-pane { width: 44%; min-width: 320px; }
}
</style>
