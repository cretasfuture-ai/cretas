<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import UCard from '@nuxt/ui/components/Card.vue';
import UButton from '@nuxt/ui/components/Button.vue';
import DynamicChartRenderer from '@/components/smartbi/DynamicChartRenderer.vue';
import { getBlacklakeCompleteCost, followupBlacklakeCompleteCost, type CompleteCostReport, type CompleteCostFact, type CostProject } from '@/api/smartbi/blacklake';
import { useAuthStore } from '@/store/modules/auth';
import { buildUnitCostChart, groupEligibleCostProjects } from './costOverview';
import { formatBlacklakeDateTime } from './blacklakePeriod';
const report=ref<CompleteCostReport|null>(null),loading=ref(false),error=ref('');
const search=ref(''),unit=ref(''),page=ref(1),cohort=ref('eligible26'),section=ref('');
const selected=ref<CostProject|null>(null),chatFact=ref<CompleteCostFact|null>(null),question=ref(''),answer=ref(''),asking=ref(false);
const auth=useAuthStore();let generation=0;let timer:number|undefined;
const identity=computed(()=>[auth.factoryId,auth.user?.id,auth.currentRole].join(':'));
const fmt=(value:number|null|undefined)=>value==null?'未提供':value.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const labels:Record<string,string>={coverage:'覆盖与数据条件',material:'原料投入',labor:'工序人工',finished:'成品产出',product_cost:'产品及工单成本',production:'生产与工序',inventory:'库存与批次'};
function stop(){if(timer!==undefined)window.clearTimeout(timer);timer=undefined;}
async function load(){
  stop();const epoch=++generation;loading.value=true;error.value='';
  try{
    const next=await getBlacklakeCompleteCost();if(epoch!==generation)return;
    report.value=next;
    if(!units.value.includes(unit.value))unit.value=units.value[0]||'';
    if(next.batchAnalysis.status==='running')timer=window.setTimeout(()=>void load(),15000);
  }catch(cause){if(epoch===generation){report.value=null;selected.value=null;chatFact.value=null;answer.value='';error.value=cause instanceof Error?cause.message:'成本分析暂不可用';}}
  finally{if(epoch===generation)loading.value=false;}
}
onMounted(()=>void load());
watch(identity,()=>{generation++;report.value=null;selected.value=null;chatFact.value=null;answer.value='';void load();});
onBeforeUnmount(()=>{generation++;stop();});
watch([cohort,section,search],()=>{page.value=1;});
const units=computed(()=>[...new Set((report.value?.analysis.cost.projects||[]).map(r=>r.unit).filter((u):u is string=>!!u))]);
const orders=computed(()=>(report.value?.analysis.cost.projects||[]).filter(r=>`${r.projectCode} ${r.product}`.toLowerCase().includes(search.value.toLowerCase())));
const groups=computed(()=>groupEligibleCostProjects(report.value?.analysis.cost.projects||[],unit.value).sort((a,b)=>b.weightedUnitCost-a.weightedUnitCost));
const chart=computed(()=>buildUnitCostChart(groups.value));
const facts=computed(()=>(report.value?.batchAnalysis.results||[]).filter(r=>r.cohort===cohort.value&&(!section.value||r.sectionId===section.value)&&r.display.text.toLowerCase().includes(search.value.toLowerCase())));
const visibleFacts=computed(()=>facts.value.slice((page.value-1)*6,page.value*6));
const completedOrders=computed(()=>(report.value?.batchAnalysis.results||[]).filter(r=>r.kind==='work_order_detail').length);
const orderFact=computed(()=>(report.value?.batchAnalysis.results||[]).find(r=>r.kind==='work_order_detail'&&r.display.entityRefs?.some(p=>p.projectId===selected.value?.projectId)));
const needs=computed(()=>report.value?.analysis.costTraceability.workOrders.find(r=>String(r.workOrderId)===String(selected.value?.projectId))?.needs||[]);
const selectedAlerts=computed(()=>(report.value?.analysis.costAlerts?.items||[]).filter(a=>a.entityRefs.some(p=>p.projectId===selected.value?.projectId)));
function openChat(fact:CompleteCostFact){chatFact.value=fact;question.value='';answer.value='';}
async function ask(){
  if(!report.value||!chatFact.value||!question.value.trim()||asking.value)return;
  const epoch=generation;const fact=chatFact.value.factId;asking.value=true;
  try{const text=await followupBlacklakeCompleteCost(report.value.sourceRunId,fact,question.value.trim());if(epoch===generation&&fact===chatFact.value?.factId)answer.value=text;}
  catch(cause){if(epoch===generation)answer.value=cause instanceof Error?cause.message:'追问暂不可用';}
  finally{asking.value=false;}
}
</script>
<template>
  <main class="complete-cost" data-testid="complete-cost-analysis">
    <header><div><h2>最新补查 · 26张工单成本复核</h2><p>逐张核对原料、加工人工、成品和异常；每条分析都保留统计依据。</p></div><UButton color="neutral" variant="outline" :loading="loading" @click="load">刷新进度</UButton></header>
    <el-alert v-if="error" type="warning" :closable="false" :title="error"/>
    <p v-if="loading&&!report" role="status">正在读取已保存的数据和排队分析…</p>
    <template v-if="report">
      <p class="note">来源核查截至 {{ formatBlacklakeDateTime(report.sourceCheckedAt) }}。固定使用原109张中字段具备的26张，不随历史页面日期筛选改变，也不能代表全厂。不是完整制造成本或毛利；实际批次关联仍待补证。</p>
      <UCard><div class="metrics"><div><span>原遗漏证据补分析</span><strong>{{ report.batchAnalysis.cohorts.original44?.analyzed ?? 0 }} / 44</strong></div><div><span>工单逐张分析</span><strong>{{ completedOrders }} / 26</strong></div><div><span>全部证据逐条完成</span><strong>{{ report.batchAnalysis.analyzedFacts }} / {{ report.batchAnalysis.expectedFacts }}</strong></div><div><span>本地模型累计用量</span><strong>{{ report.batchAnalysis.usage?.totalTokens?.toLocaleString() ?? '等待返回' }}</strong></div></div>
        <p role="status">{{ report.batchAnalysis.status==='complete'?'本轮所有证据已逐条完成并保存。':report.batchAnalysis.status==='failed'?'部分批次尚未通过校验，已保留原答，等待恢复；数据仍可查看。':'模型正在按顺序分析，已完成的内容逐条显示，刷新或关闭页面不会丢失队列。' }}</p>
        <p v-if="report.batchAnalysis.usageUnknownCalls" class="note">有 {{ report.batchAnalysis.usageUnknownCalls }} 次调用用量尚未返回，上方仅统计已返回用量。</p>
      </UCard>
      <UCard><template #header><div class="heading"><h3>同单位产品成本 · 材料加人工</h3><el-select v-model="unit" aria-label="最新成本成品单位"><el-option v-for="u in units" :key="u" :value="u" :label="u"/></el-select></div></template>
        <DynamicChartRenderer v-if="groups.length" :config="chart" :height="300" :number-precision="2" presentation="plain"/>
        <p class="note">每个产品按材料加人工总额除该组最终良品量；不同单位、规格、加工阶段不能仅按高低认定异常。半成品领料已经承接的上游价值不再重复相加。</p>
        <div v-for="g in groups" :key="g.key" class="rank"><span>{{ g.product }}</span><strong>{{ fmt(g.weightedUnitCost) }} / {{ g.unit }}</strong><span>{{ g.sampleCount }} 张工单</span></div>
      </UCard>
      <UCard><template #header><h3>26张工单 · 从原料到成品逐单核对</h3></template>
        <el-input v-model="search" aria-label="搜索最新成本工单" placeholder="搜索工单或产品，同时筛选下方分析" clearable/>
        <el-table :data="orders" stripe><el-table-column prop="projectCode" label="工单" min-width="180"/><el-table-column prop="product" label="成品" min-width="180"/>
          <el-table-column label="最终良品" min-width="120"><template #default="{row}">{{ fmt(row.finishedGood) }} {{ row.unit }}</template></el-table-column>
          <el-table-column label="单位直接成本" min-width="130"><template #default="{row}">{{ fmt(row.unitIdentifiedDirectCost) }} / {{ row.unit }}</template></el-table-column>
          <el-table-column label="明细与异常" width="160"><template #default="{row}"><UButton color="neutral" variant="outline" @click="selected=row">查看原料与分析</UButton></template></el-table-column>
        </el-table>
        <section v-if="selected" class="detail" aria-label="最新工单成本明细"><h4>{{ selected.projectCode }} · {{ selected.product }}</h4>
          <p>成品良品 {{ fmt(selected.finishedGood) }} {{ selected.unit }}；材料净额 {{ fmt(selected.materialCost) }}，工序人工 {{ fmt(selected.laborCost) }}；单位材料加人工 {{ fmt(selected.unitIdentifiedDirectCost) }} / {{ selected.unit }}。币种待核。</p>
          <h4>原料或半成品 → 本工单成品</h4><el-table :data="selected.materialLinesDetail" stripe><el-table-column prop="material" label="材料"/><el-table-column prop="orderCode" label="领退料单"/><el-table-column label="净耗用"><template #default="{row}">{{ fmt(row.netConsumption) }} {{ row.unit }}</template></el-table-column><el-table-column label="已记录净额"><template #default="{row}">{{ fmt(row.recognizedCost) }}</template></el-table-column></el-table>
          <h4>加工人工</h4><el-table :data="selected.processes" stripe><el-table-column prop="process" label="工序"/><el-table-column label="累计工时"><template #default="{row}">{{ fmt(row.hours) }} 小时</template></el-table-column><el-table-column label="已记录工资"><template #default="{row}">{{ fmt(row.laborCost) }}</template></el-table-column></el-table>
          <div class="analysis"><h4>这张工单的分析</h4><template v-if="orderFact"><p>{{ orderFact.whatItMeans }}</p><p>影响：{{ orderFact.whyItMatters }}</p><p>下一步：{{ orderFact.nextStep }}（{{ orderFact.owner }}）</p><p class="note">现在不能下的结论：{{ orderFact.cannotConclude }}</p><UButton color="neutral" variant="outline" @click="openChat(orderFact)">追问这张工单</UButton></template><p v-else>这张工单的解释仍在排队；上述数字已由规则计算。</p></div>
          <h4>异常与待核资料</h4><p v-for="a in selectedAlerts" :key="a.id">{{ a.title }}：{{ a.message }}</p><p v-if="!selectedAlerts.length">现有规则未命中明确异常；不代表批次链或实际费用已全部核实。</p>
          <article v-for="n in needs" :key="n.id" class="request"><strong>需确认：{{ n.missingData }}</strong><p>找谁：{{ n.responsibleRole }}。补齐后：{{ n.afterProvided }}</p></article>
        </section>
      </UCard>
      <UCard><template #header><h3>逐条证据 · 数据下面的白话分析</h3></template>
        <div class="filters"><el-select v-model="cohort" aria-label="证据分析范围"><el-option value="eligible26" label="26张工单全部证据"/><el-option value="original44" label="原遗漏的44条证据"/></el-select><el-select v-model="section" aria-label="分析板块" clearable placeholder="全部板块"><el-option v-for="(label,id) in labels" :key="id" :value="id" :label="label"/></el-select></div>
        <article v-for="f in visibleFacts" :key="f.factId" class="fact"><span class="note">{{ labels[f.sectionId] }}</span><details><summary>查看统计依据与具体工单</summary><p>{{ f.display.text }}</p></details><div class="analysis"><p><strong>怎么看：</strong>{{ f.whatItMeans }}</p><p><strong>影响：</strong>{{ f.whyItMatters }}</p><p><strong>下一步：</strong>{{ f.nextStep }}（{{ f.owner }}）</p><p class="note">现在不能下的结论：{{ f.cannotConclude }}</p><UButton color="neutral" variant="outline" @click="openChat(f)">单独追问这条分析</UButton></div></article>
        <p v-if="!visibleFacts.length">当前筛选下暂无已完成解释，可调整筛选或等待排队完成。</p>
        <el-pagination v-model:current-page="page" :page-size="6" :total="facts.length" layout="prev, pager, next, total"/>
      </UCard>
      <p class="note">{{ report.contract.inventoryBoundary }} 本轮只调用本地27B，不调用付费云端补课接口。</p>
    </template>
    <el-dialog :model-value="!!chatFact" title="围绕这条证据继续核对" width="min(720px, 94vw)" @update:model-value="value=>{if(!value)chatFact=null;}"><template v-if="chatFact"><p>{{ chatFact.whatItMeans }}</p><el-input v-model="question" type="textarea" :rows="3" aria-label="本条成本证据追问" placeholder="例如：找谁补资料？为什么还不能认定浪费？"/><UButton color="primary" :loading="asking" @click="ask">提交追问</UButton><p class="chat-answer">{{ answer }}</p><p class="note">基于这条已保存证据回答，不增加模型调用或改变金额。</p></template></el-dialog>
  </main>
</template>
<style scoped>
.complete-cost{padding:24px;display:grid;gap:20px;color:var(--c-ink)}header,.heading{display:flex;justify-content:space-between;align-items:center;gap:16px}h2,h3,h4{margin:0 0 8px}h2{font-size:23px}h3{font-size:17px}h4{font-size:15px}p{line-height:1.8}header p,.note{color:var(--c-muted);font-size:12px}.metrics{display:flex;flex-wrap:wrap;gap:24px}.metrics span,.metrics strong{display:block}.metrics strong{font-size:24px;margin-top:8px}.metrics span{color:var(--c-muted)}.heading .el-select{width:180px}.rank,.filters{display:flex;gap:16px;align-items:center;margin:12px 0}.rank{justify-content:space-between;border-bottom:1px solid var(--c-line);padding:8px 0}.filters .el-select{width:240px}.detail,.fact{border-top:1px solid var(--c-line);padding-top:20px;margin-top:20px}.analysis{padding:16px;margin:16px 0;border:1px solid var(--c-line);border-radius:10px;background:var(--c-page)}.request{padding:12px 0;border-bottom:1px solid var(--c-line)}details p,.chat-answer{white-space:pre-wrap;overflow-wrap:anywhere}.chat-answer{line-height:1.9}.el-input{margin-bottom:12px}@media(max-width:760px){.complete-cost{padding:16px}header,.filters{align-items:stretch;flex-direction:column}.metrics{gap:16px}.metrics strong{font-size:20px}}
</style>
