<script setup lang="ts">
/**
 * SP11 F1/F2 — 进销存台账报表 + Excel 导出
 *
 * 防呆设计 (per fool-proof-design.md):
 * - Rule 1: 日期范围必填, 缺失禁用查询
 * - Rule 2: 表头显示期间 + 物料筛选上下文
 * - Rule 3: @PriceSensitive null 金额列显示"—", tooltip 说明需财务权限
 * - Rule 4: 导出防重按钮(loading 态 disabled)
 * - 4位一体: error toast sticky(duration:0, showClose=true), 后端真实 message
 *
 * Route: /finance/inventory-ledger
 * RBAC: inventory:read (后端控制), 金额字段由 PriceFieldResponseAdvice 自动脱敏
 *
 * Endpoints:
 *   GET /api/mobile/{factoryId}/inventory/ledger
 *     params: startDate, endDate, materialTypeId?
 *     returns: { data: { factoryId, startDate, endDate, lines: InventoryLedgerLineDTO[] } }
 *   GET /api/mobile/{factoryId}/inventory/ledger/export
 *     params: startDate, endDate, materialTypeId?
 *     returns: xlsx attachment (进销存台账, 金额列对非财务角色脱敏)
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import type { CsTableColumn } from '@/types/csTable';
import { maskedAmount } from '../financeDisplay';
import { get } from '@/api/request';
import request from '@/api/request';

// ============================================================
// Types — mirror InventoryLedgerLineDTO (camelCase, backend truth)
// ============================================================
interface InventoryLedgerLineDTO {
  materialTypeId: string;
  materialCode: string | null;
  materialName: string | null;
  unit: string | null;
  // 数量 (全角色可见)
  openingQty: number | null;
  inboundQty: number | null;
  outboundProductionQty: number | null;
  outboundSalesQty: number | null;
  transferInQty: number | null;
  transferOutQty: number | null;
  adjustQty: number | null;
  closingQty: number | null;
  // 金额 (@PriceSensitive — 仓管角色后端返 null)
  openingAmount: number | null;
  inboundAmount: number | null;
  outboundAmount: number | null;
  adjustAmount: number | null;
  closingAmount: number | null;
  movingAvgUnitPrice: number | null;
}

interface InventoryLedgerDTO {
  factoryId: string;
  startDate: string;
  endDate: string;
  lines: InventoryLedgerLineDTO[];
}

// ============================================================
// Stores
// ============================================================
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId ?? '');
const canViewPrice = computed(() => permissionStore.canViewPrice);

// ============================================================
// State
// ============================================================
const loading = ref(false);
const exportLoading = ref(false);
const tableData = ref<InventoryLedgerLineDTO[]>([]);
const ledgerPeriod = ref({ startDate: '', endDate: '' });

// Filters
// 日期范围: 两个原生日期输入 (取代 el-date-picker); 物料: 下拉选择 (取代手填物料类型 UUID)
const startDate = ref('');
const endDate = ref('');
const dateRange = computed<[string, string] | null>(() => (startDate.value && endDate.value ? [startDate.value, endDate.value] : null));
const ALL_MATERIALS = '__ALL__';
const materialTypeId = ref('');
interface MaterialOption { id: string; name?: string | null; code?: string | null }
const materialOptions = ref<MaterialOption[]>([]);
const materialItems = computed(() => [
  { label: '全部物料', value: ALL_MATERIALS },
  ...materialOptions.value.map((m) => ({
    label: m.code ? `${m.name || m.code}（${m.code}）` : String(m.name || '未命名物料'),
    value: String(m.id),
  })),
]);
async function loadMaterialOptions() {
  if (!factoryId.value) return;
  try {
    const res = await get<MaterialOption[] | { content: MaterialOption[] }>(`/${factoryId.value}/raw-material-types/active`, { _silent: true } as never);
    const d = res.data;
    materialOptions.value = Array.isArray(d) ? d : (d?.content ?? []);
  } catch {
    ElMessage.error('物料选项加载失败，可先不选物料直接查询');
  }
}

// ============================================================
// Computed
// ============================================================
const canQuery = computed(() => {
  return !!dateRange.value && !!dateRange.value[0] && !!dateRange.value[1];
});

const periodLabel = computed(() => {
  if (ledgerPeriod.value.startDate && ledgerPeriod.value.endDate) {
    return `${ledgerPeriod.value.startDate} 至 ${ledgerPeriod.value.endDate}`;
  }
  return '';
});

// ============================================================
// Helpers
// ============================================================
// 数量/金额: null = 没有值 → 「—」, 不当 0 显示; 真实的 0 仍显示 0。
function fmtQty(v: number | null): string {
  return maskedAmount(v, 6);
}

function fmtAmt(v: number | null): string {
  // @PriceSensitive: null → "—" (后端已脱敏, 前端不重算); 真实 0 金额显示 0.00 (旧写法 !v 会把 0 也当遮蔽)
  return maskedAmount(v, 2);
}

function fmtPrice(v: number | null): string {
  return maskedAmount(v, 4);
}

const isMasked = (v: number | null | undefined) => v === null || v === undefined;
const AMT_TIP = '需要财务权限查看金额';
const PRICE_TIP = '需要财务权限查看单价';

// ============================================================
// Data load
// ============================================================
async function loadData() {
  if (!canQuery.value) {
    ElMessage({ message: '请先选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const [startDate, endDate] = dateRange.value!;
    const params: Record<string, string> = { startDate, endDate };
    if (materialTypeId.value && materialTypeId.value !== ALL_MATERIALS) params.materialTypeId = materialTypeId.value;

    const res = await get<InventoryLedgerDTO>(
      `/${factoryId.value}/inventory/ledger`,
      { params }
    );
    if (res.success && res.data) {
      tableData.value = res.data.lines ?? [];
      ledgerPeriod.value = {
        startDate: res.data.startDate,
        endDate: res.data.endDate,
      };
    }
  } catch {
    // 拦截器已 toast
  } finally {
    loading.value = false;
  }
}

// ============================================================
// Export
// ============================================================
async function handleExport() {
  if (!canQuery.value) {
    ElMessage({ message: '请先选择日期范围再导出', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  exportLoading.value = true;
  try {
    const [startDate, endDate] = dateRange.value!;
    const params: Record<string, string> = {
      startDate,
      endDate,
    };
    if (materialTypeId.value && materialTypeId.value !== ALL_MATERIALS) params.materialTypeId = materialTypeId.value;

    // _keepResponse: true 必须 — 否则响应拦截器把 blob 解成 {success,data,message} 信封,
    // response.headers 会是 undefined(信封没有 headers 字段), 下面读 content-disposition
    // 会直接抛 TypeError 导致导出静默失败 (2026-07-03 sweep, 同 #1172 根因).
    const response = await request.get<Blob>(
      `/${factoryId.value}/inventory/ledger/export`,
      { params, responseType: 'blob', _keepResponse: true }
    );

    // 触发浏览器下载
    const contentDisposition = response.headers['content-disposition'] ?? '';
    let filename = `inventory_ledger_${startDate}_${endDate}.xlsx`;
    const match = contentDisposition.match(/filename\*?=(?:UTF-8'')?([^;]+)/i);
    if (match) {
      filename = decodeURIComponent(match[1].trim());
    }

    const url = URL.createObjectURL(new Blob([response.data]));
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);

    ElMessage({ message: '台账已导出', type: 'success', duration: 3000 });
  } catch {
    // 拦截器已 toast
  } finally {
    exportLoading.value = false;
  }
}

function handleReset() {
  startDate.value = '';
  endDate.value = '';
  materialTypeId.value = '';
  tableData.value = [];
  ledgerPeriod.value = { startDate: '', endDate: '' };
}

onMounted(() => {
  // 默认不自动查询，需用户选日期后手动触发（防呆 Rule 1）; 只预载物料下拉
  void loadMaterialOptions();
});

const QTY_KEYS = ['openingQty', 'inboundQty', 'outboundProductionQty', 'outboundSalesQty', 'transferInQty', 'transferOutQty', 'adjustQty', 'closingQty'] as const;
const AMT_KEYS = ['openingAmount', 'inboundAmount', 'outboundAmount', 'adjustAmount', 'closingAmount'] as const;
const R = { th: 'text-right', td: 'text-right' };
const columns: CsTableColumn<InventoryLedgerLineDTO>[] = [
  { id: 'materialCode', header: '物料代码', meta: { class: { th: 'sticky left-0 z-10 min-w-[110px]', td: 'sticky left-0 z-10 bg-[var(--cs-surface)] min-w-[110px]' } } },
  { id: 'materialName', header: '物料名称', meta: { class: { th: 'sticky left-[110px] z-10 min-w-[140px]', td: 'sticky left-[110px] z-10 bg-[var(--cs-surface)] min-w-[140px]' } } },
  { accessorKey: 'unit', header: '单位' },
  { id: 'openingQty', header: '期初数量', meta: { class: R } },
  { id: 'openingAmount', header: '期初金额', meta: { class: R } },
  { id: 'inboundQty', header: '入库数量', meta: { class: R } },
  { id: 'inboundAmount', header: '入库金额', meta: { class: R } },
  { id: 'outboundProductionQty', header: '领用数量', meta: { class: R } },
  { id: 'outboundSalesQty', header: '出货数量', meta: { class: R } },
  { id: 'outboundAmount', header: '出库金额', meta: { class: R } },
  { id: 'transferInQty', header: '调入数量', meta: { class: R } },
  { id: 'transferOutQty', header: '调出数量', meta: { class: R } },
  { id: 'adjustQty', header: '盘点调整', meta: { class: R } },
  { id: 'adjustAmount', header: '调整金额', meta: { class: R } },
  { id: 'closingQty', header: '期末数量', meta: { class: R } },
  { id: 'closingAmount', header: '期末金额', meta: { class: R } },
  { id: 'movingAvgUnitPrice', header: '移动均价', meta: { class: R } },
];
</script>

<template>
  <div class="page" data-testid="inventory-ledger-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>进销存台账</h2>
        <span v-if="periodLabel" class="period" data-testid="ledger-period">{{ periodLabel }}</span>
      </header>

      <!-- 筛选栏 -->
      <div class="filter-bar">
        <UFormField label="日期范围" required orientation="horizontal">
          <div class="range">
            <UInput v-model="startDate" type="date" class="w-40" data-testid="ledger-start" />
            <span class="muted">至</span>
            <UInput v-model="endDate" type="date" class="w-40" data-testid="ledger-end" />
          </div>
        </UFormField>
        <UFormField label="物料" orientation="horizontal">
          <!-- 物料从档案下拉选, 不再让用户手填物料类型 UUID -->
          <USelectMenu
            :model-value="materialTypeId || ALL_MATERIALS"
            value-key="value"
            :items="materialItems"
            placeholder="全部物料"
            class="w-64"
            data-testid="ledger-material"
            @update:model-value="materialTypeId = String($event ?? '')"
          />
        </UFormField>
        <UButton icon="i-lucide-search" :loading="loading" :disabled="!canQuery" data-testid="ledger-query" @click="loadData">查询</UButton>
        <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" @click="handleReset">重置</UButton>
        <UButton color="success" icon="i-lucide-download" :loading="exportLoading" :disabled="!canQuery" data-testid="ledger-export" @click="handleExport">导出 Excel</UButton>
      </div>

      <UAlert
        v-if="!canViewPrice && tableData.length > 0"
        color="info" variant="soft" icon="i-lucide-info" class="mx-5 mb-3"
        title="金额列需要财务权限查看，当前仅显示数量"
      />

      <div class="scroll">
        <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="ledger-table">
          <template #empty>请选择日期范围后点击查询</template>
          <template #materialCode-cell="{ row }">{{ row.original.materialCode || '—' }}</template>
          <template #materialName-cell="{ row }">{{ row.original.materialName || '—' }}</template>
          <template v-for="k in QTY_KEYS" :key="k" #[`${k}-cell`]="{ row }">
            <span class="num" :class="{ 'text-danger': k === 'adjustQty' && (row.original.adjustQty ?? 0) < 0 }">{{ fmtQty(row.original[k]) }}</span>
          </template>
          <template v-for="k in AMT_KEYS" :key="k" #[`${k}-cell`]="{ row }">
            <!-- 遮蔽 (null) 只显示「—」+ 原生 title; 真实 0 金额显示 0.00。UTooltip 需全局 UApp, 暂用 title -->
            <span v-if="isMasked(row.original[k])" class="num masked" :title="AMT_TIP" data-testid="ledger-masked">{{ fmtAmt(row.original[k]) }}</span>
            <span v-else class="num" :class="{ 'text-danger': k === 'adjustAmount' && (row.original.adjustAmount ?? 0) < 0 }">{{ fmtAmt(row.original[k]) }}</span>
          </template>
          <template #movingAvgUnitPrice-cell="{ row }">
            <span v-if="isMasked(row.original.movingAvgUnitPrice)" class="num masked" :title="PRICE_TIP" data-testid="ledger-masked">{{ fmtPrice(row.original.movingAvgUnitPrice) }}</span>
            <span v-else class="num">{{ fmtPrice(row.original.movingAvgUnitPrice) }}</span>
          </template>
        </UTable>
      </div>

      <footer v-if="tableData.length" class="foot">共 {{ tableData.length }} 条物料</footer>
    </section>
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.period { font-size: var(--cs-font-small); color: var(--cs-primary); background: var(--cs-primary-soft); padding: 2px 10px; border-radius: 12px; }
.filter-bar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-3) var(--cs-space-5); }
.range { display: flex; align-items: center; gap: var(--cs-space-2); }
.muted { color: var(--cs-muted); font-size: var(--cs-font-small); }
.scroll { overflow-x: auto; }
.foot { padding: var(--cs-space-3) var(--cs-space-5); font-size: 13px; color: var(--cs-muted); text-align: right; border-top: 1px solid var(--cs-border); }
.num { font-variant-numeric: tabular-nums; }
.masked { color: var(--cs-muted); cursor: help; }
.text-danger { color: var(--cs-danger); }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); white-space: nowrap; }
</style>
