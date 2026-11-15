<script setup lang="ts">
/**
 * 交付风险: 扫描未交订单, 有风险的成为处理实例, 走 证据 → 影响 → 处理建议 → 负责人 → 处理结果。
 * 「无法判断」单独列出, 不算正常。这里只记录任务和结果, 排程、采购到各自页面处理。
 * 组件: Nuxt UI (UTable / UBadge / USlideover / USelectMenu / UTextarea / UButton) + --cs-*; 详情抽屉整体是 USlideover。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import type { TableColumn } from '@nuxt/ui';
import { badgeColor } from '@/utils/csBadge';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listDeliveryRisks,
  scanDeliveryRisks,
  getDeliveryRisk,
  listAssignees,
  assignDeliveryRisk,
  recheckDeliveryRisk,
  finishDeliveryRisk,
  type Assignee,
  type DeliveryRiskCase,
  type DeliveryRiskDetail,
  type RiskLevel,
  type ScanResult,
} from '@/api/deliveryRisks';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
// 与后端写接口的 @RequirePermission 同一份口径
const canWrite = computed(() => permissionStore.canWrite('production') || permissionStore.canWrite('sales'));

const LEVEL_LABEL: Record<RiskLevel, string> = { RISK: '有风险', UNKNOWN: '无法判断', OK: '正常' };
const LEVEL_TAG: Record<RiskLevel, 'danger' | 'warning' | 'success'> = { RISK: 'danger', UNKNOWN: 'warning', OK: 'success' };
const ROLE_LABEL: Record<string, string> = { production_manager: '生产', procurement_manager: '采购' };

const scanColumns: TableColumn<ScanResult['orders'][number]>[] = [
  { accessorKey: 'orderNumber', header: '订单' },
  { id: 'due', header: '交期' },
  { id: 'level', header: '结论' },
  { id: 'note', header: '说明' },
];
const caseColumns: TableColumn<DeliveryRiskCase>[] = [
  { id: 'order', header: '订单' },
  { id: 'customer', header: '客户' },
  { id: 'due', header: '交期' },
  { id: 'level', header: '当前结论' },
  { accessorKey: 'stageLabel', header: '阶段' },
  { id: 'owner', header: '负责人' },
  { id: 'assessed', header: '最近评估' },
];
function onSelect(_: Event, r: { original: DeliveryRiskCase }) {
  openDetail(r.original);
}

const openOnly = ref(true);
const loading = ref(false);
const loadFailed = ref(false);
const rows = ref<DeliveryRiskCase[]>([]);
const scanning = ref(false);
const scan = ref<ScanResult | null>(null);

const detailVisible = ref(false);
const detail = ref<DeliveryRiskDetail | null>(null);
const assignees = ref<Assignee[]>([]);
const ownerUserId = ref<number | null>(null);
const task = ref('');
const result = ref('');
const busy = ref(false);
const isOpen = computed(() => !!detail.value && detail.value.case.closedAt == null);

function time(v: string | null) {
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
    const res = await listDeliveryRisks(factoryId.value, openOnly.value);
    if (res.success) {
      rows.value = res.data ?? [];
    } else {
      loadFailed.value = true;
      ElMessage.error(res.message || '交付风险加载失败');
    }
  } catch (e) {
    loadFailed.value = true;
    ElMessage.error(errorText(e, '交付风险加载失败，请稍后重试'));
  } finally {
    loading.value = false;
  }
}

async function runScan() {
  scanning.value = true;
  try {
    const res = await scanDeliveryRisks(factoryId.value);
    if (res.success) {
      scan.value = res.data;
      await load();
    } else {
      ElMessage.error(res.message || '扫描失败');
    }
  } catch (e) {
    ElMessage.error(errorText(e, '扫描失败，请稍后重试'));
  } finally {
    scanning.value = false;
  }
}

async function openDetail(row: DeliveryRiskCase) {
  detailVisible.value = true;
  // 同一条实例刷新时保留旧内容, 不让抽屉闪空
  if (detail.value?.case.id !== row.id) detail.value = null;
  task.value = '';
  result.value = '';
  try {
    const [d, a] = await Promise.all([getDeliveryRisk(factoryId.value, row.id), listAssignees(factoryId.value)]);
    if (d.success) {
      detail.value = d.data;
      // 负责人以服务端最新值为准: 传进来的 row 可能是指派之前的
      ownerUserId.value = d.data.case.ownerUserId;
    } else ElMessage.error(d.message || '详情加载失败');
    assignees.value = a.success ? a.data ?? [] : [];
  } catch (e) {
    ElMessage.error(errorText(e, '详情加载失败'));
  }
}

async function act(run: () => Promise<{ success: boolean; message?: string }>, done: string) {
  if (!detail.value) return;
  const current = detail.value.case;
  busy.value = true;
  try {
    const res = await run();
    if (res.success) {
      ElMessage.success(done);
      await Promise.all([openDetail(current), load()]);
    } else {
      ElMessage.error(res.message || '操作失败');
    }
  } catch (e) {
    ElMessage.error(errorText(e, '操作失败，请稍后重试'));
  } finally {
    busy.value = false;
  }
}
const assign = () => act(() => assignDeliveryRisk(factoryId.value, detail.value!.case.id, ownerUserId.value!, task.value), '已指派');
const recheck = () => act(() => recheckDeliveryRisk(factoryId.value, detail.value!.case.id), '已按当前数据复核');
const finish = (d: 'CLOSE' | 'CANCEL') => act(() => finishDeliveryRisk(factoryId.value, detail.value!.case.id, d, result.value), d === 'CLOSE' ? '已关闭' : '已取消');

onMounted(load);
</script>

<template>
  <div class="page">
    <div class="cs-page-head">
      <div>
        <h2>交付风险</h2>
        <p>按交期、计划、入库进度、工序和物料找出可能延期的订单。这里只记录任务和结果，不改排程、不下采购。</p>
      </div>
      <div class="head-actions">
        <UCheckbox v-model="openOnly" label="只看未结束" @update:model-value="load" />
        <UButton v-if="canWrite" icon="i-lucide-refresh-cw" :loading="scanning" data-testid="scan" @click="runScan">扫描未交订单</UButton>
      </div>
    </div>

    <UAlert v-if="loadFailed" color="error" variant="subtle" title="加载失败，下面的列表不代表没有风险" class="mb-3" />

    <section v-if="scan" class="cs-card" data-testid="scan-result">
      <header class="cs-card-head">
        本次扫描 {{ scan.scanned }} 张：有风险 {{ scan.risk }}，无法判断 {{ scan.unknown }}，正常 {{ scan.ok }}；新建 {{ scan.created }}，更新 {{ scan.refreshed }}
      </header>
      <UAlert v-if="scan.truncated" color="warning" variant="subtle" title="订单过多，只扫描了最新的一部分" class="m-3" />
      <UTable :data="scan.orders" :columns="scanColumns" class="cs-table">
        <template #due-cell="{ row }">{{ row.original.dueDate ?? '未知' }}</template>
        <template #level-cell="{ row }"><UBadge :color="badgeColor(LEVEL_TAG[row.original.level as RiskLevel])" variant="soft">{{ LEVEL_LABEL[row.original.level as RiskLevel] }}</UBadge></template>
        <template #note-cell="{ row }">{{ [...row.original.signals.map((s: { label: string }) => s.label), ...row.original.unknowns].join('；') || '排程或产能显示能按期' }}</template>
      </UTable>
    </section>

    <section class="cs-card">
      <header class="cs-card-head">处理实例</header>
      <UTable :data="rows" :columns="caseColumns" :loading="loading" class="cs-table cs-clickable" @select="onSelect">
        <template #empty>没有处理实例</template>
        <template #order-cell="{ row }">{{ row.original.orderNumber ?? '订单已不存在' }}</template>
        <template #customer-cell="{ row }">{{ row.original.customerName ?? '未知' }}</template>
        <template #due-cell="{ row }">{{ row.original.dueDate ?? '未知' }}</template>
        <template #level-cell="{ row }">
          <UBadge v-if="row.original.riskLevel" :color="badgeColor(LEVEL_TAG[row.original.riskLevel as RiskLevel])" variant="soft">{{ LEVEL_LABEL[row.original.riskLevel as RiskLevel] }}</UBadge>
          <span v-else>未知</span>
        </template>
        <template #owner-cell="{ row }">{{ row.original.ownerName ?? `待指派（${ROLE_LABEL[row.original.ownerRole] ?? row.original.ownerRole}）` }}</template>
        <template #assessed-cell="{ row }">{{ time(row.original.assessedAt) }}</template>
      </UTable>
    </section>

    <USlideover
      :open="detailVisible"
      side="right"
      :title="detail ? `交付风险 ${detail.case.orderNumber ?? ''}` : '交付风险'"
      description="交付风险处理详情"
      :ui="{ content: 'cs-slide', description: 'sr-only' }"
      @update:open="(o: boolean) => (detailVisible = o)"
    >
      <template #body>
        <template v-if="detail">
          <p>
            <UBadge :color="badgeColor(LEVEL_TAG[detail.assessment.level])" variant="soft" data-testid="detail-level">{{ LEVEL_LABEL[detail.assessment.level] }}</UBadge>
            {{ detail.case.stageLabel }} · 交期 {{ detail.assessment.dueDate ?? '未知' }}
          </p>
          <h4>异常</h4>
          <ul><li v-for="s in detail.assessment.signals" :key="s.code">{{ s.label }}</li><li v-if="!detail.assessment.signals.length">当前没有风险信号</li></ul>
          <h4>证据</h4>
          <ul><li v-for="(t, i) in detail.assessment.evidence" :key="i">{{ t }}</li></ul>
          <template v-if="detail.assessment.unknowns.length">
            <h4>不知道的</h4>
            <ul><li v-for="(t, i) in detail.assessment.unknowns" :key="i">{{ t }}</li></ul>
          </template>
          <h4>影响</h4>
          <ul><li v-for="(t, i) in detail.assessment.impact" :key="i">{{ t }}</li></ul>
          <h4>处理建议</h4>
          <ul><li v-for="(t, i) in detail.assessment.suggestions" :key="i">{{ t }}</li><li v-if="!detail.assessment.suggestions.length">暂无</li></ul>

          <template v-if="isOpen && canWrite">
            <h4>负责人与任务</h4>
            <USelectMenu v-model="ownerUserId" :items="assignees" value-key="id" label-key="name" placeholder="选择负责人" class="w-full" data-testid="owner" />
            <UTextarea v-model="task" :rows="2" placeholder="要做什么" class="gap w-full" data-testid="task" />
            <div class="actions">
              <UButton :loading="busy" :disabled="!ownerUserId || !task.trim()" data-testid="assign" @click="assign">指派</UButton>
              <UButton color="neutral" variant="outline" :loading="busy" data-testid="recheck" @click="recheck">按当前数据复核</UButton>
            </div>

            <h4>处理结果</h4>
            <UTextarea v-model="result" :rows="2" placeholder="做了什么、结果如何" class="w-full" data-testid="result" />
            <div class="actions">
              <UButton color="success" :loading="busy" :disabled="!result.trim()" data-testid="close" @click="finish('CLOSE')">写结果并关闭</UButton>
              <UButton color="neutral" variant="outline" :loading="busy" :disabled="!result.trim()" data-testid="cancel" @click="finish('CANCEL')">误报，取消</UButton>
            </div>
          </template>

          <h4>处理任务与结果</h4>
          <ul>
            <li v-for="a in detail.actions" :key="a.id">{{ time(a.createdAt) }} {{ a.actionType === 'HANDLING_TASK' ? '任务' : '结果' }}：{{ a.reason }}</li>
            <li v-if="!detail.actions.length">暂无</li>
          </ul>
          <h4>结果核对</h4>
          <ul>
            <li v-for="c in detail.checks" :key="c.id">{{ time(c.createdAt) }} {{ c.verdictLabel }}：{{ c.detail }}</li>
            <li v-if="!detail.checks.length">暂无</li>
          </ul>
          <h4>留痕</h4>
          <ul><li v-for="(e, i) in detail.events" :key="i">{{ time(e.createdAt) }} {{ e.detail }}</li></ul>
        </template>
      </template>
    </USlideover>
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.head-actions { display: flex; align-items: center; gap: var(--cs-space-3); }
.gap { margin-top: 8px; }
.actions { display: flex; gap: 8px; margin-top: 8px; }
h4 { margin: var(--cs-space-4) 0 var(--cs-space-1); font-size: var(--cs-type-section); font-weight: 600; color: var(--cs-text); }
ul { margin: 0; padding-left: 1.25em; color: var(--cs-text); }
</style>
