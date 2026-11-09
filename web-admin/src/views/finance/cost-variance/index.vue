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
function tagType(status: PlanCostVarianceLine['status']): 'success' | 'warning' | 'info' {
  return status === 'COMPARABLE' ? 'success' : status === 'PARTIAL' ? 'warning' : 'info';
}

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
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="false"
          data-testid="cost-variance-range"
          @change="load"
        />
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
        <el-button :disabled="!report" data-testid="cost-variance-export" @click="exportPeriod">导出 Excel</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="basis-note"
      data-testid="cost-variance-basis"
      :title="report?.standardCostBasis || '标准成本 = 计划锁定配方的标准用量 × 开计划时物料单价快照'"
    />

    <div v-loading="loading">
      <template v-if="report">
        <el-alert
          v-if="report.comparabilityReason"
          type="warning"
          :closable="false"
          show-icon
          class="basis-note"
          data-testid="cost-variance-reason"
          :title="report.comparabilityReason"
        />
        <el-alert
          v-if="report.processSecretNote"
          type="info"
          :closable="false"
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

        <el-table :data="plans" border stripe data-testid="cost-variance-plans" empty-text="所选期间没有生产计划">
          <el-table-column prop="planNumber" label="计划" min-width="170">
            <template #default="{ row }">{{ row.planNumber || row.planId }}</template>
          </el-table-column>
          <el-table-column prop="productName" label="产品" min-width="140">
            <template #default="{ row }">{{ row.productName || row.productTypeId || '—' }}</template>
          </el-table-column>
          <el-table-column label="标准成本" min-width="110" align="right">
            <template #default="{ row }">{{ money(row.standardCost) }}</template>
          </el-table-column>
          <el-table-column label="实际成本" min-width="110" align="right">
            <template #default="{ row }">{{ money(row.actualCost) }}</template>
          </el-table-column>
          <el-table-column label="差异" min-width="120" align="right">
            <template #default="{ row }">
              <span :class="varianceClass(row.totalVariance)">{{ signedMoney(row.totalVariance) }}</span>
              <div class="sub">{{ rate(row.varianceRate) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="价格差" min-width="105" align="right">
            <template #default="{ row }">{{ signedMoney(row.priceVariance) }}</template>
          </el-table-column>
          <el-table-column label="用量差" min-width="105" align="right">
            <template #default="{ row }">{{ signedMoney(row.usageVariance) }}</template>
          </el-table-column>
          <el-table-column label="回料差" min-width="120" align="right">
            <template #default="{ row }">
              <span :class="varianceClass(row.reworkVariance)">{{ reworkVarianceText(row.reworkVariance, row.reworkStatus, row.reworkPriceMasked) }}</span>
              <div v-if="reworkQuantityLine(row)" class="sub">{{ reworkQuantityLine(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }">
              <span class="reason">{{ row.comparabilityReason || '全部可比' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :data-testid="`plan-detail-${row.planId}`" @click="openDetail(row.planId)">明细</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>

    <el-drawer v-model="drawerVisible" size="78%" :title="detail ? `成本差异明细 · ${detail.planNumber || detail.planId}` : '成本差异明细'">
      <div v-loading="detailLoading" class="detail" data-testid="plan-cost-variance-detail">
        <template v-if="detail">
          <div class="detail-meta">
            <span>产品：{{ detail.productName || detail.productTypeId || '—' }}</span>
            <span>产量：{{ qty(detail.outputQuantity, detail.outputUnit) }}<template v-if="detail.outputKg != null">（{{ qty(detail.outputKg, 'kg') }}）</template></span>
            <span>单价快照时间：{{ detail.snapshotCapturedAt ? detail.snapshotCapturedAt.replace('T', ' ').slice(0, 19) : '无快照' }}</span>
          </div>
          <el-alert v-if="detail.comparabilityReason" type="warning" :closable="false" show-icon :title="detail.comparabilityReason" class="basis-note" />
          <el-alert v-if="detail.priceMasked" type="info" :closable="false" title="你没有采购价查看权限，单价与逐项金额已隐藏" class="basis-note" />
          <el-alert
            v-if="detail.recoveredMaterialNote"
            :type="detail.reworkStatus === 'COMPARABLE' || detail.reworkStatus === 'INCOMPARABLE' ? 'warning' : 'info'"
            :closable="false"
            show-icon
            class="basis-note"
            data-testid="plan-rework-note"
            :title="`回料：${detail.recoveredMaterialNote}`"
          />
          <div class="detail-actions">
            <el-button size="small" data-testid="plan-cost-variance-export" @click="exportDetail">导出明细 Excel</el-button>
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

          <el-table :data="detail.lines" border size="small" data-testid="plan-cost-variance-lines">
            <el-table-column label="项目" min-width="150">
              <template #default="{ row }">
                <div>{{ row.materialName || row.materialTypeId || '—' }}</div>
                <div class="sub">{{ LINE_TYPE_LABEL[row.lineType as PlanCostVarianceLine['lineType']] }}<template v-if="row.materialCategory"> · {{ CATEGORY_LABEL[row.materialCategory] || row.materialCategory }}</template></div>
              </template>
            </el-table-column>
            <el-table-column label="开计划时单价" min-width="110" align="right">
              <template #default="{ row }">{{ snapshotPriceText(row, detail.priceMasked) }}</template>
            </el-table-column>
            <el-table-column label="标准用量" min-width="100" align="right">
              <template #default="{ row }">{{ qty(row.standardQuantity, row.lineType === 'LABOR' ? 'kg 成品' : row.priceUnit) }}</template>
            </el-table-column>
            <el-table-column label="实际用量" min-width="100" align="right">
              <template #default="{ row }">{{ qty(row.actualQuantity, row.priceUnit) }}</template>
            </el-table-column>
            <el-table-column label="实际单价" min-width="100" align="right">
              <template #default="{ row }">
                {{ actualPriceText(row) }}
                <div v-if="row.priceSource" class="sub">{{ row.priceSource }}</div>
              </template>
            </el-table-column>
            <el-table-column label="标准金额" min-width="100" align="right">
              <template #default="{ row }">{{ row.standardAmount == null ? '—' : money(row.standardAmount) }}</template>
            </el-table-column>
            <el-table-column label="实际金额" min-width="100" align="right">
              <template #default="{ row }">{{ row.actualAmount == null ? '—' : money(row.actualAmount) }}</template>
            </el-table-column>
            <el-table-column label="价格差" min-width="95" align="right">
              <template #default="{ row }"><span :class="varianceClass(row.priceVariance)">{{ row.lineType === 'LABOR' || row.lineType === 'REWORK' || row.lineType === 'REWORK_OUTPUT' ? '—' : signedMoney(row.priceVariance) }}</span></template>
            </el-table-column>
            <el-table-column label="用量差 / 替代料差 / 人工差 / 回料差" min-width="160" align="right">
              <template #default="{ row }">
                <span :class="lineVarianceClass(row)">{{ lineVarianceText(row, detail.priceMasked) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" min-width="220">
              <template #default="{ row }">
                <el-tag size="small" :type="tagType(row.status)">
                  {{ STATUS_LABEL[row.status as PlanCostVarianceLine['status']] }}
                </el-tag>
                <div v-for="reason in row.reasons" :key="reason" class="sub">{{ reason }}</div>
                <div v-if="reworkSourcesText(row.reworkSources, row.reworkSourcesNote)" class="sub" data-testid="rework-sources">
                  来源：{{ reworkSourcesText(row.reworkSources, row.reworkSourcesNote) }}
                </div>
              </template>
            </el-table-column>
          </el-table>
          <p v-if="detail.customerSuppliedItemCount > 0" class="sub note">客供料 {{ detail.customerSuppliedItemCount }} 批不计我方成本，不参与比较</p>

          <h3 class="section-title">多产出成本分摊方法</h3>
          <p v-if="!detail.allocationMethods.length" class="sub" data-testid="allocation-methods-empty">本计划没有多产出工序的分摊记录</p>
          <el-table v-else :data="detail.allocationMethods" border size="small" data-testid="allocation-methods">
            <el-table-column label="工序" min-width="120">
              <template #default="{ row }">{{ row.processCode || '—' }}（第 {{ row.processOrder ?? '?' }} 道）</template>
            </el-table-column>
            <el-table-column label="分法" min-width="200">
              <template #default="{ row }"><el-tag size="small">{{ row.basisLabel }}</el-tag></template>
            </el-table-column>
            <el-table-column label="各产出" min-width="380">
              <template #default="{ row }">
                <div v-for="o in row.outputs" :key="`${o.batchNumber}:${o.productTypeId}`" class="alloc-output">
                  {{ o.productName || o.productTypeId }}：{{ o.costAllocationRatio == null ? '—' : `${Number(o.costAllocationRatio).toFixed(2)}%` }}
                  <template v-if="o.allocatedCost != null && !detail.priceMasked">（{{ money(o.allocatedCost) }}）</template>
                  <span v-if="o.workflowFixedCostShare != null && o.costAllocationRatio != null && Math.abs(Number(o.costAllocationRatio) - Number(o.workflowFixedCostShare)) > 0.01" class="sub">
                    工艺固定 {{ o.workflowFixedCostShare }}%，报工时改过
                  </span>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.cost-variance-page { padding: 16px; }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; }
.head-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.basis-note { margin-bottom: 12px; }
.summary-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 10px; margin-bottom: 14px; }
.summary-grid.compact { grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); }
.summary-card { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 12px; background: #fff; display: flex; flex-direction: column; gap: 4px; }
.summary-card .label { color: #909399; font-size: 12px; }
.summary-card strong { font-size: 18px; font-variant-numeric: tabular-nums; }
.summary-card small { color: #909399; font-size: 12px; }
.is-over { color: #c45656; }
.is-under { color: #529b2e; }
.is-na { color: #909399; font-weight: normal; }
.sub { color: #909399; font-size: 12px; }
.reason { font-size: 12px; color: #606266; }
.detail-meta { display: flex; gap: 18px; flex-wrap: wrap; margin-bottom: 10px; color: #606266; font-size: 13px; }
.section-title { font-size: 15px; margin: 18px 0 8px; }
.note { margin-top: 8px; }
.detail-actions { display: flex; justify-content: flex-end; margin-bottom: 8px; }
.alloc-output { line-height: 1.7; }
</style>
