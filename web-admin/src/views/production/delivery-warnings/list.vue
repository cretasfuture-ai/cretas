<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';

/**
 * 生产计划交货预警 dashboard — Sprint 4 Wave 2 M-DELIVERY-WARN-1.
 *
 * 调用 GET /api/mobile/{factoryId}/production-plans/delivery-warnings?windowDays=7
 * 返回 4 个预警等级 (OVERDUE / URGENT / WARN / NORMAL) 的 list, 按到期日期升序。
 */
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const windowDays = ref(7);

/**
 * 这一窗口的数据「问到了没有」。
 *
 * ⛔ 不能用 `tableData.length` 反推 —— 请求失败时它同样是空数组, 于是
 * 「一条都没查到」会被四张卡印成整齐的 0/0/0/0, 而那正好是「窗口内无预警」
 * 的样子。⚠️ 这两件事的含义完全相反: 一个是好消息, 一个是我们什么都不知道。
 */
const loaded = ref(false);

/** 预警等级 = 筛选维度。ALL 是不筛。 */
type WarnFilterKey = 'ALL' | 'OVERDUE' | 'URGENT' | 'WARN' | 'NORMAL';
const activeLevel = ref<WarnFilterKey>('ALL');

/** 计数三态: 问到了就印(含 0), 没问到印 `—`。⛔ 不许 `?? 0`。 */
function countOf(level: WarnFilterKey): number | null {
  if (!loaded.value) return null;
  if (level === 'ALL') return tableData.value.length;
  return tableData.value.filter(r => r.warnLevel === level).length;
}
function formatCount(n: number | null): string {
  return n === null ? '—' : String(n);
}

const overdueCount = computed(() => countOf('OVERDUE'));
const urgentCount = computed(() => countOf('URGENT'));
const warnCount = computed(() => countOf('WARN'));
const normalCount = computed(() => countOf('NORMAL'));
const allCount = computed(() => countOf('ALL'));

/**
 * 卡片就是筛选入口, 所以卡上的数必须等于点下去之后表格里的行数 ——
 * 两者都从同一个 `tableData` 上算, 不存在第二个口径。
 */
const displayRows = computed(() =>
  activeLevel.value === 'ALL'
    ? tableData.value
    : tableData.value.filter(r => r.warnLevel === activeLevel.value)
);

/** 再点一次已选中的卡 = 取消筛选。 */
function selectLevel(level: WarnFilterKey): void {
  activeLevel.value = activeLevel.value === level && level !== 'ALL' ? 'ALL' : level;
}

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/production-plans/delivery-warnings`, {
      params: { windowDays: windowDays.value }
    });
    if (response.success && Array.isArray(response.data)) {
      tableData.value = response.data;
      loaded.value = true;
    } else {
      // ⛔ 失败时不留着上一窗口的数据 —— 那会把过期读数冒充成当前窗口的。
      tableData.value = [];
      loaded.value = false;
      ElMessage.error(response.message || '加载交货预警失败');
    }
  } catch (error) {
    console.error('加载交货预警失败:', error);
    tableData.value = [];
    loaded.value = false;
    ElMessage.error('加载交货预警失败');
  } finally {
    loading.value = false;
  }
}

function getWarnTagType(level: string): string {
  switch (level) {
    case 'OVERDUE': return 'danger';
    case 'URGENT': return 'danger';
    case 'WARN': return 'warning';
    case 'NORMAL': return 'success';
    default: return 'info';
  }
}

function getWarnLabel(level: string): string {
  const map: Record<string, string> = {
    OVERDUE: '已超期',
    URGENT: '紧急 (<3d)',
    WARN: '预警 (3-7d)',
    NORMAL: '正常 (≥7d)'
  };
  return map[level] || level;
}

function getStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PLANNED: '待执行',
    PENDING: '待执行',
    PREPARED: '草稿',
    IN_PROGRESS: '进行中',
    PAUSED: '暂停',
    COMPLETED: '已完成',
    CANCELLED: '已取消'
  };
  return map[status] || status;
}

function formatDaysCell(days: number | null | undefined): string {
  if (days === null || days === undefined) return '-';
  if (days < 0) return `已超 ${Math.abs(days)} 天`;
  if (days === 0) return '今日到期';
  return `${days} 天后`;
}
</script>

<template>
  <div class="delivery-warnings-page">
    <div class="page-header">
      <h2>交货预警</h2>
      <div class="page-actions">
        <el-select v-model="windowDays" style="width: 160px" @change="loadData">
          <el-option :value="3" label="未来 3 天" />
          <el-option :value="7" label="未来 7 天" />
          <el-option :value="14" label="未来 14 天" />
          <el-option :value="30" label="未来 30 天" />
        </el-select>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
      </div>
    </div>

    <!--
      四张卡同时是筛选入口: 卡上的数 == 点下去之后表格里的行数。
      用 <button> 而不是纯 div —— 键盘可达, aria-pressed 表达选中态。
    -->
    <el-row :gutter="16" class="summary-cards">
      <el-col :span="4">
        <button
          type="button"
          class="summary-card card-all"
          :class="{ 'card-active': activeLevel === 'ALL' }"
          data-warn-card="ALL"
          :aria-pressed="activeLevel === 'ALL'"
          @click="selectLevel('ALL')"
        >
          <span class="card-label">全部</span>
          <span class="card-value">{{ formatCount(allCount) }}</span>
        </button>
      </el-col>
      <el-col :span="5">
        <button
          type="button"
          class="summary-card card-danger"
          :class="{ 'card-active': activeLevel === 'OVERDUE' }"
          data-warn-card="OVERDUE"
          :aria-pressed="activeLevel === 'OVERDUE'"
          @click="selectLevel('OVERDUE')"
        >
          <span class="card-label">已超期</span>
          <span class="card-value">{{ formatCount(overdueCount) }}</span>
        </button>
      </el-col>
      <el-col :span="5">
        <button
          type="button"
          class="summary-card card-urgent"
          :class="{ 'card-active': activeLevel === 'URGENT' }"
          data-warn-card="URGENT"
          :aria-pressed="activeLevel === 'URGENT'"
          @click="selectLevel('URGENT')"
        >
          <span class="card-label">紧急 (&lt;3d)</span>
          <span class="card-value">{{ formatCount(urgentCount) }}</span>
        </button>
      </el-col>
      <el-col :span="5">
        <button
          type="button"
          class="summary-card card-warn"
          :class="{ 'card-active': activeLevel === 'WARN' }"
          data-warn-card="WARN"
          :aria-pressed="activeLevel === 'WARN'"
          @click="selectLevel('WARN')"
        >
          <span class="card-label">预警 (3-7d)</span>
          <span class="card-value">{{ formatCount(warnCount) }}</span>
        </button>
      </el-col>
      <el-col :span="5">
        <button
          type="button"
          class="summary-card card-normal"
          :class="{ 'card-active': activeLevel === 'NORMAL' }"
          data-warn-card="NORMAL"
          :aria-pressed="activeLevel === 'NORMAL'"
          @click="selectLevel('NORMAL')"
        >
          <span class="card-label">正常 (≥7d)</span>
          <span class="card-value">{{ formatCount(normalCount) }}</span>
        </button>
      </el-col>
    </el-row>

    <el-table
      v-loading="loading"
      :data="displayRows"
      stripe
      border
      :empty-text="loaded ? '窗口内无交货预警' : '数据未加载 — 请点刷新重试'"
      class="warn-table"
    >
      <el-table-column label="预警等级" width="120">
        <template #default="{ row }">
          <el-tag :type="getWarnTagType(row.warnLevel)" effect="dark">
            {{ getWarnLabel(row.warnLevel) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="planNumber" label="计划编号" min-width="160" />
      <el-table-column label="产品类型" min-width="160">
        <template #default="{ row }">
          {{ row.productTypeName || row.productTypeId || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="sourceCustomerName" label="客户" min-width="140" show-overflow-tooltip />
      <el-table-column prop="plannedQuantity" label="计划数量" width="100" align="right" />
      <el-table-column prop="actualQuantity" label="实际数量" width="100" align="right" />
      <el-table-column prop="expectedCompletionDate" label="预期完工" width="120" />
      <el-table-column label="距交期" width="140" align="center">
        <template #default="{ row }">
          <span :class="row.warnLevel === 'OVERDUE' ? 'days-overdue' : ''">
            {{ formatDaysCell(row.daysUntilDeadline) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          {{ getStatusLabel(row.status) }}
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.delivery-warnings-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.summary-cards {
  margin-bottom: 16px;
}

/* 卡片即筛选入口 —— <button> 自带键盘可达与按下语义 */
.summary-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  padding: 16px;
  text-align: left;
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 4px;
  background: var(--el-fill-color-blank, #fff);
  cursor: pointer;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.summary-card:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.summary-card:focus-visible {
  outline: 2px solid var(--el-color-primary, #409eff);
  outline-offset: 2px;
}

.summary-card.card-active {
  border-color: var(--el-color-primary, #409eff);
  box-shadow: 0 0 0 1px var(--el-color-primary, #409eff) inset;
}

.summary-cards .card-label {
  font-size: 14px;
  color: #909399;
  margin-bottom: 8px;
}

.summary-cards .card-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}

.summary-cards .card-all .card-value { color: #303133; }
.summary-cards .card-danger .card-value { color: #f56c6c; }
.summary-cards .card-urgent .card-value { color: #e6a23c; }
.summary-cards .card-warn .card-value { color: #e6a23c; }
.summary-cards .card-normal .card-value { color: #67c23a; }

.days-overdue {
  color: #f56c6c;
  font-weight: 600;
}

.warn-table {
  margin-top: 8px;
}
</style>
