<script setup lang="ts">
// 重整后首页「今天」：以事为中心。四块 = 今天要办的事 / 数字员工在办什么 / 等我确认 / 发现的问题；单据入口退到页底第二层。
// 第八批 R38：今天要办的事、等我确认、发现的问题、我在盯的都接真实数据（只读，口径见 today-data.ts），按快照日（R28）算；接不到的写明缺什么、找哪条线。
import { computed, onMounted, ref, watch } from 'vue'
import { G, go, explain } from './nav'
import { ROLES, ISSUE_SOURCES, NAV_AFTER } from './pages'
import { T, loadToday, ROLE_SOURCES, ROLE_GAPS } from './today-data'
import TodoBlock from './TodoBlock.vue'
import { S } from '../frame/store'
import CrewCards from '../crew/CrewCards.vue' // 数字班组卡片组件（只读，自带数据与样式；点卡片跳 /crew 各页）
const roleId = computed(() => G.query.get('role') || 'boss')
const role = computed(() => ROLES.find(r => r.id === roleId.value) || ROLES[0])
function setRole(id: string) { const sp = new URLSearchParams(G.query); sp.set('role', id); location.hash = '/g/today?' + sp.toString() }
const ask = ref('')
function send() {
  explain('问数字班组', '「' + (ask.value || '（空）') + '」会交给' + (role.value.crew === '全部' ? '对应的数字员工（按问题自动分派）' : role.value.crew) + '：查询类直接返回结果和依据；要改数据的先出预览，进「等我确认」。', { label: '打开数字班组', run: () => go('F:home') })
}
const biz = NAV_AFTER.filter(g => g.kind === 'biz')
// R28：六膳门的「今天」= 快照日（checkedAt 换算北京时间）；味美按本机日期
const bj = (d: Date) => new Date(d.getTime() + 8 * 3600e3).toISOString().slice(0, 10)
const ydwm = computed(() => G.query.get('ws') === 'ydwm')
const dayLabel = computed(() => {
  if (ydwm.value) return '按本机日期 ' + bj(new Date())
  const at = S.source?.checkedAt
  return at ? '按快照日 ' + bj(new Date(String(at))) : '快照日未读到'
})
onMounted(loadToday)
watch(() => [S.source?.checkedAt, G.query.get('ws')], loadToday)
const blocks = computed(() => (ROLE_SOURCES[role.value.id] || []).filter(id => id !== 'confirm').map(id => T.s[id]))
const gap = computed(() => ROLE_GAPS[role.value.id] || '')
const chipN = (id: string) => ({ crew: T.chips['汇报'], rule: T.chips['预警'], reconcile: T.chips['对账'], data: T.chips['缺口'] } as Record<string, number>)[id] ?? 0
// 「我在盯的」：有数据来源的显示数，其余汇总成一句说明
const WATCH: Record<string, () => string | null> = {
  '数字班组今天办成几件': () => ydwm.value ? null : String(T.doneToday),
  '临期批次': () => T.s.expiry.state === 'ok' ? String(T.s.expiry.count) : null,
  '临期 / 过期批次': () => T.s.expiry.state === 'ok' ? String(T.s.expiry.count) : null,
  '对账差异': () => T.s.reconW.state === 'ok' || T.s.reconB.state === 'ok' ? T.chips['对账'] + ' 项来源有差异' : null,
  '对账差异（入库批次 ↔ 黑湖）': () => T.s.reconB.state === 'ok' ? String(T.s.reconB.count) : null
}
const watchRows = computed(() => role.value.watch.map(w => ({ w, v: WATCH[w] ? WATCH[w]() : null })))
const watchShown = computed(() => watchRows.value.filter(r => r.v != null))
const watchMissing = computed(() => watchRows.value.filter(r => r.v == null).map(r => r.w))
</script>
<template>
<div class="g-page">
  <header class="g-head">
    <div><p class="g-crumb">今天 · {{ role.label }}</p><h1>今天 <span class="g-day">{{ dayLabel }}</span></h1><p class="g-why">打开先看「要我办的事」和「数字员工替我办了什么」，单据和报表在下面第二层。</p></div>
    <div class="g-roles" role="tablist" aria-label="岗位">
      <button v-for="r in ROLES" :key="r.id" role="tab" :aria-selected="r.id === role.id" @click="setRole(r.id)">{{ r.label }}</button>
    </div>
  </header>

  <form class="g-ask" @submit.prevent="send">
    <input v-model="ask" :placeholder="'告诉' + (role.crew === '全部' ? '数字班组' : role.crew) + '要办什么，例如：周五要加一单，能不能排进去'" aria-label="问数字班组" />
    <button class="g-btn primary" type="submit">交给数字班组</button>
  </form>

  <div class="g-grid">
    <section class="g-card span2">
      <header><h2>今天要办的事</h2><span class="g-src">{{ dayLabel }}<template v-if="T.busy"> · 正在读取…</template></span></header>
      <p v-if="ydwm" class="g-ydwm">味美工作区只有本机练习数据，没有待称重、批次、对账这些来源，不显示数量；六膳门工作区里有。</p>
      <template v-else>
        <TodoBlock v-for="b in blocks" :key="b.id" :s="b" />
        <p v-if="!blocks.length" class="g-empty-line">这个岗位暂时没有可读的待办来源。</p>
        <p v-if="gap" class="g-gapnote">还没接：{{ gap }}</p>
      </template>
      <div class="g-quick" aria-label="常用去处"><span class="g-src">常用去处</span><button v-for="t in role.tasks" :key="t.what" class="g-link" @click="go(t.to, t.what)">{{ t.what }}</button></div>
    </section>

    <section class="g-card">
      <header><h2>等我确认</h2><button class="g-link" @click="go('F:inbox', '等我确认')">全部</button></header>
      <p v-if="ydwm" class="g-ydwm">味美工作区没有数字班组演示数据，不显示。</p>
      <template v-else>
        <p class="g-num">{{ T.s.confirm.state === 'ok' ? T.s.confirm.count : '—' }}<small>条</small></p>
        <p class="g-src">{{ T.s.confirm.note || '数字员工要改数据的事先出预览，只显示我有权确认的。' }}</p>
        <ul v-if="T.s.confirm.items.length" class="g-blk-l"><li v-for="(i, k) in T.s.confirm.items.slice(0, 3)" :key="k"><button class="g-link" @click="go(i.to, i.label)">{{ i.label }}</button><small>{{ i.sub }}</small></li></ul>
      </template>
      <div class="g-row-btns"><button class="g-btn primary" @click="go('F:inbox', '等我确认')">逐条确认</button><button class="g-btn" @click="go('/g/todo?tab=approve')">待我审批</button></div>
    </section>

    <section class="g-card span3">
      <header><h2>数字员工在办什么</h2><button class="g-link" @click="go('F:home', '数字班组首页')">班组首页</button><button class="g-link" @click="go('F:records', '工作记录与额度')">工作记录与额度</button></header>
      <CrewCards :summary="false" :ws="G.query.get('ws') === 'ydwm' ? 'ydwm' : 'lsm'" />
      <p class="g-src">计数来自数字班组的工作记录和主动汇报，口径见班组首页。</p>
    </section>

    <section class="g-card span2">
      <header><h2>发现的问题</h2><button class="g-link" @click="go('/g/issues')">全部</button></header>
      <div class="g-chips">
        <button v-for="k in ISSUE_SOURCES.slice(1)" :key="k.id" @click="go('/g/issues?src=' + k.id)">{{ k.label }} <b>{{ ydwm ? '—' : chipN(k.id) }}</b></button>
        <button @click="go('F:issues?source=疑点')">数据疑点 <b>{{ ydwm ? '—' : T.chips['疑点'] }}</b></button>
      </div>
      <p class="g-src">每条带依据、找谁、可转待办；录入错误单列「数据疑点」，不上头条。{{ ydwm ? '味美工作区没有这类数据。' : '条数口径同「发现的问题」页，按快照日。' }}</p>
    </section>

    <section class="g-card">
      <header><h2>我在盯的</h2></header>
      <ul class="g-watch"><li v-for="x in watchShown" :key="x.w"><button class="g-link" @click="go('/g/issues', x.w)">{{ x.w }}</button><span>{{ x.v }}</span></li></ul>
      <p v-if="watchMissing.length" class="g-src">还没有数据来源：{{ watchMissing.join('、') }}（要 A、B、C 线给口径后再接）。</p>
    </section>
  </div>

  <section class="g-second">
    <header><h2>业务办理（第二层）</h2><button class="g-link" @click="go('/g/map')">菜单与动线对照</button></header>
    <div class="g-biz">
      <div v-for="g in biz" :key="g.label"><h3>{{ g.label }}</h3><button v-for="i in g.items" :key="i.label" class="g-link" @click="go(i.to, i.label)">{{ i.label }}</button></div>
    </div>
  </section>
</div>
</template>
