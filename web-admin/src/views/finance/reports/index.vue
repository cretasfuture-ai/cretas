<script setup lang="ts">
import { CS_TOKENS } from '@/styles/csTokens';
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { CsTableColumn } from '@/types/csTable';
import { toDateString } from '@/utils/dateFormat';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canRead = computed(() => permissionStore.canAccess('finance'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const loading = ref(false);
const loadFailed = ref(false);
const dateRange = ref<[Date, Date]>([
  new Date(new Date().setDate(new Date().getDate() - 30)),
  new Date()
]);

// 财务报表数据
const financeData = ref<{
  totalRevenue?: number;
  totalCost?: number;
  grossProfit?: number;
  profitMargin?: number | null;
  materialCost?: number;
  laborCost?: number;
  equipmentCost?: number;
  otherCost?: number;
  dailyStats?: Array<{ date: string; revenue: number; cost: number; profit: number }>;
}>({});

// 后端响应类型 (来自 GET /smart-bi/analysis/finance?analysisType=profit/cost)
interface MetricResultDTO {
  metricCode: string;
  metricName: string;
  value: number | null;
  formattedValue?: string;
  unit?: string;
}
interface ChartConfigDTO<T = Record<string, unknown>> {
  chartType?: string;
  title?: string;
  data?: T[];
}
interface ProfitAnalysisResponse {
  metrics?: MetricResultDTO[];
  trendChart?: ChartConfigDTO<{ period: string; revenue: number; cost: number; grossProfit: number; netProfit?: number; grossMargin: number | null }>;
}
interface CostAnalysisResponse {
  structureChart?: ChartConfigDTO<{ category?: string; name?: string; value: number; percentage?: number }>;
  trendChart?: ChartConfigDTO;
}

// P0-2: 卡片 format 函数统一签名 (v: number | null | undefined => string)
// 因为 profitMargin 可能为 null (后端 P0-1 Bug C 修复后，毛利率 >100% 或 <-100% 返 null)
type StatFormat = (v: number | null | undefined) => string;
// 没有值显示「—」, 不当 0 (毛利润取不到不等于毛利润为 0)
const fmtCurrency: StatFormat = (v) => v == null ? '—' : v.toLocaleString('zh-CN', { minimumFractionDigits: 2 });
const fmtPercent: StatFormat = (v) => v == null ? '—' : v.toFixed(1);

const statCards = computed<Array<{
  title: string;
  value: number | null | undefined;
  unit: string;
  icon: string;
  color: string;
  format: StatFormat;
}>>(() => [
  {
    title: '总收入',
    value: financeData.value.totalRevenue,
    unit: '元',
    icon: 'i-lucide-banknote',
    color: CS_TOKENS.primary,
    format: fmtCurrency
  },
  {
    title: '总成本',
    value: financeData.value.totalCost,
    unit: '元',
    icon: 'i-lucide-coins',
    color: 'var(--cs-warning)',
    format: fmtCurrency
  },
  {
    title: '毛利润',
    value: financeData.value.grossProfit,
    unit: '元',
    icon: 'i-lucide-trending-up',
    color: 'var(--cs-success)',
    format: fmtCurrency
  },
  {
    title: '利润率',
    // profitMargin 可能为 null — null 时显示 "N/A"，单位也清空
    value: financeData.value.profitMargin,
    unit: financeData.value.profitMargin == null ? '' : '%',
    icon: 'i-lucide-percent',
    color: 'var(--cs-danger)',
    format: fmtPercent
  }
]);

// 成本分解表格
const costBreakdown = computed(() => [
  { name: '原材料成本', value: financeData.value.materialCost ?? 0, percentage: getPercentage('material') },
  { name: '人工成本', value: financeData.value.laborCost ?? 0, percentage: getPercentage('labor') },
  { name: '设备成本', value: financeData.value.equipmentCost ?? 0, percentage: getPercentage('equipment') },
  { name: '其他成本', value: financeData.value.otherCost ?? 0, percentage: getPercentage('other') }
]);

// 当 4 个分类全部为 0 时, 显示空状态而不是 4 行 0.00 / 0.0%
// (chart audit P0-2 副发现: structureChart.data 真空时 backend 返 [], FE 应区分
// "0 数据" vs "全分类都是 0" — 前者是数据真空, 后者可能是分类未配置或时间窗内无成本)
const hasCostBreakdown = computed(() =>
  costBreakdown.value.some((row) => row.value > 0),
);

function getPercentage(type: string) {
  const total = financeData.value.totalCost ?? 1;
  const value = financeData.value[`${type}Cost` as keyof typeof financeData.value] as number ?? 0;
  return total > 0 ? ((value / total) * 100).toFixed(1) : '0.0';
}

// 财务数据是否为空 (无营收/成本/明细) — 用于默认期落地自动展开判断
function isFinanceDataEmpty(): boolean {
  const d = financeData.value;
  const hasRevenue = !!d.totalRevenue;
  const hasCost = !!d.totalCost;
  const hasStats = Array.isArray(d.dailyStats) && d.dailyStats.length > 0;
  return !hasRevenue && !hasCost && !hasStats;
}

onMounted(async () => {
  await loadFinanceData();
  // 优化默认落地 (2026-06-24): 财务数据来自 SmartBI 上传, 常落在更早会计期。
  // 近 30 天若无数据, 自动展开到近 12 个月重载一次, 让用户落地即见数据 (而非空状态)。
  if (isFinanceDataEmpty()) {
    const wideStart = new Date();
    wideStart.setMonth(wideStart.getMonth() - 12);
    dateRange.value = [wideStart, new Date()];
    await loadFinanceData();
    if (!isFinanceDataEmpty()) {
      ElMessage.info('近 30 天暂无财务数据，已自动展开至近 12 个月');
    }
  }
});

async function loadFinanceData() {
  if (!factoryId.value) return;

  loading.value = true;
  loadFailed.value = false;
  try {
    if (!dateRange.value) return;
    const [startDate, endDate] = dateRange.value;
    const startStr = formatDate(startDate);
    const endStr = formatDate(endDate);

    // P0-2: 并行调用 analysisType=profit + analysisType=cost
    // 后端返嵌套结构，前端需要 flat KPI 字段，所以双调用 + 字段映射
    const baseUrl = `/${factoryId.value}/smart-bi/analysis/finance?startDate=${startStr}&endDate=${endStr}`;
    const [profitRes, costRes] = await Promise.all([
      get<ProfitAnalysisResponse>(`${baseUrl}&analysisType=profit`),
      get<CostAnalysisResponse>(`${baseUrl}&analysisType=cost`)
    ]);

    if (!profitRes.success || !costRes.success) {
      loadFailed.value = true;
      ElMessage.error('加载财务数据失败');
      return;
    }

    financeData.value = mapResponses(profitRes.data, costRes.data);
  } catch (error) {
    loadFailed.value = true;
    console.error('加载财务数据失败:', error);
    ElMessage.error('加载财务数据失败，请检查网络连接');
  } finally {
    loading.value = false;
  }
}

/**
 * 将后端两个响应映射为前端 flat 字段
 * P0-2 字段映射：
 *  - totalRevenue/totalCost ← profit.trendChart.data 加和
 *  - grossProfit/profitMargin ← profit.metrics (metricCode 匹配)
 *  - materialCost/laborCost/equipmentCost/otherCost ← cost.structureChart.data (按 name 匹配)
 *  - dailyStats ← profit.trendChart.data (字段重命名 period→date, grossProfit→profit)
 */
function mapResponses(
  profitData: ProfitAnalysisResponse | undefined,
  costData: CostAnalysisResponse | undefined
): typeof financeData.value {
  const out: typeof financeData.value = {};

  // --- Profit metrics (KPI cards) ---
  const metrics = profitData?.metrics ?? [];
  const findMetric = (code: string): number | null | undefined => {
    const m = metrics.find(x => x.metricCode === code);
    return m ? m.value : undefined;
  };
  // 毛利额 (元) — backend GROSS_PROFIT
  const gp = findMetric('GROSS_PROFIT');
  out.grossProfit = gp != null ? Number(gp) : undefined;
  // 毛利率 (%) — backend GROSS_MARGIN. 后端可能返 null (毛利率>100% 或 <-100% 时，P0-1 Bug C 修复)
  const gm = findMetric('GROSS_MARGIN');
  out.profitMargin = gm != null ? Number(gm) : null;

  // --- Profit trendChart → totalRevenue/totalCost/dailyStats ---
  const profitTrend = profitData?.trendChart?.data ?? [];
  let totalRevenue = 0;
  let totalCost = 0;
  const dailyStats: Array<{ date: string; revenue: number; cost: number; profit: number }> = [];
  for (const p of profitTrend) {
    const revenue = Number(p.revenue ?? 0);
    const cost = Number(p.cost ?? 0);
    const profit = Number(p.grossProfit ?? 0);
    totalRevenue += revenue;
    totalCost += cost;
    dailyStats.push({ date: p.period, revenue, cost, profit });
  }
  out.totalRevenue = totalRevenue;
  out.totalCost = totalCost;
  out.dailyStats = dailyStats;

  // --- Cost structureChart → materialCost/laborCost/equipmentCost/otherCost ---
  // 后端 PIE data items 来自 createPieDataItem(category, value, total) — 字段是 category/value/percentage
  const costSlices = costData?.structureChart?.data ?? [];
  for (const slice of costSlices) {
    const name = String(slice.category ?? slice.name ?? '');
    const value = Number(slice.value ?? 0);
    if (name.includes('原材料')) {
      out.materialCost = (out.materialCost ?? 0) + value;
    } else if (name.includes('人工')) {
      out.laborCost = (out.laborCost ?? 0) + value;
    } else if (name.includes('制造费用') || name.includes('设备')) {
      // 后端常量是 "制造费用" (overheadCost)，UI 显示为 "设备成本"，做兼容映射
      out.equipmentCost = (out.equipmentCost ?? 0) + value;
    } else {
      out.otherCost = (out.otherCost ?? 0) + value;
    }
  }
  // 默认值 fallback (如果某类没有)
  out.materialCost = out.materialCost ?? 0;
  out.laborCost = out.laborCost ?? 0;
  out.equipmentCost = out.equipmentCost ?? 0;
  out.otherCost = out.otherCost ?? 0;

  return out;
}

function formatDate(date: Date | null | undefined) {
  if (!date) return '';
  return toDateString(date);
}

// 日期范围: 两个原生日期输入 (取代 el-date-picker, Nuxt UI 页面里不混用 Element 控件)
const startStr = computed({
  get: () => formatDate(dateRange.value?.[0]),
  set: (v: string) => { if (v) { dateRange.value = [new Date(`${v}T00:00:00`), dateRange.value[1]]; handleDateChange(); } },
});
const endStr = computed({
  get: () => formatDate(dateRange.value?.[1]),
  set: (v: string) => { if (v) { dateRange.value = [dateRange.value[0], new Date(`${v}T00:00:00`)]; handleDateChange(); } },
});
function setRangeDays(days: number) {
  dateRange.value = [new Date(Date.now() - days * 24 * 60 * 60 * 1000), new Date()];
  handleDateChange();
}

function handleDateChange() {
  loadFinanceData();
}

const costColumns: CsTableColumn<{ name: string; value: number; percentage: string }>[] = [
  { accessorKey: 'name', header: '成本类型' },
  { id: 'value', header: '金额 (元)', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'percentage', header: '占比' },
];
const barColor = (p: string) => (parseFloat(p) > 50 ? 'var(--cs-danger)' : parseFloat(p) > 30 ? 'var(--cs-warning)' : 'var(--cs-success)');

function handleExport() {
  const data = financeData.value;
  if (!data || (!data.totalRevenue && !data.totalCost)) {
    ElMessage.warning('暂无数据可导出');
    return;
  }
  const [startDate, endDate] = dateRange.value;
  const headers = ['项目', '金额(元)', '占比(%)'];
  const rows = [
    ['总收入', String(data.totalRevenue ?? 0), '-'],
    ['总成本', String(data.totalCost ?? 0), '100.0'],
    ['  原材料成本', String(data.materialCost ?? 0), getPercentage('material')],
    ['  人工成本', String(data.laborCost ?? 0), getPercentage('labor')],
    ['  设备成本', String(data.equipmentCost ?? 0), getPercentage('equipment')],
    ['  其他成本', String(data.otherCost ?? 0), getPercentage('other')],
    ['毛利润', String(data.grossProfit ?? 0), '-'],
    ['利润率(%)', data.profitMargin == null ? 'N/A' : data.profitMargin.toFixed(1), '-']
  ];
  const csvContent = '\uFEFF' + [
    [`财务报表 (${formatDate(startDate)} ~ ${formatDate(endDate)})`],
    [],
    headers,
    ...rows
  ].map(r => r.map(c => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `财务报表_${formatDate(startDate)}_${formatDate(endDate)}.csv`;
  link.click();
  URL.revokeObjectURL(link.href);
  ElMessage.success('导出成功');
}
</script>

<template>
  <div class="page" data-testid="finance-reports-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>财务分析(Excel)</h2>
        <div class="head-actions">
          <UInput v-model="startStr" type="date" class="w-40" data-testid="reports-start" />
          <span class="muted">至</span>
          <UInput v-model="endStr" type="date" class="w-40" data-testid="reports-end" />
          <UButton color="neutral" variant="outline" size="sm" @click="setRangeDays(7)">最近一周</UButton>
          <UButton color="neutral" variant="outline" size="sm" @click="setRangeDays(30)">最近一月</UButton>
          <UButton color="neutral" variant="outline" size="sm" @click="setRangeDays(90)">最近三月</UButton>
          <UButton v-if="canViewPrice" icon="i-lucide-download" data-testid="reports-export" @click="handleExport">导出报表</UButton>
        </div>
      </header>

      <div class="body">
        <template v-if="canViewPrice">
          <UAlert v-if="loadFailed" color="error" variant="soft" icon="i-lucide-circle-alert" class="mb-4" data-testid="reports-load-error"
            title="财务数据暂时没取到，下方金额显示为「—」，请调整日期范围重试。" />

          <!-- 统计卡片 -->
          <div class="stat-cards" data-testid="reports-stat-cards">
            <div v-for="card in statCards" :key="card.title" class="stat-card">
              <div class="stat-icon" :style="{ backgroundColor: `color-mix(in srgb, ${card.color} 14%, transparent)`, color: card.color }">
                <UIcon :name="card.icon" class="size-6" />
              </div>
              <div class="stat-info">
                <div class="stat-title">{{ card.title }}</div>
                <div class="stat-value" :style="{ color: card.color }">
                  {{ card.format(card.value) }} <span class="stat-unit">{{ card.unit }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 成本分解 -->
          <h3 class="section-title">成本分解</h3>
          <UTable v-if="hasCostBreakdown" :data="costBreakdown" :columns="costColumns" class="cs-table" data-testid="reports-cost-table">
            <template #value-cell="{ row }"><span class="num">{{ row.original.value.toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</span></template>
            <template #percentage-cell="{ row }">
              <div class="bar-wrap">
                <div class="bar"><div class="bar-fill" :style="{ width: `${Math.min(100, parseFloat(row.original.percentage))}%`, background: barColor(row.original.percentage) }" /></div>
                <span class="num">{{ row.original.percentage }}%</span>
              </div>
            </template>
          </UTable>
          <p v-else class="empty" data-testid="reports-cost-empty">该期无成本细分数据 — 可在「成本分析」页面查看明细或检查 Excel 上传是否包含 原材料 / 人工 / 制造费用 列</p>

          <UAlert color="info" variant="soft" icon="i-lucide-info" class="mt-4" title="数据说明"
            description="当前显示的是所选日期范围内的财务汇总数据。详细的成本分析请前往「成本分析」页面查看。" />
        </template>
        <p v-else class="empty" data-testid="reports-no-permission">您没有查看价格/财务数据的权限</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: center; flex-wrap: wrap; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.head-actions { margin-left: auto; display: flex; align-items: center; flex-wrap: wrap; gap: var(--cs-space-2); }
.body { padding: var(--cs-space-5); }
.muted { color: var(--cs-muted); font-size: var(--cs-font-small); }
.stat-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--cs-space-4); margin-bottom: var(--cs-space-6); }
.stat-card { display: flex; align-items: center; gap: var(--cs-space-4); padding: var(--cs-space-4); background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); }
.stat-icon { width: 48px; height: 48px; border-radius: var(--cs-radius); display: flex; align-items: center; justify-content: center; }
.stat-info { flex: 1; }
.stat-title { font-size: 13px; color: var(--cs-muted); margin-bottom: 4px; }
.stat-value { font-size: 22px; font-weight: 600; font-variant-numeric: tabular-nums; }
.stat-unit { font-size: 12px; font-weight: normal; margin-left: 4px; }
.section-title { font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); margin: 0 0 var(--cs-space-3); padding-left: 10px; border-left: 3px solid var(--cs-primary); }
.bar-wrap { display: flex; align-items: center; gap: var(--cs-space-2); }
.bar { flex: 1; height: 6px; border-radius: 3px; background: var(--cs-surface-subtle); overflow: hidden; min-width: 80px; }
.bar-fill { height: 100%; }
.empty { padding: var(--cs-space-6) 0; text-align: center; color: var(--cs-muted); font-size: 13px; background: var(--cs-surface-subtle); border-radius: var(--cs-radius-sm); }
.num { font-variant-numeric: tabular-nums; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
