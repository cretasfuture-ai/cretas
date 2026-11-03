<script setup lang="ts">
import { computed, ref } from 'vue';
import VChart from 'vue-echarts';
import '@/utils/echarts';
import type { BlacklakeCostIntelligence } from '@/api/smartbi/blacklake';
const props = withDefaults(defineProps<{ intelligence: BlacklakeCostIntelligence; mode?: 'analysis' | 'gaps' }>(), { mode: 'analysis' });
const selectedId = ref('');
const comparison = computed(() => props.intelligence.comparisons.find(c => c.candidateId === selectedId.value) || props.intelligence.comparisons.find(c => c.bridge) || props.intelligence.comparisons[0]);
const card = computed(() => props.intelligence.recommendations.find(c => c.id === comparison.value?.candidateId));
const gap = computed(() => props.intelligence.gapEvidence);
const summary = computed(() => gap.value.summary || {});
const fmt = (value: number | null | undefined) => value == null ? '不可计算' : (value === 0 ? 0 : value).toLocaleString('zh-CN', { maximumFractionDigits: 4 });
</script>

<template>
  <section v-if="mode === 'analysis'" class="intelligence" data-testid="cost-intelligence">
    <el-card shadow="never">
      <div class="heading"><div><h3>同产品工单 · 成本差异追溯</h3><p>按产品代码与成品单位隔离比较；只分析已通过校验的工单子集。</p></div>
        <el-select :model-value="comparison?.candidateId" @update:model-value="selectedId = $event" aria-label="选择成本对比产品" placeholder="选择产品">
          <el-option v-for="c in intelligence.comparisons" :key="c.candidateId" :value="c.candidateId" :label="`${c.product} · ${c.unit}（${c.eligibleProjects}/${c.visibleProjects}张）`" />
        </el-select>
      </div>
      <template v-if="comparison && card">
        <div class="metrics"><div><small>可比较工单</small><strong>{{ comparison.eligibleProjects }} / {{ comparison.visibleProjects }}</strong></div><div><small>通过校验子集 · 加权单位直接成本</small><strong>{{ fmt(comparison.weightedUnitCost) }} / {{ comparison.unit }}</strong></div><div><small>子集最低 / 最高</small><strong>{{ fmt(comparison.minimum) }} / {{ fmt(comparison.maximum) }}</strong></div></div>
        <p class="note">金额使用来源币种，币种尚未核实；不含设备、能耗与制造费用，不能作为完整制造成本或毛利。</p>
        <VChart class="source-chart" :option="card.chart.echartsOption" autoresize />
        <el-alert v-if="!comparison.bridge" type="info" :closable="false" title="当前该产品只有一张通过校验的工单，不生成工单间差异结论。" />
        <template v-if="comparison.bridge">
          <h4>差异构成：{{ comparison.bridge.comparisonProject }} 对比 {{ comparison.bridge.baselineProject }}</h4>
          <p>{{ comparison.bridge.selection }}</p>
          <div class="metrics bridge-metrics"><div><small>材料单位成本差额</small><strong>{{ fmt(comparison.bridge.materialDelta) }}</strong></div><div><small>人工单位成本差额</small><strong>{{ fmt(comparison.bridge.laborDelta) }}</strong></div><div><small>单位直接成本差额</small><strong>{{ fmt(comparison.bridge.delta) }} / {{ comparison.unit }}</strong></div></div>
          <el-table :data="comparison.bridge.materials" stripe data-testid="cost-bridge-materials">
            <el-table-column prop="material" label="原料" min-width="180" />
            <el-table-column label="基准单位耗用" min-width="145"><template #default="{ row }">{{ fmt(row.baselineUsage) }} {{ row.unit }}/{{ comparison.unit }}</template></el-table-column>
            <el-table-column label="对比单位耗用" min-width="145"><template #default="{ row }">{{ fmt(row.comparisonUsage) }} {{ row.unit }}/{{ comparison.unit }}</template></el-table-column>
            <el-table-column label="材料成本差额" min-width="130"><template #default="{ row }">{{ fmt(row.delta) }}</template></el-table-column>
            <el-table-column label="耗用量分解项" min-width="130"><template #default="{ row }">{{ fmt(row.usageEffect) }}</template></el-table-column>
            <el-table-column label="折算价格分解项" min-width="140"><template #default="{ row }">{{ fmt(row.priceEffect) }}</template></el-table-column>
          </el-table>
          <p class="note">材料差额 + 人工差额 = 单位直接成本差额；对账差额 {{ fmt(comparison.bridge.reconciliationDifference) }}。以上是代数分解，不能据此认定采购涨价、损耗或人员效率原因；新增材料项不强行拆分。</p>
        </template>
        <div class="findings"><h4>需关注</h4><p v-for="text in card.findings.attention" :key="text">{{ text }}</p><h4>建议核对</h4><p v-for="text in card.findings.suggestions" :key="text">{{ text }}</p></div>
      </template>
      <el-empty v-else description="尚无通过校验的同产品成本数据" />
    </el-card>
  </section>
  <section v-else class="intelligence" data-testid="cost-gap-evidence">
    <el-alert v-if="gap.status !== 'complete'" type="warning" :closable="false" :title="`补查证据不可用：${gap.status || 'unknown'}；保留原归档口径。`" />
    <template v-else>
      <el-alert type="success" :closable="false" :title="`${summary.materialsQueryVerified || 0} 条用料记录的父级查询已交叉核实`" />
      <p class="note">已核对 {{ summary.materialProjectsChecked }}/{{ summary.materialProjectsExpected }} 张工单，失败 {{ summary.failedProjects }} 张；分别按 ID 和编号查询，与归档核对。负对照：{{ gap.negativeControlVerified ? '源端明确拒绝不存在的工单' : '未形成有效证据' }}。原响应未提供 projectId，不制造源端字段。</p>
      <div class="metrics"><div><small>仍无工单关联的领退料</small><strong>{{ summary.unlinkedStillMissing || 0 }} 行</strong></div><div><small>补查仍为零的领料金额</small><strong>{{ summary.zeroAmountsReconfirmed || 0 }} 行</strong></div><div><small>当前移动均价线索</small><strong>{{ summary.productsWithCurrentPriceClues || 0 }} 种物料</strong></div></div>
      <el-card shadow="never"><h3>未闭合的关联排查</h3><el-table :data="gap.unresolvedLinks || []" stripe><el-table-column prop="orderCode" label="来源领退料单" min-width="180" /><el-table-column prop="material" label="物料" min-width="180" /><el-table-column label="本次源端状态" min-width="200"><template #default="{ row }">{{ row.status === 'source_still_has_no_link' ? '关联指针仍为空' : '源记录已变化或未返回，需重新核实' }}</template></el-table-column><el-table-column prop="action" label="下一步核对" min-width="300" /></el-table></el-card>
      <el-card shadow="never" class="section"><h3>价格线索 · 与历史实际成本分开</h3><p class="note">本次复核 {{ summary.zeroAmountsReconfirmed || 0 }} 行金额仍为零，{{ summary.zeroMovementUnitPrices || 0 }} 行成本单价仍为零；单价缺失 {{ summary.movementUnitPriceMissing || 0 }} 行、变化 {{ summary.movementUnitPriceChanged || 0 }} 行。当前移动均价不能代替历史领料成本；历史同单位较早时点的参考价格线索共 {{ summary.priceReferenceRows || 0 }} 条，均不得直接回填。</p>
        <el-table :data="gap.currentPriceClues || []" stripe><el-table-column prop="material" label="物料" min-width="180" /><el-table-column prop="unit" label="来源单位" /><el-table-column label="当前移动均价"><template #default="{ row }">{{ fmt(row.price) }}</template></el-table-column><el-table-column prop="usage" label="证据限制" min-width="300" /></el-table>
        <p class="note">本次补查共 {{ summary.freshStockOrders || 0 }} 张出入库单，原成本归档仍为332张；新增单据不混入旧时点金额。查询时间：{{ gap.checkedAt }}</p>
      </el-card>
    </template>
    <el-card shadow="never" class="section"><h3>成本核对清单</h3><p class="note">同一工单可能有多个问题，以下数量不能相加当作工单数。BOM单位与损耗口径未核实前，不计算标准耗用差异。</p><el-table :data="intelligence.worklist" stripe><el-table-column prop="issue" label="需核对的问题" min-width="300" /><el-table-column prop="projects" label="涉及工单数" width="140" /></el-table></el-card>
  </section>
</template>

<style scoped>
.intelligence { margin-bottom: 20px; min-width: 0; }.heading { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }.heading .el-select { width: 360px; max-width: 100%; flex-shrink: 0; }h3 { margin: 0 0 12px; font-size: 17px; }h4 { margin: 20px 0 12px; }p { line-height: 1.8; }.note,small { color: #7a8599; }.source-chart { height: 320px; }.metrics { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 16px; margin: 20px 0; }.metrics div { padding: 16px; background: #f4f6f9; border-radius: 10px; }.metrics small,.metrics strong { display: block; }.metrics strong { margin-top: 8px; font-size: 23px; }.bridge-metrics strong { font-size: 20px; }.section { margin-top: 20px; }.el-card { border-radius: 10px; }
@media(max-width: 960px) { .heading { flex-wrap: wrap; }.heading .el-select { width: 100%; }.metrics { grid-template-columns: 1fr; } }
</style>
