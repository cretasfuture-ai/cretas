<script setup lang="ts">
// 「待办」：一个人「要我动手」的事只在一处。第八批 R38：等我确认、业务待办、指派给我接真实数据（只读，口径见 today-data.ts），
// 审批类（待我审批 / 我发起的 / 抄送与已处理）六膳门还没有数据来源，写明缺什么、找哪条线；味美工作区直接打开原审批页。
import { computed, onMounted, watch } from 'vue'
import { S } from '../frame/store'
import { C, mayConfirm, itemStatus } from '../crew/crew-store'
import { crewById } from '../crew/crew-config'
import { G, go } from './nav'
import { T, loadToday } from './today-data'
import TodoBlock from './TodoBlock.vue'
const TABS = [
  { id: 'confirm', label: '等我确认' }, { id: 'work', label: '业务待办' }, { id: 'assigned', label: '指派给我' },
  { id: 'approve', label: '待我审批' }, { id: 'mine', label: '我发起的' }, { id: 'cc', label: '抄送与已处理' }
]
const ydwm = computed(() => G.query.get('ws') === 'ydwm')
const tabId = computed(() => G.query.get('tab') || 'confirm')
const tab = computed(() => TABS.find(t => t.id === tabId.value) || TABS[0])
const badTab = computed(() => !!G.query.get('tab') && !TABS.some(t => t.id === G.query.get('tab')))
function setTab(id: string) { const sp = new URLSearchParams(G.query); sp.set('tab', id); location.hash = '/g/todo?' + sp.toString() }
onMounted(loadToday)
watch(() => [S.source?.checkedAt, G.query.get('ws')], loadToday)
const dayLabel = computed(() => ydwm.value ? '按本机日期 ' + new Date(Date.now() + 8 * 3600e3).toISOString().slice(0, 10) : T.day ? '按快照日 ' + T.day : '快照日未读到')
const inbox = computed(() => (C.state?.inbox || []).filter(x => x.status === 'pending' && mayConfirm(x)))
const num = (id: string) => ydwm.value ? '—' : T.s[id].state === 'ok' ? String(T.s[id].count) : '—'
const tabN = (id: string) => id === 'confirm' ? num('confirm') : id === 'assigned' ? num('tasks')
  : id === 'approve' ? num('oaPending') : id === 'mine' ? num('oaMine') : id === 'cc' ? num('oaActed')
  : id === 'work' ? (ydwm.value || ['weigh', 'batch', 'expiry', 'reconW', 'reconB', 'rules', 'risk'].some(k => T.s[k].state !== 'ok') ? '—' : String(['weigh', 'batch', 'expiry', 'reconW', 'reconB', 'rules', 'risk'].reduce((a, k) => a + T.s[k].count, 0))) : ''
const previewOf = (x: { preview?: { rows?: { label: string; value: string }[] } }) => (x.preview?.rows || []).slice(0, 2).map(r => r.label + '：' + r.value).join('；')
const OA: Record<string, string> = { approve: 'oaPending', mine: 'oaMine', cc: 'oaActed' }
const WF: Record<string, { label: string; route: string; why: string }> = {
  approve: { label: '审批中心', route: '/workflow/pending', why: '待我审批 = 个人 OA 待我审批 + 待审批请购 + 财务调整 / 报废审批。' },
  mine: { label: '我发起的', route: '/workflow/my-created', why: '我发起的单据现在停在哪一步。' },
  cc: { label: '已处理 / 抄送我的', route: '/workflow/acted', why: '抄送给我的和我已经处理过的审批。' }
}
</script>
<template>
<div class="g-page">
  <header class="g-head"><div>
    <p class="g-crumb">待办</p><h1>待办 <span class="g-day">{{ dayLabel }}</span></h1>
    <p class="g-why">一个人「要我动手」的事只在一处：数字员工出的草案等我确认、业务上今天要办的、指派给我的任务、别人发起的审批。审批动作仍在单据详情发生。</p>
  </div></header>
  <p v-if="badTab" class="g-note" role="status">没有「{{ G.query.get('tab') }}」这个页签，已显示「{{ tab.label }}」。</p>
  <div class="g-card flush">
    <div class="g-tabs" role="tablist">
      <button v-for="t in TABS" :key="t.id" role="tab" :aria-selected="t.id === tab.id" @click="setTab(t.id)">{{ t.label }} <b v-if="tabN(t.id)">{{ tabN(t.id) }}</b></button>
    </div>
    <div class="pad" style="padding:12px 16px 16px">
      <p v-if="ydwm && ['confirm', 'work', 'assigned'].includes(tab.id)" class="g-ydwm">味美工作区没有数字班组、称重、批次这些演示数据（只有本机练习数据），这一页不显示；六膳门工作区里有。味美的审批在原来的「审批中心」。</p>

      <template v-else-if="tab.id === 'confirm'">
        <p class="g-src">数字员工要改数据的事，先出预览，等有权限的人确认。{{ T.s.confirm.note }}</p>
        <p v-if="!C.state" class="g-ph">正在读取数字班组收件箱…</p>
        <p v-else-if="!inbox.length" class="g-empty-line">现在没有等我确认的事。</p>
        <div v-else class="g-table-wrap"><table class="g-table"><thead><tr><th>事项</th><th>哪位数字员工</th><th>要改什么（预览）</th><th>状态</th><th></th></tr></thead>
          <tbody><tr v-for="x in inbox" :key="x.id"><td>{{ x.title }}</td><td>{{ crewById(x.employee)?.name || x.employee }}</td><td>{{ previewOf(x) || x.module }}</td><td>{{ itemStatus(x).label }}</td><td class="g-acts"><button class="g-link" @click="go('F:inbox', '等我确认')">看预览 / 确认</button></td></tr></tbody></table></div>
        <div class="g-row-btns" style="margin-top:10px"><button class="g-btn primary" @click="go('F:inbox', '等我确认')">打开完整收件箱</button></div>
      </template>

      <template v-else-if="tab.id === 'work'">
        <p class="g-src">业务上今天要办的事（只读，按快照日 {{ T.day || '—' }}）。点条目去对应页面办理；这里不改任何数据。</p>
        <TodoBlock v-for="id in ['risk', 'weigh', 'batch', 'expiry', 'reconW', 'reconB', 'rules']" :key="id" :s="T.s[id]" :max="8" />
      </template>

      <template v-else-if="tab.id === 'assigned'">
        <TodoBlock :s="T.s.tasks" :max="10" />
        <div class="g-row-btns" style="margin-top:10px"><button class="g-btn" @click="go('/collab/tasks', '协同任务')">新建任务</button></div>
      </template>

      <template v-else>
        <p class="g-src">{{ WF[tab.id].why }}{{ ydwm ? '味美工作区用原来的审批页。' : '' }}</p>
        <template v-if="!ydwm">
          <TodoBlock :s="T.s[OA[tab.id]]" :max="10" />
          <TodoBlock v-if="tab.id === 'cc'" :s="T.s.oaCopied" />
        </template>
        <div class="g-row-btns" style="margin-top:10px"><button class="g-btn" @click="go(WF[tab.id].route, WF[tab.id].label)">打开{{ WF[tab.id].label }}</button></div>
      </template>
    </div>
  </div>
</div>
</template>
