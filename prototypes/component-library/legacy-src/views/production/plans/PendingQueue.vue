<script setup lang="ts">
/**
 * PendingQueue.vue — 未完成生产计划队列 (Clerk View)
 *
 * 显示所有 PENDING 和 IN_PROGRESS 状态的生产计划，按计划日期升序（最紧迫的排最前）。
 * 设计原则：防呆 (fool-proof) — 文员只需看"还差什么"，不需要理解状态机。
 *
 * 数据来源：直接调用现有分页端点两次（PENDING + IN_PROGRESS），前端合并排序。
 * 后端无需任何修改。
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh, Search, WarningFilled } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

// ==================== Data ====================
const loading = ref(false);
const planRows = ref<TableRow[]>([]);
const lastRefreshedAt = ref<string>('');

/**
 * 两路请求各自的「问到了没有」。
 *
 * ⛔ 不能用 `planRows.length` 反推 —— 请求失败时 planRows 也是空数组,
 * 那样「一条都没查到」会被印成「确实是 0 条」, 而 0 在筛选胶囊上恰好是
 * 「这一类没有」的样子。两件事含义相反, 界面上必须分得开。
 */
const pendingLoaded = ref(false);
const inProgressLoaded = ref(false);

// ==================== Filter ====================
const keyword = ref('');

/** 状态胶囊的选中项。OVERDUE 是「紧迫度」维度, 与前两个状态正交, 这里作为单选视图并列。 */
type QueueFilterKey = 'ALL' | 'PENDING' | 'IN_PROGRESS' | 'OVERDUE';
const statusFilter = ref<QueueFilterKey>('ALL');

/**
 * 只经过关键词过滤的行 —— **胶囊计数的口径基准**。
 *
 * 计数必须与「点下去之后会看到多少条」完全一致, 所以计数算在关键词过滤【之后】、
 * 状态过滤【之前】。否则搜索框一输入, 胶囊上的数就和表格里的行数对不上。
 */
const keywordRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return planRows.value;
  return planRows.value.filter((row) => {
    return (
      String(row.planNumber || '').toLowerCase().includes(kw) ||
      String(row.productTypeName || row.productName || '').toLowerCase().includes(kw) ||
      String(row.sourceCustomerName || '').toLowerCase().includes(kw)
    );
  });
});

// ==================== Load ====================
async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    // Fetch PENDING and IN_PROGRESS in parallel using the existing list endpoint
    // Each call uses sortBy=plannedDate ASC so results come pre-sorted per group
    //
    // ⛔ 这里【曾经】是 `Promise.all` —— 它在任一路 reject 时整体 reject,
    // 于是 catch 把**另一路已经读到的真实条数**一起抹成「没问到」。
    // 两路是两个独立读数, 一路挂了不该让另一路的有效读数陪葬 ⇒ allSettled。
    const [pendingSettled, inProgressSettled] = await Promise.allSettled([
      get(`/${factoryId.value}/production-plans`, {
        params: {
          page: 1,
          size: 200,
          status: 'PENDING',
          sortBy: 'plannedDate',
          sortDirection: 'ASC',
        },
      }),
      get(`/${factoryId.value}/production-plans`, {
        params: {
          page: 1,
          size: 200,
          status: 'IN_PROGRESS',
          sortBy: 'plannedDate',
          sortDirection: 'ASC',
        },
      }),
    ]);

    /**
     * 一路的结果 → `TableRow[] | null`。
     * `null` = 没问到(reject / success:false / 没有 data), ⛔ 与「问到了但是 0 条」严格分开。
     */
    function rowsOf(
      settled: PromiseSettledResult<{ success?: boolean; data?: { content?: TableRow[] } }>
    ): TableRow[] | null {
      if (settled.status !== 'fulfilled') {
        console.error('[未完成队列] 一路请求失败', settled.reason);
        return null;
      }
      const res = settled.value;
      if (res?.success !== true || !res.data) return null;
      return res.data.content || [];
    }

    const pendingList = rowsOf(pendingSettled);
    const inProgressList = rowsOf(inProgressSettled);
    pendingLoaded.value = pendingList !== null;
    inProgressLoaded.value = inProgressList !== null;

    // Merge then sort by plannedDate ascending (nulls last)
    const merged = [...(pendingList ?? []), ...(inProgressList ?? [])];
    merged.sort((a, b) => {
      const da = String(a.plannedDate || '');
      const db = String(b.plannedDate || '');
      if (!da && !db) return 0;
      if (!da) return 1; // null dates go last
      if (!db) return -1;
      return da < db ? -1 : da > db ? 1 : 0;
    });

    planRows.value = merged;
    lastRefreshedAt.value = new Date().toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    });

    if (pendingList === null || inProgressList === null) {
      ElMessage({ message: '部分计划数据加载失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    console.error('[未完成队列] 加载失败', e);
    pendingLoaded.value = false;
    inProgressLoaded.value = false;
    planRows.value = [];
    ElMessage({ message: '加载未完成计划失败', type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

onMounted(() => loadData());

// ==================== Status helpers ====================
function getStatusType(status: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<string, '' | 'success' | 'warning' | 'info' | 'danger'> = {
    PENDING: 'info',
    PLANNED: 'info',
    PREPARED: 'info',
    IN_PROGRESS: 'warning',
  };
  return map[String(status).toUpperCase()] ?? 'info';
}

function getStatusText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待执行',
    PLANNED: '待执行',
    PREPARED: '草稿',
    IN_PROGRESS: '进行中',
  };
  return map[String(status).toUpperCase()] ?? status;
}

// 防呆 Rule 1: 存货生产(SAFETY_STOCK)等无「计划数量」字段的计划落地为 0/null，
// 裸 "0" 紧邻状态标签会被文员误读成异常/漏填，明确降级为 "—"。
function formatPlannedQuantity(v: number | null | undefined): string {
  if (v == null || v === 0) return '—';
  return String(v);
}

// ==================== Urgency / Shortage ====================

/**
 * 返回日期紧迫性级别:
 *  'overdue'  — 计划日期已过期
 *  'urgent'   — 今日或明日到期
 *  'soon'     — 3天内到期
 *  'normal'   — 4天以上
 */
function getDateUrgency(
  plannedDate: unknown
): 'overdue' | 'urgent' | 'soon' | 'normal' | 'unknown' {
  if (!plannedDate) return 'unknown';
  const target = new Date(String(plannedDate));
  if (isNaN(target.getTime())) return 'unknown';
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  target.setHours(0, 0, 0, 0);
  const diffDays = Math.floor(
    (target.getTime() - today.getTime()) / (1000 * 60 * 60 * 24)
  );
  if (diffDays < 0) return 'overdue';
  if (diffDays <= 1) return 'urgent';
  if (diffDays <= 3) return 'soon';
  return 'normal';
}

function getDateUrgencyTag(row: TableRow): {
  text: string;
  type: '' | 'success' | 'warning' | 'info' | 'danger';
  tooltip: string;
} {
  const urgency = getDateUrgency(row.plannedDate);
  switch (urgency) {
    case 'overdue':
      return {
        text: '已超期',
        type: 'danger',
        tooltip: `计划日 ${row.plannedDate} 已过期，请尽快处理`,
      };
    case 'urgent':
      return {
        text: '今明到期',
        type: 'danger',
        tooltip: `计划日 ${row.plannedDate} 极度紧迫`,
      };
    case 'soon':
      return {
        text: '3天内',
        type: 'warning',
        tooltip: `计划日 ${row.plannedDate} 即将到期`,
      };
    case 'normal':
      return { text: '', type: 'info', tooltip: '' };
    default:
      return { text: '', type: 'info', tooltip: '' };
  }
}

/**
 * 是否有开工阻断风险 (N1 后端已放行警告，但前端仍提示文员关注)
 * 使用 isUrgent 字段（CR < 1）作为代理指标; 真实缺料需看 transfer 记录
 */
function hasMaterialRisk(row: TableRow): boolean {
  // isUrgent: CR < 1 = 期望完工时间 < 工期，通常伴随资源压力
  return row.isUrgent === true;
}

// ==================== Counts ====================
/**
 * 计数三态: 问到了就印(**含 0**), 没问到印 `—`。
 *
 * `null` 专门表示「这一路没问到」, ⛔ 不许 `?? 0` 兜底 —— 那会把
 * 「接口挂了」翻译成「这一类一条都没有」, 而后者正是用户点进去之前想知道的事。
 */
function rowIsPending(r: TableRow): boolean {
  return String(r.status).toUpperCase() === 'PENDING';
}
function rowIsInProgress(r: TableRow): boolean {
  return String(r.status).toUpperCase() === 'IN_PROGRESS';
}

/** 待执行只由 PENDING 那一路供给 ⇒ 只看它问到没有。 */
const pendingCount = computed<number | null>(() =>
  pendingLoaded.value ? keywordRows.value.filter(rowIsPending).length : null
);
const inProgressCount = computed<number | null>(() =>
  inProgressLoaded.value ? keywordRows.value.filter(rowIsInProgress).length : null
);
/** 「全部」和「已超期」横跨两路 ⇒ 任一路没问到就都不算数。 */
const bothLoaded = computed(() => pendingLoaded.value && inProgressLoaded.value);
const allCount = computed<number | null>(() =>
  bothLoaded.value ? keywordRows.value.length : null
);
const overdueCount = computed<number | null>(() =>
  bothLoaded.value
    ? keywordRows.value.filter((r) => getDateUrgency(r.plannedDate) === 'overdue').length
    : null
);

/** 读到就印(含 0), 读不到印 `—`。 */
function formatCount(n: number | null): string {
  return n === null ? '—' : String(n);
}

// ==================== Status filter ====================
/**
 * 表格实际渲染的行 = 关键词过滤 ∩ 状态胶囊。
 * 胶囊的计数正是在 `keywordRows` 上算的, 所以「点之前看到的数」==「点之后的行数」。
 */
const filteredRows = computed(() => {
  const rows = keywordRows.value;
  switch (statusFilter.value) {
    case 'PENDING':
      return rows.filter(rowIsPending);
    case 'IN_PROGRESS':
      return rows.filter(rowIsInProgress);
    case 'OVERDUE':
      return rows.filter((r) => getDateUrgency(r.plannedDate) === 'overdue');
    default:
      return rows;
  }
});

/** 再点一次已选中的胶囊 = 取消筛选, 回到「全部」。 */
function selectStatus(key: QueueFilterKey): void {
  statusFilter.value = statusFilter.value === key && key !== 'ALL' ? 'ALL' : key;
}

const CAPSULE_LABEL: Record<QueueFilterKey, string> = {
  ALL: '全部',
  PENDING: '待执行',
  IN_PROGRESS: '进行中',
  OVERDUE: '已超期',
};

/**
 * 空表的说法必须跟着**为什么空**走 —— 三种「空」含义完全不同:
 *   ① 没问到          → 我们什么都不知道
 *   ② 筛掉了          → 队列里有计划, 只是这一类没有(⚠️「已超期 0」是好消息)
 *   ③ 队列真的是空的  → 没有未完成计划
 * ⛔ 一律说「暂无未完成计划」会把 ② 说成 ③: 用户筛「已超期」看到「暂无未完成计划」,
 * 会以为整个队列空了。
 */
const emptyDescription = computed(() => {
  if (!bothLoaded.value) return '数据未加载完整 — 请点刷新重试';
  if (statusFilter.value !== 'ALL') {
    return `当前筛选「${CAPSULE_LABEL[statusFilter.value]}」下没有计划`;
  }
  return keyword.value.trim() ? '没有匹配的计划' : '暂无未完成计划';
});

// ==================== Navigation ====================
function goToPlanList() {
  router.push('/production/plans');
}
</script>

<template>
  <div class="pending-queue-page">
    <!-- Header -->
    <div class="queue-header">
      <div class="queue-header-left">
        <h2 class="queue-title">未完成计划队列</h2>
        <span class="queue-subtitle">按计划日期升序 — 最紧迫排最前</span>
      </div>
      <div class="queue-header-right">
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button type="info" link @click="goToPlanList">→ 返回完整计划列表</el-button>
      </div>
    </div>

    <!-- Summary badges — 同时是筛选入口: 数就是「点下去会看到多少条」 -->
    <div class="summary-row">
      <button
        type="button"
        class="status-capsule capsule-all"
        :class="{ 'capsule-active': statusFilter === 'ALL' }"
        data-capsule="ALL"
        :aria-pressed="statusFilter === 'ALL'"
        @click="selectStatus('ALL')"
      >
        全部 <strong class="capsule-count">{{ formatCount(allCount) }}</strong>
      </button>
      <button
        type="button"
        class="status-capsule capsule-pending"
        :class="{ 'capsule-active': statusFilter === 'PENDING' }"
        data-capsule="PENDING"
        :aria-pressed="statusFilter === 'PENDING'"
        @click="selectStatus('PENDING')"
      >
        待执行 <strong class="capsule-count">{{ formatCount(pendingCount) }}</strong>
      </button>
      <button
        type="button"
        class="status-capsule capsule-progress"
        :class="{ 'capsule-active': statusFilter === 'IN_PROGRESS' }"
        data-capsule="IN_PROGRESS"
        :aria-pressed="statusFilter === 'IN_PROGRESS'"
        @click="selectStatus('IN_PROGRESS')"
      >
        进行中 <strong class="capsule-count">{{ formatCount(inProgressCount) }}</strong>
      </button>
      <!--
        ⛔ 不加 `v-if="overdueCount > 0"`: 胶囊消失时「0 条超期」和「没问到」
        长得一模一样, 而「今天一条都没超期」正是文员最想确认的好消息。
      -->
      <button
        type="button"
        class="status-capsule capsule-overdue"
        :class="{ 'capsule-active': statusFilter === 'OVERDUE' }"
        data-capsule="OVERDUE"
        :aria-pressed="statusFilter === 'OVERDUE'"
        @click="selectStatus('OVERDUE')"
      >
        ⚠ 已超期 <strong class="capsule-count">{{ formatCount(overdueCount) }}</strong>
      </button>
      <span v-if="lastRefreshedAt" class="refresh-time">上次刷新 {{ lastRefreshedAt }}</span>
    </div>

    <!-- Search filter -->
    <div class="filter-row">
      <el-input
        v-model="keyword"
        placeholder="按计划编号 / 产品名称 / 客户筛选"
        :prefix-icon="Search"
        clearable
        style="width: 320px"
      />
      <span class="filter-count">
        共 <strong>{{ filteredRows.length }}</strong> 条未完成计划
      </span>
    </div>

    <!-- Table -->
    <el-table
      :data="filteredRows"
      v-loading="loading"
      :empty-text="emptyDescription"
      stripe
      border
      style="width: 100%"
      :row-class-name="
        ({ row }: { row: { plannedDate?: string } }) => {
          const u = getDateUrgency(row.plannedDate);
          if (u === 'overdue') return 'row-overdue';
          if (u === 'urgent') return 'row-urgent';
          return '';
        }
      "
    >
      <!-- 计划编号 -->
      <el-table-column prop="planNumber" label="计划编号" width="160" fixed="left" />

      <!-- 产品 -->
      <el-table-column label="产品" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.productTypeName || row.productName || row.productTypeId || '-' }}
        </template>
      </el-table-column>

      <!-- 客户 -->
      <el-table-column
        prop="sourceCustomerName"
        label="客户"
        min-width="120"
        show-overflow-tooltip
      />

      <!-- 计划数量 -->
      <el-table-column
        label="计划量"
        width="100"
        align="right"
      >
        <template #default="{ row }">{{ formatPlannedQuantity(row.plannedQuantity) }}</template>
      </el-table-column>

      <!-- 计划日期 + 紧迫度 -->
      <el-table-column label="计划日期" width="160" align="center">
        <template #default="{ row }">
          <div class="date-cell">
            <span>{{ row.plannedDate || '-' }}</span>
            <el-tooltip
              v-if="getDateUrgencyTag(row).text"
              :content="getDateUrgencyTag(row).tooltip"
              placement="top"
            >
              <el-tag
                :type="getDateUrgencyTag(row).type"
                size="small"
                style="margin-left: 4px"
              >{{ getDateUrgencyTag(row).text }}</el-tag>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>

      <!-- 状态 -->
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.status)" size="small">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>

      <!-- 报工模式 -->
      <el-table-column label="报工模式" width="90" align="center">
        <template #default="{ row }">
          <el-tag
            v-if="row.skipProcessReporting === false"
            type="warning"
            size="small"
            title="逐道工序报工"
          >逐道</el-tag>
          <el-tag v-else type="info" size="small" title="两点报工（领料+产出）">
            免工序
          </el-tag>
        </template>
      </el-table-column>

      <!-- 物料风险 -->
      <el-table-column label="物料" width="90" align="center">
        <template #default="{ row }">
          <el-tooltip
            v-if="hasMaterialRisk(row)"
            content="CR值 < 1：排程紧张，建议确认原料到位"
            placement="top"
          >
            <el-tag type="danger" size="small" effect="plain">
              <el-icon><WarningFilled /></el-icon>
              排程紧
            </el-tag>
          </el-tooltip>
          <span v-else class="material-ok">✓</span>
        </template>
      </el-table-column>

      <!-- 指派主管 -->
      <el-table-column
        prop="assignedSupervisorName"
        label="主管"
        width="90"
        show-overflow-tooltip
      />

      <!-- 来源 -->
      <el-table-column label="来源" width="90" align="center">
        <template #default="{ row }">
          <el-tag
            v-if="String(row.planNumber || '').startsWith('SEC-')"
            type="warning"
            size="small"
          >二次加工</el-tag>
          <el-tag
            v-else-if="row.sourceType === 'CUSTOMER_ORDER'"
            type="primary"
            size="small"
          >销售单</el-tag>
          <el-tag v-else-if="row.sourceType === 'AI_FORECAST'" type="success" size="small">
            AI预测
          </el-tag>
          <el-tag v-else size="small">手动</el-tag>
        </template>
      </el-table-column>

      <!-- 操作 -->
      <el-table-column label="操作" width="100" fixed="right" align="center">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            size="small"
            @click="router.push(`/production/plans?highlight=${row.id}`)"
          >
            查看计划
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Empty hint -->
    <div v-if="!loading && filteredRows.length === 0" class="empty-hint">
      <el-empty :description="emptyDescription" :image-size="80">
        <el-button v-if="statusFilter !== 'ALL'" @click="selectStatus('ALL')">清除筛选</el-button>
        <el-button type="primary" @click="goToPlanList">去查看完整计划列表</el-button>
      </el-empty>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.pending-queue-page {
  padding: 16px 20px;
  min-height: 100%;
  background: var(--el-bg-color-page, #f2f3f5);
}

.queue-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 8px;

  .queue-header-right {
    display: flex;
    align-items: center;
    gap: 8px;
  }
}

.queue-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--el-text-color-primary, #303133);
}

.queue-subtitle {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  margin-top: 2px;
}

.summary-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

// 状态胶囊 — 用 <button> 而不是 el-tag: 它本来就是一个筛选入口,
// 键盘可达 / aria-pressed 表达选中态, 都是 <button> 自带的。
.status-capsule {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 14px;
  line-height: 1.4;
  border-radius: 16px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  background: var(--el-fill-color-blank, #fff);
  color: var(--el-text-color-regular, #606266);
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s, color 0.2s;
}

.status-capsule:hover {
  border-color: var(--el-color-primary, #409eff);
  color: var(--el-color-primary, #409eff);
}

.status-capsule:focus-visible {
  outline: 2px solid var(--el-color-primary, #409eff);
  outline-offset: 2px;
}

.status-capsule .capsule-count {
  font-weight: 600;
}

.status-capsule.capsule-active {
  border-color: var(--el-color-primary, #409eff);
  background: var(--el-color-primary, #409eff);
  color: #fff;
}

.status-capsule.capsule-overdue {
  color: var(--el-color-danger, #f56c6c);
  border-color: var(--el-color-danger-light-5, #fab6b6);
}

.status-capsule.capsule-overdue:hover {
  border-color: var(--el-color-danger, #f56c6c);
  color: var(--el-color-danger, #f56c6c);
}

.status-capsule.capsule-overdue.capsule-active {
  background: var(--el-color-danger, #f56c6c);
  border-color: var(--el-color-danger, #f56c6c);
  color: #fff;
}

.refresh-time {
  font-size: 12px;
  color: var(--el-text-color-placeholder, #c0c4cc);
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.filter-count {
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
}

// Row overdue / urgent highlighting
:deep(.row-overdue) {
  background-color: rgba(245, 108, 108, 0.08) !important;
}

:deep(.row-urgent) {
  background-color: rgba(230, 162, 60, 0.08) !important;
}

.date-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 4px;
}

.material-ok {
  color: var(--el-color-success, #67c23a);
  font-size: 14px;
}

.empty-hint {
  margin-top: 32px;
  display: flex;
  justify-content: center;
}
</style>
