<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import SourceBlock from './SourceBlock.vue';
import { useToday } from './useToday';
import { tabBadge, TODO_TABS, type TodoTabId } from './todoTabs';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const { T, reload } = useToday();
onMounted(() => { void reload(); });
watch(() => [auth.factoryId, auth.user?.id], () => { void reload(); });

const tabId = computed<TodoTabId>(() => {
  const q = String(route.query.tab || '');
  return (TODO_TABS.some((t) => t.id === q) ? q : 'confirm') as TodoTabId;
});
const tab = computed(() => TODO_TABS.find((t) => t.id === tabId.value)!);
const badge = (id: TodoTabId) => tabBadge(id, T.s);
const setTab = (id: TodoTabId) => { void router.replace({ path: '/todo', query: { tab: id } }); };
const blocks = computed(() => tab.value.sources.map((id) => T.s[id]));
</script>

<template>
  <div class="todo" data-testid="todo-page">
    <header class="head">
      <div>
        <h1>待办</h1>
        <p class="why">要我动手的事只在这一处。审批动作仍在各单据详情里发生，这里只汇总、只读。</p>
      </div>
      <el-button :loading="T.busy" @click="reload">刷新</el-button>
    </header>
    <div class="tabs" role="tablist">
      <button v-for="t in TODO_TABS" :key="t.id" role="tab" :aria-selected="t.id === tabId"
        :data-testid="`tab-${t.id}`" :class="{ on: t.id === tabId }" @click="setTab(t.id)">
        {{ t.label }}<b v-if="badge(t.id) !== null" class="badge" :data-testid="`tab-${t.id}-badge`">{{ badge(t.id) }}</b>
      </button>
    </div>
    <el-card class="card" shadow="never">
      <p v-if="tab.reason" class="reason" :data-testid="`tab-${tab.id}-reason`">{{ tab.reason }}</p>
      <SourceBlock v-for="b in blocks" :key="b.id" :s="b" :max="10" />
    </el-card>
  </div>
</template>

<style scoped>
.todo { padding: 16px 0; max-width: 960px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
h1 { margin: 0; font-size: var(--cs-type-title); color: var(--cs-text); }
.why { margin: 4px 0 0; color: var(--cs-muted); font-size: var(--cs-type-body); }
.tabs { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 8px; }
.tabs button { border: 1px solid var(--cs-border); background: var(--cs-surface); color: var(--cs-text); padding: 6px 14px; border-radius: var(--cs-radius-sm); cursor: pointer; font-size: var(--cs-type-body); }
.tabs button.on { background: var(--cs-primary-soft); border-color: var(--cs-primary); color: var(--cs-primary); }
.badge { margin-left: 6px; background: var(--cs-primary); color: #fff; border-radius: 10px; padding: 0 7px; font-size: var(--cs-type-caption); }
.reason { margin: 0 0 8px; padding: 8px 10px; background: var(--cs-surface-subtle); border-radius: var(--cs-radius-sm); color: var(--cs-muted); font-size: var(--cs-type-body); }
</style>
