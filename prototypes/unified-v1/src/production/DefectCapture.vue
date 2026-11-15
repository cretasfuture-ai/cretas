<script setup lang="ts">
// 报工不良品项采集（BL-borrow B7）：E 线出不良品项字典与统计，A 线在报工详情里按字典补录。
// 黑湖已有不良数时各项之和不得超过它（黑湖为准）；黑湖不良数为 0 或缺失时不补录（不凭空造不良）。
// 采集值标「Cretas 补充」，预览 → 确认 → 保存 → 独立回读；可撤销，留痕；不回写黑湖。
import {computed,onMounted,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import type {Row} from '../frame/types'
import {rs} from '../frame/store'
import {fmtQty,sourceTagHTML,confirmReason} from '../frame/ai-protocol'
import {acceptanceSession} from './session'
import {bjTime} from './time'
interface Defect{id:string;code:string;name:string;category?:string;processes:string[];active:boolean}
interface Item{defectId:string;code:string;name:string;qty:number}
interface Capture{id:string;reportId:string;items:Item[];total:number;sourceDefective:number|null;unallocated:number|null;unit:string|null;reason:string;at:string;by:string;revoked:null|{at:string;reason:string}}
const props=defineProps<{report:Row}>()
const session=acceptanceSession()
const q=(path:string,extra:Record<string,string>={})=>{const p=new URLSearchParams({ws:'lsm',...extra});if(session)p.set('acceptanceSession',session);return path+(path.includes('?')?'&':'?')+p}
const dict=ref<Defect[]>([]),captures=ref<Capture[]>([]),version=ref(0),busy=ref(false),err=ref(''),notice=ref(''),editing=ref(false),preview=ref(false),qty=ref<Record<string,string>>({}),reason=ref('')
const reportId=computed(()=>String(props.report.id))
const defective=computed(()=>{const v=props.report.numberofDefectives;return v===null||v===undefined||v===''?null:Number(v)})
const unit=computed(()=>String(props.report.stockUnit||''))
const processCode=computed(()=>rs('process').find(p=>String(p.id)===String(props.report.processId))?.code as string|undefined)
const usable=computed(()=>dict.value.filter(d=>d.active!==false&&(!d.processes?.length||(processCode.value&&d.processes.includes(processCode.value)))))
const current=computed(()=>captures.value.find(c=>!c.revoked))
const draft=computed(()=>usable.value.map(d=>({d,v:Number(qty.value[d.id]||0)})).filter(x=>x.v>0))
const draftTotal=computed(()=>draft.value.reduce((a,x)=>a+x.v,0))
const overflow=computed(()=>defective.value!==null&&draftTotal.value-defective.value>1e-9)
const canCapture=computed(()=>defective.value!==null&&Number.isFinite(defective.value)&&defective.value>0)
async function load(){err.value='';try{const [a,e]=await Promise.all([fetch(q('/__unified/a/api/defect-captures',{reportId:reportId.value})).then(r=>r.json()),fetch(q('/__unified/e/api/state')).then(r=>r.json())]);if(!a.success)throw Error(a.message==='未找到'?'补录服务尚未接入统一入口（等待 D 线挂载 /__unified/a/api），暂时只能查看':a.message||'读取补录失败');captures.value=a.data.captures;version.value=a.data.version;dict.value=e.success?(e.data.defects||[]):[]}catch(x){err.value=x instanceof Error?x.message:'读取失败'}}
function start(){qty.value=Object.fromEntries((current.value?.items||[]).map(i=>[i.defectId,String(i.qty)]));reason.value='';preview.value=false;editing.value=true;notice.value=''}
async function post(path:string,body:Record<string,unknown>){const r=await fetch(q(path),{method:'POST',headers:{'content-type':'application/json','x-prototype-action':'explicit'},body:JSON.stringify({ws:'lsm',...body})});const b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'保存失败');return b.data}
async function confirm(){busy.value=true;err.value='';try{await post('/__unified/a/api/defect-captures/save',{reportId:reportId.value,expectedVersion:version.value,reason:reason.value,items:draft.value.map(x=>({defectId:x.d.id,qty:x.v}))});await load();editing.value=false;preview.value=false;notice.value=current.value?'已保存并回读：'+current.value.items.map(i=>i.name+' '+i.qty).join('、'):'已保存，但回读没有找到记录，请刷新核对。'}catch(x){err.value=x instanceof Error?x.message:'保存失败'}finally{busy.value=false}}
async function revoke(){if(!current.value)return;const why=await confirmReason({title:'撤销不良项补录',object:'报工 '+String(reportId.value||''),message:'撤销后保留留痕，可在记录里看到原因；不回写黑湖。',reasonLabel:'撤销原因',okLabel:'确认撤销',danger:true,required:true});if(!why)return;busy.value=true;err.value='';try{await post('/__unified/a/api/defect-captures/revoke',{id:current.value.id,reason:why,expectedVersion:version.value});await load();notice.value='已撤销，留痕保留；统计恢复为「未采集」。'}catch(x){err.value=x instanceof Error?x.message:'撤销失败'}finally{busy.value=false}}
onMounted(load);watch(reportId,load)
</script>
<template><section class="detail-section defect-capture"><h3>不良品项 <span v-if="current" v-html="sourceTagHTML('cretas')"/></h3>
<p>黑湖不良数：<b>{{defective===null?'黑湖未返回':fmtQty(defective,unit)}}</b>。按 E 线「不良品项字典」分项补录，合计不超过黑湖不良数；补录值标「Cretas 补充」，不回写黑湖。</p>
<p v-if="!canCapture" class="muted">{{defective===0?'黑湖记录这条报工没有不良，不需要分项。':'黑湖没有这条报工的不良数，不能分摊不良项；请先在黑湖补录不良数。'}}</p>
<template v-else>
<div v-if="current&&!editing" class="dc-current"><table><thead><tr><th>不良项</th><th>数量</th></tr></thead><tbody><tr v-for="i in current.items" :key="i.defectId"><td>{{i.code}} · {{i.name}}</td><td>{{fmtQty(i.qty,unit)}}</td></tr><tr v-if="current.unallocated"><td>未分项</td><td>{{fmtQty(current.unallocated,unit)}}</td></tr></tbody></table><small>{{current.by}} · {{bjTime(current.at)}} · 原因：{{current.reason}}</small><div class="actions"><UButton size="xs" color="neutral" variant="outline" @click="start">修改</UButton><UButton size="xs" color="error" variant="ghost" :loading="busy" @click="revoke">撤销</UButton></div></div>
<p v-else-if="!editing" class="muted">未采集不良项。</p>
<UButton v-if="!editing&&!current" size="sm" color="neutral" variant="outline" :disabled="!usable.length" :title="usable.length?'按字典分项补录这条报工的不良':'字典里没有适用于本工序的启用不良项，请质量人员先在「不良分析 → 不良品项字典」维护'" @click="start">补录不良项</UButton>
<p v-if="!usable.length" class="muted">字典里没有适用于本工序的不良项（负责：质量人员，在「不良分析」维护）。</p>
<div v-if="editing" class="dc-form"><label v-for="d in usable" :key="d.id"><span>{{d.code}} · {{d.name}}<small v-if="d.category"> · {{d.category}}</small></span><input v-model="qty[d.id]" type="number" min="0" step="any" :aria-label="d.name+' 不良数量'" @input="preview=false"> <em>{{unit||'（单位未填）'}}</em></label>
<label><span>补录原因</span><input v-model="reason" placeholder="例如：按车间不良记录表分项" @input="preview=false"></label>
<p :class="overflow?'error':'muted'">合计 {{fmtQty(draftTotal,unit)}} / 黑湖不良数 {{fmtQty(defective,unit)}}{{overflow?'：分项合计超过黑湖记录的不良数，不能保存；若实际不良更多，请先在黑湖修正报工':''}}</p>
<div v-if="preview" class="dc-preview"><b>保存前预览</b><p>保存前：{{current?current.items.map(i=>i.name+' '+i.qty).join('、'):'未采集'}}</p><p>保存后：{{draft.map(x=>x.d.name+' '+x.v).join('、')||'（无）'}}；未分项 {{fmtQty((defective||0)-draftTotal,unit)}}</p></div>
<div class="actions"><UButton size="sm" color="neutral" variant="ghost" @click="editing=false">取消</UButton><UButton v-if="!preview" size="sm" :disabled="!draft.length||overflow||!reason.trim()" @click="preview=true">预览</UButton><UButton v-else size="sm" :loading="busy" @click="confirm">确认保存</UButton></div></div>
</template>
<p v-if="notice" role="status" class="notice">{{notice}}</p><p v-if="err" role="alert" class="error">{{err}}</p></section></template>
<style scoped>
.defect-capture table{width:100%;font-size:12px;border-collapse:collapse}.defect-capture td,.defect-capture th{height:32px;padding:4px;border-bottom:1px solid var(--cs-border,#e6ebf0);text-align:left;font-variant-numeric:tabular-nums}
.dc-form{display:flex;flex-direction:column;gap:8px;margin-top:8px}.dc-form label{display:grid;grid-template-columns:1fr 120px 60px;gap:8px;align-items:center;font-size:13px}.dc-form label:last-of-type{grid-template-columns:1fr 240px}.dc-form input{border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:4px 8px}
.dc-preview{background:var(--cs-primary-soft,#eef5fc);border-radius:6px;padding:8px;font-size:12px}.dc-preview p{margin:2px 0}.actions{display:flex;gap:8px;align-items:center;margin-top:6px}.muted{color:#748297;font-size:12px}.error{color:var(--cs-danger,#c53b3b);font-size:12px}.notice{color:#2f7d4a;font-size:12px}
</style>
