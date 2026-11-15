<script setup lang="ts">
// 结构 5 · 入职：把对话建厂包装成数字班组入职。说清产品和工艺 → 整理成工序和记录要求（对照黑湖工序目录与这款产品真实报过的工序）
// → 确认规则 → 每位数字员工显示是否具备上岗条件、还缺什么资料 → 确认后班组上岗。
// 写入边界：确认只改数字班组自己的配置并留入职记录（回读）；工序草稿不写进工艺（工艺在 A 线工序配置由人保存），页面写明。
import {computed,ref} from 'vue'
import {navigate} from '../frame/store'
import {C,src,createInbox,perms} from './crew-store'
import {CREW,crewPerm} from './crew-config'
import {onboardParse,readiness,verdict,yieldSeries,workOrders,pct,type Src} from './crew-facts'
import CrewPreview from './CrewPreview.vue'
import CrewAvatar from './CrewAvatar.vue'
const DEMO_CODE='C P2026100016'
// 示例口述：按这款产品在黑湖真实报过的工序和半成品（猪舌去舌苔、焯水）写的演示用口述，不是厂长原话
const SAMPLE='猪舌先出料，去舌苔，再焯水5分钟，然后卤制40分钟，冷却后称重，最后气调包装、贴成品盒签'
const code=ref(DEMO_CODE),text=ref(''),parsed=ref<ReturnType<typeof onboardParse>|null>(null),std=ref(''),tol=ref('3'),itemId=ref('')
const products=computed(()=>{const s=src();if(!s)return [];const seen=new Map<string,string>();for(const w of workOrders(s))if(!seen.has(w.productCode))seen.set(w.productCode,w.productName);return [...seen].map(([c,n])=>({code:c,name:n,routed:!!(s.resources.product||[]).find(p=>p.productCode===c)?.prcessRoutingCode})).sort((a,b)=>Number(a.routed)-Number(b.routed)||a.name.localeCompare(b.name,'zh'))})
const pname=computed(()=>products.value.find(p=>p.code===code.value)?.name||code.value)
// 整理时按上岗结论给默认勾选：暂不能上岗的默认不勾（F-12），人可以改
function run(){const s=src();if(!s||!text.value.trim())return;parsed.value=onboardParse(s,code.value,text.value);for(const c of CREW)pick.value[c.id]=verdict(ready.value[c.id])!=='暂不能上岗'}
const VTONE:Record<string,string>={可上岗:'success',带缺口上岗:'warning',暂不能上岗:'danger'}
// 出成规则：只给真实历史范围，标准由人填，不猜
const kgPair=computed(()=>{const p=parsed.value;if(!p)return null;const kg=p.steps.filter(x=>x.unitSeen==='kg').map(x=>x.process!);return kg.length>=2?[kg[0],kg[1]]:null})
const hist=computed(()=>{const s=src(),k=kgPair.value;if(!s||!k)return null;return yieldSeries(s as Src,code.value,k[0],k[1])})
const ready=computed(()=>readiness(src()||null,C.inv,{inspections:C.e?(C.e.inspections||[]).length:undefined,standards:C.e?(C.e.standards||[]).length:undefined,yieldStd:std.value?Number(std.value)/100:null,productCode:code.value}))
const pick=ref<Record<string,boolean>>(Object.fromEntries(CREW.map(c=>[c.id,true])))
const item=computed(()=>C.state?.inbox.find(x=>x.id===itemId.value))
const stdErr=computed(()=>std.value===''?'':!/^\d{1,3}(\.\d{1,2})?$/.test(std.value)||Number(std.value)<=0||Number(std.value)>100?'填 0–100 之间的百分数，最多两位小数':'')
async function submit(){
  const p=parsed.value;if(!p)return
  const rules=[...(kgPair.value?[{label:kgPair.value[0]+' → '+kgPair.value[1]+' 出成标准',value:std.value?std.value+'%（偏离超过 '+tol.value+' 个百分点提醒）':'未填：先不设标准，只按历史对比提醒'}]:[]),
    ...p.steps.filter(s=>s.unitSeen==='kg'||s.records.includes('投入重量')).map(s=>({label:s.process+' 记录要求',value:s.records.join('、')})),
    ...p.steps.filter(s=>s.note).map(s=>({label:s.process+' 工艺参数',value:s.note}))]
  const crewReady=Object.fromEntries(CREW.map(c=>[c.id,ready.value[c.id].filter(r=>!r.ok).map(r=>r.label+'：'+r.detail)]))
  try{const it=await createInbox({employee:'planner',kind:'onboard',module:'production',title:('班组入职 · '+pname.value).slice(0,80),sourceKey:'onboard:'+code.value+':'+p.steps.map(s=>s.process).join('>'),context:'班组入职',
    preview:{rows:[{label:'产品',value:pname.value+'（'+code.value+'）'},{label:'口述',value:text.value},
      {label:'整理出的工序',value:p.steps.map((s,i)=>(i+1)+'. '+s.process+(s.inHistory?'（黑湖报过）':'（黑湖这款产品没报过）')).join('  ')},
      ...(p.unmatched.length?[{label:'没对上工序的话',value:p.unmatched.join('；')}]:[]),
      ...(p.missing.length?[{label:'报过但口述没提',value:p.missing.join('、')}]:[]),
      ...rules,
      {label:'上岗的数字员工',value:CREW.filter(c=>pick.value[c.id]).map(c=>c.name+'（'+verdict(ready.value[c.id])+(crewReady[c.id].length?'，缺 '+crewReady[c.id].length+' 项':'')+'）').join('、')||'无'},
      {label:'确认后执行',value:'保存数字班组启用配置与入职记录（数字班组保存区，回读）'}],
      impact:['上岗的员工出现在班组首页、手机端入口与「发现的问题」'],basis:['黑湖工序目录 '+(src()?.resources.process||[]).length+' 道','这款产品黑湖工单 '+p.workOrders+' 张，报过的工序：'+p.historyProcs.join('、')],
      notDone:['不写入工艺路线 / 工序配置：在「工序配置」页由人保存','不写黑湖','出成标准不写入「出成标准」页（标准只在那里维护）']},
    action:{type:'crew-onboard',payload:{productCode:code.value,productName:pname.value,steps:p.steps,rules,enable:CREW.filter(c=>pick.value[c.id]).map(c=>c.id),crewReady}}})
    itemId.value=it.id}catch(e){C.error=(e instanceof Error?e.message:'失败')+'。没有执行任何写操作。'}
}
const last=computed(()=>(C.state?.onboard||[]).slice(-1)[0])
</script>
<template><div>
<p v-if="last" class="notice">最近一次入职：{{last.productName}} · {{last.state}} · {{last.by}} <button class="btn link" type="button" @click="navigate('/crew',C.ws==='ydwm'?{ws:'ydwm'}:{})">看班组首页</button></p>
<p v-if="C.ws==='ydwm'" class="empty">味美工作区没有黑湖工单和工序目录，入职演示在六膳门工作区。</p>
<section v-else class="panel"><header><h2>① 说清产品和工艺</h2><span class="muted">对话建厂的入口；本原型用文字口述（语音转写未接）</span></header>
  <div class="body" style="display:grid;gap:10px">
    <label class="kv"><span>产品</span><select v-model="code" aria-label="产品" style="max-width:420px"><option v-for="p in products" :key="p.code" :value="p.code">{{p.name}}{{p.routed?'':'（黑湖无工艺路线）'}}</option></select></label>
    <textarea v-model="text" rows="3" aria-label="口述做法" placeholder="厂长口述这款产品怎么做，例如：先出料，去舌苔，焯水，卤制，最后气调包装、贴盒签" style="width:100%;font-size:15px"/>
    <div class="actions"><button class="btn" type="button" @click="text=SAMPLE;code=DEMO_CODE">填入示例口述（卤猪舌）</button><span class="muted">示例按这款产品在黑湖真实报过的工序写，不是厂长原话</span><button class="btn primary" type="button" :disabled="!text.trim()||!src()" @click="run">整理成工序</button></div>
  </div></section>
<template v-if="parsed">
<section class="panel"><header><h2>② 整理出的工序与记录要求 <small class="muted">{{pname}} · 黑湖{{parsed.hasRoute?'有':'没有'}}工艺路线 · 工单 {{parsed.workOrders}} 张</small></h2></header>
  <div class="body steps">
    <div v-for="(s,i) in parsed.steps" :key="i" class="step" :data-step="s.process"><span class="n">{{i+1}}</span><div><b>{{s.process}}</b><div class="muted">口述：{{s.said}}</div></div>
      <div><span :class="s.inHistory?'ok':'muted'">{{s.inHistory?'黑湖工单上报过（单位 '+s.unitSeen+'）':'这款产品的黑湖工单没报过这道'}}</span><div>要记：{{s.records.join('、')}}</div><div v-if="s.note" class="muted">{{s.note}}</div></div></div>
    <p v-if="parsed.unmatched.length" class="muted">没对上工序目录的话：{{parsed.unmatched.join('；')}}（不自动新建工序；需要的话在工序配置里加）</p>
    <p v-if="parsed.missing.length" class="muted">黑湖报过、口述没提：{{parsed.missing.join('、')}}</p>
  </div></section>
<section class="panel"><header><h2>③ 确认规则</h2></header><div class="body" style="display:grid;gap:8px">
  <template v-if="kgPair&&hist">
    <p>{{kgPair[0]}} → {{kgPair[1]}} 出成（后道良品 ÷ 前道良品）。黑湖历史：{{hist.points.filter(p=>p.ratio!=null&&!p.suspect).length}} 张工单可算，范围 {{pct(hist.range?.min.ratio??null)}}（{{hist.range?.min.code}}）～ {{pct(hist.range?.max.ratio??null)}}（{{hist.range?.max.code}}）。</p>
    <label class="kv"><span>出成标准（%）</span><span><input v-model="std" inputmode="decimal" placeholder="由厂长填写，不填就不设标准" aria-label="出成标准" style="width:220px"> <small v-if="stdErr" class="no">{{stdErr}}</small></span></label>
    <label class="kv"><span>偏离提醒（百分点）</span><select v-model="tol" style="width:120px"><option>2</option><option>3</option><option>5</option></select></label>
  </template>
  <p v-else class="muted">口述里没有两道按 kg 记录的连续工序，出成规则先不设。</p>
  <p class="muted">称重类工序（按 kg）报工时记 投入、良品、不良、起止时间（称重报工的字段）；口述里的温度、时长，系统报工没有字段，先进工序说明。</p>
</div></section>
<section class="panel"><header><h2>④ 每位数字员工的上岗条件</h2><span class="muted">只看资料有没有，不评价好坏</span></header>
  <div class="body grid5">
    <div v-for="c in CREW" :key="c.id" class="crew-card" :data-ready="c.id"><header><CrewAvatar :name="c.name" :state="ready[c.id].every(r=>r.ok)?'idle':'alert'" :size="24"/><h2>{{c.name}} <span class="chip" :class="VTONE[verdict(ready[c.id])]">{{verdict(ready[c.id])}}</span></h2></header>
      <p v-for="r in ready[c.id]" :key="r.label" style="font-size:12px;line-height:1.5"><b :class="r.ok?'ok':'no'">{{r.ok?'具备':'缺'}}</b> {{r.label}}{{r.key?'（关键）':''}}：{{r.detail}}</p>
      <p class="muted" v-if="crewPerm(c,perms())==='-'">当前身份没有它的模块权限</p>
      <label style="display:flex;gap:6px;align-items:center;font-size:13px"><input v-model="pick[c.id]" type="checkbox" style="height:auto"> 随本次入职上岗</label></div>
  </div></section>
<section class="panel"><header><h2>⑤ 确认后上岗</h2><button class="btn primary" type="button" :disabled="!!stdErr||!!item||!C.state?.writable" @click="submit">生成入职预览</button></header>
  <div class="body"><CrewPreview v-if="item" :item="item"/><p v-else class="muted">生成后进「等我确认」，有生产模块办理权限的人确认。</p></div></section>
</template>
</div></template>
