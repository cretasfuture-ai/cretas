<script setup lang="ts">
// 一个数据来源的待办块（今天、待办两页共用）：数量、前几条、口径说明、去哪办。读不到时写原因，不画假数。
import { go } from './nav'
import type { TSrc } from './today-data'
defineProps<{ s: TSrc; max?: number }>()
</script>
<template>
<div class="g-blk" :data-src="s.id" :data-state="s.state">
  <div class="g-blk-h"><b>{{ s.title }}</b>
    <strong v-if="s.state === 'ok'" class="g-blk-n" :class="{ zero: !s.count }">{{ s.count }}</strong>
    <span v-else-if="s.state === 'loading' || s.state === 'idle'" class="g-ph">正在读取…</span>
    <span v-else class="g-ph">没有数据</span>
    <button v-if="s.state === 'ok' && s.count" class="g-link" @click="go(s.link, s.linkLabel)">{{ s.linkLabel }} →</button>
  </div>
  <p class="g-src">{{ s.hint }}</p>
  <p v-if="s.note" class="g-src" :class="{ bad: s.state === 'error' }">{{ s.note }}</p>
  <ul v-if="s.state === 'ok' && s.items.length" class="g-blk-l">
    <li v-for="(i, k) in s.items.slice(0, max || 5)" :key="k"><button class="g-link" @click="go(i.to, i.label)">{{ i.label }}</button><small v-if="i.sub">{{ i.sub }}</small></li>
  </ul>
  <p v-else-if="s.state === 'ok'" class="g-empty-line">现在没有。</p>
  <button v-if="s.state === 'ok' && s.items.length > (max || 5)" class="g-link" @click="go(s.link, s.linkLabel)">还有 {{ s.count - (max || 5) }} 条 → {{ s.linkLabel }}</button>
</div>
</template>
