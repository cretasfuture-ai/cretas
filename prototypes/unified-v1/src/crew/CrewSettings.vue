<script setup lang="ts">
// 第七批 · 数字班组配置（R27 G14，放在「设置」下；班组首页底部只留跳转）。
// ① 本厂启用哪几位：改动要写原因，保存后回读，留调整记录；② 每位员工管哪些权限模块、当前身份能查 / 能办；
// ③ 一线岗位 → 数字员工的对应：目前是代码常量（crew-config.ts），不能在这里改，如实写明（缺口 D4）。
import {computed,reactive,watch} from 'vue'
import {navigate} from '../frame/store'
import {C,saveConfig,perms,whoAmI} from './crew-store'
import {CREW,DEMO_ROLES,crewPerm,modCn,type CrewId} from './crew-config'
import {bjDate} from './crew-facts'
import {confirmReason} from '../frame/ai-protocol'
import CrewAvatar from './CrewAvatar.vue'
const on=(id:CrewId)=>C.state?C.state.config.enabled[id]!==false:true
const draft=reactive<Record<string,boolean>>({})
watch(()=>C.state?.config.enabled,e=>{for(const c of CREW)draft[c.id]=e?e[c.id]!==false:true},{immediate:true,deep:true})
const dirty=computed(()=>CREW.some(c=>draft[c.id]!==on(c.id)))
async function save(){const r=await confirmReason({title:'调整本厂启用的数字员工',object:CREW.filter(c=>draft[c.id]).map(c=>c.name).join('、')||'（一位都不启用）',message:'停用的员工不再出现在班组首页、手机端入口和「发现的问题」里；已有记录保留',okLabel:'保存'});if(r===null)return
  try{await saveConfig({...draft},r);C.notice='已保存并回读：本厂启用 '+CREW.filter(c=>on(c.id)).length+' 位'}catch(e){C.error=e instanceof Error?e.message:'保存失败'}}
const log=computed(()=>(C.state?.config.log||[]).slice().reverse() as {at:string;by:string;action:string;reason?:string;before?:Record<string,boolean>;after?:Record<string,boolean>}[])
const diff=(l:{before?:Record<string,boolean>;after?:Record<string,boolean>})=>CREW.filter(c=>l.before&&l.after&&l.before[c.id]!==l.after[c.id]).map(c=>c.name+(l.after![c.id]?' 启用':' 停用')).join('、')||'无变化'
const permText={rw:'能查能办',r:'只能查','-':'没有权限'} as Record<string,string>
// 和手机端一致：只列一线三个岗位
const mobile=Object.entries(DEMO_ROLES).filter(([k,r])=>r.mobile&&['operator','keeper','inspector'].includes(k)).map(([k,r])=>({role:r.label.replace('（演示）',''),crew:CREW.find(c=>c.id===r.mobile)!,key:k}))
</script>
<template><div>
<p v-if="C.ws==='ydwm'" class="empty">味美工作区没有数字班组演示数据；班组配置在六膳门工作区演示。</p>
<template v-else>
<section class="panel"><header><h2>本厂启用哪几位 <small class="muted">各厂按需启用；能办什么另由登录人权限决定</small></h2><button class="btn primary" type="button" data-testid="crew-settings-save" :disabled="!dirty||!C.state?.writable" @click="save">保存启用</button></header>
  <div class="body" style="display:grid;gap:8px">
    <label v-for="c in CREW" :key="c.id" style="display:flex;gap:10px;align-items:center"><input v-model="draft[c.id]" type="checkbox" style="height:auto"><CrewAvatar :name="c.name" :size="24"/><b>{{c.name}}</b><span class="muted">{{c.duty}}</span></label>
    <p v-if="!C.state?.writable" class="muted">没带验收会话，不能保存（地址加 ?acceptanceSession=test-f-r7-20261113）</p>
  </div></section>
<section class="panel"><header><h2>能力跟着权限走 <small class="muted">当前：{{whoAmI()}}</small></h2></header>
  <table class="list"><thead><tr><th>数字员工</th><th>管的权限模块</th><th>当前身份</th><th>能办的事（写操作都要先确认）</th><th>还办不了</th></tr></thead>
  <tbody><tr v-for="c in CREW" :key="c.id"><td>{{c.name}}</td><td>{{c.modules.map(m=>modCn(m)+'：'+permText[(perms()||{})[m]||'-']).join('、')}}</td><td><span class="perm" :class="crewPerm(c,perms())">{{permText[crewPerm(c,perms())]}}</span></td>
    <td>{{c.capabilities.filter(x=>x.backend!=='gap').map(x=>x.label).join('；')}}</td><td class="muted">{{c.capabilities.filter(x=>x.backend==='gap').map(x=>x.label+'（'+x.note+'）').join('；')}}</td></tr></tbody></table></section>
<section class="panel"><header><h2>一线岗位 → 数字员工 <small class="muted">手机端按岗位只给一个入口</small></h2></header>
  <table class="list"><thead><tr><th>一线岗位</th><th>手机端入口</th><th>本厂是否启用</th></tr></thead>
  <tbody><tr v-for="m in mobile" :key="m.key"><td>{{m.role}}</td><td>{{m.crew.name}}</td><td>{{on(m.crew.id)?'启用':'未启用（手机端提示找管理员）'}}</td></tr></tbody></table>
  <p class="body muted">这张对应表目前写在原型代码里，不能在这里改；做成每个工厂可配置，列在缺口清单里。</p></section>
<section class="panel"><header><h2>调整记录 <small class="muted">{{log.length}} 条</small></h2></header>
  <p v-if="!log.length" class="body muted">还没有调整过。</p>
  <table v-else class="list"><thead><tr><th>时间</th><th>谁</th><th>动作</th><th>变化</th><th>原因</th></tr></thead>
  <tbody><tr v-for="(l,k) in log" :key="k"><td>{{bjDate(l.at)}}</td><td>{{l.by}}</td><td>{{l.action}}</td><td>{{diff(l)}}</td><td>{{l.reason||''}}</td></tr></tbody></table></section>
<p class="footnote"><button class="btn link" type="button" @click="navigate('/crew',{})">回到班组首页</button></p>
</template>
</div></template>
