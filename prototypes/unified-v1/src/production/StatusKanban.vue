<script setup lang="ts">
// 状态看板（R41，六膳门；正式端叫生产看板，统一原型改名状态看板）。
// 来源：黑湖工单状态（新建 / 执行中 / 已结束 / 已取消），卡片只读，不能拖动改状态（状态在黑湖办理，Cretas 不回写）。
import {computed,ref} from 'vue'
import {S,rs,rowId,navigate} from '../frame/store'
import {fmtQty,sourceTagHTML} from '../frame/ai-protocol'
import {progress,pct,LABEL} from './progress'
import {bjDay,bjToday} from './time'
const COLS:[string,string][]=[['0','新建'],['10','执行中'],['20','已结束'],['30','已取消']]
const today=computed(()=>bjDay(S.source?.checkedAt)||bjToday())
const LIM=30,more=ref<Record<string,boolean>>({})
const cards=(k:string)=>rs('project').filter(p=>String(p.status)===k).sort((a,b)=>String(b.planStartTime).localeCompare(String(a.planStartTime)))
const g=(p:Record<string,unknown>)=>progress({planned:p.planAmount,done:p.numberofGoodProducts,start:p.planStartTime,end:p.planEndTime,finished:String(p.status)==='20',cancelled:String(p.status)==='30',now:Date.parse(today.value+'T12:00:00+08:00')})
</script>
<template><section class="card table-panel kb">
<div class="kb-head"><div><h2>状态看板</h2><p class="kb-note">按黑湖工单状态分列，快照日 {{today}}。卡片只读，不能拖动改状态；状态在黑湖办理。</p></div><span v-html="sourceTagHTML('blacklake')"/></div>
<div class="kb-cols"><div v-for="[k,l] in COLS" :key="k" class="kb-col"><h3>{{l}} <small>{{cards(k).length}}</small></h3>
<button v-for="p in (more[k]?cards(k):cards(k).slice(0,LIM))" :key="rowId(p)" type="button" class="kb-card" @click="navigate('/production/plans',{project:rowId(p)})"><b>{{p.projectCode}}</b><span>{{p.productName}}</span><small>计划 {{p.planAmount==null?'未填写':fmtQty(Number(p.planAmount),String(p.projectUnit||''))}} · 良品 {{fmtQty(Number(p.numberofGoodProducts)||0,String(p.projectUnit||''))}}</small><small>工单进度 {{pct(g(p).work,g(p).workReason)}} · {{LABEL[g(p).schedule]}}</small></button>
<button v-if="!more[k]&&cards(k).length>LIM" type="button" class="kb-more" @click="more[k]=true">显示其余 {{cards(k).length-LIM}} 张</button><p v-if="!cards(k).length" class="empty">没有工单</p></div></div>
</section></template>
<style scoped>.kb-head{display:flex;justify-content:space-between;gap:12px}.kb-head h2{margin:0 0 4px}.kb-note{font-size:12px;color:#667589;margin:4px 0}.kb-cols{display:grid;grid-template-columns:repeat(4,minmax(200px,1fr));gap:12px;align-items:start;overflow-x:auto}.kb-col{background:#f4f6f9;border-radius:8px;padding:8px;max-height:640px;overflow-y:auto}.kb-col h3{font-size:14px;margin:2px 4px 8px}.kb-col h3 small{color:#667589;font-weight:400}.kb-card{display:flex;flex-direction:column;gap:2px;width:100%;text-align:left;background:#fff;border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:8px;margin-bottom:6px;cursor:pointer;font:inherit}.kb-card span{font-size:13px}.kb-card small{color:#667589;font-size:11px}.kb-more{width:100%;border:0;background:none;color:var(--cs-primary,#2563eb);cursor:pointer;padding:6px}</style>
