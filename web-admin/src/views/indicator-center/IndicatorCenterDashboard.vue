<template>
  <div class="indicator-center">
    <!-- Sprint 11 D6 — Mock 数据 banner (per goal anti-goal) -->
    <el-alert
      v-if="isMockFactory"
      type="warning"
      :closable="false"
      show-icon
      class="mock-banner"
    >
      <template #title>
        <span class="mock-title">
          模拟数据 — F999_MOCK 工厂 30 天 × 7 指标 = 210 行 mock snapshots
        </span>
      </template>
      <template #default>
        <span class="mock-detail">
          数据由生成脚本 ({{ '`scripts/mock/generate-f006-indicator-data.py`' }})
          落库。切回真实工厂数据后 UI 业务逻辑不变。
        </span>
      </template>
    </el-alert>

    <!-- Sprint 11 BI 4-B fix — 真业务数据 B2B section (前端 compute, Sprint 12 接 backend) -->
    <B2BRealDataSection
      v-if="!isMockFactory && hasMirroredIndicators"
      :factory-id="factoryId || ''"
    />

    <el-card class="period-card" shadow="never">
      <div class="period-toolbar">
        <span class="period-label">实际流统计期间</span>
        <el-radio-group v-model="periodPreset" size="small">
          <el-radio-button label="today">今天</el-radio-button>
          <el-radio-button label="month">本月</el-radio-button>
          <el-radio-button label="custom">自定义</el-radio-button>
        </el-radio-group>
        <el-date-picker v-if="periodPreset === 'custom'" v-model="customPeriodRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" clearable size="small" />
        <span class="period-hint">{{ periodLabel }}</span>
      </div>
      <p v-if="periodError" class="period-error">{{ periodError }}</p>
      <p class="period-hint">此期间用于实际出成率、生产良品率和完整成本；其他指标沿用各自口径。</p>
    </el-card>

    <IndicatorApplicability :factory-id="factoryId || ''" :actor-key="actorKey" />

    <!-- 头部 -->
    <div class="page-header">
      <div class="title-block">
        <el-icon class="title-icon" :size="24"><Histogram /></el-icon>
        <div>
          <h2 class="page-title">指标中心</h2>
          <p class="page-subtitle">食品行业指标定义、阈值预警、历史趋势</p>
        </div>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="refreshAll" :loading="loading">
          刷新全部
        </el-button>
      </div>
    </div>

    <!-- 汇总卡片 -->
    <el-row :gutter="16" class="summary-row">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="summary-card" shadow="never">
          <div class="summary-content">
            <div class="summary-icon icon-total">
              <el-icon :size="28"><DataLine /></el-icon>
            </div>
            <div class="summary-text">
              <div class="summary-num">{{ stats.total }}</div>
              <div class="summary-label">总指标</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="summary-card" shadow="never">
          <div class="summary-content">
            <div class="summary-icon icon-green">
              <el-icon :size="28"><CircleCheck /></el-icon>
            </div>
            <div class="summary-text">
              <div class="summary-num text-success">{{ stats.available }}</div>
              <div class="summary-label">数据可用</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="summary-card" shadow="never">
          <div class="summary-content">
            <div class="summary-icon icon-yellow">
              <el-icon :size="28"><Warning /></el-icon>
            </div>
            <div class="summary-text">
              <div class="summary-num text-warning">{{ stats.missing }}</div>
              <div class="summary-label">缺数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card class="summary-card" shadow="never">
          <div class="summary-content">
            <div class="summary-icon icon-red">
              <el-icon :size="28"><CircleClose /></el-icon>
            </div>
            <div class="summary-text">
              <div class="summary-num text-danger">{{ stats.failed }}</div>
              <div class="summary-label">计算失败</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 主内容: 列表 + 树切换 -->
    <el-card class="main-card" shadow="never">
      <template #header>
        <div class="main-header">
          <el-tabs v-model="activeView" class="view-tabs">
            <el-tab-pane name="list">
              <template #label>
                <span class="tab-label">
                  <el-icon><Grid /></el-icon>
                  指标列表
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="tree">
              <template #label>
                <span class="tab-label">
                  <el-icon><Share /></el-icon>
                  指标树
                </span>
              </template>
            </el-tab-pane>
          </el-tabs>

          <div class="filter-bar" v-show="activeView === 'list'">
            <el-select
              v-model="filterCategory"
              placeholder="全部分类"
              clearable
              size="default"
              class="category-filter"
              @change="loadList"
            >
              <el-option label="全部" :value="''" />
              <el-option label="工厂" value="FACTORY" />
              <el-option label="餐饮" value="RESTAURANT" />
              <el-option label="质量" value="QUALITY" />
            </el-select>
            <el-input
              v-model="searchKey"
              placeholder="搜索名称或编码"
              clearable
              size="default"
              :prefix-icon="Search"
              class="search-input"
            />
          </div>
        </div>
      </template>

      <!-- 列表视图 -->
      <div v-show="activeView === 'list'" class="list-view">
        <div v-if="loading" class="loading-block">
          <el-skeleton :rows="6" animated />
        </div>

        <el-empty
          v-else-if="filteredIndicators.length === 0"
          :image-size="100"
          :description="searchKey || filterCategory ? '无匹配指标' : '暂无指标数据'"
        />

        <el-row v-else :gutter="16" class="card-grid">
          <el-col
            v-for="ind in filteredIndicators"
            :key="ind.code"
            :xs="24"
            :sm="12"
            :md="8"
            :lg="6"
            class="card-col"
          >
            <IndicatorValueCard
              :indicator="indicatorToCardData(ind)"
              :clickable="true"
              @click="onSelectIndicator"
            />
          </el-col>
        </el-row>
      </div>

      <!-- 树视图 -->
      <div v-show="activeView === 'tree'" class="tree-view">
        <IndicatorTreeViewer
          ref="treeRef"
          :factory-id="factoryId"
          @select="onSelectIndicator"
        />
      </div>
    </el-card>

    <!-- 详情抽屉 -->
    <IndicatorDetailDrawer
      v-model="drawerOpen"
      :factory-id="factoryId"
      :actor-key="actorKey"
      :code="selectedCode"
      :initial-period="selectedPeriod"
      @recomputed="onRecomputed"
    />
    <ProductProductionResults :factory-id="factoryId || ''" :actor-key="actorKey" :period="selectedPeriod" />
    <FinishedBatchEvidence :factory-id="factoryId || ''" :actor-key="actorKey" :period="selectedPeriod" />
  </div>
</template>

<script setup lang="ts">
/**
 * IndicatorCenterDashboard — 指标中心入口页.
 *
 * 顶部 4 个汇总卡片 (总数 / 数据可用 / 缺数 / 计算失败).
 * 主区: el-tabs 切换 "列表" / "树".
 * 选中指标 → 打开 IndicatorDetailDrawer 显示完整信息.
 */
import { ref, computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage } from 'element-plus';
import {
  Histogram,
  Refresh,
  DataLine,
  CircleCheck,
  Warning,
  CircleClose,
  Grid,
  Share,
  Search,
} from '@element-plus/icons-vue';
import {
  listIndicators,
  getIndicatorValue,
  type IndicatorListResponse,
  type IndicatorCategory,
  type AlertLevel,
  type IndicatorValueStatus,
} from '@/api/indicator';
import IndicatorValueCard from '@/components/indicator/IndicatorValueCard.vue';
import IndicatorTreeViewer from './IndicatorTreeViewer.vue';
import IndicatorDetailDrawer from './IndicatorDetailDrawer.vue';
import B2BRealDataSection from './B2BRealDataSection.vue';
import FinishedBatchEvidence from './FinishedBatchEvidence.vue';
import ProductProductionResults from './ProductProductionResults.vue';
import IndicatorApplicability from '@/components/indicators/IndicatorApplicability.vue';
import { isValidPeriod } from './actualFlowPeriod';

const authStore = useAuthStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const actorRevision = ref(0);
const actorKey = computed(() => JSON.stringify([authStore.user?.id, actorRevision.value]));
watch([() => authStore.user?.id, () => authStore.currentRole], () => { actorRevision.value += 1; }, { flush: 'sync' });

// Sprint 11 D6 — Mock factory detection (per goal anti-goal: 必标"模拟数据")
const isMockFactory = computed(() =>
  factoryId.value === 'F999_MOCK' || factoryId.value?.endsWith('_MOCK') || false
);

// Sprint 12 Phase A: V_23_11 mirror is being deleted via V20260825_01 migration.
// After deploy, no indicators carry these codes anymore, so the filter becomes a no-op.
// Kept as an empty array (rather than removing the wiring) so the banner conditional
// `hasMirroredIndicators` stays well-defined while Phase D rewrites banner UX.
const MIRRORED_CODES: string[] = [];

const mirroredIndicatorsPresent = computed(() => {
  if (isMockFactory.value) return [];
  return indicators.value
    .filter(i => MIRRORED_CODES.includes(i.code))
    .map(i => i.code);
});

const hasMirroredIndicators = computed(() => mirroredIndicatorsPresent.value.length > 0);
const mirroredCodeList = computed(() => mirroredIndicatorsPresent.value.join(', '));

interface DashboardIndicator extends IndicatorListResponse {
  status?: IndicatorValueStatus;
  periodStart?: string;
  periodEnd?: string;
}

const ACTUAL_FLOW_CODES = new Set([
  'FACTORY_LU_YIELD_RATE',
  'FACTORY_GOOD_OUTPUT_RATE',
  'FACTORY_LU_UNIT_COST',
  'FACTORY_ACTUAL_TOTAL_COST',
]);

const indicators = ref<DashboardIndicator[]>([]);
const loading = ref(false);
const activeView = ref<'list' | 'tree'>('list');
const filterCategory = ref<IndicatorCategory | ''>('');
const searchKey = ref('');
const selectedCode = ref<string | null>(null);
const drawerOpen = ref(false);
type PeriodPreset = 'today' | 'month' | 'custom';
type DateRange = [string, string];
const periodPreset = ref<PeriodPreset>('today');
const customPeriodRange = ref<DateRange | null>(null);
const periodError = ref<string | null>(null);
function formatDateOnly(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
const calendarToday = ref(formatDateOnly(new Date()));
const selectedPeriod = computed<DateRange | null>(() => {
  const today = calendarToday.value;
  if (periodPreset.value === 'today') return [today, today];
  if (periodPreset.value === 'month') {
    return [`${today.slice(0, 7)}-01`, today];
  }
  return isValidPeriod(customPeriodRange.value) ? customPeriodRange.value : null;
});
const periodLabel = computed(() => selectedPeriod.value ? selectedPeriod.value.join(' 至 ') : '请选择完整日期');
const treeRef = ref<InstanceType<typeof IndicatorTreeViewer> | null>(null);
const mounted = ref(false);
let requestSeq = 0;

const stats = computed(() => {
  const total = indicators.value.length;
  const statusOf = (indicator: DashboardIndicator): IndicatorValueStatus => {
    if (indicator.status) return indicator.status;
    return indicator.lastValue == null ? 'NOT_COMPUTED' : 'AVAILABLE';
  };
  return {
    total,
    available: indicators.value.filter(i => statusOf(i) === 'AVAILABLE' && i.lastValue != null).length,
    missing: indicators.value.filter(i => ['NO_DATA', 'NOT_COMPUTED'].includes(statusOf(i))).length,
    failed: indicators.value.filter(i => statusOf(i) === 'FAILED').length,
  };
});

const filteredIndicators = computed(() => {
  let list = indicators.value;
  // Sprint 11 BI 4-B fix: 非 mock 工厂下隐藏 F999_MOCK mirror codes
  // 这些 codes 业态错配 (餐饮 codes 在 F006 工厂场景没意义) + 数值是 mirror 不是真算
  // B2BRealDataSection 替代提供真业务数据 cards
  // Sprint 12 backend 真接业务表后, 删除本 filter + 删除 V_23_11 mirror migration
  if (!isMockFactory.value) {
    list = list.filter(i => !MIRRORED_CODES.includes(i.code));
  }
  if (searchKey.value) {
    const v = searchKey.value.toLowerCase();
    list = list.filter(
      i => i.name.toLowerCase().includes(v) || i.code.toLowerCase().includes(v)
    );
  }
  return list;
});

async function loadList() {
  const currentFactoryId = factoryId.value;
  const currentActor = actorKey.value;
  const seq = ++requestSeq;
  indicators.value = [];
  loading.value = false;
  if (!currentFactoryId) {
    ElMessage.warning('未识别到当前工厂, 无法加载指标');
    return;
  }
  const range = selectedPeriod.value;
  if (!range) {
    indicators.value = [];
    periodError.value = '日期为空或无效，未查询指标';
    return;
  }
  periodError.value = null;
  indicators.value = [];
  loading.value = true;
  try {
    const list = await listIndicators(
      currentFactoryId,
      filterCategory.value || undefined
    );
    if (!mounted.value || seq !== requestSeq || factoryId.value !== currentFactoryId || actorKey.value !== currentActor) return;
    const nextIndicators = await Promise.all(list.map(async (indicator): Promise<DashboardIndicator> => {
      if (!ACTUAL_FLOW_CODES.has(indicator.code)) return indicator;
      try {
        const current = await getIndicatorValue(currentFactoryId, indicator.code, {
          periodStart: range[0],
          periodEnd: range[1],
        });
        const status = current.status || (current.value == null ? 'NO_DATA' : 'AVAILABLE');
        return {
          ...indicator,
          lastValue: status === 'AVAILABLE' ? current.value : null,
          lastComputedAt: status === 'AVAILABLE' ? current.computedAt ?? null : null,
          status,
          periodStart: current.periodStart || range[0],
          periodEnd: current.periodEnd || range[1],
        };
      } catch (error) {
        console.warn(`[IndicatorCenterDashboard] value load failed: ${indicator.code}`, error);
        return {
          ...indicator,
          lastValue: null,
          lastComputedAt: null,
          status: 'FAILED',
          periodStart: range[0],
          periodEnd: range[1],
        };
      }
    }));
    if (mounted.value && seq === requestSeq && factoryId.value === currentFactoryId && actorKey.value === currentActor && selectedPeriod.value?.[0] === range[0] && selectedPeriod.value?.[1] === range[1]) {
      indicators.value = nextIndicators;
    }
  } catch (e) {
    console.warn('[IndicatorCenterDashboard] list load failed:', e);
    if (mounted.value && seq === requestSeq) {
      indicators.value = [];
      periodError.value = e instanceof Error ? `指标读取失败：${e.message}` : '指标读取失败，请刷新重试';
    }
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

async function refreshAll() {
  if (syncCalendarToday()) await nextTick();
  else await loadList();
  if (treeRef.value) {
    await treeRef.value.loadTree();
  }
}
function syncCalendarToday(): boolean {
  const before = selectedPeriod.value?.join('|');
  calendarToday.value = formatDateOnly(new Date());
  return before !== selectedPeriod.value?.join('|');
}

function onSelectIndicator(code: string) {
  selectedCode.value = code;
  drawerOpen.value = true;
}

function onRecomputed(_code: string) {
  // 重算成功后刷新列表 (lastValue 更新)
  void refreshAll();
}

function indicatorToCardData(ind: DashboardIndicator) {
  return {
    code: ind.code,
    name: ind.name,
    description: ind.description,
    category: ind.category,
    unit: ind.unit,
    lastValue: ind.lastValue,
    lastComputedAt: ind.lastComputedAt,
    status: ind.status,
    periodStart: ind.periodStart,
    periodEnd: ind.periodEnd,
    computeStrategy: ind.computeStrategy,
    // list 接口不返回 alertLevel; 点击详情时从 /value 获取真值
    alertLevel: null as AlertLevel,
    // Sprint 12 #265: next-action 提示 (后端 config.actionHint extract)
    actionHint: (ind as { actionHint?: { label: string; route?: string | null } | null }).actionHint ?? null,
  };
}

function initializePeriodFromRoute() {
  const start = typeof route.query.periodStart === 'string' ? route.query.periodStart : '';
  const end = typeof route.query.periodEnd === 'string' ? route.query.periodEnd : '';
  if (isValidPeriod([start, end])) {
    periodPreset.value = 'custom';
    customPeriodRange.value = [start, end];
  } else if (route.query.periodStart != null || route.query.periodEnd != null) {
    periodPreset.value = 'custom';
    customPeriodRange.value = null;
  }
}

initializePeriodFromRoute();
watch([factoryId, actorKey], () => { drawerOpen.value = false; selectedCode.value = null; });
watch([factoryId, actorKey, selectedPeriod], () => {
  requestSeq += 1;
  indicators.value = [];
  if (mounted.value) void loadList();
}, { deep: true });
onMounted(() => {
  mounted.value = true;
  loadList();
});
onDeactivated(() => { mounted.value = false; ++requestSeq; indicators.value = []; drawerOpen.value = false; });
onActivated(() => { if (!mounted.value) { mounted.value = true; if (!syncCalendarToday()) void loadList(); } });
onUnmounted(() => { mounted.value = false; requestSeq += 1; });
</script>

<style scoped>
.indicator-center {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* Sprint 11 D6 — Mock data banner */
.mock-banner {
  border-left: 4px solid var(--el-color-warning);
}
.mock-title {
  font-weight: 600;
  font-size: 14px;
}
.mock-detail {
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.title-block {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title-icon {
  color: var(--el-color-primary);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.page-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.summary-row {
  margin: 0 !important;
}

.summary-card {
  border: 1px solid var(--el-border-color-lighter);
}

.summary-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.summary-icon {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.icon-total { background: var(--el-color-primary); }
.icon-green { background: var(--el-color-success); }
.icon-yellow { background: var(--el-color-warning); }
.icon-red { background: var(--el-color-danger); }

.summary-text {
  flex: 1;
  min-width: 0;
}

.summary-num {
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.summary-num.text-success { color: var(--el-color-success); }
.summary-num.text-warning { color: var(--el-color-warning); }
.summary-num.text-danger { color: var(--el-color-danger); }

.summary-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.main-card {
  border: 1px solid var(--el-border-color-lighter);
}

.main-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.view-tabs {
  flex: 1;
  min-width: 200px;
}

.view-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.tab-label {
  display: flex;
  align-items: center;
  gap: 4px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.category-filter {
  width: 140px;
}

.search-input {
  width: 220px;
}

.list-view,
.tree-view {
  min-height: 320px;
}

.loading-block {
  padding: 16px;
}

.card-grid {
  margin: 0 !important;
}

.card-col {
  margin-bottom: 16px;
}

@media (max-width: 768px) {
  .indicator-center {
    padding: 12px;
  }
  .main-header {
    flex-direction: column;
    align-items: stretch;
  }
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .category-filter,
  .search-input {
    width: 100%;
  }
}
</style>
