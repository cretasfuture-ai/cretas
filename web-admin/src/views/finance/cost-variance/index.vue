<script setup lang="ts">
/**
 * 成本差异报表 (食品 DAG 缺口 09 / 10, Steve 2026-11-03)
 *
 * 标准成本 = 计划锁定配方的标准用量 × 开计划时物料单价快照 (之后不随价格变)。
 * 差异拆价格差 / 用量差 / 替代料差 / 人工差 / 回料差; 后端给 null 的项一律显示「无法比较」+ 原因, ⛔ 不在前端补 0。
 * 回料差 (2026-11-09): 回投了多少、按什么价、对总差异贡献多少, 来源批次取追溯 (先进先出归属)。
 * 明细抽屉同时列出多产出工序用的成本分摊方法 (按重量 / 固定比例 / 报工手填)。
 * 显示文字与 Excel 导出共用 costVarianceFormat.ts —— 页面和导出是同一句话。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import {
  getCostVarianceReport,
  getPlanCostVariance,
  type CostVarianceReport,
  type PlanCostVariance,
  type PlanCostVarianceLine,
} from '@/api/costVariance';
import {
  CATEGORY_LABEL,
  LINE_TYPE_LABEL,
  STATUS_LABEL,
  actualPriceText,
  buildPeriodRows,
  buildPlanRows,
  lineVarianceText,
  money,
  qty,
  rate,
  reworkQuantityLine,
  reworkSourcesText,
  reworkVarianceText,
  signedMoney,
  snapshotPriceText,
} from './costVarianceFormat';
import { downloadSheet } from './costVarianceExport';
import type { CsTableColumn } from '@/types/csTable';
import type { CsBadgeColor } from '@/utils/csBadge';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}
const today = new Date();
const monthAgo = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 30);
const range = ref<[string, string]>([isoDate(monthAgo), isoDate(today)]);

const loading = ref(false);
const report = ref<CostVarianceReport | null>(null);
type PlanCostVarianceSummaryRow = NonNullable<CostVarianceReport['planVariances']>[number];
const plans = computed(() => report.value?.planVariances ?? []);

function errorText(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback;
}

let loadSeq = 0;
async function load() {
  if (!factoryId.value) return;
  const seq = ++loadSeq;
  loading.value = true;
  report.value = null; // 换期间/失败时不留旧数
  try {
    const res = await getCostVarianceReport(factoryId.value, range.value[0], range.value[1]);
    if (seq !== loadSeq) return; // 慢的旧请求不覆盖新期间
    if (res.success) {
      report.value = res.data;
    } else {
      ElMessage({ message: res.message || '成本差异报表加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    if (seq !== loadSeq) return;
    ElMessage({ message: errorText(e, '成本差异报表加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function varianceClass(v: number | null | undefined): string {
  if (v == null) return 'is-na';
  return Number(v) > 0 ? 'is-over' : Number(v) < 0 ? 'is-under' : '';
}
function lineVarianceClass(row: PlanCostVarianceLine): string {
  if (row.lineType === 'UNPLANNED' || row.status === 'INTERNAL') return 'is-na';
  const v = row.lineType === 'LABOR' ? row.laborVariance
    : row.lineType === 'SUBSTITUTE' ? row.substituteVariance
      : row.lineType === 'REWORK' || row.lineType === 'REWORK_OUTPUT' ? row.reworkVariance
        : row.usageVariance;
  return varianceClass(v);
}
function statusColor(status: PlanCostVarianceLine['status']): CsBadgeColor {
  return status === 'COMPARABLE' ? 'success' : status === 'PARTIAL' ? 'warning' : 'neutral';
}

const R = { meta: { class: { th: 'text-right', td: 'text-right' } } };
const planColumns: CsTableColumn<PlanCostVarianceSummaryRow>[] = [
  { id: 'planNumber', header: '计划' },
  { id: 'productName', header: '产品' },
  { id: 'standardCost', header: '标准成本', ...R },
  { id: 'actualCost', header: '实际成本', ...R },
  { id: 'totalVariance', header: '差异', ...R },
  { id: 'priceVariance', header: '价格差', ...R },
  { id: 'usageVariance', header: '用量差', ...R },
  { id: 'reworkVariance', header: '回料差', ...R },
  { id: 'reason', header: '说明' },
  { id: 'actions', header: '' },
];
const lineColumns: CsTableColumn<PlanCostVarianceLine>[] = [
  { id: 'item', header: '项目' },
  { id: 'snapshotPrice', header: '开计划时单价', ...R },
  { id: 'standardQuantity', header: '标准用量', ...R },
  { id: 'actualQuantity', header: '实际用量', ...R },
  { id: 'actualPrice', header: '实际单价', ...R },
  { id: 'standardAmount', header: '标准金额', ...R },
  { id: 'actualAmount', header: '实际金额', ...R },
  { id: 'priceVariance', header: '价格差', ...R },
  { id: 'otherVariance', header: '用量差 / 替代料差 / 人工差 / 回料差', ...R },
  { id: 'status', header: '状态' },
];
const allocColumns: CsTableColumn<PlanCostVariance['allocationMethods'][number]>[] = [
  { id: 'process', header: '工序' },
  { id: 'basis', header: '分法' },
  { id: 'outputs', header: '各产出' },
];

// ---- 导出 (与页面同一套文字) ----
function exportPeriod() {
  if (!report.value) return;
  downloadSheet(buildPeriodRows(report.value, range.value), '成本差异', `成本差异-${range.value[0]}至${range.value[1]}.xlsx`);
}
function exportDetail() {
  if (!detail.value) return;
  downloadSheet(buildPlanRows(detail.value), '成本差异明细', `成本差异明细-${detail.value.planNumber || detail.value.planId}.xlsx`);
}

// ---- 明细抽屉 ----
const drawerVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<PlanCostVariance | null>(null);

async function openDetail(planId: string) {
  if (!factoryId.value) return;
  drawerVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    const res = await getPlanCostVariance(factoryId.value, planId);
    if (res.success) {
      detail.value = res.data;
    } else {
      ElMessage({ message: res.message || '计划成本差异明细加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    ElMessage({ message: errorText(e, '计划成本差异明细加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    detailLoading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="cost-variance-page" data-testid="cost-variance-page">
    <div class="page-head">
      <h2>成本差异</h2>
      <div class="head-actions">
        <UInput v-model="range[0]" type="date" class="w-40" data-testid="cost-variance-range-start" @change="load" />
        <span class="to">至</span>
        <UInput v-model="range[1]" type="date" class="w-40" data-testid="cost-variance-range-end" @change="load" />
        <UButton :loading="loading" data-testid="cost-variance-query" @click="load">查询</UButton>
        <UButton color="neutral" variant="outline" :disabled="!report" data-testid="cost-variance-export" @click="exportPeriod">导出 Excel</UButton>
      </div>
    </div>

    <UAlert
      color="info"
      variant="soft"
      icon="i-lucide-info"
      class="basis-note"
      data-testid="cost-variance-basis"
      :title="report?.standardCostBasis || '标准成本 = 计划锁定配方的标准用量 × 开计划时物料单价快照'"
    />

    <div>
      <template v-if="report">
        <UAlert
          v-if="report.comparabilityReason"
          color="warning"
          variant="soft"
          icon="i-lucide-triangle-alert"
          class="basis-note"
          data-testid="cost-variance-reason"
          :title="report.comparabilityReason"
        />
        <UAlert
          v-if="report.processSecretNote"
          color="info"
          variant="soft"
          class="basis-note"
          :title="report.processSecretNote"
        />

        <div class="summary-grid" data-testid="cost-variance-summary">
          <div class="summary-card">
            <span class="label">标准成本（可比部分）</span>
            <strong>{{ money(report.totalBomCost) }}</strong>
          </div>
          <div class="summary-card">
            <span class="label">差异</span>
            <strong :class="varianceClass(report.totalVariance)">{{ signedMoney(report.totalVariance) }}</strong>
            <small>{{ rate(report.totalVarianceRate) }}</small>
          </div>
          <div class="summary-card" data-testid="summary-price-variance">
            <span class="label">价格差</span>
            <strong :class="varianceClass(report.priceVariance)">{{ signedMoney(report.priceVariance) }}</strong>
          </div>
          <div class="summary-card" data-testid="summary-usage-variance">
            <span class="label">用量差</span>
            <strong :class="varianceClass(report.usageVariance)">{{ signedMoney(report.usageVariance) }}</strong>
          </div>
          <div class="summary-card">
            <span class="label">替代料差</span>
            <strong :class="varianceClass(report.substituteVariance)">{{ signedMoney(report.substituteVariance) }}</strong>
          </div>
          <div class="summary-card">
            <span class="label">人工差</span>
            <strong :class="varianceClass(report.laborVariance)">{{ signedMoney(report.laborVariance) }}</strong>
          </div>
          <div class="summary-card" data-testid="summary-rework-variance">
            <span class="label">回料差</span>
            <strong :class="varianceClass(report.reworkVariance)">{{ reworkVarianceText(report.reworkVariance, report.reworkStatus) }}</strong>
            <small v-if="report.reworkSummary">{{ report.reworkSummary }}</small>
          </div>
          <div class="summary-card">
            <span class="label">期间实际总成本（全部批次）</span>
            <strong>{{ money(report.totalActualCost) }}</strong>
            <small>可比计划 {{ report.comparedPlanCount ?? 0 }} · 无法比较 {{ report.incomparablePlanCount ?? 0 }}</small>
          </div>
        </div>

        <UTable :data="plans" :columns="planColumns" :loading="loading" class="cs-table" data-testid="cost-variance-plans">
          <template #empty>所选期间没有生产计划</template>
          <template #planNumber-cell="{ row }">{{ row.original.planNumber || row.original.planId }}</template>
          <template #productName-cell="{ row }">{{ row.original.productName || row.original.productTypeId || '—' }}</template>
          <template #standardCost-cell="{ row }">{{ money(row.original.standardCost) }}</template>
          <template #actualCost-cell="{ row }">{{ money(row.original.actualCost) }}</template>
          <template #totalVariance-cell="{ row }">
            <span :class="varianceClass(row.original.totalVariance)">{{ signedMoney(row.original.totalVariance) }}</span>
            <div class="sub">{{ rate(row.original.varianceRate) }}</div>
          </template>
          <template #priceVariance-cell="{ row }">{{ signedMoney(row.original.priceVariance) }}</template>
          <template #usageVariance-cell="{ row }">{{ signedMoney(row.original.usageVariance) }}</template>
          <template #reworkVariance-cell="{ row }">
            <span :class="varianceClass(row.original.reworkVariance)">{{ reworkVarianceText(row.original.reworkVariance, row.original.reworkStatus, row.original.reworkPriceMasked) }}</span>
            <div v-if="reworkQuantityLine(row.original)" class="sub">{{ reworkQuantityLine(row.original) }}</div>
          </template>
          <template #reason-cell="{ row }"><span class="reason">{{ row.original.comparabilityReason || '全部可比' }}</span></template>
          <template #actions-cell="{ row }">
            <UButton variant="link" size="xs" :data-testid="`plan-detail-${row.original.planId}`" @click="openDetail(row.original.planId)">明细</UButton>
          </template>
        </UTable>
      </template>
    </div>

    <!-- 非模态: 模态 USlideover 会让 body 变 pointer-events:none, 抽屉里的导出按钮点不动。 -->
    <USlideover
      v-model:open="drawerVisible"
      :modal="false"
      :overlay="false"
      side="right"
      :title="detail ? `成本差异明细 · ${detail.planNumber || detail.planId}` : '成本差异明细'"
      description="计划成本差异明细"
      :ui="{ content: 'max-w-[78vw] w-full', title: 'pe-10', description: 'sr-only' }"
    >
      <template #body>
        <div class="detail" data-testid="plan-cost-variance-detail">
          <p v-if="detailLoading" class="sub">加载中…</p>
          <template v-if="detail">
            <div class="detail-meta">
              <span>产品：{{ detail.productName || detail.productTypeId || '—' }}</span>
              <span>产量：{{ qty(detail.outputQuantity, detail.outputUnit) }}<template v-if="detail.outputKg != null">（{{ qty(detail.outputKg, 'kg') }}）</template></span>
              <span>单价快照时间：{{ detail.snapshotCapturedAt ? detail.snapshotCapturedAt.replace('T', ' ').slice(0, 19) : '无快照' }}</span>
            </div>
            <UAlert v-if="detail.comparabilityReason" color="warning" variant="soft" icon="i-lucide-triangle-alert" :title="detail.comparabilityReason" class="basis-note" />
            <UAlert v-if="detail.priceMasked" color="info" variant="soft" icon="i-lucide-info" title="你没有采购价查看权限，单价与逐项金额已隐藏" class="basis-note" />
            <UAlert
              v-if="detail.recoveredMaterialNote"
              :color="detail.reworkStatus === 'COMPARABLE' || detail.reworkStatus === 'INCOMPARABLE' ? 'warning' : 'info'"
              variant="soft"
              icon="i-lucide-info"
              class="basis-note"
              data-testid="plan-rework-note"
              :title="`回料：${detail.recoveredMaterialNote}`"
            />
            <div class="detail-actions">
              <UButton size="sm" color="neutral" variant="outline" data-testid="plan-cost-variance-export" @click="exportDetail">导出明细 Excel</UButton>
            </div>

            <div class="summary-grid compact">
              <div class="summary-card"><span class="label">标准成本</span><strong>{{ money(detail.standardCost) }}</strong></div>
              <div class="summary-card"><span class="label">实际成本</span><strong>{{ money(detail.actualCost) }}</strong></div>
              <div class="summary-card"><span class="label">差异</span><strong :class="varianceClass(detail.totalVariance)">{{ signedMoney(detail.totalVariance) }}</strong><small>{{ rate(detail.varianceRate) }}</small></div>
              <div class="summary-card"><span class="label">价格差</span><strong>{{ signedMoney(detail.priceVariance) }}</strong></div>
              <div class="summary-card"><span class="label">用量差</span><strong>{{ signedMoney(detail.usageVariance) }}</strong></div>
              <div class="summary-card"><span class="label">替代料差</span><strong>{{ signedMoney(detail.substituteVariance) }}</strong></div>
              <div class="summary-card"><span class="label">人工差</span><strong>{{ signedMoney(detail.laborVariance) }}</strong></div>
              <div class="summary-card" data-testid="detail-rework-variance"><span class="label">回料差</span><strong :class="varianceClass(detail.reworkVariance)">{{ reworkVarianceText(detail.reworkVariance, detail.reworkStatus, detail.reworkPriceMasked) }}</strong></div>
            </div>

            <UTable :data="detail.lines" :columns="lineColumns" class="cs-table" data-testid="plan-cost-variance-lines">
              <template #item-cell="{ row }">
                <div>{{ row.original.materialName || row.original.materialTypeId || '—' }}</div>
                <div class="sub">{{ LINE_TYPE_LABEL[row.original.lineType as PlanCostVarianceLine['lineType']] }}<template v-if="row.original.materialCategory"> · {{ CATEGORY_LABEL[row.original.materialCategory] || row.original.materialCategory }}</template></div>
              </template>
              <template #snapshotPrice-cell="{ row }">{{ snapshotPriceText(row.original, detail.priceMasked) }}</template>
              <template #standardQuantity-cell="{ row }">{{ qty(row.original.standardQuantity, row.original.lineType === 'LABOR' ? 'kg 成品' : row.original.priceUnit) }}</template>
              <template #actualQuantity-cell="{ row }">{{ qty(row.original.actualQuantity, row.original.priceUnit) }}</template>
              <template #actualPrice-cell="{ row }">
                {{ actualPriceText(row.original) }}
                <div v-if="row.original.priceSource" class="sub">{{ row.original.priceSource }}</div>
              </template>
              <template #standardAmount-cell="{ row }">{{ row.original.standardAmount == null ? '—' : money(row.original.standardAmount) }}</template>
              <template #actualAmount-cell="{ row }">{{ row.original.actualAmount == null ? '—' : money(row.original.actualAmount) }}</template>
              <template #priceVariance-cell="{ row }"><span :class="varianceClass(row.original.priceVariance)">{{ row.original.lineType === 'LABOR' || row.original.lineType === 'REWORK' || row.original.lineType === 'REWORK_OUTPUT' ? '—' : signedMoney(row.original.priceVariance) }}</span></template>
              <template #otherVariance-cell="{ row }"><span :class="lineVarianceClass(row.original)">{{ lineVarianceText(row.original, detail.priceMasked) }}</span></template>
              <template #status-cell="{ row }">
                <UBadge :color="statusColor(row.original.status)" variant="soft">{{ STATUS_LABEL[row.original.status as PlanCostVarianceLine['status']] }}</UBadge>
                <div v-for="reason in row.original.reasons" :key="reason" class="sub">{{ reason }}</div>
                <div v-if="reworkSourcesText(row.original.reworkSources, row.original.reworkSourcesNote)" class="sub" data-testid="rework-sources">
                  来源：{{ reworkSourcesText(row.original.reworkSources, row.original.reworkSourcesNote) }}
                </div>
              </template>
            </UTable>
            <p v-if="detail.customerSuppliedItemCount > 0" class="sub note">客供料 {{ detail.customerSuppliedItemCount }} 批不计我方成本，不参与比较</p>

            <h3 class="section-title">多产出成本分摊方法</h3>
            <p v-if="!detail.allocationMethods.length" class="sub" data-testid="allocation-methods-empty">本计划没有多产出工序的分摊记录</p>
            <UTable v-else :data="detail.allocationMethods" :columns="allocColumns" class="cs-table" data-testid="allocation-methods">
              <template #process-cell="{ row }">{{ row.original.processCode || '—' }}（第 {{ row.original.processOrder ?? '?' }} 道）</template>
              <template #basis-cell="{ row }"><UBadge color="neutral" variant="soft">{{ row.original.basisLabel }}</UBadge></template>
              <template #outputs-cell="{ row }">
                <div v-for="o in row.original.outputs" :key="`${o.batchNumber}:${o.productTypeId}`" class="alloc-output">
                  {{ o.productName || '未知物料' }}：{{ o.costAllocationRatio == null ? '—' : `${Number(o.costAllocationRatio).toFixed(2)}%` }}
                  <template v-if="o.allocatedCost != null && !detail.priceMasked">（{{ money(o.allocatedCost) }}）</template>
                  <span v-if="o.workflowFixedCostShare != null && o.costAllocationRatio != null && Math.abs(Number(o.costAllocationRatio) - Number(o.workflowFixedCostShare)) > 0.01" class="sub">
                    工艺固定 {{ o.workflowFixedCostShare }}%，报工时改过
                  </span>
                </div>
              </template>
            </UTable>
          </template>
        </div>
      </template>
    </USlideover>
  </div>
</template>

<style scoped>
.cost-variance-page { padding: 16px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; }
.head-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.to { color: var(--cs-muted); }
.basis-note { margin-bottom: 12px; }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 10px; margin-bottom: 14px; }
.summary-grid.compact { grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); }
.summary-card { border: 1px solid var(--cs-border); border-radius: 6px; padding: 10px 12px; background: var(--cs-surface); display: flex; flex-direction: column; gap: 4px; }
.summary-card .label { color: var(--cs-muted); font-size: 12px; }
.summary-card strong { font-size: 18px; font-variant-numeric: tabular-nums; }
.summary-card small { color: var(--cs-muted); font-size: 12px; }
.is-over { color: var(--cs-danger); }
.is-under { color: var(--cs-success); }
.is-na { color: var(--cs-muted); font-weight: normal; }
.sub { color: var(--cs-muted); font-size: 12px; }
.reason { font-size: 12px; color: var(--cs-muted); }
.detail-meta { display: flex; gap: 18px; flex-wrap: wrap; margin-bottom: 10px; color: var(--cs-muted); font-size: 13px; }
.section-title { font-size: 15px; margin: 18px 0 8px; }
.note { margin-top: 8px; }
.detail-actions { display: flex; justify-content: flex-end; margin-bottom: 8px; }
.alloc-output { line-height: 1.7; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; color: var(--cs-text); border-color: var(--cs-border); }
</style>
