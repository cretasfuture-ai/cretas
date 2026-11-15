<script setup lang="ts">
// 结构 3 · 统一的「等我确认」：所有写操作先出预览，人确认后才执行；按当前登录人权限只显示他有权确认的事项。
import {computed} from 'vue'
import {S,navigate} from '../frame/store'
import {C,mayConfirm,whoAmI,itemStatus} from './crew-store'
import {crewById,CREW,modCn} from './crew-config'
import {bjDate} from './crew-facts'
import CrewPreview from './CrewPreview.vue'
const crew=computed(()=>S.query.get('crew')||'')
const all=computed(()=>(C.state?.inbox||[]).filter(x=>!crew.value||x.employee===crew.value))
const mine=computed(()=>all.value.filter(x=>x.status==='pending'&&mayConfirm(x)).slice().reverse())
const hidden=computed(()=>all.value.filter(x=>x.status==='pending'&&!mayConfirm(x)))
const handled=computed(()=>all.value.filter(x=>x.status!=='pending').slice().reverse())
function filter(v:string){const q:Record<string,string>={};if(v)q.crew=v;if(C.ws==='ydwm')q.ws='ydwm';navigate('/crew/inbox',q)}
</script>
<template><div>
<div class="crew-strip"><span>当前：{{whoAmI()}}</span>
  <label class="crew-role">数字员工<select :value="crew" aria-label="按数字员工筛选" @change="filter(($event.target as HTMLSelectElement).value)"><option value="">全部</option><option v-for="c in CREW" :key="c.id" :value="c.id">{{c.name}}</option></select></label>
  <span v-if="hidden.length" data-testid="crew-inbox-hidden">另有 {{hidden.length}} 件需要别的岗位确认（{{[...new Set(hidden.map(x=>modCn(x.module)))].join('、')}}模块），按你的权限不显示</span></div>
<p v-if="!mine.length" class="empty">没有等你确认的事。{{hidden.length?'':'在数字员工工作台或「发现的问题」里让它办事，预览会进这里。'}}</p>
<div style="display:grid;gap:12px;margin-bottom:20px"><CrewPreview v-for="x in mine" :key="x.id" :item="x"/></div>
<section class="panel" v-if="handled.length"><header><h2>已处理 <small class="muted">{{handled.length}} 件</small></h2></header>
  <table class="list"><thead><tr><th>时间</th><th>数字员工</th><th>事项</th><th>结果</th><th>确认人 / 原因</th></tr></thead>
  <tbody><tr v-for="x in handled" :key="x.id"><td>{{bjDate(x.result?.at||x.log[x.log.length-1]?.at)}}</td><td>{{crewById(x.employee)?.name}}</td><td>{{x.title}}</td><td><span class="chip" :class="itemStatus(x).tone">{{itemStatus(x).label}}</span><div class="muted" v-if="x.result?.ref">{{x.result.ref.label}}</div><div class="muted" v-if="x.result?.note">{{x.result.note}}</div></td><td>{{x.confirmedBy||x.log.find(l=>l.reason)?.by}}<div class="muted">{{x.log.find(l=>l.reason)?.reason}}</div></td></tr></tbody></table></section>
</div></template>
