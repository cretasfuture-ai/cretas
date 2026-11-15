<script setup lang="ts">
// B7 不良分析：不良品项字典（Cretas 补充，质量人员维护）+ 工序不良分布（只用报工的良品数、不良数，代码计算）。
// 不做按人员排名（BL-borrow 第 7 条）；报工未采集不良项时如实显示「未采集」，不显示 0%。
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {E,get,write,fmtTime,routeQuery,setQuery,tag,qty,logHTML} from '../collab/e-store'
import {safeRatio,openFormula} from '../frame/ai-protocol'
import type {Defect} from '../collab/e-store'
const st=computed(()=>E.state!)
interface Row{processId:string;processCode:string;processName:string;unit:string;reports:number;good:number;defective:number;withDefect:number;rate:number|null}
interface Stats{available:boolean;reason?:string;source?:string;revision?:string;used?:number;skipped?:number;rows:Row[];defectReports?:{id:string;processId:string;unit:string;defective:number}[];totals:{unit:string;reports:number;good:number;defective:number;rate:number|null}[];itemsCollected?:boolean;itemsNote?:string}
const rq=routeQuery()
const from=ref(rq.get('from')||''),to=ref(rq.get('to')||''),onlyBad=ref(rq.get('bad')==='1')
const stats=ref<Stats>(),statsErr=ref(''),loadingStats=ref(false)
const procs=ref<{id:string;code:string;name:string}[]|null>(null)
async function loadStats(){loadingStats.value=true;statsErr.value='';try{const q=new URLSearchParams();if(from.value)q.set('from',from.value);if(to.value)q.set('to',to.value);stats.value=await get<Stats>('defect-stats?'+q.toString())}catch(e){statsErr.value=e instanceof Error?e.message:'读取失败'}finally{loadingStats.value=false}}
watch([from,to,onlyBad],()=>{setQuery({from:from.value,to:to.value,bad:onlyBad.value?'1':''});loadStats()})
const rows=computed(()=>(stats.value?.rows||[]).filter(r=>!onlyBad.value||r.defective>0))
const rateText=(r:{rate:number|null;defective:number;good:number})=>r.rate==null?'无法计算：报工数量为 0':r.rate.toFixed(2)+'%'+(r.defective===0?'（报工不良数记为 0）':'')
// 不良率口径弹层（D 共享件 B3）
function showRate(ev:MouseEvent,r:{rate:number|null;defective:number;good:number;unit:string},title:string){
  try{openFormula(ev.currentTarget as HTMLElement,{title:title+' · 不良率',result:{value:r.rate==null?null:Number(r.rate.toFixed(2)),unit:'%'},plus:[{label:'不良数',value:r.defective,unit:r.unit,source:'blacklake'},{label:'良品数',value:r.good,unit:r.unit,source:'blacklake'}],formula:'不良率 = 不良数 ÷（良品数 + 不良数）× 100%',note:r.rate==null?'良品数与不良数都为 0，无法计算。':r.defective===0?'报工不良数记为 0；是否实际采集不良，黑湖未说明。':'同一单位内计算，不同单位不相加。'})}catch{/* 共享脚本未加载时只显示文字 */}
}
const procName=(c:string)=>procs.value?.find(p=>p.code===c)?.name||c

// ---- 按不良项的分布：读 A 线补录（GET /__unified/a/api/defect-captures），只算未撤销、且属于当前日期范围内含不良报工的记录 ----
interface Capture{id?:string;reportId:string;process:{id:string;code:string;name:string};unit:string;sourceDefective:number;items:{defectId:string;code:string;name:string;category:string;qty:number}[];total:number;unallocated:number;at:string;by:string;revoked:unknown}
const captures=ref<Capture[]>(),capErr=ref('')
async function loadCaptures(){capErr.value='';try{const q=new URLSearchParams({ws:E.ws});if(E.session)q.set('acceptanceSession',E.session);const r=await fetch('/__unified/a/api/defect-captures?'+q.toString(),{cache:'no-store'});const b=await r.json().catch(()=>({}));if(!r.ok||!b.success)throw Error((b.message||'')+'（'+r.status+'）');captures.value=(b.data?.captures||[]) as Capture[]}catch(e){captures.value=undefined;capErr.value='A 线不良项补录接口未接通：'+(e instanceof Error?e.message:'')}}
const inRange=computed(()=>new Map((stats.value?.defectReports||[]).map(r=>[r.id,r])))
const liveCaps=computed(()=>(captures.value||[]).filter(c=>c.revoked==null&&inRange.value.has(String(c.reportId))))
const itemRows=computed(()=>{
  const m=new Map<string,{process:string;processId:string;code:string;name:string;category:string;unit:string;qty:number}>()
  for(const c of liveCaps.value)for(const it of c.items){const k=c.process.id+'|'+c.unit+'|'+it.code;const x=m.get(k)||{process:c.process.name,processId:c.process.id,code:it.code,name:it.name,category:it.category,unit:c.unit,qty:0};x.qty+=Number(it.qty)||0;m.set(k,x)}
  const out:{process:string;code:string;name:string;category:string;unit:string;qty:number;share:string;unalloc?:boolean}[]=[]
  const procRows=(stats.value?.rows||[]).filter(r=>r.defective>0)
  for(const pr of procRows){
    const items=[...m.values()].filter(x=>x.processId===pr.processId&&x.unit===pr.unit).sort((a,b)=>b.qty-a.qty)
    if(!items.length)continue
    const share=(v:number)=>{const r=safeRatio(v,pr.defective,'本工序黑湖不良数为 0');return r.value==null?'无法计算':(r.value*100).toFixed(2)+'%'}
    for(const it of items)out.push({process:pr.processName,code:it.code,name:it.name,category:it.category||'—',unit:pr.unit,qty:it.qty,share:share(it.qty)})
    const left=+(pr.defective-items.reduce((a,b)=>a+b.qty,0)).toFixed(6)
    if(left>0)out.push({process:pr.processName,code:'—',name:'未分项（未补录或补录不足）',category:'—',unit:pr.unit,qty:left,share:share(left),unalloc:true})
  }
  return out
})
const coverage=computed(()=>{const total=(stats.value?.defectReports||[]).length,done=new Set(liveCaps.value.map(c=>String(c.reportId))).size;return {total,done}})

// ---- 字典 ----
const showOff=ref(false)
const dict=computed(()=>st.value.defects.filter(d=>showOff.value||d.active))
const open=ref(false),editing=ref<Defect>(),phase=ref<'edit'|'preview'|'toggle'>('edit'),err=ref(''),ok=ref(''),saving=ref(false)
const f=reactive({code:'',name:'',category:'',note:'',processes:[] as string[],reason:'',toggleReason:''})
const CATS=['异物','重量','外观','包装标签','温度','感官','其他']
function openNew(){editing.value=undefined;Object.assign(f,{code:'',name:'',category:'',note:'',processes:[],reason:'',toggleReason:''});phase.value='edit';err.value='';ok.value='';open.value=true;E.drawerOpen=true}
function openOne(d:Defect){editing.value=d;Object.assign(f,{code:d.code,name:d.name,category:d.category,note:d.note,processes:[...d.processes],reason:'',toggleReason:''});phase.value='edit';err.value='';ok.value='';open.value=true;E.drawerOpen=true}
function close(){open.value=false;E.drawerOpen=false}
const invalid=computed(()=>!f.code.trim()?'不良项编码不能为空':!f.name.trim()?'不良项名称不能为空':st.value.defects.some(d=>d.code===f.code.trim()&&d.id!==editing.value?.id)?'不良项编码已存在':editing.value&&!f.reason.trim()?'修改不良项必须填写原因':'')
const desc=(x:{code:string;name:string;category:string;processes:string[]})=>x.code+' '+x.name+'｜'+(x.category||'—')+'｜'+(x.processes.map(procName).join('、')||'全部工序')
async function save(){err.value='';ok.value='';saving.value=true;try{const r=await write('defects/save',{id:editing.value?.id,code:f.code,name:f.name,category:f.category,note:f.note,processes:f.processes,reason:f.reason});const back=st.value.defects.find(d=>d.id===r.id);if(!back)throw Error('已提交，回读不一致');openOne(back);ok.value='已保存 · 回读一致：'+desc(back)}catch(e){err.value=e instanceof Error?e.message:'保存失败';phase.value='edit'}finally{saving.value=false}}
async function toggle(){err.value='';if(!f.toggleReason.trim()){err.value=(editing.value!.active?'停用':'启用')+'必须填写原因';return}saving.value=true;try{const r=await write('defects/toggle',{id:editing.value!.id,active:!editing.value!.active,reason:f.toggleReason});const back=st.value.defects.find(d=>d.id===r.id)!;openOne(back);ok.value='已'+(back.active?'启用':'停用')+' · 回读一致'}catch(e){err.value=e instanceof Error?e.message:'操作失败'}finally{saving.value=false}}
onMounted(async()=>{loadStats();loadCaptures();try{procs.value=(await get<{list:{id:string;code:string;name:string}[]|null}>('processes')).list}catch{procs.value=null}})
onBeforeUnmount(()=>{E.drawerOpen=false})
</script>
<template>
<section class="card">
  <div class="section-head"><h2>工序不良分布 <small>按工序汇总报工的良品数与不良数；不同单位分开，不相加</small></h2><span v-if="stats?.available" v-html="tag(E.ws==='lsm'?'blacklake':'ydwm')"/></div>
  <div class="filters"><label class="e-switch">报工日期<input v-model="from" type="date" aria-label="开始日期" style="min-width:0;flex:none"></label><span class="muted">至</span><input v-model="to" type="date" aria-label="结束日期" style="min-width:0;flex:none"><label class="e-switch"><input v-model="onlyBad" type="checkbox">只看有不良的工序</label><UButton v-if="from||to" size="xs" color="neutral" variant="ghost" @click="from='';to=''">清除日期</UButton></div>
  <p v-if="statsErr" class="error" style="margin:0 16px 12px">{{statsErr}}</p>
  <p v-else-if="loadingStats&&!stats" class="empty">正在读取报工…</p>
  <div v-else-if="stats&&!stats.available" class="e-empty"><p>{{stats.reason}}</p><p class="muted">没有报工不良数据时不显示不良率；需要 A 线报工采集不良数后才有分布。</p></div>
  <template v-else-if="stats">
    <div class="table-scroll"><table><thead><tr><th>单位</th><th>报工条数</th><th>良品数</th><th>不良数</th><th>不良率 <i class="help" tabindex="0" title="不良率 = 不良数 ÷（良品数 + 不良数），同一单位内计算；报工数为 0 时无法计算。">?</i></th></tr></thead>
      <tbody><tr v-for="t in stats.totals" :key="t.unit"><td>{{t.unit||'单位未填'}}</td><td>{{t.reports}}</td><td>{{qty(t.good,t.unit)}}</td><td>{{qty(t.defective,t.unit)}}</td><td><button type="button" class="e-link" @click="showRate($event,t,t.unit||'单位未填')">{{rateText(t)}}</button></td></tr></tbody></table></div>
    <div v-if="!rows.length" class="e-empty"><p>当前日期范围内没有{{onlyBad?'记录了不良的':''}}报工。</p></div>
    <div v-else class="table-scroll"><table><thead><tr><th>工序</th><th>单位</th><th>报工条数</th><th>含不良的报工</th><th>良品数</th><th>不良数</th><th>不良率</th></tr></thead>
      <tbody><tr v-for="r in rows" :key="r.processId+r.unit"><td :title="r.processCode">{{r.processName}}</td><td>{{r.unit||'单位未填'}}</td><td>{{r.reports}}</td><td>{{r.withDefect}}</td><td>{{qty(r.good,r.unit)}}</td><td :class="{'e-over':r.defective>0}">{{qty(r.defective,r.unit)}}</td><td><button type="button" class="e-link" @click="showRate($event,r,r.processName)">{{rateText(r)}}</button></td></tr></tbody></table></div>
    <div class="section-head" style="border-top:1px solid var(--cs-border)"><h2>按不良项 <small>工序 × 不良项；数量来自 A 线报工补录，合计以黑湖不良数为准</small></h2><span v-html="tag('cretas')"/></div>
    <p v-if="capErr" class="source-gap" style="margin:12px 16px">{{capErr}}。按不良项的分布暂不可用；上方工序汇总不受影响。</p>
    <p v-else-if="!coverage.total" class="e-empty">当前日期范围内没有记录了不良的报工，无需补录不良项。</p>
    <p v-else-if="!liveCaps.length" class="source-gap" style="margin:12px 16px">按不良项的分布：<b>未采集</b>。含不良的报工 {{coverage.total}} 条，已补录不良项 0 条。{{stats.itemsNote}} 负责：A 线（报工详情「补录不良项」）。</p>
    <template v-else>
      <p class="e-stage-hint" style="padding-bottom:8px">含不良的报工 {{coverage.total}} 条，已补录不良项 {{coverage.done}} 条{{coverage.done<coverage.total?'；其余报工的不良数计入「未分项」':''}}。</p>
      <div class="table-scroll"><table><thead><tr><th>工序</th><th>不良项</th><th>类别</th><th>数量</th><th>占本工序不良 <i class="help" tabindex="0" title="不良项数量 ÷ 该工序在日期范围内的黑湖不良数。未补录的部分单列为「未分项」，不估算。">?</i></th></tr></thead>
        <tbody><tr v-for="(r,i) in itemRows" :key="i" :class="{muted:r.unalloc}"><td>{{r.process}}</td><td>{{r.code==='—'?r.name:r.code+' '+r.name}}</td><td>{{r.category}}</td><td>{{qty(r.qty,r.unit)}}</td><td>{{r.share}}</td></tr></tbody></table></div>
    </template>
    <div class="pagination"><span>{{stats.source}} · 纳入 {{stats.used}} 条报工{{stats.skipped?' · 数量缺失未纳入 '+stats.skipped+' 条':''}}</span><span class="muted">不按人员排名</span></div>
  </template>
</section>

<section class="card">
  <div class="section-head"><h2>不良品项字典 <small>报工时按这里的不良项采集（A 线）；字典值由质量人员填写</small></h2><div class="actions"><span v-html="tag('cretas')"/><UButton size="sm" @click="openNew()">新建不良项</UButton></div></div>
  <div class="filters"><label class="e-switch"><input v-model="showOff" type="checkbox">显示已停用</label></div>
  <div v-if="!dict.length" class="e-empty"><p>还没有不良品项。常见如金检不良、X 光检验不良、净含量不足、封口不良、标签错误。</p><UButton size="sm" @click="openNew()">新建不良项</UButton></div>
  <div v-else class="table-scroll"><table><thead><tr><th>编码</th><th>名称</th><th>类别</th><th>适用工序</th><th>状态</th><th>来源</th><th>最后修改</th><th class="op">操作</th></tr></thead>
    <tbody><tr v-for="d in dict" :key="d.id"><td>{{d.code}}</td><td>{{d.name}}</td><td>{{d.category||'—'}}</td><td :title="d.processes.map(procName).join('、')">{{d.processes.length?d.processes.map(procName).join('、'):'全部工序'}}</td><td><span class="state" :class="d.active?'success':'muted'">{{d.active?'启用':'已停用'}}</span></td><td><span v-html="tag('cretas')"/></td><td>{{fmtTime(d.updatedAt)}}</td><td class="op"><UButton size="xs" color="neutral" variant="outline" @click="openOne(d)">编辑</UButton></td></tr></tbody></table></div>
</section>

<aside v-if="open" class="e-drawer" role="dialog" aria-modal="false" :aria-label="editing?'编辑不良项':'新建不良项'" @keydown.esc="close()">
  <header><div><h2>{{editing?editing.code+' '+editing.name:'新建不良项'}} <span v-if="editing" class="state" :class="editing.active?'success':'muted'">{{editing.active?'启用':'已停用'}}</span></h2><p>不良品项字典 · <span v-html="tag('cretas')"/></p></div><UButton aria-label="关闭" size="xs" color="neutral" variant="ghost" @click="close()">×</UButton></header>
  <div class="e-body">
    <div class="e-sec"><div class="e-grid">
      <label><span class="e-req">编码</span><input v-model="f.code" maxlength="30" placeholder="如 MD-01"></label>
      <label><span class="e-req">名称</span><input v-model="f.name" maxlength="40" placeholder="如 金检不良"></label>
      <label><span>类别</span><select v-model="f.category"><option value="">未分类</option><option v-for="c in CATS" :key="c">{{c}}</option></select></label>
      <label class="wide"><span>说明</span><input v-model="f.note" maxlength="200"></label>
      <div class="e-field wide"><span>适用工序（不选 = 全部工序）<i class="help" tabindex="0" title="报工时只在这些工序显示该不良项。工序取自本工作区的工序目录。">?</i></span>
        <p v-if="procs===null" class="muted">本工作区没有可读的工序目录，适用于全部工序。</p>
        <div v-else class="e-procs"><label v-for="p in procs" :key="p.code" style="display:flex;gap:4px;align-items:center;margin:0;font-size:12px;color:var(--cs-text)"><input v-model="f.processes" type="checkbox" :value="p.code" style="height:auto">{{p.name}}</label></div></div>
    </div></div>
    <label v-if="editing" class="e-field e-sec"><span class="e-req">修改原因</span><input v-model="f.reason" aria-label="修改原因"></label>
    <p v-if="invalid&&phase==='edit'" class="muted" role="note">{{invalid}}</p>
    <div v-if="phase==='preview'" class="e-preview"><h4>保存预览</h4><div class="diff"><span class="h">字段</span><span class="h">改前</span><span class="h">改后</span><span>不良项</span><del>{{editing?desc(editing):'（新建）'}}</del><ins>{{desc({code:f.code,name:f.name,category:f.category,processes:f.processes})}}</ins></div><p class="muted" style="margin-top:6px">只改字典；已采集的报工记录不改。不回写黑湖。</p></div>
    <div v-if="phase==='toggle'" class="e-preview"><h4>{{editing!.active?'停用':'启用'}}不良项</h4><label class="e-field"><span class="e-req">原因</span><input v-model="f.toggleReason" aria-label="停用或启用原因"></label><p class="muted">停用后报工不再提供该选项，历史记录保留。</p></div>
    <p v-if="err" role="alert" class="e-err">{{err}}</p><p v-if="ok" role="status" class="e-ok">{{ok}}</p>
    <details v-if="editing" class="e-log e-sec"><summary>修改记录 {{editing.log.length}} 条</summary><div v-html="logHTML(editing.log,'不良项')"/></details>
  </div>
  <footer><span class="muted">{{E.session?'写入练习会话 '+E.session:'写入本机原型保存区'}}</span>
    <template v-if="phase==='preview'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">返回修改</UButton><UButton size="sm" :loading="saving" @click="save()">确认保存</UButton></template>
    <template v-else-if="phase==='toggle'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">取消</UButton><UButton size="sm" :color="editing!.active?'error':'primary'" :loading="saving" @click="toggle()">确认{{editing!.active?'停用':'启用'}}</UButton></template>
    <template v-else><UButton v-if="editing" size="sm" color="neutral" variant="ghost" @click="phase='toggle'">{{editing.active?'停用':'启用'}}</UButton><UButton size="sm" :disabled="!!invalid" @click="phase='preview'">保存</UButton></template>
  </footer>
</aside>
</template>
