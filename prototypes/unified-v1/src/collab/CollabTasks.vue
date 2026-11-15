<script setup lang="ts">
// E4 协同任务列表 + E5 任务详情（指派、截止、状态、评论、关联单据）。超期、合法状态由代码算。
// 第二批：任务类型（BL 19：颜色、标题规则、默认负责人、专属字段、响应与处理时长）与规则提醒（BL 24：提醒、规则、执行日志）。
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {batchNoSearch,matchNos,relativeDate,relativeRange,viewTabs} from '../frame/ai-protocol'
import {E,TYPE_LABEL,write,fmtTime,overdueDays,dur,routeQuery,setQuery,openTarget,tag,refTag,logHTML,verifiedRef} from './e-store'
import {setAiObject,draftTask} from './e-ai-adapter'
import type {Ref,Task,Reminder} from './e-store'
const st=computed(()=>E.state!),meta=computed(()=>st.value.meta),today=computed(()=>meta.value.today)
const rq=routeQuery()
const dueToday=ref(rq.get('due')==='today'),tab=ref(rq.get('view')||'all'),status=ref(rq.get('status')||''),overOnly=ref(rq.get('over')==='1'),kw=ref(rq.get('q')||''),ttype=ref(rq.get('type')||'')
// 共享组件（SHARED「接入说明」BL21/23）：批量任务号匹配全部任务；相对日期只存 key，按「截止」日筛选
const nos=ref<string[]>([]),dateKey=ref(rq.get('date')||'')
const range=computed(()=>{try{return dateKey.value?relativeRange(dateKey.value):null}catch{return null}})
const batchEl=ref<HTMLElement>(),dateEl=ref<HTMLElement>();let shared:HTMLElement[]=[]
watch([tab,status,overOnly,kw,ttype,dateKey,dueToday],()=>setQuery({view:tab.value==='all'?'':tab.value,status:status.value,over:overOnly.value?'1':'',q:kw.value,type:ttype.value,date:dateKey.value,due:dueToday.value?'today':''}))
// 已存视图（BL22，共享 viewTabs）：内置 全部 / 我负责的 / 我发起的 / 今天到期 / 已逾期；视图只存筛选 key（不存具体日期）；按账号 + 工作区保存。
// 四态计数小标签（未开始 / 进行中 / 已完成 / 已取消）点一下就按该状态筛选，替代原状态下拉
const viewEl=ref<HTMLElement>();let vt:ReturnType<typeof viewTabs>|undefined;let dateCtl:{set(v:string):void}|undefined
const BUILTIN_VIEWS=[{id:'all',name:'全部',state:{tab:'all'}},{id:'mine',name:'我负责的',state:{tab:'mine'}},{id:'created',name:'我发起的',state:{tab:'created'}},{id:'today',name:'今天到期',state:{tab:'all',dueToday:true}},{id:'over',name:'已逾期',state:{tab:'all',over:true}}]
function viewState(){return {tab:tab.value,status:status.value,ttype:ttype.value,over:overOnly.value,dueToday:dueToday.value,q:kw.value,dateKey:dateKey.value}}
function applyView(s:Record<string,unknown>){tab.value=String(s.tab||'all');status.value=String(s.status||'');ttype.value=String(s.ttype||'');overOnly.value=!!s.over;dueToday.value=!!s.dueToday;kw.value=String(s.q||'');dateKey.value=String(s.dateKey||'');dateCtl?.set(dateKey.value)}
const types=computed(()=>st.value.taskTypes||{})
const typeOf=(t:Task)=>t.type&&types.value[t.type]?t.type:'other'
const tm=(t:Task)=>st.value.timings?.[t.id]||{respondedAt:null,responseMin:null,handleMin:null}
const mine=(t:Task)=>!!E.actor&&t.assignee.role===E.actor
const byMe=(t:Task)=>!!E.actor&&t.createdBy===E.actor
const inTab=(t:Task,k:string)=>k==='mine'?mine(t):k==='created'?byMe(t):true
const counts=computed(()=>Object.fromEntries(['mine','created','all'].map(k=>[k,st.value.tasks.filter(t=>inTab(t,k)&&!['DONE','CANCELLED'].includes(t.status)).length])))
const overCount=computed(()=>st.value.tasks.filter(t=>overdueDays(t,today.value)>0).length)
const base=computed(()=>st.value.tasks.filter(t=>pass(t,'status')))
const statusCount=(k:string)=>base.value.filter(t=>t.status===k).length
const typeCount=(k:string)=>st.value.tasks.filter(t=>pass(t,'type')&&typeOf(t)===k).length
// 列表与计数共用一个筛选函数（BATCH5-AUDIT 退回 E1，R21）：计数 = 除被计数维度以外的全部当前筛选（含视图），所以在「已逾期」等视图里点计数不会得到 0 条
function pass(t:Task,skip:''|'status'|'type'=''){const s=kw.value.trim().toLowerCase();return inTab(t,tab.value)&&(!dueToday.value||(t.due===today.value&&!['DONE','CANCELLED'].includes(t.status)))&&(!nos.value.length||nos.value.includes(t.no))&&(!range.value||(t.due>=range.value.from&&t.due<=range.value.to))&&(skip==='status'||!status.value||t.status===status.value)&&(skip==='type'||!ttype.value||typeOf(t)===ttype.value)&&(!overOnly.value||overdueDays(t,today.value)>0)&&(!s||[t.no,t.title,t.assignee.role,t.assignee.name,...t.related.map(r=>r.code)].join(' ').toLowerCase().includes(s))}
const rows=computed(()=>st.value.tasks.filter(t=>pass(t)).slice().sort((a,b)=>(overdueDays(b,today.value)-overdueDays(a,today.value))||a.due.localeCompare(b.due)))
const ORIGIN:Record<string,string>={manual:'手建',inspection:'检验异常',draft:'解读草稿',reminder:'规则提醒'}
const stCls=(s:string)=>s==='DONE'?'success':s==='DOING'?'active':s==='CANCELLED'?'muted':''
const picked=computed(()=>E.current?.kind==='task'?E.current.id:'')

// ---- 详情 / 新建 ----
const open=ref(false),editing=ref<Task|undefined>(),phase=ref<'edit'|'preview'|'status'>('edit'),err=ref(''),ok=ref(''),saving=ref(false),toStatus=ref(''),statusReason=ref(''),comment=ref('')
const f=reactive({type:'other',fields:{} as Record<string,string|number|null>,title:'',titleAuto:true,description:'',role:'',name:'',due:'',priority:'中',related:[] as Ref[],relType:'project',relCode:''})
// 标题规则（与服务端 task-types.cjs 同口径）：类型 · 关联项 · 关键字段；可改，改过后不再自动覆盖
function titleRule(){const t=types.value[f.type]||{label:'其他'},x=f.fields,rel=f.related[0];const key=({quality:x.failItems,first:x.product&&x.process?x.product+' '+x.process:x.product,equipment:x.device,shortage:x.material,rush:[x.order,x.product].filter(Boolean).join(' '),materialCheck:[x.material,x.order].filter(Boolean).join(' ')} as Record<string,unknown>)[f.type]||'';return [t.label,rel?rel.code:'',key].filter(Boolean).join(' · ').slice(0,60)}
watch(()=>[f.type,JSON.stringify(f.fields),f.related.map(r=>r.code).join()],()=>{if(!editing.value&&f.titleAuto)f.title=titleRule()})
let lastDefault=''
watch(()=>f.type,(t)=>{const d=types.value[t]?.role||'';if(!f.role||f.role===lastDefault)f.role=d;lastDefault=d;const keep:Record<string,string|number|null>={};for(const fd of types.value[t]?.fields||[])keep[fd.key]=f.fields[fd.key]??(fd.kind==='number'?'':'');f.fields=keep})
const typeFields=computed(()=>types.value[f.type]?.fields||[])
const fieldText=(type:string,fl?:Record<string,unknown>)=>(types.value[type]?.fields||[]).map(d=>fl&&fl[d.key]!=null&&fl[d.key]!==''?d.label+' '+fl[d.key]:'').filter(Boolean).join('；')
function addDays(n:number){const d=new Date(today.value+'T00:00:00Z');d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10)}
function openNew(){editing.value=undefined;seed.value='';seedInsp.value='';seedReminder.value='';draftNote.value='';fieldSrc.value={};lastDefault='';Object.assign(f,{type:'other',fields:{},title:'',titleAuto:true,description:'',role:'',name:'',due:addDays(3),priority:'中',related:[],relCode:''});phase.value='edit';err.value='';ok.value='';open.value=true;E.drawerOpen=true}
function load(t:Task){lastDefault=types.value[typeOf(t)]?.role||'';Object.assign(f,{type:typeOf(t),fields:{...(t.fields||{})},titleAuto:false,title:t.title,description:t.description,role:t.assignee.role,name:t.assignee.name,due:t.due,priority:t.priority,related:t.related.map(r=>({...r})),relCode:''})}
function openOne(t:Task){setAiObject({kind:'task',id:t.id});editing.value=t;load(t);phase.value='edit';err.value='';ok.value='';comment.value='';open.value=true;E.drawerOpen=true;setQuery({open:t.id})}
function close(){open.value=false;E.drawerOpen=false;setQuery({open:''})}
function addRel(){const c=f.relCode.trim();if(!c)return;const all=[...E.objects.material,...E.objects.product,...E.objects.project];const hit=all.find(o=>o.code===c);const qi=st.value.inspections.find(i=>i.no===c);const tk=st.value.tasks.find(t=>t.no===c);f.related.push(hit?verifiedRef(hit):qi?{type:'inspection',id:qi.id,code:qi.no,name:'检验单',verified:true,source:'Cretas'}:tk?{type:'task',id:tk.id,code:tk.no,name:tk.title,verified:true,source:'Cretas'}:{type:f.relType,id:'',code:c,name:''});f.relCode=''}
const invalid=computed(()=>{if(!f.title.trim())return '任务标题不能为空';if(!f.role)return '请选择负责岗位';if(!/^\d{4}-\d{2}-\d{2}$/.test(f.due))return '请填写截止日期';for(const fd of typeFields.value){const v=f.fields[fd.key];if(fd.required&&(v===''||v==null))return types.value[f.type].label+'必须填写「'+fd.label+'」';if(fd.kind==='number'&&v!==''&&v!=null&&!(Number(v)>=0))return '「'+fd.label+'」必须是非负数字'}if('unit' in f.fields&&['qty','standardQty','actualQty'].some(k=>f.fields[k]!==''&&f.fields[k]!=null)&&!f.fields.unit)return '填写了数量就要填写单位';return ''})
const changes=computed(()=>{const t=editing.value;if(!t)return [] as string[][];const a=(x:{role:string;name:string})=>x.role+(x.name?' · '+x.name:'');const out:string[][]=[];const pairs:[string,string,string][]=[['标题',t.title,f.title],['说明',t.description,f.description],['负责人',a(t.assignee),a({role:f.role,name:f.name})],['截止',t.due,f.due],['优先级',t.priority,f.priority],['关联单据',t.related.map(r=>r.code).join('、'),f.related.map(r=>r.code).join('、')],['类型',types.value[typeOf(t)]?.label||'其他',types.value[f.type]?.label||'其他'],['专属字段',fieldText(typeOf(t),t.fields),fieldText(f.type,f.fields)]];for(const [k,b,c] of pairs)if((b||'')!==(c||'').trim())out.push([k,b,c]);return out})
function body(){return {type:f.type,fields:f.fields,title:f.title,description:f.description,assignee:{kind:'role',role:f.role,name:f.name},due:f.due,priority:f.priority,related:f.related}}
async function save(){err.value='';ok.value='';saving.value=true;try{const insp=seedInsp.value?st.value.inspections.find(i=>i.id===seedInsp.value):undefined;const r=await write('tasks/save',{id:editing.value?.id,...body(),origin:editing.value?undefined:seedReminder.value?{kind:'reminder',ref:seedReminder.value,draftBy:'系统规则'}:insp?{kind:'inspection',ref:insp.no,draftBy:'系统规则'}:Object.keys(fieldSrc.value).length?{kind:'draft',ref:'',draftBy:'系统规则'}:{kind:'manual'}});const back=st.value.tasks.find(t=>t.id===r.id);if(!back)throw Error('已提交，回读不一致');openOne(back);ok.value=(editing.value&&back.log.length>1?'已保存':'已创建')+' · 回读一致：'+back.no+' · '+back.assignee.role+' · 截止 '+back.due}catch(e){err.value=e instanceof Error?e.message:'保存失败';phase.value='edit'}finally{saving.value=false}}
const LABEL:Record<string,string>={DOING:'开始',DONE:'完成',CANCELLED:'取消',TODO:'重开'}
const nextOf=computed(()=>editing.value?(meta.value.next[editing.value.status]||[]):[])
const needReason=(to:string)=>to==='CANCELLED'||(editing.value?.status==='DONE'&&to==='DOING')||(editing.value?.status==='CANCELLED'&&to==='TODO')
const lbl=(to:string)=>editing.value?.status==='DONE'&&to==='DOING'?'重开':LABEL[to]
function askStatus(to:string){toStatus.value=to;statusReason.value='';err.value='';phase.value='status'}
async function doStatus(){err.value='';if(needReason(toStatus.value)&&!statusReason.value.trim()){err.value=lbl(toStatus.value)+'必须填写原因';return}saving.value=true;try{const r=await write('tasks/status',{id:editing.value!.id,to:toStatus.value,reason:statusReason.value});const back=st.value.tasks.find(t=>t.id===r.id)!;openOne(back);ok.value='状态已更新 · 回读一致：'+meta.value.taskStatus[back.status]}catch(e){err.value=e instanceof Error?e.message:'操作失败';phase.value='edit'}finally{saving.value=false}}
async function postComment(){err.value='';if(!comment.value.trim())return;saving.value=true;try{const r=await write('tasks/comment',{id:editing.value!.id,text:comment.value});const back=st.value.tasks.find(t=>t.id===r.id)!;const last=back.comments[back.comments.length-1];openOne(back);ok.value='已发表 · 回读一致（'+fmtTime(last.at)+'）'}catch(e){err.value=e instanceof Error?e.message:'发表失败'}finally{saving.value=false}}
// 进展（系统计算）：AiFloat 接入前只显示规则摘要；模型概括在统一悬浮窗里
const ruleSummary=computed(()=>{const t=editing.value;if(!t)return '';const o=overdueDays(t,today.value),c=t.comments[t.comments.length-1];return meta.value.taskStatus[t.status]+' · 截止 '+t.due+(o?' · 超期 '+o+' 天':'')+' · 负责 '+t.assignee.role+(t.assignee.name?' · '+t.assignee.name:'')+' · 评论 '+t.comments.length+' 条'+(c?' · 最近：'+c.by+' '+fmtTime(c.at)+'：'+c.text.slice(0,60):'')})
// 从异常说明整理（系统规则草稿，填进表单，仍需预览确认才创建）
const seed=ref(''),seedInsp=ref(''),seedReminder=ref(''),drafting=ref(false),draftNote=ref(''),fieldSrc=ref<Record<string,string>>({})
async function fromText(){if(!seed.value.trim())return;drafting.value=true;err.value='';try{const d=await draftTask(seed.value,{inspectionId:seedInsp.value||undefined,rulesOnly:true});if(d.type&&types.value[d.type]){f.type=d.type;await new Promise(r=>setTimeout(r));Object.assign(f.fields,d.fields||{})}Object.assign(f,{titleAuto:false,title:d.title,description:d.description,role:d.role,due:d.due,priority:d.priority,related:d.related.map(r=>({...r,verified:true,source:r.type==='inspection'||r.type==='task'?'Cretas':E.ws==='lsm'?'黑湖':'味美'}))});if(d.type&&types.value[d.type]){f.title=titleRule();f.titleAuto=true}fieldSrc.value=d.fieldSource;draftNote.value='已按系统规则填入草稿（标题按类型规则生成，可改），请核对后创建'+(d.unverified.length?'；单号 '+d.unverified.join('、')+' 在本工作区未核实，未关联':'')+'。'}catch(e){err.value=e instanceof Error?e.message:'整理失败'}finally{drafting.value=false}}
// ---- 规则提醒（BL 24）：提醒由服务端规则在每次写入后生成；这里只展示、跳转、忽略、看规则与日志 ----
const RULE_NAME=computed(()=>Object.fromEntries((st.value.rulesSummary||[]).map(r=>[r.id,r.name])))
const openRem=computed(()=>(st.value.reminders||[]).filter(r=>r.status==='open').slice().reverse())
const remShow=ref(false),dismissId=ref(''),dismissReason=ref(''),remErr=ref(''),remOk=ref('')
const remByRule=computed(()=>{const g:Record<string,Reminder[]>={};for(const r of openRem.value)(g[r.rule]=g[r.rule]||[]).push(r);return g})
function act(r:Reminder){
  if(r.rule==='TASK_OVERDUE'){const t=st.value.tasks.find(x=>x.id===r.ref.id);if(t)openOne(t);return}
  if(r.rule==='INSPECTION_FAIL'){const q=st.value.inspections.find(x=>x.id===r.ref.id);if(!q)return;openNew();seedReminder.value=r.id;seed.value=r.title+'。请核对并处理。';seedInsp.value=q.id;fromText();return}
  if(r.rule==='FIRST_CHECK_PENDING'){openNew();seedReminder.value=r.id;f.type='first';setTimeout(()=>{const pj=E.objects.project.find(o=>o.code===r.ref.code);Object.assign(f.fields,{product:pj?.name||'',process:r.title.match(/「(.+?)」/)?.[1]||''});f.related=pj?[verifiedRef(pj)]:[];f.description=r.title+'。请安排首件确认。'},0)}
}
function goFirst(){location.hash='#/quality/inspections?stage=production'+(E.ws==='ydwm'?'&ws=ydwm':'')}
async function dismiss(){remErr.value='';if(!dismissReason.value.trim()){remErr.value='忽略提醒必须填写原因';return}try{await write('reminders/dismiss',{id:dismissId.value,reason:dismissReason.value});remOk.value='已忽略 · 回读一致';dismissId.value='';dismissReason.value=''}catch(e){remErr.value=e instanceof Error?e.message:'操作失败'}}
const rulesOpen=ref(false),ruleReason=ref(''),ruleTarget=ref(''),ruleErr=ref(''),ruleOk=ref(''),running=ref(false)
async function runRules(){ruleErr.value='';running.value=true;try{const before=(st.value.ruleLog||[]).length;await write('rules/run',{});const n=(st.value.ruleLog||[]).length-before;ruleOk.value='已检查 · 回读一致：新增日志 '+n+' 条'}catch(e){ruleErr.value=e instanceof Error?e.message:'检查失败'}finally{running.value=false}}
async function toggleRule(){ruleErr.value='';const r=(st.value.rulesSummary||[]).find(x=>x.id===ruleTarget.value);if(!r)return;if(!ruleReason.value.trim()){ruleErr.value=(r.enabled?'停用':'启用')+'规则必须填写原因';return}try{await write('rules/toggle',{id:r.id,enabled:!r.enabled,reason:ruleReason.value});const back=st.value.rulesSummary.find(x=>x.id===r.id)!;ruleOk.value='已'+(back.enabled?'启用':'停用')+' · 回读一致：'+back.name;ruleTarget.value='';ruleReason.value=''}catch(e){ruleErr.value=e instanceof Error?e.message:'操作失败'}}
// 悬浮窗创建任务后打开它
const onOpen=(e:Event)=>{const t=st.value.tasks.find(x=>x.id===(e as CustomEvent).detail);if(t)openOne(t)}
const stateCounts=computed(()=>[{key:'',label:'全部',count:base.value.length},...Object.entries(meta.value.taskStatus).map(([k,l])=>({key:k,label:l as string,count:statusCount(k)}))])
function activeView(){if(dueToday.value)return 'today';if(overOnly.value)return 'over';return ['mine','created'].includes(tab.value)?tab.value:'all'}
watch([stateCounts,status],()=>vt?.refresh(stateCounts.value,status.value))
onMounted(()=>{try{const b=batchNoSearch(batchEl.value!,{label:'批量任务号',placeholder:'每行一个任务号',onSearch:(list:string[])=>{nos.value=list;const m=matchNos(st.value.tasks,list,(t:Task)=>t.no);return {found:m.found.length,missing:m.missing}},onClear:()=>{nos.value=[]}});const d=relativeDate(dateEl.value!,{value:dateKey.value,onChange:r=>{dateKey.value=r?.key||''}});dateCtl=d;vt=viewTabs(viewEl.value!,{page:'/collab/tasks@'+E.ws,builtins:BUILTIN_VIEWS,getState:viewState,apply:applyView,active:activeView(),counts:stateCounts.value,countKey:status.value,onCount:k=>{status.value=k}});shared=[b.element,d.element,vt.element]}catch{/* 共享脚本未加载时不显示这些控件 */}
  window.addEventListener('e-open-task',onOpen);if(E.taskPrefill){const p=E.taskPrefill;E.taskPrefill=undefined;openNew();seedReminder.value=p.reminderId||'';if(p.type){f.type=p.type;setTimeout(()=>{Object.assign(f.fields,p.fields||{});f.related=[...(p.related||[])];f.description=p.text},0);return}seed.value=p.text;seedInsp.value=p.inspectionId||'';fromText();return}const id=routeQuery().get('open');if(id){const t=st.value.tasks.find(x=>x.id===id);if(t)openOne(t)}})
onBeforeUnmount(()=>{E.drawerOpen=false;window.removeEventListener('e-open-task',onOpen);for(const el of shared)el.remove()})
</script>
<template>
<section class="card e-rem">
  <div class="section-head"><h2>提醒 <b :class="{'e-over':openRem.length}">{{openRem.length}}</b> <small>由规则在每次保存后检查生成；只提醒，不自动建任务 <span v-html="tag('system')"/></small></h2><div class="actions"><UButton size="xs" color="neutral" variant="ghost" @click="remShow=!remShow">{{remShow?'收起':'展开'}}</UButton><UButton size="xs" color="neutral" variant="outline" @click="rulesOpen=true;E.drawerOpen=true">规则与日志</UButton></div></div>
  <div v-if="remShow">
    <p v-if="!openRem.length" class="e-stage-hint" style="padding-bottom:10px">没有未处理的提醒。</p>
    <div v-for="(list,rule) in remByRule" :key="rule" class="e-rem-group"><h3>{{RULE_NAME[rule]||rule}} <small>{{list.length}} 条</small></h3>
      <ul><li v-for="r in list.slice(0,6)" :key="r.id"><span>{{r.title}}</span><small>提醒 {{r.role||'负责岗位'}} · {{fmtTime(r.at)}}</small>
        <span class="actions"><UButton v-if="r.rule==='INSPECTION_FAIL'" size="xs" @click="act(r)">生成任务</UButton><UButton v-else-if="r.rule==='TASK_OVERDUE'" size="xs" color="neutral" variant="outline" @click="act(r)">打开任务</UButton><template v-else><UButton size="xs" color="neutral" variant="outline" @click="goFirst()">去首检</UButton><UButton size="xs" color="neutral" variant="outline" @click="act(r)">建首件确认任务</UButton></template><UButton size="xs" color="neutral" variant="ghost" @click="dismissId=r.id;dismissReason='';remErr=''">忽略</UButton></span>
        <div v-if="dismissId===r.id" class="e-preview" style="width:100%"><label class="e-field"><span class="e-req">忽略原因</span><input v-model="dismissReason" aria-label="忽略原因"></label><div class="actions" style="justify-content:flex-end;margin-top:6px"><UButton size="xs" color="neutral" variant="outline" @click="dismissId=''">取消</UButton><UButton size="xs" @click="dismiss()">确认忽略</UButton></div></div></li>
        <li v-if="list.length>6" class="muted">还有 {{list.length-6}} 条</li></ul></div>
    <p v-if="remErr" role="alert" class="e-err" style="margin:0 16px 10px">{{remErr}}</p><p v-if="remOk" role="status" class="e-ok" style="margin:0 16px 10px">{{remOk}}</p>
  </div>
</section>
<section class="card">
  <p v-if="E.lake?.available" class="e-stage-hint" style="padding-top:10px">黑湖协同任务当前 {{E.lake.counts.collaborative_task??0}} 条；这里的任务都在 Cretas 本机创建（Cretas 补充），不回写黑湖。</p>
  <div class="section-head"><h2>协同任务 <small v-if="overCount" class="e-over">超期 {{overCount}} 件</small></h2><div class="actions"><UButton size="sm" @click="openNew()">新建任务</UButton></div></div>
  <div ref="viewEl" class="e-views"/>
  <p v-if="tab!=='all'&&!E.actor" class="e-stage-hint">未选择「我是」，这个视图为空。请在页头选择岗位。</p>
  <p v-if="dueToday" class="e-stage-hint">今天到期：截止日为 {{today}}（北京时间）且未完成、未取消的任务</p>
  <div class="filters"><select v-model="ttype" aria-label="任务类型"><option value="">全部类型</option><option v-for="(t,k) in types" :key="k" :value="k">{{t.label}}（{{typeCount(String(k))}}）</option></select><label class="e-switch"><input v-model="overOnly" type="checkbox">只看超期</label><input v-model="kw" type="search" placeholder="任务号、标题、负责人、关联单号" aria-label="搜索任务"><span ref="batchEl" class="e-shared"/><span class="e-shared e-date"><small>截止</small><span ref="dateEl"/></span></div>
  <p v-if="nos.length||range" class="e-stage-hint">{{nos.length?'按任务号筛选 '+nos.length+' 个':''}}{{nos.length&&range?' · ':''}}{{range?'截止 '+range.label+'（'+range.from+' ~ '+range.to+'）':''}}</p>
  <div v-if="!st.tasks.length" class="e-empty"><p>还没有协同任务。可在这里新建，或从不合格检验单「生成任务」，或在右下角悬浮窗「整理成任务草稿」。</p><UButton size="sm" @click="openNew()">新建任务</UButton></div>
  <div v-else-if="!rows.length" class="e-empty"><p>当前筛选没有任务。</p></div>
  <div v-else class="table-scroll"><table><thead><tr><th>任务号</th><th>类型</th><th>标题</th><th>负责人</th><th>截止</th><th>状态</th><th>响应 <i class="help" tabindex="0" title="响应时长 = 创建到第一次「开始」或第一条非发起人评论（取早者）；处理时长 = 创建到完成。由记录时间计算。">?</i></th><th>处理</th><th>关联单据</th><th class="op">操作</th></tr></thead>
  <tbody><tr v-for="t in rows" :key="t.id" :class="{picked:picked===t.id}" @click="setAiObject({kind:'task',id:t.id})">
    <td>{{t.no}}</td><td><span class="state" :class="types[typeOf(t)]?.color">{{types[typeOf(t)]?.label||'其他'}}</span></td><td :title="t.title+' · 优先级 '+t.priority+' · 来源 '+(ORIGIN[t.origin.kind]||t.origin.kind)">{{t.title}}<small v-if="t.priority==='高'" class="e-over">高优先级</small></td><td>{{t.assignee.role}}{{t.assignee.name?' · '+t.assignee.name:''}}</td>
    <td>{{t.due}} <span v-if="overdueDays(t,today)" class="e-over">超期 {{overdueDays(t,today)}} 天</span></td>
    <td><span class="state" :class="stCls(t.status)">{{meta.taskStatus[t.status]}}</span></td><td>{{tm(t).responseMin!=null?dur(tm(t).responseMin):'未响应'}}</td><td>{{tm(t).handleMin!=null?dur(tm(t).handleMin):(t.status==='CANCELLED'?'已取消':'未完成')}}</td>
    <td>{{t.related.length?t.related.map(r=>r.code).join('、'):'—'}}</td>
    <td class="op"><UButton size="xs" color="neutral" variant="outline" @click.stop="openOne(t)">详情</UButton></td></tr></tbody></table></div>
  <div class="pagination"><span>共 {{rows.length}} 件</span></div>
</section>
<aside v-if="open" class="e-drawer" role="dialog" aria-modal="false" :aria-label="editing?'任务 '+editing.no:'新建任务'" @keydown.esc="close()">
  <header><div><h2>{{editing?editing.no:'新建任务'}} <span v-if="editing" class="state" :class="stCls(editing.status)">{{meta.taskStatus[editing.status]}}</span> <span v-if="editing&&overdueDays(editing,today)" class="e-over">超期 {{overdueDays(editing,today)}} 天</span></h2><p v-if="editing">来源：{{ORIGIN[editing.origin.kind]}}{{editing.origin.ref?' '+editing.origin.ref:''}} · 发起 {{editing.createdBy}} · {{fmtTime(editing.createdAt)}} · 响应 {{tm(editing).responseMin!=null?dur(tm(editing).responseMin):'未响应'}} · 处理 {{tm(editing).handleMin!=null?dur(tm(editing).handleMin):'未完成'}}</p></div><UButton aria-label="关闭" size="xs" color="neutral" variant="ghost" @click="close()">×</UButton></header>
  <div class="e-body">
    <div v-if="editing&&phase!=='preview'" class="e-sec actions"><template v-for="to in nextOf" :key="to"><UButton size="xs" :color="to==='CANCELLED'?'error':'primary'" :variant="to==='DONE'||to==='DOING'?'solid':'outline'" @click="askStatus(to)">{{lbl(to)}}</UButton></template></div>
    <p v-if="editing&&phase!=='preview'" class="e-sec" style="font-size:13px">{{ruleSummary}} <span v-html="tag('system')"/> <i class="help" tabindex="0" title="状态、超期、评论数由代码计算。模型概括进展在右下角统一悬浮窗里（接入后可用），需预览确认才会发表为评论。">?</i></p>
    <div v-if="!editing&&phase==='edit'" class="e-sec"><h3>从异常说明整理 <i class="help" tabindex="0" title="把一段异常说明按系统规则整理成草稿填进下面的表单：岗位按阶段、截止按是否不合格、关联只放本工作区能核实的单号。仍需预览确认才创建；模型整理在统一悬浮窗里（接入后可用）。">?</i></h3><textarea v-model="seed" rows="3" aria-label="异常说明" style="width:100%" placeholder="例如：工单 S20260047-002-001 出料偏少，请生产主管核对报工"/><div class="actions" style="justify-content:flex-end;margin-top:6px"><span v-if="seedInsp" class="muted" style="margin-right:auto">来自检验单 {{st.inspections.find(i=>i.id===seedInsp)?.no}}</span><UButton size="xs" color="neutral" variant="outline" :loading="drafting" :disabled="!seed.trim()" @click="fromText()">整理成草稿</UButton></div><p v-if="draftNote" class="muted">{{draftNote}}</p></div>
    <div v-if="phase==='status'" class="e-preview"><h4>{{lbl(toStatus)}}任务：{{meta.taskStatus[editing!.status]}} → {{meta.taskStatus[toStatus]}}</h4><label class="e-field"><span :class="{'e-req':needReason(toStatus)}">原因</span><input v-model="statusReason" aria-label="状态变更原因" :placeholder="needReason(toStatus)?'必填':'可选'"></label><div class="actions" style="justify-content:flex-end;margin-top:6px"><UButton size="xs" color="neutral" variant="outline" @click="phase='edit'">取消</UButton><UButton size="xs" :loading="saving" @click="doStatus()">确认{{lbl(toStatus)}}</UButton></div></div>
    <div class="e-sec"><div class="e-grid">
      <label><span class="e-req">任务类型</span><select v-model="f.type" aria-label="任务类型"><option v-for="(t,k) in types" :key="k" :value="k">{{t.label}}{{t.role?'（默认 '+t.role+'）':''}}</option></select></label>
      <div class="e-field"><span>&nbsp;</span><span class="state" :class="types[f.type]?.color" style="justify-self:start">{{types[f.type]?.label}}</span></div>
      <template v-for="fd in typeFields" :key="fd.key"><label><span :class="{'e-req':fd.required}">{{fd.label}}</span><input v-model="f.fields[fd.key]" :type="fd.kind==='number'?'number':'text'" :min="fd.kind==='number'?0:undefined" step="any" :maxlength="fd.max" :aria-label="fd.label"></label></template>
      <label class="wide"><span class="e-req">标题 <i class="help" tabindex="0" title="标题规则：类型 · 第一个关联单号 · 关键字段（不良项 / 产品与工序 / 设备 / 物料）。新建时随字段自动生成，手改后不再覆盖。">?</i></span><span class="actions" style="flex-wrap:nowrap"><input v-model="f.title" maxlength="60" style="flex:1" @input="f.titleAuto=false"><UButton size="xs" color="neutral" variant="ghost" @click="f.title=titleRule();f.titleAuto=!editing">按规则生成</UButton></span></label>
      <label class="wide"><span>说明</span><textarea v-model="f.description" rows="3"/></label>
      <label><span class="e-req">负责岗位</span><select v-model="f.role" aria-label="负责岗位"><option value="">请选择</option><option v-for="r in meta.roles" :key="r">{{r}}</option><option disabled class="e-agent" value="agent">指派给 Agent（预留，本轮不可用）</option></select></label>
      <label><span>姓名（可选）<i class="help" tabindex="0" title="原型没有可核实的人员名单，姓名为手填，标「未核实人员」。">?</i></span><input v-model="f.name" maxlength="30"></label>
      <label><span class="e-req">截止</span><input v-model="f.due" type="date" aria-label="截止日期"></label>
      <label><span>优先级</span><select v-model="f.priority"><option v-for="p in meta.priority" :key="p">{{p}}</option></select></label>
      <p class="muted wide" style="grid-column:1/-1">指派给 Agent：预留入口。以后可由智能代理按任务说明做只读核对并回写评论；本轮不接执行，不会自动改任何数据。<i class="help" tabindex="0" title="占位说明：选项保持禁用，接口也会拒绝保存 kind=agent。">?</i></p>
      <div class="e-field wide"><span>关联单据</span><div class="e-rel"><span v-for="(r,i) in f.related" :key="i"><a class="e-link" href="javascript:void 0" @click="openTarget(r)">{{TYPE_LABEL[r.type]||r.type}} {{r.code}}</a><span v-html="refTag(r)"/><button class="x" :aria-label="'移除 '+r.code" @click="f.related.splice(i,1)">×</button></span><em v-if="!f.related.length" class="muted">无</em></div>
        <div class="actions" style="margin-top:6px"><select v-model="f.relType" aria-label="关联类型" style="width:96px"><option value="project">工单</option><option value="material">原材料</option><option value="product">成品</option><option value="inspection">检验单</option><option value="other">其他</option></select><input v-model="f.relCode" placeholder="单号" aria-label="关联单号" style="flex:1" @keydown.enter.prevent="addRel()"><UButton size="xs" color="neutral" variant="outline" @click="addRel()">添加</UButton></div></div>
    </div></div>
    <div v-if="phase==='preview'" class="e-preview"><h4>{{editing?'修改预览':'创建预览'}}</h4>
      <div v-if="editing" class="diff"><span class="h">字段</span><span class="h">改前</span><span class="h">改后</span><template v-for="[k,b,a] in changes" :key="k"><span>{{k}}</span><del>{{b||'—'}}</del><ins>{{a||'—'}}</ins></template></div>
      <dl v-else class="e-kv"><dt>类型</dt><dd>{{types[f.type]?.label}}{{fieldText(f.type,f.fields)?' · '+fieldText(f.type,f.fields):''}}</dd><dt>标题</dt><dd>{{f.title}}</dd><dt>负责</dt><dd>{{f.role}}{{f.name?' · '+f.name:''}}</dd><dt>截止</dt><dd>{{f.due}}</dd><dt>优先级</dt><dd>{{f.priority}}</dd><dt>关联</dt><dd>{{f.related.map(r=>r.code).join('、')||'无'}}</dd></dl>
      <p v-if="editing&&!changes.length" class="muted">没有修改。</p></div>
    <p v-if="invalid&&phase==='edit'" class="muted" role="note">{{invalid}}</p>
    <p v-if="err" role="alert" class="e-err">{{err}}</p><p v-if="ok" role="status" class="e-ok">{{ok}}</p>
    <div v-if="editing" class="e-sec"><h3>评论 {{editing.comments.length}}</h3>
      <ul class="e-timeline"><li v-for="c in editing.comments" :key="c.id">{{c.text}}<small>{{c.by}} · {{fmtTime(c.at)}}{{c.fromSummary?' · 来自进展概括（已确认发表）':''}}</small></li><li v-if="!editing.comments.length" class="muted">还没有评论。</li></ul>
      <div class="e-comment-box"><textarea v-model="comment" aria-label="评论内容" placeholder="写进展、卡点或需要谁配合" @keydown.ctrl.enter.prevent="postComment()"/><div class="actions"><small>Ctrl + Enter 发表</small><UButton size="xs" :loading="saving" :disabled="!comment.trim()" @click="postComment()">发表</UButton></div></div></div>
    <details v-if="editing" class="e-log e-sec"><summary>修改记录 {{editing.log.length}} 条</summary><div v-html="logHTML(editing.log,'任务')"/></details>
  </div>
  <footer><span class="muted">{{E.session?'写入练习会话 '+E.session:'写入本机原型保存区'}}</span>
    <template v-if="phase==='preview'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">返回修改</UButton><UButton size="sm" :loading="saving" :disabled="!!editing&&!changes.length" @click="save()">确认{{editing?'保存':'创建'}}</UButton></template>
    <UButton v-else-if="phase==='edit'" size="sm" :disabled="!!invalid||(!!editing&&!changes.length)" @click="phase='preview'">{{editing?'保存修改':'创建任务'}}</UButton>
  </footer>
</aside>
<aside v-if="rulesOpen" class="e-drawer" role="dialog" aria-modal="false" aria-label="规则与日志" @keydown.esc="rulesOpen=false;E.drawerOpen=false">
  <header><div><h2>规则与日志</h2><p>固定 3 条规则，只生成提醒、不改业务数据；不提供自定义规则设计 <span v-html="tag('system')"/></p></div><UButton aria-label="关闭" size="xs" color="neutral" variant="ghost" @click="rulesOpen=false;E.drawerOpen=false">×</UButton></header>
  <div class="e-body">
    <table><thead><tr><th>规则</th><th>状态</th><th>最近触发</th><th>近 3 天</th><th>未处理</th><th class="op"></th></tr></thead>
      <tbody><tr v-for="r in st.rulesSummary" :key="r.id"><td :title="'触发：'+r.trigger+'；动作：'+r.action">{{r.name}}<small>{{r.trigger}}</small></td><td><span class="state" :class="r.enabled?'success':'muted'">{{r.enabled?'启用':'已停用'}}</span></td><td>{{r.lastAt?fmtTime(r.lastAt):'无记录'}}</td><td>{{r.count3d}} 次</td><td>{{r.open}}</td><td class="op"><UButton size="xs" color="neutral" variant="ghost" @click="ruleTarget=r.id;ruleReason='';ruleErr=''">{{r.enabled?'停用':'启用'}}</UButton></td></tr></tbody></table>
    <div v-if="ruleTarget" class="e-preview"><h4>{{st.rulesSummary.find(x=>x.id===ruleTarget)?.enabled?'停用':'启用'}}规则：{{st.rulesSummary.find(x=>x.id===ruleTarget)?.name}}</h4><label class="e-field"><span class="e-req">原因</span><input v-model="ruleReason" aria-label="规则启停原因"></label><div class="actions" style="justify-content:flex-end;margin-top:6px"><UButton size="xs" color="neutral" variant="outline" @click="ruleTarget=''">取消</UButton><UButton size="xs" @click="toggleRule()">确认</UButton></div></div>
    <div class="actions" style="margin:10px 0"><UButton size="xs" :loading="running" @click="runRules()">立即检查</UButton><small>近 3 天 = 72 小时内「已生成提醒」的次数</small></div>
    <p v-if="ruleErr" role="alert" class="e-err">{{ruleErr}}</p><p v-if="ruleOk" role="status" class="e-ok">{{ruleOk}}</p>
    <h3 style="font-size:13px;margin:8px 0">执行日志 <small>最近 50 条 · 时间为北京时间</small></h3>
    <table><thead><tr><th>时间</th><th>规则</th><th>结果</th><th>说明</th></tr></thead><tbody><tr v-for="l in (st.ruleLog||[]).slice(-50).reverse()" :key="l.id"><td>{{fmtTime(l.at)}}</td><td>{{RULE_NAME[l.rule]||l.rule}}</td><td>{{l.result}}</td><td :title="l.detail">{{l.detail}}</td></tr><tr v-if="!(st.ruleLog||[]).length"><td colspan="4" class="muted">无记录</td></tr></tbody></table>
  </div>
</aside>
</template>
