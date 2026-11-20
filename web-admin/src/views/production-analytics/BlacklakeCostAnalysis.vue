<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch, watchEffect } from 'vue';
import type { ExternalCostData, ExternalCostMetrics, ExternalIssueQuantity, ExternalMaterialIssue, ExternalWorkOrderCost } from '@/api/smartbi/factoryGold';
import echarts from '@/utils/echarts';
import { fmtMoney, fmtPercent, fmtQty } from './costAnalysisFormat';
import BlacklakeBarChart from './BlacklakeBarChart.vue';
import BlacklakeChartAnalysis from './BlacklakeChartAnalysis.vue';
import { blacklakeInsightChart, sortBlacklakeRows, type BlacklakeSort } from './blacklakeChartData';

const props = defineProps<{ data: ExternalCostData; canViewPrice: boolean; factoryId: string }>();
const trend = ref<HTMLDivElement>();
const orderPage = ref(1);
const issuePage = ref(1);
const orderSort = ref<BlacklakeSort>({ prop: null, order: null });
const issueSort = ref<BlacklakeSort>({ prop: null, order: null });
const productSort = ref<BlacklakeSort>({ prop: 'totalCost', order: 'descending' });
const pageSize = 20;
const materialCreditLabel = '含冲销/退料';
let trendChart: echarts.ECharts | undefined;
let resize: ResizeObserver | undefined;

const moneyFields = [
  { key: 'totalCost', label: '总成本', missingKey: 'costMissingOrders', missingLabel: '成本缺失' },
  { key: 'materialCost', label: '材料成本', missingKey: 'materialCostMissingOrders', missingLabel: '材料成本缺失' },
  { key: 'laborCost', label: '人工成本', missingKey: 'laborCostMissingOrders', missingLabel: '人工成本缺失' },
] as const;
const yieldReasons = {
  missing_issue: '缺领料记录',
  incompatible_units: '单位不可比',
  missing_output: '缺产出量',
  snapshot_mismatch: '工单与领料快照时间不同',
  nonpositive_input: '有效投入不足',
};
const priceSources = { reported: '黑湖成本单价', moving_avg: '移动均价', price_missing: '缺价', invalid_valuation: '计价待核' };
const time = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai', hourCycle: 'h23',
});

const snapshots = computed(() => {
  const { costCollectedAt, issueCollectedAt } = props.data;
  if (costCollectedAt && costCollectedAt === issueCollectedAt) {
    return [{ label: '最近快照采集', at: costCollectedAt }];
  }
  return [
    { label: '最近工单快照', at: costCollectedAt },
    { label: '最近领料快照', at: issueCollectedAt },
  ].filter((item): item is { label: string; at: string } => item.at !== null);
});
const materialCreditDays = computed(() => props.data.daily.filter(row => row.hasMaterialCredit));
const basis = '采用黑湖工单汇总，不含制费。缺价和待核行不计入领料金额。有产出的零单价工单按缺失处理，未计入平均单位成本。';
const negativeOrders = computed(() => props.data.workOrders
  .filter(row => (row.materialCost ?? 0) < 0 || (row.totalCost ?? 0) < 0));
const trendInsight = computed(() => {
  const excluded = new Set(negativeOrders.value.map(row => row.costDate));
  return blacklakeInsightChart('每日工单总成本', 'line', 'cost',
    props.data.daily.flatMap(row => row.totalCost !== null && row.totalCost >= 0
      && (row.materialCost ?? 0) >= 0 && !row.hasMaterialCredit && !excluded.has(row.costDate)
      ? [{ label: row.costDate, value: row.totalCost }] : []));
});
const productPoints = computed(() => {
  const points = props.data.products.flatMap(row => row.totalCost === null ? [] : [{
    label: row.productName ? `${row.productName} · ${row.productCode}` : row.productCode,
    name: row.productName || row.productCode,
    value: row.totalCost,
    code: row.productCode,
  }]);
  const nameCounts = new Map<string, number>();
  for (const { name } of points) {
    nameCounts.set(name, (nameCounts.get(name) ?? 0) + 1);
  }
  return points.map(point => ({
    ...point,
    axisLabel: nameCounts.get(point.name) === 1 ? point.name : point.label,
  }));
});
const productAnalysisPoints = computed(() => {
  const excluded = new Set(negativeOrders.value.map(row => row.productCode));
  return productPoints.value.filter(row => row.value >= 0 && !excluded.has(row.code));
});

const money = (value: number | null, digits = 2) => fmtMoney(value, digits);
const quantity = (value: number | null, unit: string | null) =>
  value == null ? '—' : `${fmtQty(value, 3)} ${unit || '（单位未提供）'}`;
const quantities = (items: ExternalIssueQuantity[]) =>
  items.map(item => quantity(item.quantity, item.unit)).join(' / ') || '—';
const laborOnlyLabel = '黑湖未计材料成本，仅含人工';
const isLaborOnly = (item: ExternalCostMetrics['unitCosts'][number]) =>
  item.zeroMaterialCostOrders > 0 && item.zeroMaterialCostOrders === item.workOrderCount;
const orderYield = (order: ExternalWorkOrderCost) => order.yieldUnavailableReason
  ? yieldReasons[order.yieldUnavailableReason] : fmtPercent(order.yieldRate);

const costKeys = {
  totalCost: (row: ExternalCostMetrics | ExternalWorkOrderCost) => row.totalCost,
  materialCost: (row: ExternalCostMetrics | ExternalWorkOrderCost) => row.materialCost,
  laborCost: (row: ExternalCostMetrics | ExternalWorkOrderCost) => row.laborCost,
  inputByUnit: (row: ExternalCostMetrics | ExternalWorkOrderCost) =>
    row.inputByUnit.length ? row.inputByUnit.flatMap(item => [item.unit ?? '未提供单位', item.quantity]) : null,
  yieldRate: (row: ExternalCostMetrics | ExternalWorkOrderCost) => row.yieldRate,
};
const products = computed(() => sortBlacklakeRows(props.data.products, productSort.value, {
  ...costKeys,
  productName: row => row.productName || row.productCode,
  unitCosts: row => row.unitCosts.length ? row.unitCosts.flatMap(item => [item.unit, item.cost]) : null,
}));
const orders = computed(() => sortBlacklakeRows(props.data.workOrders, orderSort.value, {
  ...costKeys,
  costDate: row => row.costDate,
  workOrderCode: row => row.workOrderCode,
  productName: row => row.productName || row.productCode,
  outputQty: row => row.outputQty === null ? null : [row.outputUnit ?? '未提供单位', row.outputQty],
  unitCost: row => row.unitCost === null ? null : [row.outputUnit ?? '未提供单位', row.unitCost],
}).slice((orderPage.value - 1) * pageSize, orderPage.value * pageSize));
const issueUnitKey = (row: ExternalMaterialIssue, value: number | null) =>
  value === null ? null : [row.unit ?? '未提供单位', value];
const issues = computed(() => sortBlacklakeRows(props.data.materialIssues, issueSort.value, {
  issueDate: row => row.issueDate,
  workOrderCode: row => row.workOrderCode,
  materialName: row => row.materialName || row.materialCode,
  quantity: row => issueUnitKey(row, row.quantity),
  effectiveUnitPrice: row => issueUnitKey(row, row.effectiveUnitPrice),
  issueCost: row => row.issueCost,
  priceSource: row => priceSources[row.priceSource],
}).slice((issuePage.value - 1) * pageSize, issuePage.value * pageSize));

function sortOrders(sort: BlacklakeSort) {
  orderSort.value = sort;
  orderPage.value = 1;
}
function sortIssues(sort: BlacklakeSort) {
  issueSort.value = sort;
  issuePage.value = 1;
}

watch(() => props.data, () => {
  orderPage.value = 1;
  issuePage.value = 1;
});

watchEffect(() => {
  const element = trend.value;
  if (!element) {
    resize?.disconnect();
    trendChart?.dispose();
    trendChart = undefined;
    return;
  }
  if (!trendChart) {
    trendChart = echarts.init(element, 'cretas');
    if (typeof ResizeObserver !== 'undefined') {
      resize = new ResizeObserver(() => trendChart?.resize());
      resize.observe(element);
    }
  }
  trendChart.setOption({
    tooltip: { trigger: 'axis', confine: true, renderMode: 'richText' },
    legend: { bottom: 0 },
    grid: { top: 36, right: 24, bottom: 54, left: 24, containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: props.data.daily.map(row => row.costDate) },
    yAxis: { type: 'value', name: '金额' },
    series: moneyFields.map(({ key, label }) => ({
      name: label, type: 'line', symbolSize: 7, data: props.data.daily.map(row => row[key]),
      ...(key === 'materialCost' && {
        markPoint: {
          symbol: 'circle', symbolSize: 12, itemStyle: { color: '#b45309' },
          label: { show: true, position: 'top', formatter: materialCreditLabel, color: '#92400e' },
          data: materialCreditDays.value.map(row => ({
            name: materialCreditLabel, coord: [row.costDate, row.materialCost],
          })),
        },
      }),
    })),
  });
}, { flush: 'post' });

onBeforeUnmount(() => {
  resize?.disconnect();
  trendChart?.dispose();
});
</script>

<template>
  <section aria-label="黑湖生产成本分析" class="blacklake-cost">
    <div class="source-details">
      <strong>数据来源　{{ data.sourceLabel }}</strong>
      <span v-for="{ label, at } in snapshots" :key="label">
        {{ label }}　<time :datetime="at">{{ time.format(new Date(at)) }}</time>（北京时间）
      </span>
      <RouterLink v-if="canViewPrice && !data.permissionMessage"
        :to="{ path: '/smart-bi/analysis', query: { tab: 'query', mode: 'factory', source: 'business' } }">自由问数出图</RouterLink>
      <el-tag v-if="data.stale" type="warning" effect="plain">数据未更新</el-tag>
    </div>
    <el-alert v-if="data.permissionMessage || !canViewPrice"
      :title="data.permissionMessage || '当前身份没有成本查看权限'" type="warning" :closable="false" show-icon />
    <el-empty v-else-if="!data.summary" :description="data.costCollectedAt || data.issueCollectedAt
      ? '所选期间暂无黑湖工单成本和生产领料' : '尚未收到黑湖工单成本或生产领料快照'" />
    <template v-else>
      <div class="kpis">
        <el-card v-for="{ key, label, missingKey, missingLabel } in moneyFields" :key="key" shadow="never">
          <div class="metric-label">{{ label }}</div>
          <strong>{{ money(data.summary[key]) }}</strong>
          <p v-if="data.summary[missingKey]" class="basis">
            {{ missingLabel }} {{ data.summary[missingKey] }} 单，未计入合计
          </p>
        </el-card>
        <el-card shadow="never">
          <div class="metric-label">平均单位成本</div>
          <p v-if="data.summary.unitCosts.length" class="basis">黑湖单价按同单位的实际产出量加权</p>
          <div v-for="item in data.summary.unitCosts" :key="item.unit" class="unit-cost">
            <strong>{{ money(item.cost, 4) }}</strong> / {{ item.unit }}
            <div v-if="item.workOrderCount" class="basis">
              {{ item.workOrderCount }} 单<template v-if="isLaborOnly(item)"> · {{ laborOnlyLabel }}</template>
            </div>
          </div>
          <strong v-if="!data.summary.unitCosts.length">—</strong>
          <p v-if="data.summary.unitCostMissingOrders" class="basis">
            单位成本缺失 {{ data.summary.unitCostMissingOrders }} 单，未计入平均
          </p>
        </el-card>
      </div>
      <p class="basis">
        总成本取黑湖工单汇总 · 不含制费（黑湖未提供） ·
        <template v-if="data.summary.priceMissingCount === null">尚未收到生产领料快照。</template>
        <template v-else>
          缺价 {{ data.summary.priceMissingCount }} 行
          <template v-if="data.summary.valuationIssueCount"> · 计价待核 {{ data.summary.valuationIssueCount }} 行</template>
          <template v-if="data.summary.priceMissingCount || data.summary.valuationIssueCount"> · 相应金额未计入领料金额。</template>
        </template>
      </p>
      <p class="basis">
        工单 {{ data.summary.workOrderCount }} 单 · 领料投入 {{ quantities(data.summary.inputByUnit) }}
        · 领料金额 {{ money(data.summary.issuedMaterialCost) }} · 出成率 {{ fmtPercent(data.summary.yieldRate) }}
        （{{ data.summary.yieldComparableOrders }} / {{ data.summary.workOrderCount }} 单重量单位可比且有有效数据）
      </p>
      <p v-if="data.summary.priceMissingCount !== null" class="basis">
        材料成本按工单成本日期汇总。领料金额按明细计价，包括期间内领料（含未纳入成本汇总或未关联工单的记录）及所选工单的跨期领料。
      </p>

      <el-card v-if="data.daily.length" shadow="never">
        <template #header><h3>每日成本趋势</h3></template>
        <div ref="trend" class="cost-chart" role="img" aria-label="每日总成本、材料成本与人工成本趋势" />
        <p v-if="materialCreditDays.length" class="basis">
          {{ materialCreditLabel }}：{{ materialCreditDays.map(row => row.costDate).join('、') }}。
          这些日期的工单含黑湖材料负账，趋势按原始金额汇总。
        </p>
        <BlacklakeChartAnalysis :chart="trendInsight" :factory-id="factoryId" metric="每日工单总成本"
          :basis="`${basis} 含冲销或负成本工单的日期保留在图上，未纳入分析。`" can-view-finance />
      </el-card>

      <el-card v-if="products.length" shadow="never">
        <template #header><h3>产品成本对比（元）</h3></template>
        <BlacklakeBarChart :points="productPoints" :analysis-points="productAnalysisPoints" title="产品工单总成本"
          :factory-id="factoryId" :basis="`${basis} 含负成本工单的产品保留在图上，未纳入分析。`" cost />
        <el-table :data="products" row-key="productCode" max-height="340" stripe
          :default-sort="{ prop: 'totalCost', order: 'descending' }" @sort-change="productSort = $event">
          <el-table-column prop="productName" label="产品" min-width="190" sortable="custom">
            <template #default="{ row }">{{ row.productName || row.productCode }}<br><small>{{ row.productCode }}</small></template>
          </el-table-column>
          <el-table-column v-for="{ key, label, missingKey, missingLabel } in moneyFields" :key="key"
            :label="label" align="right" min-width="128" sortable="custom" :prop="key">
            <template #default="{ row }">
              {{ money(row[key]) }}
              <div v-if="row[missingKey]" class="basis">{{ missingLabel }} {{ row[missingKey] }} 单</div>
            </template>
          </el-table-column>
          <el-table-column prop="unitCosts" label="平均单位成本" min-width="150" sortable="custom">
            <template #default="{ row }">
              <div v-for="item in row.unitCosts" :key="item.unit">
                {{ money(item.cost, 4) }}/{{ item.unit }}
                <div v-if="isLaborOnly(item)" class="basis">{{ laborOnlyLabel }}</div>
              </div>
              <template v-if="!row.unitCosts.length">—</template>
              <div v-if="row.unitCostMissingOrders" class="basis">单位成本缺失 {{ row.unitCostMissingOrders }} 单</div>
            </template>
          </el-table-column>
          <el-table-column prop="inputByUnit" label="领料投入" min-width="150" sortable="custom">
            <template #default="{ row }">{{ quantities(row.inputByUnit) }}</template>
          </el-table-column>
          <el-table-column prop="yieldRate" label="出成率" align="right" min-width="112" sortable="custom">
            <template #default="{ row }">
              {{ fmtPercent(row.yieldRate) }}
              <div class="basis" title="可比工单的实际产出重量合计 ÷ 对应工单领料重量合计">
                {{ row.yieldComparableOrders }} / {{ row.workOrderCount }} 单可比
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header><h3>工单成本明细</h3></template>
        <el-table :data="orders" row-key="workOrderCode" max-height="460" stripe
          empty-text="所选期间暂无黑湖工单成本" @sort-change="sortOrders">
          <el-table-column prop="costDate" label="日期" width="105" sortable="custom" />
          <el-table-column prop="workOrderCode" label="工单号" min-width="132" sortable="custom" />
          <el-table-column prop="productName" label="产品" min-width="154" sortable="custom">
            <template #default="{ row }">{{ row.productName || row.productCode }}<br><small>{{ row.productCode }}</small></template>
          </el-table-column>
          <el-table-column prop="outputQty" label="产出" min-width="86" sortable="custom">
            <template #default="{ row }">{{ quantity(row.outputQty, row.outputUnit) }}</template>
          </el-table-column>
          <el-table-column v-for="{ key, label } in moneyFields" :key="key" :prop="key" :label="label"
            align="right" min-width="98" sortable="custom">
            <template #default="{ row }">{{ money(row[key]) }}</template>
          </el-table-column>
          <el-table-column prop="unitCost" label="单位成本" align="right" min-width="100" sortable="custom">
            <template #default="{ row }">{{ row.unitCost === null ? '缺失' : money(row.unitCost, 4) }}</template>
          </el-table-column>
          <el-table-column prop="inputByUnit" label="领料投入" min-width="124" sortable="custom">
            <template #default="{ row }">{{ quantities(row.inputByUnit) }}</template>
          </el-table-column>
          <el-table-column prop="yieldRate" label="出成率" align="right" min-width="114" sortable="custom">
            <template #default="{ row }">{{ orderYield(row) }}</template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="data.workOrders.length > pageSize" v-model:current-page="orderPage"
          :page-size="pageSize" :total="data.workOrders.length" layout="total, prev, pager, next" size="small" />
      </el-card>

      <el-card shadow="never">
        <template #header><h3>生产领料投入</h3></template>
        <el-table :data="issues" row-key="lineId" max-height="460" stripe
          empty-text="所选期间及相关工单暂无生产领料" @sort-change="sortIssues">
          <el-table-column prop="issueDate" label="领料日" width="112" sortable="custom" />
          <el-table-column prop="workOrderCode" label="工单号" min-width="140" sortable="custom">
            <template #default="{ row }">{{ row.workOrderCode || '未关联' }}</template>
          </el-table-column>
          <el-table-column prop="materialName" label="物料" min-width="195" sortable="custom">
            <template #default="{ row }">{{ row.materialName || row.materialCode }}<br><small>{{ row.materialCode }}</small></template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" min-width="108" sortable="custom">
            <template #default="{ row }">{{ quantity(row.quantity, row.unit) }}</template>
          </el-table-column>
          <el-table-column prop="effectiveUnitPrice" label="成本单价" align="right" min-width="116" sortable="custom">
            <template #default="{ row }">{{ money(row.effectiveUnitPrice, 4) }}</template>
          </el-table-column>
          <el-table-column prop="issueCost" label="计入金额" align="right" min-width="116" sortable="custom">
            <template #default="{ row }">{{ money(row.issueCost) }}</template>
          </el-table-column>
          <el-table-column prop="priceSource" label="单价来源" min-width="122" sortable="custom">
            <template #default="{ row }">{{ priceSources[row.priceSource as keyof typeof priceSources] }}</template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="data.materialIssues.length > pageSize" v-model:current-page="issuePage"
          :page-size="pageSize" :total="data.materialIssues.length" layout="total, prev, pager, next" size="small" />
      </el-card>
    </template>
  </section>
</template>

<style scoped>
.blacklake-cost { display: grid; gap: 16px; }
.source-details { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 20px; }
.source-details span, .basis, .metric-label, small { color: var(--el-text-color-secondary); }
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(150px, 100%), 1fr)); gap: 12px; }
.kpis strong { font-size: 20px; font-variant-numeric: tabular-nums; }
.metric-label { margin-bottom: 8px; }
.unit-cost strong { font-size: 16px; }
.basis { margin: 0; font-size: 13px; }
h3 { margin: 0; }
.cost-chart { height: 268px; }
.el-pagination { margin-top: 12px; }
</style>
