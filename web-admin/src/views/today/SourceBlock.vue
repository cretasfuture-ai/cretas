<script setup lang="ts">
import { useRouter } from 'vue-router';
import type { TSrc } from './todayData';

const props = defineProps<{ s: TSrc; max?: number }>();
const router = useRouter();
const go = (to?: string) => { if (to) void router.push(to); };
</script>

<template>
  <section class="src-block" :data-testid="`src-${s.id}`" :data-state="s.state">
    <header>
      <h3>{{ s.title }}</h3>
      <span v-if="s.state === 'ok'" class="num" :data-testid="`src-${s.id}-count`">{{ s.count }}</span>
      <span v-else-if="s.state === 'loading'" class="muted">读取中…</span>
      <span v-else-if="s.state === 'error'" class="bad">读取失败</span>
      <span v-else-if="s.state === 'na'" class="muted">不适用</span>
      <el-button v-if="s.link" link type="primary" @click="go(s.link)">{{ s.linkLabel }}</el-button>
    </header>
    <p class="hint">{{ s.hint }}</p>
    <p v-if="s.note" :class="['note', { bad: s.state === 'error' }]">{{ s.note }}</p>
    <ul v-if="s.state === 'ok' && s.items.length">
      <li v-for="(i, k) in s.items.slice(0, props.max ?? 5)" :key="k">
        <el-button link type="primary" @click="go(i.to)">{{ i.label }}</el-button>
        <small v-if="i.sub">{{ i.sub }}</small>
      </li>
    </ul>
    <p v-else-if="s.state === 'ok'" class="muted">现在没有。</p>
  </section>
</template>

<style scoped>
.src-block { padding: 12px 0; border-bottom: 1px solid var(--cs-border); }
.src-block:last-child { border-bottom: 0; }
header { display: flex; align-items: center; gap: 12px; }
h3 { margin: 0; font-size: var(--cs-type-body); font-weight: 600; color: var(--cs-text); }
.num { font-size: var(--cs-type-section); font-weight: 700; color: var(--cs-primary); }
.hint, .note, .muted { margin: 4px 0 0; font-size: var(--cs-type-caption); color: var(--cs-muted); }
.bad { color: var(--cs-danger); font-size: var(--cs-type-caption); }
ul { list-style: none; margin: 6px 0 0; padding: 0; }
li { display: flex; gap: 10px; align-items: baseline; padding: 2px 0; }
small { color: var(--cs-muted); }
</style>
