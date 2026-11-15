<script setup lang="ts">
/**
 * 委外单 (生产管理 → 生产过程; DECISIONS 第十九节, 不新增委外菜单组)。
 * 一张委外单 = 一个委外厂, 下面若干任务行 (工序委外挂工单工序 / 整单委外可不挂工单)。
 * 列表的筛选全部走服务端 (状态 / 单号), 不在当前页里 includes。
 * 详情三个页签: 任务行 / 委外厂结存 (物权仍是我方) / 收发明细。本页只读。
 * 组件全部是 Nuxt UI (UTable / USlideover / UTabs / UBadge / UInput / USelectMenu / UPagination) + --cs-*；
 * 详情整体用 USlideover，里面不放 Element 控件（Element 下拉放进 Nuxt UI 弹层点不了）。
 * 错误提示仍用 ElMessage（命令式服务，不是可视组件，不受弹层限制）。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable';
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

const STATUS_ITEMS = STATUS_OPTIONS.map((s) => ({ label: ORDER_STATUS_LABEL[s], value: s }));
const BADGE_COLOR = { info: 'neutral', primary: 'primary', success: 'success', warning: 'warning' } as const;
const R = { class: { th: 'text-right', td: 'text-right' } };
const listColumns: TableColumn<OutsourceOrderSummary>[] = [
  { accessorKey: 'orderNo', header: '委外单号' },
  { id: 'supplier', header: '委外厂' },
  { id: 'status', header: '状态' },
  { accessorKey: 'lineCount', header: '任务行', meta: R },
  { id: 'received', header: '已收合格 / 计划', meta: R },
  { id: 'balance', header: '委外厂结存', meta: R },
  { id: 'fee', header: '加工费', meta: R },
  { id: 'expected', header: '预计回厂' },
  { id: 'created', header: '创建时间' },
];
const lineColumns: TableColumn<OutsourceOrderDetail['lines'][number]>[] = [
  { accessorKey: 'lineNo', header: '行' },
  { id: 'type', header: '类型' },
  { id: 'plan', header: '工单 / 工序' },
  { id: 'output', header: '产出' },
  { id: 'planned', header: '计划', meta: R },
  { id: 'goodDefect', header: '合格 / 不良', meta: R },
  { id: 'returned', header: '退货 / 返工待收', meta: R },
  { id: 'remaining', header: '剩余应收', meta: R },
  { id: 'unitFee', header: '单价', meta: R },
  { id: 'lineFee', header: '加工费', meta: R },
  { id: 'lineStatus', header: '状态' },
];
const balanceColumns: TableColumn<OutsourceOrderDetail['balances'][number]>[] = [
  { id: 'line', header: '行' },
  { accessorKey: 'batchNumber', header: '委外仓批次' },
  { id: 'material', header: '物料' },
  { id: 'issued', header: '发出', meta: R },
  { id: 'current', header: '结存', meta: R },
  { id: 'price', header: '单价', meta: R },
];
const movementColumns: TableColumn<OutsourceOrderDetail['movements'][number]>[] = [
  { id: 'date', header: '日期' },
  { id: 'mtype', header: '类型' },
  { id: 'mline', header: '行' },
  { id: 'source', header: '来源批次' },
  { id: 'target', header: '去向批次' },
  { id: 'qty', header: '数量', meta: R },
  { id: 'amount', header: '金额', meta: R },
  { id: 'reason', header: '原因' },
  { id: 'created', header: '录入时间' },
];
const DETAIL_TABS: TabsItem[] = [
  { label: '任务行', value: 'lines', slot: 'lines' },
  { label: '委外厂结存', value: 'balances', slot: 'balances' },
  { label: '收发明细', value: 'movements', slot: 'movements' },
];
function clearKeyword() {
  keyword.value = '';
  search();
}
function onRowSelect(_: Event, row: { original: OutsourceOrderSummary }) {
  openDetail(row.original.id);
}

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

function onDrawerChange(open: boolean) {
  detailVisible.value = open;
  if (!open && route.query.orderId) router.replace({ query: { ...route.query, orderId: undefined } });
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
    <UAlert v-if="loadFailed" color="error" variant="subtle" title="加载失败，下面的列表不代表没有委外单" class="mb-3" />

    <section class="cs-card">
      <header class="cs-card-head">
        <h2>委外单</h2>
        <span class="count">共 {{ total }} 条记录</span>
      </header>
      <div class="filters">
        <UInput v-model="keyword" icon="i-lucide-search" placeholder="搜索委外单号" class="w-70" data-testid="outsource-keyword" @keyup.enter="search">
          <template v-if="keyword" #trailing>
            <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="clearKeyword" />
          </template>
        </UInput>
        <USelectMenu v-model="statusFilter" multiple value-key="value" :items="STATUS_ITEMS" placeholder="全部状态" class="w-52" data-testid="outsource-status" @update:model-value="search" />
        <UButton color="neutral" variant="outline" icon="i-lucide-search" @click="search">搜索</UButton>
      </div>
      <UTable
        :data="rows"
        :columns="listColumns"
        :loading="loading"
        class="cs-table clickable"
        @select="onRowSelect"
      >
        <template #empty>没有符合条件的委外单</template>
        <template #supplier-cell="{ row }">{{ row.original.supplierName ?? row.original.supplierId }}</template>
        <template #status-cell="{ row }">
          <UBadge :color="BADGE_COLOR[ORDER_STATUS_TAG[row.original.status as OutsourceOrderStatus]]" variant="soft">{{ ORDER_STATUS_LABEL[row.original.status as OutsourceOrderStatus] }}</UBadge>
        </template>
        <template #received-cell="{ row }"><span class="num">{{ num(row.original.receivedGoodQuantity) }} / {{ num(row.original.plannedQuantity) }}</span></template>
        <template #balance-cell="{ row }">
          <span class="num" :class="{ warn: (row.original.outsourceBalance ?? 0) > 0 && row.original.status === 'RELEASED' }">{{ num(row.original.outsourceBalance) }}</span>
        </template>
        <template #fee-cell="{ row }"><span class="num">{{ money(row.original.feeAmount) }}</span></template>
        <template #expected-cell="{ row }">{{ row.original.expectedReturnDate ?? '—' }}</template>
        <template #created-cell="{ row }">{{ time(row.original.createdAt) }}</template>
      </UTable>
      <div class="pager">
        <UPagination v-model:page="page" :items-per-page="size" :total="total" show-edges :sibling-count="1" @update:page="load" />
      </div>
    </section>

    <USlideover
      :open="detailVisible"
      side="right"
      :title="detail ? `委外单 ${detail.orderNo} — ${detail.supplierName ?? detail.supplierId}` : '委外单'"
      description="委外单详情（只读）"
      :ui="{ content: 'cs-outsource-slide', description: 'sr-only' }"
      @update:open="onDrawerChange"
    >
      <template #body>
        <div v-if="detailError" class="state error" data-testid="outsource-detail-error">
          {{ detailError }} <UButton variant="link" size="xs" @click="detail ? openDetail(detail.id) : undefined">重试</UButton>
        </div>
        <div v-else-if="!detail" class="state" data-testid="outsource-detail-loading">正在读取…</div>
        <template v-else>
          <dl class="summary" data-testid="outsource-detail-summary">
            <div><dt>状态</dt><dd><UBadge :color="BADGE_COLOR[ORDER_STATUS_TAG[detail.status]]" variant="soft">{{ ORDER_STATUS_LABEL[detail.status] }}</UBadge></dd></div>
            <div><dt>预计回厂</dt><dd>{{ detail.expectedReturnDate ?? '—' }}</dd></div>
            <div><dt>加工费合计</dt><dd class="num">{{ money(detail.feeAmount) }}</dd></div>
            <div><dt>下达时间</dt><dd>{{ time(detail.releasedAt) }}</dd></div>
            <div><dt>结单时间</dt><dd>{{ time(detail.closedAt) }}</dd></div>
            <div><dt>委外厂结存</dt><dd>{{ balanceTotal ? `${balanceTotal} 个批次未清` : '已清' }}</dd></div>
            <div v-if="detail.remark" class="wide"><dt>备注</dt><dd>{{ detail.remark }}</dd></div>
          </dl>

          <UTabs v-model="activeTab" :items="DETAIL_TABS" variant="link" class="mt-3" :ui="{ content: 'pt-3' }">
            <template #lines>
              <UTable :data="detail.lines" :columns="lineColumns" class="cs-table" data-testid="outsource-lines">
                <template #type-cell="{ row }">{{ OUTSOURCE_TYPE_LABEL[row.original.outsourceType as 'PROCESS' | 'WHOLE'] }}</template>
                <template #plan-cell="{ row }">
                  <template v-if="row.original.productionPlanId">{{ row.original.productionPlanNumber ?? row.original.productionPlanId }}<span v-if="row.original.processName"> · {{ row.original.processName }}</span></template>
                  <span v-else class="muted">不挂工单</span>
                </template>
                <template #output-cell="{ row }">{{ row.original.outputName ?? row.original.productTypeId }}</template>
                <template #planned-cell="{ row }"><span class="num">{{ num(row.original.plannedQuantity, row.original.unit) }}</span></template>
                <template #goodDefect-cell="{ row }"><span class="num">{{ num(row.original.receivedGoodQuantity) }} / {{ num(row.original.receivedDefectQuantity) }}</span></template>
                <template #returned-cell="{ row }"><span class="num">{{ num(row.original.returnedGoodsQuantity) }} / {{ num(row.original.reworkPendingQuantity) }}</span></template>
                <template #remaining-cell="{ row }"><span class="num">{{ num(row.original.remainingToReceive) }}</span></template>
                <template #unitFee-cell="{ row }"><span class="num">{{ money(row.original.unitFee) }}</span></template>
                <template #lineFee-cell="{ row }"><span class="num">{{ money(row.original.feeAmount) }}</span></template>
                <template #lineStatus-cell="{ row }">{{ LINE_STATUS_LABEL[row.original.status as 'OPEN' | 'COMPLETED' | 'CANCELLED'] }}</template>
              </UTable>
            </template>

            <template #balances>
              <p class="hint">发到委外厂的料仍是我方库存，放在本单的委外仓里；结单前每个批次都要收完、退回或登记损耗。</p>
              <UTable :data="detail.balances" :columns="balanceColumns" class="cs-table" data-testid="outsource-balances">
                <template #empty>还没有发料</template>
                <template #line-cell="{ row }">{{ lineNoById[row.original.lineId] ?? '—' }}</template>
                <template #material-cell="{ row }">{{ row.original.name ?? row.original.materialTypeId ?? row.original.productTypeId }}</template>
                <template #issued-cell="{ row }"><span class="num">{{ num(row.original.issuedQuantity, row.original.unit) }}</span></template>
                <template #current-cell="{ row }"><span class="num" :class="{ warn: (row.original.currentQuantity ?? 0) > 0 }">{{ num(row.original.currentQuantity, row.original.unit) }}</span></template>
                <template #price-cell="{ row }"><span class="num">{{ money(row.original.unitPrice) }}</span></template>
              </UTable>
            </template>

            <template #movements>
              <UTable :data="detail.movements" :columns="movementColumns" class="cs-table" data-testid="outsource-movements">
                <template #empty>还没有收发记录</template>
                <template #date-cell="{ row }">{{ row.original.bizDate ?? '—' }}</template>
                <template #mtype-cell="{ row }">{{ movementLabel(row.original.movementType) }}</template>
                <template #mline-cell="{ row }">{{ lineNoById[row.original.lineId] ?? '—' }}</template>
                <template #source-cell="{ row }">{{ row.original.sourceBatchNumber ?? '—' }}</template>
                <template #target-cell="{ row }">{{ row.original.targetBatchNumber ?? '—' }}</template>
                <template #qty-cell="{ row }"><span class="num">{{ num(row.original.quantity, row.original.unit) }}</span><span v-if="row.original.defectQuantity" class="muted"> (不良 {{ row.original.defectQuantity }})</span></template>
                <template #amount-cell="{ row }"><span class="num">{{ money(row.original.amount) }}</span></template>
                <template #reason-cell="{ row }">{{ row.original.reason ?? '' }}</template>
                <template #created-cell="{ row }">{{ time(row.original.createdAt) }}</template>
              </UTable>
            </template>
          </UTabs>
        </template>
      </template>
    </USlideover>
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.filters { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-4) var(--cs-space-5); }
.hint { margin: 0 0 var(--cs-space-2); color: var(--cs-muted); font-size: var(--cs-font-small); }
.muted { color: var(--cs-muted); }
.warn { color: var(--cs-warning); font-weight: 600; }
.num { font-variant-numeric: tabular-nums; }
.state { padding: var(--cs-space-4); color: var(--cs-muted); }
.state.error { color: var(--cs-danger); }
.summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--cs-space-3); margin: 0; padding: var(--cs-space-3) var(--cs-space-4); background: var(--cs-surface-subtle); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.summary dt { font-size: var(--cs-font-small); color: var(--cs-muted); }
.summary dd { margin: 2px 0 0; color: var(--cs-text); }
.summary .wide { grid-column: 1 / -1; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
:deep(.clickable tbody tr) { cursor: pointer; }
:deep(.clickable tbody tr:hover td) { background: var(--cs-primary-soft); }
</style>
<style>
/* USlideover 自带 sm:max-w-md，同名工具类合并不掉；宽度直接用一条全局规则 */
.cs-outsource-slide { width: 100%; max-width: min(1080px, 92vw) !important; }
</style>
