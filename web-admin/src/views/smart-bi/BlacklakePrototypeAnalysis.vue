<script setup lang="ts">
import CostDataTable from '@/components/smartbi/CostDataTable.vue';
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue';
import { createBlacklakePeriodReport, getBlacklakeAnalysis, getBlacklakePeriodReport, getBlacklakePeriodSchedule, listBlacklakePeriodReports, type AlertFollowup, type BlacklakeAnalysis, type BlacklakePeriodJob, type BlacklakePeriodQuery, type BlacklakePeriodSchedule, type CostDataAlert, type CostProject, type SectionInsightReport } from '@/api/smartbi/blacklake';
import DynamicChartRenderer from '@/components/smartbi/DynamicChartRenderer.vue';
import SectionInsight from '@/components/smartbi/PrototypeSectionInsight.vue';
import BlacklakeCostIntelligence from './BlacklakePrototypeCostIntelligence.vue';
import BlacklakeCostAlerts from './BlacklakePrototypeCostAlerts.vue';
import type { DynamicChartConfig } from '@/types/smartbi';
import UCard from '@nuxt/ui/components/Card.vue';
import UButton from '@nuxt/ui/components/Button.vue';
import UBadge from '@nuxt/ui/components/Badge.vue';
import { buildCostOverview, buildUnitCostChart, groupEligibleCostProjects } from './costOverview';
import { formatBlacklakeNumber, localizeBlacklakeText } from './blacklakePresentation';
import BlacklakePeriodControls from './BlacklakePeriodControls.vue';
import BlacklakePeriodAnalysis from './BlacklakePeriodAnalysis.vue';
import { beijingDate, formatBlacklakeDateTime, periodForKind, periodIdentity } from './blacklakePeriod';
import OperationsReviewPanel from '@/components/smartbi/OperationsReviewPanel.vue';
import {reviewProduction,reviewInventory,reviewPeriod,type OperationsFinding} from './operationsGuidance';
import { useAuthStore } from '@/store/modules/auth';

const props = defineProps<{ activeView?: string }>();
const emit = defineEmits<{ (event: 'update:activeView', value: string): void }>();
const data = shallowRef<BlacklakeAnalysis | null>(null);
const refreshEpoch=ref(0);
const loading = ref(false);
const error = ref('');
const tab = ref('cost');
const search = ref('');
const orderPage=ref(1);
const pageSize=ref(20);
const issuesOnly = ref(false);
const eligibleOnly = ref(true);
const selected = ref<CostProject | null>(null);
const overviewUnit = ref('');
const focusedSection = ref('');
const focusedAlert = ref<CostDataAlert | null>(null);
const openRequest = ref(0);
const comparisonOpen=ref(false);
const productionFocus=ref<{kind:'product'|'process';name:string;key:string}|null>(null);
const productionOrders=computed(()=>{
  const focus=productionFocus.value;if(!focus||!productionReview.value)return [];
  const source=focus.kind==='product'?productionReview.value.plans.find(p=>p.reviewKey===focus.key):productionReview.value.processes.find(p=>p.reviewKey===focus.key);
  const ids=source&&('orderIds' in source?source.orderIds:source.projectIds)||[];
  return (data.value?.cost.projects||[]).filter(row=>ids.some(id=>String(id)===String(row.projectId)));
});
const planFocus=ref<string[]>([]),processFocus=ref<string[]>([]),qualityFocus=ref<string[]>([]),inventoryFocus=ref<string[]>([]),periodFocus=ref<string[]>([]);
const operationalQuestion=ref('');
const operationalContext=ref('');
watch(()=>data.value?.runId,()=>{planFocus.value=[];processFocus.value=[];qualityFocus.value=[];inventoryFocus.value=[];periodFocus.value=[];operationalQuestion.value='';});
watch(()=>data.value?.runId,()=>{productionFocus.value=null;});
const periodQuery = ref<BlacklakePeriodQuery | null>(periodForKind('day'));
const periodJob = ref<BlacklakePeriodJob | null>(null);
const periodHistory = ref<BlacklakePeriodJob[]>([]);
const periodSchedule = ref<BlacklakePeriodSchedule | null>(null);
const periodError = ref('');
const periodLoading = ref(false);
const periodRequestGeneration = ref(0);
const lastAppliedPeriodResultKey = ref('');
const historyDrawerJob = ref<BlacklakePeriodJob | null>(null);
const periodAuxError = ref('');
const mounted = ref(false);
let initialDateResolved=false;
const authStore = useAuthStore();
const authIdentity = computed(() => [authStore.factoryId, authStore.user?.id ?? '', authStore.currentRole].join(':'));
let periodPollTimer: number | undefined;
function toggleComparison(event:Event){comparisonOpen.value=(event.target as HTMLDetailsElement).open;}
const viewTitles:Record<string,string>={cost:'原料到成品成本分析',alerts:'异常与跟进',gaps:'关联与估值补查',production:'生产与工序',inventory:'库存与仓库',coverage:'数据覆盖与口径'};
const viewDescriptions:Record<string,string>={cost:'看本期材料与人工成本，找到需要核对的工单。',alerts:'先看问题、责任人和处理进度，再回到工单核对。',gaps:'逐单看缺什么、找谁补，以及补齐后能解决什么问题。',production:'先看产品产量和工序进度，再查相关工单。',inventory:'按物料和仓库核对库存，没有记录的先补资料。',coverage:'看哪些资料已经齐备，哪些还影响生产和成本分析。'};
function updateProgress(value:AlertFollowup){if(data.value)data.value={...data.value,alertFollowups:{...data.value.alertFollowups,[value.alertId]:value}};}
const verifiedReferences = computed(() => selected.value ? data.value?.costIntelligence.verifiedMaterialReferences[selected.value.projectCode] || [] : []);
const unit = ref('');
const inventoryUnit = ref('');
watch(inventoryUnit,()=>{inventoryFocus.value=[];});
const fmt = (value: number | null | undefined) => formatBlacklakeNumber(value);
const statusName = (value: string) => ({ '0': '未开始', '10': '进行中', '20': '已完工', '30': '已取消' }[value] || `状态 ${value}`);
const filtered = computed(() => (data.value?.cost.projects || []).filter(p =>
  (!periodFocus.value.length||periodFocus.value.includes(String(p.projectId))) &&
  (!eligibleOnly.value || p.unitCostAvailable) &&
  (!issuesOnly.value || p.issues.length > 0 || projectAlerts(p.projectId).length > 0) && `${p.projectCode} ${p.product}`.toLowerCase().includes(search.value.toLowerCase())));
const pagedOrders=computed(()=>filtered.value.slice((orderPage.value-1)*pageSize.value,orderPage.value*pageSize.value));
watch([search,issuesOnly,eligibleOnly,periodQuery,pageSize],()=>{orderPage.value=1;});
watch(()=>filtered.value.length,()=>{orderPage.value=Math.min(orderPage.value,Math.max(1,Math.ceil(filtered.value.length/pageSize.value)));});
const alertsByProject = computed(() => {
  const grouped = new Map<number, CostDataAlert[]>();
  for (const alert of data.value?.costAlerts?.items || []) {
    for (const ref of alert.entityRefs) {
      const existing = grouped.get(ref.projectId) || [];
      if (!existing.some(item => item.id === alert.id)) grouped.set(ref.projectId, [...existing, alert]);
    }
  }
  return grouped;
});
const projectAlerts = (projectId: number) => alertsByProject.value.get(projectId) || [];
const selectedAlerts = computed(() => selected.value ? projectAlerts(selected.value.projectId) : []);
const valueLabel = (value: unknown) => typeof value === 'number' ? fmt(value) : value == null || value === '' ? '未提供' : String(value);
const units = computed(() => [...new Set(data.value?.production.planActual.map(p => p.unit).filter(Boolean) || [])]);
const inventoryUnits = computed(() => {const rows=data.value?.inventory.records||[];return ['__all__',...new Set(rows.map(p=>p.unit).filter(Boolean)),...(rows.some(p=>!p.unit)?['__missing__']:[])];});
const planRows = computed(() => (data.value?.production.planActual || []).filter(p => p.unit === unit.value));
const productionReview=computed(()=>data.value?reviewProduction(data.value):null);
const inventoryReview=computed(()=>data.value?reviewInventory(data.value,inventoryUnit.value):null);
const periodReview=computed(()=>data.value?reviewPeriod(data.value):null);
const inventorySummary=computed(()=>inventoryReview.value?`本次共返回 ${inventoryReview.value.totalRecords} 行余额，以下提醒检查全部记录；当前表格显示 ${inventoryReview.value.rows.length} 行。不同单位只逐行看，不合计数量。`:'库存资料尚未返回。');
const productionPlanRows=computed(()=>(productionReview.value?.plans||[]).filter(p=>!planFocus.value.length||planFocus.value.includes(p.reviewKey)));
const productionProcessRows=computed(()=>(productionReview.value?.processes||[]).filter(p=>!processFocus.value.length||processFocus.value.includes(p.reviewKey)));
const productionQualityRows=computed(()=>(productionReview.value?.quality||[]).filter(p=>!qualityFocus.value.length||qualityFocus.value.includes(p.reviewKey)));
const stockRows = computed(() => (inventoryReview.value?.rows||[]).filter(p=>!inventoryFocus.value.length||inventoryFocus.value.includes(p.reviewKey)));
const stockTotals = computed(() => (data.value?.inventory.byWarehouseUnit || []).filter(p => p.unit === inventoryUnit.value));
const sectionInsights = computed<SectionInsightReport[]>(() => data.value?.sectionInsights || []);
const insightFor = (sectionId: string) => sectionInsights.value.find(report => report.sectionId === sectionId);
const overviewMetrics = computed(() => data.value ? buildCostOverview(data.value) : null);
const overviewGroups = computed(() => groupEligibleCostProjects(data.value?.cost.projects || [], overviewUnit.value || undefined));
const overviewUnits = computed(() => [...new Set((data.value?.cost.projects || []).filter(p => p.unitCostAvailable && p.unit).map(p => p.unit as string))]);
const overviewChart = computed(() => buildUnitCostChart(overviewGroups.value));
const fmtPercent = (value: number | null | undefined) => value == null ? '未提供' : `${value.toFixed(2)}%`;
const chart = (names: string[], series: { name: string; data: (number | null)[] }[], axis: string): DynamicChartConfig => ({
  chartType: 'bar', xAxis: { type: 'category', data: names }, yAxis: [{ type: 'value', name: axis }],
  series: series.map(s => ({ ...s, type: 'bar', yAxisIndex: 0 })),
});
const planChart = computed(() => chart(planRows.value.slice(0, 15).map(p => p.product), [
  { name: '计划', data: planRows.value.slice(0, 15).map(p => p.planned) },
  { name: '实际', data: planRows.value.slice(0, 15).map(p => p.actual) },
], unit.value));
const processChart = computed(() => {
  const rows = [...(data.value?.production.processes || [])].filter(p=>typeof p.hours==='number'&&Number.isFinite(p.hours)&&p.hours>=0).sort((a, b) => b.hours!-a.hours!).slice(0, 12);
  return chart(rows.map(p => p.process), [{ name: '累计工时', data: rows.map(p => p.hours) }], '小时');
});
const stockChart = computed(() => chart(stockTotals.value.map(p => p.warehouse), [{ name: '余额', data: stockTotals.value.map(p => p.quantity) }], inventoryUnit.value));
const coverageLabel: Record<string, string> = { project: '工单', task: '工序任务', output: '报工', stock_order: '出入库单', product: '产品', process: '工序', routing: '工艺路线', warehouse: '仓库', unit: '单位', bom: 'BOM', materials: '工单用料', stock: '库存余额', project_expand: '当前工序查询回执' };
async function locateOperational(finding:OperationsFinding,section:'production'|'inventory'){
  if(section==='inventory'){inventoryUnit.value='__all__';await nextTick();inventoryFocus.value=finding.keys;}
  else if(finding.code.startsWith('process')||finding.code==='task_count_mismatch')processFocus.value=finding.keys;
  else if(finding.code.startsWith('quality'))qualityFocus.value=finding.keys;
  else planFocus.value=finding.keys;
  const target=section==='inventory'?'stock-records':finding.code.startsWith('quality')?'production-quality':finding.code.startsWith('process')?'production-processes':'production-plans';
  void nextTick(()=>document.querySelector(`[data-testid="${target}"]`)?.scrollIntoView({block:'start'}));
}
async function locatePeriodFinding(finding:OperationsFinding){
  if(finding.code==='period_dates_missing'){periodQuery.value=null;await loadForPeriod(null);}
  periodFocus.value=finding.keys;eligibleOnly.value=false;issuesOnly.value=false;search.value='';
  void nextTick(()=>document.querySelector('[data-testid="cost-projects"]')?.scrollIntoView({block:'start'}));
}
function askOperational(finding:OperationsFinding,section:'production'|'inventory'|'finished'){
  operationalContext.value=`${section}:${finding.code}:${finding.keys.join(',')}`;
  operationalQuestion.value=`${finding.title}。${finding.summary} 请根据本组数据说明可能原因、先核对什么、找谁处理。`.slice(0,500);
  focusedSection.value=section;focusedAlert.value=null;openRequest.value+=1;
  void nextTick(()=>document.querySelector(`[data-testid="section-insight-${section}"]`)?.scrollIntoView({block:'start'}));
}
function viewLatestStock(){const at=inventoryReview.value?.lastAvailableAt;if(!at)return;const date=new Date(at);if(!Number.isNaN(date.getTime()))onPeriodChange(periodForKind('day',beijingDate(date)));}
watch(() => props.activeView, value => {
  if (value && value !== tab.value) tab.value = value;
}, { immediate: true });
watch(tab, value => {
  if (value !== props.activeView) emit('update:activeView', value);
});
function goTo(section: string) {
  tab.value = section;
  void nextTick(()=>document.querySelector(section==='cost'?'[data-testid="cost-projects"]':'.blacklake-page .page-header')?.scrollIntoView({block:'start',behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'}));
}

function stopPeriodPolling() {
  if (periodPollTimer !== undefined) window.clearTimeout(periodPollTimer);
  periodPollTimer = undefined;
}

function resetPeriodView() {
  stopPeriodPolling();
  data.value = null; periodJob.value = null; lastAppliedPeriodResultKey.value = ''; periodHistory.value = []; historyDrawerJob.value = null; periodSchedule.value = null; error.value = ''; periodError.value = ''; periodAuxError.value = ''; periodLoading.value = false; selected.value = null; focusedAlert.value = null; focusedSection.value = ''; openRequest.value = 0;
}

function applyPeriodInsights(job: BlacklakePeriodJob): boolean {
  const metadata = data.value?.periodMetadata;
  const query = periodQuery.value;
  const valid = !!data.value && !!job.result && !!metadata && !!query
    && job.fingerprint === metadata.fingerprint
    && job.period.startDate === query.start_date && job.period.endDate === query.end_date
    && job.period.basis === query.basis
    && job.period.timezone === metadata.timezone;
  if (!valid) {
    periodError.value = '分析结果与当前数据快照或周期范围不一致，未装载旧结果，请刷新后重试。';
    return false;
  }
  if (!data.value || !job.result) return false;
  const resultKey = `${job.id}:${job.result.generatedAt || JSON.stringify(job.result)}`;
  if (lastAppliedPeriodResultKey.value === resultKey) return true;
  const nextCostAlerts = data.value.costAlerts && job.result.ai
    ? { ...data.value.costAlerts, ai: { ...data.value.costAlerts.ai, ...job.result.ai } }
    : data.value.costAlerts;
  data.value = { ...data.value, sectionInsights: job.result.sectionInsights, costAlerts: nextCostAlerts };
  lastAppliedPeriodResultKey.value = resultKey;
  refreshEpoch.value += 1;
  return true;
}

function periodPollDelay(nextAttemptAt?: string | null) {
  if (!nextAttemptAt) return 2500;
  const remaining = new Date(nextAttemptAt).getTime() - Date.now();
  return Math.min(30000, Math.max(2500, Number.isFinite(remaining) ? remaining : 2500));
}

function isCurrentRequest(generation: number, identity: string) {
  return mounted.value && generation === periodRequestGeneration.value && identity === authIdentity.value;
}

async function refreshPeriodHistory(generation: number, identity: string) {
  try {
    const history = await listBlacklakePeriodReports();
    if (isCurrentRequest(generation, identity)) periodHistory.value = history;
  } catch (cause) {
    if (isCurrentRequest(generation, identity)) periodAuxError.value = cause instanceof Error ? cause.message : '历史周期报告暂不可用。';
  }
}

async function refreshPeriodSchedule(generation: number, identity: string) {
  try {
    const schedule = await getBlacklakePeriodSchedule();
    if (isCurrentRequest(generation, identity)) periodSchedule.value = schedule;
  } catch (cause) {
    if (isCurrentRequest(generation, identity)) periodAuxError.value = cause instanceof Error ? cause.message : '自动计划暂不可用。';
  }
}

async function pollPeriodReport(jobId: string, generation: number, identity: string) {
  try {
    const next = await getBlacklakePeriodReport(jobId);
    if (!isCurrentRequest(generation, identity)) return;
    periodJob.value = next;
    if (next.result) applyPeriodInsights(next);
    if (next.status === 'ready') { periodLoading.value = false; void refreshPeriodHistory(generation, identity); return; }
    if (next.status === 'failed') { periodLoading.value = false; periodError.value = next.error || '周期分析失败，可重试。'; return; }
    periodPollTimer = window.setTimeout(() => { void pollPeriodReport(jobId, generation, identity); }, periodPollDelay(next.nextAttemptAt));
  } catch (cause) {
    if (!isCurrentRequest(generation, identity)) return;
    periodLoading.value = false; periodError.value = cause instanceof Error ? cause.message : '读取周期分析状态失败。';
  }
}

async function loadForPeriod(query: BlacklakePeriodQuery | null, force = false) {
  const generation = ++periodRequestGeneration.value;
  const identity = authIdentity.value;
  resetPeriodView();
  void refreshPeriodHistory(generation, identity);
  void refreshPeriodSchedule(generation, identity);
  loading.value = true;
  try {
    const refreshed = await getBlacklakeAnalysis(query);
    if (!isCurrentRequest(generation, identity)) return;
    if(!initialDateResolved){
      initialDateResolved=true;
      const parsed=new Date(refreshed.snapshotAt);
      const latest=refreshed.latestCompletedDate||(!Number.isNaN(parsed.getTime())?beijingDate(parsed):null);
      if(query?.kind==='day'&&!refreshed.production.projectCount&&latest&&query.start_date>latest){
        const next=periodForKind('day',latest);periodQuery.value=next;void loadForPeriod(next);return;
      }
    }
    data.value = refreshed; refreshEpoch.value += 1;
    unit.value = units.value.includes('kg') ? 'kg' : units.value[0] || '';
    inventoryUnit.value = '__all__';
    overviewUnit.value = overviewUnits.value.includes('kg') ? 'kg' : overviewUnits.value[0] || '';
    if (!query) return;
    periodLoading.value = true;
    const submitted = await createBlacklakePeriodReport(query, force, refreshed.periodMetadata?.fingerprint);
    if (!isCurrentRequest(generation, identity)) return;
    periodJob.value = submitted;
    if (submitted.result) applyPeriodInsights(submitted);
    if (submitted.status === 'ready') { periodLoading.value = false; void refreshPeriodHistory(generation, identity); }
    else if (submitted.status === 'failed') { periodLoading.value = false; periodError.value = submitted.error || '周期分析失败，可重试。'; }
    else void pollPeriodReport(submitted.id, generation, identity);
  } catch (cause) {
    if (!isCurrentRequest(generation, identity)) return;
    if (data.value) periodError.value = cause instanceof Error ? cause.message : '周期分析任务未能创建。';
    else error.value = cause instanceof Error ? cause.message : '读取失败，请重试';
  } finally {
    if (isCurrentRequest(generation, identity)) loading.value = false;
  }
}

function onPeriodChange(query: BlacklakePeriodQuery | null) {
  if (periodIdentity(query) === periodIdentity(periodQuery.value)) return;
  periodQuery.value = query;
  void loadForPeriod(query);
}

function retryPeriod() { if (periodQuery.value) void loadForPeriod(periodQuery.value, true); }
function openHistory(job: BlacklakePeriodJob) { historyDrawerJob.value = job; }

watch(authIdentity, (value, previous) => {
  if (!previous || value === previous) return;
  resetPeriodView(); periodRequestGeneration.value += 1; periodHistory.value = []; historyDrawerJob.value = null; periodSchedule.value = null; periodAuxError.value = '';
  void loadForPeriod(periodQuery.value);
});

function clearFocus(sectionId?: string) {
  if (!sectionId || focusedSection.value === sectionId) {
    focusedSection.value = '';
    focusedAlert.value = null;
  }
}

function locateOrder(projectId: number) {
  const order = data.value?.cost.projects.find(project => project.projectId === projectId);
  if (!order) return;
  selected.value = order;
  void nextTick(() => document.querySelector('[data-testid="cost-projects"]')?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'center' }));
}

function focusAlert(alert: CostDataAlert) {
  const preferredSections = ['product_cost', 'labor', 'material', 'finished', 'coverage'];
  const matched = preferredSections.find(sectionId => sectionInsights.value.some(report =>
    report.sectionId === sectionId && report.evidenceRefs.some(reference => reference === alert.id || reference.includes(alert.id))));
  const inferred = matched || (alert.family === 'readiness' ? 'coverage' : alert.fields.some(field => /labor|工资|工时/i.test(field)) ? 'labor' : 'product_cost');
  focusedAlert.value = alert;
  focusedSection.value = inferred;
  openRequest.value += 1;
  tab.value = ['product_cost', 'labor', 'material', 'finished'].includes(inferred) ? 'cost' : inferred;
  void nextTick(() => document.querySelector(`[data-testid="section-insight-${inferred}"]`)?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth', block: 'center' }));
}
onMounted(() => {
  mounted.value = true;
  void loadForPeriod(periodQuery.value);
});
onUnmounted(() => { mounted.value = false; periodRequestGeneration.value += 1; stopPeriodPolling(); });
</script>

<template>
  <main :key="refreshEpoch" class="blacklake-page" :data-shell-navigation="props.activeView !== undefined" v-loading="loading">
    <header class="page-header">
      <div><span class="eyebrow">SMARTBI · 黑湖只读测试</span><h2>六膳门 · {{ viewTitles[tab] || '生产经营分析' }}</h2>
        <p>{{ viewDescriptions[tab] || '查看本期数据和需要处理的问题。' }}</p></div>
      <UButton color="neutral" variant="outline" :loading="loading" @click="void loadForPeriod(periodQuery)">刷新分析</UButton>
    </header>
    <BlacklakePeriodControls :model-value="periodQuery" :disabled="loading" @change="onPeriodChange" />
    <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon />
    <el-alert v-if="periodError" type="warning" :closable="false" :title="periodError" show-icon />
    <el-alert v-if="periodAuxError" type="info" :closable="false" :title="periodAuxError" show-icon />
    <BlacklakePeriodAnalysis :metadata="data?.periodMetadata" :job="periodJob" :history="periodHistory" :schedule="periodSchedule" @retry="retryPeriod" @open-history="openHistory" />
    <template v-if="data">
      <p class="snapshot">资料更新 {{ formatBlacklakeDateTime(data.snapshotAt) }}</p>
      <section v-show="tab === 'cost'" class="overview-shell" aria-label="成本分析概览">
        <OperationsReviewPanel v-if="periodReview" title="本期先核什么" summary="按所选日期看这些工单的材料与人工；尚未完工、资料待补和真正的数值矛盾分别处理。" :findings="periodReview.findings" :allow-ask="!!insightFor('finished')" @locate="locatePeriodFinding" @ask="askOperational($event,'finished')" />
        <UCard class="overview-card overview-summary">
          <div class="overview-heading"><div><span class="eyebrow">先看结论，再看明细</span><h3>成本核算概览</h3><p>材料＋人工，暂不含制造费用。</p></div></div>
          <div class="overview-metrics">
            <div><span>工单覆盖</span><strong>{{ overviewMetrics?.eligibleProjects }} / {{ overviewMetrics?.projectCount }}</strong><small>可核算工单 · {{ fmtPercent(overviewMetrics?.orderCoveragePercent) }}</small></div>
            <div><span>暂不能核算</span><strong>{{ overviewMetrics?.blockedProjects }}</strong><small>打开工单查看缺什么</small></div>
            <div><span>关联领退料</span><strong>{{ overviewMetrics?.linkedMaterialLines }}</strong><small>另有 {{ overviewMetrics?.unlinkedMaterialLines }} 行未确认</small></div>
            <div><span>材料金额为零</span><strong class="warning">{{ overviewMetrics?.sourceZeroCostLines }}</strong><small>零值不等于免费</small></div>
          </div>
        </UCard>
        <div class="overview-grid">
          <UCard class="overview-card analysis-card">
            <div class="overview-heading"><div><h3>当前分析</h3><p>先处理影响成本判断的问题</p></div><UBadge color="primary" variant="soft">{{ data.production.projectCount }} 张工单</UBadge></div>
            <ul class="overview-list"><li>{{ overviewMetrics?.eligibleProjects }} 张工单可以核算，{{ overviewMetrics?.blockedProjects }} 张暂不进入成本比较；具体原因见上方核对提醒。</li><li v-for="alert in (data.costAlerts?.items || []).filter(a=>a.family!=='readiness').slice(0,2)" :key="alert.id"><strong>{{ localizeBlacklakeText(alert.title) }}</strong>：{{ localizeBlacklakeText(alert.action) }}</li><li v-if="!data.production.projectCount">这段时间没有已采集的工单，可调整日期查看。</li></ul>
            <div class="overview-actions"><UButton size="sm" @click="goTo('cost')">查看成本链路</UButton><UButton size="sm" color="neutral" variant="outline" @click="goTo('coverage')">查看数据口径</UButton></div>
          </UCard>
          <UCard class="overview-card priority-card">
            <div class="overview-heading"><div><h3>优先核对</h3><p>异常提醒按证据回到对应页面</p></div><UBadge color="warning" variant="soft">{{ data.costAlerts?.summary.alerts || 0 }} 条提醒</UBadge></div>
            <div class="signal-list"><button type="button" @click="goTo('alerts')"><span>成本差异信号</span><UBadge color="warning" variant="soft">{{ overviewMetrics?.businessSignalCount }}</UBadge></button><button type="button" @click="goTo('alerts')"><span>数据质量</span><UBadge color="neutral" variant="soft">{{ overviewMetrics?.dataQualityAlertCount }}</UBadge></button><button type="button" @click="goTo('alerts')"><span>比较条件待补</span><UBadge color="error" variant="soft">{{ overviewMetrics?.readinessAlertCount }}</UBadge></button></div>
            <div class="overview-actions"><UButton size="sm" color="neutral" variant="outline" @click="goTo('alerts')">打开异常提醒</UButton></div>
          </UCard>
        </div>
        <UCard class="overview-card unit-cost-card">
          <div class="overview-heading"><div><h3>产品单位直接成本</h3><p>材料 + 人工按同一成品单位堆叠；不合并不同单位或冲突产品名。</p></div><div class="unit-selector"><span>成品单位</span><el-select v-model="overviewUnit" aria-label="产品单位成本单位"><el-option v-for="u in overviewUnits" :key="u" :label="u" :value="u" /></el-select></div></div>
          <DynamicChartRenderer v-if="overviewGroups.length" :config="overviewChart" :height="250" :number-precision="2" presentation="plain" />
          <el-empty v-else description="当前没有同单位且具备材料、人工、成品量的可核算工单" />
          <div v-if="overviewGroups.length" class="unit-cost-groups" data-testid="unit-cost-groups"><div v-for="group in overviewGroups" :key="group.key"><span class="product-identity"><b>{{ group.product }}</b><small>{{ group.displayIdentity }}</small></span><UBadge color="neutral" variant="soft">{{ group.sampleCount }} 条工单</UBadge><strong>{{ fmt(group.weightedUnitCost) }} / {{ group.unit }}</strong></div></div>
          <div class="unit-cost-note"><span>加权口径：各产品组材料成本 ÷ 成品良品量 + 人工成本 ÷ 成品良品量。</span><UButton size="sm" color="neutral" variant="ghost" @click="goTo('cost')">查看逐工单明细</UButton></div>
          <SectionInsight v-if="insightFor('product_cost')" :report="insightFor('product_cost')!" :period="periodQuery" :focus-alert="focusedSection === 'product_cost' ? focusedAlert : null" :open-request="focusedSection === 'product_cost' ? openRequest : 0" @clear-focus="clearFocus('product_cost')" @locate-order="locateOrder" />
        </UCard>
      </section>
      <el-tabs v-model="tab">
        <el-tab-pane label="原料 → 成品成本" name="cost">
          <el-card shadow="never">
            <div class="section-heading"><h3>逐工单成本链路</h3><div class="filters"><el-input v-model="search" placeholder="搜索工单或产品" clearable aria-label="搜索工单或产品" /><el-checkbox v-model="eligibleOnly">仅看可核算工单</el-checkbox><el-checkbox v-model="issuesOnly">仅看待核对</el-checkbox></div></div>
            <p class="note">材料＋人工；资料不齐的工单标记待核对。币种待确认。</p>
            <p v-if="periodFocus.length" class="note">已定位 {{ periodFocus.length }} 张相关工单，汇总仍按上方日期范围。<UButton color="neutral" variant="ghost" @click="periodFocus=[]">恢复全部明细</UButton></p>
            <CostDataTable :rows="filtered" :columns="[{key:'projectCode',label:'工单',type:'text',width:155},{key:'product',label:'成品',type:'text',minWidth:180},{key:'status',label:'状态',type:'select',labels:{'0':'未开始','10':'进行中','20':'已完工','30':'已取消'},width:85},{key:'finishedGood',label:'成品良品量',type:'number',minWidth:125},{key:'materialCost',label:'已识别材料净额',type:'number',width:110},{key:'laborCost',label:'已识别人工累计',type:'number',width:110},{key:'unitIdentifiedDirectCost',label:'单位直接成本',type:'number',minWidth:145},{key:'_status',label:'核对状态',type:'select',value:row=>!row.unitCostAvailable?'资料待补':projectAlerts(row.projectId).length||row.issues.length?'需要核对':'资料具备',minWidth:225},{key:'col8',label:'链路',type:'text',width:95,sortable:false,filterable:false}]" paginate   row-key="projectId" stripe  @row-click="selected = $event" data-testid="cost-projects"><template #status="{row}">{{ statusName(row.status) }}</template><template #finishedGood="{row}">{{ fmt(row.finishedGood) }} {{ row.unit }}</template><template #materialCost="{row}">{{ fmt(row.materialCost) }}</template><template #laborCost="{row}">{{ fmt(row.laborCost) }}</template><template #unitIdentifiedDirectCost="{row}">{{ row.unitCostAvailable ? `${fmt(row.unitIdentifiedDirectCost)} / ${row.unit}` : '待核对' }}</template><template #_status="{row}"><el-tag :type="!row.unitCostAvailable ? 'danger' : projectAlerts(row.projectId).length || row.issues.length ? 'warning' : 'success'">{{ !row.unitCostAvailable ? '暂不能核算，需核对资料' : projectAlerts(row.projectId).length ? `${projectAlerts(row.projectId).length} 条核对提醒` : row.issues.length ? `${row.issues.length} 项待补资料` : '来源字段具备' }}</el-tag><el-button v-if="projectAlerts(row.projectId).length || row.issues.length" link type="warning" @click.stop="locateOrder(row.projectId)">查看问题</el-button></template><template #col8="{row}"><el-button link type="primary" @click.stop="selected = row">查看链路</el-button></template></CostDataTable>

            <SectionInsight v-if="insightFor('finished')" :report="insightFor('finished')!" :period="periodQuery" :focus-alert="focusedSection === 'finished' ? focusedAlert : null" :open-request="focusedSection === 'finished' ? openRequest : 0" :initial-question="operationalQuestion" :context-key="operationalContext" @clear-focus="clearFocus('finished')" @locate-order="locateOrder" />
          </el-card>
          <details class="comparison-evidence" @toggle="toggleComparison"><summary>展开同产品工单差额与逐物料证据</summary><BlacklakeCostIntelligence v-if="comparisonOpen" :intelligence="data.costIntelligence" :projects="data.cost.projects" /></details>
          <el-card shadow="never" class="section-card"><details><summary>加工人工 · 查看工序成本与分析</summary>
            <p class="note">单独查看人工贡献，不与报工估算工资重复累加。</p>
            <CostDataTable :rows="data.eligibleCostReview?.projects || []" :columns="[{key:'projectCode',label:'工单',type:'text',minWidth:150},{key:'product',label:'产品',type:'text',minWidth:160},{key:'laborCost',label:'累计人工',type:'number'},{key:'laborPerFinished',label:'单位成品人工',type:'number'},{key:'laborShare',label:'人工占比',type:'number',scale:100}]" paginate    stripe data-testid="cost-labor"><template #laborCost="{row}">{{ fmt(row.laborCost) }}</template><template #laborPerFinished="{row}">{{ fmt(row.laborPerFinished) }} / {{ row.unit }}</template><template #laborShare="{row}">{{ row.laborShare == null ? '待核' : `${fmt(row.laborShare * 100)}%` }}</template></CostDataTable>
            <SectionInsight v-if="insightFor('labor')" :report="insightFor('labor')!" :period="periodQuery" :focus-alert="focusedSection === 'labor' ? focusedAlert : null" :open-request="focusedSection === 'labor' ? openRequest : 0" @clear-focus="clearFocus('labor')" @locate-order="locateOrder" />
          </details></el-card>
          <el-card shadow="never" class="section-card"><details><summary>原料净消耗 · 查看材料明细与分析</summary><p class="note">按物料与来源单位保留；不混加不同单位。未关联领退料未计入下表。</p>
            <CostDataTable :rows="data.cost.materials" :columns="[{key:'material',label:'原料',type:'text',minWidth:190},{key:'unit',label:'单位',type:'select',width:85},{key:'netConsumption',label:'净消耗',type:'number'},{key:'recognizedCost',label:'已识别净额',type:'number'},{key:'missingCostLines',label:'缺金额行数',type:'number',count:true},{key:'lines',label:'领退料行数',type:'number',count:true}]" paginate    stripe><template #netConsumption="{row}">{{ fmt(row.netConsumption) }}</template><template #recognizedCost="{row}">{{ fmt(row.recognizedCost) }}<small v-if="row.missingCostLines"> · 金额未齐</small></template></CostDataTable>
            <SectionInsight v-if="insightFor('material')" :report="insightFor('material')!" :period="periodQuery" :focus-alert="focusedSection === 'material' ? focusedAlert : null" :open-request="focusedSection === 'material' ? openRequest : 0" @clear-focus="clearFocus('material')" @locate-order="locateOrder" />
          </details></el-card>
        </el-tab-pane>
        <el-tab-pane label="成本与数据异常" name="alerts">
          <BlacklakeCostAlerts v-if="data.costAlerts && data.eligibleCostReview" :alerts="data.costAlerts" :review="data.eligibleCostReview" :followups="data.alertFollowups" :period="periodQuery" @ask="focusAlert" @updated="updateProgress" @locate-order="locateOrder" />
          <el-alert v-else type="warning" :closable="false" title="异常分析结果未返回，请核对分析服务。" />
        </el-tab-pane>
        <el-tab-pane label="关联与估值补查" name="gaps">
          <BlacklakeCostIntelligence :intelligence="data.costIntelligence" :projects="data.cost.projects" mode="gaps" />
        </el-tab-pane>
        <el-tab-pane label="生产与工序" name="production">
          <div v-if="productionReview" class="operation-metrics"><div><span>本组工单</span><strong>{{ productionReview.total }} 张</strong></div><div><span>已完工</span><strong>{{ productionReview.statuses['20']||0 }} 张</strong></div><div><span>进行中</span><strong>{{ productionReview.statuses['10']||0 }} 张</strong></div><div><span>未开始 / 已取消</span><strong>{{ productionReview.statuses['0']||0 }} / {{ productionReview.statuses['30']||0 }} 张</strong></div></div>
          <p v-if="productionReview?.completedOnly" class="note">当前按完工日期选工单，不能用这组工单计算全厂完工率。<UButton v-if="periodQuery" color="neutral" variant="outline" size="sm" @click="onPeriodChange({...periodQuery,basis:'created'})">按创建日期看在制情况</UButton></p>
          <OperationsReviewPanel v-if="productionReview" title="生产先核什么" :summary="`已审批报工 ${productionReview.approved} / 全部 ${data.production.outputCount} 条；工单产出、工序任务和报工数量分别核对，不相加。`" :findings="productionReview.findings" :allow-ask="!!insightFor('production')" @locate="locateOperational($event,'production')" @ask="askOperational($event,'production')" />
          <el-card shadow="never"><h3>产品计划与产出</h3>
            <p class="note">工单实际产量与最终良品量分开看；差额为实际产量减计划量，不代表已确认损耗或延期。</p>
            <p v-if="planFocus.length" class="note">已定位问题产品。<UButton color="neutral" variant="ghost" @click="planFocus=[]">恢复全部产品</UButton></p>
            <CostDataTable :rows="productionPlanRows" :columns="[{key:'product',label:'产品'},{key:'productCode',label:'产品编码'},{key:'unit',label:'单位',type:'select'},{key:'planned',label:'计划量',type:'number'},{key:'actual',label:'工单实际产量',type:'number'},{key:'finishedGood',label:'成品良品量',type:'number'},{key:'gap',label:'实际与计划差额',type:'number'},{key:'projects',label:'工单数',type:'number',count:true},{key:'action',label:'相关工单',filterable:false,sortable:false}]" paginate data-testid="production-plans">
              <template #action="{row}"><UButton color="neutral" variant="outline" :disabled="row.identityAmbiguous" @click="productionFocus={kind:'product',name:row.product,key:row.reviewKey}">查看工单</UButton></template>
            </CostDataTable>
          </el-card>
          <el-card shadow="never" class="section-card"><h3>工序进度与工时</h3>
            <p class="note">这是所选工单的任务累计报工时长，不是人均工时；工时多时先查任务量，不能直接判断效率低。</p>
            <p v-if="processFocus.length" class="note">已定位问题工序。<UButton color="neutral" variant="ghost" @click="processFocus=[]">恢复全部工序</UButton></p>
            <CostDataTable :rows="productionProcessRows" :columns="[{key:'process',label:'工序'},{key:'tasks',label:'任务数',type:'number',count:true},{key:'inProgress',label:'进行中',type:'number',count:true},{key:'hours',label:'累计报工小时',type:'number'},{key:'missingDurationTasks',label:'时长未提供的任务',type:'number',count:true},{key:'action',label:'相关工单',filterable:false,sortable:false}]" paginate data-testid="production-processes">
              <template #action="{row}"><UButton color="neutral" variant="outline" :disabled="!row.projectIds?.length" @click="productionFocus={kind:'process',name:row.process,key:row.reviewKey}">查看工单</UButton></template>
            </CostDataTable>
          </el-card>
          <el-card v-if="productionFocus" shadow="never" class="section-card" data-testid="production-related-orders"><div class="section-heading"><h3>{{ productionFocus.name }} · 相关工单</h3><UButton color="neutral" variant="outline" @click="productionFocus=null">收起工单</UButton></div>
            <CostDataTable :rows="productionOrders" :columns="[{key:'projectCode',label:'工单'},{key:'product',label:'产品'},{key:'productCode',label:'产品编码'},{key:'unit',label:'单位',type:'select'},{key:'finishedGood',label:'成品良品',type:'number'},{key:'status',label:'状态',type:'select',labels:{'0':'未开始','10':'进行中','20':'已完工','30':'已取消'}},{key:'action',label:'明细',sortable:false,filterable:false}]" paginate><template #action="{row}"><UButton color="neutral" variant="outline" @click="selected=row">查看工单分析</UButton></template></CostDataTable>
          </el-card>
          <details class="technical"><summary>生产计划与工序工时图</summary><div class="chart-grid"><el-card shadow="never"><div class="section-heading"><h3>计划与实际 · 前 15 个产品</h3><el-select v-model="unit" aria-label="生产数量单位"><el-option v-for="u in units" :key="u" :label="u" :value="u" /></el-select></div><DynamicChartRenderer :config="planChart" :number-precision="2" /></el-card><el-card shadow="never"><h3>工序累计工时 · 前 12 项</h3><p class="note">累计快照，用于定位需关注工序，不能据此认定效率或瓶颈原因。</p><DynamicChartRenderer :config="processChart" :number-precision="2" /></el-card></div>
</details>
          <el-card shadow="never" class="section-card"><h3>已审批报工质量 · 按产品 / 工序 / 单位</h3><p class="note">良品率＝良品÷（良品＋不良）。未审批和其他状态的报工不混入本表；分母为零或数量不齐时不显示比例。</p><p v-if="qualityFocus.length" class="note">已定位质量问题。<UButton color="neutral" variant="ghost" @click="qualityFocus=[]">恢复全部质量记录</UButton></p><CostDataTable :rows="productionQualityRows" :columns="[{key:'product',label:'产品',type:'text',minWidth:180},{key:'process',label:'工序',type:'text'},{key:'unit',label:'单位',type:'select',width:75},{key:'records',label:'已审批报工条数',type:'number',count:true},{key:'good',label:'良品数',type:'number'},{key:'defective',label:'不良数',type:'number'},{key:'reviewGoodRate',label:'良品率',type:'number',scale:100},{key:'missingRecords',label:'数量未齐条数',type:'number',count:true}]" paginate stripe data-testid="production-quality"><template #good="{row}">{{ fmt(row.good) }}</template><template #defective="{row}">{{ fmt(row.defective) }}</template><template #reviewGoodRate="{row}">{{ row.invalid?'资料待核':row.reviewGoodRate==null?'无有效产出':`${fmt(row.reviewGoodRate*100)}%` }}</template></CostDataTable></el-card>
            <SectionInsight v-if="insightFor('production')" :report="insightFor('production')!" :period="periodQuery" :focus-alert="focusedSection === 'production' ? focusedAlert : null" :open-request="focusedSection === 'production' ? openRequest : 0" :initial-question="operationalQuestion" :context-key="operationalContext" @clear-focus="clearFocus('production')" @locate-order="locateOrder" />
        </el-tab-pane>
        <el-tab-pane label="库存与仓库" name="inventory">
          <el-alert type="info" :closable="false" :title="data.periodMetadata && data.periodMetadata.inventoryStatus !== 'snapshot_within_period' ? '这段时间没有库存余额记录。请仓库补当期库存表，不能据此判断库存为零。' : `已返回 ${data.inventory.records.length} 条采集时点余额；未返回的产品与仓库组合${data.inventory.pairsNotReturned == null ? '数量未知' : `有 ${data.inventory.pairsNotReturned} 个`}，保持未知，不是期末余额。`" />
            <el-card shadow="never" class="section-card"><div class="section-heading"><h3>仓库余额 · 同单位比较</h3><el-select v-model="inventoryUnit" aria-label="库存数量单位"><el-option v-for="u in inventoryUnits" :key="u" :label="u==='__all__'?'全部单位（逐行查看）':u==='__missing__'?'单位待补':u" :value="u" /></el-select></div>
              <p class="note">库存余额采集：{{ inventoryReview?.snapshotAt?formatBlacklakeDateTime(inventoryReview.snapshotAt):'本期没有可用快照' }}。库存覆盖当时可见物料，不只限于上面工单的用料。</p>
              <UButton v-if="!data.inventory.records.length&&inventoryReview?.lastAvailableAt" color="primary" variant="outline" @click="viewLatestStock">查看最近一次库存快照</UButton>
              <p v-if="inventoryFocus.length" class="note">已定位 {{ stockRows.length }} 行问题明细，库存汇总仍按当前单位。<UButton color="neutral" variant="ghost" @click="inventoryFocus=[]">恢复全部库存明细</UButton></p>
              <details><summary>按仓库比较库存数量</summary><p v-if="inventoryUnit==='__all__'||inventoryUnit==='__missing__'" class="note">请先选择同一个计量单位，再比较仓库数量。</p><DynamicChartRenderer v-else :config="stockChart" :number-precision="2" /></details>
              <CostDataTable :rows="stockRows" :columns="[{key:'product',label:'物料 / 产品',type:'text',minWidth:190},{key:'productCode',label:'物料编码',minWidth:140},{key:'warehouse',label:'仓库',type:'select'},{key:'warehouseCode',label:'仓库编码'},{key:'unit',label:'单位',type:'select',width:75},{key:'quantity',label:'采集时余额',type:'number'},{key:'reviewStatus',label:'核对提示',type:'select',minWidth:150}]" paginate stripe data-testid="stock-records"><template #quantity="{row}">{{ fmt(row.quantity) }}</template></CostDataTable>
              <OperationsReviewPanel v-if="inventoryReview" title="库存先核什么" :summary="inventorySummary" :findings="inventoryReview.findings" :allow-ask="!!insightFor('inventory')" @locate="locateOperational($event,'inventory')" @ask="askOperational($event,'inventory')" />
              <SectionInsight v-if="insightFor('inventory')" :report="insightFor('inventory')!" :period="periodQuery" :focus-alert="focusedSection === 'inventory' ? focusedAlert : null" :open-request="focusedSection === 'inventory' ? openRequest : 0" :initial-question="operationalQuestion" :context-key="operationalContext" @clear-focus="clearFocus('inventory')" @locate-order="locateOrder" /></el-card>
        </el-tab-pane>
        <el-tab-pane label="数据覆盖与口径" name="coverage">
          <el-card shadow="never"><h3>数据集已接入 13 类资料</h3><CostDataTable :rows="data.catalog" :columns="[{key:'resource',label:'资料',type:'text',value:row=>coverageLabel[row.resource]||row.resource},{key:'count',label:'条数',type:'number',count:true}]" paginate  ><template #resource="{row}">{{ coverageLabel[row.resource] || row.resource }}</template></CostDataTable><h3>分析发现</h3><ul><li v-for="item in data.findings" :key="item">{{ item }}</li></ul><h3>使用边界</h3><ul><li v-for="item in data.limitations" :key="item">{{ item }}</li></ul></el-card>
            <SectionInsight v-if="insightFor('coverage')" :report="insightFor('coverage')!" :period="periodQuery" :focus-alert="focusedSection === 'coverage' ? focusedAlert : null" :open-request="focusedSection === 'coverage' ? openRequest : 0" @clear-focus="clearFocus('coverage')" @locate-order="locateOrder" />
        </el-tab-pane>
      </el-tabs>
      <el-drawer :model-value="!!selected" @close="selected = null" size="min(100%, 1080px)" title="原料 → 工序 → 成品 · 工单成本链路">
        <template v-if="selected"><h2>{{ selected.projectCode }} · {{ selected.product }}</h2>
          <section class="order-checks" aria-label="本工单问题与核对建议"><h3>这张工单需要核对什么</h3><p class="plain-language">先核对以下问题，再确认这张工单的成本。</p><ul v-if="selected.issues.length || selectedAlerts.length"><li v-for="issue in selected.issues" :key="`reason-${issue}`"><strong>数据缺口：</strong>{{ localizeBlacklakeText(issue) }}</li><li v-for="alert in selectedAlerts" :key="alert.id"><strong>{{ localizeBlacklakeText(alert.title) }}</strong>：{{ localizeBlacklakeText(alert.message) }}<span class="alert-meta">责任角色：{{ localizeBlacklakeText(alert.owner) }} · 建议：{{ localizeBlacklakeText(alert.action) }}</span></li></ul><el-empty v-else description="当前没有已识别的待核对问题" :image-size="48" /></section>
          <div class="flow"><div><span>原料领退料</span><strong>{{ selected.materialLines }} 行</strong><small>净额 {{ fmt(selected.materialCost) }}</small></div><b aria-hidden="true">→</b><div><span>生产工序</span><strong>{{ selected.taskCount }} 项</strong><small>累计人工 {{ fmt(selected.laborCost) }}</small></div><b aria-hidden="true">→</b><div><span>工单成品良品量</span><strong>{{ fmt(selected.finishedGood) }} {{ selected.unit }}</strong><small>{{ statusName(selected.status) }}</small></div></div>
          <el-alert v-if="selected.issues.length" type="warning" :closable="false" title="暂不展示单位成本"><template #default><ul><li v-for="issue in selected.issues" :key="issue">{{ issue }}</li></ul></template></el-alert>
          <el-alert v-else type="success" :closable="false" :title="`单位可识别直接成本 ${fmt(selected.unitIdentifiedDirectCost)} / ${selected.unit}（来源币种，非完整制造成本）`" />
          <p class="note">材料金额有值 {{ selected.materialCostKnownLines }}/{{ selected.materialLines }} 行；累计工资有值 {{ selected.laborCostKnownTasks }}/{{ selected.taskCount }} 项。缺失不补零。</p><h3>实际领料与退料明细</h3><CostDataTable :rows="selected.materialLinesDetail" :columns="[{key:'material',label:'原料',type:'text',minWidth:170},{key:'orderCode',label:'来源出入库单',type:'text',minWidth:150},{key:'netConsumption',label:'净消耗',type:'number'},{key:'unitPrice',label:'来源单价',type:'number'},{key:'recognizedCost',label:'来源净额',type:'number'}]" paginate   stripe><template #netConsumption="{row}">{{ fmt(row.netConsumption) }} {{ row.unit }}</template><template #unitPrice="{row}">{{ fmt(row.unitPrice) }}</template><template #recognizedCost="{row}">{{ fmt(row.recognizedCost) }}</template></CostDataTable>
          <h3>工序进度与累计人工</h3><CostDataTable :rows="selected.processes" :columns="[{key:'process',label:'工序',type:'text'},{key:'status',label:'状态',type:'select',labels:{'0':'未开始','10':'进行中','20':'已完工','30':'已取消'}},{key:'hours',label:'累计工时（小时）',type:'number'},{key:'laborCost',label:'累计工资',type:'number'}]" paginate   stripe><template #status="{row}">{{ statusName(row.status) }}</template><template #hours="{row}">{{ fmt(row.hours) }}</template><template #laborCost="{row}">{{ fmt(row.laborCost) }}</template></CostDataTable>
          <h3>工单用料参考 · {{ verifiedReferences.length ? '已核实的配方用料参考' : '暂无可展示的已核实参考用料' }}</h3>
          <p class="note">{{ verifiedReferences.length ? '已按工单 ID 和编号交叉查询验证归属；' : '当前快照没有可展示的已核实参考用料；' }}源端单位用量的换算基准待核，不能替代实际领料或成本。</p>
          <CostDataTable :rows="verifiedReferences" :columns="[{key:'materialCode',label:'物料代码',type:'text'},{key:'feedProcessCode',label:'投料工序代码',type:'text'},{key:'sourceUnitQty',label:'源端单位用量（基准待核）',type:'number'}]" paginate   stripe><template #sourceUnitQty="{row}">{{ valueLabel(row.sourceUnitQty) }}</template></CostDataTable>
          <p class="note">数量未跨工序求和。工资估算未重复叠加；BOM 标准用量未代替实际领料。</p>
        </template>
      </el-drawer>
    </template>
    <el-drawer v-if="historyDrawerJob" :model-value="!!historyDrawerJob" title="历史周期报告" size="min(100%, 760px)" @close="historyDrawerJob = null">
      <template v-if="historyDrawerJob"><h3>{{ historyDrawerJob.period.startDate }} 至 {{ historyDrawerJob.period.endDate }}</h3><p class="note">报告状态：{{ ({ queued: '等候分析', running: '正在分析', ready: '已生成', failed: '分析失败' })[historyDrawerJob.status] }} · {{ formatBlacklakeDateTime(historyDrawerJob.finishedAt || historyDrawerJob.createdAt) }}</p><p v-if="historyDrawerJob.result?.summary">{{ localizeBlacklakeText(historyDrawerJob.result.summary) }}</p><h4 v-if="historyDrawerJob.result?.observations.length">观察</h4><ul><li v-for="item in historyDrawerJob.result?.observations || []" :key="item">{{ localizeBlacklakeText(item) }}</li></ul></template>
    </el-drawer>
  </main>
</template>

<style scoped lang="scss">
.blacklake-page { --c-primary: #1b65a8; --c-ink: #1a293d; --c-text: #41536a; --c-muted: #8491a3; --c-page: #f4f6f9; --c-line: #e5eaf1; --c-radius: 10px; padding: 28px 29px 32px; color: var(--c-ink); background: var(--c-page); min-width: 0; }
.page-header, .section-heading, .filters { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header { margin-bottom: 20px; align-items: flex-start; }
h2 { margin: 8px 0; font-size: 26px; letter-spacing: -.5px; } h3 { margin: 0 0 16px; font-size: 16px; }
p, small, .note, .snapshot { color: var(--c-muted); line-height: 1.7; }
.eyebrow { color: var(--c-primary); font-size: 11px; letter-spacing: 1.2px; font-weight: 600; }
.snapshot { margin: 12px 0 18px; font-size: 12px; }
.warning { color: #b38336; }.el-card { border-radius: var(--c-radius); }.section-card { margin-top: 20px; }
.overview-shell { display: grid; gap: 14px; }
.operation-metrics { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:16px; margin-bottom:20px; }.operation-metrics>div { padding:14px 16px; background:#f6f8fb; border:1px solid var(--c-line); border-radius:var(--c-radius); }.operation-metrics span,.operation-metrics strong { display:block; }.operation-metrics span { color:var(--c-text); font-size:12px; }.operation-metrics strong { margin-top:7px; font-size:22px; font-variant-numeric:tabular-nums; }
.overview-card { border: 1px solid var(--c-line); border-radius: var(--c-radius); box-shadow: 0 5px 24px rgb(27 47 78 / 4%); background: #fff; }
.overview-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }.overview-heading h3 { margin: 4px 0 5px; color: var(--c-ink); font-size: 17px; }.overview-heading p { margin: 0; font-size: 12px; }
.overview-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0; margin-top: 22px; }.overview-metrics > div { padding: 0 20px; border-right: 1px solid #eef2f6; }.overview-metrics > div:first-child { padding-left: 0; }.overview-metrics > div:last-child { padding-right: 0; border-right: 0; }.overview-metrics span, .overview-metrics strong, .overview-metrics small { display: block; }.overview-metrics span { color: var(--c-muted); font-size: 11px; }.overview-metrics strong { margin: 5px 0; color: var(--c-ink); font-size: 26px; font-variant-numeric: tabular-nums; }.overview-metrics small { color: var(--c-muted); font-size: 11px; }
.overview-grid { display: grid; grid-template-columns: 1.2fr .8fr; gap: 14px; }.overview-list { display: grid; gap: 8px; margin: 16px 0 0; padding-left: 18px; color: var(--c-text); font-size: 12px; line-height: 1.7; }.overview-list li::marker { color: var(--c-primary); }.overview-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 18px; }.signal-list { display: grid; gap: 8px; margin-top: 13px; }.signal-list button { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 10px; border: 1px solid var(--c-line); border-radius: 7px; color: var(--c-text); background: #fafbfd; text-align: left; font: inherit; font-size: 12px; cursor: pointer; }.signal-list button:hover { border-color: #9ebad3; background: #f5f8fb; }
.unit-cost-card { padding-bottom: 12px; }.unit-selector { display: flex; align-items: center; gap: 8px; color: var(--c-muted); font-size: 11px; }.unit-selector .el-select { width: 130px; }.unit-cost-note { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 2px 4px 0; border-top: 1px solid #eef2f6; color: var(--c-muted); font-size: 11px; }
.unit-cost-groups { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px 14px; padding: 4px 4px 12px; }.unit-cost-groups > div { display: flex; align-items: center; gap: 8px; min-width: 0; color: var(--c-text); font-size: 11px; }.unit-cost-groups span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.unit-cost-groups strong { margin-left: auto; color: var(--c-ink); font-variant-numeric: tabular-nums; white-space: nowrap; }
.filters { flex-wrap: wrap; }.filters .el-input { width: 240px; }.section-heading .el-select { width: 130px; }
.chart-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.flow { display: flex; align-items: center; gap: 12px; margin: 20px 0; }.flow div { flex: 1; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 10px; min-width: 0; }.flow span,.flow strong,.flow small { display: block; }.flow strong { font-size: 20px; margin: 8px 0; }
.blacklake-page[data-shell-navigation] :deep(.el-tabs__header) { display: none; }
.order-checks { margin: 18px 0; padding: 16px; border: 1px solid #eadbbd; border-radius: 10px; background: #fffaf1; }.order-checks h3 { margin-bottom: 5px; color: #765b2a; }.plain-language { margin: 0 0 8px; color: #6f624f; }.order-checks ul { margin: 0; padding-left: 20px; color: #5e5549; line-height: 1.8; }.order-checks li + li { margin-top: 5px; }.alert-meta { display: block; color: #8d7c61; font-size: 12px; }
li { line-height: 1.9; } :deep(.el-drawer__body h3) { margin-top: 24px; }
@media(max-width: 960px) { .overview-grid { grid-template-columns: 1fr; }.overview-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 18px 0; }.overview-metrics > div:nth-child(2) { border-right: 0; }.overview-metrics > div:nth-child(3) { padding-left: 0; }.chart-grid { grid-template-columns: 1fr; }.page-header,.section-heading { flex-wrap: wrap; }.flow { flex-wrap: wrap; }.flow div { flex-basis: 100%; }.flow b { display: none; } }
@media(max-width: 560px) { .blacklake-page { padding: 20px 15px; }.overview-metrics { grid-template-columns: 1fr; }.overview-metrics > div { padding: 0 !important; border-right: 0; }.overview-heading { flex-direction: column; }.unit-cost-note { align-items: flex-start; flex-direction: column; }.unit-cost-groups { grid-template-columns: 1fr; }.filters .el-input { width: 100%; } }
</style>
<style scoped>.product-identity b,.product-identity small{display:block;font-weight:400;overflow:hidden;text-overflow:ellipsis}.product-identity small{font-size:11px;color:var(--c-text)}.comparison-evidence>summary{cursor:pointer;padding:16px;border-bottom:1px solid var(--c-line);color:var(--c-primary)}</style>
