<script setup lang="ts">
// 结构 4 · 工作记录与额度：按「已交办 / 已办成 / 没办成 / 结果不明」分开显示（R29），另列原型演示未执行。
// 额度口径（R26 第 1、2 题已定）：每件已办成的业务事项 1 个额度；已交办（交给人、还没办完）、没办成、结果不明、原型演示、班组配置都不扣。
// 已交办的记录在读取时回读协同任务：任务完成 → 已办成并扣额度；任务取消 → 没办成。
import {computed} from 'vue'
import {S,navigate} from '../frame/store'
import {C,realToday as today} from './crew-store'
import {CREW,crewById} from './crew-config'
import {bjDate} from './crew-facts'
const crew=computed(()=>S.query.get('crew')||'')
const rows=computed(()=>(C.state?.records||[]).filter(r=>!crew.value||r.employee===crew.value).slice().reverse())
const cnt=(o:string,r=rows.value)=>r.filter(x=>x.outcome===o).length
const sum=computed(()=>{const r=rows.value;return {done:cnt('done'),handed:cnt('handed'),quota:r.reduce((a,x)=>a+x.quota,0),todayQuota:r.filter(x=>bjDate(x.at).slice(0,10)===today()&&x.quota).reduce((a,x)=>a+x.quota,0)}})
const per=computed(()=>CREW.map(c=>{const r=(C.state?.records||[]).filter(x=>x.employee===c.id);return {c,handed:cnt('handed',r),done:cnt('done',r),failed:cnt('failed',r),unknown:cnt('unknown',r),demo:cnt('demo',r),quota:r.reduce((a,x)=>a+x.quota,0)}}))
const tone:Record<string,string>={done:'success',handed:'info',failed:'danger',unknown:'neutral',demo:'neutral'}
function filter(v:string){const q:Record<string,string>={};if(v)q.crew=v;if(C.ws==='ydwm')q.ws='ydwm';navigate('/crew/ledger',q)}
function csv(){const head=['时间','数字员工','事项','结果','额度','依据','确认人','说明'],esc=(v:unknown)=>{let s=String(v??'');if(/^[=+\-@]/.test(s))s="'"+s;return '"'+s.replace(/"/g,'""')+'"'}
  const body=rows.value.map(r=>[bjDate(r.at),crewById(r.employee)?.name,r.title,r.label,r.quota,r.ref?.label||'',r.confirmedBy,r.note].map(esc).join(','))
  const blob=new Blob(['﻿'+[head.join(','),...body,'','导出时间 '+bjDate(new Date().toISOString())+'；会话 '+(C.session||'无')].join('\n')],{type:'text/csv'}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='数字班组工作记录.csv';a.click();URL.revokeObjectURL(a.href)}
</script>
<template><div>
<p v-if="C.ws==='ydwm'" class="empty">味美工作区没有数字班组的工作记录（味美只有本机练习数据）。</p>
<template v-else>
<div class="summary-row">
  <div><span>已办成（扣额度）</span><strong data-testid="crew-ledger-done">{{sum.done}}</strong></div>
  <div><span>已交办（等人办完，不扣）</span><strong data-testid="crew-ledger-handed">{{sum.handed}}</strong></div>
  <div><span>消耗业务额度（累计）</span><strong data-testid="crew-ledger-quota">{{sum.quota}}</strong></div>
  <div><span>今天消耗额度（{{today()}}）</span><strong>{{sum.todayQuota}}</strong></div>
</div>
<section class="panel"><header><h2>按数字员工</h2><span class="muted">额度口径：每件已办成的业务事项 1 个；已交办、没办成、结果不明、原型演示未执行、班组配置都不扣</span></header>
  <table class="list"><thead><tr><th>数字员工</th><th class="num">已交办</th><th class="num">已办成</th><th class="num">没办成</th><th class="num">结果不明</th><th class="num">原型演示未执行</th><th class="num">额度</th></tr></thead>
  <tbody><tr v-for="p in per" :key="p.c.id"><td>{{p.c.name}}</td><td class="num">{{p.handed}}</td><td class="num">{{p.done}}</td><td class="num">{{p.failed}}</td><td class="num">{{p.unknown}}</td><td class="num">{{p.demo}}</td><td class="num">{{p.quota}}</td></tr></tbody></table></section>
<section class="panel"><header><h2>工作记录 <small class="muted">{{rows.length}} 条</small></h2><div class="actions">
  <select :value="crew" aria-label="按数字员工筛选" @change="filter(($event.target as HTMLSelectElement).value)"><option value="">全部</option><option v-for="c in CREW" :key="c.id" :value="c.id">{{c.name}}</option></select>
  <button class="btn" type="button" :disabled="!rows.length" @click="csv">导出 CSV</button></div></header>
  <p v-if="!rows.length" class="empty">还没有记录。确认后交给人办的记「已交办」；任务办完、回读核实后才记「已办成」。</p>
  <table v-else class="list" data-testid="crew-ledger-table"><thead><tr><th>时间</th><th>数字员工</th><th>事项</th><th>结果</th><th class="num">额度</th><th>依据（回读到的记录）</th><th>确认人</th></tr></thead>
  <tbody><tr v-for="r in rows" :key="r.id"><td>{{bjDate(r.at)}}</td><td>{{crewById(r.employee)?.name}}</td><td>{{r.title}}</td><td><span class="chip" :class="tone[r.outcome]">{{r.label}}</span><div v-if="r.note" class="muted">{{r.note}}</div></td><td class="num">{{r.quota}}</td><td>{{r.ref?.label||'没有可核实的记录'}}</td><td>{{r.confirmedBy}}</td></tr></tbody></table></section>
<p class="footnote">这一页是「按办成的事收费」在产品里唯一看得见的地方。本原型记录保存在数字班组保存区（会话 {{C.session||'未带'}}），不是计费系统；真实计费、额度包、对账单见缺口清单。</p>
</template>
</div></template>
