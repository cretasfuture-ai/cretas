<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { getBlacklakeAnalysis, type AlertFollowup, type BlacklakeAnalysis, type CostDataAlert, type CostProject, type SectionInsightReport } from '@/api/smartbi/blacklake';
import DynamicChartRenderer from '@/components/smartbi/DynamicChartRenderer.vue';
import SectionInsight from '@/components/smartbi/PrototypeSectionInsight.vue';
import BlacklakeCostIntelligence from './BlacklakeCostIntelligence.vue';
import BlacklakeCostAlerts from './BlacklakePrototypeCostAlerts.vue';
import type { DynamicChartConfig } from '@/types/smartbi';
import UCard from '@nuxt/ui/components/Card.vue';
import UButton from '@nuxt/ui/components/Button.vue';
import UBadge from '@nuxt/ui/components/Badge.vue';
import { buildCostOverview, buildUnitCostChart, groupEligibleCostProjects } from './costOverview';

const props = defineProps<{ activeView?: string }>();
const emit = defineEmits<{ (event: 'update:activeView', value: string): void }>();
const data = ref<BlacklakeAnalysis | null>(null);
const refreshEpoch=ref(0);
const loading = ref(false);
const error = ref('');
const tab = ref('cost');
const search = ref('');
const issuesOnly = ref(false);
const eligibleOnly = ref(true);
const selected = ref<CostProject | null>(null);
const overviewUnit = ref('');
const focusedSection = ref('');
const focusedAlert = ref<CostDataAlert | null>(null);
const openRequest = ref(0);
const comparisonOpen=ref(false);
function toggleComparison(event:Event){comparisonOpen.value=(event.target as HTMLDetailsElement).open;}
const viewTitles:Record<string,string>={cost:'原料到成品成本分析',alerts:'异常与跟进',gaps:'关联与估值补查',production:'生产与工序',inventory:'库存与仓库',coverage:'数据覆盖与口径'};
function updateProgress(value:AlertFollowup){if(data.value)data.value.alertFollowups={...data.value.alertFollowups,[value.alertId]:value};}
const verifiedReferences = computed(() => selected.value ? data.value?.costIntelligence.verifiedMaterialReferences[selected.value.projectCode] || [] : []);
const unit = ref('');
const inventoryUnit = ref('');
const fmt = (value: number | null | undefined, digits = 2) => value == null ? '未提供' : value.toLocaleString('zh-CN', { maximumFractionDigits: digits });
const statusName = (value: string) => ({ '0': '未开始', '10': '进行中', '20': '已完工', '30': '已取消' }[value] || `状态 ${value}`);
const filtered = computed(() => (data.value?.cost.projects || []).filter(p =>
  (!eligibleOnly.value || p.unitCostAvailable) &&
  (!issuesOnly.value || p.issues.length > 0) && `${p.projectCode} ${p.product}`.toLowerCase().includes(search.value.toLowerCase())));
const units = computed(() => [...new Set(data.value?.production.planActual.map(p => p.unit).filter(Boolean) || [])]);
const inventoryUnits = computed(() => [...new Set(data.value?.inventory.records.map(p => p.unit).filter(Boolean) || [])]);
const planRows = computed(() => (data.value?.production.planActual || []).filter(p => p.unit === unit.value));
const stockRows = computed(() => (data.value?.inventory.records || []).filter(p => p.unit === inventoryUnit.value));
const stockTotals = computed(() => (data.value?.inventory.byWarehouseUnit || []).filter(p => p.unit === inventoryUnit.value));
const sectionInsights = computed<SectionInsightReport[]>(() => data.value?.sectionInsights || []);
const insightFor = (sectionId: string) => sectionInsights.value.find(report => report.sectionId === sectionId);
const overviewMetrics = computed(() => data.value ? buildCostOverview(data.value) : null);
const overviewGroups = computed(() => groupEligibleCostProjects(data.value?.cost.projects || [], overviewUnit.value || undefined));
const overviewUnits = computed(() => [...new Set((data.value?.cost.projects || []).filter(p => p.unitCostAvailable && p.unit).map(p => p.unit as string))]);
const overviewChart = computed(() => buildUnitCostChart(overviewGroups.value));
const fmtPercent = (value: number | null | undefined) => value == null ? '未提供' : `${value.toFixed(1)}%`;
const chart = (names: string[], series: { name: string; data: (number | null)[] }[], axis: string): DynamicChartConfig => ({
  chartType: 'bar', xAxis: { type: 'category', data: names }, yAxis: [{ type: 'value', name: axis }],
  series: series.map(s => ({ ...s, type: 'bar', yAxisIndex: 0 })),
});
const planChart = computed(() => chart(planRows.value.slice(0, 15).map(p => p.product), [
  { name: '计划', data: planRows.value.slice(0, 15).map(p => p.planned) },
  { name: '实际', data: planRows.value.slice(0, 15).map(p => p.actual) },
], unit.value));
const processChart = computed(() => {
  const rows = [...(data.value?.production.processes || [])].sort((a, b) => (b.hours || 0) - (a.hours || 0)).slice(0, 12);
  return chart(rows.map(p => p.process), [{ name: '累计工时', data: rows.map(p => p.hours) }], '小时');
});
const stockChart = computed(() => chart(stockTotals.value.map(p => p.warehouse), [{ name: '余额', data: stockTotals.value.map(p => p.quantity) }], inventoryUnit.value));
const coverageLabel: Record<string, string> = { project: '工单', task: '工序任务', output: '报工', stock_order: '出入库单', product: '产品', process: '工序', routing: '工艺路线', warehouse: '仓库', unit: '单位', bom: 'BOM', materials: '工单用料', stock: '库存余额', project_expand: '当前工序查询回执' };
watch(() => props.activeView, value => {
  if (value && value !== tab.value) tab.value = value;
}, { immediate: true });
watch(tab, value => {
  if (value !== props.activeView) emit('update:activeView', value);
});
async function load() {
  loading.value = true; error.value = '';
  try {
    const refreshed=await getBlacklakeAnalysis();
    selected.value=null;focusedAlert.value=null;focusedSection.value='';openRequest.value=0;
    data.value=refreshed;refreshEpoch.value++;
    unit.value = units.value.includes('kg') ? 'kg' : units.value[0] || '';
    inventoryUnit.value = inventoryUnits.value.includes('kg') ? 'kg' : inventoryUnits.value[0] || '';
    overviewUnit.value = overviewUnits.value.includes('kg') ? 'kg' : overviewUnits.value[0] || '';
  } catch (e) { error.value = e instanceof Error ? e.message : '读取失败，请重试'; data.value = null; }
  finally { loading.value = false; }
}
function goTo(section: string) {
  tab.value = section;
  void nextTick(()=>document.querySelector(section==='cost'?'[data-testid="cost-projects"]':'.blacklake-page .page-header')?.scrollIntoView({block:'start',behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'}));
}

function clearFocus(sectionId?: string) {
  if (!sectionId || focusedSection.value === sectionId) {
    focusedSection.value = '';
    focusedAlert.value = null;
  }
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
onMounted(load);
</script>

<template>
  <main :key="refreshEpoch" class="blacklake-page" :data-shell-navigation="props.activeView !== undefined" v-loading="loading">
    <header class="page-header">
      <div><span class="eyebrow">SMARTBI · 黑湖只读测试</span><h2>六膳门 · {{ viewTitles[tab] || '生产经营分析' }}</h2>
        <p>以工单贯通领退料、工序与成品；可识别直接成本单独披露。</p></div>
      <UButton color="neutral" variant="outline" :loading="loading" @click="load">刷新分析</UButton>
    </header>
    <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon />
    <template v-if="data">
      <p class="snapshot">采集时间：{{ new Date(data.snapshotAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}（北京时间） · 账号可见历史 · 库存为该时点快照</p>
      <section v-show="tab === 'cost'" class="overview-shell" aria-label="成本分析概览">
        <UCard class="overview-card overview-summary">
          <div class="overview-heading"><div><span class="eyebrow">先看结论，再看明细</span><h3>成本核算概览</h3><p>当前先展示工单覆盖与可核对信号；金额仅按同单位、可追溯工单计算。</p></div><UBadge color="warning" variant="soft">只读快照</UBadge></div>
          <div class="overview-metrics">
            <div><span>工单覆盖</span><strong>{{ overviewMetrics?.eligibleProjects }} / {{ overviewMetrics?.projectCount }}</strong><small>可核算工单 · {{ fmtPercent(overviewMetrics?.orderCoveragePercent) }}</small></div>
            <div><span>暂不能核算</span><strong>{{ overviewMetrics?.blockedProjects }}</strong><small>保留缺失原因，不补零</small></div>
            <div><span>关联领退料</span><strong>{{ overviewMetrics?.linkedMaterialLines }}</strong><small>另有 {{ overviewMetrics?.unlinkedMaterialLines }} 行未确认</small></div>
            <div><span>来源零值</span><strong class="warning">{{ overviewMetrics?.sourceZeroCostLines }}</strong><small>零值不等于免费</small></div>
          </div>
        </UCard>
        <div class="overview-grid">
          <UCard class="overview-card analysis-card">
            <div class="overview-heading"><div><h3>当前分析</h3><p>先处理能改变核算准备度的事项</p></div><UBadge color="primary" variant="soft">{{ data.production.projectCount }} 张工单</UBadge></div>
            <ul class="overview-list"><li>已识别 {{ overviewMetrics?.eligibleProjects }} 张工单具备单位直接成本字段，覆盖率按工单数计算。</li><li>{{ overviewMetrics?.sourceZeroCostLines }} 行材料来源为零值，需区分“源端零值”和“未提供”。</li><li>材料与人工分开披露，不据此推导完整制造成本、利润或跨工序总额。</li></ul>
            <div class="overview-actions"><UButton size="sm" @click="goTo('cost')">查看成本链路</UButton><UButton size="sm" color="neutral" variant="outline" @click="goTo('coverage')">查看数据口径</UButton></div>
          </UCard>
          <UCard class="overview-card priority-card">
            <div class="overview-heading"><div><h3>优先核对</h3><p>异常提醒按证据回到对应页面</p></div><UBadge color="warning" variant="soft">{{ data.costAlerts?.summary.alerts || 0 }} 条提醒</UBadge></div>
            <div class="signal-list"><button type="button" @click="goTo('alerts')"><span>成本差异信号</span><UBadge color="warning" variant="soft">{{ overviewMetrics?.businessSignalCount }}</UBadge></button><button type="button" @click="goTo('alerts')"><span>数据质量</span><UBadge color="neutral" variant="soft">{{ overviewMetrics?.dataQualityAlertCount }}</UBadge></button><button type="button" @click="goTo('alerts')"><span>核算准备条件</span><UBadge color="error" variant="soft">{{ overviewMetrics?.readinessAlertCount }}</UBadge></button></div>
            <div class="overview-actions"><UButton size="sm" color="neutral" variant="outline" @click="goTo('alerts')">打开异常提醒</UButton></div>
          </UCard>
        </div>
        <UCard class="overview-card unit-cost-card">
          <div class="overview-heading"><div><h3>产品单位直接成本</h3><p>材料 + 人工按同一成品单位堆叠；不合并不同单位或冲突产品名。</p></div><div class="unit-selector"><span>成品单位</span><el-select v-model="overviewUnit" aria-label="产品单位成本单位"><el-option v-for="u in overviewUnits" :key="u" :label="u" :value="u" /></el-select></div></div>
          <DynamicChartRenderer v-if="overviewGroups.length" :config="overviewChart" :height="250" presentation="plain" />
          <el-empty v-else description="当前没有同单位且具备材料、人工、成品量的可核算工单" />
          <div v-if="overviewGroups.length" class="unit-cost-groups" data-testid="unit-cost-groups"><div v-for="group in overviewGroups" :key="group.key"><span class="product-identity"><b>{{ group.product }}</b><small>{{ group.displayIdentity }}</small></span><UBadge color="neutral" variant="soft">{{ group.sampleCount }} 条工单</UBadge><strong>{{ fmt(group.weightedUnitCost, 4) }} / {{ group.unit }}</strong></div></div>
          <div class="unit-cost-note"><span>加权口径：各产品组材料成本 ÷ 成品良品量 + 人工成本 ÷ 成品良品量。</span><UButton size="sm" color="neutral" variant="ghost" @click="goTo('cost')">查看逐工单明细</UButton></div>
          <SectionInsight v-if="insightFor('product_cost')" :report="insightFor('product_cost')!" :focus-alert="focusedSection === 'product_cost' ? focusedAlert : null" :open-request="focusedSection === 'product_cost' ? openRequest : 0" @clear-focus="clearFocus('product_cost')" />
        </UCard>
      </section>
      <el-tabs v-model="tab">
        <el-tab-pane label="原料 → 成品成本" name="cost">
          <el-card shadow="never">
            <div class="section-heading"><h3>逐工单成本链路</h3><div class="filters"><el-input v-model="search" placeholder="搜索工单或产品" clearable aria-label="搜索工单或产品" /><el-checkbox v-model="eligibleOnly">仅看可核算工单</el-checkbox><el-checkbox v-model="issuesOnly">仅看待核对</el-checkbox></div></div>
            <p class="note">金额按来源币种展示（接口未返回币种，待业务核实）。材料净额 = 领料成本 − 退料成本；人工只取任务累计工资，不叠加报工估算。未核实工单不展示单位成本。</p>
            <el-table :data="filtered" stripe height="480" @row-click="selected = $event" data-testid="cost-projects">
              <el-table-column prop="projectCode" label="工单" width="155" fixed />
              <el-table-column prop="product" label="成品" min-width="180" show-overflow-tooltip />
              <el-table-column label="状态" width="85"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
              <el-table-column label="成品良品量" min-width="125"><template #default="{ row }">{{ fmt(row.finishedGood) }} {{ row.unit }}</template></el-table-column>
              <el-table-column label="已识别材料净额" width="110"><template #default="{ row }">{{ fmt(row.materialCost) }}</template></el-table-column>
              <el-table-column label="已识别人工累计" width="110"><template #default="{ row }">{{ fmt(row.laborCost) }}</template></el-table-column>
              <el-table-column label="单位直接成本" min-width="145"><template #default="{ row }">{{ row.unitCostAvailable ? `${fmt(row.unitIdentifiedDirectCost, 4)} / ${row.unit}` : '待核对' }}</template></el-table-column>
              <el-table-column label="核对状态" min-width="185"><template #default="{ row }"><el-tag :type="row.issues.length ? 'warning' : 'success'">{{ row.issues.length ? `${row.issues.length} 项待核对` : '来源字段具备' }}</el-tag></template></el-table-column>
              <el-table-column label="链路" width="95" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="selected = row">查看链路</el-button></template></el-table-column>
            </el-table>
            <SectionInsight v-if="insightFor('finished')" :report="insightFor('finished')!" :focus-alert="focusedSection === 'finished' ? focusedAlert : null" :open-request="focusedSection === 'finished' ? openRequest : 0" @clear-focus="clearFocus('finished')" />
          </el-card>
          <details class="comparison-evidence" @toggle="toggleComparison"><summary>展开同产品工单差额与逐物料证据</summary><BlacklakeCostIntelligence v-if="comparisonOpen" :intelligence="data.costIntelligence" /></details>
          <el-card shadow="never" class="section-card"><h3>加工人工 · 可核算工单</h3>
            <p class="note">单独查看人工贡献，不与报工估算工资重复累加。</p>
            <el-table :data="data.eligibleCostReview?.projects || []" height="300" stripe data-testid="cost-labor">
              <el-table-column prop="projectCode" label="工单" min-width="150" />
              <el-table-column prop="product" label="产品" min-width="160" />
              <el-table-column label="累计人工"><template #default="{ row }">{{ fmt(row.laborCost) }}</template></el-table-column>
              <el-table-column label="单位成品人工"><template #default="{ row }">{{ fmt(row.laborPerFinished, 4) }} / {{ row.unit }}</template></el-table-column>
              <el-table-column label="人工占比"><template #default="{ row }">{{ row.laborShare == null ? '待核' : `${fmt(row.laborShare * 100)}%` }}</template></el-table-column>
            </el-table>
            <SectionInsight v-if="insightFor('labor')" :report="insightFor('labor')!" :focus-alert="focusedSection === 'labor' ? focusedAlert : null" :open-request="focusedSection === 'labor' ? openRequest : 0" @clear-focus="clearFocus('labor')" />
          </el-card>
          <el-card shadow="never" class="section-card"><h3>全历史原料净消耗（已关联工单）</h3><p class="note">按物料与来源单位保留；不混加不同单位。未关联领退料未计入下表。</p>
            <el-table :data="data.cost.materials" height="340" stripe><el-table-column prop="material" label="原料" min-width="190" /><el-table-column prop="unit" label="单位" width="85" /><el-table-column label="净消耗"><template #default="{ row }">{{ fmt(row.netConsumption) }}</template></el-table-column><el-table-column label="已识别净额"><template #default="{ row }">{{ fmt(row.recognizedCost) }}</template></el-table-column><el-table-column prop="lines" label="领退料行数" /></el-table>
            <SectionInsight v-if="insightFor('material')" :report="insightFor('material')!" :focus-alert="focusedSection === 'material' ? focusedAlert : null" :open-request="focusedSection === 'material' ? openRequest : 0" @clear-focus="clearFocus('material')" />
          </el-card>
        </el-tab-pane>
        <el-tab-pane label="成本与数据异常" name="alerts">
          <BlacklakeCostAlerts v-if="data.costAlerts && data.eligibleCostReview" :alerts="data.costAlerts" :review="data.eligibleCostReview" :followups="data.alertFollowups" @ask="focusAlert" @updated="updateProgress" />
          <el-alert v-else type="warning" :closable="false" title="异常分析结果未返回，请核对分析服务。" />
        </el-tab-pane>
        <el-tab-pane label="关联与估值补查" name="gaps">
          <BlacklakeCostIntelligence :intelligence="data.costIntelligence" mode="gaps" />
        </el-tab-pane>
        <el-tab-pane label="生产与工序" name="production">
          <div class="chart-grid"><el-card shadow="never"><div class="section-heading"><h3>计划与实际 · 前 15 个产品</h3><el-select v-model="unit" aria-label="生产数量单位"><el-option v-for="u in units" :key="u" :label="u" :value="u" /></el-select></div><DynamicChartRenderer :config="planChart" /></el-card><el-card shadow="never"><h3>工序累计工时 · 前 12 项</h3><p class="note">累计快照，用于定位需关注工序，不能据此认定效率或瓶颈原因。</p><DynamicChartRenderer :config="processChart" /></el-card></div>
          <el-card shadow="never" class="section-card"><h3>已审批报工质量 · 按产品 / 工序 / 单位</h3><el-table :data="data.production.quality" height="400" stripe><el-table-column prop="product" label="产品" min-width="180" /><el-table-column prop="process" label="工序" /><el-table-column prop="unit" label="单位" width="75" /><el-table-column label="良品数"><template #default="{ row }">{{ fmt(row.good) }}</template></el-table-column><el-table-column label="不良数"><template #default="{ row }">{{ fmt(row.defective) }}</template></el-table-column><el-table-column label="良品率"><template #default="{ row }">{{ row.goodRate == null ? '未提供' : `${fmt(row.goodRate * 100)}%` }}</template></el-table-column></el-table></el-card>
            <SectionInsight v-if="insightFor('production')" :report="insightFor('production')!" :focus-alert="focusedSection === 'production' ? focusedAlert : null" :open-request="focusedSection === 'production' ? openRequest : 0" @clear-focus="clearFocus('production')" />
        </el-tab-pane>
        <el-tab-pane label="库存与仓库" name="inventory">
          <el-alert type="info" :closable="false" :title="`已返回 ${data.inventory.records.length} 条余额；另有 ${data.inventory.pairsNotReturned} 个产品与仓库组合未返回，保持未知。`" />
            <el-card shadow="never" class="section-card"><div class="section-heading"><h3>仓库余额 · 同单位比较</h3><el-select v-model="inventoryUnit" aria-label="库存数量单位"><el-option v-for="u in inventoryUnits" :key="u" :label="u" :value="u" /></el-select></div><DynamicChartRenderer :config="stockChart" /><el-table :data="stockRows" height="380" stripe><el-table-column prop="product" label="物料 / 产品" min-width="200" /><el-table-column prop="warehouse" label="仓库" /><el-table-column prop="unit" label="单位" /><el-table-column label="来源余额"><template #default="{ row }">{{ fmt(row.quantity) }}</template></el-table-column></el-table><SectionInsight v-if="insightFor('inventory')" :report="insightFor('inventory')!" :focus-alert="focusedSection === 'inventory' ? focusedAlert : null" :open-request="focusedSection === 'inventory' ? openRequest : 0" @clear-focus="clearFocus('inventory')" /></el-card>
        </el-tab-pane>
        <el-tab-pane label="数据覆盖与口径" name="coverage">
          <el-card shadow="never"><h3>数据集已接入 13 类资料</h3><el-table :data="data.catalog"><el-table-column label="资料"><template #default="{ row }">{{ coverageLabel[row.resource] || row.resource }}</template></el-table-column><el-table-column prop="count" label="条数" /></el-table><h3>分析发现</h3><ul><li v-for="item in data.findings" :key="item">{{ item }}</li></ul><h3>使用边界</h3><ul><li v-for="item in data.limitations" :key="item">{{ item }}</li></ul></el-card>
            <SectionInsight v-if="insightFor('coverage')" :report="insightFor('coverage')!" :focus-alert="focusedSection === 'coverage' ? focusedAlert : null" :open-request="focusedSection === 'coverage' ? openRequest : 0" @clear-focus="clearFocus('coverage')" />
        </el-tab-pane>
      </el-tabs>
      <el-drawer :model-value="!!selected" @close="selected = null" size="min(100%, 1080px)" title="原料 → 工序 → 成品 · 工单成本链路">
        <template v-if="selected"><h2>{{ selected.projectCode }} · {{ selected.product }}</h2>
          <div class="flow"><div><span>原料领退料</span><strong>{{ selected.materialLines }} 行</strong><small>净额 {{ fmt(selected.materialCost) }}</small></div><b aria-hidden="true">→</b><div><span>生产工序</span><strong>{{ selected.taskCount }} 项</strong><small>累计人工 {{ fmt(selected.laborCost) }}</small></div><b aria-hidden="true">→</b><div><span>工单成品良品量</span><strong>{{ fmt(selected.finishedGood) }} {{ selected.unit }}</strong><small>{{ statusName(selected.status) }}</small></div></div>
          <el-alert v-if="selected.issues.length" type="warning" :closable="false" title="暂不展示单位成本"><template #default><ul><li v-for="issue in selected.issues" :key="issue">{{ issue }}</li></ul></template></el-alert>
          <el-alert v-else type="success" :closable="false" :title="`单位可识别直接成本 ${fmt(selected.unitIdentifiedDirectCost, 4)} / ${selected.unit}（来源币种，非完整制造成本）`" />
          <p class="note">材料金额有值 {{ selected.materialCostKnownLines }}/{{ selected.materialLines }} 行；累计工资有值 {{ selected.laborCostKnownTasks }}/{{ selected.taskCount }} 项。缺失不补零。</p><h3>实际领料与退料明细</h3><el-table :data="selected.materialLinesDetail" stripe><el-table-column prop="material" label="原料" min-width="170" /><el-table-column prop="orderCode" label="来源出入库单" min-width="150" /><el-table-column label="净消耗"><template #default="{ row }">{{ fmt(row.netConsumption) }} {{ row.unit }}</template></el-table-column><el-table-column label="来源单价"><template #default="{ row }">{{ fmt(row.unitPrice, 4) }}</template></el-table-column><el-table-column label="来源净额"><template #default="{ row }">{{ fmt(row.recognizedCost) }}</template></el-table-column></el-table>
          <h3>工序进度与累计人工</h3><el-table :data="selected.processes" stripe><el-table-column prop="process" label="工序" /><el-table-column label="状态"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column><el-table-column label="累计工时（小时）"><template #default="{ row }">{{ fmt(row.hours) }}</template></el-table-column><el-table-column label="累计工资"><template #default="{ row }">{{ fmt(row.laborCost) }}</template></el-table-column></el-table>
          <h3>工单用料参考 · 父级查询已核实</h3>
          <p class="note">已按工单ID和编号交叉查询验证归属；源端单位用量的换算基准待核，不替代实际领料或成本。</p>
          <el-table :data="verifiedReferences" stripe><el-table-column prop="materialCode" label="物料代码" /><el-table-column prop="feedProcessCode" label="投料工序代码" /><el-table-column prop="sourceUnitQty" label="源端单位用量（基准待核）" /></el-table>
          <p class="note">数量未跨工序求和。工资估算未重复叠加；BOM 标准用量未代替实际领料。</p>
        </template>
      </el-drawer>
    </template>
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
li { line-height: 1.9; } :deep(.el-drawer__body h3) { margin-top: 24px; }
@media(max-width: 960px) { .overview-grid { grid-template-columns: 1fr; }.overview-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 18px 0; }.overview-metrics > div:nth-child(2) { border-right: 0; }.overview-metrics > div:nth-child(3) { padding-left: 0; }.chart-grid { grid-template-columns: 1fr; }.page-header,.section-heading { flex-wrap: wrap; }.flow { flex-wrap: wrap; }.flow div { flex-basis: 100%; }.flow b { display: none; } }
@media(max-width: 560px) { .blacklake-page { padding: 20px 15px; }.overview-metrics { grid-template-columns: 1fr; }.overview-metrics > div { padding: 0 !important; border-right: 0; }.overview-heading { flex-direction: column; }.unit-cost-note { align-items: flex-start; flex-direction: column; }.unit-cost-groups { grid-template-columns: 1fr; }.filters .el-input { width: 100%; } }
</style>
<style scoped>.product-identity b,.product-identity small{display:block;font-weight:400;overflow:hidden;text-overflow:ellipsis}.product-identity small{font-size:11px;color:var(--c-text)}.comparison-evidence>summary{cursor:pointer;padding:16px;border-bottom:1px solid var(--c-line);color:var(--c-primary)}</style>
