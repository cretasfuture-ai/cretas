<script setup lang="ts">
import { CompatDatePicker, CompatDescriptions, CompatDescriptionsItem, CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

/**
 * 冷链超温批次 (食品 DAG 缺口 15「温度关联批次」, Steve 2026-11-03: 先做软件侧)。
 *
 * 一条超温告警 → 算出超温时段 → 按设备当时所在的仓库, 找出这段时间在库的批次 (原料 / 在制半成品 / 成品)
 * 和各自暴露了多久 → 质检员勾选后一键转质检 (原料「质检中」, 在制/成品进「质检放行」的待检)。
 *
 * 防呆:
 *   - Rule 1 事前告知: 不能转的批次直接禁选, 并在行内写明为什么 (已在待检 / 已出库 / 状态不对)
 *   - Rule 2 上下文: 转质检确认框列出设备、超温时段、每个批次号和暴露时长
 *   - 在库时间拿不到精确时刻的批次如实标「在库时间不确定」并给出最多暴露多久, 不猜
 *   - 设备没登记库位时不显示「无受影响批次」, 而是提示去「设备库位」登记 (Rule 5)
 */
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable';
import iconColdChain from '@/assets/quality/icon-cold-chain.webp';
import { useAuthStore } from '@/store/modules/auth';
import { handleCatchError } from '@/utils/errorToast';
import { listWarehouses, type FactoryWarehouse } from '@/api/factoryWarehouse';
import {
  assignColdChainLocation,
  getColdChainAffectedBatches,
  holdColdChainBatches,
  listColdChainAlerts,
  listColdChainEquipment,
  listColdChainLocations,
  type ColdChainAffectedBatch,
  type ColdChainAffectedReport,
  type ColdChainAlertView,
  type ColdChainEquipmentView,
  type ColdChainLocationView,
} from '@/api/coldChain';

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId || '');

const activeTab = ref<'alerts' | 'equipment'>('alerts');

// ------------------------------------------------------------ 告警
const alerts = ref<ColdChainAlertView[]>([]);
const alertsLoading = ref(false);
const alertsError = ref<string | null>(null);

async function loadAlerts() {
  if (!factoryId.value) return;
  alertsLoading.value = true;
  alertsError.value = null;
  try {
    const res = await listColdChainAlerts(factoryId.value, 90);
    alerts.value = res.success && Array.isArray(res.data) ? res.data : [];
    if (!res.success) alertsError.value = res.message || '告警加载失败';
  } catch (e) {
    alertsError.value = '告警加载失败，请点「刷新」重试';
    handleCatchError(e, '告警加载失败');
  } finally {
    alertsLoading.value = false;
  }
}

const C = { class: { th: 'text-center', td: 'text-center' } };
const ALERT_COLUMNS: TableColumn<ColdChainAlertView>[] = [
  { accessorKey: 'alertTime', header: '告警时间' },
  { id: 'equipment', header: '设备' },
  { id: 'severity', header: '级别' },
  { id: 'temp', header: '超温读数' },
  { id: 'status', header: '状态' },
  { accessorKey: 'heldBatchCount', header: '已转质检', meta: C },
  { id: 'actions', header: '操作', meta: C },
];
const EQUIPMENT_COLUMNS: TableColumn<ColdChainEquipmentView>[] = [
  { id: 'equipment', header: '设备' },
  { id: 'target', header: '目标温度' },
  { id: 'warehouse', header: '现在所在仓库' },
  { accessorKey: 'currentSince', header: '从何时起' },
  { id: 'actions', header: '操作', meta: C },
];
const HISTORY_COLUMNS: TableColumn<ColdChainLocationView>[] = [
  { accessorKey: 'warehouseName', header: '仓库' },
  { accessorKey: 'effectiveFrom', header: '开始' },
  { id: 'end', header: '结束' },
  { accessorKey: 'remark', header: '备注' },
];
const tabItems = computed<TabsItem[]>(() => [
  { label: `超温告警 (${alerts.value.length})`, value: 'alerts', slot: 'alerts' },
  { label: t('quality.cold_chain_index.label_92a4b9108e54'), value: 'equipment', slot: 'equipment' },
]);
const STATUS_LABELS: Record<string, string> = { OPEN: '未处理', ACKNOWLEDGED: '已确认', RESOLVED: '已解决' };

// ------------------------------------------------------------ 受影响批次
const drawerVisible = ref(false);
const report = ref<ColdChainAffectedReport | null>(null);
const reportLoading = ref(false);
const reportError = ref<string | null>(null);
const selected = ref<ColdChainAffectedBatch[]>([]);
const holding = ref(false);

async function openAlert(alertId: number) {
  drawerVisible.value = true;
  report.value = null;
  reportError.value = null;
  selected.value = [];
  reportLoading.value = true;
  try {
    const res = await getColdChainAffectedBatches(factoryId.value, alertId);
    if (res.success && res.data) report.value = res.data;
    else reportError.value = res.message || '受影响批次加载失败';
  } catch (e) {
    reportError.value = (e as { message?: string })?.message || '受影响批次加载失败';
    handleCatchError(e, '受影响批次加载失败');
  } finally {
    reportLoading.value = false;
  }
}

function exposureText(b: ColdChainAffectedBatch): string {
  return b.verdict === 'AFFECTED' ? `${b.exposureMinutes} 分钟` : `不确定，最多 ${b.maxExposureMinutes} 分钟`;
}

function qty(b: ColdChainAffectedBatch): string {
  return b.quantityNow == null ? '—' : `${Number(b.quantityNow)} ${b.unit ?? ''}`;
}

const selectable = (b: ColdChainAffectedBatch) => b.holdable && !b.alreadyHeld;

async function holdSelected() {
  const r = report.value;
  if (!r || selected.value.length === 0) return;
  const lines = selected.value
    .map((b) => `${b.formLabel} ${b.batchNumber ?? b.targetId}（${b.itemName ?? '—'}，暴露 ${exposureText(b)}）`)
    .join('\n');
  try {
    await ElMessageBox.confirm(
      `设备 ${r.alert.equipmentName ?? ''}（${r.alert.equipmentCode ?? ''}）${r.excursion.start} ~ ${r.excursion.end} 超温。\n`
        + `下面 ${selected.value.length} 个批次将转为待检，放行前不能领用、分配或发货：\n${lines}`,
      t('quality.cold_chain_index.message_24d4552ef1dc'),
      { confirmButtonText: t('quality.cold_chain_index.confirmbuttontext_2401bd0e7fbf'), cancelButtonText: t('quality.cold_chain_index.cancelbuttontext_2cd0f3be8738'), type: 'warning', customStyle: { whiteSpace: 'pre-line' } },
    );
  } catch {
    return;
  }
  holding.value = true;
  try {
    const res = await holdColdChainBatches(factoryId.value, r.alert.id,
      selected.value.map((b) => ({ targetType: b.targetType, targetId: b.targetId })));
    if (res.success && Array.isArray(res.data)) {
      const held = res.data.filter((o) => o.outcome === 'HELD').length;
      const others = res.data.filter((o) => o.outcome !== 'HELD');
      ElMessage.success(`已转质检 ${held} 个批次${others.length ? `；${others.length} 个未转：${others.map((o) => `${o.batchNumber ?? o.targetId} ${o.message}`).join('；')}` : ''}`);
      await Promise.all([openAlert(r.alert.id), loadAlerts()]);
    }
  } catch (e) {
    handleCatchError(e, '转质检失败，请重试');
  } finally {
    holding.value = false;
  }
}

// ------------------------------------------------------------ 设备库位
const equipment = ref<ColdChainEquipmentView[]>([]);
const equipmentLoading = ref(false);
const warehouses = ref<FactoryWarehouse[]>([]);
const historyFor = ref<ColdChainEquipmentView | null>(null);
const history = ref<ColdChainLocationView[]>([]);

const assignVisible = ref(false);
const assignTarget = ref<ColdChainEquipmentView | null>(null);
const assignWarehouseId = ref('');
const assignFrom = ref<string | null>(null);
const assignRemark = ref('');
const assigning = ref(false);

async function loadEquipment() {
  if (!factoryId.value) return;
  equipmentLoading.value = true;
  try {
    const [eq, wh] = await Promise.all([listColdChainEquipment(factoryId.value), listWarehouses(factoryId.value)]);
    equipment.value = eq.success && Array.isArray(eq.data) ? eq.data : [];
    warehouses.value = wh.success && Array.isArray(wh.data) ? wh.data : [];
  } catch (e) {
    handleCatchError(e, '冷链设备加载失败');
  } finally {
    equipmentLoading.value = false;
  }
}

async function showHistory(eq: ColdChainEquipmentView) {
  historyFor.value = eq;
  try {
    const res = await listColdChainLocations(factoryId.value, eq.id);
    history.value = res.success && Array.isArray(res.data) ? res.data : [];
  } catch (e) {
    handleCatchError(e, '库位历史加载失败');
  }
}

function openAssign(eq: ColdChainEquipmentView) {
  assignTarget.value = eq;
  assignWarehouseId.value = '';
  assignFrom.value = null;
  assignRemark.value = '';
  assignVisible.value = true;
}

const assignDisabledReason = computed(() => {
  if (!assignWarehouseId.value) return '请选择仓库';
  if (assignTarget.value?.currentWarehouseId === assignWarehouseId.value) return '设备现在就在这个仓库';
  return null;
});

async function submitAssign() {
  if (!assignTarget.value || assignDisabledReason.value) return;
  assigning.value = true;
  try {
    const res = await assignColdChainLocation(factoryId.value, assignTarget.value.id, {
      warehouseId: assignWarehouseId.value,
      effectiveFrom: assignFrom.value,
      remark: assignRemark.value.trim() || null,
    });
    if (res.success) {
      ElMessage.success(`已登记：${assignTarget.value.equipmentName} 从 ${res.data.effectiveFrom} 起在 ${res.data.warehouseName ?? ''}`);
      assignVisible.value = false;
      await loadEquipment();
      if (historyFor.value?.id === assignTarget.value.id) await showHistory(assignTarget.value);
    }
  } catch (e) {
    handleCatchError(e, '登记失败，请重试');
  } finally {
    assigning.value = false;
  }
}

function goEquipmentTab() {
  drawerVisible.value = false;
  activeTab.value = 'equipment';
}

onMounted(async () => {
  await Promise.all([loadAlerts(), loadEquipment()]);
  const q = Number(route.query.alertId);
  if (Number.isFinite(q) && q > 0) await openAlert(q);
});
</script>

<template>
  <div class="cold-chain">
    <div class="cs-page-head">
      <div class="head-main">
        <img :src="iconColdChain" alt="" class="head-icon" width="56" height="56">
        <div>
          <h2>{{ t('quality.cold_chain_index.text_04e7c526d2c4') }}</h2>
          <p>{{ t('quality.cold_chain_index.text_7427e7a1af69') }}</p>
        </div>
      </div>
      <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="alertsLoading || equipmentLoading" @click="loadAlerts(); loadEquipment()">{{ t('quality.cold_chain_index.action_aee887434131') }}</UButton>
    </div>

    <section class="cs-card">
      <UTabs v-model="activeTab" :items="tabItems" variant="link" :ui="{ content: 'pt-0' }">
        <template #alerts>
          <UAlert v-if="alertsError" :title="alertsError" color="error" variant="subtle" icon="i-lucide-circle-alert" class="m-3" />
          <UTable :data="alerts" :columns="ALERT_COLUMNS" :loading="alertsLoading" class="cs-table" data-testid="cc-alert-table">
            <template #empty><div class="cs-empty-art"><img :src="iconColdChain" alt="" width="96"><span>{{ t('quality.cold_chain_index.text_4417f37078d4') }}</span></div></template>
            <template #equipment-cell="{ row }">{{ row.original.equipmentName || '—' }}（{{ row.original.equipmentCode || row.original.equipmentId }}）</template>
            <template #severity-cell="{ row }">
              <UBadge :color="row.original.severity === 'CRITICAL' ? 'error' : 'warning'" variant="soft">{{ row.original.severity === 'CRITICAL' ? t('quality.cold_chain_index.text_73eb0e14e307') : t('quality.cold_chain_index.text_a8b7a4480407') }}</UBadge>
            </template>
            <template #temp-cell="{ row }"><span class="num">{{ row.original.minTemp }} ~ {{ row.original.maxTemp }} ℃</span></template>
            <template #status-cell="{ row }">{{ STATUS_LABELS[row.original.status] ?? row.original.status }}</template>
            <template #actions-cell="{ row }">
              <UButton size="xs" :data-testid="`cc-open-${row.original.id}`" @click="openAlert(row.original.id)">{{ t('quality.cold_chain_index.action_b32c85c2193c') }}</UButton>
            </template>
          </UTable>
        </template>

        <template #equipment>
          <p class="muted hint">
            {{ t('quality.cold_chain_index.text_16904f58e4e0') }}
          </p>
          <UTable :data="equipment" :columns="EQUIPMENT_COLUMNS" :loading="equipmentLoading" class="cs-table" data-testid="cc-equipment-table">
            <template #empty>{{ t('quality.cold_chain_index.text_ff4499b028b5') }}</template>
            <template #equipment-cell="{ row }">{{ row.original.equipmentName }}（{{ row.original.equipmentCode }}）</template>
            <template #target-cell="{ row }"><span class="num">{{ row.original.targetTempMin }} ~ {{ row.original.targetTempMax }} ℃</span></template>
            <template #warehouse-cell="{ row }">
              <span v-if="row.original.currentWarehouseName">{{ row.original.currentWarehouseName }}</span>
              <UBadge v-else color="error" variant="soft">{{ t('quality.cold_chain_index.text_0db3db44213b') }}</UBadge>
            </template>
            <template #actions-cell="{ row }">
              <UButton size="xs" class="mr-2" @click="openAssign(row.original)">{{ t('quality.cold_chain_index.action_a65749e7acaa') }}</UButton>
              <UButton size="xs" color="neutral" variant="outline" @click="showHistory(row.original)">{{ t('quality.cold_chain_index.action_b0385cfe4b42') }}</UButton>
            </template>
          </UTable>
          <div v-if="historyFor" class="history">
            <h4>{{ historyFor.equipmentName }} {{ t('quality.cold_chain_index.text_ec952657e9b1') }}</h4>
            <UTable :data="history" :columns="HISTORY_COLUMNS" class="cs-table">
              <template #empty>{{ t('quality.cold_chain_index.text_3c96e5f1dd59') }}</template>
              <template #end-cell="{ row }">{{ row.original.effectiveTo ?? t('quality.cold_chain_index.text_fe985eafa4a9') }}</template>
            </UTable>
          </div>
        </template>
      </UTabs>
    </section>

    <el-drawer v-model="drawerVisible" size="80%" :title="t('quality.cold_chain_index.title_833d08a14e5f')" data-testid="cc-drawer">
      <div v-loading="reportLoading">
        <el-alert v-if="reportError" :title="reportError" type="error" :closable="false" show-icon class="mb" />
        <template v-if="report">
          <CompatDescriptions :column="3" border size="small" class="mb">
            <CompatDescriptionsItem :label="t('quality.cold_chain_index.label_e1506406a5bd')">{{ report.alert.equipmentName }}（{{ report.alert.equipmentCode }}）</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('quality.cold_chain_index.label_49b8d682252e')">
              {{ report.excursion.start }} ~ {{ report.excursion.end }}（{{ report.excursion.minutes }} {{ t('quality.cold_chain_index.text_bd957bc497aa') }}{{ report.excursion.ongoing ? t('quality.cold_chain_index.text_0f9d0e8221b7') : '' }}）
            </CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('quality.cold_chain_index.label_3732e264dfa9')">
              {{ t('quality.cold_chain_index.text_c1f1b497d395') }} {{ report.excursion.minTemp }} ~ {{ report.excursion.maxTemp }} {{ t('quality.cold_chain_index.text_e608d4ff1083') }} {{ report.excursion.targetTempMin }} ~ {{ report.excursion.targetTempMax }} ℃
            </CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('quality.cold_chain_index.label_c3ea4f9e0a93')" :span="3">
              <span v-for="s in report.segments" :key="s.warehouseId + s.start" class="seg">
                {{ s.warehouseName ?? s.warehouseId }}：{{ s.start }} ~ {{ s.end }}（{{ s.minutes }} {{ t('quality.cold_chain_index.text_40fce7ced6e7') }}
              </span>
              <span v-if="report.segments.length === 0" class="danger">{{ t('quality.cold_chain_index.text_0db3db44213b') }}</span>
            </CompatDescriptionsItem>
          </CompatDescriptions>
          <el-alert v-if="report.note" type="warning" :closable="false" show-icon class="mb" :title="report.note">
            <UButton variant="link" color="primary" @click="goEquipmentTab">{{ t('quality.cold_chain_index.action_8ef5203fcd70') }}</UButton>
          </el-alert>
          <div class="summary mb">
            <span>{{ t('quality.cold_chain_index.text_d1f87b8b3d6e') }} <b>{{ report.affectedCount }}</b> {{ t('quality.cold_chain_index.text_3a116815bc9d') }}</span>
            <span>{{ t('quality.cold_chain_index.text_2455f02f6337') }} <b>{{ report.uncertainCount }}</b> {{ t('quality.cold_chain_index.text_3a116815bc9d') }}</span>
            <span class="muted">{{ t('quality.cold_chain_index.text_e4eae0cccfcb') }}</span>
          </div>
          <CompatTable :data="report.batches" stripe :empty-text="t('quality.cold_chain_index.empty_text_d9c119bb56ec')"
                    data-testid="cc-affected-table" @selection-change="(v: ColdChainAffectedBatch[]) => (selected = v)">
            <CompatTableColumn type="selection" width="44" :selectable="selectable" />
            <CompatTableColumn :label="t('quality.cold_chain_index.label_b81375e09f24')" width="110">
              <template #default="{ row }">
                <el-tag :type="row.targetType === 'FINISHED_GOODS' ? 'success' : row.targetType === 'WIP_MATERIAL_BATCH' ? 'info' : ''">{{ row.formLabel }}</el-tag>
              </template>
            </CompatTableColumn>
            <CompatTableColumn prop="batchNumber" :label="t('quality.cold_chain_index.label_cb5386f1a98a')" min-width="170" show-overflow-tooltip />
            <CompatTableColumn prop="itemName" :label="t('quality.cold_chain_index.label_7d432cf3863b')" min-width="130" show-overflow-tooltip />
            <CompatTableColumn prop="warehouseName" :label="t('quality.cold_chain_index.label_89b2ecbe1f09')" width="110" />
            <CompatTableColumn :label="t('quality.cold_chain_index.label_77a5541fefbd')" min-width="230">
              <template #default="{ row }">{{ row.inStockFrom }} → {{ row.inStockUntil }}</template>
            </CompatTableColumn>
            <CompatTableColumn :label="t('quality.cold_chain_index.label_5036e6958926')" min-width="170">
              <template #default="{ row }">
                <span v-if="row.verdict === 'AFFECTED'" class="danger"><b>{{ row.exposureMinutes }}</b> {{ t('quality.cold_chain_index.text_bd957bc497aa') }}</span>
                <el-tooltip v-else :content="row.uncertainReason ?? '在库时间不确定'" placement="top">
                  <UBadge variant="soft" color="warning">{{ t('quality.cold_chain_index.text_a3478dafcb52') }} {{ row.maxExposureMinutes }} {{ t('quality.cold_chain_index.text_bd957bc497aa') }}</UBadge>
                </el-tooltip>
              </template>
            </CompatTableColumn>
            <CompatTableColumn :label="t('quality.cold_chain_index.label_b7e9a7c29419')" width="110" align="right">
              <template #default="{ row }">{{ qty(row) }}</template>
            </CompatTableColumn>
            <CompatTableColumn :label="t('quality.cold_chain_index.label_8a4bd8a4a5a9')" min-width="150">
              <template #default="{ row }">
                <UBadge variant="soft" v-if="row.alreadyHeld" color="success">{{ t('quality.cold_chain_index.text_0c77409730e3') }}</UBadge>
                <span v-else-if="!row.holdable" class="muted">{{ row.holdBlockedReason }}</span>
                <span v-else class="muted">{{ t('quality.cold_chain_index.text_e01ab10129c6') }}</span>
              </template>
            </CompatTableColumn>
          </CompatTable>
        </template>
      </div>
      <template #footer>
        <span v-if="report && selected.length === 0" class="muted footer-tip">{{ t('quality.cold_chain_index.text_0a3956bf7bd3') }}</span>
        <UButton color="neutral" variant="outline" @click="drawerVisible = false">{{ t('quality.cold_chain_index.action_3fd47edce45b') }}</UButton>
        <UButton color="error" :disabled="selected.length === 0" :loading="holding" data-testid="cc-hold" @click="holdSelected">
          {{ t('quality.cold_chain_index.action_3f6809b51ff9') }}{{ selected.length }})
        </UButton>
        <UButton variant="link" color="primary" @click="router.push('/quality/release')">{{ t('quality.cold_chain_index.action_806d24e29f42') }}</UButton>
      </template>
    </el-drawer>

    <CompatDialog v-model="assignVisible" :title="`登记库位 — ${assignTarget?.equipmentName ?? ''}（${assignTarget?.equipmentCode ?? ''}）`" width="480px">
      <CompatForm label-position="top">
        <CompatFormItem :label="t('quality.cold_chain_index.label_d263f048cc68')">
          {{ assignTarget?.currentWarehouseName ?? t('quality.cold_chain_index.text_0db3db44213b') }}<span v-if="assignTarget?.currentSince" class="muted">{{ t('quality.cold_chain_index.text_8a54749f7246') }} {{ assignTarget.currentSince }}）</span>
        </CompatFormItem>
        <CompatFormItem :label="t('quality.cold_chain_index.label_b3a4021d7dba')" required>
          <CompatSelect v-model="assignWarehouseId" :placeholder="t('quality.cold_chain_index.placeholder_1c25426ef7e8')" style="width: 100%" data-testid="cc-assign-warehouse">
            <CompatOption v-for="w in warehouses" :key="w.id" :label="`${w.name}（${w.code}）`" :value="w.id"
                       :disabled="w.id === assignTarget?.currentWarehouseId" />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('quality.cold_chain_index.label_cd1d8e6981e4')">
          <CompatDatePicker v-model="assignFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          :placeholder="t('quality.cold_chain_index.placeholder_43584f37c1f4')" style="width: 100%" />
          <div class="form-tip">{{ t('quality.cold_chain_index.text_b113aa3843a4') }}</div>
        </CompatFormItem>
        <CompatFormItem :label="t('quality.cold_chain_index.label_daede9881787')">
          <UInput v-model="assignRemark" maxlength="500" :placeholder="t('quality.cold_chain_index.placeholder_08d1f416bd70')" />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <span v-if="assignDisabledReason" class="disabled-reason">{{ assignDisabledReason }}</span>
        <UButton color="neutral" variant="outline" @click="assignVisible = false">{{ t('quality.cold_chain_index.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="assigning" :disabled="!!assignDisabledReason" @click="submitAssign">{{ t('quality.cold_chain_index.action_c10ad85c5c9f') }}</UButton>
      </template>
    </CompatDialog>
  </div>
</template>
<style scoped>
.cold-chain { padding: var(--cs-space-4); }
.head-main { flex: 1; min-width: 0; display: flex; gap: var(--cs-space-3); align-items: center; }
.head-icon { flex: none; object-fit: contain; }
.muted { color: var(--cs-muted); font-size: 13px; }
.hint { margin: 0; padding: var(--cs-space-3) var(--cs-space-5); }
.danger { color: var(--cs-danger); }
.mb { margin-bottom: var(--cs-space-3); }
.seg { margin-right: var(--cs-space-4); }
.summary { display: flex; gap: var(--cs-space-5); align-items: center; font-size: 14px; }
.history { padding: var(--cs-space-4) var(--cs-space-5); }
.history h4 { margin: 0 0 var(--cs-space-2); }
.form-tip { font-size: 12px; color: var(--cs-muted); line-height: 1.5; margin-top: 4px; }
.footer-tip { margin-right: var(--cs-space-3); }
.disabled-reason { color: var(--cs-danger); font-size: var(--cs-font-small); margin-right: var(--cs-space-3); }
</style>
