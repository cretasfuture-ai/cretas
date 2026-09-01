<script setup lang="ts">
/**
 * 生产管理 Dashboard
 * 适用角色: production_manager, workshop_supervisor
 * 特点: 生产数据为主、批次管理、计划执行
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { DashboardOverview, ProductionStats } from '@/types/api';
import { TrendCharts, DataLine, Timer, Calendar, Document } from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const overview = ref<DashboardOverview | null>(null);
const productionStats = ref<ProductionStats | null>(null);

const factoryId = computed(() => authStore.factoryId);

/**
 * 🔴 「进行中批次」曾经读 `productionStats.value?.inProgressBatches ?? 0`。
 * `/reports/dashboard/production?period=today` 的响应里**根本没有这个字段**
 * (见 ProcessingServiceImpl.getProductionStatistics —— 只 put totalBatches /
 * completedBatches / totalOutput / totalCost / averageEfficiency /
 * batchStatusDistribution / productTypeStats), 于是 `undefined ?? 0` 恒为 0。
 * LIUSHANMEN 实测: 一个 08-29 建的批次跑了 3 天仍在 IN_PROGRESS, 卡片却印「0」。
 *
 * 语义订正 (不只是换个字段名): 「进行中」对生产经理的含义是「当前还没结束」,
 * 不是「今天开始的」——一个跑了 3 天的批次今天看仍然是「进行中」。这意味着
 * **不管用哪个字段**, 只要数据源是 `period=today` 限定的, 语义就是错的
 * (哪怕补一个 today 口径的 inProgressBatches, 3 天前开的批次依然算不进去)。
 * `/reports/dashboard/overview` 的 `inProgressBatches` 字段 (=IN_PROGRESS 状态的
 * 全量历史计数, 无日期过滤查询) 才是正确语义, 而且**已经在页面上**(overview
 * 请求本来就要发)。改用它是纯前端修复, 不需要后端改动。
 *
 * 「没有读数」三态(还没问到 / 问了读不到 / 真的是 0)—— 见
 * dashboardAdminHonestZero.spec.ts 建立的模式, 这里照搬到本组件。
 */
type Reading = number | null;

/** 每个端点各自的成败 —— 一个挂了不许把别的卡一起打成失败。 */
const sourceFailed = ref({ overview: false, production: false });
/** 是否已经跑完一轮加载。⛔ 没跑完时任何数字都不是读数。 */
const dashboardLoaded = ref(false);

/** 把「兜底 0」换成「没有读数」。`raw ?? null` 只吃 null/undefined —— 真读数 0 会原样通过。 */
function readingOf(failed: boolean, raw: number | null | undefined): Reading {
  if (!dashboardLoaded.value) return null; // 还没问到
  if (failed) return null;                 // 问了读不到
  return raw ?? null;                      // 真读数(0 合法); 字段本身缺失也是「没有读数」
}

// 统计卡片
//
// 每张卡三态: value === null 时印 '—'(unavailable 决定要不要说明「读取失败」),
// value 是数字时照印 —— 包括 0。见上方 readingOf 注释。
//
// ⚠️ DELIBERATELY_NOT_FIXED ——「完成批次」「完成率」两张卡刻意保留 `?? 0`
// 不升级三态: 「完成批次」的今日口径本身没问题(今天 0 完成是真读数, 不是缺陷,
// 只是与相邻「今日产量」相比缺一个口径标签, 属于另一个已知的、明确排除在本 PR
// 之外的 copy 问题)。「完成率」这个字段在 /reports/dashboard/overview 里
// **从未被后端赋值过**(不是今天恰好是 0, 是这个 key 永远不存在, 与「进行中批次」
// 曾经的问题同形) —— 但升级三态会让这张卡在**每一次成功加载**时都变成不同的
// 显示(0% → —), 那是一次可见的行为变化, 超出本次「进行中批次 / 批次状态分布」
// 的修复范围, 已在 PR 描述里单独报告, 不在本次改动。
const statCards = computed(() => [
  {
    title: '今日产量',
    value: readingOf(sourceFailed.value.overview, overview.value?.todayOutput),
    unavailable: dashboardLoaded.value && sourceFailed.value.overview,
    unit: 'kg',
    icon: TrendCharts,
    color: '#409eff',
    route: '/production/batches'
  },
  {
    title: '完成批次',
    value: overview.value?.completedBatches ?? 0,
    unit: '个',
    icon: DataLine,
    color: '#67c23a',
    route: '/production/batches'
  },
  {
    title: '进行中批次',
    value: readingOf(sourceFailed.value.overview, overview.value?.inProgressBatches),
    unavailable: dashboardLoaded.value && sourceFailed.value.overview,
    unit: '个',
    icon: Timer,
    color: '#e6a23c',
    route: '/production/batches'
  },
  {
    title: '完成率',
    value: overview.value?.completionRate ?? 0,
    unit: '%',
    icon: Document,
    color: '#909399',
    route: '/production/plans'
  }
]);

// 「批次状态分布」——「已完成」沿用 KPI 卡同一个今日口径字段(不在本 PR 改动语义,
// 理由同上); 「进行中」与 KPI 卡的「进行中批次」同源同修(全量历史, 见上方大注释);
// 「待开始」的 productionStats.pendingBatches 与 completionRate 一样, 后端从未
// 赋值过 —— 但这个字段**在 Defect② 里被明确点名要修**(与 completionRate 不同,
// 后者是本 PR 明确排除的 item③), 所以这里升级成三态, 让恒假的 0 变成诚实的「—」。
const batchStatusItems = computed(() => [
  {
    label: '已完成',
    tagType: 'success' as const,
    value: overview.value?.completedBatches ?? 0,
  },
  {
    label: '进行中',
    tagType: 'warning' as const,
    value: readingOf(sourceFailed.value.overview, overview.value?.inProgressBatches),
    unavailable: dashboardLoaded.value && sourceFailed.value.overview,
  },
  {
    label: '待开始',
    tagType: 'info' as const,
    value: readingOf(sourceFailed.value.production, productionStats.value?.pendingBatches),
    unavailable: dashboardLoaded.value && sourceFailed.value.production,
  },
]);

// 快捷操作
// T125: 转换率配置入口已移除 (后端 API/fallback 仍保留, 维护路径: BOM成本管理 → 转换率 tab)
const quickActions = [
  { title: '生产批次', icon: DataLine, route: '/production/batches', color: '#409eff' },
  { title: '生产计划', icon: Calendar, route: '/production/plans', color: '#67c23a' }
];

onMounted(async () => {
  await loadProductionData();
});

async function loadProductionData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const [overviewRes, productionRes] = await Promise.allSettled([
      get<DashboardOverview>(`/${factoryId.value}/reports/dashboard/overview`),
      get<ProductionStats>(`/${factoryId.value}/reports/dashboard/production?period=today`)
    ]);

    const failed: string[] = [];

    if (overviewRes.status === 'fulfilled' && overviewRes.value.success) {
      overview.value = overviewRes.value.data;
      sourceFailed.value.overview = false;
    } else {
      failed.push('概览');
      // ⛔ 失败时把上一轮的数据清掉: 留着旧值会让「读不到」冒充「刚读到的」。
      overview.value = null;
      sourceFailed.value.overview = true;
      console.error('[DashboardProduction] overview API failed',
        overviewRes.status === 'rejected' ? overviewRes.reason : overviewRes.value);
    }
    if (productionRes.status === 'fulfilled' && productionRes.value.success) {
      productionStats.value = productionRes.value.data;
      sourceFailed.value.production = false;
    } else {
      failed.push('生产统计');
      productionStats.value = null;
      sourceFailed.value.production = true;
      console.error('[DashboardProduction] production API failed',
        productionRes.status === 'rejected' ? productionRes.reason : productionRes.value);
    }

    if (failed.length === 2) {
      ElMessage.error('Dashboard 数据全部加载失败');
    } else if (failed.length > 0) {
      ElMessage.warning(`部分数据加载失败: ${failed.join('、')}`);
    }
  } catch (error) {
    // 兜底: allSettled 之外的意外(例如 factoryId 变更导致的同步抛错)。
    // ⛔ 同样不许让卡片停在「0」—— 两个来源一律标记为读不到。
    overview.value = null;
    productionStats.value = null;
    sourceFailed.value = { overview: true, production: true };
    console.error('[DashboardProduction] Failed to load production data:', error);
    ElMessage.error('加载生产 Dashboard 失败');
  } finally {
    loading.value = false;
    dashboardLoaded.value = true;
  }
}

function navigateTo(route: string) {
  router.push(route);
}
</script>

<template>
  <div class="dashboard-production" v-loading="loading">
    <!-- 欢迎区 -->
    <div class="welcome-section">
      <div class="welcome-info">
        <h1>欢迎回来，{{ authStore.user?.fullName || authStore.user?.username }}</h1>
        <p>
          <el-tag type="success" size="small">{{ authStore.roleMetadata?.displayName }}</el-tag>
          <span class="factory-info">工厂: {{ factoryId }}</span>
        </p>
      </div>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stat-cards">
      <el-col v-for="card in statCards" :key="card.title" :xs="24" :sm="12" :md="6">
        <el-card
          class="stat-card"
          :class="{ 'is-unavailable': card.unavailable }"
          :data-stat="card.title"
          shadow="hover"
          @click="navigateTo(card.route)"
        >
          <div class="stat-content">
            <div class="stat-info">
              <span class="stat-title">{{ card.title }}</span>
              <!-- 没有读数时印 '—' 而不是 0, 且连单位一起收掉:
                   「— kg」读起来仍像一个产量, 而我们并没有产量可说。 -->
              <span
                class="stat-value"
                :style="{ color: card.value === null ? '#C0C4CC' : card.color }"
              >
                {{ card.value === null ? '—' : card.value }}
                <small v-if="card.value !== null">{{ card.unit }}</small>
              </span>
              <!-- 失败态与「还没问到」分开: 只有真的问过并且读不到才说这句。 -->
              <span v-if="card.unavailable" class="stat-unavailable">读取失败</span>
            </div>
            <el-icon class="stat-icon" :style="{ backgroundColor: card.color + '20', color: card.color }">
              <component :is="card.icon" />
            </el-icon>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷操作 -->
    <el-card class="quick-actions-card">
      <template #header>
        <span>生产管理</span>
      </template>
      <div class="quick-actions">
        <div
          v-for="action in quickActions"
          :key="action.title"
          class="action-item"
          @click="navigateTo(action.route)"
        >
          <el-icon :size="32" :style="{ color: action.color }">
            <component :is="action.icon" />
          </el-icon>
          <span>{{ action.title }}</span>
        </div>
      </div>
    </el-card>

    <!-- 生产概览 -->
    <el-row :gutter="20">
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>今日生产目标</span>
              <el-button type="primary" link @click="navigateTo('/production/plans')">
                查看计划
              </el-button>
            </div>
          </template>
          <div class="target-progress">
            <div class="progress-info">
              <span>计划: {{ overview?.plannedOutput ?? 0 }} kg</span>
              <span>实际: {{ overview?.todayOutput ?? 0 }} kg</span>
            </div>
            <el-progress
              :percentage="overview?.completionRate ?? 0"
              :color="(overview?.completionRate ?? 0) >= 100 ? '#67c23a' : '#409eff'"
              :stroke-width="20"
            />
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>批次状态分布</span>
              <el-button type="primary" link @click="navigateTo('/production/batches')">
                查看批次
              </el-button>
            </div>
          </template>
          <div class="batch-status">
            <div
              v-for="item in batchStatusItems"
              :key="item.label"
              class="status-item"
              :data-stat="item.label"
            >
              <el-tag :type="item.tagType">{{ item.label }}</el-tag>
              <span class="count" :class="{ 'count-unavailable': item.value === null }">
                {{ item.value === null ? '—' : item.value }}
              </span>
              <span v-if="item.unavailable" class="stat-unavailable">读取失败</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
.dashboard-production {
  min-height: calc(100vh - 144px);
}

.welcome-section {
  margin-bottom: 24px;

  h1 {
    font-size: 24px;
    color: #333;
    margin: 0 0 8px;
  }

  p {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 0;
    color: #999;
    font-size: 14px;
  }
}

.stat-cards {
  margin-bottom: 24px;

  .stat-card {
    margin-bottom: 20px;
    cursor: pointer;
    transition: transform 0.2s;

    &:hover {
      transform: translateY(-2px);
    }
  }

  .stat-content {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .stat-info {
    display: flex;
    flex-direction: column;
  }

  .stat-title {
    font-size: 14px;
    color: #999;
    margin-bottom: 8px;
  }

  .stat-value {
    font-size: 28px;
    font-weight: 600;

    small {
      font-size: 14px;
      font-weight: 400;
      margin-left: 4px;
    }
  }

  .stat-icon {
    width: 48px;
    height: 48px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 24px;
  }
}

/* 读不到的卡片: 视觉上必须与「读到 0」区分开, 否则三态在界面上又并回两态。 */
.stat-card.is-unavailable {
  border-style: dashed;
  border-color: #dcdfe6;
}

.stat-unavailable {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.quick-actions-card {
  margin-bottom: 24px;

  .quick-actions {
    display: flex;
    gap: 24px;
    flex-wrap: wrap;
  }

  .action-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 16px 24px;
    border-radius: 8px;
    cursor: pointer;
    transition: background-color 0.2s;

    &:hover {
      background-color: #f5f7fa;
    }

    span {
      font-size: 14px;
      color: #606266;
    }
  }
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.target-progress {
  padding: 20px 0;

  .progress-info {
    display: flex;
    justify-content: space-between;
    margin-bottom: 16px;
    font-size: 14px;
    color: #606266;
  }
}

.batch-status {
  display: flex;
  justify-content: space-around;
  padding: 20px 0;

  .status-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;

    .count {
      font-size: 24px;
      font-weight: 600;
      color: #303133;
    }

    .count-unavailable {
      color: #c0c4cc;
    }
  }
}

.el-card {
  margin-bottom: 20px;
}
</style>
