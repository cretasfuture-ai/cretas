<script setup lang="ts">
// F 线模块根：数字班组。两个工作区共用；演示数据只有六膳门（黑湖真实工单）可用，味美工作区只有本机练习数据，页面写明。
// 不画第二套侧栏，不自带 AI 悬浮窗：工作台左侧对话就是本页的对话区（D 在 /crew/* 隐藏 AiFloat）。
import {computed,defineAsyncComponent,onBeforeUnmount,onMounted,ref} from 'vue'
import {S,navigate} from '../frame/store'
import {C,loadAll,setRole,syncContext,perms,snapshotDay} from './crew-store'
import {DEMO_ROLES} from './crew-config'
import './crew.css'
const CrewHome=defineAsyncComponent(()=>import('./CrewHome.vue'))
const CrewWorker=defineAsyncComponent(()=>import('./CrewWorker.vue'))
const CrewInbox=defineAsyncComponent(()=>import('./CrewInbox.vue'))
const CrewLedger=defineAsyncComponent(()=>import('./CrewLedger.vue'))
const CrewOnboard=defineAsyncComponent(()=>import('./CrewOnboard.vue'))
const CrewIssues=defineAsyncComponent(()=>import('./CrewIssues.vue'))
const CrewSettings=defineAsyncComponent(()=>import('./CrewSettings.vue'))
const CrewMobile=defineAsyncComponent(()=>import('./CrewMobile.vue'))
const CrewMap=defineAsyncComponent(()=>import('./CrewMap.vue'))
const path=ref('/crew')
// 切页时清掉上一页的提示和错误（F-15）
function sync(){const p0=path.value;path.value=location.hash.replace(/^#/,'').split('?')[0]||'/crew';if(p0!==path.value){C.notice='';C.error=''};const ws0=C.ws,s0=C.session;syncContext();if(ws0!==C.ws||s0!==C.session)loadAll()}
const TABS=[['/crew','班组首页'],['/crew/inbox','等我确认'],['/crew/ledger','工作记录与额度'],['/crew/issues','发现的问题'],['/crew/onboard','班组入职'],['/crew/mobile','一线手机端'],['/crew/map','功能归属与架构'],['/crew/settings','班组配置']] as const
// /crew/reports 是旧路由（原「主动汇报」），第七批并入「发现的问题」
const view=computed(()=>path.value==='/crew/worker'?'worker':path.value==='/crew/reports'?'/crew/issues':TABS.find(t=>t[0]===path.value)?.[0]||'/crew')
const pendingMine=computed(()=>(C.state?.inbox||[]).filter(x=>x.status==='pending'&&!!perms()&&perms()![x.module]==='rw').length)
function go(p:string){const q:Record<string,string>={};if(C.ws==='ydwm')q.ws='ydwm';navigate(p,q)}
onMounted(async()=>{sync();window.addEventListener('hashchange',sync);await loadAll()})
onBeforeUnmount(()=>window.removeEventListener('hashchange',sync))
</script>
<template><div class="crew" :data-view="view==='/crew/mobile'?'mobile':view">
<div class="page-heading"><div><p>{{C.ws==='ydwm'?'味美':'六膳门'}} · 数字班组</p><h1>数字班组 <i class="help" tabindex="0" title="数字班组是现有功能之上的入口和组织方式：每位数字员工 = 一组现有能力 + 当前登录人的权限。查询直接回答；写操作先出预览，进「等我确认」，有权限的人确认后才调用现有接口办理。">?</i></h1></div>
  <div class="actions">
    <label class="crew-role">我是<select aria-label="演示身份" :value="C.role" @change="setRole(($event.target as HTMLSelectElement).value)"><option v-for="(r,k) in DEMO_ROLES" :key="k" :value="k">{{r.label}}</option></select><i class="help" tabindex="0" title="「当前账号」读统一入口账号的真实权限（permissions/current）。其余岗位是原型假设的权限，只用来预览「按权限过滤」，不是真实账号权限。">?</i></label>
    <button class="btn" type="button" :disabled="C.loading" @click="loadAll()">{{C.loading?'读取中…':'重新读取'}}</button>
  </div></div>
<div class="crew-strip">
  <span v-if="C.ws==='lsm'">六膳门黑湖快照 {{S.source?new Date(Date.parse(S.source.checkedAt)+8*3600e3).toISOString().slice(0,16).replace('T',' '):'读取中'}}（只读，不回写黑湖）· 今天、近 14 天、周五都按快照日 {{snapshotDay()}} 计</span>
  <span v-else class="warn">味美工作区没有数字班组演示数据（只有本机练习数据），各页显示「无数据」；三条演示流程用六膳门工作区</span>
  <span v-if="C.session">演示写入会话 {{C.session}} · 只写数字班组记录和协同任务</span>
  <span v-else class="warn">没带验收会话：可以查、可以出预览，确认办理要在地址加 ?acceptanceSession=test-f-20261112</span>
  <span v-if="C.role==='self'&&C.permsError" class="warn">账号权限没读到：{{C.permsError}}</span>
</div>
<nav class="crew-tabs" aria-label="数字班组页面"><button v-for="t in TABS" :key="t[0]" type="button" :aria-current="view===t[0]?'page':undefined" @click="go(t[0])">{{t[1]}}<span v-if="t[0]==='/crew/inbox'&&pendingMine" class="count">{{pendingMine}}</span></button></nav>
<p v-if="C.error" role="alert" class="error">{{C.error}}</p>
<p v-if="C.notice" role="status" class="notice">{{C.notice}} <button aria-label="关闭提示" @click="C.notice=''">×</button></p>
<CrewWorker v-if="view==='worker'"/>
<CrewInbox v-else-if="view==='/crew/inbox'"/>
<CrewLedger v-else-if="view==='/crew/ledger'"/>
<CrewIssues v-else-if="view==='/crew/issues'"/>
<CrewSettings v-else-if="view==='/crew/settings'"/>
<CrewOnboard v-else-if="view==='/crew/onboard'"/>
<CrewMobile v-else-if="view==='/crew/mobile'"/>
<CrewMap v-else-if="view==='/crew/map'"/>
<CrewHome v-else/>
</div></template>
