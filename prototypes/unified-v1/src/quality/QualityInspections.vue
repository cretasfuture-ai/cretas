<script setup lang="ts">
// E1 质量检验列表（按阶段）+ E2 检验单详情与录入。判定由代码按标准上下限计算；处置由人选择。
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {batchNoSearch,matchNos,relativeDate,relativeRange,viewTabs} from '../frame/ai-protocol'
import {E,STAGE_ORDER,TYPE_LABEL,get,write,fmtTime,refText,range,itemResult,overall,bad2,stdVersion,routeQuery,setQuery,openTarget,tag,refTag,logHTML,verifiedRef} from '../collab/e-store'
import {setAiObject,explain} from '../collab/e-ai-adapter'
import type {EAiAnswer} from '../collab/e-ai-adapter'
import type {Inspection,Ref,StageKey,StdItem,LakeInspection,BatchRef,BatchHold} from '../collab/e-store'

const st=computed(()=>E.state!)
const meta=computed(()=>st.value.meta)
const rq=routeQuery()
const stage=ref<'all'|StageKey>((rq.get('stage') as StageKey)||'all')
const status=ref(rq.get('status')||'')
const kw=ref(rq.get('q')||'')
// 共享组件（SHARED「接入说明」BL21/23）：批量单号匹配全部记录；相对日期只存 key，按北京时间日历日换算，按「检验时间」筛选
const nos=ref<string[]>([]),dateKey=ref(rq.get('date')||'')
const dateRange=computed(()=>{try{return dateKey.value?relativeRange(dateKey.value):null}catch{return null}})
const inRange=(d:string)=>{const r=dateRange.value;if(!r)return true;const x=(d||'').slice(0,10);return !!x&&x>=r.from&&x<=r.to}
const inNos=(no:string)=>!nos.value.length||nos.value.includes(no)
watch([stage,status,kw,dateKey],()=>setQuery({stage:stage.value==='all'?'':stage.value,status:status.value,q:kw.value,date:dateKey.value}))
const batchEl=ref<HTMLElement>(),dateEl=ref<HTMLElement>();let shared:HTMLElement[]=[]
// 按阶段的内置视图（共享 viewTabs）：全部 + 来料 / 生产 / 入库 / 发货 / 其他；阶段多，不加计数小标签，各阶段数量放在下方一行说明。视图按账号 + 工作区保存
const viewEl=ref<HTMLElement>();let vt:ReturnType<typeof viewTabs>|undefined;let dateCtl:{set(v:string):void}|undefined
const STAGE_VIEWS=()=>[{id:'all',name:'全部',state:{stage:'all'}},...STAGE_ORDER.map(k=>({id:k,name:meta.value.stages[k].label,state:{stage:k}}))]
function viewState(){return {stage:stage.value,status:status.value,q:kw.value,dateKey:dateKey.value}}
function applyView(s:Record<string,unknown>){stage.value=(String(s.stage||'all')) as 'all'|StageKey;status.value=String(s.status||'');kw.value=String(s.q||'');dateKey.value=String(s.dateKey||'');dateCtl?.set(dateKey.value)}
const STATUS_OPTS:[string,string][]=[['','全部状态'],['DRAFT','草稿（未提交）'],['FAIL','不合格'],['PASS','合格'],['VOID','已作废'],['LAKE','黑湖检验单']]
// 黑湖检验单（只读，DECISIONS 第十节 E①）：与 Cretas 检验单并列，阶段由服务端按来源单据判定
const lakeAll=computed<LakeInspection[]>(()=>E.lake?.rows||[])
const lakeState=(x:LakeInspection)=>x.hasResult?['黑湖已出结果','active']:['黑湖未检验','muted']
const lakeRows=computed(()=>lakeAll.value.filter(x=>(stage.value==='all'||x.stage===stage.value)&&passLake(x)))
const otherReasons=computed(()=>lakeAll.value.filter(x=>x.stage==='other'))
const lakeOpen=ref<LakeInspection>()
const q2=(v:number|null,u:string)=>v==null?'未返回':Number(v).toFixed(2)+' '+(u||'（单位未返回）')
function matchStatus(x:Inspection,s:string){if(!s)return true;if(s==='LAKE')return false;if(s==='DRAFT'||s==='VOID')return x.status===s;return x.status==='SUBMITTED'&&x.verdict===s}
// 各阶段数量 = 除「阶段」以外的全部当前筛选（状态、关键词、批量单号、相对日期），与列表口径一致（BATCH5-AUDIT 退回 E1，R21）
const passCretas=(x:Inspection)=>{const t=kw.value.trim().toLowerCase();return matchStatus(x,status.value)&&inNos(x.no)&&inRange(x.inspectedAt||x.createdAt)&&(!t||[x.no,x.objectRef?.code,x.objectRef?.name,x.batchRef?.batchNo,...(x.related||[]).map(r=>r.code)].join(' ').toLowerCase().includes(t))}
const passLake=(x:LakeInspection)=>{const t=kw.value.trim().toLowerCase();if(status.value&&status.value!=='LAKE')return false;return inNos(x.no)&&inRange(x.createdAt)&&(!t||[x.no,x.object.code,x.object.name,x.sourceDoc.no].join(' ').toLowerCase().includes(t))}
const counts=computed(()=>{const c:Record<string,{n:number;fail:number}>={all:{n:0,fail:0}};for(const k of STAGE_ORDER)c[k]={n:0,fail:0};for(const x of st.value.inspections){if(!passCretas(x))continue;c.all.n++;c[x.stage].n++;if(x.status==='SUBMITTED'&&x.verdict==='FAIL'){c.all.fail++;c[x.stage].fail++}}for(const x of lakeAll.value){if(!passLake(x))continue;c.all.n++;c[x.stage].n++}return c})
const passRate=computed(()=>{const list=st.value.inspections.filter(x=>x.status==='SUBMITTED'&&(stage.value==='all'||x.stage===stage.value));if(!list.length)return '';return '合格率 '+(list.filter(x=>x.verdict==='PASS').length/list.length*100).toFixed(2)+'%（'+list.length+' 张已提交）'})
const rows=computed(()=>st.value.inspections.filter(x=>(stage.value==='all'||x.stage===stage.value)&&passCretas(x)).slice().sort((a,b)=>b.updatedAt.localeCompare(a.updatedAt)))
const stdOf=(id:string)=>st.value.standards.find(s=>s.id===id)
function verdictLabel(x:Inspection){if(x.status==='VOID')return ['已作废','muted'];if(x.status==='DRAFT')return ['草稿 · '+meta.value.verdict[x.verdict],x.verdict==='FAIL'?'danger':''];return [meta.value.verdict[x.verdict],x.verdict==='PASS'?'success':x.verdict==='FAIL'?'danger':'']}
const picked=computed(()=>E.current?.kind==='inspection'?E.current.id:'')
function pick(x:Inspection){setAiObject({kind:'inspection',id:x.id})}

// ---- 详情/录入 ----
const open=ref(false),editing=ref<Inspection|undefined>(),err=ref(''),ok=ref(''),saving=ref(false),phase=ref<'edit'|'preview'|'void'>('edit'),hl=ref('')
// 第六批（R23）：六膳门来料 / 入库检验挂 C 的入库批次（Cretas 主来源）。选批次带出产品与到期日；不合格时批次「待处置」，C 的先到期先出建议跳过
const bat=ref<{available:boolean;reason?:string;source?:string;rows:(BatchRef&{productType:string;createdAt:string})[];holds:BatchHold[]}>(),batErr=ref('')
async function loadBatches(){if(E.ws!=='lsm')return;batErr.value='';try{bat.value=await get('batches')}catch(e){batErr.value=e instanceof Error?e.message:'读取失败'}}
const batchStage=computed(()=>E.ws==='lsm'&&(f.stage==='incoming'||f.stage==='warehousing'))
const batchChoices=computed(()=>(bat.value?.rows||[]).filter(b=>f.stage==='incoming'?b.kind==='purchase':true).slice().sort((a,b)=>(a.expiryDate||'9').localeCompare(b.expiryDate||'9')))
const holdOf=(no:string)=>(bat.value?.holds||[]).find(h=>h.batchNo===no)
const curBatch=computed(()=>(bat.value?.rows||[]).find(b=>b.batchNo===f.batchNo)||(editing.value?.batchRef&&editing.value.batchRef.batchNo===f.batchNo?editing.value.batchRef:undefined))
const KIND_LABEL:Record<string,string>={purchase:'采购入库',production:'生产入库'}
function daysLeft(d:string){if(!d)return null;const t=Date.parse(d+'T00:00:00+08:00'),n=Date.parse(meta.value.today+'T00:00:00+08:00');return Math.round((t-n)/864e5)}
function expiryText(d:string){const n=daysLeft(d);if(n==null)return '到期日未填';return d+(n<0?'（已过期 '+(-n)+' 天）':n===0?'（今天到期）':'（剩 '+n+' 天）')}
const batchHoldBanner=computed(()=>{const x=editing.value;if(!x?.batchRef)return null;const h=holdOf(x.batchRef.batchNo);return h&&h.inspectionId===x.id?h:null})
const close2=reactive({result:'',reason:''})
async function doCloseBatch(){err.value='';ok.value='';if(!close2.result){err.value='请选择批次处置结论';return}if(!close2.reason.trim()){err.value='请填写处置说明';return}saving.value=true;try{const r=await write('inspections/batch-close',{id:editing.value!.id,result:close2.result,reason:close2.reason});await loadBatches();const back=st.value.inspections.find(x=>x.id===r.id)!;openOne(back);const h=holdOf(back.batchRef!.batchNo);ok.value='批次处置已保存 · 回读一致：'+back.batchRef!.batchNo+' → '+back.batchClose!.result+' · 批次状态 '+(h?h.label:'可用（不再拦先到期先出）')}catch(e){err.value=e instanceof Error?e.message:'保存失败'}finally{saving.value=false}}
const f=reactive({batchNo:'',stage:'incoming' as StageKey,standardId:'',objType:'material',objCode:'',objName:'',related:[] as Ref[],relCode:'',relType:'project',sampleQty:'' as string|number,sampleUnit:'',inspector:'',inspectedAt:'',note:'',vals:{} as Record<string,unknown>,disposal:'',disposalReason:'',voidReason:'',processCode:''})
// 首检（BL-borrow 第 17 条）：生产页签显示黑湖工序任务的首检状态；首检标准要选工序
interface FcRow{taskId:string;projectId:string;projectCode:string;productName:string;processCode:string;processName:string;taskState:string;taskStateLabel:string;standardId:string;standardName:string;status:'PENDING'|'DRAFT'|'PASS'|'FAIL';inspection:{id:string;no:string;disposal:string;at?:string}|null;firstWeighin?:{id:string;start:string;end:string;inputKg:number;goodKg:number;by:string}|null;order?:null|'CHECK_FIRST'|'WEIGHED_FIRST'|'WEIGHED_NO_CHECK'}
const fc=ref<{available:boolean;reason?:string;rows:FcRow[];requirements:number}>(),fcErr=ref(''),fcOnlyLive=ref(true)
async function loadFc(){fcErr.value='';try{fc.value=await get('first-checks')}catch(e){fcErr.value=e instanceof Error?e.message:'读取失败'}}
// R23：称重、入库批次是 Cretas 主来源，标「Cretas」（不是「Cretas 补充」）；共享来源标签还没有这一档，先用文字标签
const MAIN_TITLE='Cretas 主来源（R23）：称重由 A「称重报工」录入、入库批次由 C「入库批次」建立；不回写黑湖'
const ORDER_LABEL:Record<string,[string,string]>={CHECK_FIRST:['先首检后称重','success'],WEIGHED_FIRST:['先称重后首检','danger'],WEIGHED_NO_CHECK:['已称重未首检','danger']}
const FC_LABEL:Record<string,[string,string]>={PENDING:['待首检',''],DRAFT:['首检草稿',''],PASS:['首检合格','success'],FAIL:['首检不合格','danger']}
const fcRows=computed(()=>(fc.value?.rows||[]).filter(r=>!fcOnlyLive.value||r.taskState==='10'))
function doFirst(r:FcRow){if(r.inspection){const x=st.value.inspections.find(i=>i.id===r.inspection!.id);if(x){openOne(x);return}}openNew();Object.assign(f,{stage:'production',standardId:r.standardId,objType:'project',objCode:r.projectCode,processCode:r.processCode})}
const locked=computed(()=>!!editing.value&&editing.value.status!=='DRAFT')
const stages=computed(()=>meta.value.stages)
const stdChoices=computed(()=>st.value.standards.filter(s=>s.stage===f.stage&&(s.active||s.id===editing.value?.standardId)))
const curStd=computed(()=>stdOf(f.standardId))
const fcProcs=computed(()=>curVer.value?.firstCheck?.processes||[])
const curVer=computed(()=>locked.value&&editing.value?stdVersion(curStd.value,editing.value.standardVersion):stdVersion(curStd.value))
const items=computed<StdItem[]>(()=>curVer.value?.items||[])
const objTypes=computed(()=>stages.value[f.stage].objectTypes)
const objList=computed(()=>(E.objects[f.objType]||[]))
// 未选标准（没有检验项）时不能判合格
const verdictNow=computed(()=>locked.value&&editing.value?editing.value.verdict:items.value.length?overall(items.value,f.vals):'INCOMPLETE')
const VERDICT_TEXT=(v:string)=>v==='INVALID'?'录入有误':meta.value.verdict[v]
const disposalOpts=computed(()=>meta.value.disposals[verdictNow.value]||[])
const scopeNote=computed(()=>{const v=curVer.value;if(!v)return '';return v.scope==='all'?'适用本阶段全部'+stages.value[f.stage].objectLabel:'只适用于 '+refText(v.objectRef)})
watch(()=>f.stage,s=>{if(locked.value)return;if(f.batchNo&&!batchChoices.value.some(b=>b.batchNo===f.batchNo))f.batchNo='';if(!objTypes.value.includes(f.objType))f.objType=objTypes.value[0];if(curStd.value&&curStd.value.stage!==s)f.standardId=''})
watch(()=>f.standardId,()=>{const v=curVer.value;if(!locked.value&&v?.scope==='object'&&v.objectRef){f.objType=v.objectRef.type;f.objCode=v.objectRef.code;f.objName=v.objectRef.name}})
watch(()=>f.batchNo,no=>{if(locked.value||!no)return;const b=(bat.value?.rows||[]).find(x=>x.batchNo===no);if(!b)return;f.objType=b.productType;f.objCode=b.productCode;f.objName=b.productName;if(b.unit&&!f.sampleUnit)f.sampleUnit=b.unit})
function pad(n:number){return String(n).padStart(2,'0')}
function nowLocal(){const d=new Date();return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'T'+pad(d.getHours())+':'+pad(d.getMinutes())}
function openNew(){editing.value=undefined;loadBatches();Object.assign(f,{batchNo:'',stage:stage.value==='all'?'incoming':stage.value,standardId:'',objType:'',objCode:'',objName:'',related:[],relCode:'',sampleQty:'',sampleUnit:'',inspector:E.actor||'',inspectedAt:nowLocal(),note:'',vals:{},disposal:'',disposalReason:'',voidReason:'',processCode:''});f.objType=stages.value[f.stage].objectTypes[0];const first=stdChoices.value[0];if(first)f.standardId=first.id;err.value='';ok.value='';phase.value='edit';open.value=true;E.drawerOpen=true}
function openOne(x:Inspection){pick(x);editing.value=x;close2.result='';close2.reason='';if(x.batchRef)loadBatches();const vals:Record<string,unknown>={};for(const r of x.results)vals[r.itemId]=r.value==null?'':r.value;Object.assign(f,{batchNo:x.batchRef?.batchNo||'',stage:x.stage,standardId:x.standardId,objType:x.objectRef.type,objCode:x.objectRef.code,objName:x.objectRef.name,related:[...(x.related||[])],relCode:'',sampleQty:x.sampleQty==null?'':x.sampleQty,sampleUnit:x.sampleUnit,inspector:x.inspector,inspectedAt:x.inspectedAt,note:x.note,vals,disposal:x.disposal||'',disposalReason:x.disposalReason||'',voidReason:'',processCode:x.processCode||''});err.value='';ok.value='';phase.value='edit';open.value=true;E.drawerOpen=true;setQuery({open:x.id});loadRules(x)}
function close(){open.value=false;E.drawerOpen=false;setQuery({open:''});hl.value=''}
function objRef():Ref|undefined{if(!f.objCode.trim())return undefined;const hit=objList.value.find(o=>o.code===f.objCode);return hit?verifiedRef(hit):{type:f.objType,id:'',code:f.objCode.trim(),name:f.objName.trim()}}
function addRel(){const c=f.relCode.trim();if(!c)return;const all=[...E.objects.material,...E.objects.product,...E.objects.project];const obj=all.find(o=>o.code===c);const hit=obj?verifiedRef(obj):st.value.inspections.filter(i=>i.no===c).map(i=>({type:'inspection',id:i.id,code:i.no,name:stages.value[i.stage].label+'检验',verified:true,source:'Cretas'}))[0];f.related.push(hit?{...hit}:{type:f.relType,id:'',code:c,name:''});f.relCode=''}
function payload(submit:boolean){return {id:editing.value?.id,batchNo:batchStage.value?f.batchNo:'',stage:f.stage,processCode:f.processCode,standardId:f.standardId,objectRef:objRef(),related:f.related,results:items.value.map(i=>({itemId:i.id,value:f.vals[i.id]===undefined?'':f.vals[i.id]})),sampleQty:f.sampleQty,sampleUnit:f.sampleUnit,inspector:f.inspector,inspectedAt:f.inspectedAt,note:f.note,submit,disposal:f.disposal,disposalReason:f.disposalReason}}
async function save(submit:boolean){
  err.value='';ok.value='';saving.value=true
  try{const r=await write('inspections/save',payload(submit));if(f.batchNo)await loadBatches();const back=st.value.inspections.find(x=>x.id===r.id);if(!back)throw Error('已提交，回读不一致：重新读取后找不到该检验单');editing.value=back;openOne(back);ok.value=(submit?'已提交':'草稿已保存')+' · 回读一致：'+back.no+' '+(back.status==='SUBMITTED'?meta.value.verdict[back.verdict]+' · '+back.disposal:'草稿 · '+meta.value.verdict[back.verdict])+(back.batchRef?' · 批次 '+back.batchRef.batchNo+(submit?'：'+(holdOf(back.batchRef.batchNo)?.label||'可用'):''):'')}
  catch(e){err.value=e instanceof Error?e.message:'保存失败';if(submit)phase.value='edit'}finally{saving.value=false}
}
function toPreview(){err.value='';const over=items.value.find(i=>i.kind==='numeric'&&bad2(f.vals[i.id]));if(over||bad2(f.sampleQty)){err.value='「'+(over?over.name:'抽样数量')+'」最多两位小数';return}if(verdictNow.value==='INCOMPLETE'){err.value='必检项未填完，不能提交';return}if(!f.inspector.trim()){err.value='请填写检验人';return}if(!f.disposal){err.value='请选择处置';return}if(f.disposal==='让步接收'&&!f.disposalReason.trim()){err.value='让步接收必须填写原因';return}phase.value='preview'}
async function doVoid(){err.value='';if(!f.voidReason.trim()){err.value='作废必须填写原因';return}saving.value=true;try{const r=await write('inspections/void',{id:editing.value!.id,reason:f.voidReason});if(editing.value?.batchRef)await loadBatches();const back=st.value.inspections.find(x=>x.id===r.id)!;openOne(back);ok.value='已作废 · 回读一致：'+back.no+'（原因：'+back.voidReason+'）'}catch(e){err.value=e instanceof Error?e.message:'作废失败'}finally{saving.value=false}}
// 生成任务：带着本检验单的不合格事实去协同任务的新建表单（系统规则草稿 → 预览 → 确认 → 回读）
function makeTask(){const x=editing.value;if(!x)return;E.taskPrefill={text:stages.value[x.stage].label+'检验 '+x.no+' 不合格：'+x.failItems.join('、')+'。对象 '+refText(x.objectRef)+'。请核对并处理。',inspectionId:x.id};close();location.hash='#/collab/tasks'+(E.ws==='ydwm'?'?ws=ydwm':'')}
// 核对方向（系统规则）：AiFloat 接入前页面只显示规则结果（DECISIONS A3）
const rules=ref<EAiAnswer>(),rulesErr=ref('')
async function loadRules(x:Inspection){rules.value=undefined;rulesErr.value='';if(x.status==='VOID')return;try{rules.value=await explain('inspection',x.id,{rulesOnly:true})}catch(e){rulesErr.value=e instanceof Error?e.message:'读取失败'}}
function resultLabel(it:StdItem){if(it.kind==='numeric'&&bad2(f.vals[it.id]))return ['最多两位小数','danger'];const r=itemResult(it,f.vals[it.id]);return r==='PASS'?['合格','success']:r==='FAIL'?['不合格','danger']:[it.required?'未填写':'选检未填','muted']}
// 悬浮窗点依据 → 高亮对应检验项
const onHl=(e:Event)=>{hl.value=(e as CustomEvent).detail||''}
watch(()=>st.value.version,()=>{if(stage.value==='production')loadFc()})
watch(stage,v=>{if(v==='production'&&!fc.value)loadFc()})
onMounted(()=>{try{const all=()=>[...st.value.inspections.map(x=>({no:x.no})),...lakeAll.value.map(x=>({no:x.no}))];const b=batchNoSearch(batchEl.value!,{label:'批量单号',placeholder:'每行一个检验单号',onSearch:(list:string[])=>{nos.value=list;const m=matchNos(all(),list,(r:{no:string})=>r.no);return {found:m.found.length,missing:m.missing}},onClear:()=>{nos.value=[]}});const d=relativeDate(dateEl.value!,{value:dateKey.value,onChange:r=>{dateKey.value=r?.key||''}});dateCtl=d;vt=viewTabs(viewEl.value!,{page:'/quality/inspections@'+E.ws,builtins:STAGE_VIEWS(),getState:viewState,apply:applyView,active:stage.value});shared=[b.element,d.element,vt.element]}catch{/* 共享脚本未加载时不显示这两个控件 */}
  window.addEventListener('e-highlight',onHl);loadBatches();if(stage.value==='production')loadFc();const id=routeQuery().get('open');if(id){const x=st.value.inspections.find(i=>i.id===id);if(x)openOne(x)}})
onBeforeUnmount(()=>{E.drawerOpen=false;window.removeEventListener('e-highlight',onHl);for(const el of shared)el.remove()})
</script>
<template>
<section class="card">
  <div class="section-head"><h2>检验单 <small>{{passRate||'已提交 0 张，暂无合格率'}}</small></h2>
    <div class="actions"><a class="e-link" :href="'#/quality/standards'+(stage!=='all'?'?stage='+stage:'')+(E.ws==='ydwm'?(stage!=='all'?'&':'?')+'ws=ydwm':'')">检验标准</a><UButton size="sm" @click="openNew()">新建检验</UButton></div></div>
  <div ref="viewEl" class="e-views"/>
  <p class="e-stage-hint e-stage-counts">按阶段：<span v-for="k in STAGE_ORDER" :key="k">{{meta.stages[k].label}} {{counts[k].n}}<b v-if="counts[k].fail" class="e-over"> · 不合格 {{counts[k].fail}}</b>　</span>（共 {{counts.all.n}} 张；按当前筛选计数，阶段除外）</p>
  <p v-if="stage!=='all'" class="e-stage-hint">{{meta.stages[stage].hint}}</p>
  <div v-if="stage==='production'" class="e-fc">
    <div class="section-head"><h2>工序首检 <small>首检要求 <span v-html="tag('cretas')"/><template v-if="fc?.available"> · 工序任务与状态 <span v-html="tag('blacklake')"/></template></small></h2><label class="e-switch"><input v-model="fcOnlyLive" type="checkbox">只看执行中</label></div>
    <p v-if="fcErr" class="error" style="margin:8px 16px">{{fcErr}}</p>
    <p v-else-if="fc&&!fc.available" class="e-stage-hint">{{fc.reason}}</p>
    <p v-else-if="fc&&!fc.requirements" class="e-stage-hint">还没有首检要求。在「检验标准 → 生产」里把一条标准设为「用作首检」并选工序。</p>
    <p v-else-if="fc&&!fcRows.length" class="e-stage-hint">没有{{fcOnlyLive?'执行中的':''}}需要首检的工序任务。</p>
    <div v-else-if="fc" class="table-scroll" style="max-height:260px"><table><thead><tr><th>工单</th><th>产品</th><th>工序</th><th>任务状态</th><th>首检标准</th><th>首检</th><th>首笔称重 <i class="help" tabindex="0" title="取 A「称重报工」里该工序任务开始时间最早、未撤销的一笔（Cretas 称重）。首笔称重开始时还没有已提交的首检，标「先称重后首检」；只提醒，不阻断称重和黑湖报工。">?</i></th><th class="op">操作</th></tr></thead>
      <tbody><tr v-for="r in fcRows" :key="r.taskId"><td>{{r.projectCode}}</td><td :title="r.productName">{{r.productName}}</td><td>{{r.processName}}</td><td>{{r.taskStateLabel}}</td><td>{{r.standardName}}</td><td><span class="state" :class="FC_LABEL[r.status][1]">{{FC_LABEL[r.status][0]}}</span><small v-if="r.inspection">{{r.inspection.no}}{{r.inspection.disposal?' · '+r.inspection.disposal:''}}{{r.inspection.at?' · '+r.inspection.at.replace('T',' '):''}}</small></td><td><template v-if="r.firstWeighin">{{r.firstWeighin.start.slice(0,16).replace('T',' ')}}<small>投入 {{Number(r.firstWeighin.inputKg).toFixed(2)}} kg · 良品 {{Number(r.firstWeighin.goodKg).toFixed(2)}} kg</small><span class="state" :class="ORDER_LABEL[r.order!][1]">{{ORDER_LABEL[r.order!][0]}}</span></template><span v-else class="muted">还没有称重</span></td><td class="op"><UButton size="xs" color="neutral" variant="outline" @click="doFirst(r)">{{r.inspection?'详情':'首检'}}</UButton></td></tr></tbody></table></div>
    <p v-if="fc?.available" class="muted" style="padding:6px 16px 10px">未通过首检或先称重后首检只标红提醒，不阻断称重与黑湖报工。首笔称重来自 A「称重报工」<span class="e-tag" :title="MAIN_TITLE">Cretas</span>。共 {{fc?.rows.length??0}} 个工序任务有首检要求。</p>
  </div>
  <div class="filters"><select aria-label="状态" v-model="status"><option v-for="[v,l] in STATUS_OPTS.filter(o=>o[0]!=='LAKE'||E.lake?.available)" :key="v" :value="v">{{l}}</option></select><input v-model="kw" type="search" placeholder="检验单号、对象编码或名称、关联单号" aria-label="搜索检验单"><span ref="batchEl" class="e-shared"/><span class="e-shared e-date"><small>检验时间</small><span ref="dateEl"/></span></div>
  <p v-if="nos.length||dateRange" class="e-stage-hint">{{nos.length?'按单号筛选 '+nos.length+' 个':''}}{{nos.length&&dateRange?' · ':''}}{{dateRange?'检验时间 '+dateRange.label+'（'+dateRange.from+' ~ '+dateRange.to+'）；没有检验时间的记录不在日期范围内':''}}</p>
  <p v-if="E.ws==='lsm'&&(stage==='all'||stage==='incoming'||stage==='warehousing')" class="e-stage-hint"><template v-if="bat?.available">入库批次 {{bat.rows.length}} 个（C 入库批次，Cretas）<template v-if="bat.holds.length"> · <b class="e-over">待处置 / 不可用 {{bat.holds.length}} 个：<span v-for="h in bat.holds" :key="h.batchNo">{{h.batchNo}}（{{h.label}}，{{h.inspectionNo}}） </span></b>，不进先到期先出建议</template><template v-else> · 没有待处置批次</template></template><template v-else-if="bat">入库批次：{{bat.reason}}；来料 / 入库检验暂时不能挂批次</template><template v-else-if="batErr">入库批次读取失败：{{batErr}}</template></p>
  <p v-if="E.lake?.available" class="e-stage-hint">黑湖检验单 {{E.lake.counts.inspection_order??0}} 张（只读，标「黑湖」，在黑湖办理）；黑湖检验项、检验规范、检验结果当前都是 0 条。快照 {{(E.lake.revision||'').slice(0,12)}}</p>
  <p v-if="(stage==='other'||stage==='all')&&otherReasons.length" class="e-stage-hint">归入「其他」的黑湖检验单：<span v-for="x in otherReasons" :key="x.id">{{x.no}}（{{x.stageReason}}） </span></p>
  <div v-if="!st.inspections.length&&!lakeAll.length" class="e-empty"><p>本工作区还没有检验记录（{{E.ws==='lsm'?'黑湖':'味美'}}来源没有返回检验数据）。</p><p v-if="!st.standards.length">先在「检验标准」里为一个阶段配置标准，再新建检验。</p><UButton size="sm" @click="openNew()">新建检验</UButton></div>
  <div v-else-if="!rows.length&&!lakeRows.length" class="e-empty"><p>当前筛选没有检验单。</p></div>
  <div v-else class="table-scroll"><table><thead><tr><th>检验单号</th><th>阶段</th><th>检验对象</th><th>关联单据</th><th>标准</th><th>判定</th><th>处置</th><th>检验人</th><th>检验时间</th><th>来源</th><th class="op">操作</th></tr></thead>
  <tbody><tr v-for="x in rows" :key="x.id" :class="{picked:picked===x.id}" @click="pick(x)">
    <td>{{x.no}}</td><td>{{meta.stages[x.stage].label}}</td>
    <td :title="refText(x.objectRef)">{{refText(x.objectRef)}} <span v-html="refTag(x.objectRef)"/><small v-if="x.batchRef">批次 {{x.batchRef.batchNo}}<b v-if="holdOf(x.batchRef.batchNo)?.inspectionId===x.id" class="e-over"> · {{holdOf(x.batchRef.batchNo)!.label}}</b></small></td>
    <td>{{x.related.length?x.related.map(r=>r.code).join('、'):'—'}}</td>
    <td>{{stdVersion(stdOf(x.standardId),x.standardVersion)?.name||'标准缺失'}} v{{x.standardVersion}}</td>
    <td><span class="state" :class="verdictLabel(x)[1]">{{verdictLabel(x)[0]}}</span><small v-if="x.failItems.length&&x.status!=='VOID'">{{x.failItems.join('、')}}</small></td>
    <td>{{x.disposal||'—'}}</td><td>{{x.inspector||'未填写'}}</td><td>{{x.inspectedAt?x.inspectedAt.replace('T',' '):'未填写'}}</td>
    <td><span v-html="tag('cretas')"/></td>
    <td class="op"><UButton size="xs" color="neutral" variant="outline" @click.stop="openOne(x)">详情</UButton></td>
  </tr>
  <tr v-for="x in lakeRows" :key="x.id" class="e-lake-row">
    <td>{{x.no}}</td><td :title="x.stageReason">{{meta.stages[x.stage].label}}</td>
    <td :title="x.object.code+' '+x.object.name">{{x.object.code}} · {{x.object.name}} <span v-html="tag('blacklake')"/></td>
    <td>{{x.sourceDoc.no||'—'}}<small v-if="x.sourceDoc.type">{{x.sourceDoc.type}}</small></td>
    <td>{{x.spec||'黑湖未指定检验规范'}}</td>
    <td><span class="state" :class="lakeState(x)[1]">{{lakeState(x)[0]}}</span></td>
    <td>—</td><td>未返回</td><td>{{x.createdAt||'未返回'}}</td>
    <td><span v-html="tag('blacklake')"/></td>
    <td class="op"><UButton size="xs" color="neutral" variant="outline" @click.stop="lakeOpen=x;E.drawerOpen=true">详情</UButton></td>
  </tr></tbody></table></div>
  <div class="pagination"><span>共 {{rows.length+lakeRows.length}} 张<template v-if="lakeRows.length">（含黑湖 {{lakeRows.length}} 张）</template></span></div>
</section>

<aside v-if="lakeOpen" class="e-drawer" role="dialog" aria-modal="false" :aria-label="'黑湖检验单 '+lakeOpen.no" @keydown.esc="lakeOpen=undefined;E.drawerOpen=false">
  <header><div><h2>{{lakeOpen.no}} <span class="state" :class="lakeState(lakeOpen)[1]">{{lakeState(lakeOpen)[0]}}</span></h2><p>{{meta.stages[lakeOpen.stage].label}}阶段 · {{lakeOpen.object.code}} · {{lakeOpen.object.name}} · <span v-html="tag('blacklake')"/></p></div><UButton aria-label="关闭" size="xs" color="neutral" variant="ghost" @click="lakeOpen=undefined;E.drawerOpen=false">×</UButton></header>
  <div class="e-body">
    <p class="e-preview">只读：这张检验单来自黑湖，在黑湖办理；Cretas 不修改、不回写。</p>
    <div class="e-sec"><h3>基本信息</h3><dl class="e-kv">
      <dt>阶段</dt><dd>{{meta.stages[lakeOpen.stage].label}} <small>（{{lakeOpen.stageReason}}）</small></dd>
      <dt>检验对象</dt><dd>{{lakeOpen.object.code}} · {{lakeOpen.object.name}}{{lakeOpen.object.spec?' · '+lakeOpen.object.spec:''}}</dd>
      <dt>来源单据</dt><dd>{{lakeOpen.sourceDoc.no||'未返回'}}{{lakeOpen.sourceDoc.type?' · '+lakeOpen.sourceDoc.type:''}}{{lakeOpen.sourceDoc.status?' · '+lakeOpen.sourceDoc.status:''}}</dd>
      <dt>工序</dt><dd>{{lakeOpen.process||'无（非生产检验）'}}</dd>
      <dt>检验规范</dt><dd>{{lakeOpen.spec||'黑湖未指定'}}（黑湖检验规范当前 0 条）</dd>
      <dt>计划完成</dt><dd>{{lakeOpen.planFinishedTime||'未返回'}}</dd>
      <dt>创建</dt><dd>{{lakeOpen.createdAt||'未返回'}}（黑湖时间）</dd></dl></div>
    <div class="e-sec"><h3>数量</h3><dl class="e-kv">
      <dt>送检数量</dt><dd>{{q2(lakeOpen.submittedQty,lakeOpen.object.unit)}}</dd>
      <dt>已检 / 合格 / 不合格</dt><dd v-if="lakeOpen.hasResult">{{q2(lakeOpen.inspectedQty,lakeOpen.object.unit)}} / {{q2(lakeOpen.qualifiedQty,lakeOpen.object.unit)}} / {{q2(lakeOpen.unqualifiedQty,lakeOpen.object.unit)}}</dd><dd v-else>未检验（黑湖记为 0，没有检验结果，不作为合格或不合格结论）</dd></dl></div>
    <div class="e-sec"><h3>检验结果</h3><p class="muted">{{lakeOpen.hasResult?'黑湖已返回结果，但检验项明细为 0 条，未展开。':'黑湖还没有检验结果；检验项与结果当前都是 0 条。'}}</p></div>
  </div>
  <footer><span class="muted">黑湖快照 {{(E.lake?.revision||'').slice(0,12)}} · 只读</span></footer>
</aside>
<aside v-if="open" class="e-drawer" role="dialog" aria-modal="false" :aria-label="editing?'检验单 '+editing.no:'新建检验'" @keydown.esc="close()">
  <header><div><h2>{{editing?editing.no:'新建检验'}} <span v-if="editing" class="state" :class="verdictLabel(editing)[1]">{{verdictLabel(editing)[0]}}</span></h2>
    <p>{{stages[f.stage].label}}阶段 · {{refText(objRef())}} <span v-html="tag('cretas')"/></p></div>
    <UButton aria-label="关闭详情" size="xs" color="neutral" variant="ghost" @click="close()">×</UButton></header>
  <div class="e-body">
    <div class="e-sec"><h3>1 基本信息</h3>
      <div class="e-grid">
        <label><span class="e-req">阶段</span><select v-model="f.stage" :disabled="!!editing"><option v-for="k in STAGE_ORDER" :key="k" :value="k">{{stages[k].label}}</option></select></label>
        <label><span class="e-req">检验标准</span><select v-model="f.standardId" :disabled="locked" aria-label="检验标准"><option value="">请选择</option><option v-for="s in stdChoices" :key="s.id" :value="s.id">{{stdVersion(s)!.name}} v{{stdVersion(s)!.v}}{{s.active?'':'（已停用）'}}</option></select></label>
        <p v-if="!stdChoices.length&&!locked" class="e-err wide" style="grid-column:1/-1">{{stages[f.stage].label}}阶段还没有启用的检验标准，不能录入。<a class="e-link" :href="'#/quality/standards?stage='+f.stage+(E.ws==='ydwm'?'&ws=ydwm':'')">去配置标准</a></p>
        <p v-else-if="scopeNote" class="muted wide" style="grid-column:1/-1">{{scopeNote}}</p>
        <label v-if="f.stage==='production'&&fcProcs.length" class="wide"><span class="e-req">首检工序</span><select v-model="f.processCode" :disabled="locked" aria-label="首检工序"><option value="">请选择</option><option v-for="c in fcProcs" :key="c" :value="c">{{fc?.rows.find(r=>r.processCode===c)?.processName||c}}</option></select><small>这条标准用作首检，检验单记为首检</small></label>
        <div v-if="batchStage" class="e-field wide"><span>入库批次 <span class="e-tag" :title="MAIN_TITLE">Cretas</span></span>
          <select v-model="f.batchNo" :disabled="locked||!!(editing&&editing.batchRef)||!bat?.available" aria-label="入库批次"><option value="">{{bat?.available?(batchChoices.length?'不挂批次':(f.stage==='incoming'?'还没有采购入库批次':'还没有入库批次')):(bat?.reason||'读取中')}}</option><option v-for="b in batchChoices" :key="b.batchNo" :value="b.batchNo">{{b.batchNo}} · {{b.productName}} · {{KIND_LABEL[b.kind]}} · 到期 {{b.expiryDate||'未填'}}{{holdOf(b.batchNo)?' · '+holdOf(b.batchNo)!.label:''}}</option></select>
          <small v-if="curBatch">{{KIND_LABEL[curBatch.kind]}} · {{curBatch.productCode}} {{curBatch.productName}} · 生产日期 {{curBatch.productionDate||'未填'}} · 到期 {{expiryText(curBatch.expiryDate)}} · {{curBatch.qty==null?'数量未填':Number(curBatch.qty).toFixed(2)+' '+curBatch.unit}}{{curBatch.lakeInboundNo?' · 黑湖入库单 '+curBatch.lakeInboundNo:' · 未关联黑湖单'}}</small>
          <small v-else-if="f.stage==='incoming'" class="muted">来料检验只列采购入库批次。挂批次后检验对象由批次带出；不合格提交后批次标「待处置」，不进先到期先出建议。</small>
          <small v-else class="muted">入库检验可挂采购入库或生产入库批次。不合格提交后批次标「待处置」，不进先到期先出建议。</small></div>
        <label v-if="objTypes.length>1"><span>对象类型</span><select v-model="f.objType" :disabled="locked||!!f.batchNo"><option v-for="t in objTypes" :key="t" :value="t">{{TYPE_LABEL[t]}}</option></select></label>
        <label :class="{wide:objTypes.length===1}"><span class="e-req">检验对象（{{stages[f.stage].objectLabel}}）</span>
          <input v-model="f.objCode" :disabled="locked||curVer?.scope==='object'||!!f.batchNo" list="e-obj-list" :placeholder="objList.length?'输入编码或从列表选择':'本工作区没有可选'+TYPE_LABEL[f.objType]+'，可手填编号（标未核实）'" aria-label="检验对象编码">
          <datalist id="e-obj-list"><option v-for="o in objList" :key="o.code" :value="o.code">{{o.name}}</option></datalist>
          <small v-if="f.objCode&&objList.find(o=>o.code===f.objCode)">{{objList.find(o=>o.code===f.objCode)!.name}} · {{E.ws==='lsm'?'黑湖':'味美'}}</small><small v-else-if="f.objCode" class="e-over">不在{{E.ws==='lsm'?'黑湖快照':'味美接口'}}中，保存为未核实引用</small></label>
        <label v-if="f.objCode&&!objList.find(o=>o.code===f.objCode)"><span>对象名称（手填）</span><input v-model="f.objName" :disabled="locked"></label>
        <label><span>抽样数量</span><input v-model="f.sampleQty" type="number" step="0.01" min="0" :disabled="locked" placeholder="未填写"></label>
        <label><span>单位</span><input v-model="f.sampleUnit" :disabled="locked" placeholder="如 KG、箱"></label>
        <label><span class="e-req">检验人</span><input v-model="f.inspector" :disabled="locked"></label>
        <label><span>检验时间</span><input v-model="f.inspectedAt" type="datetime-local" :disabled="locked"></label>
        <div class="e-field wide"><span>关联单据</span><div class="e-rel"><span v-for="(r,i) in f.related" :key="i"><a class="e-link" href="javascript:void 0" @click="openTarget(r)">{{TYPE_LABEL[r.type]||r.type}} {{r.code}}</a><span v-html="refTag(r)"/><button v-if="!locked" class="x" :aria-label="'移除 '+r.code" @click="f.related.splice(i,1)">×</button></span><em v-if="!f.related.length" class="muted">无</em></div>
          <div v-if="!locked" class="actions" style="margin-top:6px"><select v-model="f.relType" aria-label="关联类型" style="width:96px"><option value="project">工单</option><option value="material">原材料</option><option value="product">成品</option><option value="inspection">检验单</option><option value="other">其他</option></select><input v-model="f.relCode" placeholder="单号" aria-label="关联单号" style="flex:1" @keydown.enter.prevent="addRel()"><UButton size="xs" color="neutral" variant="outline" @click="addRel()">添加</UButton></div></div>
      </div></div>
    <div class="e-sec"><h3>2 检验项结果 <i class="help" tabindex="0" title="单项结果由代码计算：数值项按上下限，选项项按标准里的合格选项；判定项由检验人选合格/不合格。标准值只作参考。缺失显示未填写，不当作 0。">?</i></h3>
      <p v-if="!items.length" class="muted">选择检验标准后显示检验项。</p>
      <table v-else class="e-items"><thead><tr><th>项目</th><th>标准</th><th style="width:130px">实测 / 判定</th><th>结果</th></tr></thead>
      <tbody><tr v-for="it in items" :key="it.id" :class="{fail:itemResult(it,f.vals[it.id])==='FAIL',hl:hl===it.name}"><td>{{it.name}}<small>{{[it.method,it.tool].filter(Boolean).join(' · ')||'—'}}{{it.required?'':' · 选检'}}</small></td><td>{{range(it)}}<small v-if="it.target!=null">标准值 {{Number(it.target).toFixed(2)}} {{it.unit}}</small></td>
        <td><select v-if="it.kind==='judge'" v-model="f.vals[it.id]" :disabled="locked" :aria-label="it.name"><option value="">未填写</option><option>合格</option><option>不合格</option></select><select v-else-if="it.kind==='option'" v-model="f.vals[it.id]" :disabled="locked" :aria-label="it.name"><option value="">未填写</option><option v-for="o in it.options" :key="o">{{o}}</option></select><input v-else v-model="f.vals[it.id]" type="number" step="0.01" :disabled="locked" :aria-label="it.name+'（'+it.unit+'）'" :placeholder="it.unit"></td>
        <td><span class="state" :class="resultLabel(it)[1]">{{resultLabel(it)[0]}}</span></td></tr></tbody></table>
    </div>
    <div class="e-sec"><h3>3 判定与处置</h3>
      <div class="e-verdict" :class="verdictNow"><b>总判定（代码算）：{{VERDICT_TEXT(verdictNow)}}</b><span v-if="verdictNow==='INVALID'">有数值超过两位小数，改正后再判定</span><span v-if="verdictNow==='INCOMPLETE'">{{items.length?'必检项未填完':'未选择检验标准'}}</span>
        <UButton v-if="editing&&editing.status==='SUBMITTED'&&editing.verdict==='FAIL'" size="xs" style="margin-left:auto" @click="makeTask()">生成任务</UButton></div>
      <div class="e-grid" style="margin-top:8px">
        <label><span class="e-req">处置</span><select v-model="f.disposal" :disabled="locked||!disposalOpts.length" aria-label="处置"><option value="">{{disposalOpts.length?'请选择':'先完成必检项'}}</option><option v-for="d in disposalOpts" :key="d">{{d}}</option></select><small v-if="verdictNow==='FAIL'">不合格时不能放行</small></label>
        <label><span :class="{'e-req':f.disposal==='让步接收'}">处置原因</span><input v-model="f.disposalReason" :disabled="locked" placeholder="让步接收时必填"></label>
        <label class="wide"><span>备注</span><textarea v-model="f.note" :disabled="locked"/></label>
      </div>
      <p v-if="editing?.status==='VOID'" class="e-err">已作废：{{editing.voidReason}}</p>
      <div v-if="editing?.batchRef&&editing.status==='SUBMITTED'&&editing.verdict==='FAIL'" class="e-preview" aria-label="批次处置"><h4>批次处置 · {{editing.batchRef.batchNo}} <span class="state" :class="batchHoldBanner?'danger':'success'">{{batchHoldBanner?batchHoldBanner.label:'可用'}}</span></h4>
        <template v-if="editing.batchClose"><p>结论：{{editing.batchClose.result}}（{{editing.batchClose.reason}}）· {{editing.batchClose.by}} · {{fmtTime(editing.batchClose.at)}}</p><p class="muted">{{batchHoldBanner?'该批次不进先到期先出建议。':'该批次恢复可用，按到期日参与先到期先出建议。'}}</p></template>
        <template v-else-if="batchHoldBanner"><p class="muted">检验不合格，批次待处置：C 的先到期先出建议会跳过它。处置结论只记在本检验单，不改 C 的批次数量、不回写黑湖。</p>
          <div class="e-grid"><label><span class="e-req">处置结论</span><select v-model="close2.result" aria-label="批次处置结论"><option value="">请选择</option><option>放行</option><option>让步放行</option><option>退货</option><option>报废</option></select><small>放行 / 让步放行：恢复可用；退货 / 报废：不可用</small></label><label><span class="e-req">处置说明</span><input v-model="close2.reason" aria-label="处置说明" placeholder="如：复检合格 / 已与供应商约定退货"></label></div>
          <div class="actions" style="margin-top:6px"><UButton size="xs" :loading="saving" @click="doCloseBatch()">保存批次处置</UButton></div></template>
        <p v-else class="muted">该批次后面有更新的检验单，状态以最新一张为准。</p></div>
      <div v-if="editing&&rules&&editing.verdict!=='PASS'" class="e-preview" aria-label="核对方向"><h4>核对方向 <span v-html="tag('system')"/> <i class="help" tabindex="0" title="按阶段与判定由规则给出，不改判定、不改业务单据。解读（模型选择核对项）统一在右下角悬浮窗，接入后出现。">?</i></h4><p>{{rules.conclusion}}</p><ol style="padding-left:18px;margin:4px 0 0"><li v-for="(a,i) in rules.items" :key="i">{{a.text}}<span v-if="a.who"> · 找{{a.who}}</span></li></ol></div>
      <p v-if="rulesErr" class="muted">核对方向读取失败：{{rulesErr}}</p>
    </div>
    <div v-if="phase==='preview'" class="e-preview" role="region" aria-label="提交预览"><h4>提交预览</h4>
      <dl class="e-kv"><dt>检验单</dt><dd>{{editing?.no||'新检验单'}} · {{stages[f.stage].label}}</dd><dt>对象</dt><dd>{{refText(objRef())}}</dd><dt>判定</dt><dd>{{meta.verdict[verdictNow]}}</dd><dt>处置</dt><dd>{{f.disposal}}{{f.disposalReason?'（'+f.disposalReason+'）':''}}</dd><template v-if="f.batchNo"><dt>批次</dt><dd>{{f.batchNo}} · 到期 {{expiryText(curBatch?.expiryDate||'')}}{{verdictNow==='FAIL'?' · 提交后标「待处置」，不进先到期先出建议':''}}</dd></template><dt>影响</dt><dd>提交后阶段、对象、标准版本与实测值锁定；只能作废不能改。不改任何业务单据状态，不改 C 的批次数量，不回写黑湖。</dd></dl></div>
    <div v-if="phase==='void'" class="e-preview"><h4>作废检验单</h4><label class="e-field"><span class="e-req">作废原因</span><input v-model="f.voidReason" aria-label="作废原因"></label><p class="muted">作废后原记录与修改记录保留，可查不可改。</p></div>
    <p v-if="err" role="alert" class="e-err">{{err}}</p><p v-if="ok" role="status" class="e-ok">{{ok}}</p>
    <details v-if="editing" class="e-log e-sec"><summary>修改记录 {{editing.log.length}} 条</summary><div v-html="logHTML(editing.log,'检验单')"/></details>
  </div>
  <footer>
    <span class="muted">{{E.session?'写入练习会话 '+E.session:'写入本机原型保存区'}}</span>
    <template v-if="!locked">
      <template v-if="phase==='preview'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">返回修改</UButton><UButton size="sm" :loading="saving" @click="save(true)">确认提交</UButton></template>
      <template v-else><UButton size="sm" color="neutral" variant="outline" :loading="saving" :disabled="!f.standardId" @click="save(false)">保存草稿</UButton><UButton size="sm" :disabled="!f.standardId" @click="toPreview()">提交结果</UButton></template>
    </template>
    <template v-if="editing&&editing.status!=='VOID'">
      <template v-if="phase==='void'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">取消</UButton><UButton size="sm" color="error" :loading="saving" @click="doVoid()">确认作废</UButton></template>
      <UButton v-else size="sm" color="error" variant="ghost" @click="phase='void'">作废</UButton>
    </template>
  </footer>
</aside>
</template>
