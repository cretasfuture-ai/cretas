<script setup lang="ts">
// 第三批①：六膳门财务口径（应收、已收、待收、待开票、逾期）。数字全部由服务端 /__unified/d/lsm-finance 从黑湖快照计算；
// 本组件只展示：缺失显示「—」，金额两位小数，标黑湖来源与快照时间，口径可点开。compact=首页摘要；否则为经营分析整页。
import {computed,nextTick,onMounted,ref,watch} from 'vue'
import {fmtMoney,sourceTagHTML,openFormula,batchNoSearch,matchNos} from '../frame/ai-protocol'
interface Metric{label:string;value:number|null;count:number;missing:number;docs:number;source:string;maxDays?:number|null;items?:{label:string;value:number}[]|null}
interface Act{last:string|null;days:number|null;stale:boolean;unknown?:boolean;staleDays:number;basis:string;today?:string;note?:string}
interface Fin{activity?:Record<string,Act>;revision:string;checkedAt:string;currency:string;metrics:Record<string,Metric>;checks:{label:string;a:number|null;b:number|null;ok:boolean}[];receivables:{no:string;customer:string|null;type:string|null;amount:number|null;pending:number|null;due:string|null;overdue:boolean;overdueDays:number|null}[];toInvoiceTop:{code:string;name:string;pendingInvoice:number|null;pendingShipment:number|null;pendingReceive:number|null;received:number|null}[];invoicingByType:Record<string,number>;notes:string[]}
const props=defineProps<{compact?:boolean}>(),emit=defineEmits<{more:[]}>()
const fin=ref<Fin>(),error=ref(''),busy=ref(false)
async function read(){busy.value=true;error.value='';try{const r=await fetch('/__unified/d/lsm-finance'),b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'财务数据读取失败');fin.value=b.data}catch(e){fin.value=undefined;/* 读取失败不保留上一次的数，免得旧数当新数 */error.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
// 黑湖未返回币种：不加「元」，只在标题旁写「币种待确认」
const money=(v:unknown)=>fmtMoney(v,{digits:2,missing:'—',currency:''})
const M=(k:string)=>fin.value?.metrics[k]
// 第六批 D④：最近一次录入；超过 14 天（或看不到任何录入）标「疑似停用」
const A=(k:string)=>fin.value?.activity?.[k]
const actText=(a?:Act)=>!a?'':a.last?'最近一次录入：'+a.last+(a.days!=null?'（按快照日 '+a.today+'，'+a.days+' 天前）':''):'最近一次录入：无法判断（'+(a.note||'看不到录入')+'）'
const cards=computed(()=>{const f=fin.value;if(!f)return [];return [
  {k:'receivable',label:'应收',sub:M('receivable')?.docs+' 张应收单'},
  {k:'received',label:'已收',sub:M('received')?.docs+' 张收款单'},
  {k:'pending',label:'待收',sub:(M('pending')?.docs||0)+' 张未收清'+(M('overpaid')?.docs?' · 另有 '+M('overpaid')!.docs+' 张多收或冲减 '+money(M('overpaid')!.value):'')},
  {k:'toInvoice',label:'待开票',sub:'客户账本 '+M('toInvoice')?.docs+' 户'},
  {k:'overdue',label:'逾期未收',sub:M('overdue')?.docs?M('overdue')!.docs+' 张 · 最长 '+M('overdue')!.maxDays+' 天':'没有逾期单据',warn:!!M('overdue')?.docs}
]})
const tag=computed(()=>fin.value?sourceTagHTML('blacklake',{at:new Date(fin.value.checkedAt).toLocaleString('zh-CN',{hour12:false})}):'')
function explain(ev:MouseEvent,k:string){const m=M(k);if(!m)return;openFormula(ev.currentTarget as HTMLElement,{title:m.label,result:{value:m.value},formula:'合计：'+m.source+'（计入 '+m.count+' 张'+(m.missing?'，字段缺失 '+m.missing+' 张不按 0 计':'')+'）',plus:(m.items||[]).map(x=>({label:x.label,value:x.value,source:'blacklake' as const})),note:(fin.value?.currency||'')+'。'+(m.items?'':'条目超过 40 条，只给合计。')+(fin.value?.notes||[]).join('')})}
// 第四批③：应收单表接共享批量单号（BL 21）；按全部应收单精确匹配，列出未找到的单号
const nos=ref<string[]>([]),batchEl=ref<HTMLElement>()
const recvRows=computed(()=>{const all=fin.value?.receivables||[];if(!nos.value.length)return all;const set=new Set(nos.value);return all.filter(r=>set.has(String(r.no)))})
let mounted=false
watch([fin,batchEl],async()=>{await nextTick();if(mounted||!batchEl.value||!fin.value)return;mounted=true;batchNoSearch(batchEl.value,{label:'批量单号',onSearch:list=>{const m=matchNos(fin.value?.receivables||[],list,r=>r.no);nos.value=list;return {found:m.found.length,missing:m.missing}},onClear:()=>{nos.value=[]}})})
onMounted(read)
</script>
<template><section :class="['lsm-fin',{compact:props.compact}]" aria-label="财务口径">
<div class="fin-head"><h2>{{compact?'财务':'经营分析 · 财务口径'}}</h2><span v-if="tag" class="fin-tag" v-html="tag"/><small v-if="fin">{{fin.currency}}</small><button v-if="compact" class="fin-link" @click="emit('more')">经营分析 →</button><button v-else class="fin-link" :disabled="busy" @click="read">{{busy?'正在读取…':'重新读取'}}</button></div>
<p v-if="error" role="alert" class="fin-error">{{error.replace(/[。.]$/,'')}}。下列指标显示「—」，不是 0。</p>
<div class="fin-cards"><template v-if="fin"><button v-for="c in cards" :key="c.k" :class="{warn:c.warn}" :title="'点开看口径：'+M(c.k)?.source" @click="explain($event,c.k)"><span>{{c.label}}</span><strong>{{money(M(c.k)?.value)}}</strong><small>{{c.sub}}</small><small v-if="['receivable','toInvoice'].includes(c.k)&&A(c.k)" :class="['fin-act',{'fin-stale':A(c.k)?.stale}]" :title="'依据：'+A(c.k)?.basis+'的录入时间；超过 '+A(c.k)?.staleDays+' 天没有录入标疑似停用'">{{actText(A(c.k))}}<b v-if="A(c.k)?.stale"> · 疑似停用</b></small></button></template><template v-else><div v-for="l in ['应收','已收','待收','待开票','逾期未收']" :key="l" class="fin-empty"><span>{{l}}</span><strong>—</strong><small>{{busy?'正在读取':'未取到数据'}}</small></div></template></div>
<template v-if="fin&&!compact">
<div class="fin-checks"><p v-for="c in fin.checks" :key="c.label" :class="c.ok?'ok':'bad'">{{c.ok?'一致':'不一致'}} · {{c.label}}：{{money(c.a)}} / {{money(c.b)}}</p></div>
<div class="fin-grid"><section class="fin-sec"><h3 class="fin-h3">应收单<span ref="batchEl" class="fin-batch"/></h3><div class="fin-scroll"><table><thead><tr><th>单号 / 客户</th><th class="num">应收</th><th class="num">待收</th><th>到期</th><th>状态</th></tr></thead><tbody><tr v-for="r in recvRows" :key="r.no"><td>{{r.no}}<small>{{r.customer||'客户未返回'}}</small></td><td class="num">{{money(r.amount)}}</td><td class="num">{{money(r.pending)}}</td><td>{{r.due||'—'}}</td><td><span v-if="(r.pending||0)<0" class="st">多收或冲减</span><span v-else-if="(r.pending||0)===0" class="st done">已收清</span><span v-else-if="r.overdue" class="st warn">逾期 {{r.overdueDays}} 天</span><span v-else class="st">未到期</span></td></tr></tbody></table></div></section>
<section><h3>待开票（客户账本前 10）</h3><div class="fin-scroll"><table><thead><tr><th>客户</th><th class="num">待开票</th><th class="num">待发货</th><th class="num">已收</th></tr></thead><tbody><tr v-for="r in fin.toInvoiceTop" :key="r.code"><td>{{r.name}}<small>{{r.code}}</small></td><td class="num">{{money(r.pendingInvoice)}}</td><td class="num">{{money(r.pendingShipment)}}</td><td class="num">{{money(r.received)}}</td></tr></tbody></table></div><p v-if="!fin.toInvoiceTop.length" class="fin-note">客户账本里没有待开票金额。</p>
<p class="fin-note">出入库单据张数：<template v-for="(n,k) in fin.invoicingByType" :key="k">{{k}} {{n}} 张；</template>只计张数，不由出入库单推算开票额。</p></section></div>
<p class="fin-note">{{fin.notes.join(' ')}} 快照 {{fin.revision.slice(0,12)}}。只读黑湖快照，不回写。</p>
</template></section></template>
<style scoped>
.lsm-fin{background:white;border:1px solid #e0e6ee;border-radius:9px;padding:14px 16px;margin-bottom:18px}.fin-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:12px}.fin-head h2{font-size:15px;font-weight:600;margin:0}.fin-head small{font-size:11px;color:#7b8c9f}.fin-link{margin-left:auto;background:none;border:0;color:#1b65a8;font-size:12px;cursor:pointer}
.fin-cards{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px}.fin-cards>button,.fin-empty{background:#fafcfe;border:1px solid #e0e7ef;border-radius:8px;padding:12px;text-align:left;cursor:pointer;min-width:0}.fin-empty{cursor:default}.fin-cards span{font-size:12px;color:#6a7e92}.fin-cards strong{display:block;font-size:20px;font-weight:600;margin:6px 0;color:#263e56;font-variant-numeric:tabular-nums;white-space:nowrap}.fin-cards small{font-size:11px;color:#7b8c9f}.fin-cards .warn{border-color:#e7c48a;background:#fff8e9}.fin-cards .warn strong{color:#8a5a12}
.fin-act{display:block;margin-top:2px}.fin-stale{color:#885910!important}.fin-stale b{font-weight:600}.fin-error{font-size:12px;color:#b3261e;margin:0 0 8px}.fin-checks{margin:12px 0}.fin-checks p{font-size:12px;margin:2px 0}.fin-checks .ok{color:#2f7a5b}.fin-checks .bad{color:#b3261e}
.fin-grid{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(0,1fr);gap:16px}.fin-h3{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.fin-batch{font-weight:400}.fin-grid h3{font-size:13px;font-weight:600;margin:6px 0}.fin-grid table{width:100%;border-collapse:collapse;font-size:12px}.fin-grid th,.fin-grid td{padding:7px 6px;border-bottom:1px solid #edf1f5;text-align:left}.fin-grid td small{display:block;color:#7b8c9f}.fin-sec{min-width:0}.fin-scroll{overflow-x:auto;-webkit-overflow-scrolling:touch}.fin-scroll table{min-width:480px}.num{white-space:nowrap;text-align:right!important;font-variant-numeric:tabular-nums}.st{font-size:11px;padding:2px 6px;border-radius:4px;background:#edf1f5}.st.done{background:#e7f4ee;color:#2f7a5b}.st.warn{background:#fff1dc;color:#8a5a12}.fin-note{font-size:11px;color:#7b8c9f;margin:8px 0 0}
@media(max-width:1100px){.fin-cards{grid-template-columns:repeat(3,minmax(0,1fr))}.fin-grid{grid-template-columns:minmax(0,1fr)}}@media(max-width:600px){.fin-cards{grid-template-columns:repeat(2,minmax(0,1fr))}.fin-cards strong{font-size:17px}.lsm-fin{padding:12px}}
</style>
