<script setup lang="ts">
// 交付物 1、2 · 信息架构图 + 现有功能页 → 数字员工对应表。对应表按本线登记的 62 页，并实时对照 /__unified/menu.json：
// 菜单里新出现、本表没有的页列为「新页面，未归类」，不让覆盖率静默变低。
import {computed,onMounted,ref} from 'vue'
import {navigate} from '../frame/store'
import {C,perms} from './crew-store'
import {CREW,PAGE_MAP,crewById,crewPerm,modCn} from './crew-config'
const menu=ref<{route:string;label:string;group:string}[]>([]),menuErr=ref('')
onMounted(async()=>{try{const m=await (await fetch('/__unified/menu.json')).json();menu.value=m.groups.flatMap((g:{label:string;entries:{members:{route:string;label:string}[]}[]})=>g.entries.flatMap(e=>e.members.map(x=>({route:x.route,label:x.label,group:g.label}))))}catch(e){menuErr.value=e instanceof Error?e.message:'读取失败'}})
const known=new Set(PAGE_MAP.map(p=>p.route))
const extra=computed(()=>menu.value.filter(m=>!known.has(m.route)&&!m.route.startsWith('/crew')))
const missing=computed(()=>menu.value.length?PAGE_MAP.filter(p=>!menu.value.some(m=>m.route===p.route)):[])
const by=computed(()=>CREW.map(c=>({c,pages:PAGE_MAP.filter(p=>p.owner===c.id)})))
const none=computed(()=>PAGE_MAP.filter(p=>!p.owner))
function open(route:string){const [p,q]=route.split('?');const o=Object.fromEntries(new URLSearchParams(q||''));if(C.ws==='ydwm')o.ws='ydwm';navigate(p,o)}
const permText={rw:'能查能办',r:'只能查','-':'没有权限'} as Record<string,string>
</script>
<template><div>
<section class="panel ia"><header><h2>信息架构 <small class="muted">数字班组各页之间、与现有页面之间的关系</small></h2></header><div class="body">
<svg viewBox="0 0 1120 560" role="img" aria-label="数字班组信息架构图">
  <defs><marker id="ar" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10z" fill="#7d8ea3"/></marker></defs>
  <g font-size="13">
    <!-- 入口 -->
    <text x="20" y="28" font-weight="600" fill="#526278">入口</text>
    <rect x="20" y="40" width="190" height="40" rx="8" fill="#eef4fb" stroke="#9db8d4"/><text x="34" y="65">侧栏「数字班组」菜单</text>
    <rect x="20" y="92" width="190" height="40" rx="8" fill="#eef4fb" stroke="#9db8d4"/><text x="34" y="117">首页 / 老板厂长</text>
    <rect x="20" y="144" width="190" height="52" rx="8" fill="#eef4fb" stroke="#9db8d4"/><text x="34" y="166">现有页面「交给数字 X」</text><text x="34" y="185" font-size="11" fill="#526278">排产、库存、质检、成本页</text>
    <rect x="20" y="208" width="190" height="52" rx="8" fill="#eef4fb" stroke="#9db8d4"/><text x="34" y="230">一线手机端</text><text x="34" y="249" font-size="11" fill="#526278">按岗位只给一个入口</text>
    <!-- 班组层 -->
    <text x="270" y="28" font-weight="600" fill="#526278">数字班组（本线新建）</text>
    <rect x="270" y="40" width="200" height="56" rx="8" fill="#fff" stroke="#1b65a8" stroke-width="1.5"/><text x="284" y="63" font-weight="600">班组首页</text><text x="284" y="83" font-size="11" fill="#526278">五张卡：办成 / 等确认 / 问题</text>
    <rect x="270" y="120" width="200" height="72" rx="8" fill="#fff" stroke="#1b65a8" stroke-width="1.5"/><text x="284" y="143" font-weight="600">数字员工工作台 × 5</text><text x="284" y="162" font-size="11" fill="#526278">左对话 · 右任务（五种状态）</text><text x="284" y="179" font-size="11" fill="#526278">查询直接答，写操作出预览</text>
    <rect x="270" y="216" width="200" height="56" rx="8" fill="#fff" stroke="#1b65a8" stroke-width="1.5"/><text x="284" y="239" font-weight="600">发现的问题</text><text x="284" y="259" font-size="11" fill="#526278">四类来源 + 数据疑点 → 转待办</text>
    <rect x="270" y="296" width="200" height="56" rx="8" fill="#fff" stroke="#1b65a8" stroke-width="1.5"/><text x="284" y="319" font-weight="600">班组入职</text><text x="284" y="339" font-size="11" fill="#526278">口述 → 工序 → 规则 → 上岗条件</text>
    <rect x="540" y="120" width="190" height="72" rx="8" fill="#fff6e5" stroke="#b7791f" stroke-width="1.5"/><text x="554" y="146" font-weight="600">等我确认</text><text x="554" y="166" font-size="11" fill="#526278">按登录人权限过滤</text><text x="554" y="182" font-size="11" fill="#526278">每行预览都显示，确认才执行</text>
    <rect x="540" y="296" width="190" height="56" rx="8" fill="#fff" stroke="#1b65a8" stroke-width="1.5"/><text x="554" y="319" font-weight="600">工作记录与额度</text><text x="554" y="339" font-size="11" fill="#526278">只记回读核实的已办成</text>
    <!-- 现有接口 -->
    <text x="800" y="28" font-weight="600" fill="#526278">现有功能与接口（不变）</text>
    <rect x="800" y="40" width="300" height="44" rx="8" fill="#eaf6ef" stroke="#24724f"/><text x="814" y="60">协同任务（现有接口）</text><text x="814" y="76" font-size="11" fill="#24724f">本原型已接：确认后真写 test 会话</text>
    <rect x="800" y="96" width="300" height="44" rx="8" fill="#f4f6f9" stroke="#a9b8c7" stroke-dasharray="4 3"/><text x="814" y="116">称重报工 A · 入库批次 C · 检验单 E</text><text x="814" y="132" font-size="11" fill="#526278">还没接上，现标「原型演示，未执行」</text>
    <rect x="800" y="152" width="300" height="44" rx="8" fill="#f4f6f9" stroke="#a9b8c7"/><text x="814" y="172">黑湖快照：工单、报工、BOM、库存</text><text x="814" y="188" font-size="11" fill="#526278">只读；开单、财务在黑湖</text>
    <rect x="800" y="208" width="300" height="44" rx="8" fill="#f4f6f9" stroke="#a9b8c7"/><text x="814" y="228">账号权限 permissions/current</text><text x="814" y="244" font-size="11" fill="#526278">决定每位员工能查 / 能办</text>
    <rect x="800" y="264" width="300" height="44" rx="8" fill="#fdecec" stroke="#b42318" stroke-dasharray="4 3"/><text x="814" y="284">原型未接：产能、急单插单、通知、计费</text><text x="814" y="300" font-size="11" fill="#b42318">正式后端多数已有，见缺口清单</text>
    <!-- 连线 -->
    <path d="M210 60H270" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/><path d="M210 112H240V68H270" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/>
    <path d="M210 170H240V156H270" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/><path d="M210 234H250V176H270" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/>
    <path d="M370 96V120" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/>
    <path d="M470 156H540" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/><path d="M470 244H505V172H540" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/><path d="M470 324H515V184H540" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/>
    <path d="M730 140H765V62H800" stroke="#24724f" fill="none" marker-end="url(#ar)"/><path d="M730 160H780V118H800" stroke="#7d8ea3" stroke-dasharray="4 3" fill="none" marker-end="url(#ar)"/>
    <path d="M635 192V296" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/><text x="642" y="250" font-size="11" fill="#526278">回读核实后记一条</text>
    <path d="M800 174H760V200H470" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/><text x="560" y="214" font-size="11" fill="#526278">读事实</text>
    <path d="M540 74H470" stroke="#7d8ea3" fill="none" marker-end="url(#ar)"/><text x="482" y="66" font-size="11" fill="#526278">计数回到首页</text>
    <path d="M540 324H470" stroke="#7d8ea3" fill="none"/>
    <!-- 现有页面归属 -->
    <text x="20" y="400" font-weight="600" fill="#526278">现有功能页归属（{{PAGE_MAP.length}} 页）</text>
    <g v-for="(b,i) in by" :key="b.c.id"><rect :x="20+i*218" y="414" width="206" height="120" rx="8" fill="#fff" stroke="#dce3ed"/><text :x="32+i*218" y="436" font-weight="600">{{b.c.name}} · {{b.pages.length}} 页</text>
      <text v-for="(p,k) in b.pages.slice(0,4)" :key="p.route" :x="32+i*218" :y="456+k*16" font-size="11" fill="#526278">{{p.label}}</text><text v-if="b.pages.length>4" :x="32+i*218" y="524" font-size="11" fill="#526278">…另 {{b.pages.length-4}} 页</text></g>
    <text x="20" y="552" font-size="11" fill="#526278">暂不归任何人：{{none.length}} 页（首页、个人 OA、协同任务、车间大屏、驾驶舱、数据工作台等，见下表）</text>
  </g>
</svg></div></section>
<section class="panel"><header><h2>现有功能页 → 数字员工 <small class="muted">{{PAGE_MAP.length}} 页；菜单实时对照 {{menu.length||'未读取'}} 页</small></h2></header>
  <p v-if="menuErr" class="error">菜单没读到：{{menuErr}}</p>
  <p v-if="extra.length" class="notice">菜单里有 {{extra.length}} 页本表没登记（新页面，未归类）：{{extra.map(x=>x.group+' / '+x.label).join('、')}}</p>
  <p v-if="missing.length" class="muted" style="padding:0 16px">本表登记、当前工作区菜单没有的 {{missing.length}} 页：{{missing.map(x=>x.label).join('、')}}（模块关闭或另一工作区才有）</p>
  <table class="list" data-testid="crew-page-map"><thead><tr><th>菜单组</th><th>页面</th><th>权限模块</th><th>归属</th><th>当前身份</th><th>说明</th></tr></thead>
  <tbody><tr v-for="p in PAGE_MAP" :key="p.route"><td>{{p.group}}</td><td><button class="btn link" type="button" @click="open(p.route)">{{p.label}}</button></td><td>{{modCn(p.module)}}</td>
    <td><span v-if="p.owner" class="chip info">{{crewById(p.owner)!.name}}</span><span v-else class="chip">暂不归任何人</span></td>
    <td><span v-if="p.owner">{{permText[crewPerm({...crewById(p.owner)!,modules:[p.module]},perms())]}}</span></td><td class="muted">{{p.reason}}</td></tr></tbody></table></section>
<p class="footnote">归属按权限模块走，不写死岗位；一个页面多位员工都要用时，以「办事的人」归属，其余只读引用。</p>
</div></template>
