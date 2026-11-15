<script setup lang="ts">
// 通用骨架页：页头（位置、负责的数字员工、正式端对应）→ 数字员工条 → 页签 → 筛选 / 主操作 → 表头 + 占位行 → 行操作。
import { computed } from 'vue'
import type { Page } from './pages'
import { ISSUE_KINDS, ISSUE_SOURCES, crewIdOf } from './pages'
import { G, go, act, explain } from './nav'
const props = defineProps<{ page: Page }>()
const ctx = computed(() => ({ page: props.page.title, crew: props.page.crew, line: props.page.line }))
const tabId = computed(() => G.query.get('tab') || props.page.tabs[0].id)
const badTab = computed(() => !!G.query.get('tab') && !props.page.tabs.some(t => t.id === G.query.get('tab')))
const tab = computed(() => props.page.tabs.find(t => t.id === tabId.value) || props.page.tabs[0])
const kind = computed(() => G.query.get('kind') || 'all')
const src = computed(() => G.query.get('src') || 'all')
function setQ(k: string, v: string) { const sp = new URLSearchParams(G.query); sp.set(k, v); location.hash = props.page.route + '?' + sp.toString() }
function filter(f: string) { explain('筛选：' + f, '这里将按「' + f + '」筛选；日期可选今天、本周、近 30 天，常用筛选可另存为视图，单号可以一次粘贴多张。') }
const adminOnly = computed(() => props.page.crew.startsWith('—'))
const crewName = computed(() => props.page.crew === '全部' ? '数字班组' : props.page.crew.split(' / ')[0])
const crewId = computed(() => crewIdOf(crewName.value))
const onIssues = computed(() => props.page.route === '/g/issues')
</script>
<template>
<div class="g-page">
  <header class="g-head">
    <div>
      <p class="g-crumb">{{ page.place }}</p>
      <h1>{{ page.title }} <span class="g-tag">骨架</span><span v-if="page.reference" class="g-tag warn" title="味美账号未开通此模块；参考设计不构成启用或授权">参考设计 · 味美未开通</span></h1>
      <p class="g-why">{{ page.why }}</p>
      <details class="g-review"><summary>原型说明（评审用）</summary><p class="g-formal">正式管理端对应：<code v-for="f in page.formal" :key="f">{{ f }}</code> · 实现归 {{ page.line }}</p></details>
    </div>
  </header>

  <div v-if="adminOnly" class="g-agent muted"><div>管理员页面，不归数字员工；这里的配置决定数字员工能办什么、何时提醒。</div></div>
  <div v-else class="g-agent">
    <span class="g-avatar sm" aria-hidden="true">{{ crewName === '数字班组' ? '班' : crewName.slice(2, 3) }}</span>
    <div><b>{{ page.crew === '全部' ? '五位数字员工' : page.crew }}</b> 在这页：</div>
    <button class="g-chip" @click="go(crewId ? 'F:' + crewId : 'F:home', '正在办')">正在办 —</button>
    <button class="g-chip" @click="go(crewIdOf(crewName) ? 'F:inbox?crew=' + crewIdOf(crewName) : 'F:inbox', '等我确认')">等你确认 —</button>
    <button class="g-chip" :class="{ on: onIssues }" :aria-current="onIssues ? 'page' : undefined" :disabled="onIssues" @click="go('/g/issues')">发现的问题 —</button>
    <button class="g-btn" @click="act({ label: '交给' + crewName, say: '带着当前页签、筛选和选中记录进入' + crewName + '的对话；办完回到本页看结果。' }, ctx)">交给{{ crewName }}</button>
  </div>

  <template v-if="onIssues">
    <div class="g-chips" aria-label="来源"><span class="g-chip-lab">来源</span>
      <button v-for="k in ISSUE_SOURCES" :key="k.id" :class="{ on: src === k.id }" @click="setQ('src', k.id)">{{ k.label }} <b>—</b></button>
      <button v-if="src === 'crew'" class="g-link" @click="go('F:reports', '主动汇报')">看汇报原文</button>
    </div>
    <div class="g-chips sub" aria-label="类型"><span class="g-chip-lab">类型</span>
      <button v-for="k in ISSUE_KINDS" :key="k.id" :class="{ on: kind === k.id }" @click="setQ('kind', k.id)">{{ k.label }}</button>
    </div>
  </template>
  <p v-if="badTab" class="g-note" role="status">没有「{{ G.query.get('tab') }}」这个页签，已显示「{{ tab.label }}」。</p>

  <div class="g-card flush">
    <div class="g-tabs" role="tablist">
      <button v-for="t in page.tabs" :key="t.id" role="tab" :aria-selected="t.id === tab.id" @click="setQ('tab', t.id)">{{ t.label }} <b>—</b></button>
    </div>
    <div class="g-toolbar">
      <input class="g-search" placeholder="搜索单号、名称" aria-label="搜索" @keydown.enter="filter('关键词')" />
      <button v-for="f in tab.filters || []" :key="f" class="g-filter" @click="filter(f)">{{ f }} ▾</button>
      <span class="g-spacer" />
      <button v-for="a in tab.actions || []" :key="a.label" class="g-btn" :class="{ primary: a.primary }" @click="act(a, ctx)">{{ a.label }}<span v-if="a.write" class="g-w" title="写操作：先预览后确认">预览</span></button>
    </div>
    <p v-if="tab.source" class="g-src pad">数据来自：{{ tab.source }}</p>
    <p v-if="tab.note" class="g-note pad">{{ tab.note }}</p>
    <div v-if="tab.cols" class="g-table-wrap">
      <table class="g-table">
        <thead><tr><th v-for="c in tab.cols" :key="c">{{ c }}</th><th v-if="tab.rowActs">操作</th></tr></thead>
        <tbody>
          <tr><td v-for="c in tab.cols" :key="c" class="g-ph">—（骨架）</td>
            <td v-if="tab.rowActs" class="g-acts"><button v-for="a in tab.rowActs" :key="a.label" class="g-link" @click="act(a, ctx)">{{ a.label }}</button></td></tr>
        </tbody>
      </table>
      <p class="g-src pad">占位行：接入数据后显示真实记录；算不出的写「无法计算：原因」，不画假数。</p>
    </div>
  </div>
</div>
</template>
