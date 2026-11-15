<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { TableColumn } from '@nuxt/ui';
import { badgeColor } from '@/utils/csBadge';
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

function getWarnTagType(level: string): 'danger' | 'warning' | 'success' | 'info' {
  switch (level) {
    case 'OVERDUE': return 'danger';
    case 'URGENT': return 'danger';
    case 'WARN': return 'warning';
    case 'NORMAL': return 'success';
    default: return 'info';
  }
}

const warnColumns: TableColumn<TableRow>[] = [
  { id: 'level', header: '预警等级' },
  { accessorKey: 'planNumber', header: '计划编号' },
  { id: 'product', header: '产品类型' },
  { id: 'customer', header: '客户' },
  { accessorKey: 'plannedQuantity', header: '计划数量' },
  { accessorKey: 'actualQuantity', header: '实际数量' },
  { accessorKey: 'expectedCompletionDate', header: '预期完工' },
  { id: 'days', header: '距交期' },
  { id: 'status', header: '状态' },
];
const windowOptions = [
  { value: 3, label: '未来 3 天' },
  { value: 7, label: '未来 7 天' },
  { value: 14, label: '未来 14 天' },
  { value: 30, label: '未来 30 天' },
];
const emptyText = computed(() => (loaded.value ? '窗口内无交货预警' : '数据未加载 — 请点刷新重试'));
const CARDS: { key: WarnFilterKey; label: string; cls: string }[] = [
  { key: 'ALL', label: '全部', cls: 'card-all' },
  { key: 'OVERDUE', label: '已超期', cls: 'card-danger' },
  { key: 'URGENT', label: '紧急 (<3d)', cls: 'card-urgent' },
  { key: 'WARN', label: '预警 (3-7d)', cls: 'card-warn' },
  { key: 'NORMAL', label: '正常 (≥7d)', cls: 'card-normal' },
];

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
    <div class="cs-page-head">
      <h2>交货预警</h2>
      <div class="page-actions">
        <USelect v-model="windowDays" :items="windowOptions" class="w-40" @update:model-value="loadData" />
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="loadData">刷新</UButton>
      </div>
    </div>

    <!--
      五张卡同时是筛选入口: 卡上的数 == 点下去之后表格里的行数。
      用 <button> 而不是纯 div —— 键盘可达, aria-pressed 表达选中态。
    -->
    <div class="summary-cards">
      <button
        v-for="c in CARDS"
        :key="c.key"
        type="button"
        class="summary-card"
        :class="[c.cls, { 'card-active': activeLevel === c.key }]"
        :data-warn-card="c.key"
        :aria-pressed="activeLevel === c.key"
        @click="selectLevel(c.key)"
      >
        <span class="card-label">{{ c.label }}</span>
        <span class="card-value">{{ formatCount(countOf(c.key)) }}</span>
      </button>
    </div>

    <section class="cs-card">
      <UTable :data="displayRows" :columns="warnColumns" :loading="loading" :data-empty-text="emptyText" class="cs-table">
        <template #empty>{{ emptyText }}</template>
        <template #level-cell="{ row }">
          <UBadge :color="badgeColor(getWarnTagType(row.original.warnLevel))" variant="solid">{{ getWarnLabel(row.original.warnLevel) }}</UBadge>
        </template>
        <template #product-cell="{ row }">{{ row.original.productTypeName || row.original.productTypeId || '-' }}</template>
        <template #customer-cell="{ row }">{{ row.original.sourceCustomerName || '' }}</template>
        <template #days-cell="{ row }">
          <span :class="row.original.warnLevel === 'OVERDUE' ? 'days-overdue' : ''">{{ formatDaysCell(row.original.daysUntilDeadline) }}</span>
        </template>
        <template #status-cell="{ row }">{{ getStatusLabel(row.original.status) }}</template>
      </UTable>
    </section>
  </div>
</template>

<style scoped>
.delivery-warnings-page { padding: var(--cs-space-4); }
.page-actions { display: flex; gap: var(--cs-space-2); }
.summary-cards { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: var(--cs-space-3); margin-bottom: var(--cs-space-3); }

/* 卡片即筛选入口 —— <button> 自带键盘可达与按下语义 */
.summary-card {
  display: flex; flex-direction: column; align-items: flex-start; width: 100%;
  padding: var(--cs-space-4); text-align: left; cursor: pointer;
  border: 1px solid var(--cs-border); border-radius: var(--cs-radius); background: var(--cs-surface);
  transition: border-color 0.15s, background 0.15s;
}
.summary-card:hover { background: var(--cs-primary-soft); }
.summary-card:focus-visible { outline: 2px solid var(--cs-primary); outline-offset: 2px; }
.summary-card.card-active { border-color: var(--cs-primary); box-shadow: 0 0 0 1px var(--cs-primary) inset; }
.card-label { font-size: var(--cs-font); color: var(--cs-muted); margin-bottom: var(--cs-space-2); }
.card-value { font-size: 28px; font-weight: 600; line-height: 1.2; font-variant-numeric: tabular-nums; }
.card-all .card-value { color: var(--cs-text); }
.card-danger .card-value { color: var(--cs-danger); }
.card-urgent .card-value, .card-warn .card-value { color: var(--cs-warning); }
.card-normal .card-value { color: var(--cs-success); }
.days-overdue { color: var(--cs-danger); font-weight: 600; }
</style>
