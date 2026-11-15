<script setup lang="ts">
// 数字员工卡片组（班组首页与 G 线「今天」页共用）：五张卡 + 可选汇总行。
// 计数口径：今天办成 / 等确认 / 已交办 = countsOf(id)；发现问题 = 主动汇报里未处理的「问题 / 提醒」（R30：数据疑点单独计数，不上头条）。
// 工作区隔离（F-2）：只有六膳门有演示数据；味美工作区（地址 ws=ydwm，或传 ws="ydwm"）一律显示「无数据」，不借六膳门的数。
// 独立使用时自己读数据（loadAll），自带样式（crew.css），点击都跳 F 的路由。只读，不写任何东西。
// 用法：<CrewCards/>；只要卡片不要汇总：<CrewCards :summary="false"/>；窄位置：<CrewCards compact/>（不显示职责与发现内容）。
import {computed,onMounted} from 'vue'
import {navigate} from '../frame/store'
import {C,countsOf,perms,src,realToday as today,loadAll,syncContext} from './crew-store'
import {CREW,crewPerm,modCn,type CrewId} from './crew-config'
import {buildReports,COUNTED} from './crew-reports'
import CrewAvatar from './CrewAvatar.vue'
import './crew.css'
const p=withDefaults(defineProps<{summary?:boolean;compact?:boolean;ws?:'lsm'|'ydwm'}>(),{summary:true,compact:false,ws:undefined})
onMounted(()=>{syncContext();if(p.ws)C.ws=p.ws;if(!C.state&&!C.loading)loadAll().then(()=>{if(p.ws)C.ws=p.ws})})
const ydwm=computed(()=>(p.ws||C.ws)==='ydwm')
const open=(r:{key:string;draft?:()=>{sourceKey:string}})=>{const s=C.state?.reports[r.key]?.status;if(s==='dismissed')return false
  const ix=C.state?.inbox||[],live=(id:string)=>{const x=ix.find(y=>y.id===id);return !!x&&!['cancelled','failed','unknown'].includes(x.status)}
  if(s==='converted'&&live(C.state!.reports[r.key].inboxId))return false
  if(r.draft){const k=r.draft().sourceKey;if(ix.some(x=>x.sourceKey===k&&!['cancelled','failed','unknown'].includes(x.status)))return false}
  return true}
const all=computed(()=>ydwm.value?[]:buildReports(src(),C.inv).filter(open))
const reports=computed(()=>all.value.filter(r=>COUNTED.includes(r.level)))
const suspects=(id:CrewId)=>all.value.filter(r=>r.employee===id&&r.level==='数据疑点').length
// 头条按严重程度：问题在前（降得最多的在前），提醒其次；数据疑点不上头条
const findings=(id:CrewId)=>reports.value.filter(r=>r.employee===id).sort((a,b)=>(a.level==='问题'?0:1)-(b.level==='问题'?0:1))
const on=(id:CrewId)=>C.state?C.state.config.enabled[id]!==false:true
function state(id:CrewId){if(C.busyId&&C.state?.inbox.find(x=>x.id===C.busyId)?.employee===id)return 'working';if(findings(id).length)return 'alert';if(countsOf(id).pending)return 'waiting';return 'idle'}
const total=computed(()=>CREW.filter(c=>on(c.id)).reduce((a,c)=>{const n=countsOf(c.id);return {done:a.done+n.done,pending:a.pending+n.pending,handed:a.handed+n.handed}},{done:0,pending:0,handed:0}))
const problems=computed(()=>reports.value.filter(r=>on(r.employee)).length)
const permText={rw:'能查能办',r:'只能查','-':'没有权限'} as Record<string,string>
function go(path:string,q:Record<string,string>={}){if(ydwm.value)q.ws='ydwm';navigate(path,q)}
</script>
<template><div class="crew crew-cards" :data-ws="ydwm?'ydwm':'lsm'">
<p v-if="ydwm" class="notice" role="status">味美工作区没有数字班组演示数据（味美只有本机练习数据）；三条演示流程在六膳门工作区。</p>
<div v-if="p.summary" class="summary-row">
  <div><span>今天办成（按实际日期 {{today()}}；办成记录按真实发生时间）</span><strong>{{ydwm?'无数据':total.done}}</strong></div>
  <div><span>等人确认</span><strong>{{ydwm?'无数据':total.pending}}</strong></div>
  <div><span>发现的问题</span><strong>{{ydwm?'无数据':problems}}</strong></div>
  <div><span>已交办（等人办完）</span><strong>{{ydwm?'无数据':total.handed}}</strong></div>
</div>
<div class="grid5">
  <article v-for="c in CREW" :key="c.id" class="crew-card" :class="{off:!on(c.id)}" :data-crew="c.id">
    <header><CrewAvatar :name="c.name" :state="on(c.id)&&!ydwm?state(c.id):'idle'" :size="p.compact?40:64"/><div><h2>{{c.name}} <span class="perm" :class="crewPerm(c,perms())">{{permText[crewPerm(c,perms())]}}</span></h2><p v-if="!p.compact">{{c.duty}}</p></div></header>
    <template v-if="!on(c.id)"><p class="muted">本厂未启用</p></template>
    <template v-else-if="ydwm"><div class="nums"><div><span>今天办成</span><strong class="muted" style="font-size:13px">无数据</strong></div><div><span>等确认</span><strong class="muted" style="font-size:13px">无数据</strong></div><div><span>发现问题</span><strong class="muted" style="font-size:13px">无数据</strong></div></div></template>
    <template v-else>
      <div class="nums">
        <button type="button" @click="go('/crew/ledger',{crew:c.id})"><span>今天办成</span><strong>{{countsOf(c.id).done}}</strong></button>
        <button type="button" class="warn" @click="go('/crew/inbox',{crew:c.id})"><span>等确认</span><strong>{{countsOf(c.id).pending}}</strong></button>
        <button type="button" class="bad" @click="go('/crew/issues',{crew:c.id,source:'汇报'})"><span>发现问题</span><strong>{{findings(c.id).length}}</strong></button>
      </div>
      <template v-if="!p.compact"><p v-if="findings(c.id)[0]" class="finding">{{findings(c.id)[0].title}}：{{findings(c.id)[0].summary}}</p>
      <p v-else class="finding none">没有发现问题</p>
      <p v-if="countsOf(c.id).handed" class="muted">已交办 {{countsOf(c.id).handed}} 件，等人办完</p>
      <p v-if="suspects(c.id)" class="muted">另有数据疑点 {{suspects(c.id)}} 条（报工录入可能有误，找车间核）</p></template>
      <p class="muted" v-if="crewPerm(c,perms())==='-'">缺「{{c.modules.map(modCn).join('、')}}」模块权限</p>
      <button class="btn primary" type="button" @click="go('/crew/worker',{id:c.id})">进入工作台</button>
    </template>
  </article>
</div>
</div></template>
