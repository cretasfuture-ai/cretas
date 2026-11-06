<script setup lang="ts">
import CostDataTable from '@/components/smartbi/CostDataTable.vue';
import ContinuousAnalysisPanel from '@/components/smartbi/ContinuousAnalysisPanel.vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import UCard from '@nuxt/ui/components/Card.vue';
import UButton from '@nuxt/ui/components/Button.vue';
import DynamicChartRenderer from '@/components/smartbi/DynamicChartRenderer.vue';
import { getBlacklakeCompleteCost, followupBlacklakeCompleteCost, type CompleteCostReport, type CompleteCostFact, type CostProject } from '@/api/smartbi/blacklake';
import { useAuthStore } from '@/store/modules/auth';
import { buildUnitCostChart } from './costOverview';
import { beijingDate, formatBlacklakeDateTime } from './blacklakePeriod';
import { localizeBlacklakeText } from './blacklakePresentation';
import { buildProductCostReviews, blacklakeLaborInput } from './productCostReview';
import LaborCostAnalysisPanel from '@/components/smartbi/LaborCostAnalysisPanel.vue';
import { productReviewPriority, productCostDifference, orderUnitCosts } from './factoryCostGuidance';
const report=ref<CompleteCostReport|null>(null),loading=ref(false),error=ref('');
const search=ref(''),unit=ref(''),page=ref(1),cohort=ref('eligible26'),section=ref('');
const selected=ref<CostProject|null>(null),chatFact=ref<CompleteCostFact|null>(null),question=ref(''),answer=ref(''),asking=ref(false);
const selectedProductKey=ref(''),productPage=ref(1);
const orderPage=ref(1),pageSize=ref(20),product=ref(''),attention=ref('all'),dateMode=ref('recent'),startDate=ref(''),endDate=ref('');
const auth=useAuthStore();let generation=0;let timer:number|undefined;
const identity=computed(()=>[auth.factoryId,auth.user?.id,auth.currentRole].join(':'));
const fmt=(value:number|null|undefined)=>value==null?'未提供':value.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const costChange=(value:number)=>Math.abs(value)<0.005?'基本相同':`${value>0?'多':'少'} ${fmt(Math.abs(value))}`;
const reviewStatusLabels={data_quality:'先核资料',business_signal:'成本差异待核对',readiness:'比较条件待补',none:'暂无重点提醒'};
const labels:Record<string,string>={coverage:'覆盖与数据条件',material:'原料投入',labor:'工序人工',finished:'成品产出',product_cost:'产品及工单成本',production:'生产与工序',inventory:'库存与批次'};
function stop(){if(timer!==undefined)window.clearTimeout(timer);timer=undefined;}
async function load(){
  stop();const epoch=++generation;loading.value=true;error.value='';
  try{
    const next=await getBlacklakeCompleteCost();if(epoch!==generation)return;
    report.value=next;
    if(next.contract.live&&cohort.value==='eligible26')cohort.value='eligible_live';
    if(selected.value)selected.value=next.analysis.cost.projects.find(r=>r.projectId===selected.value?.projectId)||null;
    if(chatFact.value)chatFact.value=next.batchAnalysis.results.find(r=>r.factId===chatFact.value?.factId)||null;
    if(!units.value.includes(unit.value))unit.value=units.value[0]||'';
    if(next.batchAnalysis.status!=='complete')timer=window.setTimeout(():void=>{void load();},15000);
  }catch(cause){if(epoch===generation){report.value=null;selectedProductKey.value='';selected.value=null;chatFact.value=null;answer.value='';error.value=cause instanceof Error?cause.message:'成本分析暂不可用';}}
  finally{if(epoch===generation)loading.value=false;}
}
onMounted(():void=>{void load();});
watch(identity,()=>{generation++;report.value=null;selectedProductKey.value='';selected.value=null;chatFact.value=null;answer.value='';void load();});
onBeforeUnmount(()=>{generation++;stop();});
watch([cohort,section,search],()=>{page.value=1;});
const units=computed(()=>[...new Set((report.value?.analysis.cost.projects||[]).map(r=>r.unit).filter((u):u is string=>!!u))]);
const allOrders=computed(()=>report.value?.analysis.cost.projects||[]);
const orderDate=(id:number)=>{const date=report.value?.analysis.cost.projectDates?.[String(id)]?.completedAt;return date&&!Number.isNaN(new Date(date).getTime())?beijingDate(new Date(date)):'';};
const latestDate=computed(()=>allOrders.value.map(r=>orderDate(r.projectId)).filter(Boolean).sort().at(-1)||'');
const range=computed(()=>{if(dateMode.value==='all'||dateMode.value==='undated')return null;if(dateMode.value==='custom')return[startDate.value,endDate.value];if(!latestDate.value)return null;const d=new Date(`${latestDate.value}T12:00:00Z`);d.setUTCDate(d.getUTCDate()-6);return[d.toISOString().slice(0,10),latestDate.value];});
watch(dateMode,value=>{if(value==='custom'&&latestDate.value&&!startDate.value&&!endDate.value){const d=new Date(latestDate.value+'T12:00:00Z');d.setUTCDate(d.getUTCDate()-6);startDate.value=d.toISOString().slice(0,10);endDate.value=latestDate.value;}});
const validDate=(v:string)=>/^\d{4}-\d{2}-\d{2}$/.test(v)&&!Number.isNaN(new Date(v+'T12:00:00Z').getTime())&&new Date(v+'T12:00:00Z').toISOString().slice(0,10)===v;
const dateError=computed(()=>dateMode.value==='custom'&&(!validDate(startDate.value)||!validDate(endDate.value)||startDate.value>endDate.value));
const products=computed(()=>[...new Set(allOrders.value.map(r=>r.product))].sort());
const orders=computed(()=>allOrders.value.filter(r=>{const date=orderDate(r.projectId);return !dateError.value&&(!product.value||r.product===product.value)&&(dateMode.value==='undated'?!date:!range.value||(!!date&&date>=range.value[0]!&&date<=range.value[1]!));}).sort((a,b)=>alertsFor(b.projectId).filter(x=>x.family!=='readiness').length-alertsFor(a.projectId).filter(x=>x.family!=='readiness').length||orderDate(b.projectId).localeCompare(orderDate(a.projectId))));
const productReviews=computed(()=>buildProductCostReviews(orders.value,report.value?.analysis.costAlerts?.items||[]).filter(group=>(attention.value==='all'||productReviewPriority(group)===attention.value)&&`${group.product} ${group.productCode||''} ${group.orders.map(r=>r.projectCode).join(' ')}`.toLowerCase().includes(search.value.toLowerCase())));
const visibleProducts=computed(()=>productReviews.value.slice((productPage.value-1)*pageSize.value,productPage.value*pageSize.value));
const activeProduct=computed(()=>productReviews.value.find(row=>row.key===selectedProductKey.value));
const productDifference=computed(()=>activeProduct.value?productCostDifference(activeProduct.value):null);
const selectedUnitCosts=computed(()=>selected.value?orderUnitCosts(selected.value):null);
const dataFirstCount=computed(()=>productReviews.value.filter(g=>productReviewPriority(g)==='data_quality').length);
const costReviewCount=computed(()=>productReviews.value.filter(g=>productReviewPriority(g)==='business_signal').length);
const productLabor=computed(()=>blacklakeLaborInput(activeProduct.value?.orders||[],report.value?.sourceRunId));
const selectedLabor=computed(()=>blacklakeLaborInput(selected.value?[selected.value]:[],report.value?.sourceRunId));
const productOrders=computed(()=>activeProduct.value?.orders||[]);
const visibleOrders=computed(()=>productOrders.value.slice((orderPage.value-1)*pageSize.value,orderPage.value*pageSize.value));
watch(selectedProductKey,()=>{orderPage.value=1;selected.value=null;chatFact.value=null;answer.value='';});
watch(()=>activeProduct.value,()=>{if(selected.value&&!productOrders.value.some(row=>row.projectId===selected.value?.projectId)){selected.value=null;chatFact.value=null;}});
function openProduct(key:string){selectedProductKey.value=key;void nextTick(()=>document.querySelector('[data-testid="selected-product-analysis"]')?.scrollIntoView?.({block:'start'}));}
const focusedOrders=computed(()=>activeProduct.value?productOrders.value:productReviews.value.flatMap(g=>g.orders));
const filteredUnits=computed(()=>[...new Set(focusedOrders.value.map(r=>r.unit).filter((u):u is string=>!!u))]);
watch(filteredUnits,value=>{if(!value.includes(unit.value))unit.value=value[0]||'';});
watch([search,product,attention,dateMode,startDate,endDate],()=>{orderPage.value=1;productPage.value=1;selectedProductKey.value='';selected.value=null;chatFact.value=null;});
watch(()=>productOrders.value.length,()=>{orderPage.value=Math.min(orderPage.value,Math.max(1,Math.ceil(productOrders.value.length/pageSize.value)));});
watch(pageSize,()=>{orderPage.value=1;productPage.value=1;});
watch(()=>productReviews.value.length,()=>{productPage.value=Math.min(productPage.value,Math.max(1,Math.ceil(productReviews.value.length/pageSize.value)));});
const groups=computed(()=>productReviews.value.filter(g=>g.unit===unit.value).sort((a,b)=>b.weightedUnitCost-a.weightedUnitCost));
const activeAlerts=computed(()=>(report.value?.analysis.costAlerts?.items||[]).filter(a=>a.entityRefs.some(p=>focusedOrders.value.some(r=>r.projectId===p.projectId))));
const countFamily=(family:string)=>activeAlerts.value.filter(a=>a.family===family).length;
const actionItems=computed(()=>activeAlerts.value.filter(a=>a.family!=='readiness').sort((a,b)=>Number(b.family==='data_quality')-Number(a.family==='data_quality')||Number(b.blocksUnitCost)-Number(a.blocksUnitCost)).slice(0,4));
async function locate(id:number){const group=productReviews.value.find(g=>g.orders.some(r=>r.projectId===id));if(!group)return;openProduct(group.key);await nextTick();selected.value=productOrders.value.find(row=>row.projectId===id)||null;}
const productAlertCount=(key:string)=>productReviews.value.find(g=>g.key===key)?.alerts.filter(a=>a.family!=='readiness').length||0;
const chart=computed(()=>buildUnitCostChart(groups.value));
const facts=computed(()=>(report.value?.batchAnalysis.results||[]).filter(r=>r.kind!=='work_order_detail'&&r.cohort===cohort.value&&(!section.value||r.sectionId===section.value)));
const visibleFacts=computed(()=>facts.value.slice((page.value-1)*6,page.value*6));
const completedOrders=computed(()=>(report.value?.batchAnalysis.results||[]).filter(r=>r.kind==='work_order_detail').length);
const alertsFor=(id:number)=>(report.value?.analysis.costAlerts?.items||[]).filter(a=>a.entityRefs.some(p=>p.projectId===id));
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
    <header><div><h2>成品 / 半成品成本分析</h2><p>先看产品成本，再找到影响成本的工单。</p></div><UButton color="neutral" variant="outline" :loading="loading" @click="load">刷新</UButton></header>
    <ContinuousAnalysisPanel @data-updated="load" />
    <el-alert v-if="error" type="warning" :closable="false" :title="error"/>
    <p v-if="loading&&!report" role="status">正在读取成本…</p>
    <template v-if="report">
      <details v-if="report.analysis.sourceGaps?.length" class="technical"><summary>来源待核：{{report.analysis.sourceGaps.length}} 张此前工单本轮未返回，历史已保留</summary><p>本轮统计只计当前可见工单，不将未返回记录直接认定为已删除。</p><p v-for="gap in report.analysis.sourceGaps" :key="gap.id"><b>{{gap.projectCode}}</b> · 找{{gap.owner}}：{{gap.action}} 补齐后：{{gap.afterProvided}}</p></details>
      <UCard><div class="filters filter-grid">
        <label>完工时间<el-select v-model="dateMode" aria-label="工单完工时间"><el-option value="recent" label="最近有数据的7天"/><el-option value="custom" label="自定义日期"/><el-option value="all" label="全部已采集"/><el-option value="undated" label="缺少完工日期"/></el-select></label>
        <label>产品<el-select v-model="product" clearable filterable placeholder="全部产品" aria-label="筛选成本产品"><el-option v-for="name in products" :key="name" :value="name" :label="name"/></el-select></label>
        <label>关注事项<el-select v-model="attention" aria-label="筛选核对事项"><el-option value="all" label="全部工单"/><el-option value="data_quality" label="先核对资料"/><el-option value="business_signal" label="再核对成本差异"/><el-option value="readiness" label="比较条件待补"/></el-select></label>
        <label>查找产品<el-input v-model="search" aria-label="搜索最新成本工单" placeholder="产品名称或工单号" clearable/></label>
      </div>
      <div v-if="dateMode==='custom'" class="filters"><label>开始日期<input v-model="startDate" type="date" aria-label="复核开始日期"></label><label>结束日期<input v-model="endDate" type="date" aria-label="复核结束日期"></label></div>
      <p v-if="dateError" role="alert">请选择有效的开始与结束日期。</p>
      <p class="note">{{ range ? `${range[0]?.slice(5)} 至 ${range[1]?.slice(5)}` : dateMode==='undated'?'缺少完工日期的工单':'全部已采集工单' }} · {{ focusedOrders.length }} 张工单 · 材料＋人工，暂不含制造费用；币种待确认。</p>
      <p v-if="!latestDate&&dateMode==='recent'" class="note">来源未提供完工日期，暂列已采集工单，请先补齐日期。</p>
      </UCard>
      <div v-if="!activeProduct" class="metrics"><UCard><span>成品 / 半成品</span><strong>{{ productReviews.length }} 项</strong></UCard><UCard><span>本页可核算工单</span><strong>{{ focusedOrders.length }} 张</strong></UCard><UCard><span>先核对资料的产品</span><strong>{{ dataFirstCount }} 项</strong></UCard><UCard><span>再核对成本差异的产品</span><strong>{{ costReviewCount }} 项</strong></UCard></div>
      <section v-if="!activeProduct" class="factory-guide" aria-label="成本核对引导">
        <h3>先看哪里</h3>
        <p v-if="dataFirstCount">先处理 {{ dataFirstCount }} 项产品的资料问题，再判断它们的成本是否真的异常。</p>
        <p v-else-if="costReviewCount">有 {{ costReviewCount }} 项产品需要核对成本差异，点开产品，再对照具体工单。</p>
        <p v-else-if="productReviews.length">当前筛选暂未发现重点提醒。点开产品，可以核对材料、人工和相关工单。</p>
        <p v-else>当前筛选没有可核算工单，先调整日期或筛选条件；缺资料的工单请到「关联与估值补查」核对。</p>
        <div class="filters"><UButton v-if="dataFirstCount" color="primary" variant="outline" @click="attention='data_quality'">查看资料问题</UButton><UButton v-if="costReviewCount" color="neutral" variant="outline" @click="attention='business_signal'">查看成本差异</UButton><UButton v-if="attention!=='all'" color="neutral" variant="ghost" @click="attention='all'">返回全部产品</UButton></div>
        <p class="note">本页只看材料与人工可计算的工单，不代表全厂全部成本；同一产品先归到最需要处理的一类。</p>
      </section>
      <UCard v-if="!activeProduct" data-testid="product-cost-list"><template #header><div class="heading"><h3>成品 / 半成品成本总览</h3><span class="note">{{ productReviews.length }} 项产品 · 点击查看成本分析和相关工单</span></div></template>
        <CostDataTable :rows="productReviews" :columns="[{key:'product',label:'成品 / 半成品',type:'text',minWidth:200},{key:'unit',label:'单位',type:'select',width:80},{key:'sampleCount',label:'工单数',type:'number',count:true,width:85},{key:'finishedGood',label:'产出合计',type:'number',minWidth:125},{key:'materialCost',label:'材料费',type:'number',minWidth:110},{key:'laborCost',label:'人工费',type:'number',minWidth:105},{key:'weightedUnitCost',label:'平均单位成本',type:'number',minWidth:135},{key:'_alerts',label:'需要关注',type:'select',value:row=>reviewStatusLabels[productReviewPriority(row)],minWidth:140},{key:'key',label:'操作',type:'text',width:145,sortable:false,filterable:false}]" paginate   row-key="key" stripe @row-click="openProduct($event.key)"><template #sampleCount="{row}">{{ row.sampleCount }}</template><template #finishedGood="{row}">{{ fmt(row.finishedGood) }} {{ row.unit }}</template><template #materialCost="{row}">{{ fmt(row.materialCost) }}</template><template #laborCost="{row}">{{ fmt(row.laborCost) }}</template><template #weightedUnitCost="{row}">{{ fmt(row.weightedUnitCost) }} / {{ row.unit }}</template><template #_alerts="{row}"><el-tag v-if="productReviewPriority(row)!=='none'" type="warning">{{ reviewStatusLabels[productReviewPriority(row)] }}</el-tag><span v-else>暂无重点提醒</span></template><template #key="{row}"><UButton color="primary" variant="outline" @click.stop="openProduct(row.key)">查看产品分析</UButton></template></CostDataTable>

      </UCard>
      <details v-if="!activeProduct" class="technical"><summary>按单位比较产品成本</summary>
      <UCard><template #header><div class="heading"><h3>哪些产品成本高</h3><el-select v-model="unit" aria-label="最新成本成品单位"><el-option v-for="u in units" :key="u" :value="u" :label="u"/></el-select></div></template>
        <DynamicChartRenderer v-if="groups.length" :config="chart" :height="280" :number-precision="2" presentation="plain"/>
        <el-empty v-else description="当前筛选没有可比较的产品"/>
        <p class="note">按每{{ unit || '单位' }}成品的材料与人工排序。规格、加工阶段不同的产品不能直接比高低。</p>
      </UCard>
      </details>
      <template v-if="activeProduct">
        <div class="product-breadcrumb"><UButton color="neutral" variant="outline" @click="selectedProductKey=''">返回全部产品</UButton><span>成品 / 半成品</span><span aria-hidden="true"> / </span><strong>{{ activeProduct.product }}</strong></div>
        <UCard data-testid="selected-product-analysis"><template #header><div><h3>{{ activeProduct.product }} · 产品成本分析</h3><p class="note">{{ activeProduct.displayIdentity }} · 本次日期范围内 {{ productOrders.length }} 张工单</p></div></template>
          <div class="order-summary"><div><span>平均单位成本</span><strong>{{ fmt(activeProduct.weightedUnitCost) }} / {{ activeProduct.unit }}</strong></div><div><span>产出合计</span><strong>{{ fmt(activeProduct.finishedGood) }} {{ activeProduct.unit }}</strong></div><div><span>材料费合计</span><strong>{{ fmt(activeProduct.materialCost) }}</strong></div><div><span>人工费合计</span><strong>{{ fmt(activeProduct.laborCost) }}</strong></div></div>
          <div class="analysis"><h4>这个产品的成本怎么样</h4><p>每{{ activeProduct.unit }}的平均成本为 {{ fmt(activeProduct.weightedUnitCost) }}，其中材料 {{ fmt(activeProduct.weightedMaterialUnitCost) }}、人工 {{ fmt(activeProduct.weightedLaborUnitCost) }}。</p><p v-if="activeProduct.sampleCount===1">这段时间只有一张工单，先看本单材料与人工，暂时无法比较不同工单的成本。</p><p v-if="activeProduct.identityConflict" class="warning">这个产品编码还出现过其他名称或加工阶段，已分开显示；请生产或主数据负责人核实，确认前不要合并比较。</p><details><summary>统计说明</summary><p class="note">平均单位成本按材料费与人工费合计除以总产出计算；成品与半成品各看各的，不把上下游成本再相加。</p></details></div>
          <section v-if="productDifference&&productDifference.delta>=0.005" class="factory-guide" aria-label="同产品工单成本差额">
            <h4>差额记在哪里</h4>
            <p>本期较高工单 {{ productDifference.higher.projectCode }}，每{{ activeProduct.unit }}成本 {{ fmt(productDifference.higherCost) }}；较低工单 {{ productDifference.lower.projectCode }}为 {{ fmt(productDifference.lowerCost) }}，相差 {{ fmt(productDifference.delta) }}。</p>
            <p>较高工单每{{ activeProduct.unit }}的材料费{{ costChange(productDifference.materialDelta) }}，人工费{{ costChange(productDifference.laborDelta) }}。</p>
            <p>{{ productDifference.needsDataReview?'先处理下方的资料问题，再判断两张工单的差额是否真实。':productDifference.action }}</p>
            <div class="filters"><UButton color="primary" variant="outline" @click="selected=productDifference.higher">核对较高成本工单</UButton><UButton color="neutral" variant="outline" @click="selected=productDifference.lower">对照较低成本工单</UButton></div>
            <p class="note">这是同一产品两张工单的账面差额，不等于成本随时间上涨，也不等于已经查明原因。</p>
          </section>
          <details><summary>材料花在哪里</summary><CostDataTable :rows="activeProduct.materials" :columns="[{key:'material',label:'原料 / 半成品',type:'text'},{key:'quantity',label:'净耗用',type:'number'},{key:'amount',label:'材料金额',type:'number'}]" paginate   stripe><template #quantity="{row}">{{ fmt(row.quantity) }} {{ row.unit }}</template><template #amount="{row}">{{ fmt(row.amount) }}</template></CostDataTable></details>
          <details><summary>人工与工序成本 · 分摊、人均和待补资料</summary><LaborCostAnalysisPanel :input="productLabor" :scope-label="`${activeProduct.product} · ${productOrders.length} 张工单`"/></details>
        </UCard>
      <UCard v-if="activeProduct && actionItems.length"><template #header><h3>优先处理</h3></template><article v-for="item in actionItems" :key="item.id" class="action-row"><div><strong>{{ localizeBlacklakeText(item.title) }}</strong><details><summary>问题依据</summary><p>{{ localizeBlacklakeText(item.message) }}</p></details><p v-if="item.entityRefs.some(p=>!productOrders.some(r=>r.projectId===p.projectId))" class="note">此对照还包含当前产品或日期范围外的工单。</p><p>找{{ item.owner }}：{{ localizeBlacklakeText(item.action) }}</p></div><UButton color="neutral" variant="outline" @click="locate(item.entityRefs.find(p=>productOrders.some(r=>r.projectId===p.projectId))!.projectId)">核对工单</UButton></article></UCard>
      <UCard><template #header><div class="heading"><h3>{{ activeProduct.product }} · 相关工单</h3><span class="note">共 {{ productOrders.length }} 张 · 点击工单查看对应分析</span></div></template>
        <CostDataTable :rows="productOrders" :columns="[{key:'projectCode',label:'工单',type:'text',minWidth:180},{key:'product',label:'成品',type:'text',minWidth:180},{key:'_completed',label:'完工日期',type:'date',value:row=>orderDate(row.projectId),width:110},{key:'finishedGood',label:'成品量',type:'number',minWidth:120},{key:'unitIdentifiedDirectCost',label:'单位成本',type:'number',minWidth:130},{key:'_alerts',label:'需要关注',type:'number',value:row=>alertsFor(row.projectId).filter(a=>a.family!=='readiness').length,count:true,minWidth:150},{key:'col6',label:'操作',type:'text',width:140,sortable:false,filterable:false}]" paginate   row-key="projectId" stripe @row-click="selected=$event" data-testid="review-order-table"><template #_completed="{row}">{{ orderDate(row.projectId).slice(5)||'待补' }}</template><template #finishedGood="{row}">{{ fmt(row.finishedGood) }} {{ row.unit }}</template><template #unitIdentifiedDirectCost="{row}">{{ fmt(row.unitIdentifiedDirectCost) }} / {{ row.unit }}</template><template #_alerts="{row}"><el-tag v-if="alertsFor(row.projectId).some(a=>a.family==='data_quality')" type="warning">数据待核对</el-tag><el-tag v-if="alertsFor(row.projectId).some(a=>a.family==='business_signal')" type="warning">成本待核查</el-tag><span v-if="!alertsFor(row.projectId).some(a=>a.family!=='readiness')">暂无重点提醒</span></template><template #col6="{row}"><UButton color="neutral" variant="outline" @click.stop="selected=row">查看成本与分析</UButton></template></CostDataTable>

      </UCard>
      </template>
      <details class="technical"><summary>查看其他专项分析与数据说明</summary>
        <p class="note">资料更新 {{ formatBlacklakeDateTime(report.sourceCheckedAt) }} · 以下专项分析使用完整采集范围，不随上方工单筛选变化。</p>
        <p role="status">{{ report.contract.live?'规则核对已更新，本地AI解读与用量见上方持续采集报告':report.batchAnalysis.status==='complete'?'分析已完成':report.batchAnalysis.status==='failed'?'部分分析暂不可用，正在等待恢复':'分析排队中，已完成的工单可先查看' }}</p>
        <p v-if="report.contract.live" class="note">本期逐单规则核对 {{ completedOrders }} / {{ report.contract.eligibleOrders }}；历史补查 {{ report.batchAnalysis.cohorts.original44?.analyzed ?? 0 }} / {{report.contract.originalRemainingFacts}}。本地AI解读与用量在上方持续采集报告中查看。</p><p v-else class="note">逐单分析 {{ completedOrders }} / {{ report.contract.eligibleOrders }}；补查 {{ report.batchAnalysis.cohorts.original44?.analyzed ?? 0 }} / 44；模型用量 {{ report.batchAnalysis.usage?.totalTokens?.toLocaleString() ?? '未返回' }} token。</p>
        <div class="filters"><el-select v-model="cohort" aria-label="证据分析范围"><el-option :value="report.contract.live?'eligible_live':'eligible26'" label="可核算工单专项分析"/><el-option value="original44" label="原109张工单的历史补查分析"/></el-select><el-select v-model="section" aria-label="分析板块" clearable placeholder="全部板块"><el-option v-for="(label,id) in labels" :key="id" :value="id" :label="label"/></el-select></div>
        <p class="note">{{ cohort==='original44'?'以下来自原109张工单，不代表上方筛选工单。':'以下来自本轮全部可核算工单。' }}</p>
        <details v-for="f in visibleFacts" :key="f.factId" class="fact"><summary>{{ labels[f.sectionId] }} · {{ localizeBlacklakeText(f.whatItMeans) }}</summary><p>{{ localizeBlacklakeText(f.whyItMatters) }}</p><p>怎么处理：{{ localizeBlacklakeText(f.nextStep) }}（{{ f.owner }}）</p><p>{{ localizeBlacklakeText(f.display.text) }}</p><p class="note">{{ localizeBlacklakeText(f.cannotConclude) }}</p><UButton color="neutral" variant="outline" @click="openChat(f)">追问这项分析</UButton></details>
        <el-pagination v-model:current-page="page" :page-size="6" :total="facts.length" layout="prev, pager, next, total"/>
      </details>
      <el-drawer :model-value="!!selected" size="min(960px, 100%)" :title="selected ? `${selected.projectCode} · ${selected.product}` : '工单成本'" @update:model-value="(value:boolean)=>{if(!value)selected=null;}">
        <section v-if="selected" class="detail" aria-label="最新工单成本明细">
          <div class="order-summary"><div><span>单位成本</span><strong>{{ fmt(selected.unitIdentifiedDirectCost) }} / {{ selected.unit }}</strong></div><div><span>材料净额</span><strong>{{ fmt(selected.materialCost) }}</strong></div><div><span>工序人工</span><strong>{{ fmt(selected.laborCost) }}</strong></div><div><span>成品良品</span><strong>{{ fmt(selected.finishedGood) }} {{ selected.unit }}</strong></div></div>
          <div class="analysis"><h3>这张工单怎么看</h3><p v-if="selectedUnitCosts">每{{ selected.unit }}成品，材料费 {{ fmt(selectedUnitCosts.material) }}、人工费 {{ fmt(selectedUnitCosts.labor) }}，合计 {{ fmt(selectedUnitCosts.total) }}。先对照下方材料单据和工序明细。</p><template v-if="orderFact"><h4>先核对什么</h4><p>{{ localizeBlacklakeText(orderFact.nextStep) }}</p><p class="note">找谁处理：{{ orderFact.owner }}</p><UButton color="primary" variant="outline" @click="openChat(orderFact)">追问这张工单</UButton><details><summary>完整分析与依据</summary><p>{{ localizeBlacklakeText(orderFact.whatItMeans) }}</p><p>{{ localizeBlacklakeText(orderFact.whyItMatters) }}</p><p>{{ localizeBlacklakeText(orderFact.display.text) }}</p><p>{{ localizeBlacklakeText(orderFact.cannotConclude) }}</p><p v-for="hypothesis in orderFact.hypotheses" :key="hypothesis">可能原因（待核实）：{{ localizeBlacklakeText(hypothesis) }}</p></details></template><p v-else>这张工单的解释仍在排队，成本数据可以先核对。</p></div>
          <h3>需要处理什么</h3><article v-for="a in selectedAlerts.filter(a=>a.family!=='readiness')" :key="a.id" class="request"><strong>{{ localizeBlacklakeText(a.title) }}</strong><p>{{ localizeBlacklakeText(a.message) }}</p><p>找{{ a.owner }}：{{ localizeBlacklakeText(a.action) }}</p></article><p v-if="!selectedAlerts.some(a=>a.family!=='readiness')">暂未发现重点问题。</p>
          <details v-if="needs.length||selectedAlerts.some(a=>a.family==='readiness')"><summary>比较前还需要确认的资料</summary><article v-for="n in needs" :key="n.id" class="request"><strong>{{ localizeBlacklakeText(n.missingData) }}</strong><p>找{{ n.responsibleRole }}补齐。补齐后：{{ localizeBlacklakeText(n.afterProvided) }}</p></article><p v-for="a in selectedAlerts.filter(a=>a.family==='readiness')" :key="a.id">{{ localizeBlacklakeText(a.message) }} 找{{ a.owner }}：{{ localizeBlacklakeText(a.action) }}</p></details>
          <h3>原料 → 成品：材料花在哪里</h3><CostDataTable :rows="selected.materialLinesDetail" :columns="[{key:'material',label:'材料',type:'text'},{key:'orderCode',label:'领退料单',type:'text'},{key:'netConsumption',label:'净耗用',type:'number'},{key:'recognizedCost',label:'金额',type:'number'}]" paginate   stripe><template #netConsumption="{row}">{{ fmt(row.netConsumption) }} {{ row.unit }}</template><template #recognizedCost="{row}">{{ fmt(row.recognizedCost) }}</template></CostDataTable>
          <LaborCostAnalysisPanel :input="selectedLabor" :scope-label="`${selected.projectCode} · ${selected.product}`"/>
        </section>
      </el-drawer>
    </template>
    <el-dialog :model-value="!!chatFact" :title="selected ? `追问 ${selected.projectCode}` : '追问这项分析'" width="min(720px, 94vw)" @update:model-value="(value:boolean)=>{if(!value)chatFact=null;}"><template v-if="chatFact"><p>{{ localizeBlacklakeText(chatFact.whatItMeans) }}</p><el-input v-model="question" type="textarea" :rows="3" aria-label="本条成本证据追问" placeholder="想核对哪里？例如：找谁补资料、人工为什么高？"/><UButton color="primary" :loading="asking" @click="ask">发送</UButton><p v-if="answer" class="chat-answer">{{ localizeBlacklakeText(answer) }}</p></template></el-dialog>
  </main>
</template>
<style scoped>
.complete-cost{padding:24px;display:grid;gap:20px;color:var(--c-ink)}header,.heading{display:flex;justify-content:space-between;align-items:center;gap:16px}h2,h3,h4{margin:0 0 8px}h2{font-size:23px}h3{font-size:17px}h4{font-size:15px}p{line-height:1.8}header p,.note{color:var(--c-muted);font-size:12px}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}.metrics span,.metrics strong{display:block}.metrics strong{font-size:24px;margin-top:8px}.metrics span{color:var(--c-muted)}.heading .el-select{width:180px}.rank,.filters{display:flex;gap:16px;align-items:center;margin:12px 0}.rank{justify-content:space-between;border-bottom:1px solid var(--c-line);padding:8px 0}.filters .el-select{width:240px}.detail,.fact{border-top:1px solid var(--c-line);padding-top:20px;margin-top:20px}.analysis{padding:16px;margin:16px 0;border:1px solid var(--c-line);border-radius:10px;background:var(--c-page)}.request{padding:12px 0;border-bottom:1px solid var(--c-line)}details p,.chat-answer{white-space:pre-wrap;overflow-wrap:anywhere}.chat-answer{line-height:1.9}.el-input{margin-bottom:12px}@media(max-width:760px){.complete-cost{padding:16px}header,.filters{align-items:stretch;flex-direction:column}.metrics{gap:16px}.metrics strong{font-size:20px}}
.filter-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));align-items:start}.filter-grid label{min-width:0;display:grid;gap:8px;font-size:12px;color:var(--c-muted)}.filter-grid .el-select{width:100%}.action-row{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:12px 0;border-top:1px solid var(--c-line)}.action-row p{margin:4px 0;font-size:13px}.technical{padding:16px;border:1px solid var(--c-line);border-radius:10px}.technical>summary,details>summary{cursor:pointer;color:var(--c-primary,#1b65a8);font-size:13px}.el-pagination{margin-top:20px;overflow:auto}.order-summary{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.order-summary span,.order-summary strong{display:block}.order-summary span{font-size:12px;color:var(--c-muted)}.order-summary strong{font-size:21px;margin-top:6px}.detail{margin:0;padding:0;border:0}.detail>h3{margin-top:28px}.detail details{margin:16px 0}.filters input[type=date]{padding:8px;border:1px solid var(--c-line);border-radius:6px}@media(max-width:760px){.filter-grid,.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.action-row{align-items:stretch;flex-direction:column}}@media(max-width:440px){.filter-grid{grid-template-columns:1fr}}
.product-breadcrumb{display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:13px;color:var(--c-muted)}[data-testid="selected-product-analysis"] details{padding:14px 0;border-top:1px solid var(--c-line)}.warning{color:#a85d16}.product-breadcrumb strong{color:var(--c-ink)}
.factory-guide{padding:16px 18px;background:var(--c-surface,#fff);border:1px solid var(--c-line,#edf2f7);border-radius:10px;margin:14px 0}.factory-guide h3,.factory-guide h4{margin:0 0 8px}.factory-guide p{margin:8px 0;line-height:1.7}
</style>
