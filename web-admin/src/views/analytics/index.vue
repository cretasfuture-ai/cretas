<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { TrendCharts, DataAnalysis, Histogram, PieChart, Timer, Sunny, Goods, User } from '@element-plus/icons-vue';
import { formatNumber } from '@/utils/format-number';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
// 餐饮租户不走制造业概览
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT');

const loading = ref(false);

/**
 * 🔴 「还没问到」「问了读不到」「真的是 0」三种状态在这一页上是同一个长相。
 *
 * 所有初值都是 0, 而两条加载路径的 catch 都只 console.error / 弹个 toast,
 * **从不重置数据、从不置失败标志** ⇒ 模板继续渲染那批初始 0。
 * 后端全挂时页面照印「领料 0 单 / 损耗 ¥0 / 今日产量 0 / 合格率 0.0%」,
 * 配色单位字号与真实读数完全一致 —— 用户读到的是「这个月什么都没干」。
 *
 * ⛔ 0 是**合法读数**: 端点成功且返回 0 时必须照印 0
 *    (「损耗 ¥0」「低库存 0」是好消息, 不许被改成 —— )。
 *    要消灭的只有**假读数 0**。
 *
 * 判据统一成: 有没有读数 → 没有就印 '—'; 有就照印, 包括 0。
 */
type Reading = number | null;

/** `raw ?? null` 只吃 null/undefined ⇒ 真读数 0 原样通过, 这正是要的。 */
function readingOf(loaded: boolean, failed: boolean, raw: number | null | undefined): Reading {
  if (!loaded) return null; // 还没问到
  if (failed) return null;  // 问了读不到
  return raw ?? null;       // 真读数 (0 合法)
}

/** 没有读数时印 '—'; 有读数才走 formatNumber。⛔ 失败绝不落成 '0'。 */
function readingText(v: Reading, digits = 0, prefix = ''): string {
  if (v === null) return '—';
  return prefix + formatNumber(v, digits);
}

// 概览数据
//
// ⚠️ `sales` / `finance` 这两组**没有任何赋值点** —— 全文搜 `overviewData.value.sales`
//    / `.finance` 只出现在模板里。loadOverviewData 只打四个端点
//    (production / quality / overview / equipment), 后端 overview 的返回里
//    也没有出货/成本字段(见 ProcessingServiceImpl 的 overview 构造)。
//    ⇒ 「出货记录 0 / 待发货 0 / 今日成本 0 / 平均 0」是**永久**的初值,
//      与任何数据无关, 也与加载成败无关 —— 属于三态里的「从未加载」。
//    ⇒ 下面用 factorySourceless 把这两张卡钉成「无数据来源」, ⛔ 不再印 0。
const overviewData = ref({
  production: { todayOutput: 0, completedBatches: 0, inProgressBatches: 0, efficiency: 0 },
  quality: { totalInspections: 0, passRate: 0, pendingCount: 0 },
  warehouse: { totalMaterialBatches: 0, lowStockCount: 0, totalQuantity: 0 },
  equipment: { totalEquipments: 0, alertCount: 0, maintenanceCount: 0 },
  sales: { totalShipments: 0, pendingShipments: 0 },
  finance: { todayCost: 0, averageCost: 0 }
});

/** 工厂侧: 已经跑完一轮加载。 */
const factoryLoaded = ref(false);
/** 工厂侧: 每个端点各自的成败 —— 一个挂了不许把别的卡一起打成失败。 */
const factoryFailed = ref({
  production: false,
  quality: false,
  warehouse: false,
  equipment: false,
});

/** 工厂侧各卡的读数入口。 */
function factoryReading(source: keyof typeof factoryFailed.value, raw: number | null | undefined): Reading {
  return readingOf(factoryLoaded.value, factoryFailed.value[source], raw);
}

// 趋势数据
//
// ⚠️ 2026-08-26 核查: `trendData` **在模板里一次都没有被渲染**(全文只有这里的
//    声明和 loadTrendData 里的赋值两处)。所以它不属于「失败却照样印 0」——
//    它根本没有印任何东西。loadTrendData 目前的净效果是: 发一个请求、
//    失败时弹个 toast、把结果存进一个没人读的 ref。
//    ⇒ 这一轮**不给它加三态 UI**(没有 UI 可加); 只在此登记, 由后续单独决定
//      是接上图表还是把这条请求删掉。⛔ 不要凭「同族」给它编一个修复。
const trendData = ref({
  productionTrend: [] as { date: string; value: number }[],
  qualityTrend: [] as { date: string; value: number }[],
  costTrend: [] as { date: string; value: number }[]
});

onMounted(() => {
  // 餐饮租户没有可用的概览数据源, 不发制造业请求 (全是 0 的无用告警)。
  if (!isRestaurant.value) {
    loadOverviewData();
    loadTrendData();
  }
});

async function loadOverviewData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // 加载各模块概览
    const [prodRes, qualRes, warehouseRes, equipRes] = await Promise.allSettled([
      get(`/${factoryId.value}/reports/dashboard/production`, { params: { period: 'today' } }),
      get(`/${factoryId.value}/reports/dashboard/quality`),
      get(`/${factoryId.value}/reports/dashboard/overview`),
      get(`/${factoryId.value}/reports/dashboard/equipment`)
    ]);

    const failed: string[] = [];
    if (prodRes.status === 'fulfilled' && prodRes.value.success) {
      overviewData.value.production = {
        ...overviewData.value.production,
        ...prodRes.value.data
      };
      factoryFailed.value.production = false;
    } else { failed.push('生产'); factoryFailed.value.production = true; }
    if (qualRes.status === 'fulfilled' && qualRes.value.success) {
      overviewData.value.quality = {
        ...overviewData.value.quality,
        ...qualRes.value.data
      };
      factoryFailed.value.quality = false;
    } else { failed.push('质量'); factoryFailed.value.quality = true; }
    if (warehouseRes.status === 'fulfilled' && warehouseRes.value.success) {
      const data = warehouseRes.value.data || {};
      // ⚠️ 这里保留 `|| 0`: 端点**成功**时字段缺失按 0 记是既有契约,
      //    与「端点失败」是两件事 —— 后者已经由 factoryFailed 接管。
      overviewData.value.warehouse.totalMaterialBatches = data.materialBatches || 0;
      overviewData.value.warehouse.lowStockCount = data.lowStockMaterials || 0;
      factoryFailed.value.warehouse = false;
    } else { failed.push('仓储'); factoryFailed.value.warehouse = true; }
    if (equipRes.status === 'fulfilled' && equipRes.value.success) {
      overviewData.value.equipment = {
        ...overviewData.value.equipment,
        ...equipRes.value.data
      };
      factoryFailed.value.equipment = false;
    } else { failed.push('设备'); factoryFailed.value.equipment = true; }
    // R18-ext #282 fix: when some/all endpoints fail, surface the failure
    // instead of silently showing zeros (prev behavior made zeros look like
    // real data).
    //
    // ⚠️ 那一版只加了 toast, 卡片仍然印 0 —— 于是提示说「可能为占位 0」,
    //    而用户没法从界面上分辨哪一张是占位。现在失败的卡片直接印 '—',
    //    措辞也跟着改: 不再说「可能为占位 0」。
    if (failed.length > 0) {
      ElMessage.warning(`概览数据部分加载失败: ${failed.join('、')}. 对应指标已置为「—」, 请刷新重试`);
    }
  } catch (error) {
    // ⛔ 兜底路径同样不许让卡片停在 0。
    factoryFailed.value = { production: true, quality: true, warehouse: true, equipment: true };
    console.error('加载概览数据失败:', error);
    ElMessage.error('加载概览数据失败，请稍后重试');
  } finally {
    loading.value = false;
    factoryLoaded.value = true;
  }
}

async function loadTrendData() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/reports/dashboard/trends`, {
      params: { period: 'week' }
    });
    if (response.success && response.data) {
      trendData.value = response.data;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载趋势数据失败');
    }
  } catch (error) {
    console.error('加载趋势数据失败:', error);
    ElMessage.error('加载趋势数据失败，请稍后重试');
  }
}

// formatNumber imported from @/utils/format-number

// R18-ext #284 fix: backend passRate is already a percentage value
// (e.g. 68.33 for 68.33%), not a decimal (0.6833). Multiplying by 100
// produced "6833.0%" in the UI.
//
// ⚠️ 这个函数原本 `num == null` 时返回 '0.0%' —— 同一个病的第三处:
//    「没有合格率可报」被翻译成「合格率是 0.0%」, 而 0% 合格率是灾难级读数。
//    现在没有读数就印 '—'; 真读数 0 仍然印 '0.0%'(端点成功且确实是 0)。
function formatPercent(num: Reading): string {
  if (num === null || isNaN(Number(num))) return '—';
  return Number(num).toFixed(1) + '%';
}
</script>

<template>
  <div class="analytics-page">
    <div class="page-header">
      <h1>
        <el-icon><DataAnalysis /></el-icon>
        数据分析中心
      </h1>
      <p class="subtitle">{{ isRestaurant ? '餐饮租户暂无可用概览数据' : '全局数据概览与趋势分析' }}</p>
    </div>

    <!-- 餐饮租户: 原 /api/smartbi/restaurant-ops/summary 后端从未提供 (2026-11-18 核实), 删除死调用与其展示。
         明确告知不可用, ⛔ 不回落到制造业卡片(会全是 0), 也不造数据。 -->
    <el-alert v-if="isRestaurant" type="info" :closable="false" show-icon title="餐饮运营概览暂不可用">
      <div style="font-size:13px">
        该概览的数据接口尚未提供。餐饮日常分析请使用侧边栏「AI 问答」或「智能数据分析」。
      </div>
    </el-alert>

    <!-- Factory tenant: manufacturing overview (original flow) -->
    <el-row v-else :gutter="16" class="overview-cards" v-loading="loading">
      <!-- 生产模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card
          class="stat-card production"
          :class="{ 'is-unavailable': factoryLoaded && factoryFailed.production }"
          data-stat="生产"
          shadow="hover"
        >
          <div class="card-header">
            <el-icon class="module-icon"><TrendCharts /></el-icon>
            <span>生产</span>
          </div>
          <div class="stat-value">{{ readingText(factoryReading('production', overviewData.production.todayOutput)) }}</div>
          <div class="stat-label">今日产量</div>
          <div class="stat-footer">
            <span>完成批次: {{ readingText(factoryReading('production', overviewData.production.completedBatches)) }}</span>
          </div>
          <div v-if="factoryLoaded && factoryFailed.production" class="stat-unavailable">读取失败</div>
        </el-card>
      </el-col>

      <!-- 质量模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card
          class="stat-card quality"
          :class="{ 'is-unavailable': factoryLoaded && factoryFailed.quality }"
          data-stat="质量"
          shadow="hover"
        >
          <div class="card-header">
            <el-icon class="module-icon"><Sunny /></el-icon>
            <span>质量</span>
          </div>
          <div class="stat-value">{{ formatPercent(factoryReading('quality', overviewData.quality.passRate)) }}</div>
          <div class="stat-label">合格率</div>
          <div class="stat-footer">
            <span>待检: {{ readingText(factoryReading('quality', overviewData.quality.pendingCount)) }}</span>
          </div>
          <div v-if="factoryLoaded && factoryFailed.quality" class="stat-unavailable">读取失败</div>
        </el-card>
      </el-col>

      <!-- 仓储模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card
          class="stat-card warehouse"
          :class="{ 'is-unavailable': factoryLoaded && factoryFailed.warehouse }"
          data-stat="仓储"
          shadow="hover"
        >
          <div class="card-header">
            <el-icon class="module-icon"><Histogram /></el-icon>
            <span>仓储</span>
          </div>
          <div class="stat-value">{{ readingText(factoryReading('warehouse', overviewData.warehouse.totalMaterialBatches)) }}</div>
          <div class="stat-label">原料批次</div>
          <div class="stat-footer">
            <!-- ⚠️ 告警底色只在**有读数且 > 0** 时点亮 —— 读不到不是「不缺货」。 -->
            <span :class="{ 'text-warning': !factoryFailed.warehouse && overviewData.warehouse.lowStockCount > 0 }">
              低库存: {{ readingText(factoryReading('warehouse', overviewData.warehouse.lowStockCount)) }}
            </span>
          </div>
          <div v-if="factoryLoaded && factoryFailed.warehouse" class="stat-unavailable">读取失败</div>
        </el-card>
      </el-col>

      <!-- 设备模块 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card
          class="stat-card equipment"
          :class="{ 'is-unavailable': factoryLoaded && factoryFailed.equipment }"
          data-stat="设备"
          shadow="hover"
        >
          <div class="card-header">
            <el-icon class="module-icon"><Timer /></el-icon>
            <span>设备</span>
          </div>
          <div class="stat-value">{{ readingText(factoryReading('equipment', overviewData.equipment.totalEquipments)) }}</div>
          <div class="stat-label">设备总数</div>
          <div class="stat-footer">
            <span :class="{ 'text-danger': !factoryFailed.equipment && overviewData.equipment.alertCount > 0 }">
              告警: {{ readingText(factoryReading('equipment', overviewData.equipment.alertCount)) }}
            </span>
          </div>
          <div v-if="factoryLoaded && factoryFailed.equipment" class="stat-unavailable">读取失败</div>
        </el-card>
      </el-col>

      <!-- 销售模块 —— ⚠️ 这张卡**没有数据来源**(见 overviewData 上方注释):
           `overviewData.sales` 全文没有任何赋值点, 它印的永远是初值。
           ⛔ 所以它不许印 0; 印 '—' 并说明「未接入」。 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card class="stat-card sales is-sourceless" data-stat="销售" shadow="hover">
          <div class="card-header">
            <el-icon class="module-icon"><PieChart /></el-icon>
            <span>销售</span>
          </div>
          <div class="stat-value">—</div>
          <div class="stat-label">出货记录</div>
          <div class="stat-footer">
            <span>待发货: —</span>
          </div>
          <div class="stat-unavailable">销售数据请到「经营分析 › 销售」查看 <router-link class="stat-link" to="/smart-bi/analysis-hub?tab=sales">前往查看</router-link></div>
        </el-card>
      </el-col>

      <!-- 财务模块 —— 同上, `overviewData.finance` 同样没有赋值点。 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="4">
        <el-card class="stat-card finance is-sourceless" data-stat="成本" shadow="hover">
          <div class="card-header">
            <el-icon class="module-icon"><DataAnalysis /></el-icon>
            <span>成本</span>
          </div>
          <div class="stat-value">—</div>
          <div class="stat-label">今日成本</div>
          <div class="stat-footer">
            <span>平均: —</span>
          </div>
          <div class="stat-unavailable">成本数据请到「成本分析」查看 <router-link class="stat-link" to="/production-analytics/cost">前往查看</router-link></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷导航 -->
    <el-row :gutter="16" class="quick-nav">
      <el-col :span="8">
        <router-link to="/analytics/trends" class="nav-card">
          <el-card shadow="hover">
            <el-icon size="32"><TrendCharts /></el-icon>
            <h3>趋势分析</h3>
            <p>查看各模块历史趋势和数据变化</p>
          </el-card>
        </router-link>
      </el-col>
      <el-col :span="8">
        <router-link to="/analytics/kpi" class="nav-card">
          <el-card shadow="hover">
            <el-icon size="32"><Histogram /></el-icon>
            <h3>KPI看板</h3>
            <p>关键绩效指标实时监控</p>
          </el-card>
        </router-link>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
.analytics-page {
  padding: 20px;
}

.page-header {
  margin-bottom: 24px;

  h1 {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 24px;
    font-weight: 600;
    color: #303133;
    margin: 0 0 8px;
  }

  .subtitle {
    color: #909399;
    font-size: 14px;
    margin: 0;
  }
}

.overview-cards {
  margin-bottom: 24px;
}

.ops-failed-alert {
  margin-bottom: 16px;
}

/* 「读不到」和「读到 0」在视觉上必须分得开, 否则三态在界面上又并回两态。 */
.stat-card.is-unavailable,
.stat-card.is-sourceless {
  border-style: dashed;

  .stat-value {
    color: #c0c4cc !important;
  }
}

.stat-unavailable {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}

.stat-unavailable .stat-link {
  margin-left: 4px;
  color: var(--cs-primary);
  text-decoration: none;
}

.stat-card {
  text-align: center;
  margin-bottom: 16px;
  border-radius: 12px;
  transition: all var(--transition-fast, 0.2s ease);

  &:hover {
    transform: translateY(-4px);
  }

  .card-header {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    margin-bottom: 12px;
    color: #606266;
    font-size: 14px;
  }

  .module-icon {
    font-size: 18px;
  }

  .stat-value {
    font-size: 28px;
    font-weight: 700;
    color: #303133;
    line-height: 1.2;
    font-variant-numeric: tabular-nums;
  }

  .stat-label {
    font-size: 12px;
    color: #909399;
    margin-top: 4px;
  }

  .stat-footer {
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px solid #ebeef5;
    font-size: 12px;
    color: #606266;
  }

  &.production {
    border-top: 3px solid var(--cs-primary);
    .stat-value { color: var(--cs-primary); }
  }

  &.quality {
    border-top: 3px solid #67C23A;
    .stat-value { color: #67C23A; }
  }

  &.warehouse {
    border-top: 3px solid #E6A23C;
    .stat-value { color: #E6A23C; }
  }

  &.equipment {
    border-top: 3px solid #909399;
    .stat-value { color: #909399; }
  }

  &.sales {
    border-top: 3px solid #9B59B6;
    .stat-value { color: #9B59B6; }
  }

  &.finance {
    border-top: 3px solid #E74C3C;
    .stat-value { color: #E74C3C; }
  }
}

.text-warning {
  color: #E6A23C !important;
}

.text-danger {
  color: #F56C6C !important;
}

.quick-nav {
  margin-top: 24px;

  .nav-card {
    text-decoration: none;
    display: block;

    .el-card {
      text-align: center;
      padding: 24px;
      cursor: pointer;
      transition: all var(--transition-base, 0.3s ease);
      border-radius: 12px;

      &:hover {
        transform: translateY(-4px);
        box-shadow: var(--shadow-xl);
      }

      .el-icon {
        color: var(--cs-primary);
        margin-bottom: 12px;
      }

      h3 {
        margin: 0 0 8px;
        font-size: 16px;
        color: #303133;
      }

      p {
        margin: 0;
        font-size: 13px;
        color: #909399;
      }
    }
  }
}
</style>
