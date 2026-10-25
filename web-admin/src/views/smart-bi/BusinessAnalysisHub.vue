<script setup lang="ts">
/**
 * BusinessAnalysisHub — 经营分析 (WS4)
 *
 * 把散落的 财务看板 / 销售分析 / 趋势分析 / KPI看板 / 指标中心 合并成单一 tab 化模块。
 * 一级 el-tabs: 财务 / 销售 / 趋势 / KPI·指标 (内层再分 KPI看板 / 指标中心)。
 *
 * - ?tab= query 同步 (保书签 + 旧路径 redirect 落点, 参考 financeDashboardSection 的
 *   ?section= 同步模式)。
 * - 此 hub 只做容器编排 + 顶部概览卡, 不改子视图逻辑。
 *
 * ============================================================
 * 混合式图表分析 (2026-07-12) — 与 CRM 会员分析/运营分析/平台分析 同一套
 * pattern (确定性 bullets + ChartInsights 按需 AI 解读 + 页面级 AnalysisChatPanel),
 * 但集成方式经过慎重取舍:
 *
 * FinancialDashboardPBI.vue (3311行) / SalesAnalysis.vue (2453行) /
 * analytics/trends/index.vue (926行, 含 useCapability 订阅分级门控) /
 * analytics/kpi/index.vue (1011行) / indicator-center/IndicatorCenterDashboard.vue
 * (545行) 这 5 个子视图已经各自拥有自己的日期选择器、权限/tier gate、且各自已经
 * 内嵌了一套更早的图表洞察系统 (ChartInsightProvider+chartInsight.ts 或
 * ChartInsight.vue+useChartInsight)。在单次改动里深入这 5 个大文件内部去抠出
 * "当前激活 tab 的真实图表数据" 风险很高 (5000+ 行且各自 gate 逻辑不同,
 * 容易在 additive 的名义下无意间破坏已有 tab/图表行为)。
 *
 * 所以这里选择 Hub 级别的、完全独立、只读的做法: Hub 自己调用工厂数据接口
 * (api/smartbi/factoryOverview.ts: 销售订单汇总 + Java 工厂报表的往来余额 / 近一年生产 /
 * 按产品产量 / KPI, 后端已按角色 RBAC 脱敏) —— 不读取、不触碰上面 5 个子视图的内部状态或
 * 渲染逻辑。每张卡写明自己的口径 (全部历史 / 近 365 天 / 截至今天), 与各 tab 内部可自定义
 * 时间范围筛选后的数字不一定一致 —— 不伪装成对应 tab 的实时口径。
 * (2026-10-23 前这里读餐饮 gold 汇总 (账单/门店), 工厂账号下全空; 餐饮已废弃。)
 * ============================================================
 */
import { ref, reactive, computed, watch, onMounted, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import FinancialDashboardPBI from './FinancialDashboardPBI.vue';
import SalesAnalysis from './SalesAnalysis.vue';
import TrendsView from '@/views/analytics/trends/index.vue';
import KpiView from '@/views/analytics/kpi/index.vue';
import IndicatorCenterDashboard from '@/views/indicator-center/IndicatorCenterDashboard.vue';
import { resolveBusinessAnalysisTab, type BusinessAnalysisTab } from './businessAnalysisTab';
import ChartInsights from './components/analysis/ChartInsights.vue';
import AnalysisChatPanel from './components/analysis/AnalysisChatPanel.vue';
import { ChatDotRound, Close } from '@element-plus/icons-vue';
import { vReveal } from '@/composables/useReveal';
import type { AnalysisChartContext } from './components/analysis/analysisBullets';
import {
  factoryFinanceOverview,
  factorySalesOverview,
  factoryTrendOverview,
  factoryKpiOverview,
  type OverviewBlock,
} from './components/analysis/factoryOverviewBullets';
import {
  getFactorySalesSummary,
  getFactoryFinance,
  getFactoryTrend,
  getFactoryProductionByProduct,
  getFactoryKpi,
} from '@/api/smartbi/factoryOverview';

const route = useRoute();
const router = useRouter();

const activeTab = ref<BusinessAnalysisTab>(resolveBusinessAnalysisTab(route.query));
// KPI·指标 内层子 tab (kpi 看板 / 指标中心)。?sub= 同步, 默认 kpi。
const kpiSubTab = ref<string>(route.query.sub === 'indicator' ? 'indicator' : 'kpi');

// 外部 query 变化 (e.g. 旧路径 redirect 带入 ?tab=) → 同步激活 tab。
watch(
  () => route.query.tab,
  () => { activeTab.value = resolveBusinessAnalysisTab(route.query); },
);

function syncQuery(name: string) {
  // replace 而非 push — tab 切换不污染浏览器返回栈 (与 FinancialDashboardPBI section 同模式)。
  router.replace({ query: { ...route.query, tab: name } });
}

function syncKpiSubQuery(name: string) {
  router.replace({ query: { ...route.query, tab: 'kpi', sub: name } });
}

// ============================================================
// Hub 级概览: 本厂业务系统的真实读数 (销售订单 / 往来余额 / 近一年生产 / KPI), 与下方各看板同源。
// 原来读餐饮 POS 汇总 (账单/门店), 工厂账号下四张卡全空、还写着「¥0」(2026-10-23 设计复评)。
// 四张卡各自 loading / error 隔离 (一个失败不连累另一个, fool-proof-design 跨规则铁律)。
// ============================================================
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

type CardKey = 'finance' | 'sales' | 'trend' | 'kpi';
const CARD_ORDER: CardKey[] = ['finance', 'sales', 'trend', 'kpi'];
const CARD_TITLES: Record<CardKey, string> = {
  finance: '往来余额',
  sales: '销售订单',
  trend: '近一年生产',
  kpi: 'KPI',
};

interface CardState { loading: boolean; error: string; block: OverviewBlock | null }
const cards = reactive<Record<CardKey, CardState>>({
  finance: { loading: false, error: '', block: null },
  sales: { loading: false, error: '', block: null },
  trend: { loading: false, error: '', block: null },
  kpi: { loading: false, error: '', block: null },
});

function errorText(e: unknown, fallback: string): string {
  const server = (e as { serverMessage?: string } | null)?.serverMessage;
  return server || (e instanceof Error && e.message) || fallback;
}

async function loadCard(key: CardKey, load: () => Promise<OverviewBlock>) {
  const card = cards[key];
  card.loading = true;
  card.error = '';
  try {
    card.block = await load();
  } catch (e) {
    card.block = null;
    card.error = errorText(e, `${CARD_TITLES[key]}加载失败`);
  } finally {
    card.loading = false;
  }
}

onMounted(() => {
  const fid = factoryId.value;
  if (!fid) {
    for (const key of CARD_ORDER) cards[key].error = '当前账号没有所属工厂，请用工厂账号查看';
    return;
  }
  void loadCard('finance', async () => factoryFinanceOverview(await getFactoryFinance(fid)));
  void loadCard('sales', async () => factorySalesOverview(await getFactorySalesSummary()));
  void loadCard('trend', async () => {
    const [trend, products] = await Promise.all([getFactoryTrend(fid), getFactoryProductionByProduct(fid)]);
    return factoryTrendOverview(trend, products);
  });
  void loadCard('kpi', async () => factoryKpiOverview(await getFactoryKpi(fid)));
});

const analysisContexts = computed<AnalysisChartContext[]>(() => CARD_ORDER.map((key) => {
  const block = cards[key].block;
  return {
    key,
    title: CARD_TITLES[key],
    dataSummary: block?.summary ?? (cards[key].error || '尚未加载'),
    dataScope: block?.dataScope ?? '',
    unavailableMetrics: block?.unavailableMetrics ?? [],
  };
}));

// AI 分析面板放在右侧抽屉里, 默认收起: 看板占满宽度, 要问再打开 (不遮罩, 打开时仍可操作图表)。
// 抽屉首次打开才渲染面板, 之后保留 (对话不丢)。
const aiPanelOpen = ref(false);
const analysisChatPanelRef = ref<InstanceType<typeof AnalysisChatPanel> | null>(null);
async function focusChart(key: string) {
  aiPanelOpen.value = true;
  await nextTick();
  if (!analysisChatPanelRef.value) await nextTick();
  analysisChatPanelRef.value?.setFocus(key);
}
</script>

<template>
  <div class="business-analysis-hub">
    <div class="bah-layout">
      <div class="bah-main">
        <!-- 财务/销售/趋势/KPI 全历史概览卡 (Hub 独立请求, 与下方各 tab 内部
             可自定义时间范围的图表相互独立 — 见 <script> 顶部说明) -->
        <div class="bah-topbar">
          <div class="bah-overview-note">
            以下是本厂业务系统里的数据（往来余额、销售订单、近一年生产、KPI），和下方看板上传的报表互不影响。
          </div>
          <el-button
            :type="aiPanelOpen ? 'primary' : 'default'"
            :plain="aiPanelOpen"
            :icon="ChatDotRound"
            :aria-expanded="aiPanelOpen"
            data-testid="bah-ai-toggle"
            @click="aiPanelOpen = !aiPanelOpen"
          >
            AI 分析
          </el-button>
        </div>
        <div class="bah-overview-grid">
          <el-card
            v-for="(key, i) in CARD_ORDER"
            :key="key"
            v-reveal="i"
            shadow="never"
            class="bah-overview-card"
            :data-testid="`bah-card-${key}`"
          >
            <template #header><div class="bah-overview-header">{{ CARD_TITLES[key] }}</div></template>
            <el-skeleton v-if="cards[key].loading" :rows="2" animated />
            <div v-else-if="cards[key].error" class="bah-inline-error">{{ cards[key].error }}</div>
            <p v-else-if="cards[key].block?.empty" class="bah-empty">{{ cards[key].block?.empty }}</p>
            <ChartInsights
              v-else-if="cards[key].block"
              :bullets="cards[key].block!.bullets"
              :chart-key="key"
              :chart-title="CARD_TITLES[key]"
              :data-summary="cards[key].block!.summary"
              @focus="focusChart"
            />
          </el-card>
        </div>

        <el-tabs v-model="activeTab" class="hub-tabs" @tab-change="syncQuery">
          <el-tab-pane label="财务" name="finance" lazy>
            <FinancialDashboardPBI />
          </el-tab-pane>
          <el-tab-pane label="销售" name="sales" lazy>
            <SalesAnalysis />
          </el-tab-pane>
          <el-tab-pane label="趋势" name="trend" lazy>
            <TrendsView />
          </el-tab-pane>
          <el-tab-pane label="KPI·指标" name="kpi" lazy>
            <el-tabs v-model="kpiSubTab" type="card" class="kpi-sub-tabs" @tab-change="syncKpiSubQuery">
              <el-tab-pane label="KPI 看板" name="kpi" lazy>
                <KpiView />
              </el-tab-pane>
              <el-tab-pane label="指标中心" name="indicator" lazy>
                <IndicatorCenterDashboard />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
        </el-tabs>
      </div>

    </div>

    <el-drawer
      v-model="aiPanelOpen"
      direction="rtl"
      size="min(440px, 100vw)"
      :with-header="false"
      :modal="false"
      modal-penetrable
      :lock-scroll="false"
      resizable
      class="bah-ai-drawer"
      data-testid="bah-ai-drawer"
    >
      <!-- 面板是共享组件 (餐饮页有逐字节版式对照), 关闭按钮放在抽屉这一层, 不改面板 -->
      <el-button
        class="bah-ai-close"
        :icon="Close"
        link
        aria-label="关闭 AI 分析"
        data-testid="bah-ai-close"
        @click="aiPanelOpen = false"
      />
      <AnalysisChatPanel
        ref="analysisChatPanelRef"
        :factory-id="factoryId"
        :contexts="analysisContexts"
        page-title="经营分析"
      />
    </el-drawer>
  </div>
</template>

<style lang="scss" scoped>
.business-analysis-hub {
  padding: 16px;

  .hub-tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 8px;
    }
  }

  .kpi-sub-tabs {
    margin-top: 8px;
  }
}

/* 看板占满宽度; AI 分析面板在右侧抽屉 (见 <el-drawer>), 不再固定占一列 */
.bah-main { min-width: 0; }

.bah-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}

.bah-overview-note {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  line-height: 1.5;
}
.bah-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
@media (max-width: 1400px) {
  .bah-overview-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 768px) {
  .bah-overview-grid { grid-template-columns: 1fr; }
}
.bah-overview-card {
  :deep(.el-card__header) {
    padding: 10px 14px;
  }
  :deep(.el-card__body) {
    padding: 12px 14px;
  }
}
.bah-overview-header {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}
.bah-empty {
  margin: 0;
  color: var(--el-text-color-regular, #606266);
  font-size: 13px;
  line-height: 1.6;
}
.bah-inline-error {
  color: #f56c6c;
  font-size: 12px;
  padding: 4px 0;
}
</style>

<style lang="scss">
/* 抽屉挂在 body 上, scoped 样式够不着; 面板自己有边框和标题, 抽屉只给高度 */
.bah-ai-drawer {
  .el-drawer__body {
    position: relative;
    padding: 0;
    display: flex;
  }
  .acp-panel {
    flex: 1;
    border: none;
    border-radius: 0;
  }
  /* 给右上角的关闭按钮让出位置 */
  .acp-header {
    padding-right: 52px;
  }
  .bah-ai-close {
    position: absolute;
    top: 12px;
    right: 14px;
    z-index: 1;
    font-size: 18px;
  }
}
</style>
