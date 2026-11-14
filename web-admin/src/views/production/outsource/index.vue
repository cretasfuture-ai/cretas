<script setup lang="ts">
/**
 * 委外单 (生产管理 → 生产过程; DECISIONS 第十九节, 不新增委外菜单组)。
 * 一张委外单 = 一个委外厂, 下面若干任务行 (工序委外挂工单工序 / 整单委外可不挂工单)。
 * 列表的筛选全部走服务端 (状态 / 单号), 不在当前页里 includes。
 * 详情三个页签: 任务行 / 委外厂结存 (物权仍是我方) / 收发明细。本页只读。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  getOutsourceOrder,
  listOutsourceOrders,
  LINE_STATUS_LABEL,
  MOVEMENT_LABEL,
  ORDER_STATUS_LABEL,
  ORDER_STATUS_TAG,
  OUTSOURCE_TYPE_LABEL,
  type OutsourceMovementType,
  type OutsourceOrderDetail,
  type OutsourceOrderStatus,
  type OutsourceOrderSummary,
} from '@/api/outsource';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

const STATUS_OPTIONS: OutsourceOrderStatus[] = ['DRAFT', 'RELEASED', 'CLOSED', 'CANCELLED'];

const statusFilter = ref<OutsourceOrderStatus[]>(['DRAFT', 'RELEASED']);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const rows = ref<OutsourceOrderSummary[]>([]);
const loading = ref(false);
const loadFailed = ref(false);

const detailVisible = ref(false);
const detail = ref<OutsourceOrderDetail | null>(null);
const detailLoading = ref(false);
const detailError = ref('');
const activeTab = ref<'lines' | 'balances' | 'movements'>('lines');

function num(v: number | null | undefined, unit?: string | null) {
  if (v == null) return '—';
  return unit ? `${v} ${unit}` : String(v);
}
function money(v: number | null | undefined) {
  if (v == null) return '—';
  return v < 0 ? `-¥${(-v).toFixed(2)}` : `¥${v.toFixed(2)}`;
}
function time(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—';
}
function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback;
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    const res = await listOutsourceOrders(factoryId.value, {
      status: statusFilter.value, keyword: keyword.value, page: page.value, size: size.value,
    });
    if (res.success && res.data) {
      rows.value = res.data.content ?? [];
      total.value = res.data.totalElements ?? 0;
    } else {
      loadFailed.value = true;
      ElMessage.error(res.message || '委外单加载失败');
    }
  } catch (e) {
    loadFailed.value = true;
    ElMessage.error(errorText(e, '委外单加载失败，请稍后重试'));
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

async function openDetail(orderId: string) {
  detailVisible.value = true;
  activeTab.value = 'lines';
  if (detail.value?.id !== orderId) detail.value = null;
  detailLoading.value = true;
  detailError.value = '';
  try {
    const res = await getOutsourceOrder(factoryId.value, orderId);
    if (res.success) detail.value = res.data;
    else detailError.value = res.message || '委外单详情加载失败';
  } catch (e) {
    detailError.value = errorText(e, '委外单详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

function onDrawerClosed() {
  if (route.query.orderId) router.replace({ query: { ...route.query, orderId: undefined } });
}

const lineNoById = computed(() => {
  const m: Record<string, number> = {};
  for (const l of detail.value?.lines ?? []) m[l.id] = l.lineNo;
  return m;
});
const balanceTotal = computed(() => (detail.value?.balances ?? []).filter((b) => (b.currentQuantity ?? 0) > 0).length);

function movementLabel(t: string) {
  return MOVEMENT_LABEL[t as OutsourceMovementType] ?? t;
}

onMounted(async () => {
  await load();
  const q = route.query.orderId;
  if (typeof q === 'string' && q) openDetail(q);
});
watch(() => route.query.orderId, (q) => {
  if (typeof q === 'string' && q && detail.value?.id !== q) openDetail(q);
});
</script>

<template>
  <div class="page" data-testid="outsource-orders">
    <el-alert v-if="loadFailed" type="error" :closable="false" title="加载失败，下面的列表不代表没有委外单" class="alert" />

    <section class="card">
      <header class="card-head">
        <h2>委外单</h2>
        <span class="count">共 {{ total }} 条记录</span>
      </header>
      <div class="filters">
        <el-input v-model="keyword" clearable placeholder="搜索委外单号" class="keyword" data-testid="outsource-keyword" @keyup.enter="search" @clear="search">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="statusFilter" multiple collapse-tags placeholder="全部状态" class="status-select" data-testid="outsource-status" @change="search">
          <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="ORDER_STATUS_LABEL[s]" :value="s" />
        </el-select>
        <el-button :icon="Search" @click="search">搜索</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" empty-text="没有符合条件的委外单" row-class-name="clickable" @row-click="(r: OutsourceOrderSummary) => openDetail(r.id)">
        <el-table-column prop="orderNo" label="委外单号" min-width="160" />
        <el-table-column label="委外厂" min-width="140"><template #default="{ row }">{{ row.supplierName ?? row.supplierId }}</template></el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="ORDER_STATUS_TAG[row.status as OutsourceOrderStatus]" disable-transitions>{{ ORDER_STATUS_LABEL[row.status as OutsourceOrderStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lineCount" label="任务行" width="80" align="right" />
        <el-table-column label="已收合格 / 计划" width="140" align="right">
          <template #default="{ row }">{{ num(row.receivedGoodQuantity) }} / {{ num(row.plannedQuantity) }}</template>
        </el-table-column>
        <el-table-column label="委外厂结存" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ warn: (row.outsourceBalance ?? 0) > 0 && row.status === 'RELEASED' }">{{ num(row.outsourceBalance) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="加工费" width="110" align="right"><template #default="{ row }">{{ money(row.feeAmount) }}</template></el-table-column>
        <el-table-column label="预计回厂" width="110"><template #default="{ row }">{{ row.expectedReturnDate ?? '—' }}</template></el-table-column>
        <el-table-column label="创建时间" width="140"><template #default="{ row }">{{ time(row.createdAt) }}</template></el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </section>

    <el-drawer v-model="detailVisible" size="min(1080px, 92vw)" :title="detail ? `委外单 ${detail.orderNo} — ${detail.supplierName ?? detail.supplierId}` : '委外单'" @closed="onDrawerClosed">
      <div v-if="detailError" class="state error" data-testid="outsource-detail-error">
        {{ detailError }} <el-button link type="primary" @click="detail ? openDetail(detail.id) : undefined">重试</el-button>
      </div>
      <div v-else-if="!detail" v-loading="detailLoading" class="state">正在读取…</div>
      <template v-else>
        <el-descriptions :column="3" border size="small" data-testid="outsource-detail-summary">
          <el-descriptions-item label="状态">
            <el-tag :type="ORDER_STATUS_TAG[detail.status]" disable-transitions>{{ ORDER_STATUS_LABEL[detail.status] }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="预计回厂">{{ detail.expectedReturnDate ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="加工费合计">{{ money(detail.feeAmount) }}</el-descriptions-item>
          <el-descriptions-item label="下达时间">{{ time(detail.releasedAt) }}</el-descriptions-item>
          <el-descriptions-item label="结单时间">{{ time(detail.closedAt) }}</el-descriptions-item>
          <el-descriptions-item label="委外厂结存">{{ balanceTotal ? `${balanceTotal} 个批次未清` : '已清' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.remark" label="备注" :span="3">{{ detail.remark }}</el-descriptions-item>
        </el-descriptions>

        <el-tabs v-model="activeTab" class="block">
          <el-tab-pane :label="`任务行 (${detail.lines.length})`" name="lines">
            <el-table :data="detail.lines" size="small" border data-testid="outsource-lines">
              <el-table-column prop="lineNo" label="行" width="50" />
              <el-table-column label="类型" width="90"><template #default="{ row }">{{ OUTSOURCE_TYPE_LABEL[row.outsourceType as 'PROCESS' | 'WHOLE'] }}</template></el-table-column>
              <el-table-column label="工单 / 工序" min-width="150">
                <template #default="{ row }">
                  <template v-if="row.productionPlanId">{{ row.productionPlanNumber ?? row.productionPlanId }}<span v-if="row.processName"> · {{ row.processName }}</span></template>
                  <span v-else class="muted">不挂工单</span>
                </template>
              </el-table-column>
              <el-table-column label="产出" min-width="120"><template #default="{ row }">{{ row.outputName ?? row.productTypeId }}</template></el-table-column>
              <el-table-column label="计划" width="90" align="right"><template #default="{ row }">{{ num(row.plannedQuantity, row.unit) }}</template></el-table-column>
              <el-table-column label="合格 / 不良" width="100" align="right"><template #default="{ row }">{{ num(row.receivedGoodQuantity) }} / {{ num(row.receivedDefectQuantity) }}</template></el-table-column>
              <el-table-column label="退货 / 返工待收" width="120" align="right"><template #default="{ row }">{{ num(row.returnedGoodsQuantity) }} / {{ num(row.reworkPendingQuantity) }}</template></el-table-column>
              <el-table-column label="剩余应收" width="90" align="right"><template #default="{ row }">{{ num(row.remainingToReceive) }}</template></el-table-column>
              <el-table-column label="单价" width="80" align="right"><template #default="{ row }">{{ money(row.unitFee) }}</template></el-table-column>
              <el-table-column label="加工费" width="90" align="right"><template #default="{ row }">{{ money(row.feeAmount) }}</template></el-table-column>
              <el-table-column label="状态" width="80"><template #default="{ row }">{{ LINE_STATUS_LABEL[row.status as 'OPEN' | 'COMPLETED' | 'CANCELLED'] }}</template></el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`委外厂结存 (${balanceTotal})`" name="balances">
            <p class="hint">发到委外厂的料仍是我方库存，放在本单的委外仓里；结单前每个批次都要收完、退回或登记损耗。</p>
            <el-table :data="detail.balances" size="small" border empty-text="还没有发料" data-testid="outsource-balances">
              <el-table-column label="行" width="50"><template #default="{ row }">{{ lineNoById[row.lineId] ?? '—' }}</template></el-table-column>
              <el-table-column prop="batchNumber" label="委外仓批次" min-width="170" />
              <el-table-column label="物料" min-width="120"><template #default="{ row }">{{ row.name ?? row.materialTypeId ?? row.productTypeId }}</template></el-table-column>
              <el-table-column label="发出" width="90" align="right"><template #default="{ row }">{{ num(row.issuedQuantity, row.unit) }}</template></el-table-column>
              <el-table-column label="结存" width="90" align="right">
                <template #default="{ row }"><span :class="{ warn: (row.currentQuantity ?? 0) > 0 }">{{ num(row.currentQuantity, row.unit) }}</span></template>
              </el-table-column>
              <el-table-column label="单价" width="90" align="right"><template #default="{ row }">{{ money(row.unitPrice) }}</template></el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`收发明细 (${detail.movements.length})`" name="movements">
            <el-table :data="detail.movements" size="small" border empty-text="还没有收发记录" data-testid="outsource-movements">
              <el-table-column label="日期" width="100"><template #default="{ row }">{{ row.bizDate ?? '—' }}</template></el-table-column>
              <el-table-column label="类型" width="70"><template #default="{ row }">{{ movementLabel(row.movementType) }}</template></el-table-column>
              <el-table-column label="行" width="50"><template #default="{ row }">{{ lineNoById[row.lineId] ?? '—' }}</template></el-table-column>
              <el-table-column label="来源批次" min-width="150"><template #default="{ row }">{{ row.sourceBatchNumber ?? '—' }}</template></el-table-column>
              <el-table-column label="去向批次" min-width="150"><template #default="{ row }">{{ row.targetBatchNumber ?? '—' }}</template></el-table-column>
              <el-table-column label="数量" width="100" align="right">
                <template #default="{ row }">{{ num(row.quantity, row.unit) }}<span v-if="row.defectQuantity" class="muted"> (不良 {{ row.defectQuantity }})</span></template>
              </el-table-column>
              <el-table-column label="金额" width="90" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
              <el-table-column label="原因" min-width="110"><template #default="{ row }">{{ row.reason ?? '' }}</template></el-table-column>
              <el-table-column label="录入时间" width="130"><template #default="{ row }">{{ time(row.createdAt) }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.alert { margin-bottom: var(--cs-space-3); }
.card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-lg); box-shadow: var(--cs-shadow); }
.card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-5) var(--cs-space-5) var(--cs-space-4); border-bottom: 1px solid var(--cs-border); }
.card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 700; color: var(--cs-text); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.filters { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.status-select { width: 200px; }
.keyword { width: 280px; }
.block { margin-top: var(--cs-space-3); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-4) var(--cs-space-5); }
.hint { margin: 0 0 var(--cs-space-2); color: var(--cs-muted); font-size: var(--cs-font-small); }
.muted { color: var(--cs-muted); }
.warn { color: var(--cs-warning); font-weight: 600; }
.state { padding: var(--cs-space-4); color: var(--cs-muted); }
.state.error { color: var(--cs-danger); }
:deep(.clickable) { cursor: pointer; }
</style>
