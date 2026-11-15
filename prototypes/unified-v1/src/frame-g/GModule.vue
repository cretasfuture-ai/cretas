<script setup lang="ts">
// G 线入口：按 hash 分发到「今天」「菜单与动线对照」和通用骨架页。嵌在统一框架（view==='g'）或单独预览都用它。
import { computed, onBeforeUnmount, onMounted, watchEffect } from 'vue'
import './g.css'
import { G, syncG, loadKnown, go, redirectIssues, redirectNew } from './nav'
import { pageOf } from './pages'
import TodayPage from './TodayPage.vue'
import MapPage from './MapPage.vue'
import SkeletonPage from './SkeletonPage.vue'
import TodoPage from './TodoPage.vue'
const page = computed(() => pageOf(G.path))
// 第七批 R32：今天、待办是侧栏顶层入口，顶部快捷栏和侧栏重复，这两页不显示
const TOP = ['/g/today', '/g/todo']
const quick = [
  { to: '/g/today', label: '今天' }, { to: 'F:home', label: '数字班组' }, { to: '/g/todo', label: '待办' },
  { to: 'F:issues', label: '发现的问题' }, { to: '/g/map', label: '菜单与动线对照' }
]
// 第七批审计退回 1：G 是模块级状态，重新挂载时先同步 hash，否则会拿上一次的 /g/issues 再跳一次
syncG()
// 第八批：hash 变了、或重新挂载时，都再试一次转跳（只靠 watchEffect 在重新挂载的页面上有时不触发）
const onHash = () => { syncG(); if (G.knownReady) redirectNew() }
onMounted(() => { syncG(); window.addEventListener('hashchange', onHash); loadKnown().then(() => redirectNew()); if (G.knownReady) redirectNew() })
// R33：F 的「发现的问题」上线后，/g/issues 直接转过去（只在当前地址真的是 /g/issues 时，用 replace）
watchEffect(() => { if (G.knownReady && G.path === '/g/issues') redirectIssues() })
// 第八批：交付与备货、工程变更与调价、放行与处置、经营报告已有正式页，骨架转过去
watchEffect(() => { if (G.knownReady) redirectNew() })
onBeforeUnmount(() => window.removeEventListener('hashchange', onHash))
</script>
<template>
<div class="g-module">
  <nav v-if="false" class="g-bar" aria-label="菜单预览">
    <span class="g-flag">骨架</span>
    <button v-for="q in quick" :key="q.to" :class="{on: G.path === q.to}" @click="go(q.to, q.label)">{{ q.label }}</button>
    <span class="g-bar-note">按钮与页面关系已搭好；文字、计算、视觉细节后续定</span>
  </nav>
  <TodayPage v-if="G.path === '/g/today'" />
  <TodoPage v-else-if="G.path === '/g/todo'" />
  <MapPage v-else-if="G.path === '/g/map'" />
  <SkeletonPage v-else-if="page" :key="page.route" :page="page" />
  <div v-else class="g-empty"><p>没有这个骨架页：{{ G.path }}</p><button class="g-btn primary" @click="go('/g/map')">看全部骨架页</button></div>
</div>
</template>
