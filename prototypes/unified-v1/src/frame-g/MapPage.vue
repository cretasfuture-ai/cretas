<script setup lang="ts">
// 菜单与动线对照：前后菜单并排（重整后的每一项可点）、前后对比图、正式管理端差距表（可按状态筛）。
import { computed, ref } from 'vue'
import { NAV_BEFORE, NAV_AFTER, PAGES } from './pages'
import { go } from './nav'
import gap from './gap-data.json'
import svg from './ia-compare.svg?raw'
type Row = string[]
const rows = gap.rows as Row[]
// 11-13 重数：状态计数只算「工厂可见」；「工厂隐藏」（数据与分析组）单列
const fac = rows.filter(r => r[9] === '工厂可见'), hid = rows.filter(r => r[9] === '工厂隐藏'), out = rows.filter(r => r[3] === 'X')
const count = (s: string) => fac.filter(r => r[3] === s).length
const st = ref<'all' | 'Y' | 'P' | 'N' | 'H' | 'X'>('N')
const shown = computed(() => st.value === 'all' ? rows : st.value === 'H' ? hid : st.value === 'X' ? out : fac.filter(r => r[3] === st.value))
const label: Record<string, string> = gap.status as Record<string, string>
const kindLabel = { agent: '以事为中心', biz: '业务办理（第二层）', set: '设置（下沉）' }
</script>
<template>
<div class="g-page">
  <header class="g-head"><div><p class="g-crumb">原型与来源 · 菜单与动线</p><h1>菜单与动线对照 <span class="g-tag">骨架</span></h1>
    <p class="g-why">左边是统一原型现在的菜单（按单据和模块分组），右边是新的菜单（先看事、再办单）。右边每一项都能点：已有的跳原型页，没有的进骨架页，数字班组的进数字班组页面。</p></div></header>

  <div class="g-compare">
    <section class="g-card">
      <header><h2>现在（8 组 · 按模块）</h2></header>
      <div v-for="g in NAV_BEFORE" :key="g.label" class="g-tree"><h3>{{ g.label }}</h3><p>{{ g.items.join(' · ') }}</p></div>
      <p class="g-src">另有「原型与来源」在账号菜单。正式管理端还有工厂管理工作区、AI 工作台、研发、财务、人事、设备、系统、智能调度等 8 组没进原型。</p>
    </section>
    <section class="g-card">
      <header><h2>新菜单（4 个以事为中心的入口 + 第二层业务 + 设置）</h2></header>
      <template v-for="(g, i) in NAV_AFTER" :key="g.label">
        <p v-if="i === 0 || NAV_AFTER[i - 1].kind !== g.kind" class="g-kind">{{ kindLabel[g.kind] }}</p>
        <div class="g-tree" :class="g.kind"><h3>{{ g.label }}</h3>
          <p><button v-for="it in g.items" :key="it.label" class="g-link" :title="it.note || it.to" @click="go(it.to, it.label)">{{ it.label }}</button></p>
          <p v-for="it in g.items.filter(x => x.note)" :key="it.label + 'n'" class="g-src">{{ it.label }}：{{ it.note }}</p>
        </div>
      </template>
    </section>
  </div>

  <section class="g-card">
    <header><h2>前后对比图</h2><span class="g-src">文件：src/frame-g/ia-compare.svg（G-ia.md 引用同一张）</span></header>
    <div class="g-svg" v-html="svg" />
  </section>

  <section class="g-card">
    <header><h2>骨架页（{{ PAGES.length + 2 }} 个）</h2></header>
    <div class="g-chips"><button @click="go('/g/today')">今天</button><button v-for="p in PAGES" :key="p.route" @click="go(p.route)">{{ p.title }}</button></div>
  </section>

  <details class="g-card flush g-review-block">
    <summary class="pad"><b>正式管理端差距表（评审用）</b> <span class="g-src">含归属线和裁定编号，给产品评审看</span></summary>
    <header class="pad"><h2>正式管理端差距表</h2><span class="g-src">工厂可见 {{ fac.length }} 页 + 工厂隐藏 {{ hid.length }} 页；{{ gap.recount }}</span></header>
    <div class="g-tabs" role="tablist">
      <button role="tab" :aria-selected="st === 'all'" @click="st = 'all'">全部 <b>{{ rows.length }}</b></button>
      <button role="tab" :aria-selected="st === 'Y'" @click="st = 'Y'">工厂可见 · 已有 <b>{{ count('Y') }}</b></button>
      <button role="tab" :aria-selected="st === 'P'" @click="st = 'P'">部分 <b>{{ count('P') }}</b></button>
      <button role="tab" :aria-selected="st === 'N'" @click="st = 'N'">没有 <b>{{ count('N') }}</b></button>
      <button role="tab" :aria-selected="st === 'H'" @click="st = 'H'">工厂隐藏 <b>{{ hid.length }}</b></button>
      <button role="tab" :aria-selected="st === 'X'" @click="st = 'X'">不纳入 <b>{{ out.length }}</b></button>
    </div>
    <div class="g-table-wrap"><table class="g-table">
      <thead><tr><th>正式菜单组</th><th>页面</th><th>正式路由</th><th>状态</th><th>原型</th><th>线</th><th>数字员工</th><th>新菜单</th><th>说明</th><th>味美编译页</th></tr></thead>
      <tbody><tr v-for="r in shown" :key="r[2] + r[1]">
        <td>{{ r[0] }}</td><td>{{ r[1] }}</td><td><code>{{ r[2] }}</code></td><td><span class="g-st" :class="r[3]">{{ label[r[3]] }}</span></td>
        <td>{{ r[4] }}</td><td>{{ r[5] }}</td><td>{{ r[6] }}</td>
        <td><button v-if="r[7] && (r[7].startsWith('/') )" class="g-link" @click="go(r[7])">{{ r[7] }}</button><span v-else>{{ r[7] }}</span></td><td class="wrap">{{ r[8] }}</td><td>{{ r[10] }}</td>
      </tr></tbody>
    </table></div>
  </details>
</div>
</template>
