<script setup lang="ts">
/**
 * S4 缺料异常: 一个缺料事件一条处理实例。缺口按物料列「需求 / 已放行 / 质检中 / 在途 / 还缺」,
 * 下了采购单和到货待检都不算解决, 只有放行入库才减少缺口。「缺口已解决」和「订单交付」分两列。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  listShortageCases,
  getShortageCase,
  refreshShortageCase,
  type ShortageCase,
  type ShortageCaseDetail,
  type ShortageCaseStage,
} from '@/api/shortageCases';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

const STAGE_LABEL: Record<ShortageCaseStage, string> = {
  DETECTED: '待处理',
  PLANNED: '已定方案',
  EXECUTING: '执行中',
  WAITING_SUPPLY: '等到货 / 放行',
  PARTIALLY_RESOLVED: '部分解决',
  GAP_RESOLVED: '缺口已解决',
  CANCELLED: '已取消',
  MANUAL: '转人工',
  CLOSED: '已关闭',
};
const STAGE_TAG: Record<ShortageCaseStage, 'danger' | 'warning' | 'success' | 'info' | 'primary'> = {
  DETECTED: 'danger',
  PLANNED: 'primary',
  EXECUTING: 'primary',
  WAITING_SUPPLY: 'warning',
  PARTIALLY_RESOLVED: 'warning',
  GAP_RESOLVED: 'success',
  CANCELLED: 'info',
  MANUAL: 'info',
  CLOSED: 'info',
};
const ROLE_LABEL: Record<string, string> = {
  procurement_manager: '采购',
  quality_inspector: '质检（放行）',
  warehouse_manager: '仓库（收货）',
  production_manager: '生产',
};
const REASON_LABEL: Record<string, string> = {
  SO_FINANCE_APPROVED: '订单财务审核通过',
  MATERIAL_RECEIVED: '物料入库',
  MATERIAL_BATCH_RELEASED: '质检放行',
  MANUAL_REFRESH: '手动核对',
};

const openOnly = ref(true);
const loading = ref(false);
const loadFailed = ref(false);
const rows = ref<ShortageCase[]>([]);

const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<ShortageCaseDetail | null>(null);
const refreshing = ref(false);

function qty(v: number | null | undefined, unit: string | null) {
  if (v === null || v === undefined) return '—';
  return `${Number(v).toLocaleString()} ${unit ?? '（单位未知）'}`;
}
function time(v: string | null) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—';
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    const res = await listShortageCases(factoryId.value, { openOnly: openOnly.value, size: 100 });
    if (res.success) {
      rows.value = res.data ?? [];
    } else {
      loadFailed.value = true;
      ElMessage.error(res.message || '缺料实例加载失败');
    }
  } catch {
    loadFailed.value = true;
    ElMessage.error('缺料实例加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

async function openDetail(row: ShortageCase) {
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    const res = await getShortageCase(factoryId.value, row.id);
    if (res.success) detail.value = res.data;
    else ElMessage.error(res.message || '详情加载失败');
  } catch {
    ElMessage.error('详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

async function refreshCase() {
  if (!detail.value) return;
  refreshing.value = true;
  try {
    const res = await refreshShortageCase(factoryId.value, detail.value.case.id);
    if (res.success) {
      ElMessage.success('已按当前库存与采购重新核对');
      await openDetail(detail.value.case);
      await load();
    } else {
      ElMessage.error(res.message || '核对失败');
    }
  } catch (e: unknown) {
    ElMessage.error((e as { message?: string })?.message || '核对失败');
  } finally {
    refreshing.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span class="title">缺料异常</span>
            <span class="subtitle">一个缺料事件一条；下单和到货待检都不算解决，放行入库才减少缺口</span>
          </div>
          <div class="actions">
            <el-switch v-model="openOnly" active-text="只看未关闭" @change="load" />
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="loadFailed" type="error" :closable="false" title="缺料实例加载失败，下面的列表不代表没有缺料" show-icon />

      <el-table v-loading="loading" :data="rows" empty-text="暂无缺料实例" @row-click="openDetail">
        <el-table-column label="来源订单" min-width="150">
          <template #default="{ row }">{{ row.salesOrderNumber || row.sourceId }}</template>
        </el-table-column>
        <el-table-column label="阶段" width="140">
          <template #default="{ row }">
            <el-tag :type="STAGE_TAG[row.stage as ShortageCaseStage]">{{ STAGE_LABEL[row.stage as ShortageCaseStage] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="该谁处理" width="130">
          <template #default="{ row }">{{ ROLE_LABEL[row.ownerRole] || row.ownerRole }}</template>
        </el-table-column>
        <el-table-column prop="gapLineCount" label="还缺的物料" width="110" />
        <el-table-column label="缺口解决时间" width="160">
          <template #default="{ row }">{{ time(row.gapResolvedAt) }}</template>
        </el-table-column>
        <el-table-column label="订单交付状态" width="130">
          <template #default="{ row }">{{ row.salesOrderStatusLabel || '—' }}</template>
        </el-table-column>
        <el-table-column label="最近核对" min-width="200">
          <template #default="{ row }">
            {{ time(row.lastRefreshedAt) }} · {{ REASON_LABEL[row.lastRefreshReason] || row.lastRefreshReason }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" size="760px" title="缺料实例">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="来源订单">{{ detail.case.salesOrderNumber || detail.case.sourceId }}</el-descriptions-item>
            <el-descriptions-item label="阶段">{{ STAGE_LABEL[detail.case.stage] }}</el-descriptions-item>
            <el-descriptions-item label="该谁处理">{{ ROLE_LABEL[detail.case.ownerRole] || detail.case.ownerRole }}</el-descriptions-item>
            <el-descriptions-item label="订单交付状态">{{ detail.case.salesOrderStatusLabel || '—' }}</el-descriptions-item>
            <el-descriptions-item label="缺口解决时间">{{ time(detail.case.gapResolvedAt) }}</el-descriptions-item>
            <el-descriptions-item label="最近核对">{{ time(detail.case.lastRefreshedAt) }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-head">
            <h4>缺口</h4>
            <el-button size="small" :icon="Refresh" :loading="refreshing" @click="refreshCase">按当前库存重新核对</el-button>
          </div>
          <el-table :data="detail.lines" size="small" border>
            <el-table-column label="物料" min-width="120">
              <template #default="{ row }">{{ row.materialName || row.materialTypeId }}</template>
            </el-table-column>
            <el-table-column label="需求"><template #default="{ row }">{{ qty(row.requiredQty, row.unitLabel) }}</template></el-table-column>
            <el-table-column label="已放行可用"><template #default="{ row }">{{ qty(row.releasedQty, row.unitLabel) }}</template></el-table-column>
            <el-table-column label="质检中"><template #default="{ row }">{{ qty(row.inspectingQty, row.unitLabel) }}</template></el-table-column>
            <el-table-column label="在途">
              <template #default="{ row }">
                {{ qty(row.onOrderQty, row.unitLabel) }}
                <div v-if="row.onOrderUnitMismatchLines > 0" class="hint">
                  另有 {{ row.onOrderUnitMismatchLines }} 行采购单位不同，未计入
                </div>
              </template>
            </el-table-column>
            <el-table-column label="还缺">
              <template #default="{ row }">
                <span :class="{ gap: row.gapQty > 0 }">{{ qty(row.gapQty, row.unitLabel) }}</span>
              </template>
            </el-table-column>
          </el-table>

          <h4>处理记录</h4>
          <el-timeline>
            <el-timeline-item v-for="(e, i) in detail.events" :key="i" :timestamp="time(e.createdAt)">
              <strong>{{ REASON_LABEL[e.triggerReason] || e.triggerReason }}</strong>
              <span v-if="e.toStage"> → {{ STAGE_LABEL[e.toStage] }}</span>
              <div v-if="e.detail" class="hint">{{ e.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { font-size: 16px; font-weight: 600; margin-right: 12px; }
.subtitle { color: var(--el-text-color-secondary); font-size: 13px; }
.actions { display: flex; gap: 12px; align-items: center; }
.section-head { display: flex; justify-content: space-between; align-items: center; margin: 16px 0 8px; }
.section-head h4, h4 { margin: 16px 0 8px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.gap { color: var(--el-color-danger); font-weight: 600; }
</style>
