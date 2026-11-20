<template>
  <section class="rounded-lg border border-default bg-default" data-testid="balance-sheet">
    <header class="flex flex-wrap items-center justify-between gap-3 border-b border-default px-4 py-3">
      <div class="flex items-baseline gap-2">
        <h3 class="text-lg font-semibold">资产负债表</h3>
        <span class="text-sm text-muted">截至 {{ year }}-{{ String(month).padStart(2, '0') }} 月底</span>
      </div>
      <div class="flex items-center gap-2">
        <UInput v-model="selectedMonth" type="month" :max="maxMonth" aria-label="选择月份" data-testid="balance-month" />
        <UButton :loading="loading" @click="load">刷新</UButton>
        <UButton color="success" variant="soft" :loading="exporting" :disabled="!data" @click="doExport">导出 Excel</UButton>
      </div>
    </header>

    <div class="flex flex-col gap-4 p-4">
      <UAlert v-if="data?.scopeApplied" color="info" variant="subtle" :title="data.scopeLabel">
        <template #description>{{ data.scopeNote }}</template>
      </UAlert>

      <!-- 防呆 R5: 期间未结账 WARN — 跳到 period 页 -->
      <UAlert
        v-if="data && data.periodStatus !== 'CLOSED'"
        color="warning"
        variant="subtle"
        :title="`数据未结账 (${statusLabel(data.periodStatus)}) — 报表数字可能不准`"
      >
        <template #description>
          建议先完成期间结账以确保数据稳定:
          <UButton variant="link" size="xs" data-testid="balance-go-period" @click="goToPeriodClose">前往期间结账</UButton>
        </template>
      </UAlert>

      <!-- F006 财务审计 Bug 6 (2026-07-04): 待过账提示 — 官方报表已切 POSTED-only. -->
      <UAlert
        v-if="data && (data.pendingDraftVoucherCount ?? 0) > 0"
        color="info"
        variant="subtle"
        :title="`本报表仅含已过账 (POSTED) 凭证; 另有 ${data.pendingDraftVoucherCount} 张凭证待过账 (未计入本报表)`"
      />

      <!-- 平衡校验 -->
      <UAlert
        v-if="data && data.balanceCheck === false"
        color="error"
        variant="subtle"
        :title="`平衡校验失败: 资产合计 ${money(data.totalAssets)} ≠ 负债+所有者权益 ${money(data.totalLiabilitiesAndEquity)}`"
      >
        <template #description>可能原因: 凭证未平 / 科目分类错误 / 历史数据缺失. 请检查 voucher 完整性.</template>
      </UAlert>

      <div v-if="loading" class="animate-pulse py-10 text-center text-muted">正在读取…</div>
      <div v-else-if="!data" class="py-10 text-center text-muted">暂无数据 — 请选择月份后点击刷新</div>
      <div v-else class="flex flex-col gap-4">
        <div class="grid grid-cols-1 gap-5 lg:grid-cols-2">
          <!-- 资产部分 (左) -->
          <div class="flex flex-col gap-1">
            <UTable :data="data.assets" :columns="ASSET_COLUMNS">
              <template #amount-cell="{ row }">{{ fmt(row.original.amount) }}</template>
            </UTable>
            <div class="flex justify-between rounded border border-primary/30 bg-primary/10 px-4 py-2.5 font-semibold text-primary">
              <span>资产合计</span>
              <span class="font-mono font-bold">{{ money(data.totalAssets) }}</span>
            </div>
          </div>

          <!-- 负债 + 所有者权益 部分 (右) -->
          <div class="flex flex-col gap-1">
            <UTable :data="data.liabilities" :columns="LIABILITY_COLUMNS">
              <template #amount-cell="{ row }">{{ fmt(row.original.amount) }}</template>
            </UTable>
            <div class="flex justify-between rounded bg-elevated px-4 py-2.5 font-semibold">
              <span>负债合计</span>
              <span class="font-mono font-bold">{{ money(data.totalLiabilities) }}</span>
            </div>

            <UTable :data="data.equity" :columns="EQUITY_COLUMNS" class="mt-3">
              <template #amount-cell="{ row }">{{ fmt(row.original.amount) }}</template>
            </UTable>
            <div class="flex justify-between rounded bg-elevated px-4 py-2.5 font-semibold">
              <span>所有者权益合计</span>
              <span class="font-mono font-bold">{{ money(data.totalEquity) }}</span>
            </div>

            <div class="flex justify-between rounded border border-primary/30 bg-primary/10 px-4 py-2.5 font-semibold text-primary">
              <span>负债 + 所有者权益 合计</span>
              <span class="font-mono font-bold">{{ money(data.totalLiabilitiesAndEquity) }}</span>
            </div>
          </div>
        </div>

        <!-- 平衡 OK 提示 -->
        <UAlert v-if="data.balanceCheck" color="success" variant="subtle" title="平衡校验通过: 资产 = 负债 + 所有者权益" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { getBalanceSheet, exportBalanceSheet, type BalanceSheetDTO } from '@/api/financeReport';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const today = new Date();
const selectedMonth = ref<string>(
  `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
);
const data = ref<BalanceSheetDTO | null>(null);
const loading = ref(false);
const exporting = ref(false);

const year = computed(() => parseInt(selectedMonth.value.split('-')[0], 10));
const month = computed(() => parseInt(selectedMonth.value.split('-')[1], 10));

const maxMonth = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;

const COLUMNS = (title: string) => [
  { accessorKey: 'accountCode', header: '科目编码' },
  { accessorKey: 'accountName', header: title },
  { id: 'amount', header: '金额 (元)', meta: { class: { th: 'text-right', td: 'text-right' } } },
];
const ASSET_COLUMNS = COLUMNS('资产');
const LIABILITY_COLUMNS = COLUMNS('负债');
const EQUITY_COLUMNS = COLUMNS('所有者权益');

/** 缺值(无权限/后端没给)显示「—」, 不显示 0。 */
function money(v: number | null | undefined): string {
  return v == null ? '—' : `¥ ${fmt(v)}`;
}

function fmt(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function statusLabel(s: string): string {
  if (s === 'OPEN') return '开账中';
  if (s === 'PENDING_CLOSE') return '审批中';
  return s;
}

function goToPeriodClose() {
  router.push({
    name: 'FinanceAccountingPeriod',
    query: { year: String(year.value), month: String(month.value) },
  });
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const resp = await getBalanceSheet(factoryId.value, year.value, month.value);
    if (resp.success && resp.data) {
      data.value = resp.data;
    } else {
      ElMessage({
        message: resp.message || '加载资产负债表失败',
        type: 'error',
        duration: 0,
        showClose: true,
      });
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

async function doExport() {
  if (!factoryId.value) return;
  exporting.value = true;
  try {
    await exportBalanceSheet(factoryId.value, year.value, month.value);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    ElMessage({ message: '导出失败: ' + msg, type: 'error', duration: 0, showClose: true });
  } finally {
    exporting.value = false;
  }
}

onMounted(() => {
  load();
});
</script>
