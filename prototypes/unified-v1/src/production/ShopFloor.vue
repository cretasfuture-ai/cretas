<script setup lang="ts">
// 车间大屏（R41，六膳门；正式端叫「生产进度大屏」，统一原型改名车间大屏）。
// 来源：黑湖工单（状态、计划量、良品、计划起止）和逐笔报工；进度算法与工单列表同一个 progress.ts。「今天」= 快照日（R28）。
// 黑湖没有车间、产线、设备、工位维度的数据，所以不画产线图，也不显示设备状态；只展示工单和报工。
import {computed} from 'vue'
import {S,rs,rowId,navigate} from '../frame/store'
import {fmtQty,sourceTagHTML} from '../frame/ai-protocol'
import {progress,pct,LABEL} from './progress'
import {bjDay,bjToday,bjTime} from './time'
const today=computed(()=>bjDay(S.source?.checkedAt)||bjToday())
const run=computed(()=>rs('project').filter(p=>String(p.status)==='10').map(p=>({p,g:progress({planned:p.planAmount,done:p.numberofGoodProducts,start:p.planStartTime,end:p.planEndTime,now:Date.parse(today.value+'T12:00:00+08:00')})})).sort((a,b)=>Number(b.g.schedule==='overdue')-Number(a.g.schedule==='overdue')||String(a.p.planEndTime).localeCompare(String(b.p.planEndTime))))
const outs=computed(()=>rs('output'))
const todayOut=computed(()=>outs.value.filter(o=>bjDay(o.endtimeofReport)===today.value))
const late=computed(()=>run.value.filter(x=>x.g.schedule==='overdue'||x.g.schedule==='behind').length)
const last=computed(()=>[...outs.value].sort((a,b)=>String(b.endtimeofReport).localeCompare(String(a.endtimeofReport))).slice(0,8))
const code=(id:unknown)=>String(rs('project').find(p=>rowId(p)===String(id))?.projectCode??'未对应工单')
const proc=(id:unknown)=>String(rs('process').find(p=>rowId(p)===String(id))?.name??'未返回')
const width=(v:number|null)=>v===null?0:Math.min(100,Math.round(v*100))
</script>
<template><section class="card table-panel sf">
<div class="sf-head"><div><h2>车间大屏</h2><p class="sf-note">按快照日 {{today}} 展示。只读；办理去工单、报工和称重报工。</p></div><span v-html="sourceTagHTML('blacklake')"/></div>
<div class="sf-kpi"><div><b>{{run.length}}</b><span>执行中工单</span></div><div :class="{warn:late>0}"><b>{{late}}</b><span>已超期 / 落后</span></div><div><b>{{todayOut.length}}</b><span>快照日报工笔数</span></div><div><b>{{rs('project').filter(p=>String(p.status)==='0').length}}</b><span>未开始工单</span></div></div>
<h3>执行中工单进度</h3>
<div class="table-scroll"><table><thead><tr><th>工单</th><th>产品</th><th>计划量</th><th>良品</th><th>工单进度</th><th>时间进度</th><th>工期</th><th class="op">操作</th></tr></thead><tbody>
<tr v-for="x in run" :key="rowId(x.p)"><td>{{x.p.projectCode}}</td><td>{{x.p.productName}}</td><td>{{x.p.planAmount==null?'未填写':fmtQty(Number(x.p.planAmount),String(x.p.projectUnit||''))}}</td><td>{{fmtQty(Number(x.p.numberofGoodProducts)||0,String(x.p.projectUnit||''))}}</td>
<td><i class="bar"><u :style="{width:width(x.g.work)+'%'}"/></i>{{pct(x.g.work,x.g.workReason)}}</td><td><i class="bar t"><u :style="{width:width(x.g.time)+'%'}"/></i>{{pct(x.g.time===null?null:Math.min(x.g.time,1),x.g.timeReason)}}</td><td :class="{bad:x.g.schedule==='overdue'||x.g.schedule==='behind'}">{{LABEL[x.g.schedule]}}</td><td class="op"><button type="button" class="lk" @click="navigate('/production/plans',{project:rowId(x.p)})">看工单</button></td></tr>
<tr v-if="!run.length"><td colspan="8" class="empty">黑湖里没有执行中的工单。</td></tr></tbody></table></div>
<h3>最近报工</h3>
<div class="table-scroll"><table><thead><tr><th>报工结束</th><th>来源工单</th><th>工序</th><th>良品</th></tr></thead><tbody><tr v-for="o in last" :key="rowId(o)"><td>{{bjTime(o.endtimeofReport)}}</td><td>{{code(o.projectId)}}</td><td>{{proc(o.processId)}}</td><td>{{fmtQty(Number(o.numberofgoodProducts)||0,String(o.stockUnit||''))}}</td></tr><tr v-if="!last.length"><td colspan="4" class="empty">没有报工记录。</td></tr></tbody></table></div>
<p class="sf-note">黑湖没有车间、产线、设备、工位维度的数据，本屏不画产线图，也不显示设备状态。</p>
</section></template>
<style scoped>.sf-head{display:flex;justify-content:space-between;gap:12px}.sf-head h2{margin:0 0 4px}.sf-note{font-size:12px;color:#667589;margin:4px 0}.sf h3{font-size:14px;margin:14px 0 6px}.sf-kpi{display:flex;gap:12px;flex-wrap:wrap;margin:10px 0}.sf-kpi div{border:1px solid var(--cs-border,#d6dee7);border-radius:8px;padding:10px 18px;min-width:130px}.sf-kpi b{display:block;font-size:26px;font-variant-numeric:tabular-nums}.sf-kpi span{font-size:12px;color:#667589}.sf-kpi .warn b{color:var(--cs-danger,#c53b3b)}.table-scroll td{white-space:nowrap;font-variant-numeric:tabular-nums}.bar{display:inline-block;width:70px;height:6px;background:#e8edf3;border-radius:3px;margin-right:6px;vertical-align:middle}.bar u{display:block;height:100%;background:var(--cs-primary,#2563eb);border-radius:3px}.bar.t u{background:#9aa8ba}.bad{color:var(--cs-danger,#c53b3b)}.lk{border:0;background:none;color:var(--cs-primary,#2563eb);cursor:pointer;font:inherit}</style>
