<script setup lang="ts">
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
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
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
      '转质检确认',
      { confirmButtonText: '确认转质检', cancelButtonText: '取消', type: 'warning', customStyle: { whiteSpace: 'pre-line' } },
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
    <div class="page-header">
      <div>
        <h2 class="title">冷链超温批次</h2>
        <p class="subtitle">
          点一条超温告警，查看超温期间存放在这台设备所在仓库里的批次及各自暴露时长，勾选后一键转质检。
        </p>
      </div>
      <el-button :icon="Refresh" :loading="alertsLoading || equipmentLoading" @click="loadAlerts(); loadEquipment()">刷新</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="`超温告警 (${alerts.length})`" name="alerts">
        <el-alert v-if="alertsError" :title="alertsError" type="error" :closable="false" show-icon class="mb" />
        <el-table v-loading="alertsLoading" :data="alerts" stripe empty-text="最近 90 天没有冷链超温告警" data-testid="cc-alert-table">
          <el-table-column prop="alertTime" label="告警时间" min-width="160" />
          <el-table-column label="设备" min-width="180">
            <template #default="{ row }">{{ row.equipmentName || '—' }}（{{ row.equipmentCode || row.equipmentId }}）</template>
          </el-table-column>
          <el-table-column label="级别" width="100">
            <template #default="{ row }">
              <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : 'warning'">{{ row.severity === 'CRITICAL' ? '严重' : '警告' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="超温读数" width="140">
            <template #default="{ row }">{{ row.minTemp }} ~ {{ row.maxTemp }} ℃</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">{{ STATUS_LABELS[row.status] ?? row.status }}</template>
          </el-table-column>
          <el-table-column label="已转质检" width="100" align="center">
            <template #default="{ row }">{{ row.heldBatchCount }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140" align="center" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" :data-testid="`cc-open-${row.id}`" @click="openAlert(row.id)">受影响批次</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="设备库位" name="equipment">
        <p class="muted mb">
          登记每台冷链设备放在哪个仓库、从什么时候开始。设备换库时登记新仓库即可，旧记录保留，历史告警仍按当时的仓库计算。
        </p>
        <el-table v-loading="equipmentLoading" :data="equipment" stripe empty-text="本厂还没有冷链设备" data-testid="cc-equipment-table">
          <el-table-column label="设备" min-width="180">
            <template #default="{ row }">{{ row.equipmentName }}（{{ row.equipmentCode }}）</template>
          </el-table-column>
          <el-table-column label="目标温度" width="130">
            <template #default="{ row }">{{ row.targetTempMin }} ~ {{ row.targetTempMax }} ℃</template>
          </el-table-column>
          <el-table-column label="现在所在仓库" min-width="160">
            <template #default="{ row }">
              <span v-if="row.currentWarehouseName">{{ row.currentWarehouseName }}</span>
              <el-tag v-else type="danger">未登记</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="currentSince" label="从何时起" min-width="160" />
          <el-table-column label="操作" width="200" align="center">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="openAssign(row)">登记库位</el-button>
              <el-button size="small" @click="showHistory(row)">历史</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="historyFor" class="history">
          <h4>{{ historyFor.equipmentName }} 的库位历史</h4>
          <el-table :data="history" size="small" empty-text="还没有登记过">
            <el-table-column prop="warehouseName" label="仓库" min-width="140" />
            <el-table-column prop="effectiveFrom" label="开始" min-width="160" />
            <el-table-column label="结束" min-width="160">
              <template #default="{ row }">{{ row.effectiveTo ?? '至今' }}</template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160" />
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="drawerVisible" size="80%" title="超温受影响批次" data-testid="cc-drawer">
      <div v-loading="reportLoading">
        <el-alert v-if="reportError" :title="reportError" type="error" :closable="false" show-icon class="mb" />
        <template v-if="report">
          <el-descriptions :column="3" border size="small" class="mb">
            <el-descriptions-item label="设备">{{ report.alert.equipmentName }}（{{ report.alert.equipmentCode }}）</el-descriptions-item>
            <el-descriptions-item label="超温时段">
              {{ report.excursion.start }} ~ {{ report.excursion.end }}（{{ report.excursion.minutes }} 分钟{{ report.excursion.ongoing ? '，仍未恢复' : '' }}）
            </el-descriptions-item>
            <el-descriptions-item label="温度">
              实测 {{ report.excursion.minTemp }} ~ {{ report.excursion.maxTemp }} ℃，要求 {{ report.excursion.targetTempMin }} ~ {{ report.excursion.targetTempMax }} ℃
            </el-descriptions-item>
            <el-descriptions-item label="当时所在仓库" :span="3">
              <span v-for="s in report.segments" :key="s.warehouseId + s.start" class="seg">
                {{ s.warehouseName ?? s.warehouseId }}：{{ s.start }} ~ {{ s.end }}（{{ s.minutes }} 分钟）
              </span>
              <span v-if="report.segments.length === 0" class="danger">未登记</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-alert v-if="report.note" type="warning" :closable="false" show-icon class="mb" :title="report.note">
            <el-button link type="primary" @click="goEquipmentTab">去登记设备库位</el-button>
          </el-alert>
          <div class="summary mb">
            <span>确定受影响 <b>{{ report.affectedCount }}</b> 个</span>
            <span>在库时间不确定 <b>{{ report.uncertainCount }}</b> 个</span>
            <span class="muted">按仓库匹配：同仓但不在这台设备里的批次也会列出，请质检判断</span>
          </div>
          <el-table :data="report.batches" stripe empty-text="超温期间这个仓库里没有在库批次"
                    data-testid="cc-affected-table" @selection-change="(v: ColdChainAffectedBatch[]) => (selected = v)">
            <el-table-column type="selection" width="44" :selectable="selectable" />
            <el-table-column label="形态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.targetType === 'FINISHED_GOODS' ? 'success' : row.targetType === 'WIP_MATERIAL_BATCH' ? 'info' : ''">{{ row.formLabel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="batchNumber" label="批次号" min-width="170" show-overflow-tooltip />
            <el-table-column prop="itemName" label="品名" min-width="130" show-overflow-tooltip />
            <el-table-column prop="warehouseName" label="仓库" width="110" />
            <el-table-column label="在库" min-width="230">
              <template #default="{ row }">{{ row.inStockFrom }} → {{ row.inStockUntil }}</template>
            </el-table-column>
            <el-table-column label="暴露时长" min-width="170">
              <template #default="{ row }">
                <span v-if="row.verdict === 'AFFECTED'" class="danger"><b>{{ row.exposureMinutes }}</b> 分钟</span>
                <el-tooltip v-else :content="row.uncertainReason ?? '在库时间不确定'" placement="top">
                  <el-tag type="warning">在库时间不确定 · 最多 {{ row.maxExposureMinutes }} 分钟</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="现存" width="110" align="right">
              <template #default="{ row }">{{ qty(row) }}</template>
            </el-table-column>
            <el-table-column label="质检" min-width="150">
              <template #default="{ row }">
                <el-tag v-if="row.alreadyHeld" type="success">已转质检</el-tag>
                <span v-else-if="!row.holdable" class="muted">{{ row.holdBlockedReason }}</span>
                <span v-else class="muted">可转质检</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <span v-if="report && selected.length === 0" class="muted footer-tip">勾选要转质检的批次</span>
        <el-button @click="drawerVisible = false">关闭</el-button>
        <el-button type="danger" :disabled="selected.length === 0" :loading="holding" data-testid="cc-hold" @click="holdSelected">
          转质检 ({{ selected.length }})
        </el-button>
        <el-button link type="primary" @click="router.push('/quality/release')">去质检放行</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="assignVisible" :title="`登记库位 — ${assignTarget?.equipmentName ?? ''}（${assignTarget?.equipmentCode ?? ''}）`" width="480px">
      <el-form label-position="top">
        <el-form-item label="现在所在仓库">
          {{ assignTarget?.currentWarehouseName ?? '未登记' }}<span v-if="assignTarget?.currentSince" class="muted">（自 {{ assignTarget.currentSince }}）</span>
        </el-form-item>
        <el-form-item label="放到哪个仓库" required>
          <el-select v-model="assignWarehouseId" placeholder="选择仓库" style="width: 100%" data-testid="cc-assign-warehouse">
            <el-option v-for="w in warehouses" :key="w.id" :label="`${w.name}（${w.code}）`" :value="w.id"
                       :disabled="w.id === assignTarget?.currentWarehouseId" />
          </el-select>
        </el-form-item>
        <el-form-item label="从什么时候开始（不填 = 现在）">
          <el-date-picker v-model="assignFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="现在" style="width: 100%" />
          <div class="form-tip">只能填现在或过去、且晚于上一次登记的时间；已登记的历史不能改。</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="assignRemark" maxlength="500" placeholder="例如：冷库 A 区搬到 B 区" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span v-if="assignDisabledReason" class="disabled-reason">{{ assignDisabledReason }}</span>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" :disabled="!!assignDisabledReason" @click="submitAssign">确认登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cold-chain {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
  gap: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 4px;
  color: #303133;
}
.page-header .subtitle {
  font-size: 13px;
  color: #606266;
  margin: 0;
}
.muted {
  color: #909399;
  font-size: 13px;
}
.danger {
  color: #f56c6c;
}
.mb {
  margin-bottom: 12px;
}
.seg {
  margin-right: 16px;
}
.summary {
  display: flex;
  gap: 20px;
  align-items: center;
  font-size: 14px;
}
.history {
  margin-top: 16px;
}
.history h4 {
  margin: 0 0 8px;
}
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
}
.footer-tip {
  margin-right: 12px;
}
.disabled-reason {
  color: #f56c6c;
  font-size: 12px;
  margin-right: 12px;
}
</style>
