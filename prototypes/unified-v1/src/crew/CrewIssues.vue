<script setup lang="ts">
// 第七批 R33 · 「发现的问题」一页（F 是 Owner；原「主动汇报」并入这里，/crew/reports 仍可打开）。
// 按来源分四类：数字员工汇报 / 规则预警 / 对账差异 / 数据缺口；数据疑点（R30）单独一类，不和异常混在一起。
// 来源：数字员工汇报 = crew-reports.ts 现算（按快照日）；规则预警 = E 线协同任务规则的未处理提醒；
// 对账差异 = A 线称重对账、C 线入库批次对账接口（和 D 的对账汇总页同一来源）；数据缺口 = 黑湖未返回的工单 + 说明类汇报。
// 汇报可转待办（生成预览进「等我确认」）；任何一条都可关闭：先选结论（已处理 / 误报 / 接受偏差）再写一句，可恢复。只读来源，不改任何一边。
import {computed,onMounted,ref,watch} from 'vue'
import {S,navigate} from '../frame/store'
import {C,src,createInbox,reportState,snapshotDay,itemStatus} from './crew-store'
import {CREW,crewById,type CrewId} from './crew-config'
import {buildReports,type Report} from './crew-reports'
import {confirmReason,cretasUI,reconcile} from '../frame/ai-protocol'
import type {Draft} from './crew-drafts'
import CrewAvatar from './CrewAvatar.vue'

type Source='汇报'|'预警'|'对账'|'缺口'|'疑点'
interface Issue{key:string;source:Source;level:string;employee?:CrewId;title:string;summary:string;basis:string[];owner?:string;at:string;draft?:()=>Draft;link?:{label:string;route:string}}
const SOURCES:{id:Source;label:string;hint:string}[]=[
  {id:'汇报',label:'数字员工汇报',hint:'数字员工扫描数据后发现的问题和提醒（已排除数据疑点）'},
  {id:'预警',label:'规则预警',hint:'协同任务规则触发、还没处理的提醒（检验不合格未建任务、任务超期、首检未做）'},
  {id:'对账',label:'对账差异',hint:'Cretas 与黑湖两边记录对不上的（称重报工、入库批次）'},
  {id:'缺口',label:'数据缺口',hint:'缺数据、算不出的地方：黑湖没返回的工单、没有标准、报工没称重'},
  {id:'疑点',label:'数据疑点',hint:'录入可能有误的记录（R30）：不当异常，只列出来找谁核'}]
// G 的骨架链接用 ?kind=…，这里映射到来源（没有数据来源的类别如实说明）
const KIND:Record<string,Source>={shortage:'汇报',yield:'汇报',stock:'汇报',alert:'预警',rule:'预警',reconcile:'对账',gap:'缺口',suspect:'疑点'}
const q=computed(()=>S.query)
const source=ref<Source|''>('')
const crew=computed(()=>q.value.get('crew')||'')
watch(q,v=>{const s=v.get('source')||'';const k=v.get('kind')||'';source.value=(SOURCES.some(x=>x.id===s)?s:KIND[k]||'') as Source|''},{immediate:true})
const unknownKind=computed(()=>{const k=q.value.get('kind')||'';return k&&!KIND[k]?k:''})

// ---- 规则预警（E 线） ----
const rules=ref<{reminders:{id:string;rule:string;title:string;role:string;status:string;at:string;ref?:{code?:string}}[];rules:{id:string;name:string;enabled:boolean}[]}|null>(null),rulesErr=ref('')
// ---- 对账（A、C 线接口，和 D 对账汇总页同一来源） ----
interface RS{title:string;owner:string;url:string;state:'loading'|'ok'|'na'|'error';msg?:string;counts?:Record<string,number>;asOf?:string|null;rows?:{label:string;state:string;owner:string}[]}
const recon=ref<RS[]>([
  {title:'称重报工 ↔ 黑湖报工',owner:'车间',url:'/__unified/a/api/weighin-reconcile?ws=lsm',state:'loading'},
  {title:'入库批次 ↔ 黑湖入库单',owner:'仓库',url:'/__lsm/supply/batch-reconcile',state:'loading'}])
const sess=()=>C.session
async function loadRecon(r:RS){
  try{const s=sess(),res=await fetch(r.url+(s?(r.url.includes('?')?'&':'?')+'acceptanceSession='+encodeURIComponent(s):''),{headers:s?{'x-acceptance-session':s}:{}}),b=await res.json().catch(()=>({}))
    if(res.status===404||res.status===503){r.state='na';r.msg='这项对账接口没接上，本页不推算';return}
    if(!res.ok||!b.success)throw Error(b.message||'读取失败')
    const d=b.data;if(d.available===false){r.state='na';r.msg=d.reason||'当前工作区没有这项对账';return}
    const rows=(d.rows||[]) as {key:string;label?:string;owner?:string;cretas:Record<string,unknown>|null;blacklake:Record<string,unknown>|null}[]
    const fields=(d.fields||[]).map((f:{key:string;label:string})=>({key:f.key,label:f.label,digits:f.key==='count'?0:2,cretas:(x:Record<string,unknown>)=>x[f.key],blacklake:(x:Record<string,unknown>)=>x[f.key]}))
    const res2=reconcile({cretas:rows.filter(x=>x.cretas).map(x=>({...x.cretas,__k:x.key,__l:x.label||''})),blacklake:rows.filter(x=>x.blacklake).map(x=>({...x.blacklake,__k:x.key,__l:x.label||''})),key:(x:Record<string,unknown>)=>x.__k,label:(x:Record<string,unknown>|null)=>String(x?.__l||''),fields} as never)
    r.counts=res2.counts as Record<string,number>;r.asOf=d.asOf||null
    const own=(k:string)=>rows.find(x=>x.key===k)?.owner||r.owner
    r.rows=(res2.rows as {key:string;state:string;label?:string}[]).filter(x=>x.state!=='match').map(x=>({label:x.label||rows.find(y=>y.key===x.key)?.label||x.key,state:x.state,owner:own(x.key)}));r.state='ok'}
  catch(e){r.state='error';r.msg=e instanceof Error?e.message:'读取失败'}}
async function loadAllSources(){
  if(C.ws!=='lsm')return
  rulesErr.value='';try{const s=sess(),r=await fetch('/__unified/e/api/rules?ws=lsm'+(s?'&acceptanceSession='+encodeURIComponent(s):'')),b=await r.json();if(!b.success)throw Error(b.message);rules.value=b.data}catch(e){rules.value=null;rulesErr.value=e instanceof Error?e.message:'读取失败'}
  for(const r of recon.value){r.state='loading';loadRecon(r)}}
onMounted(loadAllSources)
watch(()=>[C.ws,C.session],loadAllSources)

const SL:Record<string,string>={diff:'有差额',cretasOnly:'只在 Cretas',blacklakeOnly:'只在黑湖'}
const snapAt=computed(()=>S.source?new Date(Date.parse(S.source.checkedAt)+8*3600e3).toISOString().slice(0,16).replace('T',' '):'')
const fromReport=(r:Report):Issue=>({key:r.key,source:r.level==='数据疑点'?'疑点':r.level==='说明'?'缺口':'汇报',level:r.level,employee:r.employee,title:r.title,summary:r.summary,basis:r.basis,owner:r.owner,at:r.at,draft:r.draft,link:r.link})
const all=computed<Issue[]>(()=>{
  if(!src())return []
  const out:Issue[]=buildReports(src(),C.inv).filter(r=>C.state?.config.enabled[r.employee]!==false).map(fromReport)
  for(const m of rules.value?.reminders||[])if(m.status==='open')out.push({key:'rule:'+m.id,source:'预警',level:'预警',title:m.title,summary:'规则「'+(rules.value?.rules.find(x=>x.id===m.rule)?.name||m.rule)+'」触发'+(m.ref?.code?' · '+m.ref.code:''),basis:['触发时间 '+m.at.slice(0,16).replace('T',' ')+'（UTC）'],owner:m.role||'负责岗位',at:m.at.slice(0,16).replace('T',' '),link:{label:'打开协同任务',route:'/collab/tasks'}})
  for(const r of recon.value)if(r.state==='ok'&&r.counts){const n=(r.counts.diff||0)+(r.counts.cretasOnly||0)+(r.counts.blacklakeOnly||0);if(!n)continue
    out.push({key:'recon:'+r.title,source:'对账',level:'对账',title:'对账差异 · '+r.title,summary:Object.entries(SL).map(([k,l])=>l+' '+(r.counts![k]||0)).join('，')+(r.counts.blacklakeOnly?'（只在黑湖 = Cretas 还没录，多是待称重 / 待建批次）':''),
      basis:[...(r.rows||[]).filter(x=>x.state!=='blacklakeOnly').slice(0,30).map(x=>SL[x.state]+' · '+x.label+' · 找 '+x.owner),...((r.rows||[]).filter(x=>x.state==='blacklakeOnly').length?['只在黑湖 '+(r.rows||[]).filter(x=>x.state==='blacklakeOnly').length+' 条（前 10 条）：',...(r.rows||[]).filter(x=>x.state==='blacklakeOnly').slice(0,10).map(x=>x.label+' · 找 '+x.owner)]:[])],
      owner:r.owner,at:r.asOf?new Date(Date.parse(r.asOf)+8*3600e3).toISOString().slice(0,16).replace('T',' '):'未知',link:{label:'打开对账汇总',route:'/lsm/reconcile'}})}
  const gaps=((S.source as unknown as {sourceGaps?:{id:unknown;projectCode:string;lastSeenAt:string}[]})?.sourceGaps)||[]
  if(gaps.length)out.push({key:'gap:source',source:'缺口',level:'说明',title:'黑湖这次没返回的工单 '+gaps.length+' 张',summary:'此前看得到、这次快照没返回；不等于删除或取消，不计入统计',basis:gaps.map(g=>'工单 '+g.projectCode+' · 最后可见 '+String(g.lastSeenAt).slice(0,16).replace('T',' ')),owner:'找黑湖管理员核对',at:snapAt.value})
  return out})
const list=computed(()=>all.value.filter(i=>(!source.value||i.source===source.value)&&(!crew.value||i.employee===crew.value)))
const count=(s:Source)=>all.value.filter(i=>i.source===s&&!(C.state?.reports[i.key]?.status==='dismissed')).length

// ---- 状态：关联待办 / 关闭 ----
const DEAD=['cancelled','failed','unknown']
function linked(i:Issue){const ix=C.state?.inbox||[],s0=C.state?.reports[i.key];let x=s0?.inboxId?ix.find(y=>y.id===s0.inboxId):undefined
  if(!x&&i.draft){const k=i.draft().sourceKey;x=[...ix].reverse().find(y=>y.sourceKey===k)}return x}
function st(i:Issue){const s0=C.state?.reports[i.key];if(s0?.status==='dismissed')return {kind:'closed',text:(s0.conclusion||'已关闭')+'：'+s0.reason}
  const x=linked(i);if(x&&!DEAD.includes(x.status))return {kind:'converted',text:'已转待办 · '+itemStatus(x).label}
  if(x&&DEAD.includes(x.status))return {kind:'open',text:'上次待办'+itemStatus(x).label+'，可重新处理'}
  return {kind:'open',text:''}}
const open=ref('')
async function toTask(i:Issue){if(!i.draft)return;try{const it=await createInbox(i.draft());await reportState(i.key,'converted','',it.id);C.notice='已转成待办，预览在「等我确认」：'+it.title}catch(e){C.error=(e instanceof Error?e.message:'失败')+'。没有执行任何写操作。'}}
function close(i:Issue,ev:MouseEvent){
  const pick=async(conclusion:string)=>{const why=await confirmReason({title:'关闭 · '+conclusion,object:i.title,message:'关闭后不再算进「发现的问题」；来源数据不变，可以恢复',reasonLabel:'说明',okLabel:'关闭'});if(why===null)return
    try{await reportState(i.key,'dismissed',why,'',conclusion)}catch(e){C.error=e instanceof Error?e.message:'失败'}}
  cretasUI().menu({anchor:ev.currentTarget as HTMLElement,items:[{label:'已处理',description:'问题已经处理掉了',onSelect:()=>pick('已处理')},{label:'误报',description:'数据或规则误判，不是真问题',onSelect:()=>pick('误报')},{label:'接受偏差',description:'是真的，但在可接受范围内',onSelect:()=>pick('接受偏差')}]})}
async function reopen(i:Issue){try{await reportState(i.key,'open')}catch(e){C.error=e instanceof Error?e.message:'失败'}}
function setQ(k:string,v:string){const o:Record<string,string>={};S.query.forEach((val,key)=>{if(key!=='kind')o[key]=val});if(v)o[k]=v;else delete o[k];if(C.ws==='ydwm')o.ws='ydwm';navigate('/crew/issues',o)}
function go(route:string){const [p,qs]=route.split('?');const o=Object.fromEntries(new URLSearchParams(qs||''));if(C.ws==='ydwm')o.ws='ydwm';navigate(p,o)}
const tone:Record<string,string>={问题:'danger',提醒:'warning',数据疑点:'info',说明:'neutral',预警:'warning',对账:'info'}
const SRC_LABEL=Object.fromEntries(SOURCES.map(s=>[s.id,s.label]))
</script>
<template><div>
<p v-if="C.ws==='ydwm'" class="empty">味美工作区没有数字班组演示数据（只有本机练习数据）；「发现的问题」在六膳门工作区。</p>
<template v-else>
<nav class="crew-tabs" aria-label="按来源" style="margin-bottom:10px">
  <button type="button" :aria-current="!source?'page':undefined" @click="setQ('source','')">全部 <span class="count">{{all.length}}</span></button>
  <button v-for="s in SOURCES" :key="s.id" type="button" :title="s.hint" :aria-current="source===s.id?'page':undefined" @click="setQ('source',s.id)">{{s.label}} <span class="count">{{count(s.id)}}</span></button>
</nav>
<div class="crew-strip">
  <label class="crew-role">数字员工<select :value="crew" aria-label="按数字员工筛选" @change="setQ('crew',($event.target as HTMLSelectElement).value)"><option value="">全部</option><option v-for="c in CREW" :key="c.id" :value="c.id">{{c.name}}</option></select></label>
  <span v-if="src()">按快照日 {{snapshotDay()}}；{{source?SOURCES.find(s=>s.id===source)!.hint:'汇报里的出成按报工良品（后道 ÷ 前道），排除数据疑点后相邻两单下降 3 个百分点以上才算「问题」'}}</span>
  <span v-if="unknownKind" class="warn">「{{unknownKind}}」这类问题目前没有数据来源（例如设备告警），没有可列的</span>
</div>
<p v-if="!src()" class="empty">正在读取黑湖快照…</p>
<p v-if="source==='预警'&&rulesErr" class="error">规则预警没读到：{{rulesErr}}</p>
<p v-if="source==='预警'&&rules&&!count('预警')" class="empty">规则都在运行（{{rules.rules.filter(r=>r.enabled).map(r=>r.name).join('、')}}），本会话没有未处理的提醒。</p>
<template v-if="source==='对账'"><p v-for="r in recon.filter(x=>x.state!=='ok')" :key="r.title" class="muted" style="margin-bottom:6px">{{r.title}}：{{r.state==='loading'?'正在读取…':r.msg}}</p></template>
<div style="display:grid;gap:10px">
  <article v-for="i in list" :key="i.key" class="panel" :data-issue="i.key" :data-source="i.source" style="margin:0">
    <header><div style="display:flex;gap:10px;align-items:center"><CrewAvatar v-if="i.employee" :name="crewById(i.employee)!.name" :state="i.level==='问题'?'alert':'idle'" :size="24"/>
      <div><b v-if="i.employee">{{crewById(i.employee)!.name}}</b> <span class="chip">{{SRC_LABEL[i.source]}}</span> <span v-if="i.level!==i.source&&i.level!=='对账'&&i.level!=='预警'" class="chip" :class="tone[i.level]">{{i.level}}</span> {{i.title}}<div class="muted">{{i.summary}}<template v-if="i.owner"> · 找谁：{{i.owner}}</template> · 数据时点 {{i.at}}</div></div></div>
      <div class="actions">
        <span v-if="st(i).text" class="chip" :class="st(i).kind==='converted'?'success':st(i).kind==='closed'?'':'warning'">{{st(i).text}}</span>
        <button class="btn" type="button" @click="open=open===i.key?'':i.key">{{open===i.key?'收起依据':'看依据'}}</button>
        <button v-if="i.link" class="btn" type="button" @click="go(i.link.route)">{{i.link.label}}</button>
        <button v-if="st(i).kind==='closed'" class="btn" type="button" :disabled="!C.state?.writable" @click="reopen(i)">恢复</button>
        <template v-else-if="st(i).kind==='open'"><button class="btn" type="button" :disabled="!C.state?.writable" @click="close(i,$event)">关闭…</button>
          <button v-if="i.draft" class="btn primary" type="button" :disabled="!C.state?.writable" @click="toTask(i)">转成待办</button></template>
      </div></header>
    <div v-if="open===i.key" class="body"><ul style="margin:0;padding-left:18px;font-size:13px;line-height:1.7"><li v-for="(b,k) in i.basis" :key="k">{{b}}</li></ul></div>
  </article>
</div>
<p class="footnote">本页按快照现算，不是后台定时；正式后端已有通知与定时任务底座，原型还没接。关闭只记在数字班组记录里，不改来源数据。</p>
</template>
</div></template>
