<script setup lang="ts">
// 生产分析（R41，六膳门；正式端「生产分析」是按条件查询的分析页）。
// 来源：黑湖工单和逐笔报工，本页只做只读汇总：工单状态与工期、按产品的计划量与良品、近 14 天报工笔数。
// 汇总全由代码算，不请求模型；不同单位的数量不相加（按产品 + 单位分组）。黑湖没有废品原因、工时、设备时间，故不做损耗和效率分析。
import {computed,ref} from 'vue'
import {S,rs,rowId} from '../frame/store'
import {fmtQty,fmtPct,sourceTagHTML} from '../frame/ai-protocol'
import {progress,LABEL,type Schedule} from './progress'
import {bjDay,bjToday} from './time'
const today=computed(()=>bjDay(S.source?.checkedAt)||bjToday())
const status=ref('')
const ST:[string,string][]=[['0','新建'],['10','执行中'],['20','已结束'],['30','已取消']]
const projects=computed(()=>rs('project').filter(p=>!status.value||String(p.status)===status.value))
const sched=computed(()=>{const c:Partial<Record<Schedule,number>>={};for(const p of projects.value){const g=progress({planned:p.planAmount,done:p.numberofGoodProducts,start:p.planStartTime,end:p.planEndTime,finished:String(p.status)==='20',cancelled:String(p.status)==='30',now:Date.parse(today.value+'T12:00:00+08:00')});c[g.schedule]=(c[g.schedule]||0)+1}return Object.entries(c) as [Schedule,number][]})
const byProduct=computed(()=>{const m=new Map<string,{name:string;unit:string;n:number;plan:number;good:number;noPlan:number}>();for(const p of projects.value){const k=String(p.productCode)+'|'+String(p.projectUnit);const x=m.get(k)||{name:String(p.productName),unit:String(p.projectUnit??''),n:0,plan:0,good:0,noPlan:0};x.n++;const pl=Number(p.planAmount);if(p.planAmount==null||!Number.isFinite(pl))x.noPlan++;else x.plan+=pl;x.good+=Number(p.numberofGoodProducts)||0;m.set(k,x)}return [...m.values()].sort((a,b)=>b.n-a.n)})
const days=computed(()=>{const t=Date.parse(today.value+'T12:00:00+08:00'),out:{d:string;n:number;good:number}[]=[];for(let i=13;i>=0;i--)out.push({d:bjDay(new Date(t-i*864e5).toISOString()),n:0,good:0})
  for(const o of rs('output')){const d=bjDay(o.endtimeofReport),x=out.find(v=>v.d===d);if(x){x.n++;x.good+=Number(o.numberofgoodProducts)||0}}return out})
const maxN=computed(()=>Math.max(1,...days.value.map(d=>d.n)))
</script>
<template><section class="card table-panel pa">
<div class="pa-head"><div><h2>生产分析</h2><p class="pa-note">黑湖工单与报工的只读汇总，快照日 {{today}}。全部由代码计算，不请求模型。</p></div><span v-html="sourceTagHTML('blacklake')"/></div>
<div class="pa-bar"><label>工单状态 <select v-model="status" aria-label="工单状态"><option value="">全部</option><option v-for="[k,l] in ST" :key="k" :value="k">{{l}}</option></select></label><span>共 {{projects.length}} 张工单</span></div>
<h3>工期状态</h3>
<div class="pa-chips"><span v-for="[k,n] in sched" :key="k" class="chip">{{LABEL[k]}} {{n}}</span><span v-if="!sched.length" class="empty">没有工单</span></div>
<h3>按产品汇总</h3>
<div class="table-scroll"><table><thead><tr><th>产品</th><th>单位</th><th>工单数</th><th>计划量</th><th>良品</th><th>良品 ÷ 计划</th></tr></thead><tbody>
<tr v-for="x in byProduct" :key="x.name+x.unit"><td>{{x.name}}</td><td>{{x.unit||'未填写'}}</td><td>{{x.n}}</td><td>{{fmtQty(x.plan,x.unit)}}<small v-if="x.noPlan">另有 {{x.noPlan}} 张没填计划量</small></td><td>{{fmtQty(x.good,x.unit)}}</td><td>{{x.plan>0&&!x.noPlan?fmtPct(x.good/x.plan*100,{digits:1}):'不能算（计划量缺失）'}}</td></tr>
<tr v-if="!byProduct.length"><td colspan="6" class="empty">没有工单</td></tr></tbody></table></div>
<h3>近 14 天报工笔数</h3>
<div class="pa-days"><div v-for="d in days" :key="d.d" class="pa-day" :title="d.d+'：'+d.n+' 笔'"><i :style="{height:Math.round(d.n/maxN*60)+'px'}"/><b>{{d.n}}</b><small>{{d.d.slice(5)}}</small></div></div>
<p class="pa-note">不同单位的良品不能相加，所以这里只数笔数。黑湖没有废品原因、工时、设备时间，所以不做损耗和效率分析。</p>
</section></template>
<style scoped>.pa-head{display:flex;justify-content:space-between;gap:12px}.pa-head h2{margin:0 0 4px}.pa-note{font-size:12px;color:#667589;margin:6px 0}.pa h3{font-size:14px;margin:14px 0 6px}.pa-bar{display:flex;gap:16px;align-items:center;font-size:13px;margin:8px 0}.pa-chips{display:flex;gap:8px;flex-wrap:wrap}.chip{border:1px solid var(--cs-border,#d6dee7);border-radius:14px;padding:2px 12px;font-size:13px}.table-scroll td{white-space:nowrap;font-variant-numeric:tabular-nums}.table-scroll small{display:block;color:#8a96a6;font-size:11px}.pa-days{display:flex;gap:6px;align-items:flex-end;height:100px}.pa-day{display:flex;flex-direction:column;align-items:center;justify-content:flex-end;width:36px;font-size:11px}.pa-day i{display:block;width:18px;background:var(--cs-primary,#2563eb);border-radius:3px 3px 0 0}.pa-day small{color:#8a96a6}</style>
