<template>
  <section class="rounded-lg border border-default bg-default" data-testid="cash-flow">
    <header class="flex flex-wrap items-center justify-between gap-3 border-b border-default px-4 py-3">
      <div class="flex items-baseline gap-2">
        <h3 class="text-lg font-semibold">现金流量表</h3>
        <span class="text-sm text-muted">
          期间累计 (直接法): {{ startYear }}-{{ String(startMonth).padStart(2, '0') }} 至
          {{ endYear }}-{{ String(endMonth).padStart(2, '0') }}
        </span>
      </div>
      <div class="flex items-center gap-2">
        <UInput v-model="startInput" type="month" :max="maxMonth" aria-label="起始月" data-testid="cashflow-start" />
        <span class="text-muted">至</span>
        <UInput v-model="endInput" type="month" :max="maxMonth" aria-label="结束月" data-testid="cashflow-end" />
        <UButton :loading="loading" @click="load">刷新</UButton>
        <UButton color="success" variant="soft" :loading="exporting" :disabled="!data" @click="doExport">导出 Excel</UButton>
      </div>
    </header>

    <div class="flex flex-col gap-4 p-4">
      <UAlert v-if="data?.scopeApplied" color="info" variant="subtle" :title="data.scopeLabel">
        <template #description>{{ data.scopeNote }}</template>
      </UAlert>

      <UAlert color="info" variant="subtle" title="直接法 — 基于现金账户 (1001/1002/1012) 与对手科目自动分类">
        <template #description>复杂业务 (e.g. 应收账款回款时间差) 可能需手工调整. 投资 / 筹资分类按科目 code 前缀自动判定.</template>
      </UAlert>

      <!-- F006 财务审计 Bug 6 follow-up (2026-07-04): 待过账提示 — 现金流量表已切 POSTED-only. -->
      <UAlert
        v-if="data && (data.pendingDraftVoucherCount ?? 0) > 0"
        color="info"
        variant="subtle"
        :title="`本报表仅含已过账 (POSTED) 凭证; 另有 ${data.pendingDraftVoucherCount} 张凭证待过账 (未计入本报表)`"
      />

      <div v-if="loading" class="animate-pulse py-10 text-center text-muted">正在读取…</div>
      <div v-else-if="!data" class="py-10 text-center text-muted">暂无数据</div>
      <template v-else>
        <!-- 4 张总览卡片 -->
        <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <div v-for="k in kpis" :key="k.id" class="rounded-lg border border-default p-4 text-center" :data-testid="`cashflow-kpi-${k.id}`">
            <div class="mb-2 text-sm text-muted">{{ k.label }}</div>
            <div class="font-mono text-xl font-bold" :class="tone(k.v)">{{ money(k.v) }}</div>
          </div>
        </div>

        <!-- 三大活动明细 -->
        <details v-for="sec in sections" :key="sec.key" class="rounded-lg border border-default" :open="sec.key === 'operating'" :data-testid="`cashflow-section-${sec.key}`">
          <summary class="cursor-pointer px-4 py-3 font-medium">{{ sec.title }}</summary>
          <div v-if="sec.activities.length === 0" class="p-5 text-center text-muted">无相关活动</div>
          <template v-else>
            <UTable :data="sec.activities" :columns="ACTIVITY_COLUMNS">
              <template #inflow-cell="{ row }"><span class="font-mono text-success">{{ fmt(row.original.inflow) }}</span></template>
              <template #outflow-cell="{ row }"><span class="font-mono text-error">{{ fmt(row.original.outflow) }}</span></template>
              <template #netAmount-cell="{ row }"><span class="font-mono font-semibold" :class="tone(row.original.netAmount)">{{ money(row.original.netAmount) }}</span></template>
            </UTable>
            <div class="flex justify-between border-t border-default bg-elevated px-4 py-2.5 font-bold">
              <span>小计</span>
              <span class="font-mono" :class="tone(sec.net)">{{ money(sec.net) }}</span>
            </div>
          </template>
        </details>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { getCashFlow, exportCashFlow, type CashFlowDTO } from '@/api/financeReport';

const ACTIVITY_COLUMNS = [
  { accessorKey: 'counterpartCode', header: '科目编码' },
  { accessorKey: 'counterpartName', header: '对手科目' },
  { id: 'inflow', header: '流入', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'outflow', header: '流出', meta: { class: { th: 'text-right', td: 'text-right' } } },
  { id: 'netAmount', header: '净额', meta: { class: { th: 'text-right', td: 'text-right' } } },
];

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const today = new Date();
const defaultStart = `${today.getFullYear()}-01`;
const defaultEnd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;

const dateRange = ref<[string, string]>([defaultStart, defaultEnd]);
const data = ref<CashFlowDTO | null>(null);
const loading = ref(false);
const exporting = ref(false);

const startYear = computed(() => parseInt(dateRange.value[0].split('-')[0], 10));
const startMonth = computed(() => parseInt(dateRange.value[0].split('-')[1], 10));
const endYear = computed(() => parseInt(dateRange.value[1].split('-')[0], 10));
const endMonth = computed(() => parseInt(dateRange.value[1].split('-')[1], 10));

const maxMonth = defaultEnd;
const startInput = computed({
  get: () => dateRange.value[0],
  set: (v: string) => { if (v) dateRange.value = [v, dateRange.value[1]]; },
});
const endInput = computed({
  get: () => dateRange.value[1],
  set: (v: string) => { if (v) dateRange.value = [dateRange.value[0], v]; },
});
/** 缺值(无权限/后端没给)显示「—」, 不显示 0。 */
function money(v: number | null | undefined): string {
  return v == null ? '—' : `¥ ${fmt(v)}`;
}
function tone(v: number | null | undefined): string {
  if (v == null) return '';
  return v >= 0 ? 'text-success' : 'text-error';
}

function fmt(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

const sections = computed(() => data.value ? [
  { key: 'operating', title: '一、经营活动现金流明细', activities: data.value.operatingActivities ?? [], net: data.value.operatingNetCashFlow },
  { key: 'investing', title: '二、投资活动现金流明细', activities: data.value.investingActivities ?? [], net: data.value.investingNetCashFlow },
  { key: 'financing', title: '三、筹资活动现金流明细', activities: data.value.financingActivities ?? [], net: data.value.financingNetCashFlow },
] : []);
const kpis = computed(() => data.value ? [
  { id: 'operating', label: '经营活动现金流', v: data.value.operatingNetCashFlow },
  { id: 'investing', label: '投资活动现金流', v: data.value.investingNetCashFlow },
  { id: 'financing', label: '筹资活动现金流', v: data.value.financingNetCashFlow },
  { id: 'net', label: '现金净增加额', v: data.value.netIncreaseInCash },
] : []);

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const resp = await getCashFlow(
      factoryId.value,
      startYear.value,
      startMonth.value,
      endYear.value,
      endMonth.value,
    );
    if (resp.success && resp.data) {
      data.value = resp.data;
    } else {
      ElMessage({
        message: resp.message || '加载现金流量表失败',
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
    await exportCashFlow(
      factoryId.value,
      startYear.value,
      startMonth.value,
      endYear.value,
      endMonth.value,
    );
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
