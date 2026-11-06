<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue';
import {getBlacklakeLaborSummary,type BlacklakeLaborSummary} from '@/api/smartbi/blacklake';
import type {PythonServiceError} from '@/api/smartbi/common';
import { analyzeLaborCosts, type LaborCostInput } from '@/utils/laborCostAnalysis';
import CostDataTable from './CostDataTable.vue';
import type {CostTableColumn} from '@/utils/costTableQuery';
const props=defineProps<{input:LaborCostInput;scopeLabel:string}>();
const registered=ref<BlacklakeLaborSummary|null>(null),summaryError=ref('');
let generation=0,retryTimer:number|undefined;
watch(()=>`${props.input.sourceRunId||''}:${props.input.blacklakeOrderIds?.join(',')||''}`,()=>{
  const current=++generation;registered.value=null;summaryError.value='';if(retryTimer!==undefined)window.clearTimeout(retryTimer);
  const ids=props.input.blacklakeOrderIds,run=props.input.sourceRunId;
  if(!ids?.length||!run?.startsWith('live:'))return;
  const load=async()=>{
    try{const value=await getBlacklakeLaborSummary(ids,run);if(current===generation){registered.value=value;summaryError.value='';}}
    catch(error){if(current!==generation)return;summaryError.value='登记人数核对暂未完成，稍后自动重试。';
      if(error instanceof Error&&[401,403,404].includes((error as PythonServiceError).status||0))return;
      retryTimer=window.setTimeout(()=>{void load();},5000);}
  };void load();
},{immediate:true});
onBeforeUnmount(()=>{generation++;if(retryTimer!==undefined)window.clearTimeout(retryTimer);});
const model=computed(()=>{
  const value=analyzeLaborCosts(props.input),summary=registered.value;
  if(!summary)return value;
  const aligned=summary.total!==null&&value.total!==null&&Math.abs(summary.total-value.total)<0.005;
  return {...value,registeredParticipants:summary.participants,registeredCoverage:summary.complete&&aligned,
    registeredPerPerson:summary.complete&&aligned?summary.perRegisteredPerson:null,
    rows:value.rows.map(row=>({...row,registeredCount:summary.processes.find(p=>p.process===row.process)?.participants??null}))};
});
const mode=ref('recorded');
watch(()=>props.input,()=>{mode.value='recorded';});
const fmt=(value:number|null|undefined)=>value==null?'待补资料':value.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const metrics=computed(()=>{
  const value=model.value;
  const amountHint=value.total===null?'缺工单人工费记录':!value.reconciled?'工序明细与人工费合计未对齐':'';
  return [
    {label:'已记录人工费',value:value.total,hint:'缺工单人工费记录'},
    {label:`每${props.input.unit||'单位成品'}人工费`,value:value.perUnit,hint:[value.total===null?'缺工单人工费记录':'',!props.input.unit?'缺成品单位':'',!Number.isFinite(props.input.output)||!(props.input.output!>0)?'成品产量需核对':''].filter(Boolean).join('；')},
    {label:'人均人工费',value:value.perPerson,hint:[value.participants===null?(value.sourceAvailable?'已查到登记人员，需确认实际班组名单':'缺实际参与人员名单'):'',amountHint].filter(Boolean).join('；')},
    {label:'每人时费用',value:value.perPersonHour,hint:[value.personHours===null?(value.sourceAvailable?'需补每段实际参与人数，不能直接用报工时长':'缺各工序的有效人时'):value.personHours===0?'有效人时为零，暂不能计算':'',amountHint].filter(Boolean).join('；')},
  ];
});
type LaborRow=ReturnType<typeof analyzeLaborCosts>['rows'][number];
const columns=computed<CostTableColumn<LaborRow>[]>(()=>[
  {key:'process',label:'工序',minWidth:145},
  {key:'amount',label:mode.value==='person_hours'?'测算分摊额':'已记录人工费',type:'number',value:row=>mode.value==='person_hours'?row.allocated:row.amount},
  {key:'share',label:'人工占比',type:'number',suffix:'%',value:row=>mode.value==='person_hours'?(model.value.total&&row.allocated!=null?row.allocated/model.value.total*100:null):row.share==null?null:row.share*100},
  {key:'perUnit',label:`每${props.input.unit||'单位成品'}人工`,type:'number',value:row=>mode.value==='person_hours'?(props.input.output&&props.input.output>0&&props.input.unit&&row.allocated!=null?row.allocated/props.input.output:null):row.perUnit},
  {key:'reportedHours',label:'已报时长',type:'number',suffix:' 小时'},
  {key:'personHours',label:'有效人时',type:'number'},
  ...(model.value.sourceAvailable?[
    {key:'sourceEffectiveHours',label:'报工有效时长',type:'number' as const,suffix:' 小时'},
    {key:'registeredPeople',label:'登记人数',type:'number' as const,value:(row:LaborRow)=>'registeredCount' in row?row.registeredCount:row.registeredWorkerKeys?.length??null},
  ]:[]),
  ...(mode.value==='recorded'&&model.value.rows.some(r=>r.segmentPerPerson!=null)?[{key:'segmentPerPerson',label:'本时段人均费用',type:'number' as const}]:[]),
]);
</script>
<template>
  <section class="labor-analysis" aria-label="人工与工序成本分析">
    <h3>人工花在哪里</h3><p class="scope">{{ scopeLabel }}</p>
    <div class="labor-metrics"><div v-for="metric in metrics" :key="metric.label"><span>{{metric.label}}</span><strong>{{fmt(metric.value)}}</strong><small v-if="metric.value===null">{{metric.hint}}</small></div></div>
    <div v-if="model.sourceAvailable" class="source-evidence">
      <b>黑湖已补到的记录</b>
      <p v-if="summaryError" class="warning">{{summaryError}}</p>
      <p>{{model.registeredReports}} 条报工，{{model.registeredParticipants??'尚未核实'}} 名登记生产人员；{{model.sourceEffectiveHours===null?'有效时长待核对':`累计报工有效时长 ${fmt(model.sourceEffectiveHours)} 小时`}}。</p>
      <p v-if="model.registeredPerPerson!==null"><b>按登记人员测算人均：{{fmt(model.registeredPerPerson)}}</b>。如由班组长代报，实际人均会不同，需先确认班组名单。</p>
      <p v-else>工资与登记人员的对应关系还未核对完整，暂不能测算登记口径的人均。</p>
      <p v-if="model.zeroTimeReports||model.overlapReports" class="warning">{{model.zeroTimeReports}} 条零时长报工，{{model.overlapReports}} 条报工与同一登记人员的其他时段重叠；请车间确认是否代报或补录。</p>
      <p class="scope">表内先看黑湖已记录的各工序工资；登记人员已跨工序去重，实际班组人数确认后才能按人时重新分摊。</p>
    </div>
    <p v-if="model.total===0" class="warning">工资记录为零，请先确认是工资另记、漏记，还是确实不计薪。</p>
    <p v-if="!model.reconciled" class="warning">工序明细还没有与人工费合计对齐，先核对金额，再做分摊。</p>
    <div class="mode" v-if="model.canAllocate"><label>查看方式<select v-model="mode" aria-label="人工分摊查看方式"><option value="recorded">已记录的工序人工</option><option value="person_hours">按人时分摊测算</option></select></label></div>
    <p v-if="mode==='person_hours'" class="warning">按各工序人时分配同一笔人工费，仅供比较，不改变工资或账目。</p>
    <CostDataTable :rows="model.rows" :columns="columns" row-key="id" missing-label="待补资料" paginate/>
    <div v-if="model.needs.length" class="missing"><h4>还需要补什么</h4><article v-for="need in model.needs" :key="need.key"><strong>{{ need.missing }}</strong><p>找{{ need.owner }}：{{ need.action }}。</p><p class="scope">补齐后：{{ need.benefit }}。</p></article></div>
    <details v-if="input.requests?.length" class="explain"><summary>逐工单、逐工序补充清单（{{input.requests.length}} 项）</summary><CostDataTable :rows="input.requests" :columns="[{key:'orderCode',label:'工单',minWidth:160},{key:'process',label:'工序',minWidth:95},{key:'missing',label:'还缺什么',minWidth:230},{key:'owner',label:'找谁补',minWidth:150},{key:'action',label:'具体核对动作',minWidth:270},{key:'benefit',label:'补齐后能算什么',minWidth:220}]" row-key="id" paginate/></details>
    <details class="explain"><summary>怎么算</summary><p>计时人工＝每段时长 × 该段人数 × 费率；计件人工沿用已核定的计件工资。同一笔工资只记一次。</p><p>人均人工费＝所选工单人工费 ÷ 去重后的参与人数；同一人做多道工序只算一个人。时段人均费用只针对该段，不代表整个范围的人均费用。</p><p>人时分摊＝所选工单人工费 × 工序人时 ÷ 总人时，尾差留在最后一个有工时的工序。已报时长不直接当作人时，资料不齐时保持待补。</p></details>
  </section>
</template>
<style scoped>
.source-evidence{padding:14px 16px;margin:16px 0;background:#f7f9fb;border:1px solid #edf2f7;border-radius:8px;font-size:13px;line-height:1.7}.source-evidence p{margin:6px 0}
.labor-metrics small{display:block;margin-top:6px;color:#7a8599;font-size:12px;font-weight:400;line-height:1.6}
.labor-analysis{border:1px solid #edf2f7;border-radius:10px;padding:20px;background:#fff;color:#1a2332}.labor-analysis h3,.labor-analysis h4{margin:0 0 12px}.scope{font-size:12px;color:#7a8599}.labor-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin:18px 0}.labor-metrics span,.labor-metrics strong{display:block}.labor-metrics span{font-size:12px;color:#7a8599}.labor-metrics strong{font-size:20px;margin-top:6px}.missing{margin-top:20px}.missing article{border-top:1px solid #edf2f7;padding:12px 0}.missing p{margin:5px 0;line-height:1.7;font-size:13px}.warning{color:#925c16;font-size:13px;line-height:1.7}.mode{margin:14px 0;font-size:13px}.mode select{padding:6px 10px;margin-left:8px;border:1px solid #edf2f7;border-radius:6px}.explain{margin-top:16px;font-size:12px;color:#7a8599}.explain summary{cursor:pointer;color:#1b65a8}.explain p{line-height:1.8}@media(max-width:760px){.labor-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.labor-analysis{padding:14px}}
</style>
