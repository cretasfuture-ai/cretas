<script setup lang="ts">
// 六膳门 · 工单执行画布（A 线规格「工序画布」「六膳门工单执行」）。
// 节点外观对齐味美工序画布：工序 Cell（徽标、投入物料/产出物料口、单位关系、可报工接口）＋上方附属「标准用料」区（对应味美辅料区）。
// 黑湖来源只读；历史工艺版本未取得时只显示「当前参考」，不画成已确认的历史工艺；BOM 类别未确认时不归入原料/辅料/包材。
import {computed,nextTick,onMounted,ref,shallowRef,watch} from 'vue'
import {VueFlow,Handle,Position,useVueFlow,type Node,type Edge} from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import LocalWorkflow from './LocalWorkflow.vue'
import type {Source,Row,Scope} from '../frame/types'
import {S,setCurrent,navigate} from '../frame/store'
import {mappingOf,mappingLabel} from './mapping'
import {fmtNumber,fmtMoney,sourceTagHTML,confirmReason} from '../frame/ai-protocol'
import {acceptanceSession} from './session'
import {SUP,effectiveSource,loadSupplements,openImport,openBatches} from './supplements'
const props=defineProps<{source:Source;initialOrder?:string}>()
const emit=defineEmits<{analyze:[scope:Scope];inspect:[kind:string,row:Row]}>()
const {fitView,zoomIn,zoomOut}=useVueFlow({id:'liushanmen-production'})
const editing=ref(false);const order=ref('52573303'),chosen=ref(''),tab=ref('reports'),tree=ref(true),fullscreen=ref(false),dirty=ref(false),message=ref(''),filter=ref(''),nodes=shallowRef<Node[]>([]),edges=shallowRef<Edge[]>([]),past=ref<string[]>([])
const pickerOpen=ref(false),pickerQuery=ref(''),toolsOpen=ref(false),folded=ref<Record<string,boolean>>({})
// 第五批 ①：画布按「黑湖来源 + 生效的 Cretas 补充」重算挂接（黑湖有值时以黑湖为准）
const eff=computed(()=>effectiveSource(props.source))
const rs=(k:string)=>eff.value.resources[k]||[]
const project=computed(()=>rs('project').find(p=>String(p.id)===order.value))
const product=computed(()=>rs('product').find(p=>p.productCode===project.value?.productCode))
const route=computed(()=>rs('routing').find(r=>r.processRoutingCode===product.value?.prcessRoutingCode))
const tasks=computed(()=>rs('task').filter(t=>String(t.projectId)===order.value))
const reports=computed(()=>rs('output').filter(t=>String(t.projectId)===order.value))
const boms=computed(()=>rs('bom').filter(b=>b.lastProductCode===project.value?.productCode))
const cost=computed(()=>props.source.costs.find(c=>String(c.projectId)===order.value))
const steps=computed<Row[]>(()=>Array.isArray(route.value?.processRoutingSteps)?route.value!.processRoutingSteps as Row[]:[])
const processCode=(t:Row|undefined)=>rs('process').find(p=>String(p.id)===String(t?.processId))?.code
// BOM 行挂到工序：只有投料工序编码在当前路线里、且本工单恰好一个任务对应时才挂；否则归「待指定工序」
const bomOfTask=(t:Row)=>boms.value.filter(b=>b.feedProcessCode&&b.feedProcessCode===processCode(t)&&steps.value.some(s=>s.code===b.feedProcessCode)&&tasks.value.filter(x=>processCode(x)===b.feedProcessCode).length===1)
const attached=computed(()=>new Set(tasks.value.flatMap(t=>bomOfTask(t).map(b=>String(b.id)))))
const pending=computed(()=>boms.value.filter(b=>!attached.value.has(String(b.id))))
const pendingReason=(b:Row)=>!b.feedProcessCode?'来源未返回投料工序':!steps.value.length?'产品未返回路线':!steps.value.some(s=>s.code===b.feedProcessCode)?'投料工序不在当前路线':'本工单无唯一对应任务'
const selected=computed(()=>nodes.value.find(n=>n.id===chosen.value))
const selectedTask=computed(()=>selected.value?.data.task as Row|undefined)
const visibleReports=computed(()=>selectedTask.value?reports.value.filter(r=>String(r.taskId)===String(selectedTask.value?.id)):reports.value)
const visibleBom=computed(()=>selectedTask.value?bomOfTask(selectedTask.value):boms.value)
const str=(x:unknown)=>x===null||x===undefined||x===''?'未记录':String(x)
// 数量统一用共享格式（T5）：缺失「—」、0 保留「0.00」、格式错「格式无效」
const qty=(x:unknown)=>fmtNumber(x)
const full=(x:unknown)=>x===null||x===undefined||x===''?'未记录':String(x)
const name=(code:unknown)=>String(rs('product').find(p=>p.productCode===code)?.productName||code||'未记录')
const unitOf=(t:Row)=>{const u=[...new Set(reports.value.filter(r=>String(r.taskId)===String(t.id)).map(r=>r.stockUnit).filter(Boolean))];return u.length===1?String(u[0]):u.length?u.join(' / ')+'（单位不一，不合计）':'未记录'}
const mapping=(t:Row)=>mappingLabel(mappingOf(eff.value,t))
// 工单选择：可搜索列表，替代 157 项原生下拉
const orderOptions=computed(()=>{const q=pickerQuery.value.trim().toLowerCase();return rs('project').filter(p=>!q||[p.projectCode,p.productName,p.productCode].some(v=>String(v??'').toLowerCase().includes(q))).slice(0,60)})
const key=()=>`liushanmen-canvas-v2:${order.value}:${props.source.revision}`
const snap=()=>JSON.stringify(nodes.value.map(n=>({id:n.id,position:n.position})))
function apply(raw:string){const p=JSON.parse(raw) as {id:string;position:{x:number;y:number}}[];nodes.value=nodes.value.map(n=>({...n,position:p.find(x=>x.id===n.id)?.position||n.position}))}
function beforeDrag(){past.value.push(snap());if(past.value.length>30)past.value.shift()}
function undo(){const s=past.value.pop();if(s){apply(s);dirty.value=true}}
const place=(i:number)=>({x:40+i*520,y:200})
function build(){
 // 不按来源 seq 补画工序之间的物料流转边：seq 只是记录顺序，不是已证实的流转
 nodes.value=tasks.value.map((t,i)=>({id:'task:'+t.id,type:'default',position:place(i),data:{label:String(t.processName),task:t,kind:'task'}}))
 edges.value=[];chosen.value='';past.value=[];dirty.value=false;message.value=''
 try{const saved=localStorage.getItem(key());if(saved)apply(saved)}catch{message.value='布局恢复失败，已使用默认布局。'}
 nextTick(()=>fitView({padding:.2}))
}
// R21：原生 confirm() 换成共享确认弹层（只问放弃与否，原因可选）；返回 null 表示取消
const discard=async(title:string,message:string,okLabel='放弃改动')=>(await confirmReason({title,message,required:false,okLabel,danger:true}))!==null
async function chooseOrder(id:string){pickerOpen.value=false;if(id===order.value)return;if(dirty.value&&!await discard('切换工单','布局尚未保存，放弃布局改动并切换工单？','放弃并切换'))return;order.value=id;try{localStorage.setItem('liushanmen-canvas-order',id)}catch{/* 偏好未保存不影响业务 */}build();setCurrent({kind:'project',id},'工序画布')}
function layout(){beforeDrag();nodes.value=nodes.value.map((n,i)=>({...n,position:place(i)}));dirty.value=true;nextTick(()=>fitView({padding:.2}))}
function save(){try{localStorage.setItem(key(),snap());dirty.value=false;message.value='布局已保存到本浏览器；来源工艺、BOM和业务记录未修改。'}catch{message.value='保存失败，布局仍未保存。'}}
// 选中工序节点：AI 悬浮窗对象仍是这张工单（scope=project），标签带上任务号（D 建议做法一）
function select(id:string){chosen.value=id;tab.value='reports';const n=nodes.value.find(x=>x.id===id);if(n)fitView({nodes:[n.id],padding:1,maxZoom:1.15});setCurrent({kind:'project',id:order.value},'工序画布');const t=n?.data.task as Row|undefined;if(t&&S.current)S.current={...S.current,label:S.current.label+' · 任务 '+String(t.id)+' '+String(t.processName??'')}}
watch(chosen,v=>{if(!v)setCurrent({kind:'project',id:order.value},'工序画布')})
function showPending(){chosen.value='';tab.value='bom'}
function exportBom(){toolsOpen.value=false;const text=['BOM行ID,投入编码,投入名称,标准量,产出基数,投料工序,挂接状态',...boms.value.map(b=>[b.id,b.nextProductCode,name(b.nextProductCode),b.unitQty,b.parentBaseQty,b.feedProcessCode,attached.value.has(String(b.id))?'已挂到工序':pendingReason(b)].map(x=>'"'+str(x).replaceAll('"','""')+'"').join(','))].join('\n');const url=URL.createObjectURL(new Blob(['﻿'+text],{type:'text/csv;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='六膳门-'+(project.value?.projectCode||order.value)+'-标准用料.csv';a.click();URL.revokeObjectURL(url)}
// BL 17（E 出首检要求与状态）：工序节点上只读显示首检状态；没通过只标红提醒，不阻断报工
const firstChecks=ref<Record<string,{status:string;std:string}>>({})
const FC:Record<string,string>={PENDING:'待首检',DRAFT:'首检草稿',PASS:'首检合格',FAIL:'首检不合格'}
async function loadFirstChecks(){try{const ss=acceptanceSession();const p=new URLSearchParams({ws:'lsm'});if(ss)p.set('acceptanceSession',ss);const b=await (await fetch('/__unified/e/api/first-checks?'+p)).json();const m:Record<string,{status:string;std:string}>={};for(const r of (b?.data?.rows||[]))m[String(r.taskId)]={status:String(r.status),std:String(r.standardName||'')};firstChecks.value=m}catch{firstChecks.value={}}}
onMounted(()=>{loadFirstChecks();loadSupplements()})
function toCost(){navigate('/lsm/cost',{order:order.value})}
function supplement(){editing.value=true}
onMounted(()=>{let last='';try{last=localStorage.getItem('liushanmen-canvas-order')||''}catch{/* 默认 */}const want=props.initialOrder;if(want&&rs('project').some(p=>String(p.id)===want))order.value=want;else if(last&&rs('project').some(p=>String(p.id)===last))order.value=last;build();setCurrent({kind:'project',id:order.value},'工序画布');nextTick(focusQuery)})
watch(()=>props.initialOrder,v=>{if(v&&v!==order.value&&rs('project').some(p=>String(p.id)===v))chooseOrder(v)})
watch(()=>props.source.revision,build)
// 深链：?task=<任务ID> 选中工序节点；?bom=<BOM行ID> 定位到挂接的工序或「待指定工序」；?tab=bom 打开用料页签
function focusQuery(){const task=S.query.get('task'),bom=S.query.get('bom');if(S.query.get('tab')==='bom'){chosen.value='';tab.value='bom'}
 if(task){if(nodes.value.some(n=>n.id==='task:'+task))nextTick(()=>select('task:'+task));else message.value='本工单没有任务 '+task+'，未按名称猜配。'}
 if(bom){const b=boms.value.find(x=>String(x.id)===bom);if(!b){message.value='本工单产品没有 BOM 行 '+bom+'。';return}const t=tasks.value.find(x=>bomOfTask(x).some(y=>String(y.id)===bom));if(t)nextTick(()=>{select('task:'+t.id);tab.value='bom'});else showPending()}}
watch(()=>S.query.toString(),()=>nextTick(focusQuery))
watch(editing,v=>{S.editorOpen=v})
</script>
<template><LocalWorkflow v-if="editing && project" :source="source" :project="project" @close="editing=false"/><section class="lc-workspace" :class="{immersive:fullscreen}" @keydown.esc="fullscreen=false;pickerOpen=false;toolsOpen=false" tabindex="-1">
<header class="lc-heading"><div class="lc-object"><small>工单执行 · 六膳门 · 来源只读</small><div class="lc-picker"><button type="button" class="lc-picker-trigger" aria-haspopup="listbox" :aria-expanded="pickerOpen" @click="pickerOpen=!pickerOpen"><strong>{{project?.projectCode||'选择工单'}}</strong> <span>{{project?.productName}}</span> ▾</button>
<div v-if="pickerOpen" class="lc-picker-panel"><input v-model="pickerQuery" aria-label="搜索工单" placeholder="工单号、产品名称或编码" autofocus><ul role="listbox" aria-label="工单"><li v-for="p in orderOptions" :key="str(p.id)"><button type="button" role="option" :aria-selected="str(p.id)===order" @click="chooseOrder(str(p.id))"><b>{{p.projectCode}}</b> {{p.productName}}</button></li><li v-if="!orderOptions.length" class="lc-empty">没有匹配的工单</li></ul><p>最多显示 60 条，继续输入可缩小范围。</p></div></div></div>
<span v-html="sourceTagHTML('blacklake')"/><span class="lc-version" tabindex="0" title="黑湖未返回这张工单锁定的生产版本。画布节点来自本工单的工序任务；产品路线与 BOM 只作当前参考，不当作历史事实。">生产版本：历史未确认</span>
<button type="button" class="lc-primary" title="把当前参考复制为 Cretas 本地草稿，人工补充单位、投料工序与连线；不回写黑湖，不改历史报工。" @click="supplement">建立本地草稿</button>
<div class="lc-tools"><button type="button" aria-haspopup="menu" :aria-expanded="toolsOpen" @click="toolsOpen=!toolsOpen">工具 ▾</button><div v-if="toolsOpen" class="lc-menu" role="menu"><button role="menuitem" @click="exportBom">导出标准用料（CSV）</button><button role="menuitem" :disabled="!dirty" @click="save();toolsOpen=false">保存布局（仅本浏览器）</button></div></div></header>
<div class="lc-notice"><b>当前参考</b><span>{{route?.processRoutingName||'产品未返回路线'}} · {{tasks.length}} 个工序任务 · {{reports.length}} 条报工 · 待指定用料 {{pending.length}}</span><button v-if="!route||pending.length" type="button" class="lc-link" @click="supplement" title="在 Cretas 本地草稿中人工补充；系统和 AI 不自动生成补充值。">补充</button></div>
<div class="lc-toolbar"><button @click="tree=!tree">{{tree?'收起目录':'展开目录'}}</button><button @click="undo" :disabled="!past.length">撤销</button><button @click="layout">自动布局</button><button @click="fitView({padding:.2})">适应画布</button><button @click="zoomOut()" aria-label="缩小画布">−</button><button @click="zoomIn()" aria-label="放大画布">＋</button><span>{{dirty?'布局有改动（工具 → 保存布局）':'拖动只改布局，不改来源'}}</span><button @click="fullscreen=!fullscreen">{{fullscreen?'退出全屏':'全屏'}}</button></div>
<p v-if="message" role="status" class="lc-feedback">{{message}}</p>
<div class="lc-body"><aside v-if="tree" class="lc-tree" aria-label="工艺目录"><input v-model="filter" placeholder="查找工序、用料" aria-label="搜索工艺目录"><details open><summary>已记录工序 {{tasks.length}}</summary><button v-for="t in tasks.filter(t=>String(t.processName).includes(filter))" :key="str(t.id)" :class="{active:chosen==='task:'+t.id}" @click="select('task:'+t.id)">序 · {{t.processName}}<small>任务 {{t.id}} · {{mapping(t)}}</small></button><p v-if="!tasks.length" class="lc-empty">来源未返回工序任务</p></details><details open><summary>标准用料 {{boms.length}}</summary><button v-for="b in boms.filter(b=>name(b.nextProductCode).includes(filter))" :key="str(b.id)" @click="attached.has(str(b.id))?select('task:'+tasks.find(t=>bomOfTask(t).some(x=>x.id===b.id))?.id):showPending()">料 · {{name(b.nextProductCode)}}<small>{{attached.has(str(b.id))?'已挂到工序':pendingReason(b)}}</small></button></details><button class="lc-pending" @click="showPending">待指定工序 {{pending.length}}</button><button @click="chosen='';tab='cost'">成本依据</button></aside>
<div class="lc-canvas"><VueFlow id="liushanmen-production" v-model:nodes="nodes" v-model:edges="edges" :min-zoom=".15" :max-zoom="2" :nodes-connectable="false" @node-drag-start="beforeDrag" @node-drag-stop="dirty=true" @node-click="({node})=>select(node.id)" @pane-click="chosen=''">
<template #node-default="{data}"><Handle type="target" :position="Position.Left"/><Handle type="source" :position="Position.Right"/><div class="lc-stack">
<div class="lc-aux" :class="{folded:folded[String(data.task.id)]}"><div class="lc-aux-head"><span class="lc-badge aux">料</span><strong>标准用料 · {{bomOfTask(data.task).length}} 种</strong><small>当前参考 · 类别未确认</small><button type="button" class="nodrag" @click.stop="folded[String(data.task.id)]=!folded[String(data.task.id)]">{{folded[String(data.task.id)]?'展开':'收起'}}</button></div><template v-if="!folded[String(data.task.id)]"><div v-for="b in bomOfTask(data.task)" :key="str(b.id)" class="lc-aux-row"><span>{{name(b.nextProductCode)}}</span><b :title="'标准量 '+full(b.unitQty)+' / 产出基数 '+full(b.parentBaseQty)">{{qty(b.unitQty)}} / {{qty(b.parentBaseQty)}}</b></div><p v-if="!bomOfTask(data.task).length" class="lc-empty">没有可唯一挂到本工序的 BOM 行</p></template></div>
<div class="lc-cell"><div class="lc-cell-head"><span class="lc-badge">序</span><div><small>工序 Cell · 任务 {{data.task.id}}</small><strong>{{data.label}}</strong></div></div>
<span v-if="firstChecks[String(data.task.id)]" class="lc-fc" :class="'fc-'+firstChecks[String(data.task.id)].status" :title="'首检要求来自检验标准「'+firstChecks[String(data.task.id)].std+'」（Cretas 补充）；没通过只提醒，不阻断报工'">{{FC[firstChecks[String(data.task.id)].status]||firstChecks[String(data.task.id)].status}}</span><span class="lc-chip">可报工接口 · {{reports.filter(r=>String(r.taskId)===String(data.task.id)).length}} 条报工 · {{mapping(data.task)}}</span>
<div class="lc-port"><b>投入物料</b><p>历史投料关联未确认</p></div><div class="lc-port"><b>产出物料</b><p>未取得历史工艺版本</p></div>
<div class="lc-relation"><b>单位关系</b><span>报工单位：{{unitOf(data.task)}}</span></div>
<div class="lc-foot"><span>已批准良品 {{qty(data.task.approvedFineAmount)}}</span><span>人工 {{qty(data.task.sumOutputSalary)}}</span></div></div></div></template>
</VueFlow><div class="lc-legend">拖动节点只改布局 · 滚轮缩放 · 空白处拖动平移<br>工序之间不补画连线：来源顺序不等于已证实的物料流转。</div></div>
<section class="lc-detail"><div class="lc-detail-head"><strong>{{selected?.data.label||'工单依据'}}</strong><button @click="chosen=''" v-if="chosen" aria-label="返回工单依据">×</button></div><nav role="tablist"><button role="tab" :aria-selected="tab==='reports'" :class="{active:tab==='reports'}" @click="tab='reports'">报工</button><button role="tab" :aria-selected="tab==='bom'" :class="{active:tab==='bom'}" @click="tab='bom'">用料</button><button role="tab" :aria-selected="tab==='cost'" :class="{active:tab==='cost'}" @click="tab='cost'">成本依据</button></nav>
<template v-if="tab==='reports'"><p>逐笔报工，不叠加工单良品。任务状态与审批状态分别核对；审批在黑湖办理。</p><table><thead><tr><th>记录 / 工序</th><th>良品 / 审批</th><th></th></tr></thead><tbody><tr v-for="r in visibleReports" :key="str(r.id)"><td>{{r.id}}<small>{{rs('process').find(p=>String(p.id)===String(r.processId))?.name}}</small></td><td>{{qty(r.numberofgoodProducts)}} {{r.stockUnit}}<small>{{String(r.approvalStatus)==='10'?'已批准':String(r.approvalStatus)==='0'?'待审批':String(r.approvalStatus)==='20'?'未通过':str(r.approvalStatus)}}</small></td><td><button @click="emit('inspect','output',r)">详情</button></td></tr></tbody></table><p v-if="!visibleReports.length" class="lc-empty">当前对象没有报工记录。</p></template>
<template v-if="tab==='bom'"><p>{{selectedTask?'本工序挂接的标准用料（当前参考，非历史配方）。':'本工单产品的全部标准用料。'}}单位与类别未确认时不转换。</p><table><thead><tr><th>用料 / 源行</th><th>标准量 / 基数</th><th></th></tr></thead><tbody><tr v-for="b in visibleBom" :key="str(b.id)"><td>{{name(b.nextProductCode)}}<small>源行 {{b.id}}</small></td><td :title="full(b.unitQty)+' / '+full(b.parentBaseQty)">{{qty(b.unitQty)}} / {{qty(b.parentBaseQty)}}<small>{{attached.has(str(b.id))?'已挂到工序 '+b.feedProcessCode+(b._feedSource?'（Cretas 补充）':''):pendingReason(b)}}</small></td><td><button @click="emit('inspect','bom',b)">依据</button></td></tr></tbody></table>
<div v-if="!selectedTask&&pending.length" class="lc-gap"><b>待指定工序 {{pending.length}} 条</b><p>来源没有给出可唯一对应的投料工序，未自动挂到首道工序。</p><button type="button" class="lc-primary" @click="supplement">在本地草稿中补充</button><button type="button" title="下载已列出缺口的模板，填写后预检、确认，画布按补充重算" @click="openImport()">Excel 批量补充</button><button v-if="SUP.batches.length" type="button" @click="openBatches()">补充记录</button><small>补充值标为「Cretas 补充」，黑湖有值时以黑湖为准；不回写黑湖。</small></div></template>
<template v-if="tab==='cost'"><p>工单良品 {{qty(cost?.finishedGood)}} {{cost?.unit||''}}</p><dl><dt>已记录材料</dt><dd>{{fmtMoney(cost?.materialCost,{currency:'（币种待确认）'})}}</dd><dt>已记录人工</dt><dd>{{fmtMoney(cost?.laborCost,{currency:'（币种待确认）'})}}</dd><dt>单位直接成本</dt><dd>{{cost?.unitCostAvailable?fmtMoney(cost.unitIdentifiedDirectCost,{currency:'（币种待确认）'})+' / '+(cost.unit||'单位未填'):'暂不计算'}}</dd></dl><p>币种待确认；材料未分摊到工序，不按 BOM 估算实耗。</p><div class="lc-row-actions"><button @click="emit('inspect','project',project!)">查看实际领退料</button><button class="lc-primary" @click="toCost">看成本</button></div><ul><li v-for="issue in cost?.issues" :key="issue">{{issue}}</li></ul></template>
</section></div></section></template>
<style scoped>
.lc-workspace{background:var(--cs-surface,#fff);border:1px solid var(--cs-border,#dce3eb);border-radius:10px;margin-bottom:18px;overflow:visible}.lc-workspace.immersive{position:fixed;inset:0;z-index:40;border-radius:0}
.lc-heading,.lc-toolbar{display:flex;align-items:center;gap:8px;padding:10px 16px;border-bottom:1px solid #e6ebf0}.lc-object{flex:1;min-width:0}.lc-object small{display:block;color:#748297;font-size:12px}
.lc-picker{position:relative}.lc-picker-trigger{border:0!important;padding:2px 0!important;background:transparent!important;font-size:16px!important}.lc-picker-trigger span{font-size:13px;color:#59677a}
.lc-picker-panel{position:absolute;z-index:60;top:34px;left:0;width:420px;background:#fff;border:1px solid #d6dee7;border-radius:12px;box-shadow:0 12px 32px #12345626;padding:10px}.lc-picker-panel input{width:100%;box-sizing:border-box;border:1px solid #d6dee7;border-radius:6px;padding:6px 10px}.lc-picker-panel ul{list-style:none;margin:8px 0 0;padding:0;max-height:320px;overflow:auto}.lc-picker-panel li button{display:block;width:100%;text-align:left;border:0!important;height:32px;padding:0 8px!important;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.lc-picker-panel li button[aria-selected=true]{background:#edf4fb!important;color:#1b65a8}.lc-picker-panel p{font-size:12px;color:#798596;margin:6px 0 0}
.lc-version{font-size:12px;padding:4px 10px;border-radius:20px;background:#fff8ea;color:#785922;border:1px solid #f0dcb4}
.lc-tools{position:relative}.lc-menu{position:absolute;right:0;top:36px;z-index:60;background:#fff;border:1px solid #d6dee7;border-radius:10px;box-shadow:0 12px 32px #12345626;padding:6px;min-width:200px}.lc-menu button{display:block;width:100%;text-align:left;border:0!important}
.lc-workspace button,.lc-tree input{border:1px solid #d6dee7;border-radius:6px;background:#fff;padding:5px 10px;font-size:13px;cursor:pointer}.lc-workspace button:hover{background:#edf4fb}.lc-workspace button:disabled{opacity:.45;cursor:default}.lc-workspace .lc-primary{background:var(--cs-primary,#1b65a8);border-color:var(--cs-primary,#1b65a8);color:#fff}.lc-workspace .lc-primary:hover{background:#155a96}
.lc-toolbar span{font-size:12px;color:#758296;flex:1}.lc-notice{padding:8px 16px;background:#f6f9fc;color:#4a5a6e;font-size:12px;display:flex;gap:12px;align-items:center;border-bottom:1px solid #e6ebf0}.lc-link{border:0!important;background:transparent!important;color:var(--cs-primary,#1b65a8);padding:0!important;text-decoration:underline}
.lc-body{display:flex;height:640px;min-height:400px}.immersive .lc-body{height:calc(100vh - 150px)}
.lc-tree{width:210px;flex-shrink:0;padding:12px;border-right:1px solid #e0e6ed;overflow:auto}.lc-tree input{width:100%;box-sizing:border-box;margin-bottom:10px}.lc-tree button{display:block;width:100%;text-align:left;margin:2px 0;border:0;font-size:13px}.lc-tree summary{font-size:13px;padding:8px 0;font-weight:600}.lc-tree .lc-pending{color:#9a5b00}.lc-tree small,.lc-detail small{display:block;color:#798596;font-size:11px}
.lc-canvas{flex:1;position:relative;min-width:200px;background-color:#f6f8fb;background-image:radial-gradient(#dce3eb 1px,transparent 1px);background-size:18px 18px}.lc-legend{position:absolute;bottom:12px;left:12px;pointer-events:none;font-size:11px;color:#69788b;background:#ffffffdc;padding:8px;border-radius:6px}
.lc-canvas :deep(.vue-flow__node){width:auto;background:transparent;padding:0;border:0;box-shadow:none}.lc-canvas :deep(.vue-flow__node.selected .lc-cell){border-color:#1b65a8;box-shadow:0 0 0 3px #1b65a826}
.lc-stack{display:flex;flex-direction:column;gap:10px;width:420px;text-align:left}
.lc-aux{background:#fffaf0;border:1px solid #f1d9a6;border-radius:10px;padding:10px 12px;font-size:12px}.lc-aux-head{display:flex;align-items:center;gap:8px}.lc-aux-head strong{font-size:13px;color:#5e4510}.lc-aux-head small{flex:1;color:#9a7a3c}.lc-aux-head button{padding:2px 8px!important;font-size:12px!important}.lc-aux-row{display:flex;justify-content:space-between;gap:8px;margin-top:6px;padding:6px 8px;background:#fff;border:1px solid #f3e3c0;border-radius:6px}.lc-aux-row b{font-variant-numeric:tabular-nums;font-weight:500}
.lc-cell{background:#fff;border:1px solid #b9d8f4;border-left:4px solid #409eff;border-radius:10px;padding:12px 14px;display:flex;flex-direction:column;gap:8px;box-shadow:0 2px 12px #1b65a817}.lc-cell-head{display:flex;gap:10px;align-items:center}.lc-cell-head small{display:block;font-size:11px;color:#748297}.lc-cell-head strong{font-size:15px;color:#26374d}
.lc-badge{display:inline-grid;place-items:center;width:26px;height:26px;border-radius:6px;background:#eaf3ff;color:#1b65a8;font-size:13px;font-weight:600;flex-shrink:0}.lc-badge.aux{width:22px;height:22px;background:#fdecc8;color:#8a5a00}
.lc-fc{width:fit-content;font-size:11px;border-radius:20px;padding:3px 8px;background:#f0f2f5;color:#6b7889}.lc-fc.fc-PASS{background:var(--cs-success-soft,#eaf6ee);color:var(--cs-success,#2f7d4a)}.lc-fc.fc-FAIL,.lc-fc.fc-PENDING{background:var(--cs-danger-soft,#fdecec);color:var(--cs-danger,#c53b3b)}.lc-chip{width:fit-content;font-size:11px;background:#eef6ff;color:#1b65a8;border-radius:20px;padding:3px 8px}
.lc-port b,.lc-relation b{display:block;font-size:12px;color:#26374d;margin-bottom:4px}.lc-port p{margin:0;font-size:12px;color:#718198;border:1px solid #dce8f3;border-radius:7px;background:#f8fbff;padding:6px 8px}.lc-relation{background:#f6f9fc;border-radius:7px;padding:6px 8px;font-size:12px;color:#4a5a6e}
.lc-foot{display:flex;justify-content:space-between;font-size:12px;color:#627086;border-top:1px dashed #e0e6ed;padding-top:6px;font-variant-numeric:tabular-nums}
.lc-detail{width:340px;flex-shrink:0;border-left:1px solid #e0e6ed;padding:14px;overflow:auto}.lc-detail-head{display:flex;justify-content:space-between;align-items:center;font-size:14px}.lc-detail nav{display:flex;gap:8px;margin:12px 0}.lc-workspace .active{background:#edf4fb;color:#1b65a8}.lc-detail p{font-size:12px;line-height:1.7;color:#667589}.lc-detail table{width:100%;font-size:12px;border-collapse:collapse}.lc-detail td,.lc-detail th{height:32px;padding:4px;border-bottom:1px solid #e6ebf0;text-align:left;font-variant-numeric:tabular-nums}.lc-detail dl{display:grid;grid-template-columns:1fr auto;font-size:13px;gap:10px}.lc-detail dd{margin:0;font-variant-numeric:tabular-nums}
.lc-gap{margin-top:12px;padding:10px;border:1px solid #f1d9a6;background:#fffaf0;border-radius:8px;display:flex;flex-direction:column;gap:6px}.lc-gap p{margin:0}.lc-row-actions{display:flex;gap:8px;margin:8px 0}.lc-empty{color:#8a96a6;font-size:12px}
.lc-feedback{margin:0;padding:8px 16px;background:#eef7ef;font-size:12px}@media(max-width:1100px){.lc-tree{width:160px}.lc-detail{width:280px}.lc-picker-panel{width:320px}}
</style>
