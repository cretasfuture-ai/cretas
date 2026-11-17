<template>
  <section class="rounded-lg border border-default bg-default" data-testid="income-statement">
    <header class="flex flex-wrap items-center justify-between gap-3 border-b border-default px-4 py-3">
      <div class="flex items-baseline gap-2">
        <h3 class="text-lg font-semibold">利润表</h3>
        <span v-if="validMonths" class="text-sm text-muted">
          期间累计: {{ startYear }}-{{ String(startMonth).padStart(2, '0') }} 至
          {{ endYear }}-{{ String(endMonth).padStart(2, '0') }}
        </span>
      </div>
      <div class="flex items-center gap-2">
        <UInput v-model="startInput" type="month" :max="maxMonth" aria-label="起始月" data-testid="income-start" />
        <span class="text-muted">至</span>
        <UInput v-model="endInput" type="month" :max="maxMonth" aria-label="结束月" data-testid="income-end" />
        <UButton :loading="loading" @click="load">刷新</UButton>
      </div>
    </header>

    <div class="flex flex-col gap-4 p-4">
      <UAlert v-if="errorMessage" color="error" variant="subtle" :title="errorMessage" data-testid="income-error" />
      <UAlert v-if="data?.scopeApplied" color="info" variant="subtle" :title="data.scopeLabel">
        <template #description>{{ data.scopeNote }}</template>
      </UAlert>
      <div v-if="loading" class="animate-pulse py-10 text-center text-muted">正在读取…</div>
      <div v-else-if="!data" class="py-10 text-center text-muted">暂无数据 — 请选择期间后点击刷新</div>
      <template v-else>
        <!-- F006 财务审计 Bug 6 (2026-07-04): 待过账提示 — 官方报表已切 POSTED-only. -->
        <UAlert
          v-if="(data.pendingDraftVoucherCount ?? 0) > 0"
          color="info"
          variant="subtle"
          :title="`本报表仅含已过账 (POSTED) 凭证; 另有 ${data.pendingDraftVoucherCount} 张凭证待过账 (未计入本报表)`"
        />
        <UAlert
          v-if="data.grossMarginStatus === 'MISSING_COST_DATA'"
          color="warning"
          variant="subtle"
          :title="data.grossMarginStatusMessage || '期间没有已过账营业成本，利润与毛利率不可计算'"
        >
          <template #description>下方成本、毛利、营业利润和净利润显示为 —；请补录或同步成本凭证后重新生成。</template>
        </UAlert>

        <!-- 4 张关键 KPI 卡片 -->
        <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <div class="rounded-lg border border-default p-4 text-center" data-testid="income-kpi-revenue">
            <div class="mb-2 text-sm text-muted">营业收入</div>
            <div class="font-mono text-xl font-bold text-primary">{{ money(data.totalRevenue) }}</div>
          </div>
          <div class="rounded-lg border border-default p-4 text-center">
            <div class="mb-2 text-sm text-muted">营业毛利</div>
            <div class="font-mono text-xl font-bold text-success">{{ money(displayGrossProfit) }}</div>
          </div>
          <div class="rounded-lg border border-default p-4 text-center">
            <div class="mb-2 text-sm text-muted">营业利润</div>
            <div class="font-mono text-xl font-bold text-warning">{{ money(displayOperatingProfit) }}</div>
          </div>
          <div class="rounded-lg border border-default p-4 text-center">
            <div class="mb-2 text-sm text-muted">净利润</div>
            <div class="font-mono text-xl font-bold" :class="netProfitClass ? 'text-error' : 'text-success'">{{ money(displayNetProfit) }}</div>
          </div>
        </div>

        <!-- 利润表明细 -->
        <UTable :data="rows" :columns="STATEMENT_COLUMNS" data-testid="income-statement-table">
          <template #label-cell="{ row }">
            <span :class="{ 'pl-6 text-muted': row.original.indent, 'font-bold text-primary': row.original.isTotal }">{{ row.original.label }}</span>
          </template>
          <template #value-cell="{ row }">
            <span :class="{ 'font-bold text-primary': row.original.isTotal }">{{ money(row.original.value) }}</span>
          </template>
        </UTable>

        <!-- 明细展开 -->
        <details v-for="sec in detailSections" :key="sec.key" class="rounded-lg border border-default" :data-testid="`income-detail-${sec.key}`">
          <summary class="cursor-pointer px-4 py-3 font-medium">{{ sec.title }}</summary>
          <UTable :data="sec.items" :columns="DETAIL_COLUMNS">
            <template #amount-cell="{ row }">{{ fmt(row.original.amount) }}</template>
          </UTable>
        </details>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { getIncomeStatement, type IncomeStatementDTO } from '@/api/financeReport';
import { readIncomeMonthRange, readMonthRange } from '@/utils/financeReportPeriod';

const DETAIL_COLUMNS = [
  { accessorKey: 'accountCode', header: '科目编码' },
  { accessorKey: 'accountName', header: '科目名称' },
  { id: 'amount', header: '金额 (元)', meta: { class: { th: 'text-right', td: 'text-right' } } },
];
const STATEMENT_COLUMNS = [
  { id: 'label', header: '项目' },
  { id: 'value', header: '金额 (元)', meta: { class: { th: 'text-right', td: 'text-right' } } },
];

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const route = useRoute();

const today = new Date();
const defaultStart = `${today.getFullYear()}-01`;
const defaultEnd = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;

function monthsFromLink(): [string, string] | null {
  return route.query.periodStart !== undefined || route.query.periodEnd !== undefined
    ? readIncomeMonthRange(route.query) : [defaultStart, defaultEnd];
}
const dateRange = ref<[string, string] | null>(monthsFromLink());
const data = ref<IncomeStatementDTO | null>(null);
const loading = ref(false);
const errorMessage = ref('');
let requestSequence = 0;
const validMonths = computed(() => readMonthRange(dateRange.value));

const startYear = computed(() => validMonths.value?.[0].slice(0, 4));
const startMonth = computed(() => validMonths.value?.[0].slice(5));
const endYear = computed(() => validMonths.value?.[1].slice(0, 4));
const endMonth = computed(() => validMonths.value?.[1].slice(5));

// 原生月份输入的上限: 不能选未来月
const maxMonth = defaultEnd;
const startInput = computed({
  get: () => dateRange.value?.[0] ?? '',
  set: (v: string) => { dateRange.value = [v, dateRange.value?.[1] ?? '']; },
});
const endInput = computed({
  get: () => dateRange.value?.[1] ?? '',
  set: (v: string) => { dateRange.value = [dateRange.value?.[0] ?? '', v]; },
});

const detailSections = computed(() => [
  { key: 'revenues', title: '营业收入明细', items: data.value?.revenues ?? [] },
  { key: 'costs', title: '营业成本明细', items: data.value?.costs ?? [] },
  { key: 'expenses', title: '营业费用明细', items: data.value?.expenses ?? [] },
]);

function fmt(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** 金额展示: 缺值(无权限/后端没给)显示「—」, 不显示 0。 */
function money(v: number | null | undefined): string {
  return v == null ? '—' : `¥ ${fmt(v)}`;
}

interface Row {
  label: string;
  value: number | null;
  indent?: boolean;
  isTotal?: boolean;
}

const rows = computed<Row[]>(() => {
  if (!data.value) return [];
  const d = data.value;
  return [
    { label: '一、营业收入', value: d.totalRevenue, isTotal: false },
    { label: '减: 营业成本', value: displayTotalCost.value, indent: true },
    { label: '二、营业毛利 (= 营业收入 - 营业成本)', value: displayGrossProfit.value, isTotal: true },
    { label: '减: 营业费用 / 管理费用 / 财务费用', value: d.totalExpense, indent: true },
    { label: '三、营业利润 (= 营业毛利 - 营业费用)', value: displayOperatingProfit.value, isTotal: true },
    { label: '减: 所得税', value: d.incomeTax, indent: true },
    { label: '四、净利润 (= 营业利润 - 所得税)', value: displayNetProfit.value, isTotal: true },
  ];
});

const profitMetricsCalculable = computed(() => data.value?.grossMarginStatus !== 'MISSING_COST_DATA');
const displayTotalCost = computed(() => profitMetricsCalculable.value ? data.value?.totalCost ?? null : null);
const displayGrossProfit = computed(() => profitMetricsCalculable.value ? data.value?.grossProfit ?? null : null);
const displayOperatingProfit = computed(() => profitMetricsCalculable.value ? data.value?.operatingProfit ?? null : null);
const displayNetProfit = computed(() => profitMetricsCalculable.value ? data.value?.netProfit ?? null : null);

const netProfitClass = computed(() => {
  if (displayNetProfit.value == null) return '';
  return displayNetProfit.value < 0 ? 'negative' : '';
});

async function load() {
  const current = ++requestSequence;
  data.value = null;
  errorMessage.value = '';
  loading.value = false;
  const factory = factoryId.value, months = validMonths.value;
  if (!factory) return;
  if (route.query.factoryId !== undefined && route.query.factoryId !== factory) {
    errorMessage.value = '链接所属工厂与当前账号不一致，请使用正确工厂账号查看。'; return;
  }
  if (!months) { errorMessage.value = '请选择有效的起止月份；同期间核对必须覆盖完整会计月。'; return; }
  const [sy, sm, ey, em] = [Number(months[0].slice(0, 4)), Number(months[0].slice(5)), Number(months[1].slice(0, 4)), Number(months[1].slice(5))];
  loading.value = true;
  try {
    const resp = await getIncomeStatement(factory, sy, sm, ey, em);
    if (current !== requestSequence) return;
    if (!resp.success || !resp.data) throw new Error(resp.message || '加载利润表失败');
    const result = resp.data;
    if (result.factoryId !== factory || result.startYear !== sy || result.startMonth !== sm || result.endYear !== ey || result.endMonth !== em) {
      throw new Error('返回的工厂或期间与本次查询不一致，请刷新后重试。');
    }
    data.value = result;
  } catch (e: unknown) {
    if (current !== requestSequence) return;
    const msg = e instanceof Error ? e.message : String(e);
    errorMessage.value = msg;
    ElMessage({ message: msg, type: 'error', duration: 0, showClose: true });
  } finally {
    if (current === requestSequence) loading.value = false;
  }
}

watch(() => [route.query.periodStart, route.query.periodEnd], () => {
  dateRange.value = monthsFromLink();
});
watch(() => [factoryId.value, route.query.factoryId, ...(dateRange.value ?? [])], () => { void load(); }, { immediate: true });
onBeforeUnmount(() => { requestSequence++; });
</script>
