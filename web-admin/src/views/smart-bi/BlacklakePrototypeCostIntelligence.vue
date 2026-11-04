<script setup lang="ts">
import { computed, ref } from 'vue';
import VChart from 'vue-echarts';
import '@/utils/echarts';
import type { BlacklakeCostIntelligence, CostProject } from '@/api/smartbi/blacklake';
import { localizeBlacklakeText } from './blacklakePresentation';
import { comparableCostGroups } from './blacklakeComparison';

const props = withDefaults(defineProps<{ intelligence: BlacklakeCostIntelligence; projects: CostProject[]; mode?: 'analysis' | 'gaps' }>(), { mode: 'analysis' });
const selectedId = ref('');
const comparisons = computed(() => comparableCostGroups(props.intelligence.comparisons, props.projects));
const excludedGroups = computed(() => props.intelligence.comparisons.length - comparisons.value.length);
const comparison = computed(() => comparisons.value.find(item => item.candidateId === selectedId.value)
  || comparisons.value.find(item => item.bridge)
  || comparisons.value[0]);
const card = computed(() => props.intelligence.recommendations.find(item => item.id === comparison.value?.candidateId));
const gap = computed(() => props.intelligence.gapEvidence);
const summary = computed(() => gap.value.summary || {});
const checkedAt = computed(() => {
  if (!gap.value.checkedAt) return '未提供';
  const date = new Date(gap.value.checkedAt);
  return Number.isNaN(date.getTime()) ? '来源查询时间无法识别' : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) + '（北京时间）';
});
const fmt = (value: number | null | undefined) => value == null ? '未提供' : value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
const asRecord = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const chartOption = computed<Record<string, unknown>>(() => {
  const option = card.value?.chart.echartsOption;
  if (!option) return {};
  const tooltip = asRecord(option.tooltip);
  const axis = option.yAxis;
  const formatAxis = (value: unknown) => typeof value === 'number' ? fmt(value) : String(value ?? '');
  const yAxis = Array.isArray(axis) ? axis.map(item => ({ ...asRecord(item), axisLabel: { ...asRecord(asRecord(item).axisLabel), formatter: formatAxis } }))
    : { ...asRecord(axis), axisLabel: { ...asRecord(asRecord(axis).axisLabel), formatter: formatAxis } };
  return { ...option, tooltip: { ...tooltip, valueFormatter: (value: unknown) => formatAxis(value) }, yAxis };
});
</script>

<template>
  <section v-if="mode === 'analysis'" class="intelligence" data-testid="prototype-cost-intelligence">
    <el-card shadow="never">
      <div class="heading"><div><h3>同产品工单 · 成本差异核对</h3><p>只比较同一产品代码、同一成品单位且已经过字段校验的工单。</p></div>
        <el-select :model-value="comparison?.candidateId" @update:model-value="selectedId = $event" aria-label="选择成本对比产品" placeholder="选择产品">
          <el-option v-for="item in comparisons" :key="item.candidateId" :value="item.candidateId" :label="`${item.product} · ${item.unit}（${item.eligibleProjects}/${item.visibleProjects} 张）`" />
        </el-select>
      </div>
      <p v-if="excludedGroups" class="note">已排除 {{ excludedGroups }} 组名称或生产阶段不一致、资料范围不符的数据；不能仅凭产品编码相同，就把它们放在一起比较。</p>
      <template v-if="comparison && card">
        <div class="metrics"><div><small>可比较工单</small><strong>{{ comparison.eligibleProjects }} / {{ comparison.visibleProjects }}</strong></div><div><small>加权单位直接成本</small><strong>{{ fmt(comparison.weightedUnitCost) }} / {{ comparison.unit }}</strong></div><div><small>工单最低 / 最高</small><strong>{{ fmt(comparison.minimum) }} / {{ fmt(comparison.maximum) }}</strong></div></div>
        <p class="note">这里的金额沿用来源记录，币种没有返回；只用于同口径核对，不代表完整制造成本或利润。</p>
        <VChart class="source-chart" :option="chartOption" autoresize />
        <el-alert v-if="!comparison.bridge" type="info" :closable="false" title="当前产品只有一张通过校验的工单，暂不判断工单之间的差异。" />
        <template v-if="comparison.bridge">
          <h4>差异从哪里来：{{ comparison.bridge.comparisonProject }} 对比 {{ comparison.bridge.baselineProject }}</h4>
          <p>{{ localizeBlacklakeText(comparison.bridge.selection) }}</p>
          <div class="metrics bridge-metrics"><div><small>材料单位成本差额</small><strong>{{ fmt(comparison.bridge.materialDelta) }}</strong></div><div><small>人工单位成本差额</small><strong>{{ fmt(comparison.bridge.laborDelta) }}</strong></div><div><small>单位直接成本差额</small><strong>{{ fmt(comparison.bridge.delta) }} / {{ comparison.unit }}</strong></div></div>
          <el-table :data="comparison.bridge.materials" stripe data-testid="prototype-cost-bridge-materials">
            <el-table-column prop="material" label="原料" min-width="180" />
            <el-table-column label="基准单位耗用" min-width="145"><template #default="{ row }">{{ fmt(row.baselineUsage) }} {{ row.unit }}/{{ comparison.unit }}</template></el-table-column>
            <el-table-column label="对比单位耗用" min-width="145"><template #default="{ row }">{{ fmt(row.comparisonUsage) }} {{ row.unit }}/{{ comparison.unit }}</template></el-table-column>
            <el-table-column label="材料成本差额" min-width="130"><template #default="{ row }">{{ fmt(row.delta) }}</template></el-table-column>
            <el-table-column label="耗用量影响" min-width="130"><template #default="{ row }">{{ fmt(row.usageEffect) }}</template></el-table-column>
            <el-table-column label="单价影响" min-width="140"><template #default="{ row }">{{ fmt(row.priceEffect) }}</template></el-table-column>
          </el-table>
          <p class="note">材料差额与人工差额只是数学拆分，不能单独证明涨价、损耗或人员效率原因；新增材料项也不会被强行拆分。</p>
        </template>
        <div class="findings"><h4>需要关注</h4><p v-for="text in card.findings.attention" :key="text">{{ localizeBlacklakeText(text) }}</p><h4>建议核对</h4><p v-for="text in card.findings.suggestions" :key="text">{{ localizeBlacklakeText(text) }}</p></div>
      </template>
      <el-empty v-else description="尚无通过校验的同产品成本数据" />
    </el-card>
  </section>
  <section v-else class="intelligence" data-testid="prototype-cost-gap-evidence">
    <el-alert v-if="gap.status !== 'complete'" type="warning" :closable="false" :title="`补查证据不可用：${gap.status || '未知'}；保留原归档口径。`" />
    <template v-else>
      <el-alert type="success" :closable="false" :title="`${summary.materialsQueryVerified || 0} 条用料记录的父级查询已交叉核实`" />
      <p class="note">已核对 {{ summary.materialProjectsChecked || 0 }}/{{ summary.materialProjectsExpected || 0 }} 张工单；负对照：{{ gap.negativeControlVerified ? '源端明确拒绝不存在的工单' : '未形成有效证据' }}。来源回执未直接给出所属工单，已用查询时填写的工单编号交叉核对，没有猜填关联。</p>
      <div class="metrics"><div><small>仍无工单关联的领退料</small><strong>{{ summary.unlinkedStillMissing || 0 }} 行</strong></div><div><small>补查仍为零的领料金额</small><strong>{{ summary.zeroAmountsReconfirmed || 0 }} 行</strong></div><div><small>当前移动均价线索</small><strong>{{ summary.productsWithCurrentPriceClues || 0 }} 种物料</strong></div></div>
      <el-card shadow="never"><h3>还没有闭合的关联</h3><el-table :data="gap.unresolvedLinks || []" stripe><el-table-column prop="orderCode" label="来源领退料单" min-width="180" /><el-table-column prop="material" label="物料" min-width="180" /><el-table-column label="源端状态" min-width="200"><template #default="{ row }">{{ row.status === 'source_still_has_no_link' ? '关联指针仍为空' : '源记录已变化或未返回，需重新核实' }}</template></el-table-column><el-table-column label="下一步核对" min-width="300"><template #default="{ row }">{{ localizeBlacklakeText(row.action) }}</template></el-table-column></el-table></el-card>
      <el-card shadow="never" class="section"><h3>价格线索 · 不回填历史实际成本</h3><p class="note">当前移动均价只能作为补查线索，不能代替历史领料成本；新增单据也不混入旧时点金额。</p>
        <el-table :data="gap.currentPriceClues || []" stripe><el-table-column prop="material" label="物料" min-width="180" /><el-table-column prop="unit" label="来源单位" /><el-table-column label="当前移动均价"><template #default="{ row }">{{ fmt(row.price) }}</template></el-table-column><el-table-column prop="usage" label="证据限制" min-width="300" /></el-table>
        <p class="note">查询时间：{{ checkedAt }}</p>
      </el-card>
    </template>
    <el-card shadow="never" class="section"><h3>成本核对清单</h3><p class="note">同一工单可能有多个问题，以下数量不能相加当作工单数。BOM 单位与损耗口径未核实前，不计算标准耗用差异。</p><el-table :data="intelligence.worklist" stripe><el-table-column label="需要核对的问题" min-width="300"><template #default="{ row }">{{ localizeBlacklakeText(row.issue) }}</template></el-table-column><el-table-column prop="projects" label="涉及工单数" width="140" /></el-table></el-card>
  </section>
</template>

<style scoped>
.intelligence{margin-bottom:20px;min-width:0}.heading{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.heading .el-select{width:360px;max-width:100%;flex-shrink:0}h3{margin:0 0 12px;font-size:17px}h4{margin:20px 0 12px}p{line-height:1.8}.note,small{color:#7a8599}.source-chart{height:320px}.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin:20px 0}.metrics div{padding:16px;background:#f4f6f9;border-radius:10px}.metrics small,.metrics strong{display:block}.metrics strong{margin-top:8px;font-size:23px}.bridge-metrics strong{font-size:20px}.section{margin-top:20px}.el-card{border-radius:10px}@media(max-width:960px){.heading{flex-wrap:wrap}.heading .el-select{width:100%}.metrics{grid-template-columns:1fr}}
</style>
