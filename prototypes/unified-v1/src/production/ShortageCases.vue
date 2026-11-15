<script setup lang="ts">
// 缺料异常（R41，六膳门）：四处挂载（生产 / 质量 / 采购 / 销售）都指向本页。
// 来源：黑湖「采购需求」（purchase_requirement）。缺口量 = 黑湖 lackAmount（需求量 − 已下达采购量）；不自己推算，不展开 BOM。
// 黑湖采购需求没有「影响哪张订单 / 工单」的字段，所以本页不写影响范围，不拿同产品订单顶替。只读，不设补充入口。
import {computed,onMounted,ref} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import type {Row} from '../frame/types'
import {S,navigate} from '../frame/store'
import {fmtQty,sourceTagHTML} from '../frame/ai-protocol'
import {bjDay,bjToday} from './time'
const rows=ref<Row[]>([]),busy=ref(false),err=ref(''),today=ref(''),all=ref(false),q=ref('')
const n=(v:unknown)=>{const x=Number(v);return v==null||v===''||!Number.isFinite(x)?null:x}
async function read(){busy.value=true;err.value=''
  try{const r=await fetch('/__lsm/supply/extras'),b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'黑湖采购需求读取失败')
    today.value=bjDay(S.source?.checkedAt||b.data.checkedAt)||bjToday();rows.value=(b.data.resources.purchase_requirement||[]) as Row[]}
  catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(read)
const lack=(r:Row)=>n(r.lackAmount)??0
const shown=computed(()=>rows.value.filter(r=>(all.value||lack(r)>0)&&(!q.value||(String(r.code)+String(r.productName)+String(r.productCode)).toLowerCase().includes(q.value.toLowerCase()))).sort((a,b)=>lack(b)-lack(a)))
const nLack=computed(()=>rows.value.filter(r=>lack(r)>0).length)
const due=(r:Row)=>{const d=bjDay(r.arrivalPlanTime);if(!d)return '未填写';return d<today.value?'已过计划到货日（'+d+'）':d}
</script>
<template><section class="card table-panel sc">
<div class="sc-head"><div><h2>缺料异常 <small>{{nLack}} 条有缺口 / 采购需求共 {{rows.length}} 条</small></h2><p class="sc-note">生产、质量、采购、销售四处菜单是同一页。缺口量取黑湖采购需求的「缺口」字段（需求量 − 已下达采购量），截至快照日 {{today||'—'}}。</p></div><span v-html="sourceTagHTML('blacklake')"/></div>
<p v-if="err" role="alert" class="sc-err">{{err}}</p>
<div class="sc-bar"><label><input type="checkbox" v-model="all"> 同时显示没有缺口的采购需求</label><input v-model="q" aria-label="搜索采购需求" placeholder="需求单号、物料"><UButton size="xs" color="neutral" variant="outline" :loading="busy" @click="read">重新读取</UButton></div>
<div class="table-scroll"><table><thead><tr><th>采购需求</th><th>物料</th><th>需求量</th><th>已下达采购</th><th>已收货</th><th>缺口</th><th>计划到货</th></tr></thead><tbody>
<tr v-for="r in shown" :key="String(r.materialRequirementsId)"><td>{{r.code}}</td><td>{{r.productName}}<small>{{r.productCode}}</small></td><td>{{fmtQty(n(r.reqAmount)??0,String(r.productUnitName||''))}}</td><td>{{fmtQty(n(r.alreadyReleaseAmount)??0,String(r.productUnitName||''))}}</td><td>{{fmtQty(n(r.alreadyReceiveAmount)??0,String(r.productUnitName||''))}}</td><td :class="{bad:lack(r)>0}">{{lack(r)>0?fmtQty(lack(r),String(r.productUnitName||'')):'无'}}</td><td>{{due(r)}}</td></tr>
<tr v-if="!shown.length"><td colspan="7" class="empty">{{busy?'读取中…':'黑湖采购需求里没有缺口。'}}</td></tr></tbody></table></div>
<ul class="sc-notes"><li>黑湖采购需求没有「影响哪张订单 / 工单」的字段，所以不显示影响范围；要看订单交期风险，去交付风险页。</li><li>本页只读；补采购回采购需求和采购订单办理。</li></ul>
<div class="sc-act"><UButton size="sm" color="neutral" variant="outline" @click="navigate('/production/delivery-risks')">交付风险</UButton></div>
</section></template>
<style scoped>.sc-head{display:flex;justify-content:space-between;gap:12px}.sc-head h2{margin:0 0 4px}.sc-head small{font-weight:400;color:#667589;font-size:13px}.sc-note{font-size:12px;color:#667589;margin:4px 0}.sc-err{color:var(--cs-danger,#c53b3b);font-size:13px}.sc-bar{display:flex;gap:12px;align-items:center;margin:8px 0;font-size:13px}.sc-bar input:not([type]){border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:4px 8px;margin-left:auto}.table-scroll td{white-space:nowrap;font-variant-numeric:tabular-nums}.table-scroll small{display:block;color:#8a96a6;font-size:11px}.bad{color:var(--cs-danger,#c53b3b);font-weight:600}.sc-notes{font-size:12px;color:#667589;padding-left:18px}.sc-act{margin-top:8px}</style>
