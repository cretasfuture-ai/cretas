<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { getShipmentSkuMargin, type ShipmentSkuMargin, type ShipmentSkuMarginRow } from '@/api/financeReport';
import { SHIPMENT_COST_SOURCE_INCOMPLETE } from '@/api/financeReportCodes';
import { fmtMoney, fmtPct } from './skuMarginData';
import { useRoute } from 'vue-router';
import { readSourceLocation, shipmentSourceUrl } from './shipmentMarginNavigation';
import { samePeriodIncomeUrl } from '@/utils/financeReportPeriod';
import type { CsTableColumn } from '@/types/csTable';

const props = defineProps<{
  factoryId: string;
  period: [string, string] | null;
  refreshKey: number;
}>();
const data = ref<ShipmentSkuMargin | null>(null);
const error = ref('');
const costIncomplete = ref(false);
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
const hasReturnReconciliation = computed(() => {
  const current = data.value;
  return current?.financialStatement && [current.reconciliationReturnedRevenue, current.reconciliationReturnedCost,
    current.reconciliationNetRevenue, current.reconciliationNetCost, current.reconciliationNetGrossProfit]
    .every(value => typeof value === 'number' && Number.isFinite(value));
});
const reconciliationRoundingDifference = computed(() => {
  const current = data.value;
  if (current?.totalCost == null || current.reconciliationReturnedCost == null || current.reconciliationNetCost == null) return null;
  return (Math.round(current.reconciliationNetCost * 100) - Math.round(current.totalCost * 100)
    + Math.round(current.reconciliationReturnedCost * 100)) / 100;
});
watch(() => [props.factoryId, ...period.value, props.refreshKey], async () => {
  const current = ++sequence;
  selected.value = null;
  sourceVisible.value = false;
  focusedLine.value = null;
  sourceNotice.value = '';
  data.value = null;
  error.value = '';
  costIncomplete.value = false;
  if (!props.factoryId) { loading.value = false; return; }
  loading.value = true;
  try {
    const response = await getShipmentSkuMargin(props.factoryId, ...period.value);
    if (current !== sequence) return;
    if (!response.success || !response.data) throw new Error(response.message || '未能读取实际出货毛利');
    data.value = response.data;
  } catch (cause) {
    if (current === sequence) {
      if ((cause as { code?: unknown } | null)?.code === SHIPMENT_COST_SOURCE_INCOMPLETE) costIncomplete.value = true;
      else error.value = cause instanceof Error ? cause.message : '读取失败，请稍后重试';
    }
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
  sourceElements.value.find(element => element.dataset.sourceLineId === String(focusedLine.value))?.scrollIntoView?.({ block: 'center' });
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
type Row = ShipmentSkuMarginRow;
type Batch = NonNullable<ShipmentSkuMarginRow['sources']>[number]['batches'][number];
const R = { class: { th: 'text-right', td: 'text-right' } };
const productColumns: CsTableColumn<Row>[] = [
  { id: 'productName', accessorKey: 'productName', header: '成品' },
  { id: 'quantity', header: '实际出货数量', meta: R },
  { id: 'revenue', header: '出货收入', meta: R },
  { id: 'cost', header: '实际销售成本', meta: R },
  { id: 'grossProfit', header: '毛利', meta: R },
  { id: 'marginRate', header: '毛利率', meta: R },
  { id: 'sources', header: '出货来源' },
];
const batchColumns: CsTableColumn<Batch>[] = [
  { id: 'batchNumber', header: '实际出货批次' },
  { id: 'quantity', header: '实际扣库数量', meta: R },
  { id: 'unitCost', header: '核定单位成本', meta: R },
  { id: 'cost', header: '批次销售成本', meta: R },
];
// 来源弹窗打开后把被链接定位的那一行滚到视野中央
watch([sourceVisible, focusedLine], async ([visible]) => {
  if (!visible) return;
  await nextTick();
  focusSource();
});
function onSourceOpenChange(next: boolean) { sourceVisible.value = next; }
function traceUrl(batchNumber: string): string {
  return `/production/traceability?batchNumber=${encodeURIComponent(batchNumber)}`;
}
</script>

<template>
  <section class="shipment-margin" aria-label="实际出货毛利" data-testid="shipment-margin">
    <h3>实际出货毛利</h3>
    <p class="basis">
      统计期间：{{ period[0] }} 至 {{ period[1] }}。
      只统计已出货或已送达的出货单，收入采用未税出货金额，成本采用实际出货批次的核定成本。
    </p>
    <p v-if="loading" class="basis" data-testid="shipment-margin-loading">加载中…</p>
    <UAlert v-if="sourceNotice" :title="sourceNotice" color="warning" variant="soft" icon="i-lucide-triangle-alert" class="mb-3" data-type="warning" />
    <UAlert v-if="costIncomplete" color="warning" variant="soft" icon="i-lucide-triangle-alert" class="mb-3"
      data-type="warning" data-testid="shipment-margin-cost-incomplete"
      title="本期有出货记录缺扣库批次或单位，暂不能计算 SKU 毛利">
      <template #description>
        请到<a href="/warehouse/shipments" data-testid="shipment-margin-fix-link">「出货管理」</a>补齐出货扣库批次和单位后重试。
      </template>
    </UAlert>
    <UAlert v-else-if="error" :title="error" color="error" variant="soft" icon="i-lucide-circle-alert" class="mb-3" data-type="error" />
    <template v-else-if="data">
      <dl class="totals">
        <div><dt>出货收入</dt><dd>{{ fmtMoney(data.totalRevenue) }}</dd></div>
        <div><dt>销售成本</dt><dd>{{ fmtMoney(data.totalCost) }}</dd></div>
        <div><dt>出货毛利</dt><dd>{{ fmtMoney(data.grossProfit) }}</dd></div>
        <div><dt>合计毛利率</dt><dd>{{ fmtPct(data.marginRate) }}</dd></div>
      </dl>
      <UTable :data="data.rows" :columns="productColumns" class="cs-table" data-testid="shipment-margin-table">
        <template #empty>所选期间没有实际出货记录</template>
        <template #quantity-cell="{ row }">{{ row.original.quantity }} {{ unitName(row.original.unit) }}</template>
        <template #revenue-cell="{ row }">{{ fmtMoney(row.original.revenue) }}</template>
        <template #cost-cell="{ row }">{{ fmtMoney(row.original.cost) }}</template>
        <template #grossProfit-cell="{ row }">{{ fmtMoney(row.original.grossProfit) }}</template>
        <template #marginRate-cell="{ row }">{{ fmtPct(row.original.marginRate) }}</template>
        <template #sources-cell="{ row }">
          {{ row.original.shipmentLineCount }} 条出货明细，{{ row.original.batchCount }} 个批次
          <UButton v-if="row.original.sources?.length" variant="link" size="xs" @click="showSources(row.original)">查看出货来源</UButton>
        </template>
      </UTable>
      <p v-if="data.roundingAdjustment !== null && data.roundingAdjustment !== 0" class="basis">
        逐产品显示金额与成本合计的舍入差额为 {{ fmtMoney(data.roundingAdjustment) }}；合计按实际出货成本统一取整。
      </p>
      <section v-if="hasReturnReconciliation" aria-label="整月退货与利润表核对">
        <h4>整月退货与利润表核对</h4>
        <p class="basis">上方保留原始出货金额；下方按本期已完成销售退货冲减收入，实物退货恢复成本后，与利润表核对。</p>
        <dl class="totals three">
          <div><dt>本期退货冲减收入</dt><dd>{{ fmtMoney(data.reconciliationReturnedRevenue) }}</dd></div>
          <div><dt>实物退货恢复成本</dt><dd>{{ fmtMoney(data.reconciliationReturnedCost) }}</dd></div>
          <div><dt>扣除退货后收入</dt><dd>{{ fmtMoney(data.reconciliationNetRevenue) }}</dd></div>
          <div><dt>净销售成本</dt><dd>{{ fmtMoney(data.reconciliationNetCost) }}</dd></div>
          <div><dt>扣除退货后毛利</dt><dd>{{ fmtMoney(data.reconciliationNetGrossProfit) }}</dd></div>
        </dl>
        <p v-if="reconciliationRoundingDifference !== null && reconciliationRoundingDifference !== 0" class="basis">
          成本按未舍入原值相减后统一取分，与显示金额相减有 {{ fmtMoney(reconciliationRoundingDifference) }} 的舍入差额。
        </p>
      </section>
      <UAlert :title="data.reconciliationMessage" :color="data.reconciliationStatus === 'MATCH' ? 'success' : 'warning'" variant="soft"
        :icon="data.reconciliationStatus === 'MATCH' ? 'i-lucide-circle-check' : 'i-lucide-triangle-alert'"
        :data-type="data.reconciliationStatus === 'MATCH' ? 'success' : 'warning'" class="reconciliation" />
      <p v-if="data.financialStatement" class="financial-values">
        同期间已过账利润表：收入 {{ fmtMoney(data.financialStatement.totalRevenue) }}，
        销售成本 {{ fmtMoney(data.financialStatement.totalCost) }}，
        毛利 {{ fmtMoney(data.financialStatement.grossProfit) }}，
        净利润 {{ fmtMoney(data.financialStatement.netProfit) }}。
      </p>
      <p class="basis"><a v-if="incomeUrl" :href="incomeUrl">查看同期间利润表</a><span v-else>当前不是完整会计月，选择完整会计月后可查看同期间利润表。</span></p>
      <p class="basis">退货、折让、期间费用及税额请结合利润表核对；净利润与出货毛利采用不同口径。</p>
    </template>

    <UModal
      v-if="selected"
      :open="sourceVisible"
      :title="`${selected.productName} · 实际出货来源`"
      description="本产品的出货行与实际扣库批次"
      :ui="{ content: 'max-w-[960px]', description: 'sr-only' }"
      @update:open="onSourceOpenChange"
    >
      <template #body>
        <div data-testid="shipment-margin-sources">
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
            <UTable :data="source.batches" :columns="batchColumns" class="cs-table">
              <template #batchNumber-cell="{ row }">
                <div>{{ row.original.batchNumber || '批次号未保存' }}</div>
                <a v-if="row.original.batchNumber" :href="traceUrl(row.original.batchNumber)">查看批次追溯</a>
              </template>
              <template #quantity-cell="{ row }">{{ row.original.quantity }} {{ unitName(row.original.unit) }}</template>
              <template #unitCost-cell="{ row }">{{ row.original.unitCost === null ? '—' : `${row.original.unitCost} 元/${unitName(row.original.unit)}` }}</template>
              <template #cost-cell="{ row }">{{ fmtMoney(row.original.cost) }}</template>
            </UTable>
          </div>
          <p class="basis">明细金额按分显示；SKU合计先按原精度汇总，再统一取整，因此明细相加可能有舍入差额。</p>
        </div>
      </template>
    </UModal>
  </section>
</template>

<style scoped>
.shipment-margin { margin-bottom: var(--cs-space-5); }
h3 { margin: 0 0 12px; font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
.basis { color: var(--cs-muted); font-size: 13px; line-height: 1.7; margin: 10px 0; }
.totals { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--cs-space-3); margin: 0 0 var(--cs-space-4); }
.totals.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.totals > div { padding: var(--cs-space-3) var(--cs-space-4); background: var(--cs-surface-subtle); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.totals dt { font-size: 12px; color: var(--cs-muted); }
.totals dd { margin: 4px 0 0; font-size: 16px; font-weight: 600; font-variant-numeric: tabular-nums; color: var(--cs-text); }
.reconciliation { margin-top: var(--cs-space-4); }
.financial-values { font-size: 14px; line-height: 1.8; }
a { color: var(--cs-primary); }
.source-line { margin: 20px 0; padding: 4px; }
.focused-line { outline: 2px solid var(--cs-primary); outline-offset: 6px; }
h4 { margin-bottom: 8px; font-weight: 600; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); font-variant-numeric: tabular-nums; }
@media (max-width: 768px) { .totals, .totals.three { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
