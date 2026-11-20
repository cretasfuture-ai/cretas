<script setup lang="ts">
/**
 * SP9 人效对比页 — LaborEfficiencyCompare
 *
 * 后端: GET /api/mobile/{factoryId}/labor-efficiency/compare
 * 参数: startDate (REQUIRED), endDate (REQUIRED), productTypeId? (optional)
 *
 * @PriceSensitive: 所有成本/金额字段对非财务角色为 null，严禁 JS 端重算，一律显示 "—"。
 * 注意: 本页为 M3 双口径对比表。M4 达成率汇总 + step-breakdown 端点见人效看板 (dashboard.vue)。
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor } from '@/utils/csBadge';
import { get } from '@/api/request';
import {
  getLaborEfficiencyCompare,
  type LaborEfficiencyCompareDTO,
  type LaborVarianceItemDTO,
} from '@/api/laborEfficiency';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const router = useRouter();

// ============================================================================
// 产品类型选项
// ============================================================================

interface ProductTypeOption {
  id: string;
  name: string;
}
const productTypes = ref<ProductTypeOption[]>([]);

async function loadProductTypes() {
  try {
    const res = await get<ProductTypeOption[]>(`/${factoryId.value}/product-types/active`);
    if (res.success && res.data) productTypes.value = res.data;
  } catch {
    // 非阻塞
  }
}

// ============================================================================
// 查询条件
// ============================================================================

// 默认近30天 — 用 sv-SE locale 得到本地时区的 YYYY-MM-DD，避免 UTC 偏移在 UTC+8 凌晨给出昨天的日期
function defaultDateRange(): [string, string] {
  const end = new Date();
  const start = new Date();
  start.setDate(start.getDate() - 30);
  const fmt = (d: Date) => d.toLocaleDateString('sv-SE'); // YYYY-MM-DD in local timezone
  return [fmt(start), fmt(end)];
}

const [defaultStart, defaultEnd] = defaultDateRange();
const startDate = ref(defaultStart);
const endDate = ref(defaultEnd);
const dateRange = computed<[string, string]>(() => [startDate.value, endDate.value]);
const selectedProductTypeId = ref<string | null>(null);

// ============================================================================
// 数据
// ============================================================================

const columns: TableColumn<LaborEfficiencyCompareDTO>[] = [
  { accessorKey: 'batchNumber', header: '批次号' },
  { accessorKey: 'productName', header: '产品' },
  { id: 'quotedKg', header: '报价人工/kg' },
  { id: 'actualKg', header: '实际人工/kg' },
  { id: 'quotedBox', header: '报价人工/箱' },
  { id: 'actualBox', header: '实际人工/箱' },
  { id: 'variance', header: '偏差率' },
  { id: 'status', header: '状态' },
  { id: 'steps', header: '工序' },
];
const stepColumns: TableColumn<LaborVarianceItemDTO>[] = [
  { accessorKey: 'processName', header: '工序' },
  { id: 'workMinutes', header: '工时' },
  { id: 'workers', header: '人数' },
  { id: 'laborCost', header: '工序人工成本' },
  { id: 'perBox', header: '每箱人工成本' },
  { id: 'rate', header: '达成率' },
  { id: 'alert', header: '达成状态' },
];
const compareRows = ref<LaborEfficiencyCompareDTO[]>([]);
const loading = ref(false);

async function loadData() {
  if (!factoryId.value) return;
  if (!dateRange.value || !dateRange.value[0] || !dateRange.value[1]) {
    ElMessage({ message: '请选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  loading.value = true;
  try {
    const res = await getLaborEfficiencyCompare(
      factoryId.value,
      dateRange.value[0],
      dateRange.value[1],
      selectedProductTypeId.value || null,
    );
    if (res.success && res.data) {
      compareRows.value = res.data;
    } else {
      ElMessage({ message: res.message || '加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

// ============================================================================
// 格式化工具
// ============================================================================

function fmt(v: number | null | undefined, prefix = '¥', decimals = 2): string {
  if (v == null) return '—';
  return `${prefix}${v.toLocaleString('zh-CN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })}`;
}

function fmtPct(v: number | null | undefined): string {
  if (v == null) return '—';
  const sign = v > 0 ? '+' : '';
  return `${sign}${v.toFixed(2)}%`;
}

function fmtMin(minutes: number | null | undefined): string {
  if (minutes == null) return '—';
  if (minutes < 60) return `${minutes} 分`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m > 0 ? `${h}h${m}m` : `${h}h`;
}

function varianceStatusType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'OK':
    case 'NORMAL': return 'success';
    case 'WARNING': return 'warning';
    case 'CRITICAL': return 'danger';
    default: return 'info';
  }
}

function varianceStatusLabel(status: string): string {
  switch (status) {
    case 'OK':
    case 'NORMAL': return '正常';
    case 'WARNING': return '预警';
    case 'CRITICAL': return '严重超支';
    default: return status;
  }
}

function varianceRateClass(varianceRate: number | null, status: string): string {
  if (varianceRate == null) return '';
  if (status === 'CRITICAL') return 'text-critical';
  if (status === 'WARNING') return 'text-warning';
  return 'text-normal';
}

function achievementAlertLabel(alert: string | null): string {
  if (alert === 'BELOW_ALERT') return '低于标准';
  if (alert === 'ABOVE_ALERT') return '高于标准';
  return '—';
}

// ============================================================================
// 展开行 — 工序明细
// ============================================================================

const expandedRows = ref<Set<string>>(new Set());

function toggleExpand(row: LaborEfficiencyCompareDTO) {
  if (expandedRows.value.has(row.batchNumber)) {
    expandedRows.value.delete(row.batchNumber);
  } else {
    expandedRows.value.add(row.batchNumber);
  }
}

// ============================================================================
// 初始化
// ============================================================================

onMounted(async () => {
  await loadProductTypes();
  await loadData();
});
</script>

<template>
  <div class="labor-efficiency-page">
    <div class="cs-page-head">
      <h2>人效对比分析</h2>
      <UButton size="sm" variant="outline" @click="router.push({ name: 'LaborEfficiencyDashboard' })">查看达成率看板 →</UButton>
    </div>

    <UAlert
      color="info"
      variant="subtle"
      icon="i-lucide-info"
      title="成本字段对非财务角色不可见（显示 —）。对比实际 vs 报价人工成本，识别超支批次。"
      class="mb"
    />

    <!-- 查询条件 -->
    <section class="cs-card mb">
      <div class="cs-card-head filter">
        <UFormField label="开始日期" orientation="horizontal">
          <UInput v-model="startDate" type="date" class="w-40" />
        </UFormField>
        <UFormField label="结束日期" orientation="horizontal">
          <UInput v-model="endDate" type="date" class="w-40" />
        </UFormField>
        <UFormField label="产品" orientation="horizontal">
          <USelectMenu v-model="selectedProductTypeId" :items="productTypes" value-key="id" label-key="name" placeholder="全部产品" clear class="w-52" />
        </UFormField>
        <UButton icon="i-lucide-search" :loading="loading" @click="loadData">查询</UButton>
      </div>

      <!-- 对比表 -->
      <UTable :data="compareRows" :columns="columns" :loading="loading" class="cs-table">
        <template #empty>暂无数据 — 请调整日期范围后重新查询</template>
        <template #quotedKg-cell="{ row }"><span class="num">{{ fmt(row.original.quotedLaborCostPerKg) }}</span></template>
        <template #actualKg-cell="{ row }"><span class="num">{{ fmt(row.original.actualLaborCostPerKg) }}</span></template>
        <template #quotedBox-cell="{ row }"><span class="num">{{ fmt(row.original.quotedLaborCostPerBox) }}</span></template>
        <template #actualBox-cell="{ row }"><span class="num">{{ fmt(row.original.actualLaborCostPerBox) }}</span></template>
        <template #variance-header>
          <span>偏差率</span>
          <span title="偏差率需先在「研发样品管理」为该批次配置预估人工成本">
            <UButton size="xs" variant="link" @click="router.push('/rd/samples')">去配置</UButton>
          </span>
        </template>
        <template #variance-cell="{ row }">
          <span v-if="row.original.varianceRate != null" class="num" :class="varianceRateClass(row.original.varianceRate, row.original.varianceStatus)">
            {{ fmtPct(row.original.varianceRate) }}
          </span>
          <!-- Rule 5: dead-end → 导航 -->
          <UButton v-else size="xs" variant="link" color="neutral" @click="router.push('/rd/samples')">未设标准 →</UButton>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(varianceStatusType(row.original.varianceStatus))" variant="soft">{{ varianceStatusLabel(row.original.varianceStatus) }}</UBadge>
        </template>
        <template #steps-cell="{ row }">
          <UButton v-if="row.original.stepDetails && row.original.stepDetails.length > 0" size="xs" variant="link" color="neutral" @click="toggleExpand(row.original)">
            {{ expandedRows.has(row.original.batchNumber) ? '收起' : `${row.original.stepDetails.length} 道` }}
          </UButton>
          <span v-else class="text-muted">—</span>
        </template>
      </UTable>

      <!-- 展开区域: 工序级明细 -->
      <template v-for="row in compareRows" :key="row.batchNumber + '-steps'">
        <div v-if="expandedRows.has(row.batchNumber)" class="step-detail-block">
          <div class="step-detail-header">工序明细 — {{ row.productName }}（{{ row.batchNumber }}）</div>
          <UTable :data="row.stepDetails" :columns="stepColumns" class="cs-table step-table">
            <template #workMinutes-cell="{ row: step }"><span class="num">{{ fmtMin(step.original.totalWorkMinutes) }}</span></template>
            <template #workers-cell="{ row: step }"><span class="num">{{ step.original.totalWorkers ?? '—' }}</span></template>
            <template #laborCost-cell="{ row: step }"><span class="num">{{ fmt(step.original.laborCost) }}</span></template>
            <template #perBox-cell="{ row: step }"><span class="num">{{ fmt(step.original.laborCostPerBox) }}</span></template>
            <template #rate-cell="{ row: step }">
              <span
                v-if="step.original.achievementRate != null"
                class="num"
                :class="step.original.achievementAlert === 'BELOW_ALERT' ? 'text-warning' : step.original.achievementAlert === 'ABOVE_ALERT' ? 'text-critical' : 'text-normal'"
              >{{ (step.original.achievementRate as number).toFixed(1) }}%</span>
              <span v-else class="text-muted">未设标准</span>
            </template>
            <template #alert-cell="{ row: step }">{{ achievementAlertLabel(step.original.achievementAlert) }}</template>
          </UTable>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.labor-efficiency-page { padding: var(--cs-space-4); }
.mb { margin-bottom: var(--cs-space-3); }
.filter { gap: var(--cs-space-4); }
.step-detail-block { padding: 0 var(--cs-space-4) var(--cs-space-3); background: var(--cs-surface-subtle); border-top: 1px solid var(--cs-border); }
.step-detail-header { padding: var(--cs-space-2) 0 var(--cs-space-1); font-size: 13px; font-weight: 600; color: var(--cs-muted); }
.step-table { background: var(--cs-surface); }
.text-critical { color: var(--cs-danger); font-weight: 600; }
.text-warning { color: var(--cs-warning); font-weight: 600; }
.text-normal { color: var(--cs-success); }
.text-muted { color: var(--cs-muted); }
</style>
