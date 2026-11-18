<script setup lang="ts">
/**
 * 经营分析「财务 → 利润分析」的系统毛利卡 (PROTO-GP-SHIPPED, Steve 2026-11-18 拍板)。
 *
 * 收入 = 发货收入 (已确认发货的发运单实发不含税金额, 与应收挂账同一批发运单), 成本 = 这些发运单实际出库批次成本。
 * 系统数据优先: 本卡在前; 下方按上传报表计算的「毛利润 / 净利润」只作补充对照。
 * ⛔ 没有发货确认数据时显示「—」并说明, 不回退到订单金额, 不把 null 读成 0。
 * 金额是否可见由后端按工厂权限配置判定 (无权时金额为 null 或接口 403), 本卡只如实转述。
 */
import { computed, ref, watch } from 'vue';
import { getShippedGrossProfit, type ShippedGrossProfit } from '@/api/financeReport';
import { ApiError } from '@/types/api';

const props = defineProps<{
  factoryId: string;
  periodStart: string;
  periodEnd: string;
}>();

const loading = ref(false);
const loadError = ref('');
const data = ref<ShippedGrossProfit | null>(null);
let seq = 0;

async function load() {
  if (!props.factoryId || !props.periodStart || !props.periodEnd) return;
  const mine = ++seq;
  loading.value = true;
  loadError.value = '';
  try {
    const res = await getShippedGrossProfit(props.factoryId, props.periodStart, props.periodEnd);
    if (mine !== seq) return;
    if (!res.success || !res.data) {
      data.value = null;
      loadError.value = `系统毛利加载失败（${res.message || '后端未返回数据'}）`;
      return;
    }
    data.value = res.data;
  } catch (error: unknown) {
    if (mine !== seq) return;
    data.value = null;
    if (error instanceof ApiError && error.status === 403) {
      loadError.value = '无权查看系统毛利：需要财务查看权限（按工厂权限配置）';
    } else {
      const reason = error instanceof Error && error.message ? `（${error.message}）` : '';
      loadError.value = `系统毛利加载失败${reason}，请稍后刷新重试`;
    }
  } finally {
    if (mine === seq) loading.value = false;
  }
}

watch(() => [props.factoryId, props.periodStart, props.periodEnd], load, { immediate: true });

const MASKED_NOTE = '无权查看金额（按工厂权限配置）';

/** 后端状态 OK 但金额为 null ⇒ 被权限脱敏, 不是算不出。 */
const masked = computed(() => data.value?.status === 'OK' && data.value.revenue == null);
const revenueKnown = computed(() => data.value?.revenue != null);
const profitKnown = computed(() => data.value?.status === 'OK' && data.value.grossProfit != null);

const revenueNote = computed(() => {
  const d = data.value;
  if (!d) return '';
  if (masked.value) return MASKED_NOTE;
  if (d.status === 'NO_SHIPMENT' || d.status === 'PRICE_MISSING') return d.unavailableReason || '';
  return `${d.shipmentLineCount} 行已确认发货明细`;
});

const profitNote = computed(() => {
  const d = data.value;
  if (!d) return '';
  if (masked.value) return MASKED_NOTE;
  if (d.status !== 'OK') return d.unavailableReason || '';
  return d.marginRate == null ? '毛利率 —（发货收入为 0）' : `毛利率 ${d.marginRate.toFixed(2)}%`;
});

function money(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
</script>

<template>
  <el-card class="shipped-gp-card" shadow="never" v-loading="loading" data-testid="shipped-gp-card">
    <div class="shipped-gp-head">
      <span class="shipped-gp-title">系统毛利（按发货收入）</span>
      <span class="shipped-gp-period">{{ periodStart }} ~ {{ periodEnd }}</span>
    </div>

    <div v-if="loadError" class="shipped-gp-error" data-testid="shipped-gp-error">{{ loadError }}</div>

    <div v-else-if="data" class="shipped-gp-grid">
      <div class="shipped-gp-cell">
        <div class="shipped-gp-label">发货收入（不含税）</div>
        <div class="shipped-gp-value" data-testid="shipped-gp-revenue">
          {{ revenueKnown ? money(data.revenue) : '—' }}<small v-if="revenueKnown">元</small>
        </div>
        <div class="shipped-gp-sub" data-testid="shipped-gp-revenue-note">{{ revenueNote }}</div>
      </div>
      <div class="shipped-gp-cell">
        <div class="shipped-gp-label">销售成本（实际出库批次）</div>
        <div class="shipped-gp-value" data-testid="shipped-gp-cost">
          {{ profitKnown ? money(data.cost) : '—' }}<small v-if="profitKnown">元</small>
        </div>
      </div>
      <div class="shipped-gp-cell">
        <div class="shipped-gp-label">毛利</div>
        <div
          class="shipped-gp-value"
          data-testid="shipped-gp-profit"
          :style="{ color: profitKnown && (data.grossProfit ?? 0) < 0 ? '#dc2626' : undefined }"
        >
          {{ profitKnown ? money(data.grossProfit) : '—' }}<small v-if="profitKnown">元</small>
        </div>
        <div class="shipped-gp-sub" data-testid="shipped-gp-profit-note">{{ profitNote }}</div>
      </div>
    </div>

    <div v-if="data" class="shipped-gp-basis" data-testid="shipped-gp-basis">
      <div>{{ data.revenueBasis }}</div>
      <div>{{ data.costBasis }}</div>
      <div>下方「毛利润 / 净利润」按已上传的财务 / 销售报表计算，仅作补充对照。</div>
    </div>
  </el-card>
</template>

<style scoped>
.shipped-gp-card { margin-bottom: 16px; }
.shipped-gp-head { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.shipped-gp-title { font-weight: 600; font-size: 15px; }
.shipped-gp-period { color: var(--el-text-color-secondary); font-size: 12px; }
.shipped-gp-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; }
.shipped-gp-label { color: var(--el-text-color-secondary); font-size: 13px; }
.shipped-gp-value { font-size: 22px; font-weight: 600; margin: 4px 0; font-variant-numeric: tabular-nums; }
.shipped-gp-value small { font-size: 12px; font-weight: 400; margin-left: 2px; }
.shipped-gp-sub { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.shipped-gp-error { color: var(--el-color-danger); font-size: 13px; }
.shipped-gp-basis { margin-top: 12px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
</style>
