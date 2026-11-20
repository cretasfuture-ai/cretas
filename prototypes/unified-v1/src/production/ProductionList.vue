<script setup lang="ts">
// 生产链列表：产品 / 生产工单 / 生产报工 / 工序目录 / BOM 用料依据。
// 迁自 liushanmen-v1/src/App.vue 的列表部分；行高 32-33px；行内只保留「详情」，解读统一走右下角悬浮窗（当前对象）。
import {computed,onMounted,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import type {Row,ListKind} from '../frame/types'
import {S,rs,rowId,text,num,fmtDate,stateLabel,inspect,setCurrent,scopeOfRow,navigate} from '../frame/store'
import {orderMapping} from './mapping'
import {SUP,effectiveSource,loadSupplements,openImport,openBatches} from './supplements'
import {progress,pct} from './progress'
import YdwmProduction from './YdwmProduction.vue'
import WipMatrix from './WipMatrix.vue'
import WeighIn from './WeighIn.vue'
import DeliveryRisk from './DeliveryRisk.vue'
import EcnGap from './EcnGap.vue'
import ShortageCases from './ShortageCases.vue'
import ShopFloor from './ShopFloor.vue'
import StatusKanban from './StatusKanban.vue'
import ProductionAnalysis from './ProductionAnalysis.vue'
import YieldStandard from './YieldStandard.vue'
import {fmtMoney,fmtNumber,batchNoSearch,matchNos,relativeDate,relativeRange,viewTabs,cretasUI} from '../frame/ai-protocol'
import {onBeforeUnmount} from 'vue'
import {acceptanceSession} from './session'
const props=defineProps<{kind:ListKind;embedded?:boolean}>()
const q=ref(''),query=ref(''),status=ref(''),statusDraft=ref(''),unit=ref(''),unitDraft=ref(''),productCode=ref(''),productDraft=ref(''),rowPage=ref(1),pageSize=ref(20),picked=ref(''),mapF=ref(''),mapDraft=ref(''),schedF=ref(''),schedDraft=ref('')
const KEY=()=>'unified-lsm-list:'+props.kind
const resourceOf:Record<ListKind,string>={products:'product',orders:'project',reports:'output',processes:'process',bom:'bom'}
const names:Record<ListKind,string>={products:'产品',orders:'生产工单',reports:'生产报工 · 只读',processes:'工序目录',bom:'标准用料总表'}
const tone=(v:unknown)=>String(v)==='20'?'success':String(v)==='30'?'muted':String(v)==='10'?'active':'pending'
const columns=computed(()=>({products:[['productCode','产品编码'],['productName','产品'],['unit','单位'],['orders','关联工单'],['boms','BOM条目']],orders:[['projectCode','工单'],['productName','产品'],['status','状态'],['planAmount','计划量'],['good','工单良品'],['projectUnit','单位'],['workPct','工单进度'],['timePct','时间进度'],['schedule','工期'],['tasks','工序任务'],['reports','报工'],['loop','闭环'],['mapping','路线对应']],reports:[['id','报工ID'],['project','来源工单'],['process','工序'],['taskId','任务ID'],['numberofgoodProducts','良品'],['stockUnit','单位'],['approvalStatus','审批'],['labor','人工（工价规则）'],['endtimeofReport','报工结束']],processes:[['code','工序编码'],['name','工序'],['tasks','任务数'],['boms','BOM绑定']],bom:[['id','BOM行ID'],['lastProductCode','产出产品'],['nextProductCode','投入产品'],['feedProcessCode','投料工序编码'],['unitQty','标准数量'],['parentBaseQty','产出基数'],['lossRate','损耗率']]} as Record<ListKind,string[][]>)[props.kind])
// 第五批 ①：路线对应按「黑湖来源 + 生效的 Cretas 补充路线」重算
const effSrc=computed(()=>S.source?effectiveSource(S.source):null)
const baseRows=computed<Row[]>(()=>{const r=S.source?.resources;if(!r)return [];const kind=resourceOf[props.kind];return (r[kind]||[]).map(a=>({...a,orders:kind==='product'?r.project.filter(x=>x.productCode===a.productCode).length:undefined,boms:kind==='product'?r.bom.filter(x=>x.lastProductCode===a.productCode).length:kind==='process'?r.bom.filter(x=>x.feedProcessCode===a.code).length:undefined,tasks:kind==='project'?r.task.filter(x=>String(x.projectId)===rowId(a)).length:kind==='process'?r.task.filter(x=>String(x.processId)===rowId(a)).length:undefined,reports:kind==='project'?r.output.filter(x=>String(x.projectId)===rowId(a)).length:undefined,mapping:kind==='project'&&S.source?orderMapping(effSrc.value!,rowId(a)):undefined,loop:kind==='project'?(()=>{const c=S.source?.costs.find(x=>String(x.projectId)===rowId(a));const ml=Array.isArray(c?.materialLinesDetail)?c!.materialLinesDetail.length:0;const g=Number(a.numberofGoodProducts)||0;return '领 '+(ml?'✓':'—')+' · 产 '+(g>0?'✓':'—')+' · 入 ?'})():undefined,...(kind==='project'?(()=>{const g=progress({planned:a.planAmount,done:a.numberofGoodProducts,start:a.planStartTime,end:a.planEndTime,finished:String(a.status)==='20',cancelled:String(a.status)==='30'});return {workPct:pct(g.work,g.workReason),timePct:pct(g.time===null?null:Math.min(g.time,1),g.timeReason),schedule:g.label,_progress:g}})():{}),good:kind==='project'?a.numberofGoodProducts:undefined,project:kind==='output'?r.project.find(x=>String(x.id)===String(a.projectId))?.projectCode:undefined,process:kind==='output'?r.process.find(x=>String(x.id)===String(a.processId))?.name:undefined}))})
// 第四批 ③：接共享组件（SHARED「接入说明」）。批量单号紧挨搜索框，匹配用全部记录；相对日期按北京时间日历日，偏好里存 key 不存日期。
// 工单按计划开始日期筛，报工按报工结束时间筛；日期缺失的记录在选了相对日期时不显示（不猜日期）。
const nos=ref<string[]>([]),dateKey=ref(''),range=ref<{from:string;to:string}|null>(null),sharedHost=ref<HTMLElement>()
const noOf=(r:Row)=>String(props.kind==='orders'?r.projectCode??'':r.id??'')
const dayOf=(r:Row)=>{const v=props.kind==='orders'?r.planStartTime:r.endtimeofReport;return v?String(v).slice(0,10):''}
const inRange=(r:Row)=>{if(!range.value)return true;const d=dayOf(r);return !!d&&d>=range.value.from&&d<=range.value.to}
const filtered=computed(()=>baseRows.value.filter(r=>(!status.value||String(r.status??r.approvalStatus??'')===status.value)&&passOther(r)))
// 第五批 ②：四态计数按「除状态以外的全部筛选」计算，点计数等于切状态筛选
const passOther=(r:Row)=>(!query.value||columns.value.some(([k])=>String(r[k]??'').toLowerCase().includes(query.value.toLowerCase())))&&(!productCode.value||[r.productCode,r.lastProductCode].includes(productCode.value))&&(!unit.value||[r.unit,r.projectUnit,r.stockUnit].includes(unit.value))&&(!mapF.value||String(r.mapping??'').includes(mapF.value))&&(!schedF.value||r.schedule===schedF.value)&&(!nos.value.length||nos.value.includes(noOf(r)))&&inRange(r)
const rows=computed(()=>filtered.value.slice((rowPage.value-1)*pageSize.value,rowPage.value*pageSize.value)),maxPage=computed(()=>Math.max(1,Math.ceil(filtered.value.length/pageSize.value))),units=computed(()=>[...new Set(S.source?.costs.map(c=>c.unit).filter((s):s is string=>!!s)||[])])
function persist(){try{localStorage.setItem(KEY(),JSON.stringify({q:query.value,status:status.value,unit:unit.value,productCode:productCode.value,mapping:mapF.value,schedule:schedF.value,pageSize:pageSize.value,rowPage:rowPage.value}))}catch{/* 偏好未保存不影响业务 */}}
function restore(){schedF.value=schedDraft.value='';mapF.value=mapDraft.value='';q.value=query.value=status.value=statusDraft.value=unit.value=unitDraft.value=productCode.value=productDraft.value='';rowPage.value=1;pageSize.value=20;try{const s=JSON.parse(localStorage.getItem(KEY())||'null');if(s){for(const [k,r] of Object.entries({query,status,unit,productCode}))if(typeof s[k==='query'?'q':k]==='string')r.value=s[k==='query'?'q':k];q.value=query.value;statusDraft.value=status.value;unitDraft.value=unit.value;productDraft.value=productCode.value;if(typeof s.mapping==='string')mapF.value=mapDraft.value=s.mapping;if(typeof s.schedule==='string')schedF.value=schedDraft.value=s.schedule;if([20,50,100].includes(s.pageSize))pageSize.value=s.pageSize;if(Number.isInteger(s.rowPage)&&s.rowPage>0)rowPage.value=s.rowPage}}catch{/* 默认 */}}
function search(){schedF.value=schedDraft.value;mapF.value=mapDraft.value;query.value=q.value;status.value=statusDraft.value;unit.value=unitDraft.value;productCode.value=productDraft.value;rowPage.value=1;persist()}
function reset(){schedDraft.value='';mapDraft.value='';q.value='';statusDraft.value='';unitDraft.value='';productDraft.value='';search()}
function pick(r:Row){picked.value=rowId(r);const sc=scopeOfRow(resourceOf[props.kind],r);if(sc)setCurrent(sc,names[props.kind]+'列表')}
function open(r:Row){pick(r);inspect(resourceOf[props.kind],r)}
// BL 16（B 出规则与计算）：报工带出人工成本，只读显示；工价规则属 Cretas 补充，黑湖任务工资有值时以黑湖为准
const topSession=acceptanceSession()
const labor=ref<Record<string,{amount:number|null;reason:string;mode:string|null}>>({})
async function loadLabor(){if(props.kind!=='reports'||!rows.value.length)return;const ids=rows.value.map(r=>rowId(r)).filter(id=>!(id in labor.value));if(!ids.length)return;try{const p=new URLSearchParams({reports:ids.join(',')});if(topSession)p.set('acceptanceSession',topSession);const b=await (await fetch('/__lsm/product-cost/report-labor?'+p)).json();const next={...labor.value};for(const x of (b?.data?.rows||[]))next[String(x.reportId)]={amount:x.amount??null,reason:String(x.reason||''),mode:x.mode??null};for(const id of ids)if(!(id in next))next[id]={amount:null,reason:'工价接口未返回',mode:null};labor.value=next}catch{const next={...labor.value};for(const id of ids)next[id]={amount:null,reason:'工价接口不可用',mode:null};labor.value=next}}
watch(()=>[props.kind,rows.value.map(r=>rowId(r)).join(',')],loadLabor,{immediate:true})
// 列表数值统一两位小数（计数取整）；文字列原样显示，缺失写「未填写」
const isNum=(v:unknown)=>typeof v==='number'?Number.isFinite(v):typeof v==='string'&&/^-?\d+(?:\.\d+)?$/.test(v.trim())
function cell(key:string,v:unknown){if(!isNum(v))return text(v);return ['tasks','reports','orders','boms'].includes(key)?fmtNumber(v,{digits:0}):fmtNumber(v)}
function canvas(r:Row){pick(r);navigate('/production/process-hub',{project:rowId(r)})}
// 深链（DECISIONS 六之后）：工单 ?project=<id>；报工 ?no=<报工ID>（兼容 ?report=）；产品 ?product=<id>。打开后筛到并选中该记录。
function focusFromQuery(){const pid=S.query.get('project');if(pid&&props.kind==='orders'){const r=rs('project').find(p=>rowId(p)===pid);if(r){q.value=query.value=String(r.projectCode||'');rowPage.value=1;open(r)}}
 const no=S.query.get('no')||S.query.get('report');if(no&&props.kind==='reports'){const r=rs('output').find(p=>rowId(p)===no);if(r){q.value=query.value=no;rowPage.value=1;open(r)}else S.notice='没有找到报工 '+no+'，未按相近记录猜配。'}
 const prod=S.query.get('product');if(prod&&props.kind==='products'){const r=rs('product').find(p=>rowId(p)===prod);if(r){q.value=query.value=String(r.productCode||'');rowPage.value=1;open(r)}}}
watch(()=>props.kind,()=>{restore();picked.value=''})
watch(()=>[S.source?.revision,S.query.toString()],focusFromQuery)
let mounted:HTMLElement[]=[]
function mountShared(){for(const m of mounted)m.remove();mounted=[];const host=sharedHost.value;if(!host||!['orders','reports'].includes(props.kind))return
  const b=batchNoSearch(host,{label:props.kind==='orders'?'批量工单号':'批量报工ID',placeholder:props.kind==='orders'?'每行一个工单号':'每行一个报工ID',onSearch:list=>{nos.value=list;rowPage.value=1;const m=matchNos(baseRows.value,list,noOf);return {found:m.found.length,missing:m.missing}},onClear:()=>{nos.value=[]}});mounted.push(b.element)
  try{dateKey.value=JSON.parse(localStorage.getItem(KEY()+':date')||'""')||''}catch{dateKey.value=''}
  range.value=dateKey.value?relativeRange(dateKey.value):null
  const d=relativeDate(host,{value:dateKey.value,allowAll:true,onChange:r=>{dateKey.value=r?.key||'';range.value=r?{from:r.from,to:r.to}:null;rowPage.value=1;try{localStorage.setItem(KEY()+':date',JSON.stringify(dateKey.value))}catch{/* 偏好未保存不影响业务 */}}});dateCtl=d;mounted.push(d.element);mountViews()}
// 第五批 ②（BL 22）：工单列表已存视图 + 四态计数（共享 viewTabs）。视图存筛选和日期 key，不存日期；批量单号不进视图。
const viewsHost=ref<HTMLElement>()
let views:{element:HTMLElement;refresh:(c?:{key:string;label:string;count:number|null}[],k?:string)=>void;readonly active:string}|null=null
const STATUS4:[string,string][]=[['0','未开始'],['10','执行中'],['20','已结束'],['30','已取消']]
const countBase=computed(()=>props.kind==='orders'?baseRows.value.filter(passOther):[])
const counts=computed(()=>[{key:'',label:'全部',count:countBase.value.length},...STATUS4.map(([k,l])=>({key:k,label:l,count:countBase.value.filter(r=>String(r.status??'')===k).length}))])
const BUILTIN_VIEWS=[{id:'all',name:'全部工单',state:{}},{id:'sup-running',name:'车间主管 · 执行中',state:{status:'10'}},{id:'plan-late',name:'计划员 · 已超期',state:{schedule:'已超期'}},{id:'eng-route',name:'工艺员 · 路线待补',state:{mapping:'产品无当前路线'}}]
const viewState=()=>({q:query.value,status:status.value,unit:unit.value,productCode:productCode.value,mapping:mapF.value,schedule:schedF.value,dateKey:dateKey.value})
let dateCtl:{set(v:string):void}|null=null
function setDate(key:string){dateKey.value=key;range.value=key?relativeRange(key):null;dateCtl?.set(key);try{localStorage.setItem(KEY()+':date',JSON.stringify(key))}catch{/* 偏好未保存不影响业务 */}}
function applyView(v:Record<string,unknown>){const g=(k:string)=>typeof v[k]==='string'?v[k] as string:'';q.value=g('q');statusDraft.value=g('status');unitDraft.value=g('unit');productDraft.value=g('productCode');mapDraft.value=g('mapping');schedDraft.value=g('schedule');search();setDate(g('dateKey'))}
function mountViews(){views?.element.remove();views=null;const host=viewsHost.value;if(!host||props.kind!=='orders')return
  const empty=Object.values(viewState()).every(x=>!x)
  views=viewTabs(host,{page:'lsm:/production/plans',builtins:BUILTIN_VIEWS,active:empty?'all':'none',getState:viewState,apply:applyView,counts:counts.value,countKey:status.value,onCount:k=>{statusDraft.value=status.value===k?'':k;search()}} as Parameters<typeof viewTabs>[1])}
// 第五批审计退回 3：当前筛选和选中视图的保存条件不一致时，取消该页签高亮并标「已修改」
const VIEW_KEYS=['q','status','unit','productCode','mapping','schedule','dateKey']
function markModified(){if(!views)return;const id=views.active;const v=[...BUILTIN_VIEWS,...cretasUI().readViews('lsm:/production/plans')].find(x=>x.id===id);if(!v)return;const cur=viewState() as Record<string,string>,st=v.state as Record<string,unknown>;const same=VIEW_KEYS.every(k=>String(st[k]??'')===String(cur[k]??''));const tab=views.element.querySelector('[data-v="'+CSS.escape(id)+'"]');if(!tab||same)return;tab.setAttribute('aria-selected','false');tab.setAttribute('title','当前筛选已和这个视图不同；点一下恢复视图条件，或「＋ 另存视图」');const m=document.createElement('small');m.className='view-modified';m.textContent='（已修改）';tab.append(m)}
watch([counts,status,query,unit,productCode,mapF,schedF,dateKey],()=>{views?.refresh(counts.value,status.value);markModified()},{flush:'post'})
onBeforeUnmount(()=>{for(const m of mounted)m.remove();views?.element.remove()})
// 第五批审计退回 1：框架换路由时（如 /production/wip ↔ /production/plans）列表的 v-else 分支会重建，宿主元素换了新的，
// 只在 onMounted 挂一次的共享控件会丢。改为：列表种类或宿主元素变化时都重新挂载（flush post，等 DOM 换好）。
watch(()=>props.kind,()=>{nos.value=[]})
// 宿主没换、控件都还在、且挂载时的列表种类没变，才跳过；种类变了（报工 ↔ 工单）要换成对应的控件和视图页签
let mountedKind=''
function ensureShared(){const h=sharedHost.value;if(h&&mounted.length&&mountedKind===props.kind&&mounted.every(m=>h.contains(m))&&(props.kind!=='orders'||(!!views&&!!viewsHost.value?.contains(views.element))))return;nos.value=[];mountedKind=props.kind;mountShared()}
watch([()=>props.kind,sharedHost,viewsHost],ensureShared,{flush:'post'})
onMounted(()=>{restore();focusFromQuery();setTimeout(ensureShared);loadSupplements()})
</script>
<template><DeliveryRisk v-if="S.path==='/production/delivery-risks'"/><ShortageCases v-else-if="S.path==='/production/shortage-cases'"/><ShopFloor v-else-if="S.path==='/dashboard/production-progress'"/><StatusKanban v-else-if="S.path==='/production/kanban'"/><ProductionAnalysis v-else-if="S.path==='/production/analysis-query'"/><YieldStandard v-else-if="S.path==='/production/yield-standard'"/><EcnGap v-else-if="S.path==='/production/bom/ecns'&&S.query.get('ws')!=='ydwm'"/><YdwmProduction v-else-if="S.query.get('ws')==='ydwm'"/><WipMatrix v-else-if="S.path==='/production/wip'||S.query.get('view')==='wip'"/><WeighIn v-else-if="S.path==='/production/weigh'||S.query.get('view')==='weigh'"/><section v-else class="card table-panel"><div v-if="kind==='orders'" ref="viewsHost" class="views-host"/><p v-if="S.path==='/production/batches'" class="batch-note">六膳门以黑湖生产工单作生产批次，列表与生产工单相同，来源只读。</p><form class="filters" @submit.prevent="search()"><input aria-label="搜索当前列表" v-model="q" placeholder="编码、名称或来源ID"><select aria-label="筛选产品" v-model="productDraft"><option value="">全部产品</option><option v-for="p in rs('product').filter(p=>p.productCode)" :key="rowId(p)" :value="String(p.productCode)">{{p.productName}} · {{p.productCode}}</option></select><select v-if="['orders','reports'].includes(kind)" aria-label="筛选状态" v-model="statusDraft"><option value="">全部状态</option><option value="0">{{kind==='reports'?'待审批':'新建'}}</option><option value="10">{{kind==='reports'?'已批准':'执行中'}}</option><option value="20">{{kind==='reports'?'未通过':'已结束'}}</option><option v-if="kind!=='reports'" value="30">已取消</option></select><select v-if="!['processes','bom'].includes(kind)" aria-label="筛选单位" v-model="unitDraft"><option value="">全部单位</option><option v-for="u in units" :key="u">{{u}}</option></select><select v-if="kind==='orders'" aria-label="筛选路线对应" v-model="mapDraft"><option value="">全部路线对应</option><option>当前路线可对应</option><option>补充路线可对应</option><option>产品无当前路线</option><option>当前路线不含此工序</option><option>混合</option><option>来源未返回任务</option></select><select v-if="kind==='orders'" aria-label="筛选工期" v-model="schedDraft"><option value="">全部工期</option><option>已超期</option><option>落后</option><option>正常</option><option>未开始</option><option>不能判断</option><option>已完成</option><option>已取消</option></select><span v-if="kind==='orders'||kind==='reports'" ref="sharedHost" class="shared-host"/><UButton type="submit">搜索</UButton><UButton color="neutral" variant="ghost" @click="reset()">重置</UButton><template v-if="kind==='orders'"><UButton color="neutral" variant="outline" title="BOM 投料工序、产品路线、工序标准的缺口按 Excel 批量补充：下载模板 → 预检 → 确认 → 重算" @click="openImport()">批量补充（Excel）</UButton><UButton v-if="SUP.batches.length" color="neutral" variant="ghost" @click="openBatches()">补充记录 {{SUP.batches.filter(b=>!b.revoked).length}}</UButton></template></form>
<div class="section-head"><h2>{{names[kind]}} <small>{{filtered.length}} 条</small></h2><span>来源只读 · 选中一行即成为右下角「解读这张工单」的当前对象 <i class="help" tabindex="0" title="点「详情」查看上下游：产品 → 工单 → 工序任务 → 逐笔报工 → BOM → 领退料。来源记录不可编辑。">?</i></span></div>
<div class="table-scroll"><table><thead><tr><th v-for="[key,name] in columns" :key="key">{{name}}</th><th class="op">操作</th></tr></thead><tbody><tr v-for="r in rows" :key="rowId(r)" :class="{picked:picked===rowId(r)}" @click="pick(r)"><td v-for="[key] in columns" :key="key" :title="key==='loop'?'领：黑湖领退料单（✓ 有记录 / — 无记录）；产：工单良品（✓ 大于 0 / — 无）；入：黑湖未返回完工入库字段（?）。发货属于销售侧，不放在工单上。':text(r[key])"><span v-if="key==='status'||key==='approvalStatus'" class="state" :class="tone(r[key])">{{stateLabel(r[key],key==='approvalStatus')}}</span><span v-else-if="key==='schedule'" class="state" :class="{overdue:'late',behind:'pending',normal:'active',done:'success',cancelled:'muted','not-started':'muted',unknown:'muted'}[(r._progress as {schedule:string}).schedule]" :title="'工单进度：已完成良品 ÷ 计划量；时间进度：按天（计划开始→计划结束）。时间进度比工单进度多 10 个百分点以上为「落后」；过了计划结束仍未完成为「已超期」。'">{{r[key]}}</span><span v-else-if="key==='labor'" :title="labor[rowId(r)]?.reason||'工价规则为 Cretas 补充；黑湖任务累计工资有值时以黑湖为准'">{{labor[rowId(r)]?(labor[rowId(r)].amount===null?(labor[rowId(r)].reason||'—'):fmtMoney(labor[rowId(r)].amount,{currency:'（币种待确认）'})+' · Cretas 补充'):'读取中'}}</span><span v-else-if="key==='endtimeofReport'">{{fmtDate(r[key])}}</span><span v-else>{{['id','taskId','productCode','projectCode','lastProductCode','nextProductCode','feedProcessCode','code','mapping','workPct','timePct','loop'].includes(key)?text(r[key]):cell(key,r[key])}}</span></td><td class="op"><UButton size="xs" color="neutral" variant="ghost" @click.stop="open(r)">详情</UButton><UButton v-if="kind==='orders'" size="xs" color="neutral" variant="ghost" title="打开这张工单的执行画布：工序任务、逐笔报工、标准用料与成本依据" @click.stop="canvas(r)">画布</UButton></td></tr><tr v-if="!rows.length"><td :colspan="columns.length+1" class="empty">{{S.busy?'正在读取来源…':'当前筛选没有记录，不用模拟数据替代。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{rowPage}} / {{maxPage}} 页</span><label>每页 <select aria-label="每页条数" v-model.number="pageSize" @change="rowPage=1;persist()"><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select></label><UButton size="xs" color="neutral" variant="outline" :disabled="rowPage<=1" @click="rowPage--;persist()">上一页</UButton><UButton size="xs" color="neutral" variant="outline" :disabled="rowPage>=maxPage" @click="rowPage++;persist()">下一页</UButton></div></section></template>
<style scoped>.shared-host{display:inline-flex;gap:8px;align-items:center}.shared-host :deep(.cs-batch-pop){left:0;right:auto}.table-scroll td,.table-scroll th{white-space:nowrap}.table-scroll td{max-width:260px;overflow:hidden;text-overflow:ellipsis}.state.late{background:var(--cs-danger-soft,#fdecec);color:var(--cs-danger,#c53b3b)}</style>
