<script setup lang="ts">
/**
 * P2 菜单搜索（统一原型 #uni-tools 的「菜单搜索 Ctrl+K」）。
 * 只搜**当前账号侧栏里看得见的**叶子（父组件传入已过滤的菜单树），不另判权限；
 * 结果带「组 › 项」路径，支持中文、全拼、首字母。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { Search } from '@element-plus/icons-vue';
import type { MenuItem } from './menuConfig';
import { matchesAnyTextOrPinyin } from '@/utils/pinyinSearch';

const props = defineProps<{
  menu: readonly MenuItem[];
  collapsed: boolean;
  titleForItem: (item: MenuItem) => string;
}>();
const emit = defineEmits<{ (e: 'select', path: string): void }>();

interface Entry { path: string; title: string; trail: string }

const visible = ref(false);
const query = ref('');
const activeIndex = ref(0);
const inputRef = ref<{ focus: () => void } | null>(null);

const entries = computed<Entry[]>(() => {
  const out: Entry[] = [];
  const walk = (items: readonly MenuItem[], parents: string[]) => {
    for (const item of items) {
      const title = props.titleForItem(item);
      if (item.children?.length) walk(item.children, [...parents, title]);
      else out.push({ path: item.path, title, trail: parents.join(' › ') });
    }
  };
  walk(props.menu, []);
  return out;
});

const results = computed<Entry[]>(() => {
  const q = query.value.trim();
  if (!q) return entries.value.slice(0, 30);
  return entries.value.filter((e) => matchesAnyTextOrPinyin([e.title, e.trail], q)).slice(0, 30);
});

async function open(): Promise<void> {
  query.value = '';
  activeIndex.value = 0;
  visible.value = true;
  await nextTick();
  inputRef.value?.focus();
}

function choose(entry: Entry | undefined): void {
  if (!entry) return;
  visible.value = false;
  emit('select', entry.path);
}

function onInputKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    activeIndex.value = Math.min(activeIndex.value + 1, results.value.length - 1);
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    activeIndex.value = Math.max(activeIndex.value - 1, 0);
  } else if (event.key === 'Enter') {
    event.preventDefault();
    choose(results.value[activeIndex.value]);
  }
}

function onGlobalKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    void open();
  }
}

onMounted(() => window.addEventListener('keydown', onGlobalKeydown));
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKeydown));

defineExpose({ open });
</script>

<template>
  <button
    type="button"
    class="menu-search-trigger"
    :class="{ 'is-collapsed': collapsed }"
    aria-label="搜索菜单（Ctrl+K）"
    title="搜索菜单（Ctrl+K）"
    @click="open"
  >
    <el-icon><Search /></el-icon>
    <span v-if="!collapsed" class="menu-search-text">搜索菜单</span>
    <kbd v-if="!collapsed" class="menu-search-kbd">Ctrl K</kbd>
  </button>

  <el-dialog
    v-model="visible"
    title="搜索菜单"
    width="520px"
    append-to-body
    class="menu-search-dialog"
    :show-close="true"
    @opened="inputRef?.focus()"
  >
    <el-input
      ref="inputRef"
      v-model="query"
      placeholder="输入菜单名、拼音或首字母"
      clearable
      @input="activeIndex = 0"
      @keydown="onInputKeydown"
    />
    <ul v-if="results.length" class="menu-search-results" role="listbox" aria-label="菜单搜索结果">
      <li
        v-for="(entry, index) in results"
        :key="entry.path"
        role="option"
        :aria-selected="index === activeIndex"
        :class="{ 'is-active': index === activeIndex }"
        @mouseenter="activeIndex = index"
        @click="choose(entry)"
      >
        <span class="menu-search-title">{{ entry.title }}</span>
        <span v-if="entry.trail" class="menu-search-trail">{{ entry.trail }}</span>
      </li>
    </ul>
    <p v-else class="menu-search-empty">没有找到「{{ query }}」。只搜索你当前侧栏里能看到的菜单。</p>
  </el-dialog>
</template>

<style scoped>
.menu-search-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: calc(100% - 16px);
  margin: 8px 8px 0;
  padding: 7px 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: var(--cs-radius-sm, 6px);
  background: rgba(255, 255, 255, 0.04);
  color: rgba(255, 255, 255, 0.62);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background-color var(--cs-duration-fast, 140ms), color var(--cs-duration-fast, 140ms);
}
.menu-search-trigger:hover,
.menu-search-trigger:focus-visible {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  outline: none;
}
.menu-search-trigger.is-collapsed {
  justify-content: center;
  padding: 7px 0;
}
.menu-search-text { flex: 1; text-align: left; }
.menu-search-kbd {
  font-family: inherit;
  font-size: 11px;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  color: rgba(255, 255, 255, 0.5);
}
.menu-search-results {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  max-height: 360px;
  overflow: auto;
}
.menu-search-results li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border-radius: var(--cs-radius-sm, 6px);
  cursor: pointer;
}
.menu-search-results li.is-active { background: var(--cs-primary-soft, #edf4fb); color: var(--cs-primary, #1b65a8); }
.menu-search-title { font-weight: 500; }
.menu-search-trail { color: var(--cs-muted, #526278); font-size: 12px; white-space: nowrap; }
.menu-search-empty { margin-top: 12px; color: var(--cs-muted, #526278); }
</style>
