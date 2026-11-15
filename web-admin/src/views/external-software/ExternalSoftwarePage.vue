<script setup lang="ts">
/**
 * P10 外部软件对照（MIGRATION-PLAN 第四块，DECISIONS 十八 M6）。只读：外部数据时点 + 报工对照列。
 * 只在平台授权了外部软件接口的工厂出现（菜单 / 路由 / 后端三处同一判据：feature-config integrations 行 authorized === true，
 * 后端再按 factoryId 校验授权行）。外部数据是本机每日采集后推来的只读快照，不改任何一边、不回写外部系统。
 * 有授权但没数据：写「外部数据未接入（找平台）」，⛔ 不显示 0。超过 36 小时没新采集：页头标出。
 * 组件: Nuxt UI (UTable / UBadge / UAlert / UButton) + --cs-*。
 */
import { computed, onMounted, ref } from 'vue';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
import { useAuthStore } from '@/store/modules/auth';
import {
  getReportComparison,
  listFactoryIntegrations,
  type ComparisonRow,
  type ExternalIntegrationView,
  type MatchState,
  type ReportComparison,
} from '@/api/externalIntegrations';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId || '');

const STATE_LABEL: Record<MatchState, string> = {
  MATCH: '一致',
  DIFF: '有差异',
  UNIT_MISMATCH: '单位不同，不比较',
  EXTERNAL_ONLY: 'Cretas 没有记录',
  CRETAS_ONLY: '外部没有记录',
};
const STATE_TAG: Record<MatchState, ElementTagType> = {
  MATCH: 'success', DIFF: 'danger', UNIT_MISMATCH: 'warning', EXTERNAL_ONLY: 'warning', CRETAS_ONLY: 'info',
};
const OWNER = '车间（报工）';

const loading = ref(false);
const error = ref('');
const integrations = ref<ExternalIntegrationView[]>([]);
const comparison = ref<ReportComparison | null>(null);
const stateFilter = ref<'ALL' | MatchState>('ALL');

const blacklake = computed(() => integrations.value.find((i) => i.system === 'blacklake') ?? null);
const label = computed(() => blacklake.value?.label ?? '外部');
const counts = computed(() => {
  const out: Record<MatchState, number> = { MATCH: 0, DIFF: 0, UNIT_MISMATCH: 0, EXTERNAL_ONLY: 0, CRETAS_ONLY: 0 };
  for (const r of comparison.value?.rows ?? []) out[r.state] += 1;
  return out;
});
const visibleRows = computed(() => (comparison.value?.rows ?? [])
  .filter((r) => stateFilter.value === 'ALL' || r.state === stateFilter.value));

const columns = computed<TableColumn<ComparisonRow>[]>(() => [
  { accessorKey: 'reportDate', header: '报工日' },
  { accessorKey: 'productName', header: '产品' },
  { id: 'process', header: '工序' },
  { id: 'cretas', header: 'Cretas 称重良品（主来源）' },
  { id: 'external', header: `${label.value}报工良品（外部）` },
  { id: 'diff', header: '差（Cretas − 外部）' },
  { id: 'state', header: '对照结果' },
  { id: 'owner', header: '找谁核' },
]);

function qty(v: number | null, unit: string | null): string {
  if (v == null) return '没有记录';
  return `${Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 3 })} ${unit ?? ''}`.trim();
}
function diff(r: ComparisonRow): string {
  if (r.difference == null) return '—';
  const n = Number(r.difference);
  return `${n > 0 ? '+' : ''}${n.toLocaleString('zh-CN', { maximumFractionDigits: 3 })}`;
}
function owner(r: ComparisonRow): string {
  if (r.state === 'MATCH') return '';
  if (r.state === 'EXTERNAL_ONLY') return `${OWNER}：Cretas 没有记录`;
  if (r.state === 'CRETAS_ONLY') return `${OWNER}：${label.value}没有记录`;
  return OWNER;
}
function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback;
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  error.value = '';
  try {
    const list = await listFactoryIntegrations(factoryId.value);
    if (!list.success) throw new Error(list.message || '外部软件授权读取失败');
    integrations.value = list.data ?? [];
    if (blacklake.value) {
      const c = await getReportComparison(factoryId.value, 'blacklake');
      if (!c.success) throw new Error(c.message || '报工对照读取失败');
      comparison.value = c.data;
    } else {
      comparison.value = null;
    }
  } catch (e) {
    error.value = errorText(e, '外部软件对照读取失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page" data-testid="external-software-page">
    <div class="cs-page-head">
      <div>
        <h2>外部软件对照</h2>
        <p>
          Cretas 称重是主来源；{{ label }}数据是每天采集后推来的只读快照，只做对照，两边不互相覆盖，也不回写{{ label }}。
        </p>
      </div>
      <div class="head-actions">
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="load">刷新</UButton>
      </div>
    </div>

    <UAlert v-if="error" color="error" variant="subtle" :title="error" description="下面的内容不代表没有差异" class="mb-3" data-testid="load-error" />

    <template v-if="blacklake">
      <section class="cs-card" data-testid="source-card">
        <header class="cs-card-head">
          <span>{{ blacklake.label }}</span>
          <UBadge color="success" variant="soft">已授权</UBadge>
          <span class="muted">授权时间 {{ blacklake.authorizedAt ?? '未知' }}</span>
        </header>
        <div class="body">
          <p v-if="blacklake.connected" data-testid="as-of">
            外部数据时点：{{ blacklake.lastCollectedAt }}（北京时间）
            <UBadge v-if="blacklake.stale" color="error" variant="soft" data-testid="stale">超过 36 小时没有新采集，数据可能过期，找平台核对采集任务</UBadge>
          </p>
          <UAlert
            v-else
            color="warning"
            variant="subtle"
            :title="blacklake.reason ?? '外部数据未接入（找平台）'"
            description="平台已授权，但还没有收到外部数据；对照列暂不显示数字。"
            data-testid="not-connected"
          />
          <p v-for="d in blacklake.datasets" :key="d.dataset" class="muted">
            {{ d.label }}：{{ d.rowCount }} 行汇总，采集于 {{ d.collectedAt }}（北京时间）
          </p>
        </div>
      </section>

      <section v-if="comparison && comparison.available" class="cs-card" data-testid="comparison-card">
        <header class="cs-card-head">
          <span>报工对照（{{ comparison.from }} 至 {{ comparison.to }}，按报工日 × 产品 × 工序）</span>
          <span class="muted">外部数据时点：{{ comparison.asOf }}（北京时间）</span>
          <UBadge v-if="comparison.stale" color="error" variant="soft">外部数据超过 36 小时未更新</UBadge>
        </header>
        <UAlert v-if="comparison.cretasTruncated" color="warning" variant="subtle" class="m-3"
                title="Cretas 一侧记录过多，只比了最近的一部分；没列出的不代表一致" />
        <div class="filters">
          <UButton size="sm" :variant="stateFilter === 'ALL' ? 'solid' : 'outline'" color="neutral" @click="stateFilter = 'ALL'">
            全部 {{ comparison.rows.length }}
          </UButton>
          <UButton v-for="(n, s) in counts" :key="s" size="sm" :variant="stateFilter === s ? 'solid' : 'outline'" color="neutral"
                   :data-testid="`filter-${s}`" @click="stateFilter = s">
            {{ STATE_LABEL[s] }} {{ n }}
          </UButton>
        </div>
        <UTable :data="visibleRows" :columns="columns" :loading="loading" class="cs-table" data-testid="comparison-table">
          <template #empty>这个范围里没有报工记录</template>
          <template #process-cell="{ row }">{{ row.original.processName || '工序未填' }}</template>
          <template #cretas-cell="{ row }">
            <span :class="{ missing: row.original.cretasGoodQty == null }">{{ qty(row.original.cretasGoodQty, row.original.cretasUnit) }}</span>
          </template>
          <template #external-cell="{ row }">
            <span :class="{ missing: row.original.externalGoodQty == null }" data-testid="external-col">{{ qty(row.original.externalGoodQty, row.original.externalUnit) }}</span>
          </template>
          <template #diff-cell="{ row }">{{ diff(row.original) }}</template>
          <template #state-cell="{ row }">
            <UBadge :color="badgeColor(STATE_TAG[row.original.state as MatchState])" variant="soft">{{ STATE_LABEL[row.original.state as MatchState] }}</UBadge>
          </template>
          <template #owner-cell="{ row }">{{ owner(row.original) }}</template>
        </UTable>
      </section>
    </template>

    <UAlert
      v-else-if="!loading && !error"
      color="neutral"
      variant="subtle"
      title="本厂没有开通外部软件接口授权"
      description="外部软件对接由平台在「工厂管理」里开通；没有开通时，所有页面都只用 Cretas 自己的数据。"
      data-testid="no-authorization"
    />
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.head-actions { display: flex; align-items: center; gap: var(--cs-space-3); }
.body { padding: var(--cs-space-3) var(--cs-space-5); display: flex; flex-direction: column; gap: var(--cs-space-2); }
.body p { margin: 0; color: var(--cs-text); }
.muted { color: var(--cs-muted); font-weight: 400; }
.filters { display: flex; flex-wrap: wrap; gap: var(--cs-space-2); padding: var(--cs-space-3) var(--cs-space-5) 0; }
.missing { color: var(--cs-muted); }
</style>
