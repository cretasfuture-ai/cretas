<script setup lang="ts">
import { CompatDescriptions, CompatDescriptionsItem, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

/**
 * S4 缺料异常: 一个缺料事件一条处理实例。缺口按物料列「需求 / 已放行 / 质检中 / 在途 / 还缺」,
 * 下了采购单和到货待检都不算解决, 只有放行入库才减少缺口。「缺口已解决」和「订单交付」分两列。
 */
import { ref, computed, onMounted } from 'vue';

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
  MATERIAL_BATCH_QC_REJECTED: '质检判不合格',
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
    ElMessage.error(t('production.shortage_cases_index.message_47aefd3b60ce'));
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
    ElMessage.error(t('production.shortage_cases_index.message_19a7ca44a09a'));
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
      ElMessage.success(t('production.shortage_cases_index.message_6fbcac1655ce'));
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
    await ElMessageBox.confirm(`按「${label}」调整排程？`, t('production.shortage_cases_index.message_7c8b3e741bed'), { confirmButtonText: t('production.shortage_cases_index.confirmbuttontext_63c73c4730f4'), cancelButtonText: t('production.shortage_cases_index.cancelbuttontext_a4d2f6abc99e') });
  } catch {
    return;
  }
  applying.value = key;
  try {
    const res = await applyReschedule(factoryId.value, detail.value.case.id, reschedule.value.id, key);
    if (res.success) {
      ElMessage.success(t('production.shortage_cases_index.message_309a8fc8e38b'));
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
      confirmButtonText: t('production.shortage_cases_index.confirmbuttontext_fac2a67ad878'),
      cancelButtonText: t('production.shortage_cases_index.cancelbuttontext_a4d2f6abc99e'),
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
    <UCard>
      <template #header>
        <div class="card-header">
          <div>
            <span class="title">{{ t('production.shortage_cases_index.text_20aa48607f41') }}</span>
            <span class="subtitle">{{ t('production.shortage_cases_index.text_bbec6ab5735d') }}</span>
          </div>
          <div class="actions">
            <el-switch v-model="openOnly" active-text="只看未关闭" @change="load" />
            <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('production.shortage_cases_index.action_aee887434131') }}</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="loadFailed" type="error" :closable="false" :title="t('production.shortage_cases_index.title_a52867afb388')" show-icon />

      <CompatTable v-loading="loading" :data="rows" :empty-text="t('production.shortage_cases_index.empty_text_b3aefcc22a49')" @row-click="openDetail">
        <CompatTableColumn :label="t('production.shortage_cases_index.label_472876096b98')" min-width="150">
          <template #default="{ row }">{{ row.salesOrderNumber || row.sourceId }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.shortage_cases_index.label_59eb8d03efea')" width="140">
          <template #default="{ row }">
            <el-tag :type="STAGE_TAG[row.stage as ShortageCaseStage]">{{ STAGE_LABEL[row.stage as ShortageCaseStage] }}</el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.shortage_cases_index.label_a0722216b2eb')" width="130">
          <template #default="{ row }">{{ ROLE_LABEL[row.ownerRole] || row.ownerRole }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="gapLineCount" :label="t('production.shortage_cases_index.label_a8c500f1ed06')" width="110" />
        <CompatTableColumn :label="t('production.shortage_cases_index.label_600efbe150eb')" width="160">
          <template #default="{ row }">{{ time(row.gapResolvedAt) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.shortage_cases_index.label_dc64460e6d8e')" width="130">
          <template #default="{ row }">{{ row.salesOrderStatusLabel || '—' }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('production.shortage_cases_index.label_538388b8f0f6')" min-width="200">
          <template #default="{ row }">
            {{ time(row.lastRefreshedAt) }} · {{ REASON_LABEL[row.lastRefreshReason] || row.lastRefreshReason }}
          </template>
        </CompatTableColumn>
      </CompatTable>
    </UCard>

    <el-drawer v-model="detailVisible" size="760px" :title="t('production.shortage_cases_index.title_35465d2bffee')">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <CompatDescriptions :column="2" border size="small">
            <CompatDescriptionsItem :label="t('production.shortage_cases_index.label_472876096b98')">{{ detail.case.salesOrderNumber || detail.case.sourceId }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.shortage_cases_index.label_59eb8d03efea')">{{ STAGE_LABEL[detail.case.stage] }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.shortage_cases_index.label_a0722216b2eb')">{{ ROLE_LABEL[detail.case.ownerRole] || detail.case.ownerRole }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.shortage_cases_index.label_dc64460e6d8e')">{{ detail.case.salesOrderStatusLabel || '—' }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.shortage_cases_index.label_600efbe150eb')">{{ time(detail.case.gapResolvedAt) }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.shortage_cases_index.label_538388b8f0f6')">{{ time(detail.case.lastRefreshedAt) }}</CompatDescriptionsItem>
          </CompatDescriptions>

          <div class="section-head">
            <h4>{{ t('production.shortage_cases_index.text_245cdcd5fad2') }}</h4>
            <el-button v-if="canRefreshCase && isOpen" size="small" :icon="Refresh" :loading="refreshing" @click="refreshCase">{{ t('production.shortage_cases_index.action_766315fa6acc') }}</el-button>
          </div>
          <CompatTable :data="detail.lines" size="small" border>
            <CompatTableColumn :label="t('production.shortage_cases_index.label_6446454e8e36')" min-width="120">
              <template #default="{ row }">{{ row.materialName || row.materialTypeId }}</template>
            </CompatTableColumn>
            <CompatTableColumn :label="t('production.shortage_cases_index.label_128a7a03668b')"><template #default="{ row }">{{ qty(row.requiredQty, row.unitLabel) }}</template></CompatTableColumn>
            <CompatTableColumn :label="t('production.shortage_cases_index.label_60186b05ea0b')"><template #default="{ row }">{{ qty(row.releasedQty, row.unitLabel) }}</template></CompatTableColumn>
            <CompatTableColumn :label="t('production.shortage_cases_index.label_f6790d80c1b7')"><template #default="{ row }">{{ qty(row.inspectingQty, row.unitLabel) }}</template></CompatTableColumn>
            <CompatTableColumn :label="t('production.shortage_cases_index.label_555874b7c11d')">
              <template #default="{ row }">
                {{ qty(row.onOrderQty, row.unitLabel) }}
                <div v-if="row.onOrderUnitMismatchLines > 0" class="hint">
                  {{ t('production.shortage_cases_index.text_268ec6902063') }} {{ row.onOrderUnitMismatchLines }} {{ t('production.shortage_cases_index.text_42d9cc3373fb') }}
                </div>
              </template>
            </CompatTableColumn>
            <CompatTableColumn :label="t('production.shortage_cases_index.label_462413f016af')">
              <template #default="{ row }">
                <span :class="{ gap: row.gapQty > 0 }">{{ qty(row.gapQty, row.unitLabel) }}</span>
              </template>
            </CompatTableColumn>
            <CompatTableColumn v-if="canPurchase && isOpen" :label="t('production.shortage_cases_index.label_6e098153a586')" min-width="190">
              <template #default="{ row }">
                <template v-if="proposals[row.materialTypeId]">
                  <UButton
                    v-if="proposals[row.materialTypeId].canDraft"
                    size="sm"
                    color="primary"
                    :loading="drafting === row.materialTypeId"
                    @click="draft(row.materialTypeId)"
                  >
                    {{ t('production.shortage_cases_index.action_f84c85692e46') }} {{ proposals[row.materialTypeId].suggestedQty }} {{ row.unitLabel ?? '' }}
                  </UButton>
                  <div v-for="(n, i) in proposals[row.materialTypeId].notes" :key="i" class="hint">{{ n }}</div>
                </template>
              </template>
            </CompatTableColumn>
          </CompatTable>

          <div v-if="canDecide && canStillDecide" class="decision-bar">
            <UButton color="neutral" variant="outline" v-if="!isManual" :loading="deciding" @click="decide('MANUAL')">{{ t('production.shortage_cases_index.action_41f043872f9e') }}</UButton>
            <UButton :loading="deciding" color="error" variant="outline" @click="decide('CANCEL')">{{ t('production.shortage_cases_index.action_2cd0f3be8738') }}</UButton>
            <UButton
              :loading="deciding"
              color="success"
              :disabled="detail.case.stage !== 'GAP_RESOLVED' && !isManual"
              @click="decide('CLOSE')"
            >
              {{ isManual ? t('production.shortage_cases_index.text_a6506d1d5829') : t('production.shortage_cases_index.text_a5054e2cb465') }}
            </UButton>
          </div>

          <template v-if="canReschedule && isOpen">
            <div class="section-head">
              <h4>{{ t('production.shortage_cases_index.text_2c40abbaeac5') }}</h4>
              <UButton color="neutral" variant="outline" size="sm" :loading="proposing" @click="propose">
                {{ reschedule ? t('production.shortage_cases_index.text_6641047eb381') : t('production.shortage_cases_index.text_9779dbcf8cbf') }}
              </UButton>
            </div>
            <div v-if="!reschedule" class="hint">{{ t('production.shortage_cases_index.text_3532de739e3b') }}</div>
            <div v-else>
              <div class="hint">{{ t('production.shortage_cases_index.text_6f949ac3522d') }} {{ shortTime(reschedule.expiresAt) }} {{ t('production.shortage_cases_index.text_0308757f77dd') }}</div>
              <UCard v-for="c in reschedule.candidates" :key="c.key"  class="candidate">
                <div class="candidate-head">
                  <strong>{{ c.label }}</strong>
                  <UBadge variant="soft" v-if="!c.applicable" size="sm" color="info">{{ t('production.shortage_cases_index.text_ce877a44859b') }}</UBadge>
                <UButton
                  v-else-if="c.applicable && candidateBasis(c).length"
                    size="sm"
                    color="primary"
                    :loading="applying === c.key"
                    @click="applyCandidate(c.key, c.label)"
                  >
                    {{ t('production.shortage_cases_index.action_1278b8f6d045') }}
                  </UButton>
                </div>
                <div v-if="candidateBasis(c).length" class="basis-list">
                  <div v-for="(b, i) in candidateBasis(c)" :key="`${b.productionPlanId}-${i}`" class="basis">{{ basisText(b) }}</div>
                </div>
                <div v-else class="hint">{{ c.basis === undefined ? t('production.shortage_cases_index.text_8907acb97378') : c.notes.join('；') }}</div>
                <CompatTable v-if="candidateBasis(c).length && c.moves.length" :data="c.moves" size="small">
                  <CompatTableColumn :label="t('production.shortage_cases_index.label_77ca877fdd41')" min-width="140">
                    <template #default="{ row }">
                      {{ row.fromLineName }}<span v-if="row.toLineId !== row.fromLineId"> → {{ row.toLineName }}</span>
                    </template>
                  </CompatTableColumn>
                  <CompatTableColumn :label="t('production.shortage_cases_index.label_d2bb025a2e51')" min-width="200">
                    <template #default="{ row }">{{ shortTime(row.fromStart) }} → {{ shortTime(row.toStart) }}</template>
                  </CompatTableColumn>
                  <CompatTableColumn :label="t('production.shortage_cases_index.label_c7b24e7997e9')" min-width="200">
                    <template #default="{ row }">{{ shortTime(row.fromEnd) }} → {{ shortTime(row.toEnd) }}</template>
                  </CompatTableColumn>
                </CompatTable>
                <div v-for="(n, i) in c.notes" :key="i" class="hint">{{ n }}</div>
              </UCard>
            </div>
          </template>

          <div class="section-head">
            <h4>{{ t('production.shortage_cases_index.text_7385dab92c9e') }}</h4>
            <UButton color="neutral" variant="outline" v-if="canRecheck && isOpen" size="sm" :loading="rechecking" @click="recheck">
              {{ t('production.shortage_cases_index.action_4123c3bc2f66') }}
            </UButton>
          </div>
          <div v-if="outcomeLoadFailed" class="hint">{{ t('production.shortage_cases_index.text_3cf7d92e8228') }}</div>
          <div v-else-if="!latestCheck" class="hint">{{ t('production.shortage_cases_index.text_feaf44b0149d') }}</div>
          <template v-else>
            <div class="verdict">
              <el-tag :type="VERDICT_TAG[latestCheck.verdict]">{{ latestCheck.verdictLabel }}</el-tag>
              <span class="hint">{{ CHECK_TRIGGER_LABEL[latestCheck.trigger] }} · {{ time(latestCheck.createdAt) }}</span>
            </div>
            <div class="verdict-detail">{{ latestCheck.detail }}</div>
            <div class="hint">
              {{ t('production.shortage_cases_index.text_7d0e25d161e5') }} {{ latestCheck.dueDate ?? '—' }} {{ t('production.shortage_cases_index.text_3a2b7f5915cd') }} {{ time(latestCheck.latestPlannedEnd) }}
              {{ t('production.shortage_cases_index.text_7135e62fccae') }} {{ latestCheck.lateScheduleCount }} {{ t('production.shortage_cases_index.text_84c5dd91e529') }} {{ latestCheck.openGapLines }} {{ t('production.shortage_cases_index.text_69d2115ea652') }} {{ latestCheck.supplyReadyDate ?? '—' }}
            </div>
            <el-collapse v-if="outcomeChecks.length > 1" class="check-history">
              <el-collapse-item :title="`更早的核对（${outcomeChecks.length - 1} 次）`">
                <div v-for="c in outcomeChecks.slice(0, -1).reverse()" :key="c.id" class="hint">
                  {{ time(c.createdAt) }} {{ CHECK_TRIGGER_LABEL[c.trigger] }}：{{ c.detail }}
                </div>
              </el-collapse-item>
            </el-collapse>
          </template>

          <h4>{{ t('production.shortage_cases_index.text_d9271468c389') }}</h4>
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
