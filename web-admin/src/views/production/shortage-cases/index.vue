<script setup lang="ts">
/**
 * S4 缺料异常: 一个缺料事件一条处理实例。缺口按物料列「需求 / 已放行 / 质检中 / 在途 / 还缺」,
 * 下了采购单和到货待检都不算解决, 只有放行入库才减少缺口。「缺口已解决」和「订单交付」分两列。
 */
import { ref, computed, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  listShortageCases,
  getShortageCase,
  refreshShortageCase,
  getProcurementProposals,
  createProcurementDraft,
  decideShortageCase,
  proposeReschedule,
  applyReschedule,
  getOutcomeChecks,
  recheckOutcome,
  type OutcomeVerdict,
  type ShortageOutcomeCheck,
  type ProcurementProposal,
  type RescheduleProposal,
  type ShortageCase,
  type ShortageCaseDecision,
  type ShortageCaseDetail,
  type ShortageCaseStage,
} from '@/api/shortageCases';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const canPurchase = computed(() => permissionStore.canWrite('procurement'));
const canDecide = computed(() => permissionStore.canWrite('procurement') || permissionStore.canWrite('production'));
const canReschedule = computed(() => permissionStore.canWrite('production') || permissionStore.canWrite('scheduling'));
// 与后端 POST /{caseId}/refresh 的 @RequirePermission 同一份口径: 没写权限的角色点了只会 403
const canRefreshCase = computed(() => permissionStore.canWrite('procurement') || permissionStore.canWrite('production')
  || permissionStore.canWrite('warehouse') || permissionStore.canWrite('quality'));

const STAGE_LABEL: Record<ShortageCaseStage, string> = {
  DETECTED: '待处理',
  PLANNED: '已定方案',
  EXECUTING: '执行中',
  WAITING_SUPPLY: '等到货 / 放行',
  PARTIALLY_RESOLVED: '部分解决',
  GAP_RESOLVED: '缺口已解决',
  CANCELLED: '已取消',
  MANUAL: '转人工',
  CLOSED: '已关闭',
};
const STAGE_TAG: Record<ShortageCaseStage, 'danger' | 'warning' | 'success' | 'info' | 'primary'> = {
  DETECTED: 'danger',
  PLANNED: 'primary',
  EXECUTING: 'primary',
  WAITING_SUPPLY: 'warning',
  PARTIALLY_RESOLVED: 'warning',
  GAP_RESOLVED: 'success',
  CANCELLED: 'info',
  MANUAL: 'info',
  CLOSED: 'info',
};
const ROLE_LABEL: Record<string, string> = {
  procurement_manager: '采购',
  quality_inspector: '质检（放行）',
  warehouse_manager: '仓库（收货）',
  production_manager: '生产',
};
const REASON_LABEL: Record<string, string> = {
  SO_FINANCE_APPROVED: '订单财务审核通过',
  MATERIAL_RECEIVED: '物料入库',
  MATERIAL_BATCH_RELEASED: '质检放行',
  PURCHASE_ORDER_SUPPLY_CHANGED: '采购单状态变化',
  MANUAL_REFRESH: '手动核对',
  PURCHASE_DRAFT_CREATED: '生成采购草稿',
  RESCHEDULE_PROPOSED: '生成重排方案',
  RESCHEDULE_APPLIED: '应用重排',
  FORCED_START: '缺料未解决时强制开工',
  DECISION_CANCEL: '取消',
  DECISION_MANUAL: '转人工',
  DECISION_CLOSE: '关闭',
};

const openOnly = ref(true);
const loading = ref(false);
const loadFailed = ref(false);
const rows = ref<ShortageCase[]>([]);

const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<ShortageCaseDetail | null>(null);
const refreshing = ref(false);
const proposals = ref<Record<string, ProcurementProposal>>({});
const drafting = ref<string | null>(null);
const deciding = ref(false);

const TERMINAL: ShortageCaseStage[] = ['CANCELLED', 'MANUAL', 'CLOSED'];
const isOpen = computed(() => !!detail.value && !TERMINAL.includes(detail.value.case.stage));
// 转人工只停自动处理, 实例还没关 (closedAt 为空): 仍要能取消 / 关闭
const canStillDecide = computed(() => !!detail.value && detail.value.case.closedAt == null);
const isManual = computed(() => detail.value?.case.stage === 'MANUAL');

function qty(v: number | null | undefined, unit: string | null) {
  if (v === null || v === undefined) return '—';
  return `${Number(v).toLocaleString()} ${unit ?? '（单位未知）'}`;
}
function time(v: string | null) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—';
}

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    const res = await listShortageCases(factoryId.value, { openOnly: openOnly.value, size: 100 });
    if (res.success) {
      rows.value = res.data ?? [];
    } else {
      loadFailed.value = true;
      ElMessage.error(res.message || '缺料实例加载失败');
    }
  } catch {
    loadFailed.value = true;
    ElMessage.error('缺料实例加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

async function openDetail(row: ShortageCase) {
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  reschedule.value = null;
  try {
    const res = await getShortageCase(factoryId.value, row.id);
    if (res.success) {
      detail.value = res.data;
      await Promise.all([loadProposals(row.id), loadOutcomeChecks(row.id)]);
    } else {
      ElMessage.error(res.message || '详情加载失败');
    }
  } catch {
    ElMessage.error('详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

async function refreshCase() {
  if (!detail.value) return;
  refreshing.value = true;
  try {
    const res = await refreshShortageCase(factoryId.value, detail.value.case.id);
    if (res.success) {
      ElMessage.success('已按当前库存与采购重新核对');
      await openDetail(detail.value.case);
      await load();
    } else {
      ElMessage.error(res.message || '核对失败');
    }
  } catch (e: unknown) {
    ElMessage.error((e as { message?: string })?.message || '核对失败');
  } finally {
    refreshing.value = false;
  }
}

async function loadProposals(caseId: string) {
  proposals.value = {};
  if (!canPurchase.value) return;
  try {
    const res = await getProcurementProposals(factoryId.value, caseId);
    if (res.success) {
      for (const p of res.data ?? []) proposals.value[p.materialTypeId] = p;
    }
  } catch {
    // 建议拿不到只是不显示按钮, 缺口本身照常展示
  }
}

async function draft(materialTypeId: string) {
  if (!detail.value) return;
  drafting.value = materialTypeId;
  try {
    const res = await createProcurementDraft(factoryId.value, detail.value.case.id, materialTypeId);
    const unitLabel = detail.value.lines.find((l) => l.materialTypeId === materialTypeId)?.unitLabel ?? '';
    if (res.success && res.data) {
      ElMessage.success(
        res.data.reused
          ? `已有在走的采购单 ${res.data.purchaseOrderNumber}，没有重复生成`
          : `已生成采购草稿 ${res.data.purchaseOrderNumber}（${res.data.quantity} ${unitLabel}），请到采购单里核对后提交`,
      );
      await openDetail(detail.value.case);
    } else {
      ElMessage.error(res.message || '生成采购草稿失败');
    }
  } catch (e: unknown) {
    ElMessage.error((e as { message?: string })?.message || '生成采购草稿失败');
  } finally {
    drafting.value = null;
  }
}

// ---- 执行后核对: 重排 / 转采购之后后端自动核对一次, 这里展示并可手动再核对
const outcomeChecks = ref<ShortageOutcomeCheck[]>([]);
const outcomeLoadFailed = ref(false);
const rechecking = ref(false);
const canRecheck = computed(() => canPurchase.value || canReschedule.value);
const VERDICT_TAG: Record<OutcomeVerdict, 'success' | 'warning' | 'danger' | 'info'> = {
  ACHIEVED: 'success',
  PENDING_SUPPLY: 'warning',
  NOT_ACHIEVED: 'danger',
  UNKNOWN: 'info',
};
const CHECK_TRIGGER_LABEL: Record<ShortageOutcomeCheck['trigger'], string> = {
  RESCHEDULE_APPLIED: '应用重排后',
  PURCHASE_DRAFT: '生成采购草稿后',
  MANUAL_RECHECK: '手动核对',
};
const latestCheck = computed(() => outcomeChecks.value[outcomeChecks.value.length - 1] ?? null);

async function loadOutcomeChecks(caseId: string) {
  outcomeChecks.value = [];
  outcomeLoadFailed.value = false;
  try {
    const res = await getOutcomeChecks(factoryId.value, caseId);
    if (res.success) {
      outcomeChecks.value = res.data ?? [];
    } else {
      outcomeLoadFailed.value = true;
    }
  } catch {
    // 拿不到就如实标「加载失败」, 不显示成「还没核对过」
    outcomeLoadFailed.value = true;
  }
}

async function recheck() {
  if (!detail.value) return;
  rechecking.value = true;
  try {
    const res = await recheckOutcome(factoryId.value, detail.value.case.id);
    if (res.success) {
      ElMessage.success(res.message || '已核对');
      await loadOutcomeChecks(detail.value.case.id);
    } else {
      ElMessage.error(res.message || '核对失败');
    }
  } catch (e: unknown) {
    ElMessage.error(errorText(e, '核对失败'));
  } finally {
    rechecking.value = false;
  }
}

// ---- 局部重排: 生成方案 (只预览) → 选一个候选应用; 排程在生成后变过, 后端整体 409, 一行不改
const reschedule = ref<RescheduleProposal | null>(null);
const proposing = ref(false);
const applying = ref<string | null>(null);

/** 后端 409 带的操作提示一起显示, 用户才知道下一步做什么 (如「重新生成重排方案」)。 */
function errorText(e: unknown, fallback: string) {
  const err = e as { message?: string; actionHint?: string | null };
  const msg = err?.message || fallback;
  return err?.actionHint ? `${msg}（${err.actionHint}）` : msg;
}

function shortTime(iso: string) {
  return iso.replace('T', ' ').slice(0, 16);
}

function candidateBasis(candidate: RescheduleProposal['candidates'][number]) {
  return candidate.basis ?? [];
}

function basisText(basis: NonNullable<RescheduleProposal['candidates'][number]['basis']>[number]) {
  const source = basis.capacitySource === 'MANUAL' ? '人工日产能' : '历史估算日产能';
  const bom = basis.bomFamilyId
    ? `BOM 配方族 ${basis.bomFamilyId}`
    : `BOM 配方 ${basis.bomRecipeId} v${basis.bomVersion}`;
  return `依据：Workflow ID ${basis.workflowId} / v${basis.workflowVersion} / 修订 ${basis.workflowRevisionId}；${bom}；APS ${source} ${basis.capacityPerDay} ${basis.unit}/天（仅日期窗口，未验证工序级精确耗时）`;
}

async function propose() {
  if (!detail.value) return;
  proposing.value = true;
  try {
    const res = await proposeReschedule(factoryId.value, detail.value.case.id);
    if (res.success && res.data) {
      reschedule.value = res.data;
    } else {
      ElMessage.error(res.message || '生成重排方案失败');
    }
  } catch (e: unknown) {
    ElMessage.error(errorText(e, '生成重排方案失败'));
  } finally {
    proposing.value = false;
  }
}

async function applyCandidate(key: string, label: string) {
  if (!detail.value || !reschedule.value) return;
  try {
    await ElMessageBox.confirm(`按「${label}」调整排程？`, '应用重排', { confirmButtonText: '应用', cancelButtonText: '先不' });
  } catch {
    return;
  }
  applying.value = key;
  try {
    const res = await applyReschedule(factoryId.value, detail.value.case.id, reschedule.value.id, key);
    if (res.success) {
      ElMessage.success('排程已调整');
      reschedule.value = null;
      await openDetail(detail.value.case);
      await load();
    } else {
      ElMessage.error(res.message || '应用失败');
    }
  } catch (e: unknown) {
    // 409 方案已过期: 丢掉旧方案, 让用户重新生成
    ElMessage.error(errorText(e, '应用失败'));
    reschedule.value = null;
  } finally {
    applying.value = null;
  }
}

const DECISION_LABEL: Record<ShortageCaseDecision, string> = { CANCEL: '取消', MANUAL: '转人工', CLOSE: '关闭' };

async function decide(decision: ShortageCaseDecision) {
  if (!detail.value) return;
  let reason: string;
  try {
    const r = await ElMessageBox.prompt(`请写明${DECISION_LABEL[decision]}的原因（必填）`, DECISION_LABEL[decision], {
      inputValidator: (v: string) => (v && v.trim().length > 0) || '原因不能为空',
      confirmButtonText: '确定',
      cancelButtonText: '先不',
    });
    reason = (r as { value: string }).value.trim();
  } catch {
    return; // 用户取消
  }
  deciding.value = true;
  try {
    const res = await decideShortageCase(factoryId.value, detail.value.case.id, { decision, reason });
    if (res.success) {
      ElMessage.success(`已${DECISION_LABEL[decision]}`);
      await openDetail(detail.value.case);
      await load();
    } else {
      ElMessage.error(res.message || '处理失败');
    }
  } catch (e: unknown) {
    ElMessage.error((e as { message?: string })?.message || '处理失败');
  } finally {
    deciding.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <span class="title">缺料异常</span>
            <span class="subtitle">一个缺料事件一条；下单和到货待检都不算解决，放行入库才减少缺口</span>
          </div>
          <div class="actions">
            <el-switch v-model="openOnly" active-text="只看未关闭" @change="load" />
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="loadFailed" type="error" :closable="false" title="缺料实例加载失败，下面的列表不代表没有缺料" show-icon />

      <el-table v-loading="loading" :data="rows" empty-text="暂无缺料实例" @row-click="openDetail">
        <el-table-column label="来源订单" min-width="150">
          <template #default="{ row }">{{ row.salesOrderNumber || row.sourceId }}</template>
        </el-table-column>
        <el-table-column label="阶段" width="140">
          <template #default="{ row }">
            <el-tag :type="STAGE_TAG[row.stage as ShortageCaseStage]">{{ STAGE_LABEL[row.stage as ShortageCaseStage] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="该谁处理" width="130">
          <template #default="{ row }">{{ ROLE_LABEL[row.ownerRole] || row.ownerRole }}</template>
        </el-table-column>
        <el-table-column prop="gapLineCount" label="还缺的物料" width="110" />
        <el-table-column label="缺口解决时间" width="160">
          <template #default="{ row }">{{ time(row.gapResolvedAt) }}</template>
        </el-table-column>
        <el-table-column label="订单交付状态" width="130">
          <template #default="{ row }">{{ row.salesOrderStatusLabel || '—' }}</template>
        </el-table-column>
        <el-table-column label="最近核对" min-width="200">
          <template #default="{ row }">
            {{ time(row.lastRefreshedAt) }} · {{ REASON_LABEL[row.lastRefreshReason] || row.lastRefreshReason }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" size="760px" title="缺料实例">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="来源订单">{{ detail.case.salesOrderNumber || detail.case.sourceId }}</el-descriptions-item>
            <el-descriptions-item label="阶段">{{ STAGE_LABEL[detail.case.stage] }}</el-descriptions-item>
            <el-descriptions-item label="该谁处理">{{ ROLE_LABEL[detail.case.ownerRole] || detail.case.ownerRole }}</el-descriptions-item>
            <el-descriptions-item label="订单交付状态">{{ detail.case.salesOrderStatusLabel || '—' }}</el-descriptions-item>
            <el-descriptions-item label="缺口解决时间">{{ time(detail.case.gapResolvedAt) }}</el-descriptions-item>
            <el-descriptions-item label="最近核对">{{ time(detail.case.lastRefreshedAt) }}</el-descriptions-item>
          </el-descriptions>

          <div class="section-head">
            <h4>缺口</h4>
            <el-button v-if="canRefreshCase && isOpen" size="small" :icon="Refresh" :loading="refreshing" @click="refreshCase">按当前库存重新核对</el-button>
          </div>
          <el-table :data="detail.lines" size="small" border>
            <el-table-column label="物料" min-width="120">
              <template #default="{ row }">{{ row.materialName || row.materialTypeId }}</template>
            </el-table-column>
            <el-table-column label="需求"><template #default="{ row }">{{ qty(row.requiredQty, row.unitLabel) }}</template></el-table-column>
            <el-table-column label="已放行可用"><template #default="{ row }">{{ qty(row.releasedQty, row.unitLabel) }}</template></el-table-column>
            <el-table-column label="质检中"><template #default="{ row }">{{ qty(row.inspectingQty, row.unitLabel) }}</template></el-table-column>
            <el-table-column label="在途">
              <template #default="{ row }">
                {{ qty(row.onOrderQty, row.unitLabel) }}
                <div v-if="row.onOrderUnitMismatchLines > 0" class="hint">
                  另有 {{ row.onOrderUnitMismatchLines }} 行采购单位不同，未计入
                </div>
              </template>
            </el-table-column>
            <el-table-column label="还缺">
              <template #default="{ row }">
                <span :class="{ gap: row.gapQty > 0 }">{{ qty(row.gapQty, row.unitLabel) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="canPurchase && isOpen" label="采购" min-width="190">
              <template #default="{ row }">
                <template v-if="proposals[row.materialTypeId]">
                  <el-button
                    v-if="proposals[row.materialTypeId].canDraft"
                    size="small"
                    type="primary"
                    :loading="drafting === row.materialTypeId"
                    @click="draft(row.materialTypeId)"
                  >
                    生成采购草稿 {{ proposals[row.materialTypeId].suggestedQty }} {{ row.unitLabel ?? '' }}
                  </el-button>
                  <div v-for="(n, i) in proposals[row.materialTypeId].notes" :key="i" class="hint">{{ n }}</div>
                </template>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="canDecide && canStillDecide" class="decision-bar">
            <el-button v-if="!isManual" :loading="deciding" @click="decide('MANUAL')">转人工</el-button>
            <el-button :loading="deciding" type="danger" plain @click="decide('CANCEL')">取消</el-button>
            <el-button
              :loading="deciding"
              type="success"
              :disabled="detail.case.stage !== 'GAP_RESOLVED' && !isManual"
              @click="decide('CLOSE')"
            >
              {{ isManual ? '关闭（人工处理完毕）' : '关闭（缺口已解决）' }}
            </el-button>
          </div>

          <template v-if="canReschedule && isOpen">
            <div class="section-head">
              <h4>局部重排</h4>
              <el-button size="small" :loading="proposing" @click="propose">
                {{ reschedule ? '重新生成方案' : '生成重排方案' }}
              </el-button>
            </div>
            <div v-if="!reschedule" class="hint">按缺料的预计到货日给出顺延 / 换线方案, 只预览, 确认后才改排程。</div>
            <div v-else>
              <div class="hint">方案 {{ shortTime(reschedule.expiresAt) }} 前有效; 期间排程被改过, 应用会被拒绝, 需重新生成。</div>
              <el-card v-for="c in reschedule.candidates" :key="c.key" shadow="never" class="candidate">
                <div class="candidate-head">
                  <strong>{{ c.label }}</strong>
                  <el-tag v-if="!c.applicable" size="small" type="info">不可直接应用</el-tag>
                <el-button
                  v-else-if="c.applicable && candidateBasis(c).length"
                    size="small"
                    type="primary"
                    :loading="applying === c.key"
                    @click="applyCandidate(c.key, c.label)"
                  >
                    应用此方案
                  </el-button>
                </div>
                <div v-if="candidateBasis(c).length" class="basis-list">
                  <div v-for="(b, i) in candidateBasis(c)" :key="`${b.productionPlanId}-${i}`" class="basis">{{ basisText(b) }}</div>
                </div>
                <div v-else class="hint">{{ c.basis === undefined ? '该方案来自旧版本，缺少正式排程依据，请重新生成' : c.notes.join('；') }}</div>
                <el-table v-if="candidateBasis(c).length && c.moves.length" :data="c.moves" size="small">
                  <el-table-column label="产线" min-width="140">
                    <template #default="{ row }">
                      {{ row.fromLineName }}<span v-if="row.toLineId !== row.fromLineId"> → {{ row.toLineName }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="开始" min-width="200">
                    <template #default="{ row }">{{ shortTime(row.fromStart) }} → {{ shortTime(row.toStart) }}</template>
                  </el-table-column>
                  <el-table-column label="结束" min-width="200">
                    <template #default="{ row }">{{ shortTime(row.fromEnd) }} → {{ shortTime(row.toEnd) }}</template>
                  </el-table-column>
                </el-table>
                <div v-for="(n, i) in c.notes" :key="i" class="hint">{{ n }}</div>
              </el-card>
            </div>
          </template>

          <div class="section-head">
            <h4>处理结果核对</h4>
            <el-button v-if="canRecheck && isOpen" size="small" :loading="rechecking" @click="recheck">
              按当前数据再核对
            </el-button>
          </div>
          <div v-if="outcomeLoadFailed" class="hint">核对记录加载失败，请稍后重新打开</div>
          <div v-else-if="!latestCheck" class="hint">还没核对过。应用重排或生成采购草稿后会自动核对一次。</div>
          <template v-else>
            <div class="verdict">
              <el-tag :type="VERDICT_TAG[latestCheck.verdict]">{{ latestCheck.verdictLabel }}</el-tag>
              <span class="hint">{{ CHECK_TRIGGER_LABEL[latestCheck.trigger] }} · {{ time(latestCheck.createdAt) }}</span>
            </div>
            <div class="verdict-detail">{{ latestCheck.detail }}</div>
            <div class="hint">
              交期 {{ latestCheck.dueDate ?? '—' }} · 最晚完工 {{ time(latestCheck.latestPlannedEnd) }}
              · 晚于交期 {{ latestCheck.lateScheduleCount }} 个 · 未关闭缺口 {{ latestCheck.openGapLines }} 行
              · 预计到货 {{ latestCheck.supplyReadyDate ?? '—' }}
            </div>
            <el-collapse v-if="outcomeChecks.length > 1" class="check-history">
              <el-collapse-item :title="`更早的核对（${outcomeChecks.length - 1} 次）`">
                <div v-for="c in outcomeChecks.slice(0, -1).reverse()" :key="c.id" class="hint">
                  {{ time(c.createdAt) }} {{ CHECK_TRIGGER_LABEL[c.trigger] }}：{{ c.detail }}
                </div>
              </el-collapse-item>
            </el-collapse>
          </template>

          <h4>处理记录</h4>
          <el-timeline>
            <el-timeline-item v-for="(e, i) in detail.events" :key="i" :timestamp="time(e.createdAt)">
              <strong>{{ REASON_LABEL[e.triggerReason] || e.triggerReason }}</strong>
              <span v-if="e.toStage"> → {{ STAGE_LABEL[e.toStage] }}</span>
              <div v-if="e.detail" class="hint">{{ e.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { font-size: 16px; font-weight: 600; margin-right: 12px; }
.subtitle { color: var(--el-text-color-secondary); font-size: 13px; }
.actions { display: flex; gap: 12px; align-items: center; }
.section-head { display: flex; justify-content: space-between; align-items: center; margin: 16px 0 8px; }
.section-head h4, h4 { margin: 16px 0 8px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.gap { color: var(--el-color-danger); font-weight: 600; }
.decision-bar { display: flex; gap: 8px; margin: 12px 0; }
.candidate { margin: 8px 0; }
.basis-list { margin: 4px 0 8px; }
.basis { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.verdict { display: flex; gap: 8px; align-items: center; }
.verdict-detail { margin: 6px 0 4px; font-size: 13px; }
.check-history { margin-top: 8px; }
.candidate-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 6px; }
</style>
