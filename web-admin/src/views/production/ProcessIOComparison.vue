<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { Search, Refresh } from '@element-plus/icons-vue';
import { balanceProcessLabel } from './balanceProcessLabel';
import { displayProcessUnit } from '@/utils/processSheetUnits';
import {
  averageScopeNote,
  formatQuantity,
  incomparableLabel,
  summaryKpi,
  type IncomparableReason,
  type ProcessYieldAgg,
} from './processIoSummary';

// ---------- auth ----------
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ---------- state ----------
const loading = ref(false);
const tableData = ref<ProcessIORow[]>([]);
const dateRange = ref<[string, string] | null>(null);
const selectedProduct = ref('');
const productTypes = ref<ProductType[]>([]);

// ---------- types ----------
interface ProcessIORow {
  processName: string;
  processCategory: string;
  inputQuantity: number | null;
  outputQuantity: number | null;
  conversionRate: number | null;
  wastageRate: number | null;
  inputUnit: string | null;
  outputUnit: string | null;
  incomparableReason: IncomparableReason | null;
  batchCount: number;
}

interface ProductType {
  id: string;
  name: string;
}

// ---------- KPI ----------
// 平均只用能比的工序 (同单位 / 可靠换算); 一道都没有时显示「—」, 不显示成 0%
const kpi = computed(() => summaryKpi(tableData.value));
const kpiScopeNote = computed(() => averageScopeNote(kpi.value));

// ---------- 物料平衡表 (缺口 #07) ----------
interface BalanceRow {
  processName: string;
  processOrder: number | null;
  level: 'BALANCED' | 'WARN' | 'RED' | 'UNIT_INCOMPARABLE' | 'NO_INPUT';
  unit: string | null;
  input: number | null;
  output: number | null;
  byproducts: number | null;
  confirmedLoss: number | null;
  unexplained: number | null;
  unexplainedPct: number | null;
  taskCount: number;
  note: string | null;
}
interface BalancePlan {
  planId: string | null;
  planNumber: string | null;
  productName: string | null;
  batchNumbers: string[];
  rows: BalanceRow[];
}
interface BalanceTable {
  warnPct: number;
  redPct: number;
  plans: BalancePlan[];
}

function localToday(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

const activeTab = ref<'summary' | 'balance'>('summary');
const balanceLoading = ref(false);
const balanceDate = ref<string>(localToday());
const balance = ref<BalanceTable>({ warnPct: 5, redPct: 15, plans: [] });

async function loadBalance() {
  if (!factoryId.value || !balanceDate.value) return;
  balanceLoading.value = true;
  try {
    const response = await get<BalanceTable>(
      `/${factoryId.value}/production/yield/material-balance`,
      { params: { startDate: balanceDate.value, endDate: balanceDate.value } }
    );
    balance.value = response.success && response.data ? response.data : { warnPct: 5, redPct: 15, plans: [] };
  } catch (error: unknown) {
    // request.ts 拦截器已弹 sticky 错误; 这里不吞成"没数据"的假象, 清空并保留控制台
    console.error('加载物料平衡表失败:', error);
    balance.value = { warnPct: 5, redPct: 15, plans: [] };
  } finally {
    balanceLoading.value = false;
  }
}

function balanceTagType(level: BalanceRow['level']): string {
  if (level === 'BALANCED') return 'success';
  if (level === 'WARN') return 'warning';
  if (level === 'RED') return 'danger';
  return 'info';
}

function balanceLabel(row: BalanceRow): string {
  if (row.level === 'BALANCED') return '已对平';
  if (row.level === 'WARN') return '差额偏大';
  if (row.level === 'RED') return '差额过大';
  if (row.level === 'UNIT_INCOMPARABLE') return '单位不可比';
  return '未登记投入';
}

function fmtQty(v: number | null, unit: string | null): string {
  return v == null ? '—' : formatQuantity(v, unit);
}

function onTabChange(name: string | number) {
  if (name === 'balance') loadBalance();
}

// ---------- load ----------
onMounted(() => {
  loadProductTypes();
  loadData();
});

async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    const response = await get<{ content: ProductType[] } | ProductType[]>(
      `/${factoryId.value}/product-types`
    );
    if (response.success && response.data) {
      const data = response.data;
      productTypes.value = Array.isArray(data) ? data : (data.content || []);
    }
  } catch (error: any) {
    console.error('加载产品类型失败:', error);
  }
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {};
    if (dateRange.value && dateRange.value[0]) {
      params.startDate = dateRange.value[0];
      params.endDate = dateRange.value[1];
    }
    if (selectedProduct.value) {
      params.productTypeId = selectedProduct.value;
    }
    // 单元4: 使用 /production/yield/by-process 后端聚合结果直接渲染
    const response = await get<ProcessYieldAgg[]>(
      `/${factoryId.value}/production/yield/by-process`, { params }
    );
    if (response.success && response.data) {
      tableData.value = (response.data || []).map((r) => ({
        processName: r.processName,
        processCategory: '',
        inputQuantity: r.inputQuantity,
        outputQuantity: r.outputQuantity,
        conversionRate: r.conversionRate ?? null,
        wastageRate: r.wastageRate ?? null,
        // 旧后端只给 unit (=投入单位, 产出同单位); 新后端分开给, 没有单位为 null
        inputUnit: r.inputUnit !== undefined ? r.inputUnit : (r.unit ?? null),
        outputUnit: r.outputUnit !== undefined ? r.outputUnit : (r.unit ?? null),
        incomparableReason: r.incomparableReason ?? null,
        batchCount: r.batchCount,
      }));
    } else {
      tableData.value = [];
    }
  } catch (error: any) {
    // audit RULE-5: 不在 catch 弹 toast — request.ts 拦截器已对 success=false 弹 sticky+actionHint
    console.error('加载工序出成率失败:', error);
    tableData.value = [];
  } finally {
    loading.value = false;
  }
}

// ---------- helpers ----------
function getConversionTagType(rate: number): string {
  if (rate >= 90) return 'success';
  if (rate >= 80) return 'warning';
  return 'danger';
}

function getConversionColor(rate: number): string {
  if (rate >= 90) return '#67C23A';
  if (rate >= 80) return '#E6A23C';
  return '#F56C6C';
}

function getWastageTagType(rate: number): string {
  if (rate <= 5) return 'success';
  if (rate <= 15) return 'warning';
  return 'danger';
}

function handleSearch() {
  loadData();
}

function handleReset() {
  dateRange.value = null;
  selectedProduct.value = '';
  loadData();
}
</script>

<template>
  <div class="page-wrapper">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">工序级投入产出对比</span>
            <span class="data-count">共 {{ tableData.length }} 个工序</span>
          </div>
          <div class="header-right">
            <el-date-picker
              v-model="dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="报工开始日期"
              end-placeholder="报工结束日期"
              value-format="YYYY-MM-DD"
              style="width: 280px"
              title="按 YIELD 报工日期筛选"
              @change="handleSearch"
            />
          </div>
        </div>
      </template>

      <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="工序汇总" name="summary">
      <!-- KPI Row -->
      <div class="kpi-row">
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value">{{ kpi.processCount }}</div>
          <div class="kpi-label">工序数量</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" data-testid="kpi-avg-conversion"
            :style="{ color: kpi.avgConversion == null ? '#909399' : getConversionColor(kpi.avgConversion) }">
            {{ kpi.avgConversion == null ? '—' : `${kpi.avgConversion}%` }}
          </div>
          <div class="kpi-label">平均转化率</div>
          <div class="kpi-note" data-testid="kpi-scope-note">{{ kpiScopeNote }}</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" :style="{ color: kpi.avgWastage == null ? '#909399' : '#E6A23C' }">
            {{ kpi.avgWastage == null ? '—' : `${kpi.avgWastage}%` }}
          </div>
          <div class="kpi-label">平均损耗率</div>
          <div class="kpi-note">{{ kpiScopeNote }}</div>
        </el-card>
        <el-card shadow="hover" class="kpi-card">
          <div class="kpi-value" style="color: #F56C6C">{{ kpi.lowEfficiencyCount }}</div>
          <div class="kpi-label">低效工序数 (&lt;80%)</div>
        </el-card>
      </div>

      <!-- Filter Bar -->
      <div class="search-bar">
        <el-select
          v-model="selectedProduct"
          placeholder="按产品筛选"
          clearable
          style="width: 220px"
          @change="handleSearch"
        >
          <el-option
            v-for="item in productTypes"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      </div>

      <!-- Main Table -->
      <el-table
        :data="tableData"
        v-loading="loading"
        empty-text="本厂暂无出成率报工数据 — 车间在 App 端逐道报工后此处自动汇总"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column prop="processName" label="工序名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="投入量" width="150" align="right">
          <template #default="{ row }">
            {{ formatQuantity(row.inputQuantity, displayProcessUnit(row.inputUnit)) }}
          </template>
        </el-table-column>
        <el-table-column label="产出量" width="150" align="right">
          <template #default="{ row }">
            {{ formatQuantity(row.outputQuantity, displayProcessUnit(row.outputUnit)) }}
          </template>
        </el-table-column>
        <el-table-column label="转化率" width="160" align="center">
          <template #default="{ row }">
            <span v-if="row.conversionRate == null" class="incomparable">{{ incomparableLabel(row) }}</span>
            <el-tag v-else :type="getConversionTagType(row.conversionRate)" size="small" effect="light">
              {{ row.conversionRate }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="损耗率" width="160" align="center">
          <template #default="{ row }">
            <span v-if="row.wastageRate == null" class="incomparable">{{ incomparableLabel(row) || '—' }}</span>
            <el-tag v-else :type="getWastageTagType(row.wastageRate)" size="small" effect="light">
              {{ row.wastageRate }}%
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="转化率进度" min-width="180">
          <template #default="{ row }">
            <span v-if="row.conversionRate == null">—</span>
            <el-progress v-else :percentage="Math.min(row.conversionRate, 100)"
              :color="getConversionColor(row.conversionRate)" :stroke-width="10" :show-text="false" />
          </template>
        </el-table-column>
        <el-table-column prop="batchCount" label="任务数" width="90" align="center" />
      </el-table>

      <!-- Legend -->
      <div class="rate-legend">
        <span class="legend-item">
          <span class="legend-dot success" />
          &ge;90% 优良
        </span>
        <span class="legend-item">
          <span class="legend-dot warning" />
          80%-90% 一般
        </span>
        <span class="legend-item">
          <span class="legend-dot danger" />
          &lt;80% 需改进
        </span>
        <span class="legend-item">
          投入与产出单位不同（如 kg → 盒）或单位不统一的工序不算转化率，也不计入平均
        </span>
      </div>
      </el-tab-pane>

      <el-tab-pane label="物料平衡表" name="balance">
        <div class="search-bar">
          <el-date-picker
            v-model="balanceDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="报工日期"
            :clearable="false"
            data-testid="balance-date"
            @change="loadBalance"
          />
          <el-button type="primary" :icon="Refresh" @click="loadBalance">刷新</el-button>
          <span class="balance-hint">
            投入 = 主产出 + 副产 + 已确认损耗(含留样) + 未解释差额;
            未解释差额占投入 &gt;{{ balance.warnPct }}% 标黄, &gt;{{ balance.redPct }}% 标红
          </span>
        </div>
        <div v-loading="balanceLoading" data-testid="balance-tables">
          <el-empty v-if="!balanceLoading && balance.plans.length === 0"
            description="这一天没有工序报工 — 换个日期试试" />
          <div v-for="plan in balance.plans" :key="plan.planId ?? 'none'" class="balance-plan">
            <div class="balance-plan-title">
              {{ plan.productName ?? '未关联产品' }}
              <span class="balance-plan-sub">计划 {{ plan.planNumber ?? '—' }} · 批次 {{ plan.batchNumbers.join('、') || '—' }}</span>
            </div>
            <el-table :data="plan.rows" border stripe style="width: 100%">
              <el-table-column label="工序" min-width="120">
                <template #default="{ row }">
                  <span data-testid="balance-process-name">{{ balanceProcessLabel(row.processName, row.processOrder) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="投入" width="120" align="right">
                <template #default="{ row }">{{ fmtQty(row.input, row.unit) }}</template>
              </el-table-column>
              <el-table-column label="主产出" width="120" align="right">
                <template #default="{ row }">{{ fmtQty(row.output, row.unit) }}</template>
              </el-table-column>
              <el-table-column label="副产" width="110" align="right">
                <template #default="{ row }">{{ fmtQty(row.byproducts, row.unit) }}</template>
              </el-table-column>
              <el-table-column label="已确认损耗" width="120" align="right">
                <template #default="{ row }">{{ fmtQty(row.confirmedLoss, row.unit) }}</template>
              </el-table-column>
              <el-table-column label="未解释差额" width="130" align="right">
                <template #default="{ row }">{{ fmtQty(row.unexplained, row.unit) }}</template>
              </el-table-column>
              <el-table-column label="占投入" width="90" align="center">
                <template #default="{ row }">{{ row.unexplainedPct == null ? '—' : row.unexplainedPct + '%' }}</template>
              </el-table-column>
              <el-table-column label="状态" min-width="150" align="center">
                <template #default="{ row }">
                  <el-tag :type="balanceTagType(row.level)" size="small" effect="light"
                    :data-testid="'balance-level-' + row.level">{{ balanceLabel(row) }}</el-tag>
                  <div v-if="row.note" class="balance-note">{{ row.note }}</div>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-color-lighter, #ebeef5);
  }

  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, #303133);
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, #909399);
    }
  }
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.kpi-card {
  text-align: center;

  :deep(.el-card__body) {
    padding: 16px;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  .kpi-value {
    font-size: 28px;
    font-weight: 700;
    line-height: 1.2;
    margin-bottom: 4px;
  }

  .kpi-label {
    font-size: 13px;
    color: var(--text-color-secondary, #909399);
  }

  .kpi-note {
    margin-top: 4px;
    font-size: 12px;
    color: var(--text-color-secondary, #909399);
  }
}

.incomparable {
  font-size: 12px;
  color: var(--text-color-secondary, #909399);
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

.rate-legend {
  display: flex;
  gap: 20px;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color-lighter, #ebeef5);
  font-size: 12px;
  color: #909399;

  .legend-item {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .legend-dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;

    &.success { background-color: #67C23A; }
    &.warning { background-color: #E6A23C; }
    &.danger { background-color: #F56C6C; }
  }
}

.balance-hint {
  font-size: 12px;
  color: #909399;
  align-self: center;
}

.balance-plan {
  margin-bottom: 20px;

  .balance-plan-title {
    font-weight: 600;
    margin-bottom: 8px;
  }

  .balance-plan-sub {
    margin-left: 12px;
    font-weight: 400;
    font-size: 12px;
    color: #909399;
  }
}

.balance-note {
  font-size: 12px;
  color: #909399;
}

@media (max-width: 768px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
