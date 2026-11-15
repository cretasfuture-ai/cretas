<script setup lang="ts">
/**
 * 管理员专属 Dashboard
 * 适用角色: factory_super_admin
 * 特点: 全模块数据 + AI 分析入口 + 系统状态
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { DashboardOverview, ProductionStats, QualityStats, EquipmentStats } from '@/types/api';
import type { KPICard } from '@/types/smartbi';
import {
  TrendCharts, DataLine, Timer, Warning, Box, User, Money, Setting
} from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

const loading = ref(false);
const overview = ref<DashboardOverview | null>(null);
const productionStats = ref<ProductionStats | null>(null);
const qualityStats = ref<QualityStats | null>(null);
const equipmentStats = ref<EquipmentStats | null>(null);

const factoryId = computed(() => authStore.factoryId);

/**
 * 🔴 「还没问到」「问了读不到」「真的是 0」三种状态, 在这一屏上曾经是同一个长相。
 *
 * 旧实现每张卡都写 `overview.value?.todayOutput ?? 0`, 而 `overview` 在请求失败时
 * 保持 `null` ⇒ 四张卡照常印
 *   「今日产量 0 kg / 完成批次 0 个 / 设备运行 0 台 / 设备告警 0 条」,
 * 配色/单位/字号与真实读数**完全一致**。用户读到的是「今天一炉都没开」,
 * 真相是「一条都没查到」—— 两件事对厂长的含义完全相反。
 *
 * ⚠️ 同一个 `?? 0` 还得过另一种病: 端点**成功**但字段没投影上来
 *    (2026-08-19 F006 客户截图, 见 ProcessingServiceImpl 顶层投影那段注释),
 *    那时卡片同样恒为 0 且与数据无关。换成 `?? null` 之后, 那一类缺陷
 *    从「安静地印 0」变成「显式地印 —」, 下次会被看见。
 *
 * ⛔ 0 是**合法读数**, 不许一并改成 —— :
 *    端点成功且返回 0 时(今天确实没产量 / 确实零告警)必须照印 0。
 *    「设备告警 0 条」是厂长最想听到的一句话, 不能由一个失败的请求替我们说出来,
 *    但也不能因为怕说错就不说。
 */
type Reading = number | null;

/** 每个端点各自的成败 —— 一个挂了不许把别的卡一起打成失败。 */
const sourceFailed = ref({
  overview: false,
  production: false,
  quality: false,
  equipment: false,
});
/** 是否已经跑完一轮加载。⛔ 没跑完时任何数字都不是读数。 */
const dashboardLoaded = ref(false);

/**
 * 把「兜底 0」换成「没有读数」。
 *
 * ⚠️ `raw ?? null` 只吃 null/undefined —— `0` 会原样通过, 这正是我们要的:
 *    真读数 0 必须活下来, 假读数 0 必须消失。
 */
function readingOf(failed: boolean, raw: number | null | undefined): Reading {
  if (!dashboardLoaded.value) return null; // 还没问到
  if (failed) return null;                 // 问了读不到
  return raw ?? null;                      // 真读数 (0 合法)
}

/** 告警数由 overview / equipment 两个端点任一供给 ⇒ **两个都挂**才算读不到。 */
const alertsUnavailable = computed(
  () => sourceFailed.value.overview && sourceFailed.value.equipment,
);

// SmartBI Gold KPI 条 — 来自经营驾驶舱 executive 端点 (month 聚合), 展示真实经营数据。
// 对 demo 工厂等无今日运营数据的租户提供有意义的首屏内容。
const goldKpiCards = ref<KPICard[]>([]);
const goldKpiLoading = ref(false);
// 是否已有 gold KPI 真实数据 (rawValue > 0 视为有效)
const hasGoldKpi = computed(() =>
  goldKpiCards.value.some(c => (c.rawValue ?? 0) > 0)
);

// Bug 2 (2026-07 现场走查): 有 296 个产品/上百张凭证的真实租户仍每次登录看到
// "暂无数据"引导 — 之前的判定只看"今天"的运营 KPI(todayOutput/completedBatches 等),
// 不代表工厂"从未建过数据"。补一个轻量的真实数据存在性检查(产品档案总数),
// 与今日 KPI 信号并列, 任一命中即视为"已有数据"不再显示引导。
const hasProductData = ref(false);
const hasProductDataChecked = ref(false);
/**
 * 🔴 这个检查**失败**时, `hasProductData` 会停在初值 `false` ——
 * 而 `false` 在下面的判定里读作「这个工厂没有产品档案」, 也就是把
 * 「我没问到」翻译成了「答案是没有」。与四张卡的 `?? 0` 是同一个病。
 * ⇒ 单独记一个失败标志, 让「读不到」不再冒充「没有」。
 */
const hasProductDataFailed = ref(false);
async function checkHasProductData() {
  if (!factoryId.value) { hasProductDataChecked.value = true; return; }
  try {
    const res = await get<{ totalElements?: number }>(`/${factoryId.value}/product-types`, {
      params: { page: 1, size: 1 },
    });
    if (res.success) {
      hasProductData.value = (res.data?.totalElements ?? 0) > 0;
    } else {
      hasProductDataFailed.value = true;
    }
  } catch {
    // 静默失败(不弹 toast): 这一格挂了不该盖住主加载的错误提示。
    // ⛔ 但**不许**继续把它当成「无数据」—— 见 hasProductDataFailed 注释。
    hasProductDataFailed.value = true;
  } finally {
    hasProductDataChecked.value = true;
  }
}

// UX Round 5: 新工厂 onboarding — 所有 KPI 都为 0 且无 gold 数据且无真实产品数据时才显示快速开始引导
const isNewFactory = computed(() => {
  if (loading.value || goldKpiLoading.value) return false;
  if (!hasProductDataChecked.value) return false;
  // ⛔ 产品档案数没问到时不下判断 —— 「读不到」不是「没有产品」。
  if (hasProductDataFailed.value || hasProductData.value) return false;
  if (hasGoldKpi.value) return false;

  // 🔴 这里曾经是本页第二处、也是更糟的一处「失败当 0」:
  //    四个端点全挂 ⇒ 四个 `?? 0` 全部为 0 ⇒ 判定成立 ⇒
  //    一个有 296 个产品的老工厂被请去做「快速开始 4 步, 当前工厂暂无数据」。
  // ⇒ 任何一项**没有读数**就不下判断; 只有四项都是**真读数且都为 0** 才算新工厂。
  const readings: Reading[] = [
    readingOf(sourceFailed.value.overview, overview.value?.todayOutput),
    readingOf(sourceFailed.value.overview, overview.value?.completedBatches),
    readingOf(sourceFailed.value.equipment, equipmentStats.value?.running),
    readingOf(alertsUnavailable.value, equipmentStats.value?.activeAlerts),
  ];
  if (readings.some((v) => v === null)) return false;
  return readings.every((v) => v === 0);
});
const shouldShowOnboarding = computed(() => isNewFactory.value && !authStore.isLiushanmenFactory);

const onboardingSteps = [
 { title: '期初建账', desc: '录入期初库存/物料, 后续报工与成本从此起算(最重要的第一步)', route: '/warehouse/stocktakes?openEntry=1', icon: '' },
 { title: '上传 Excel 数据', desc: '财务/销售/采购报表导入 AI 分析', route: '/smart-bi/analysis', icon: '' },
 { title: '配置工厂模块', desc: '按业务启用/禁用功能模块', route: '/system/features', icon: '️' },
 { title: '添加登录账号', desc: '邀请团队成员登录协作', route: '/system/users', icon: '' },
];

// 统计卡片 - 管理员看全部
//
// 每张卡三态: value === null 时印 '—'(并按 unavailable 决定要不要说明「读取失败」),
// value 是数字时照印 —— 包括 0。见上方 readingOf 注释。
const statCards = computed(() => [
  {
    title: '今日产量',
    value: readingOf(sourceFailed.value.overview, overview.value?.todayOutput),
    unavailable: dashboardLoaded.value && sourceFailed.value.overview,
    unit: 'kg',
    icon: TrendCharts,
    color: '#2D8B57',
    route: '/production/batches'
  },
  {
    title: '完成批次',
    value: readingOf(sourceFailed.value.overview, overview.value?.completedBatches),
    unavailable: dashboardLoaded.value && sourceFailed.value.overview,
    unit: '个',
    icon: DataLine,
    color: '#36B37E',
    route: '/production/batches'
  },
  {
    title: '设备运行',
    value: readingOf(sourceFailed.value.equipment, equipmentStats.value?.running),
    unavailable: dashboardLoaded.value && sourceFailed.value.equipment,
    unit: '台',
    icon: Timer,
    color: '#FFAB00',
    route: '/equipment/list'
  },
  {
    title: '设备告警',
    // R42 BUG-14 + R76: dashboard/equipment doesn't have activeAlerts field;
    // dashboard/overview returns it nested at summary.activeAlerts.
    // 类型已升级到 DashboardOverview.summary?.activeAlerts (R76), 不再需要 `as any`.
    //
    // ⚠️ 这一格由**两个端点**任一供给, 所以它的「读不到」判据是两个都挂,
    //    ⛔ 不是任一挂 —— 否则 overview 挂了会把 equipment 已经读到的真实告警数抹掉。
    value: readingOf(
      alertsUnavailable.value,
      overview.value?.summary?.activeAlerts
        ?? overview.value?.alerts?.active
        ?? equipmentStats.value?.activeAlerts,
    ),
    unavailable: dashboardLoaded.value && alertsUnavailable.value,
    unit: '条',
    icon: Warning,
    color: '#FF5630',
    route: '/equipment/alerts'
  }
]);

// 快捷操作 - 管理员专属
const quickActions = [
  { title: '生产管理', icon: TrendCharts, route: '/production/batches', color: '#2D8B57' },
  { title: '仓储管理', icon: Box, route: '/warehouse/materials', color: '#36B37E' },
  { title: '人员管理', icon: User, route: '/hr/employees', color: '#FFAB00' },
  { title: '财务报表', icon: Money, route: '/finance/three-statements', color: '#FF5630' },
  { title: '系统设置', icon: Setting, route: '/system/settings', color: '#6B778C' },
  { title: '车间看板', icon: DataLine, route: '/analytics/production-report', color: '#6554C0' },
  { title: '人效分析', icon: User, route: '/production-analytics/efficiency', color: '#00B8D9' },
];

onMounted(async () => {
  // 并行加载制造业运营数据 + SmartBI Gold KPI + 真实产品数据存在性检查 (互不依赖)
  await Promise.all([loadDashboardData(), loadGoldKpi(), checkHasProductData()]);
});

// SmartBI Gold KPI: 调 executive 端点拿 kpiCards, 与经营驾驶舱同源。
// 静默失败 — gold 数据不可用时不影响制造业 KPI 展示。
async function loadGoldKpi() {
  if (!factoryId.value) return;
  goldKpiLoading.value = true;
  try {
    const res = await get<{ kpiCards?: KPICard[] }>(
      `/${factoryId.value}/smart-bi/dashboard/executive`,
      { params: { period: 'month' } }
    );
    if (res.success && res.data?.kpiCards?.length) {
      goldKpiCards.value = res.data.kpiCards;
    }
  } catch {
    // Gold 数据不可用: 静默, 不展示 strip
  } finally {
    goldKpiLoading.value = false;
  }
}

async function loadDashboardData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const [overviewRes, productionRes, qualityRes, equipmentRes] = await Promise.allSettled([
      get<DashboardOverview>(`/${factoryId.value}/reports/dashboard/overview`),
      get<ProductionStats>(`/${factoryId.value}/reports/dashboard/production?period=today`),
      get<QualityStats>(`/${factoryId.value}/reports/dashboard/quality`),
      get<EquipmentStats>(`/${factoryId.value}/reports/dashboard/equipment`)
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
      console.error('[DashboardAdmin] overview API failed',
        overviewRes.status === 'rejected' ? overviewRes.reason : overviewRes.value);
    }
    if (productionRes.status === 'fulfilled' && productionRes.value.success) {
      productionStats.value = productionRes.value.data;
      sourceFailed.value.production = false;
    } else {
      failed.push('生产');
      productionStats.value = null;
      sourceFailed.value.production = true;
      console.error('[DashboardAdmin] production API failed',
        productionRes.status === 'rejected' ? productionRes.reason : productionRes.value);
    }
    if (qualityRes.status === 'fulfilled' && qualityRes.value.success) {
      qualityStats.value = qualityRes.value.data;
      sourceFailed.value.quality = false;
    } else {
      failed.push('质量');
      qualityStats.value = null;
      sourceFailed.value.quality = true;
      console.error('[DashboardAdmin] quality API failed',
        qualityRes.status === 'rejected' ? qualityRes.reason : qualityRes.value);
    }
    if (equipmentRes.status === 'fulfilled' && equipmentRes.value.success) {
      equipmentStats.value = equipmentRes.value.data;
      sourceFailed.value.equipment = false;
    } else {
      failed.push('设备');
      equipmentStats.value = null;
      sourceFailed.value.equipment = true;
      console.error('[DashboardAdmin] equipment API failed',
        equipmentRes.status === 'rejected' ? equipmentRes.reason : equipmentRes.value);
    }

    if (failed.length === 4) {
      ElMessage.error('Dashboard 数据全部加载失败,请检查网络或联系管理员');
    } else if (failed.length > 0) {
      ElMessage.warning(`部分数据加载失败: ${failed.join('、')}`);
    }
  } catch (error) {
    // 兜底: allSettled 之外的意外(例如 factoryId 变更导致的同步抛错)。
    // ⛔ 同样不许让卡片停在「0」—— 四个来源一律标记为读不到。
    overview.value = null;
    productionStats.value = null;
    qualityStats.value = null;
    equipmentStats.value = null;
    sourceFailed.value = { overview: true, production: true, quality: true, equipment: true };
    console.error('[DashboardAdmin] Failed to load dashboard data:', error);
    ElMessage.error('加载 Dashboard 失败');
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
  <div class="dashboard-admin" v-loading="loading">
    <!-- 欢迎区 -->
    <div class="welcome-section">
      <div class="welcome-info">
        <h1>欢迎回来，{{ authStore.user?.fullName || authStore.user?.username }}</h1>
        <p>
          <el-tag type="danger" size="small">超级管理员</el-tag>
          <span class="factory-info">工厂: {{ factoryId }}</span>
        </p>
      </div>
      <div class="ai-entry">
        <el-button type="primary" @click="navigateTo('/finance/costs')">
          <el-icon><TrendCharts /></el-icon>
          AI 成本分析
        </el-button>
      </div>
    </div>

    <!-- SmartBI Gold KPI 条 — 展示本月经营核心指标 (来自 executive 端点, 与经营驾驶舱同源) -->
    <el-card
      v-if="hasGoldKpi || goldKpiLoading"
      class="gold-kpi-strip"
      shadow="never"
      v-loading="goldKpiLoading"
    >
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <span style="font-weight: 600; color: #1B65A8;">本月经营概览</span>
          <el-button
            link
            type="primary"
            size="small"
            @click="navigateTo('/smart-bi/dashboard')"
          >查看经营驾驶舱 →</el-button>
        </div>
      </template>
      <div class="gold-kpi-items">
        <div
          v-for="card in goldKpiCards.slice(0, 4)"
          :key="card.key"
          class="gold-kpi-item"
          @click="navigateTo('/smart-bi/dashboard')"
        >
          <span class="gold-kpi-label">{{ card.title }}</span>
          <span class="gold-kpi-value">
            {{ card.value || (card.rawValue != null ? card.rawValue.toLocaleString() : '-') }}
            <small v-if="card.unit">{{ card.unit }}</small>
          </span>
          <span
            v-if="card.trend && card.trend !== 'flat' && card.trend !== 'stable'"
            class="gold-kpi-trend"
            :class="card.trend === 'up' ? 'trend-up' : 'trend-down'"
          >{{ card.trend === 'up' ? '↑' : '↓' }}</span>
        </div>
      </div>
    </el-card>

    <!-- UX Round 5: 新工厂 onboarding 引导卡 (仅当所有 KPI=0 时显示) -->
    <el-card v-if="shouldShowOnboarding" class="onboarding-card" shadow="always">
      <div class="onboarding-header">
 <span class="onboarding-icon"></span>
        <div>
          <h3>快速开始 {{ onboardingSteps.length }} 步, 激活您的 AI 工厂</h3>
          <p>当前工厂暂无数据。跟着这 {{ onboardingSteps.length }} 步上手, 先建期初账再看 AI 分析效果.</p>
        </div>
      </div>
      <div class="onboarding-steps">
        <div v-for="(step, idx) in onboardingSteps" :key="step.route" class="onboarding-step" @click="navigateTo(step.route)">
          <div class="step-num">{{ idx + 1 }}</div>
          <div class="step-icon">{{ step.icon }}</div>
          <div class="step-body">
            <div class="step-title">{{ step.title }}</div>
            <div class="step-desc">{{ step.desc }}</div>
          </div>
          <el-icon class="step-arrow">→</el-icon>
        </div>
      </div>
    </el-card>

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
              <!-- 🔴 没有读数时印 '—' 而不是 0, 且连单位一起收掉:
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
        <span>快捷操作</span>
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

    <!-- 数据概览 -->
    <el-row :gutter="20" class="overview-section">
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <span>今日生产概览</span>
          </template>
          <div class="overview-content">
            <div class="overview-item">
              <span class="label">计划产量</span>
              <span class="value">{{ overview?.plannedOutput ?? '-' }} kg</span>
            </div>
            <div class="overview-item">
              <span class="label">实际产量</span>
              <span class="value">{{ overview?.todayOutput ?? '-' }} kg</span>
            </div>
            <div class="overview-item">
              <span class="label">完成率</span>
              <span class="value" :class="{ 'text-success': (overview?.completionRate ?? 0) >= 100 }">
                {{ overview?.completionRate ?? '-' }}%
              </span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card>
          <template #header>
            <span>质量统计</span>
          </template>
          <!--
            🔴 2026-08-19 F006 客户截图: 同一张卡上「今日检验 0 批」配「合格率 47%」,
               读起来自相矛盾 —— 0 批检验算不出 47%。

               真相是这三行**不是同一个时间范围**(后端 getQualityDashboard):
                 todayInspections = getQualityStatistics(factoryId, today, today)   ← 今日
                 passRate         = monthlyStats.averagePassRate                    ← 本月
                 failedBatches    = monthlyStats.failedBatches                      ← 本月
               而卡片上一个字都没说, 所以三行被读成同一个范围。

            ⇒ 把范围写进标签。⛔ 不改成全部取今日: 那样「今天没检验」时合格率只能是 0 或空,
               而本月合格率是店长真正要看的那个数 —— 问题从来不是取错了数, 是没说清是哪个范围。

            ⚠️ 根因是**字段名不带时间语义**(passRate / failedBatches 看不出是本月),
               与 APP-DATA-005 那次 activeBatches/totalBatches 是同一个病。
               彻底修法是后端给这类键带上范围后缀, 那会同时动到 App 端, 单独一轮做。
          -->
          <div class="overview-content">
            <div class="overview-item">
              <span class="label">今日检验</span>
              <span class="value">{{ qualityStats?.todayInspections ?? '-' }} 批</span>
            </div>
            <div class="overview-item">
              <span class="label">本月合格率</span>
              <span class="value text-success">{{ qualityStats?.passRate ?? '-' }}%</span>
            </div>
            <div class="overview-item">
              <span class="label">本月不合格批次</span>
              <span class="value" :class="{ 'text-danger': (qualityStats?.failedBatches ?? 0) > 0 }">
                {{ qualityStats?.failedBatches ?? '-' }} 批
              </span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
@use './dashboard-shared.scss';

.dashboard-admin {
  min-height: calc(100vh - 144px);
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

.overview-section {
  .overview-content {
    .overview-item {
      border-bottom-style: solid;
    }
  }
}

/* SmartBI Gold KPI 条 */
.gold-kpi-strip {
  margin-bottom: 20px;
  border-color: #d0e8ff;

  .gold-kpi-items {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 16px;

    @media (max-width: 900px) {
      grid-template-columns: repeat(2, 1fr);
    }
    @media (max-width: 480px) {
      grid-template-columns: 1fr;
    }
  }

  .gold-kpi-item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 12px 16px;
    background: #f5f9ff;
    border-radius: 8px;
    cursor: pointer;
    transition: background 0.15s;

    &:hover { background: #e8f4ff; }
  }

  .gold-kpi-label {
    font-size: 12px;
    color: #909399;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .gold-kpi-value {
    font-size: 20px;
    font-weight: 700;
    color: #1B65A8;
    small { font-size: 12px; font-weight: 400; margin-left: 2px; color: #606266; }
  }

  .gold-kpi-trend {
    font-size: 11px;
    font-weight: 600;
    &.trend-up { color: #36B37E; }
    &.trend-down { color: #FF5630; }
  }
}

/* UX Round 5: 新工厂 onboarding 卡片 */
.onboarding-card {
  margin-bottom: 20px;
  background: linear-gradient(135deg, #e8f4ff 0%, #f0f9ff 100%);
  border: 1px solid #1B65A8;

  .onboarding-header {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-bottom: 16px;

    .onboarding-icon { font-size: 36px; }
    h3 { margin: 0 0 4px; color: #1B65A8; }
    p { margin: 0; color: #606266; font-size: 13px; }
  }

  .onboarding-steps {
    display: grid;
    grid-template-columns: 1fr 1fr 1fr;
    gap: 12px;

    @media (max-width: 900px) {
      grid-template-columns: 1fr;
    }
  }

  .onboarding-step {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
    background: #fff;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s;
    border: 1px solid #e6ebf2;

    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(27, 101, 168, 0.15);
      border-color: #1B65A8;

      .step-arrow { color: #1B65A8; transform: translateX(4px); }
    }

    .step-num {
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: #1B65A8;
      color: #fff;
      font-size: 12px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }
    .step-icon { font-size: 22px; }
    .step-body {
      flex: 1;
      min-width: 0;
    }
    .step-title { font-size: 14px; font-weight: 600; color: #303133; }
    .step-desc { font-size: 12px; color: #909399; margin-top: 2px; }
    .step-arrow { color: #c0c4cc; transition: all 0.2s; }
  }
}
</style>
