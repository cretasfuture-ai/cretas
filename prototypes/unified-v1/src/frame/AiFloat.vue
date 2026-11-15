<script setup lang="ts">
// 六膳门工作区唯一的 AI 入口：悬浮窗。按跨页 AI 协议（D-home-analytics.md 第一部分）实现，
// 状态标签、降级文案、排队提示、位置记忆全部取共享设计系统 CretasUI.ai（与味美悬浮窗同一套）。
// 动线：当前对象 → 依据（来源事实）→ 结论（解读）→ 预览核对记录 → 确认保存 → 回读。
// 解读走六膳门既有本地模型路径（/api/analyze，经统一入口排队）；保存只写六膳门原型工作区，不改黑湖、不碰味美。
import {computed,onMounted,onBeforeUnmount,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {analysisLines} from '../cost/cost-review'
import type {Analysis,Workspace} from './types'
import {S,rs,rowId,text,costOf,stateLabel,scopeKey,fmtDate,api,setCurrent} from './store'
import {ai,fmtMoney,fmtQty,fmtNumber,cretasUI} from './ai-protocol'
// 结论可能是多段文字（如列表分析的服务端确定性正文）；用共享 renderAnswer 转义后排版，不直接注入模型原文
const renderConclusion=(t:string)=>cretasUI().renderAnswer(String(t||''))
import type {AiState} from './ai-protocol'
import {aiAdapter,loadEAdapters,bindTopAdapters} from './ai-objects'
// embedded：味美原生页面（不在框架里）用一个透明小 iframe 承载同一个 AiFloat；外壳按本组件报告的位置大小摆放 iframe
const props=defineProps<{embedded?:boolean}>()
import type {AiExplain} from './ai-objects'
// 不需要 AI 的页面（规格第 5 项写明「本页不需要 AI」）不显示入口
const NO_AI=['/unified/reports','/quality/standards','/quality/defects']
// 按工作区分键（审计第二轮必修 2）：六膳门与味美的悬浮窗对象、位置互不恢复
const WS=S.query.get('ws')==='ydwm'||/[?&]ws=ydwm(&|$)/.test(location.hash)?'ydwm':'lsm',KEY='unified-ai-v1:'+WS
const question=ref(''),busy=ref(false),err=ref(''),errState=ref<AiState|''>(''),answer=ref<Analysis>(),stage=ref<'ask'|'preview'|'saved'>('ask'),noteDraft=ref(''),statusDraft=ref('MISSING'),saving=ref(false),readback=ref<{note:string;status:string;at:string;readAt:string}|undefined>(),elapsed=ref(0),queueMsg=ref('')
const pos=ref({right:20,bottom:18,width:460})
let timer:number|undefined,poll:number|undefined,controller:AbortController|undefined
const scope=computed(()=>S.current?.scope)
// 各线注册的对象类型（stock/document/inspection/task…）：依据与解读都来自适配器
const adapter=computed(()=>{const k=scope.value?.kind;return k&&k!=='project'&&k!=='product'?aiAdapter(String(k)):undefined})
const ext=ref<AiExplain>(),EXT_KEY='unified-ai-ext-v1:'+WS
function loadExt(){ext.value=undefined;const s=scope.value;if(!s||!adapter.value)return;try{const all=JSON.parse(localStorage.getItem(EXT_KEY)||'{}');ext.value=all[s.kind+':'+s.id]}catch{/* 无缓存 */}}
function saveExt(a:AiExplain){const s=scope.value;if(!s)return;try{const all=JSON.parse(localStorage.getItem(EXT_KEY)||'{}');all[s.kind+':'+s.id]=a;localStorage.setItem(EXT_KEY,JSON.stringify(all))}catch{/* 只影响刷新恢复 */}}
const extState=computed<AiState>(()=>{const a=ext.value,s=scope.value;if(!a||!s)return 'answered';if(adapter.value?.isStale?.(s.id,a))return 'stale';return (['answered','unavailable','rejected','stale','queued'].includes(String(a.state))?a.state:'answered') as AiState})
const launchText=computed(()=>{const s=scope.value;if(!s)return '解读';if(s.kind==='project')return '解读这张工单';if(s.kind==='product')return '解读这个产品';return '解读'+(adapter.value?.noun||'当前对象')})
const facts=computed(()=>{const s=scope.value;if(!s)return [] as [string,string][];if(adapter.value)return adapter.value.facts(s.id).map(f=>[f.label,(f.unit?fmtQty(f.value,f.unit):f.value===null||f.value===undefined||f.value===''?'—（未填写）':String(f.value))+(f.source==='cretas'?' · 含 Cretas 补充':f.source==='missing'?' · 来源未返回':'')] as [string,string]);if(!S.source)return [] as [string,string][];if(s.kind==='project'){const p=rs('project').find(x=>rowId(x)===s.id),c=costOf(s.id);if(!p)return [];return [['工单',text(p.projectCode)],['产品',text(p.productName)+' · '+text(p.productCode)],['状态',stateLabel(p.status)],['工单良品',fmtQty(c?.finishedGood,c?.unit)],['已记录材料费',fmtMoney(c?.materialCost,{currency:'（币种待确认）'})],['已记录人工费',fmtMoney(c?.laborCost,{currency:'（币种待确认）'})],['单位直接成本',c?.unitCostAvailable?fmtNumber(c.unitIdentifiedDirectCost)+' / '+(c?.unit||'单位未填'):'无法计算：'+(c?.issues[0]||'核算条件不足')],['工序任务 / 报工',rs('task').filter(t=>String(t.projectId)===s.id).length+' / '+rs('output').filter(o=>String(o.projectId)===s.id).length+' 条'],['核算条件',c?.issues.length?c.issues.join('；'):'无额外核算阻断']] as [string,string][]}const p=rs('product').find(x=>rowId(x)===s.id);if(!p)return [];return [['产品',text(p.productName)],['编码 / 单位',text(p.productCode)+' · '+text(p.unit)],['关联工单',rs('project').filter(x=>x.productCode===p.productCode).length+' 张'],['BOM 条目',rs('bom').filter(b=>b.lastProductCode===p.productCode).length+' 条'],['工艺路线',text(p.prcessRoutingCode)]] as [string,string][]})
const saved=computed(()=>scope.value?S.workspace?.followups[scopeKey(scope.value)]:undefined)
const history=computed(()=>scope.value?(S.workspace?.analyses||[]).filter(a=>a.scopeKey===scopeKey(scope.value!)&&!a.localWorkflowHash):[])
const answerState=computed<AiState>(()=>answer.value&&answer.value.sourceRevision===S.source?.revision?'answered':'stale')
const badge=(s:AiState,extra?:string)=>ai().badgeHTML(s,extra)
const statusName:Record<string,string>={OPEN:'未处理',MISSING:'待补资料',CHECKED:'已核对'}
function persist(){ai().savePosition(WS,pos.value);try{localStorage.setItem(KEY,JSON.stringify({open:S.aiOpen,scope:scope.value,path:S.path}))}catch{/* 偏好未保存不影响业务 */}}
function syncAnswer(){answer.value=history.value[history.value.length-1];stage.value=readback.value?'saved':'ask';err.value='';errState.value=''}
watch(()=>scope.value&&scopeKey(scope.value),()=>{readback.value=undefined;syncAnswer();loadExt();persist()})
watch(()=>S.aiOpen,persist)
async function askExt(){const s=scope.value,a=adapter.value;if(!s||!a||busy.value)return;if(!a.explain){errState.value='missing';err.value='本对象只提供系统计算的依据，没有解读服务。';return}busy.value=true;err.value='';errState.value='';elapsed.value=0;const t0=Date.now();timer=window.setInterval(()=>{elapsed.value=Math.round((Date.now()-t0)/1000)},1000)
  try{const r=await a.explain(s.id,{question:question.value.trim()});ext.value=r;saveExt(r);question.value='';if(r.state==='unavailable'){errState.value='unavailable';err.value=ai().TEXT.unavailable}}catch(e){errState.value='sourceError';err.value=e instanceof Error?e.message:'解读失败'}finally{busy.value=false;window.clearInterval(timer)}}
async function ask(){if(adapter.value)return askExt();if(!S.source||!scope.value||busy.value||!question.value.trim())return;busy.value=true;err.value='';errState.value='';elapsed.value=0;queueMsg.value='';controller=new AbortController();const t0=Date.now(),ticket=crypto.randomUUID();timer=window.setInterval(()=>{elapsed.value=Math.round((Date.now()-t0)/1000)},1000)
  poll=window.setInterval(async()=>{try{const q=await (await fetch('/__unified/ai-queue?ticket='+ticket)).json();const d=q.data||{};queueMsg.value=d.state==='queued'?ai().queueText({position:d.position,waitedMs:d.waitedMs}):d.waitedMs>=600000?ai().queueText({position:0,waitedMs:d.waitedMs}):''}catch{/* 查询排队位置失败不影响解读 */}},1500)
  try{const r=await fetch('/api/analyze',{method:'POST',signal:controller.signal,headers:{'Content-Type':'application/json','x-prototype-action':'explicit','x-ai-ticket':ticket},body:JSON.stringify({...scope.value,factoryId:'LIUSHANMEN',revision:S.source.revision,question:question.value})});let b:{success?:boolean;message?:string;data?:Analysis;unavailable?:boolean}={};try{b=await r.json()}catch{/* 非 JSON */}
    if(r.status===502||b.unavailable){errState.value='unavailable';throw Error(ai().TEXT.unavailable)}
    if(r.status===403){errState.value='forbidden';throw Error(b.message||ai().STATES.forbidden.label)}
    if(!r.ok||!b.success||!b.data){errState.value='sourceError';throw Error(b.message||ai().sourceErrorText(r.status,'解读接口'))}
    answer.value=b.data;S.workspace?.analyses.push(b.data);question.value='';stage.value='ask';readback.value=undefined}
  catch(e){err.value=e instanceof DOMException&&e.name==='AbortError'?ai().TEXT.stopWaiting:e instanceof Error?e.message:'解读失败'}
  finally{busy.value=false;queueMsg.value='';window.clearInterval(timer);window.clearInterval(poll)}}
function stop(){controller?.abort()}
function openPreview(){if(!answer.value)return;const lines=analysisLines(answer.value.answer).map(l=>l.text).join('；');noteDraft.value=('依据解读（'+fmtDate(answer.value.at)+'）：'+lines).slice(0,1400);statusDraft.value=saved.value?.status||'MISSING';stage.value='preview'}
async function confirmSave(){if(!S.source||!S.workspace||!scope.value)return;saving.value=true;err.value='';try{const s=scope.value;await api<Workspace>('/api/followup',{...s,factoryId:'LIUSHANMEN',revision:S.source.revision,expectedVersion:S.workspace.version,status:statusDraft.value,note:noteDraft.value});const fresh=await api<{workspace:Workspace}>('/api/workspace');S.workspace=fresh.workspace;const f=fresh.workspace.followups[scopeKey(s)];if(!f)throw Error('保存后未能回读到核对记录，请刷新核对');if(f.note!==noteDraft.value||f.status!==statusDraft.value)throw Error(ai().STATES.mismatch.label+'：回读内容与预览不同，请刷新核对');readback.value={note:f.note,status:f.status,at:f.at,readAt:new Date().toISOString()};stage.value='saved'}catch(e){err.value=e instanceof Error?e.message:'保存失败'}finally{saving.value=false}}
// 拖动标题移动；左边缘拖动调宽；标题获得焦点时方向键移动（Shift 加大步长）
let drag:{x:number;y:number;r:number;b:number}|undefined,size:{x:number;w:number;r:number}|undefined
// 嵌入 iframe 时 iframe 跟着移动，用屏幕坐标算位移；视口取外层页面
const px=(e:PointerEvent)=>props.embedded?e.screenX:e.clientX,py=(e:PointerEvent)=>props.embedded?e.screenY:e.clientY
function topView(){try{return {w:window.top!.innerWidth,h:window.top!.innerHeight}}catch{return {w:window.innerWidth,h:window.innerHeight}}}
function clamp(p:{right:number;bottom:number;width:number}){if(!props.embedded)return ai().clampPosition(p);const v=topView(),w=Math.min(Math.max(360,Number(p.width)||460),Math.max(360,Math.round(v.w*.6)));return {width:w,right:Math.max(0,Math.min(v.w-w,Number(p.right)||0)),bottom:Math.max(0,Math.min(v.h-120,Number(p.bottom)||0))}}
function down(e:PointerEvent){if((e.target as HTMLElement).closest('button'))return;drag={x:px(e),y:py(e),r:pos.value.right,b:pos.value.bottom};(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)}
function move(e:PointerEvent){if(!drag)return;pos.value=clamp({...pos.value,right:drag.r-(px(e)-drag.x),bottom:drag.b-(py(e)-drag.y)})}
function up(){if(drag){drag=undefined;persist()}}
function sizeDown(e:PointerEvent){size={x:px(e),w:pos.value.width,r:pos.value.right};(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)}
function sizeMove(e:PointerEvent){if(!size)return;pos.value=clamp({...pos.value,width:size.w-(px(e)-size.x),right:size.r})}
function sizeUp(){if(size){size=undefined;persist()}}
function headKey(e:KeyboardEvent){const step=e.shiftKey?40:10,map:Record<string,[number,number]>={ArrowLeft:[step,0],ArrowRight:[-step,0],ArrowUp:[0,step],ArrowDown:[0,-step]};const d=map[e.key];if(!d)return;e.preventDefault();pos.value=ai().clampPosition({...pos.value,right:pos.value.right+d[0],bottom:pos.value.bottom+d[1]});persist()}
function keyClose(e:KeyboardEvent){if(e.key==='Escape'&&S.aiOpen&&stage.value!=='preview')S.aiOpen=false}
function onResize(){pos.value=clamp(pos.value)}
// 嵌入模式：把当前要占的框（启动按钮或面板）告诉外壳，外壳据此摆放 iframe
watch(()=>[S.aiOpen,pos.value.right,pos.value.bottom,pos.value.width,S.current?.label],()=>{if(!props.embedded)return;const v=topView();try{window.parent.postMessage({source:'lsm-frame',type:'ai-box',open:S.aiOpen,right:pos.value.right,bottom:pos.value.bottom,width:S.aiOpen?pos.value.width:380,height:S.aiOpen?Math.min(680,v.h-40):48},location.origin)}catch{/* 独立打开时无外壳 */}},{immediate:true})
onMounted(()=>{loadEAdapters();if(props.embedded){document.documentElement.classList.add('uni-ai-embedded');bindTopAdapters()}pos.value=props.embedded?clamp(ai().loadPosition(WS)):ai().loadPosition(WS);try{const s=JSON.parse(localStorage.getItem(KEY)||'null');if(s){if(s.pos&&typeof s.pos.right==='number'&&!localStorage.getItem('cretas-ai-float:'+WS))pos.value=ai().clampPosition(s.pos);if(s.scope&&['project','product'].includes(s.scope.kind)){const stopW=watch(()=>S.source?.revision,()=>{if(S.source){if(s.path===S.path){if(!S.current)setCurrent(s.scope,'上次解读');S.aiOpen=s.open===true}syncAnswer();stopW()}},{immediate:true})}}}catch{/* 默认位置 */}window.addEventListener('keydown',keyClose);window.addEventListener('resize',onResize)})
// 业务弹窗（详情、补充导入、发布确认等）打开时收起悬浮窗，关闭后恢复；不叠第二层对话（DESIGN.md 页面与浮层规则）。
// 贴右侧的业务侧栏（如 E 的 .e-drawer，或任何带 data-ai-avoid 的元素）打开时，启动按钮左移让出底部按钮
const avoidRight=ref(0)
function measureAvoid(){let w=0;document.querySelectorAll<HTMLElement>('.e-drawer,[data-ai-avoid]').forEach(el=>{const r=el.getBoundingClientRect();if(r.width&&r.right>=window.innerWidth-4)w=Math.max(w,r.width)});avoidRight.value=w?w+20:0}
let autoCollapsed=false,observer:MutationObserver|undefined
function modalOpen(){return !!document.querySelector('[data-slot="content"][data-state="open"]')}
function watchModals(){measureAvoid();if(modalOpen()){if(S.aiOpen){autoCollapsed=true;S.aiOpen=false}}else if(autoCollapsed){autoCollapsed=false;S.aiOpen=true}}
// 第六批（E 报）：抽屉有时只改 class / style / open 就打开（不增删节点），原来只听 childList 和 data-state 会漏量；再加窗口尺寸变化和每次点击后补量一次
const remeasure=()=>requestAnimationFrame(()=>requestAnimationFrame(watchModals))
onMounted(()=>{observer=new MutationObserver(watchModals);observer.observe(document.body,{subtree:true,childList:true,attributes:true,attributeFilter:['data-state','class','style','open']});window.addEventListener('resize',remeasure);document.addEventListener('click',remeasure,true)})
onBeforeUnmount(()=>{window.removeEventListener('keydown',keyClose);window.removeEventListener('resize',onResize);observer?.disconnect();window.removeEventListener('resize',remeasure);document.removeEventListener('click',remeasure,true);window.clearInterval(timer);window.clearInterval(poll)})
</script>
<template>
<button v-if="!S.aiOpen&&!S.editorOpen&&!NO_AI.includes(S.path)" type="button" class="uni-ai-launcher" :style="avoidRight?{right:avoidRight+'px'}:undefined" :title="S.current?'解读当前对象：'+S.current.label:'先在列表、画布或成本页选中一个产品或工单'" @click="S.aiOpen=true"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l1.8 4.6L18 9l-4.2 1.4L12 15l-1.8-4.6L6 9l4.2-1.4z"/></svg><span>{{launchText}}</span><small>{{S.current?S.current.label:'未选对象'}}</small></button>
<section v-if="S.aiOpen&&!S.editorOpen&&!NO_AI.includes(S.path)" class="uni-ai-panel" role="dialog" aria-label="记录解读" :style="{right:pos.right+'px',bottom:pos.bottom+'px',width:pos.width+'px'}">
<div class="uni-ai-resize" role="separator" aria-orientation="vertical" aria-label="拖动调整宽度" @pointerdown="sizeDown" @pointermove="sizeMove" @pointerup="sizeUp"/>
<header tabindex="0" aria-label="解读窗口标题，方向键移动位置" @pointerdown="down" @pointermove="move" @pointerup="up" @keydown="headKey"><div><strong>记录解读</strong><small>拖动标题移动 · 左边缘调宽 · Esc 收起</small></div><UButton aria-label="收起解读" size="xs" color="neutral" variant="ghost" @click="S.aiOpen=false">×</UButton></header>
<div class="uni-ai-scroll">
<div class="uni-ai-step"><b>1 当前对象</b><p v-if="S.current">{{S.current.label}} <small>来自{{S.current.from}}</small></p><p v-else class="muted">{{ai().TEXT.noObject}}</p></div>
<div v-if="facts.length" class="uni-ai-step"><b>2 依据 <i class="help" tabindex="0" title="系统计算：直接取自黑湖只读来源快照；0 是已记录零值，“—”是缺失，不估填。金额币种来源未确认。">?</i></b><dl class="uni-ai-facts"><template v-for="[k,v] in facts" :key="k"><dt>{{k}}</dt><dd>{{v}}</dd></template></dl></div>
<div v-if="S.current&&adapter" class="uni-ai-step"><b>3 结论</b><template v-if="ext"><span v-html="badge(extState)"/><div class="uni-ai-sys"><small>系统计算</small><div class="cs-ai-answer" v-html="renderConclusion(ext.conclusion)"/></div><ul class="uni-ai-items"><li v-for="(it,i) in ext.items" :key="i"><small>{{it.source==='model'?'解读（已核对）':'系统计算'}}</small> {{it.text}}<em v-if="it.who"> · 找：{{it.who}}</em></li></ul><details v-if="ext.rejected.length" class="cs-ai-unverified"><summary>未核实内容 {{ext.rejected.length}} 段（不作结论）</summary><div v-for="(r,i) in ext.rejected" :key="i" class="cs-ai-unverified-item"><p>{{r.text}}</p><small>{{r.why}}</small></div></details><small>{{fmtDate(ext.at)}}<template v-if="ext.note"> · {{ext.note}}</template></small></template><p v-else class="muted">{{adapter.explain?ai().TEXT.readOnly:'本对象只提供系统计算的依据。'}}</p></div>
<div v-else-if="S.current" class="uni-ai-step"><b>3 结论</b><template v-if="answer"><span v-html="badge(answerState)"/><h4>{{answer.question}}</h4><div class="answer"><template v-for="(line,i) in analysisLines(answer.answer)" :key="i"><h4 v-if="line.heading">{{line.text}}</h4><p v-else>{{line.text}}</p></template></div><small>{{fmtDate(answer.at)}} · 本对象历史 {{history.length}} 条</small></template><p v-else class="muted">{{ai().TEXT.readOnly}}</p></div>
<div v-if="!adapter&&answer&&stage==='ask'" class="uni-ai-step"><b>4 建议动作</b><p class="muted">可把这条结论存为该对象的核对记录（不改黑湖工单、报工、BOM 或成本）。</p><UButton size="xs" color="neutral" variant="outline" @click="openPreview()">预览核对记录</UButton></div>
<div v-if="stage==='preview'" class="uni-ai-step uni-ai-preview"><b>5 预览确认</b><dl class="uni-ai-facts"><dt>对象</dt><dd>{{S.current?.label}}</dd><dt>保存前</dt><dd>{{saved?statusName[saved.status]+'：'+saved.note.slice(0,80):'尚无核对记录'}}</dd></dl><label>保存后状态<select aria-label="核对状态" v-model="statusDraft"><option value="OPEN">未处理</option><option value="MISSING">待补资料</option><option value="CHECKED">已核对</option></select></label><label>核对说明<textarea aria-label="核对说明" v-model="noteDraft" rows="4"/></label><p class="muted">只写六膳门原型工作区（Cretas 补充，不回写黑湖）；“已核对”不会消除原数据的核算阻断。</p><div class="actions"><UButton size="xs" color="neutral" variant="outline" @click="stage='ask'">返回修改</UButton><UButton size="xs" :loading="saving" :disabled="!noteDraft.trim()" @click="confirmSave()">确认保存</UButton></div></div>
<div v-if="stage==='saved'&&readback" class="uni-ai-step uni-ai-readback" role="status"><b>6 回读结果</b><span v-html="badge('draft','核对记录 · 回读一致')"/><p>已从六膳门工作区重新读取：<strong>{{statusName[readback.status]}}</strong></p><p class="uni-ai-note">{{readback.note}}</p><small>保存 {{fmtDate(readback.at)}} · 回读 {{fmtDate(readback.readAt)}}</small></div>
<div v-else-if="!adapter&&saved&&stage==='ask'" class="uni-ai-step"><b>已保存的核对记录</b><span v-html="badge(saved.sourceRevision===S.source?.revision?'draft':'stale','核对记录')"/><p class="uni-ai-note">{{statusName[saved.status]}}：{{saved.note}}</p><small>{{fmtDate(saved.at)}}</small></div>
<p v-if="err" role="alert" class="error"><span v-if="errState" v-html="badge(errState)"/> {{err}}</p>
</div>
<form v-if="S.current&&stage!=='preview'&&(!adapter||adapter.explain)" @submit.prevent="ask()"><textarea aria-label="解读问题" v-model="question" rows="2" :placeholder="adapter?.placeholder||'例如：这张工单哪些成本已记录？还缺什么依据？'" @keydown.ctrl.enter.prevent="ask()"/><div class="actions"><small v-if="busy" role="status">{{queueMsg||'正在核对当前记录…'}} {{elapsed}} 秒</small><small v-else>Ctrl + Enter 发送</small><UButton v-if="busy" size="xs" color="neutral" variant="ghost" @click="stop()">停止等待</UButton><UButton type="submit" size="xs" :loading="busy" :disabled="!adapter&&!question.trim()">{{adapter?'解读':'发送'}}</UButton></div></form>
</section>
</template>
