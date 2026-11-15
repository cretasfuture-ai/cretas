<script setup lang="ts">
// 标准出成率（R41，六膳门）：黑湖没有标准出成率这类数据，标准由 Cretas 补充（来源标「Cretas」），按工序维护，不回写黑湖。
// 补充入口 = 称重报工里已有的 a-api yield-standards 同一份数据（版本号并发保护、原因必填、撤销留痕），本页只是集中查看和维护。
// 实际出成率来自称重报工（良品净重 ÷ 投入），按工单看；没有称重记录的工序不显示实际值，不拿黑湖良品数顶替。
import {computed,onMounted,ref} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {rs} from '../frame/store'
import {fmtPct,sourceTagHTML,confirmReason} from '../frame/ai-protocol'
import {acceptanceSession} from './session'
import {bjTime} from './time'
interface Std{id:string;processCode:string;rate:number;tolerance:number;reason:string;at:string;by:string;revoked:null|{at:string;by:string;reason:string}}
const aq=(p:string)=>{const q=new URLSearchParams({ws:'lsm'});const s=acceptanceSession();if(s)q.set('acceptanceSession',s);return '/__unified/a/api/'+p+'?'+q}
const version=ref(0),list=ref<Std[]>([]),busy=ref(false),err=ref(''),notice=ref(''),form=ref(false)
const f=ref({processCode:'',rate:'',tol:'5',reason:''})
async function load(){busy.value=true;err.value='';try{const b=await (await fetch(aq('yield-standards'))).json();if(!b.success)throw Error(b.message||'读取失败');version.value=b.data.version;list.value=b.data.history||[]}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(load)
async function post(path:string,body:Record<string,unknown>){const r=await fetch(aq(path),{method:'POST',headers:{'content-type':'application/json','x-prototype-action':'explicit'},body:JSON.stringify({ws:'lsm',expectedVersion:version.value,...body})});const b=await r.json().catch(()=>({success:false,message:'服务返回格式不正确'}));if(!r.ok||!b.success)throw Error(b.message||'保存失败')}
const procs=computed(()=>rs('process').map(p=>({code:String(p.code),name:String(p.name)})))
const nameOf=(c:string)=>procs.value.find(p=>p.code===c)?.name||c
const active=computed(()=>list.value.filter(x=>!x.revoked))
const past=computed(()=>list.value.filter(x=>x.revoked).slice().reverse())
const stdOf=(c:string)=>active.value.find(x=>x.processCode===c)
async function save(){err.value='';const r=Number(f.value.rate);if(!f.value.processCode){err.value='请选择工序';return}if(!(r>0&&r<=150)){err.value='标准出成率填 0 ~ 150 之间的百分数';return}
  busy.value=true;try{await post('yield-standards/save',{processCode:f.value.processCode,rate:r/100,tolerance:f.value.tol,reason:f.value.reason});notice.value='已保存 '+nameOf(f.value.processCode)+' 的标准出成率 '+r+'%';form.value=false;f.value={processCode:'',rate:'',tol:'5',reason:''};await load()}catch(e){err.value=e instanceof Error?e.message:'保存失败'}finally{busy.value=false}}
async function revoke(s:Std){const why=await confirmReason({title:'撤销标准出成率',object:nameOf(s.processCode)+' · '+fmtPct(s.rate*100,{digits:2}),message:'撤销后该工序没有标准，出成率不再对比；记录保留留痕，不回写黑湖。',reasonLabel:'撤销原因',okLabel:'确认撤销',danger:true,required:true});if(why===null)return
  busy.value=true;try{await post('yield-standards/revoke',{id:s.id,reason:why});notice.value='已撤销 '+nameOf(s.processCode)+' 的标准';await load()}catch(e){err.value=e instanceof Error?e.message:'撤销失败'}finally{busy.value=false}}
function edit(code:string){const s=stdOf(code);f.value={processCode:code,rate:s?String(Math.round(s.rate*10000)/100):'',tol:s?String(s.tolerance):'5',reason:''};form.value=true}
</script>
<template><section class="card table-panel ys">
<div class="ys-head"><div><h2>标准出成率 <small>{{active.length}} 道工序已设标准</small></h2><p class="ys-note">黑湖没有标准出成率数据；标准由 Cretas 补充，按工序维护，不回写黑湖。称重报工里的「出成率对比标准」用的就是这里的数据。</p></div><div class="ys-tags"><span v-html="sourceTagHTML('missing')"/><span v-html="sourceTagHTML('cretas')"/></div></div>
<p v-if="err" role="alert" class="ys-err">{{err}}</p><p v-if="notice" role="status" class="ys-ok">{{notice}}</p>
<div class="ys-bar"><UButton size="sm" @click="form=!form;err=''">{{form?'收起':'新增 / 修改标准'}}</UButton><UButton size="xs" color="neutral" variant="outline" :loading="busy" @click="load">重新读取</UButton></div>
<form v-if="form" class="ys-form" @submit.prevent="save"><label>工序<select v-model="f.processCode"><option value="">请选择</option><option v-for="p in procs" :key="p.code" :value="p.code">{{p.name}}</option></select></label><label>标准出成率（%，0~150）<input v-model="f.rate" inputmode="decimal" placeholder="如 85"></label><label>允许偏离（个百分点）<input v-model="f.tol" inputmode="decimal"></label><label class="w">原因（必填）<input v-model="f.reason" placeholder="为什么设这个标准"></label><UButton type="submit" size="sm" :loading="busy">保存</UButton></form>
<div class="table-scroll"><table><thead><tr><th>工序</th><th>标准出成率</th><th>允许偏离</th><th>设置人 / 时间</th><th>原因</th><th class="op">操作</th></tr></thead><tbody>
<tr v-for="s in active" :key="s.id"><td>{{nameOf(s.processCode)}}</td><td>{{fmtPct(s.rate*100,{digits:2})}}</td><td>± {{s.tolerance}} 个百分点</td><td>{{s.by}} · {{bjTime(s.at)}}</td><td class="wrap">{{s.reason}}</td><td class="op"><UButton size="xs" color="neutral" variant="outline" @click="edit(s.processCode)">修改</UButton> <UButton size="xs" color="neutral" variant="outline" @click="revoke(s)">撤销</UButton></td></tr>
<tr v-if="!active.length"><td colspan="6" class="empty">{{busy?'读取中…':'还没有设置任何标准。黑湖没有此类数据，可点「新增 / 修改标准」补充。'}}</td></tr></tbody></table></div>
<details v-if="past.length"><summary>已撤销或被取代的标准（{{past.length}}）</summary><ul class="ys-past"><li v-for="s in past" :key="s.id">{{nameOf(s.processCode)}} {{fmtPct(s.rate*100,{digits:2})}}：{{s.revoked?.reason}}（{{s.revoked?.by}} · {{bjTime(s.revoked?.at)}}）</li></ul></details>
<p class="ys-note">口径：标准可录到 150%（副产或复水增重的工序可能超过 100%）；称重报工里实际出成率达到或超过 100% 的记录会标成「数据疑点」，只标不改原值。标准本身不因 ≥ 100% 被标疑点。</p><p class="ys-note">实际出成率看称重报工，按工单对比标准；没有称重记录的工序不显示实际值。</p>
</section></template>
<style scoped>.ys-head{display:flex;justify-content:space-between;gap:12px}.ys-head h2{margin:0 0 4px}.ys-head small{font-weight:400;color:#667589;font-size:13px}.ys-tags{display:flex;gap:6px;align-items:flex-start}.ys-note{font-size:12px;color:#667589;margin:6px 0}.ys-err{color:var(--cs-danger,#c53b3b);font-size:13px}.ys-ok{color:var(--cs-success,#2f7d4a);font-size:13px}.ys-bar{display:flex;gap:8px;margin:8px 0}.ys-form{display:flex;flex-wrap:wrap;gap:10px;align-items:flex-end;margin:8px 0;padding:10px;background:#f4f6f9;border-radius:8px;font-size:13px}.ys-form label{display:flex;flex-direction:column;gap:2px}.ys-form .w{min-width:240px}.ys-form input,.ys-form select{border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:4px 8px}.table-scroll td{white-space:nowrap}.wrap{white-space:normal!important;max-width:300px}.ys-past{font-size:12px;color:#667589}</style>
