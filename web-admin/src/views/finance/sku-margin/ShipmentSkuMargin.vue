<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { getShipmentSkuMargin, type ShipmentSkuMargin, type ShipmentSkuMarginRow } from '@/api/financeReport';
import { fmtMoney, fmtPct } from './skuMarginData';
import { useRoute } from 'vue-router';
import { readSourceLocation, shipmentSourceUrl } from './shipmentMarginNavigation';
import { samePeriodIncomeUrl } from '@/utils/financeReportPeriod';

const props = defineProps<{
  factoryId: string;
  period: [string, string] | null;
  refreshKey: number;
}>();
const data = ref<ShipmentSkuMargin | null>(null);
const error = ref('');
const loading = ref(false);
const selected = ref<ShipmentSkuMarginRow | null>(null);
const sourceVisible = ref(false);
const route = useRoute();
const focusedLine = ref<number | null>(null);
const sourceElements = ref<HTMLElement[]>([]);
const sourceNotice = ref('');
let consumedLocation = '';
const sourceLocation = computed(() => readSourceLocation(route.query, props.factoryId));
let sequence = 0;
const period = computed<[string, string]>(() => {
  if (props.period) return props.period;
  const now = new Date();
  const prefix = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  return [`${prefix}-01`, `${prefix}-${new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()}`];
});
const incomeUrl = computed(() => samePeriodIncomeUrl(props.factoryId, period.value));
watch(() => [props.factoryId, ...period.value, props.refreshKey], async () => {
  const current = ++sequence;
  selected.value = null;
  sourceVisible.value = false;
  focusedLine.value = null;
  sourceNotice.value = '';
  data.value = null;
  error.value = '';
  if (!props.factoryId) { loading.value = false; return; }
  loading.value = true;
  try {
    const response = await getShipmentSkuMargin(props.factoryId, ...period.value);
    if (current !== sequence) return;
    if (!response.success || !response.data) throw new Error(response.message || '未能读取实际出货毛利');
    data.value = response.data;
  } catch (cause) {
    if (current === sequence) error.value = cause instanceof Error ? cause.message : '读取失败，请稍后重试';
  } finally {
    if (current === sequence) loading.value = false;
  }
}, { immediate: true });
onBeforeUnmount(() => { sequence++; });
watch([data, sourceLocation], () => {
  const target = sourceLocation.value;
  if (!target) {
    if (consumedLocation) { selected.value = null; sourceVisible.value = false; focusedLine.value = null; sourceNotice.value = ''; }
    consumedLocation = ''; return;
  }
  if (!data.value || data.value.periodStart !== target.period[0] || data.value.periodEnd !== target.period[1]) return;
  const key = JSON.stringify(target);
  if (key === consumedLocation) return;
  consumedLocation = key;
  selected.value = null;
  sourceVisible.value = false;
  focusedLine.value = null;
  const row = data.value.rows.find(row => row.productTypeId === target.productTypeId
    && row.sources?.some(source => source.shipmentLineId === target.lineId));
  if (!row) { sourceNotice.value = '所选期间未找到这条出货来源，请核对链接或重新选择产品。'; return; }
  sourceNotice.value = '';
  showSources(row);
  focusedLine.value = target.lineId;
});
function focusSource() {
  sourceElements.value.find(element => element.dataset.sourceLineId === String(focusedLine.value))?.scrollIntoView({ block: 'center' });
}
function sumDisplayed(field: 'cost' | 'revenue'): number | null {
  const sources = selected.value?.sources;
  if (!sources?.length || sources.some(source => source[field] === null || !Number.isFinite(source[field]))) return null;
  return sources.reduce((cents, source) => cents + Math.round(source[field]! * 100), 0) / 100;
}
const sourceRevenue = computed(() => sumDisplayed('revenue'));
const sourceCost = computed(() => sumDisplayed('cost'));
const displayDifference = computed(() => selected.value?.cost == null || sourceCost.value === null ? null
  : (Math.round(selected.value.cost * 100) - Math.round(sourceCost.value * 100)) / 100);
function unitName(unit: string): string {
  return ({ kg: '公斤', g: '克', box: '盒', case: '箱', bag: '袋' } as Record<string, string>)[unit.toLowerCase()] || unit;
}
function showSources(row: ShipmentSkuMarginRow) {
  focusedLine.value = null;
  selected.value = row;
  sourceVisible.value = true;
}
function traceUrl(batchNumber: string): string {
  return `/production/traceability?batchNumber=${encodeURIComponent(batchNumber)}`;
}
</script>

<template>
  <section class="shipment-margin" v-loading="loading" aria-label="实际出货毛利">
    <h3>实际出货毛利</h3>
    <p class="basis">
      统计期间：{{ period[0] }} 至 {{ period[1] }}。
      只统计已出货或已送达的出货单，收入采用未税出货金额，成本采用实际出货批次的核定成本。
    </p>
    <el-alert v-if="sourceNotice" :title="sourceNotice" type="warning" show-icon :closable="false" />
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <template v-else-if="data">
      <el-descriptions :column="4" border class="totals">
        <el-descriptions-item label="出货收入">{{ fmtMoney(data.totalRevenue) }}</el-descriptions-item>
        <el-descriptions-item label="销售成本">{{ fmtMoney(data.totalCost) }}</el-descriptions-item>
        <el-descriptions-item label="出货毛利">{{ fmtMoney(data.grossProfit) }}</el-descriptions-item>
        <el-descriptions-item label="合计毛利率">{{ fmtPct(data.marginRate) }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="data.rows" border stripe empty-text="所选期间没有实际出货记录">
        <el-table-column prop="productName" label="成品" min-width="190" />
        <el-table-column label="实际出货数量" min-width="125">
          <template #default="{ row }">{{ row.quantity }} {{ unitName(row.unit) }}</template>
        </el-table-column>
        <el-table-column label="出货收入" min-width="115">
          <template #default="{ row }">{{ fmtMoney(row.revenue) }}</template>
        </el-table-column>
        <el-table-column label="实际销售成本" min-width="125">
          <template #default="{ row }">{{ fmtMoney(row.cost) }}</template>
        </el-table-column>
        <el-table-column label="毛利" min-width="115">
          <template #default="{ row }">{{ fmtMoney(row.grossProfit) }}</template>
        </el-table-column>
        <el-table-column label="毛利率" min-width="100">
          <template #default="{ row }">{{ fmtPct(row.marginRate) }}</template>
        </el-table-column>
        <el-table-column label="出货来源" min-width="150">
          <template #default="{ row }">
            {{ row.shipmentLineCount }} 条出货明细，{{ row.batchCount }} 个批次
            <el-button v-if="row.sources?.length" link type="primary" @click="showSources(row)">查看出货来源</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="data.roundingAdjustment !== null && data.roundingAdjustment !== 0" class="basis">
        逐产品显示金额与成本合计的舍入差额为 {{ fmtMoney(data.roundingAdjustment) }}；合计按实际出货成本统一取整。
      </p>
      <el-alert :title="data.reconciliationMessage" :type="data.reconciliationStatus === 'MATCH' ? 'success' : 'warning'"
        show-icon :closable="false" class="reconciliation" />
      <p v-if="data.financialStatement" class="financial-values">
        同期间已过账利润表：收入 {{ fmtMoney(data.financialStatement.totalRevenue) }}，
        销售成本 {{ fmtMoney(data.financialStatement.totalCost) }}，
        毛利 {{ fmtMoney(data.financialStatement.grossProfit) }}，
        净利润 {{ fmtMoney(data.financialStatement.netProfit) }}。
      </p>
      <p class="basis"><a v-if="incomeUrl" :href="incomeUrl">查看同期间利润表</a><span v-else>当前不是完整会计月，选择完整会计月后可查看同期间利润表。</span></p>
      <p class="basis">退货、折让、期间费用及税额请结合利润表核对；净利润与出货毛利采用不同口径。</p>
    </template>
    <el-dialog v-if="selected" v-model="sourceVisible" :title="`${selected.productName} · 实际出货来源`" width="min(960px, 94vw)" destroy-on-close @opened="focusSource">
      <p>统计期间：{{ period[0] }} 至 {{ period[1] }}。收入 {{ fmtMoney(selected.revenue) }}，成本 {{ fmtMoney(selected.cost) }}，毛利 {{ fmtMoney(selected.grossProfit) }}。</p>
      <p class="basis">每条出货行只计一次收入；成本按各批次实际扣库数量和当前核定单位成本计算。出货数量与批次数量各自保留原有单位。</p>
      <p v-if="sourceRevenue !== null">出货行收入合计 {{ fmtMoney(sourceRevenue) }}；本产品收入 {{ fmtMoney(selected.revenue) }}。批次只分摊成本，不重复计入收入。</p>
      <p v-if="sourceCost !== null">出货行显示成本相加 {{ fmtMoney(sourceCost) }}；本产品统一取整成本 {{ fmtMoney(selected.cost) }}。</p>
      <p v-if="displayDifference !== null && displayDifference !== 0" class="basis">显示舍入差额 {{ fmtMoney(displayDifference) }}，来自明细逐行取整与本产品汇总后统一取整的区别。</p>
      <div v-for="source in selected.sources" :key="source.shipmentLineId" ref="sourceElements" class="source-line"
        :class="{ 'focused-line': source.shipmentLineId === focusedLine }" :data-source-line-id="source.shipmentLineId">
        <h4>{{ source.deliveryNumber || '出货单号未保存' }} · 第 {{ source.shipmentLineId }} 行</h4>
        <a :href="shipmentSourceUrl(factoryId, period, selected.productTypeId, source.shipmentLineId)">直接打开这一行</a>
        <p>{{ source.deliveryDate }}；出货 {{ source.quantity }} {{ unitName(source.unit) }}；收入 {{ fmtMoney(source.revenue) }}；成本 {{ fmtMoney(source.cost) }}。</p>
        <el-table :data="source.batches" border>
          <el-table-column label="实际出货批次" min-width="230">
            <template #default="{ row }">
              <div>{{ row.batchNumber || '批次号未保存' }}</div>
              <a v-if="row.batchNumber" :href="traceUrl(row.batchNumber)">查看批次追溯</a>
            </template>
          </el-table-column>
          <el-table-column label="实际扣库数量" min-width="120">
            <template #default="{ row }">{{ row.quantity }} {{ unitName(row.unit) }}</template>
          </el-table-column>
          <el-table-column label="核定单位成本" min-width="130">
            <template #default="{ row }">{{ row.unitCost === null ? '—' : `${row.unitCost} 元/${unitName(row.unit)}` }}</template>
          </el-table-column>
          <el-table-column label="批次销售成本" min-width="120">
            <template #default="{ row }">{{ fmtMoney(row.cost) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <p class="basis">明细金额按分显示；SKU合计先按原精度汇总，再统一取整，因此明细相加可能有舍入差额。</p>
    </el-dialog>
  </section>
</template>

<style scoped>
.shipment-margin { margin-bottom: 20px; }
h3 { margin: 0 0 12px; font-size: 16px; }
.basis { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; margin: 10px 0; }
.totals { margin-bottom: 16px; }
.reconciliation { margin-top: 16px; }
.financial-values { font-size: 14px; line-height: 1.8; }
a { color: var(--el-color-primary); }
.source-line { margin: 20px 0; }
.focused-line { outline: 2px solid var(--el-color-primary); outline-offset: 6px; }
h4 { margin-bottom: 8px; }
</style>
