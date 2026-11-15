<script setup lang="ts">
// 第八批 R41：个人 OA「审批中心」四个视图迁入六膳门（待我处理 / 我发起的 / 已处理 / 抄送我的）。
// 只读聚合，不新造审批流：黑湖报工审批（只读，办理在黑湖）+ Cretas 协同任务（E 线，只读读取）。
// 本原型不识别登录人，「我」用岗位 / 发起人下拉选择代替；黑湖快照没有审批人、发起人、抄送字段，页面直接写明。
import {computed,onMounted,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {S,rs,navigate,text} from '../frame/store'
import {fmtNumber} from '../frame/ai-protocol'
interface Task{id:string;no:string;title:string;status:string;due:string;priority?:string;assignee?:{role?:string;name?:string};createdBy?:string;createdAt?:string;updatedAt?:string;related?:{type:string;code?:string;id?:string}[]}
const SEG=[{path:'/workflow/pending',label:'待我处理'},{path:'/workflow/my-created',label:'我发起的'},{path:'/workflow/acted',label:'已处理'},{path:'/workflow/copied',label:'抄送我的'}]
const seg=computed(()=>SEG.find(s=>s.path===S.path)||SEG[0])
const session=computed(()=>{const ok=(v:string|null)=>v&&/^test-[a-z0-9-]{8,64}$/.test(v)?v:'';let v=ok(S.query.get('acceptanceSession'));if(!v)try{v=ok(new URLSearchParams(window.top!.location.search).get('acceptanceSession'))}catch{/* 跨域时只认 frame 地址 */}return v})
const tasks=ref<Task[]>([]),loaded=ref(false),err=ref(''),busy=ref(false)
async function load(){busy.value=true;err.value=''
  try{const r=await fetch('/__unified/e/api/state?ws=lsm'+(session.value?'&acceptanceSession='+encodeURIComponent(session.value):'')),b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'协同任务读取失败（'+r.status+'）');tasks.value=Array.isArray(b.data.tasks)?b.data.tasks:[];loaded.value=true}
  catch(e){err.value=e instanceof Error?e.message:'协同任务读取失败'}finally{busy.value=false}}
onMounted(load);watch(session,load)
const STATUS:Record<string,string>={TODO:'未开始',DOING:'进行中',DONE:'已完成',CANCELLED:'已取消'}
const open=(t:Task)=>!['DONE','CANCELLED'].includes(t.status)
// 黑湖报工审批：approvalStatus 0 待审批 / 10 已批准 / 20 未通过
const outs=computed(()=>rs('output')),cnt=(s:string)=>outs.value.filter(r=>String(r.approvalStatus)===s).length
const lakePending=computed(()=>cnt('0')),lakeDone=computed(()=>cnt('10')+cnt('20'))
const role=ref(''),maker=ref(''),kw=ref(''),type=ref<''|'task'|'lake'>('')
const roles=computed(()=>[...new Set(tasks.value.map(t=>t.assignee?.role||'').filter(Boolean))].sort())
const makers=computed(()=>[...new Set(tasks.value.map(t=>t.createdBy||'').filter(Boolean))].sort())
interface Row{kind:'task'|'lake';no:string;title:string;type:string;node:string;who:string;role:string;at:string;go:string;task?:Task}
const taskRow=(t:Task):Row=>({kind:'task',no:t.no,title:t.title,type:'协同任务',node:STATUS[t.status]||t.status,who:t.createdBy||'未记录',role:t.assignee?.role||'未指定',at:t.due?'截止 '+t.due:'未设截止',go:'/collab/tasks',task:t})
const LAKE_NODE:Record<string,string>={'0':'待审批','10':'已批准','20':'未通过'}
const lakeAll=computed(()=>seg.value.path==='/workflow/pending'?outs.value.filter(r=>String(r.approvalStatus)==='0'):seg.value.path==='/workflow/acted'?outs.value.filter(r=>['10','20'].includes(String(r.approvalStatus))).sort((x,y)=>String(y.starttimeofReport||'').localeCompare(String(x.starttimeofReport||''))):[])
const LAKE_SHOW=100
const lakeRows=computed<Row[]>(()=>lakeAll.value.slice(0,LAKE_SHOW).map(r=>({kind:'lake',no:'报工 '+r.id,title:text(r.productName),type:'黑湖报工审批',node:LAKE_NODE[String(r.approvalStatus)]||text(r.approvalStatus),who:'黑湖未提供',role:'黑湖未提供',at:String(r.starttimeofReport||'').slice(0,16),go:'/production/approval'})))
const rows=computed<Row[]>(()=>{
  let t=tasks.value
  if(seg.value.path==='/workflow/pending')t=t.filter(open);else if(seg.value.path==='/workflow/acted')t=t.filter(x=>!open(x));else if(seg.value.path==='/workflow/copied')t=[]
  if(role.value&&seg.value.path!=='/workflow/my-created')t=t.filter(x=>(x.assignee?.role||'')===role.value)
  if(maker.value&&seg.value.path==='/workflow/my-created')t=t.filter(x=>(x.createdBy||'')===maker.value)
  let a:Row[]=[...t.map(taskRow),...lakeRows.value]
  if(type.value)a=a.filter(x=>x.kind===type.value)
  const k=kw.value.trim();if(k)a=a.filter(x=>(x.no+x.title+x.type+x.role+x.who).includes(k))
  return a})
const total=computed(()=>({
  '/workflow/pending':tasks.value.filter(open).length+lakePending.value,
  '/workflow/my-created':tasks.value.length,
  '/workflow/acted':tasks.value.filter(x=>!open(x)).length+lakeDone.value,
  '/workflow/copied':0} as Record<string,number>))
const oldest=computed(()=>{const a=tasks.value.filter(open).filter(t=>t.due).sort((x,y)=>x.due.localeCompare(y.due))[0];return a?a.no+'（截止 '+a.due+'）':''})
const go=(p:string)=>navigate(p)
</script>
<template>
<section class="oa-page">
<header class="oa-head"><div><p>个人 OA · 六膳门工作区</p><h1>审批中心</h1><small>在一处按「与我的关系」查看；这里只读聚合，办理回到各自的页面。本原型不识别登录人，按岗位或发起人筛选代替「我」。</small></div><div class="actions"><UButton size="sm" color="neutral" variant="outline" :loading="busy" @click="load()">刷新</UButton></div></header>
<nav class="oa-seg" role="tablist" aria-label="与我的关系"><button v-for="s in SEG" :key="s.path" type="button" role="tab" :aria-selected="s.path===seg.path" :class="{on:s.path===seg.path}" @click="go(s.path)">{{s.label}} <b>{{total[s.path]}}</b></button></nav>
<p v-if="err" role="alert" class="error">{{err}}</p>
<div v-if="seg.path==='/workflow/pending'" class="oa-sum"><span>等我处理 <b>{{total['/workflow/pending']}}</b> 张</span><span>最早到期：{{oldest||'没有设置截止日的待办'}}</span></div>
<div class="oa-filter"><label>业务类型<select v-model="type"><option value="">全部来源</option><option value="task">协同任务</option><option value="lake">黑湖报工审批</option></select></label>
<label v-if="seg.path!=='/workflow/my-created'">负责岗位<select v-model="role"><option value="">全部岗位</option><option v-for="r in roles" :key="r" :value="r">{{r}}</option></select></label>
<label v-else>发起人<select v-model="maker"><option value="">全部发起人</option><option v-for="r in makers" :key="r" :value="r">{{r}}</option></select></label>
<label>关键词<input v-model="kw" placeholder="单号、标题"></label></div>
<div class="oa-tablewrap"><table class="oa-table" aria-label="审批单列表"><thead><tr><th>单据</th><th>类型</th><th>当前节点</th><th>发起 / 申请人</th><th>负责岗位</th><th>时间</th><th>操作</th></tr></thead>
<tbody><tr v-for="r in rows" :key="r.kind+r.no"><td><b>{{r.no}}</b><small class="muted"> {{r.title}}</small></td><td>{{r.type}}</td><td>{{r.node}}</td><td>{{r.who}}</td><td>{{r.role}}</td><td>{{r.at}}</td><td><button type="button" class="link" @click="go(r.go)">{{r.kind==='task'?'去协同任务办理':'去报工审批页'}}</button></td></tr>
<tr v-if="lakeAll.length>LAKE_SHOW&&type!=='task'"><td colspan="7" class="muted">黑湖报工审批共 {{fmtNumber(lakeAll.length,{digits:0})}} 条，这里按报工时间只显示最新 {{LAKE_SHOW}} 条；全部在<button type="button" class="link" @click="go('/production/approval')">报工撤回审批</button>页。</td></tr>
<tr v-if="!rows.length"><td colspan="7" class="muted">{{loaded?'当前筛选下没有记录。':'正在读取…'}}</td></tr></tbody></table></div>
<section class="oa-gap" aria-label="来源说明"><h2>这一视图的数据来源与缺口</h2>
<ul v-if="seg.path==='/workflow/pending'"><li>黑湖报工审批：快照里待审批的报工 <b>{{lakePending}}</b> 条（已批准 {{fmtNumber(cnt('10'),{digits:0})}} 条，未通过 {{cnt('20')}} 条）；黑湖没有「审批人」字段，无法按个人切分，办理在黑湖，这里只读。</li><li>协同任务：Cretas 自有，按负责岗位筛选；点「去协同任务办理」进入原页面处理。</li><li>请购单审批、销售订单审批：黑湖没有此类审批数据，这里不列。</li></ul>
<ul v-else-if="seg.path==='/workflow/my-created'"><li>协同任务按创建人列出，创建人是任务里记录的岗位名，不是登录账号。</li><li>黑湖没有「发起人」字段，报工、请购都无法判断是谁发起，这里不列。</li></ul>
<ul v-else-if="seg.path==='/workflow/acted'"><li>协同任务：已完成、已取消的任务。</li><li>黑湖报工审批：已批准 {{fmtNumber(cnt('10'),{digits:0})}} 条、未通过 {{cnt('20')}} 条，列表按报工时间只显示最新 100 条，全部在<button type="button" class="link" @click="go('/production/approval')">报工撤回审批</button>页。</li></ul>
<ul v-else><li>黑湖没有此类数据：快照里没有抄送关系，协同任务也没有抄送字段。需要时再由业务线确认补充入口，本页不造数据、不拿别的数据顶替。</li></ul></section>
</section>
</template>
<style scoped>
.oa-page{display:grid;grid-template-columns:minmax(0,1fr);gap:12px}.oa-tablewrap{overflow-x:auto}
.oa-head{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:flex-start;gap:8px 16px}.oa-head>div:first-child{min-width:0;flex:1 1 260px}.oa-head small{display:block;overflow-wrap:anywhere}.oa-head p{margin:0;font-size:12px;color:var(--cs-muted)}.oa-head h1{margin:2px 0;font-size:1.375rem}.oa-head small{color:var(--cs-muted);font-size:.75rem}
.oa-seg{display:inline-flex;flex-wrap:wrap;border:1px solid var(--cs-border);border-radius:8px;overflow:hidden;width:max-content;max-width:100%}.oa-seg button{flex:1 1 auto;border:0;background:none;padding:6px 12px;font-size:.875rem;cursor:pointer}.oa-seg button.on{background:var(--cs-primary-soft);color:var(--cs-primary);font-weight:600}
.oa-sum{display:flex;flex-wrap:wrap;gap:6px 24px;font-size:.875rem}
.oa-filter{display:flex;flex-wrap:wrap;gap:8px 16px}.oa-filter label{display:grid;gap:4px;font-size:.8125rem}.oa-filter select,.oa-filter input{height:32px;border:1px solid var(--cs-border);border-radius:6px;padding:0 8px;font-size:.875rem;background:var(--cs-surface)}
.oa-table{width:100%;border-collapse:collapse;font-size:.875rem;table-layout:auto}
.oa-table th,.oa-table td{padding:6px 8px;border-bottom:1px solid var(--cs-border);text-align:left;line-height:20px;max-width:none;white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:break-word}.oa-table td:first-child{min-width:10em}.oa-table th{font-weight:600;color:var(--cs-muted);font-size:.8125rem}
.oa-gap{border:1px solid var(--cs-border);border-radius:10px;padding:10px 14px;background:var(--cs-surface)}.oa-gap h2{font-size:.9375rem;margin:0 0 6px}.oa-gap ul{margin:0;padding-left:18px;display:grid;gap:4px;font-size:.8125rem}
.link{border:0;background:none;color:var(--cs-primary);cursor:pointer;padding:0;font:inherit}.link:hover{text-decoration:underline}
.muted{color:var(--cs-muted);font-size:.8125rem}
</style>
