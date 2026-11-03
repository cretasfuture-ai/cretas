<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getBlacklakeAnalysis, type BlacklakeAnalysis, type CostProject } from '@/api/smartbi/blacklake';
import DynamicChartRenderer from '@/components/smartbi/DynamicChartRenderer.vue';
import BlacklakeCostIntelligence from './BlacklakeCostIntelligence.vue';
import type { DynamicChartConfig } from '@/types/smartbi';

const data = ref<BlacklakeAnalysis | null>(null);
const loading = ref(false);
const error = ref('');
const tab = ref('cost');
const search = ref('');
const issuesOnly = ref(false);
const selected = ref<CostProject | null>(null);
const verifiedReferences = computed(() => selected.value ? data.value?.costIntelligence.verifiedMaterialReferences[selected.value.projectCode] || [] : []);
const unit = ref('');
const inventoryUnit = ref('');
const fmt = (value: number | null | undefined, digits = 2) => value == null ? '未提供' : value.toLocaleString('zh-CN', { maximumFractionDigits: digits });
const statusName = (value: string) => ({ '0': '未开始', '10': '进行中', '20': '已完工', '30': '已取消' }[value] || `状态 ${value}`);
const filtered = computed(() => (data.value?.cost.projects || []).filter(p =>
  (!issuesOnly.value || p.issues.length > 0) && `${p.projectCode} ${p.product}`.toLowerCase().includes(search.value.toLowerCase())));
const units = computed(() => [...new Set(data.value?.production.planActual.map(p => p.unit).filter(Boolean) || [])]);
const inventoryUnits = computed(() => [...new Set(data.value?.inventory.records.map(p => p.unit).filter(Boolean) || [])]);
const planRows = computed(() => (data.value?.production.planActual || []).filter(p => p.unit === unit.value));
const stockRows = computed(() => (data.value?.inventory.records || []).filter(p => p.unit === inventoryUnit.value));
const stockTotals = computed(() => (data.value?.inventory.byWarehouseUnit || []).filter(p => p.unit === inventoryUnit.value));
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
async function load() {
  loading.value = true; error.value = '';
  try {
    data.value = await getBlacklakeAnalysis();
    unit.value = units.value.includes('kg') ? 'kg' : units.value[0] || '';
    inventoryUnit.value = inventoryUnits.value.includes('kg') ? 'kg' : inventoryUnits.value[0] || '';
  } catch (e) { error.value = e instanceof Error ? e.message : '读取失败，请重试'; data.value = null; }
  finally { loading.value = false; }
}
onMounted(load);
</script>

<template>
  <main class="blacklake-page" v-loading="loading">
    <header class="page-header">
      <div><span class="eyebrow">SMARTBI · 黑湖只读测试</span><h2>六膳门 · 原料到成品成本分析</h2>
        <p>以工单贯通领退料、工序与成品；可识别直接成本单独披露。</p></div>
      <el-button @click="load" :loading="loading">刷新分析</el-button>
    </header>
    <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon />
    <template v-if="data">
      <el-alert type="warning" :closable="false" show-icon title="成本尚有缺口：来源零值需核实，可识别直接成本不等于完整制造成本或毛利。" />
      <p class="snapshot">采集时间：{{ new Date(data.snapshotAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}（北京时间） · 账号可见历史 · 库存为该时点快照</p>
      <section class="kpi-grid" aria-label="数据与成本覆盖">
        <el-card shadow="never"><span>工单</span><strong>{{ data.production.projectCount }}</strong><small>已完工 {{ data.production.statuses['20'] || 0 }} 张</small></el-card>
        <el-card shadow="never"><span>已关联生产领退料</span><strong>{{ data.cost.linkedMaterialLines }} <small>行</small></strong><small>另有 {{ data.cost.unlinkedMaterialLines }} 行关联未确认</small></el-card>
        <el-card shadow="never"><span>材料金额源端零值</span><strong class="warning">{{ data.cost.sourceZeroCostLines }} <small>行</small></strong><small>有值 {{ data.cost.knownMaterialCostLines }} 行，零值不等于免费</small></el-card>
        <el-card shadow="never"><span>单位直接成本可展示</span><strong>{{ data.cost.unitCostAvailableProjects }} / {{ data.production.projectCount }}</strong><small>其他工单逐项显示缺失原因</small></el-card>
      </section>
      <el-tabs v-model="tab">
        <el-tab-pane label="原料 → 成品成本" name="cost">
          <BlacklakeCostIntelligence :intelligence="data.costIntelligence" />
          <el-card shadow="never">
            <div class="section-heading"><h3>逐工单成本链路</h3><div class="filters"><el-input v-model="search" placeholder="搜索工单或产品" clearable aria-label="搜索工单或产品" /><el-checkbox v-model="issuesOnly">仅看待核对</el-checkbox></div></div>
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
          </el-card>
          <el-card shadow="never" class="section-card"><h3>全历史原料净消耗（已关联工单）</h3><p class="note">按物料与来源单位保留；不混加不同单位。未关联领退料未计入下表。</p>
            <el-table :data="data.cost.materials" height="340" stripe><el-table-column prop="material" label="原料" min-width="190" /><el-table-column prop="unit" label="单位" width="85" /><el-table-column label="净消耗"><template #default="{ row }">{{ fmt(row.netConsumption) }}</template></el-table-column><el-table-column label="已识别净额"><template #default="{ row }">{{ fmt(row.recognizedCost) }}</template></el-table-column><el-table-column prop="lines" label="领退料行数" /></el-table>
          </el-card>
        </el-tab-pane>
        <el-tab-pane label="关联与估值补查" name="gaps">
          <BlacklakeCostIntelligence :intelligence="data.costIntelligence" mode="gaps" />
        </el-tab-pane>
        <el-tab-pane label="生产与工序" name="production">
          <div class="chart-grid"><el-card shadow="never"><div class="section-heading"><h3>计划与实际 · 前 15 个产品</h3><el-select v-model="unit" aria-label="生产数量单位"><el-option v-for="u in units" :key="u" :label="u" :value="u" /></el-select></div><DynamicChartRenderer :config="planChart" /></el-card><el-card shadow="never"><h3>工序累计工时 · 前 12 项</h3><p class="note">累计快照，用于定位需关注工序，不能据此认定效率或瓶颈原因。</p><DynamicChartRenderer :config="processChart" /></el-card></div>
          <el-card shadow="never" class="section-card"><h3>已审批报工质量 · 按产品 / 工序 / 单位</h3><el-table :data="data.production.quality" height="400" stripe><el-table-column prop="product" label="产品" min-width="180" /><el-table-column prop="process" label="工序" /><el-table-column prop="unit" label="单位" width="75" /><el-table-column label="良品数"><template #default="{ row }">{{ fmt(row.good) }}</template></el-table-column><el-table-column label="不良数"><template #default="{ row }">{{ fmt(row.defective) }}</template></el-table-column><el-table-column label="良品率"><template #default="{ row }">{{ row.goodRate == null ? '未提供' : `${fmt(row.goodRate * 100)}%` }}</template></el-table-column></el-table></el-card>
        </el-tab-pane>
        <el-tab-pane label="库存与仓库" name="inventory">
          <el-alert type="info" :closable="false" :title="`已返回 ${data.inventory.records.length} 条余额；另有 ${data.inventory.pairsNotReturned} 个产品与仓库组合未返回，保持未知。`" />
          <el-card shadow="never" class="section-card"><div class="section-heading"><h3>仓库余额 · 同单位比较</h3><el-select v-model="inventoryUnit" aria-label="库存数量单位"><el-option v-for="u in inventoryUnits" :key="u" :label="u" :value="u" /></el-select></div><DynamicChartRenderer :config="stockChart" /><el-table :data="stockRows" height="380" stripe><el-table-column prop="product" label="物料 / 产品" min-width="200" /><el-table-column prop="warehouse" label="仓库" /><el-table-column prop="unit" label="单位" /><el-table-column label="来源余额"><template #default="{ row }">{{ fmt(row.quantity) }}</template></el-table-column></el-table></el-card>
        </el-tab-pane>
        <el-tab-pane label="数据覆盖与口径" name="coverage">
          <el-card shadow="never"><h3>数据集已接入 13 类资料</h3><el-table :data="data.catalog"><el-table-column label="资料"><template #default="{ row }">{{ coverageLabel[row.resource] || row.resource }}</template></el-table-column><el-table-column prop="count" label="条数" /></el-table><h3>分析发现</h3><ul><li v-for="item in data.findings" :key="item">{{ item }}</li></ul><h3>使用边界</h3><ul><li v-for="item in data.limitations" :key="item">{{ item }}</li></ul></el-card>
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
.blacklake-page { padding: 20px; color: var(--text-primary, #1a2332); min-width: 0; }
.page-header, .section-heading, .filters { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header { margin-bottom: 20px; align-items: flex-start; }
h2 { margin: 8px 0; font-size: 24px; } h3 { margin: 0 0 16px; font-size: 16px; }
p, small, .note, .snapshot { color: var(--text-secondary, #7a8599); line-height: 1.7; }
.eyebrow { color: var(--el-color-primary, #1b65a8); font-size: 12px; letter-spacing: 1px; }
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin: 20px 0; }
.kpi-grid span, .kpi-grid small, .kpi-grid strong { display: block; }.kpi-grid strong { font-size: 30px; margin: 10px 0; }.kpi-grid strong small { display: inline; font-size: 14px; font-weight: normal; }
.warning { color: var(--el-color-warning); }.el-card { border-radius: 10px; }.section-card { margin-top: 20px; }
.filters { flex-wrap: wrap; }.filters .el-input { width: 240px; }.section-heading .el-select { width: 130px; }
.chart-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
.flow { display: flex; align-items: center; gap: 12px; margin: 20px 0; }.flow div { flex: 1; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 10px; min-width: 0; }.flow span,.flow strong,.flow small { display: block; }.flow strong { font-size: 20px; margin: 8px 0; }
li { line-height: 1.9; } :deep(.el-drawer__body h3) { margin-top: 24px; }
@media(max-width: 960px) { .kpi-grid { grid-template-columns: repeat(2,minmax(0,1fr)); }.chart-grid { grid-template-columns: 1fr; }.page-header,.section-heading { flex-wrap: wrap; }.flow { flex-wrap: wrap; }.flow div { flex-basis: 100%; }.flow b { display: none; } }
</style>
