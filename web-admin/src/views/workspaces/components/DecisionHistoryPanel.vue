<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { usePermissionStore } from '@/store/modules/permission'
import {
  compareDecisionHistory,
  isDecisionRecommendationReplay,
  isDecisionHistoryPage,
  isDecisionSnapshotComparison,
  listDecisionHistory,
  replayDecisionHistory,
  type DecisionHistoryRecord,
  type DecisionRecommendationReplay,
  type DecisionSnapshotComparison,
} from '@/api/decisionHistory'

const props = defineProps<{ factoryId: string; role: string }>()
const permissionStore = usePermissionStore()
const supportedRoles = new Set(['factory_super_admin', 'permission_admin'])
const decisionTypeLabels: Record<DecisionHistoryRecord['decisionType'], string> = {
  RULE_EXECUTION: '规则执行', STATE_TRANSITION: '状态变更', FORCE_INSERT: '强制插入',
  APPROVAL: '审批', AI_ANALYSIS: '智能分析', MANUAL_OVERRIDE: '人工覆盖',
}
const statusLabels = {
  SAME_RESULT: '记录结果一致（已核验）', DIFFERENT_RESULT: '记录结果不同（已核验）', NOT_COMPARABLE: '不可比较',
} as const
const replayStatusLabels = {
  REPLAY_MATCH: '建议复算一致（已核验）',
  REPLAY_DIFFERENT: '建议复算不同（已核验）',
  UNAVAILABLE: '不可复算',
} as const
const reasonLabels: Record<string, string> = {
  INVALID_HISTORICAL_BASELINE: '基线不是当前记录之前的同一历史范围',
  BUSINESS_SCOPE_MISMATCH: '业务对象或决策类型不一致',
  SNAPSHOT_MISSING_OR_INVALID: '历史快照缺失或格式不可核验',
  INPUT_MISMATCH: '两条记录的输入事实不一致',
  RECORDED_OUTPUT_EQUAL: '持久化输出一致', RECORDED_OUTPUT_DIFFERENT: '持久化输出不同',
  CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF: '校验和不匹配，不能证明记录完整性',
}
const basisLabels = {
  CHECKSUM_AND_COLUMNS: '校验和与持久化字段均已核验',
  PERSISTED_COLUMNS_SEMANTIC_ONLY: '仅比较持久化字段语义，校验和未核验',
  NOT_EVALUATED: '未完成可核验对照',
} as const

const pageSize = 20
const records = ref<DecisionHistoryRecord[]>([])
const page = ref(0)
const totalElements = ref(0)
const totalPages = ref(0)
const listLoaded = ref(false)
const listLoading = ref(false)
const listError = ref('')
const currentId = ref('')
const baselineId = ref('')
const comparison = ref<DecisionSnapshotComparison | null>(null)
const comparisonLoading = ref(false)
const comparisonError = ref('')
const replay = ref<DecisionRecommendationReplay | null>(null)
const replayLoading = ref(false)
const replayError = ref('')
let contextEpoch = 0
let comparisonEpoch = 0
let replayEpoch = 0

const hasAccess = computed(() => /^[A-Za-z0-9_-]{1,64}$/.test(props.factoryId) && supportedRoles.has(props.role))
const canReplayAccess = computed(() => hasAccess.value
  && permissionStore.isDbLoaded
  && permissionStore.canAccess('system')
  && permissionStore.canAccess('quality'))
const replayAccessDenied = computed(() => hasAccess.value && !canReplayAccess.value)
const currentRecord = computed(() => records.value.find(record => record.id === currentId.value) || null)
const baselineRecord = computed(() => records.value.find(record => record.id === baselineId.value) || null)
const canCompare = computed(() => Boolean(currentRecord.value && baselineRecord.value)
  && currentId.value !== baselineId.value && !comparisonLoading.value)

function resetContext() {
  contextEpoch += 1; comparisonEpoch += 1; replayEpoch += 1
  records.value = []; page.value = 0; totalElements.value = 0; totalPages.value = 0
  listLoaded.value = false; listLoading.value = false; listError.value = ''
  currentId.value = ''; baselineId.value = ''; comparison.value = null
  comparisonLoading.value = false; comparisonError.value = ''
  replay.value = null; replayLoading.value = false; replayError.value = ''
}
function invalidateComparison() {
  comparisonEpoch += 1; comparison.value = null; comparisonError.value = ''; comparisonLoading.value = false
}
function invalidateReplay() {
  replayEpoch += 1; replay.value = null; replayError.value = ''; replayLoading.value = false
}
watch(() => [props.factoryId, props.role], resetContext)
watch([currentId, baselineId], () => { invalidateComparison(); invalidateReplay() })
watch(canReplayAccess, () => invalidateReplay())

function errorMessage(error: unknown, fallback: string): string {
  if (error && typeof error === 'object') {
    const candidate = error as { response?: { data?: { message?: string } }; message?: string }
    return candidate.response?.data?.message || candidate.message || fallback
  }
  return fallback
}

async function loadRecords(targetPage = page.value) {
  if (listLoading.value || !hasAccess.value) return
  invalidateComparison()
  invalidateReplay()
  currentId.value = ''
  baselineId.value = ''
  records.value = []
  listLoaded.value = false
  totalElements.value = 0
  totalPages.value = 0
  page.value = targetPage
  listLoading.value = true; listError.value = ''
  const requestEpoch = contextEpoch
  try {
    const response = await listDecisionHistory(props.factoryId, targetPage, pageSize)
    if (requestEpoch !== contextEpoch) return
    if (!response.success || !isDecisionHistoryPage(response.data, targetPage, pageSize)) {
      listError.value = response.message || '历史记录返回结果无法核验，未展示旧列表。'; return
    }
    records.value = response.data.records; page.value = response.data.page
    totalElements.value = response.data.totalElements; totalPages.value = response.data.totalPages
    listLoaded.value = true
    if (!records.value.some(record => record.id === currentId.value)) currentId.value = ''
    if (!records.value.some(record => record.id === baselineId.value) || currentId.value === baselineId.value) baselineId.value = ''
    comparison.value = null; comparisonError.value = ''
  } catch (error) {
    if (requestEpoch === contextEpoch) listError.value = errorMessage(error, '历史记录读取失败，未展示旧列表。')
  } finally {
    if (requestEpoch === contextEpoch) listLoading.value = false
  }
}
function selectCurrent(id: string) { if (id === baselineId.value) baselineId.value = ''; currentId.value = id }
function selectBaseline(id: string) { if (id === currentId.value) currentId.value = ''; baselineId.value = id }

async function compareSelected() {
  if (comparisonLoading.value || listLoading.value || !listLoaded.value) return
  if (!hasAccess.value || !records.value.some(record => record.id === currentId.value)
    || !records.value.some(record => record.id === baselineId.value)) {
    comparisonError.value = '当前列表已变化，请重新选择历史记录。'
    return
  }
  if (!currentId.value || !baselineId.value) { comparisonError.value = '请分别选择当前记录和基线记录。'; return }
  if (currentId.value === baselineId.value) { comparisonError.value = '当前记录和基线记录必须是两条不同的历史记录。'; return }
  const requestEpoch = ++comparisonEpoch
  const requestedFactory = props.factoryId; const requestedRole = props.role
  const requestedCurrentId = currentId.value; const requestedBaselineId = baselineId.value
  comparisonLoading.value = true; comparisonError.value = ''; comparison.value = null
  try {
    const response = await compareDecisionHistory(requestedFactory, requestedCurrentId, requestedBaselineId)
    if (requestEpoch !== comparisonEpoch || requestedFactory !== props.factoryId || requestedRole !== props.role
      || requestedCurrentId !== currentId.value || requestedBaselineId !== baselineId.value) return
    if (!response.success || !isDecisionSnapshotComparison(response.data, requestedCurrentId, requestedBaselineId)) {
      comparisonError.value = response.message || '对照结果无法核验，未展示旧结果。'; return
    }
    comparison.value = response.data
  } catch (error) {
    if (requestEpoch === comparisonEpoch) comparisonError.value = errorMessage(error, '历史记录对照失败，未展示旧结果。')
  } finally {
    if (requestEpoch === comparisonEpoch) comparisonLoading.value = false
  }
}

async function replaySelected() {
  if (replayLoading.value || listLoading.value || !listLoaded.value || !canReplayAccess.value) return
  const selected = records.value.find(record => record.id === currentId.value)
  if (!selected) { replayError.value = '请先选择当前记录。'; return }
  const requestEpoch = ++replayEpoch
  const requestedFactory = props.factoryId
  const requestedRole = props.role
  const requestedDecisionId = selected.id
  replayLoading.value = true; replayError.value = ''; replay.value = null
  try {
    const response = await replayDecisionHistory(requestedFactory, requestedDecisionId)
    if (requestEpoch !== replayEpoch || requestedFactory !== props.factoryId || requestedRole !== props.role
      || requestedDecisionId !== currentId.value) return
    if (!response.success || !isDecisionRecommendationReplay(response.data, requestedDecisionId)) {
      replayError.value = response.message || '复算结果无法核验，未展示旧结果。'; return
    }
    replay.value = response.data
  } catch (error) {
    if (requestEpoch === replayEpoch && requestedFactory === props.factoryId && requestedRole === props.role
      && requestedDecisionId === currentId.value) replayError.value = errorMessage(error, '建议复算读取失败，未展示旧结果。')
  } finally {
    if (requestEpoch === replayEpoch) replayLoading.value = false
  }
}
function formatCreatedAt(value: string | null): string {
  if (!value) return '时间未提供'
  const date = new Date(value); return Number.isNaN(date.getTime()) ? '时间格式无法核验' : date.toLocaleString()
}
function recordLabel(record: DecisionHistoryRecord): string { return `${decisionTypeLabels[record.decisionType]} · ${record.entityType}/${record.entityId}` }
function reasonLabel(reason: string): string { return reasonLabels[reason] ? `${reasonLabels[reason]}（${reason}）` : `未识别原因（${reason}）` }
</script>

<template>
  <section class="decision-history-panel" data-testid="decision-history-panel" aria-labelledby="decision-history-title">
    <header class="panel-header"><div><p class="eyebrow">持久化证据</p><h2 id="decision-history-title">决策历史对照</h2><p class="subtitle">只读取当前工厂的历史记录；不会重新执行规则、审批或业务动作。</p></div><span class="scope-badge">当前工厂：{{ props.factoryId || '未选择' }}</span></header>
    <section v-if="!hasAccess" class="state-panel error-state" role="alert"><strong>暂时无法读取决策历史</strong><p>仅支持当前工厂的工厂超级管理员或权限管理员。</p></section>
    <template v-else>
      <div class="toolbar"><button class="primary-button" data-testid="history-load" type="button" :disabled="listLoading" @click="loadRecords(0)">{{ listLoading ? '正在读取…' : '读取历史记录' }}</button><span class="privacy-note">不显示原始输入、输出、人员姓名或财务载荷。</span></div>
      <section v-if="listError" class="state-panel error-state" role="alert"><strong>历史记录未读取</strong><p>{{ listError }}</p></section>
      <section v-else-if="listLoaded && !records.length" class="state-panel" role="status"><strong>当前页没有可用历史记录</strong><p>未生成示例记录；请读取其他页或确认当前工厂范围。</p></section>
      <section v-else-if="!listLoaded" class="state-panel" role="status"><strong>尚未读取历史记录</strong><p>点击“读取历史记录”后，从真实返回的记录中选择当前与基线。</p></section>
      <template v-else>
        <div class="selection-hint">每条记录可分别选作当前记录或基线；两者必须来自当前页且不能是同一条。</div>
        <div class="table-wrap"><table><caption class="sr-only">当前工厂决策历史记录</caption><thead><tr><th>记录</th><th>决策类型</th><th>规则版本</th><th>创建时间</th><th>当前</th><th>基线</th></tr></thead><tbody>
          <tr v-for="record in records" :key="record.id" :data-testid="`history-record-${record.id}`"><td><strong>{{ recordLabel(record) }}</strong><code>{{ record.id }}</code></td><td>{{ decisionTypeLabels[record.decisionType] }}</td><td>{{ record.ruleConfigId || '规则配置未提供' }}<span v-if="record.ruleConfigVersion !== null"> · v{{ record.ruleConfigVersion }}</span></td><td>{{ formatCreatedAt(record.createdAt) }}</td><td><input :checked="currentId === record.id" :data-testid="`history-current-${record.id}`" type="radio" name="decision-history-current" :aria-label="`选择${record.id}为当前记录`" @change="selectCurrent(record.id)" /></td><td><input :checked="baselineId === record.id" :data-testid="`history-baseline-${record.id}`" type="radio" name="decision-history-baseline" :aria-label="`选择${record.id}为基线记录`" @change="selectBaseline(record.id)" /></td></tr>
        </tbody></table></div>
        <div class="pagination" aria-label="历史记录分页"><button class="secondary-button" type="button" :disabled="listLoading || page <= 0" @click="loadRecords(page - 1)">上一页</button><span>第 {{ page + 1 }} 页<span v-if="totalPages"> / 共 {{ totalPages }} 页</span> · {{ totalElements }} 条</span><button class="secondary-button" type="button" :disabled="listLoading || totalPages === 0 || page + 1 >= totalPages" @click="loadRecords(page + 1)">下一页</button></div>
        <section class="compare-controls" aria-label="历史记录对照操作"><div><p v-if="currentRecord">当前：{{ recordLabel(currentRecord) }}</p><p v-else>当前：尚未选择</p><p v-if="baselineRecord">基线：{{ recordLabel(baselineRecord) }}</p><p v-else>基线：尚未选择</p><p v-if="replayAccessDenied" class="permission-note">当前账号缺少系统读取或质量读取权限，建议复算不可用。</p></div><div class="action-buttons"><button class="primary-button" data-testid="history-compare" type="button" :disabled="!canCompare" @click="compareSelected">{{ comparisonLoading ? '正在对照…' : '读取对照结果' }}</button><button class="secondary-button" data-testid="history-replay" type="button" :disabled="!currentRecord || replayLoading || listLoading || !canReplayAccess" @click="replaySelected">{{ replayLoading ? '正在复算…' : '复算记录建议' }}</button></div></section>
      </template>
      <section v-if="comparisonError" class="state-panel error-state" role="alert"><strong>对照未完成</strong><p>{{ comparisonError }}</p></section>
      <section v-if="comparison" class="comparison-card" data-testid="history-result" aria-live="polite"><div class="comparison-title"><div><p class="eyebrow">只读历史对照</p><h3>{{ statusLabels[comparison.status] }}</h3></div><span class="status-pill" :class="comparison.status.toLowerCase()">{{ comparison.reason }}</span></div><p class="reason-copy">{{ reasonLabel(comparison.reason) }}</p><div class="provenance-grid"><div><span>对照基础</span><strong>{{ basisLabels[comparison.comparisonBasis] }}</strong></div><div><span>记录关系</span><strong>{{ comparison.recordedOutputRelation }}</strong></div><div><span>当前规则配置</span><strong>{{ comparison.currentRuleConfigId || '未提供' }}<span v-if="comparison.currentRuleConfigVersion !== null"> · v{{ comparison.currentRuleConfigVersion }}</span></strong></div><div><span>基线规则配置</span><strong>{{ comparison.baselineRuleConfigId || '未提供' }}<span v-if="comparison.baselineRuleConfigVersion !== null"> · v{{ comparison.baselineRuleConfigVersion }}</span></strong></div></div><div class="hash-grid"><div><span>当前输出摘要</span><code>{{ comparison.canonicalRecordedOutputHash || '未提供' }}</code></div><div><span>基线输出摘要</span><code>{{ comparison.canonicalBaselineOutputHash || '未提供' }}</code></div></div><div class="truth-grid"><span>{{ comparison.integrityVerified ? '校验和已核验' : '校验和未核验' }}</span><span>不会重新执行规则</span><span>不会审批、保存或预留资源</span></div><p v-if="comparison.status === 'NOT_COMPARABLE'" class="warning-copy">不可比较结果保持原样展示；语义相同但校验和未核验时，也不会显示为已核验成功。</p></section>
      <p v-if="comparison" class="warning-copy" data-testid="history-integrity-limit">完整性核验仅表示记录与存储的校验和内部一致，不是防篡改签名；历史记录对照不代替业务审批。</p>
      <section v-if="replayError" class="state-panel error-state" role="alert"><strong>建议复算未完成</strong><p>{{ replayError }}</p></section>
      <section v-if="replay" class="comparison-card replay-card" data-testid="history-replay-result" aria-live="polite"><div class="comparison-title"><div><p class="eyebrow">只读建议复算</p><h3>{{ replayStatusLabels[replay.status] }}</h3></div><span class="status-pill" :class="replay.status.toLowerCase()">{{ replay.reason }}</span></div><p class="reason-copy">仅针对当前记录的质量建议快照；旧记录缺失或不支持快照时保持不可复算。</p><div class="provenance-grid"><div><span>评估器</span><strong>{{ replay.evaluatorId || '未提供' }}<span v-if="replay.evaluatorVersion"> · {{ replay.evaluatorVersion }}</span></strong></div><div><span>规则包摘要</span><code>{{ replay.ruleBundleHash || '未提供' }}</code></div><div><span>快照摘要</span><code>{{ replay.snapshotHash || '未提供' }}</code></div><div><span>记录建议 / 复算建议</span><strong>{{ replay.recordedRecommendation || '未提供' }} / {{ replay.replayedRecommendation || '未提供' }}</strong></div><div><span>记录置信度 / 复算置信度</span><strong>{{ replay.recordedConfidence || '未提供' }} / {{ replay.replayedConfidence || '未提供' }}</strong></div></div><div class="truth-grid"><span>{{ replay.snapshotConsistencyVerified ? '快照内部一致性已核验' : '快照内部一致性未核验' }}</span><span>{{ replay.ruleEvaluationPerformed ? '已执行纯建议复算' : '未执行建议复算' }}</span><span>仅质量建议，不审批、不执行业务</span></div><p class="warning-copy">动作与置信度均为纯输出对比；内部一致性核验不是防篡改签名，本结果不会审批、释放、执行、保存或预留资源。</p></section>
    </template>
  </section>
</template>

<style scoped>
.decision-history-panel { padding: 20px; color: var(--el-text-color-primary, #1a2332); }
.panel-header, .toolbar, .comparison-title, .pagination, .compare-controls, .action-buttons { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.panel-header { align-items: flex-start; margin-bottom: 16px; }
.eyebrow { margin: 0 0 6px; color: var(--el-text-color-secondary, #7a8599); font-size: 12px; }
h2, h3, p { margin: 0; }
h2 { font-size: 20px; }
h3 { font-size: 17px; }
.subtitle, .privacy-note, .selection-hint, .compare-controls p { color: var(--el-text-color-secondary, #7a8599); }
.permission-note { color: var(--el-color-warning, #b88230) !important; }
.subtitle { margin-top: 6px; line-height: 1.5; }
.scope-badge, .status-pill { padding: 5px 9px; border: 1px solid var(--el-border-color-lighter, #edf2f7); border-radius: 999px; font-size: 12px; }
.toolbar, .selection-hint, .pagination, .compare-controls { margin-bottom: 16px; }
.state-panel, .comparison-card { padding: 20px; border: 1px solid var(--el-border-color-lighter, #edf2f7); border-radius: 10px; background: var(--el-bg-color, #fff); box-shadow: 0 2px 12px rgb(27 101 168 / 6%); }
.state-panel p, .warning-copy, .reason-copy { margin-top: 8px; line-height: 1.6; }
.error-state { border-color: rgb(245 108 108 / 35%); color: var(--el-color-danger, #f56c6c); }
.primary-button, .secondary-button { min-height: 36px; padding: 0 14px; border-radius: 6px; font: inherit; cursor: pointer; }
.primary-button { border: 1px solid var(--el-color-primary, #409eff); background: var(--el-color-primary, #409eff); color: white; }
.secondary-button { border: 1px solid var(--el-color-primary, #1b65a8); background: white; color: var(--el-color-primary, #1b65a8); }
button:disabled { cursor: not-allowed; opacity: .55; }
.table-wrap { overflow-x: auto; border: 1px solid var(--el-border-color-lighter, #edf2f7); border-radius: 8px; }
table { width: 100%; min-width: 860px; border-collapse: collapse; }
th, td { padding: 11px 12px; border-bottom: 1px solid var(--el-border-color-lighter, #edf2f7); text-align: left; vertical-align: top; }
th { background: var(--el-fill-color-light, #f5f7fa); font-size: 13px; }
td strong, td code { display: block; }
td code, .hash-grid code, .provenance-grid code { margin-top: 4px; color: var(--el-text-color-secondary, #7a8599); font-size: 12px; overflow-wrap: anywhere; }
input[type='radio'] { width: 16px; height: 16px; accent-color: var(--el-color-primary, #409eff); }
.pagination { justify-content: center; margin-top: 14px; }
.compare-controls { flex-wrap: wrap; padding: 14px 0 0; }
.comparison-card { margin-top: 16px; }
.replay-card { border-color: rgb(64 158 255 / 28%); }
.comparison-title { align-items: flex-start; }
.status-pill.not_comparable { color: var(--el-color-warning, #e6a23c); }
.status-pill.same_result, .status-pill.different_result, .status-pill.replay_match, .status-pill.replay_different { color: var(--el-color-primary, #1b65a8); }
.status-pill.unavailable { color: var(--el-color-warning, #b88230); }
.reason-copy { color: var(--el-text-color-regular, #303133); }
.provenance-grid, .hash-grid, .truth-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }
.provenance-grid div, .hash-grid div { display: grid; min-width: 0; gap: 5px; padding: 10px; border: 1px solid var(--el-border-color-lighter, #edf2f7); border-radius: 6px; }
.provenance-grid span, .hash-grid span { color: var(--el-text-color-secondary, #7a8599); font-size: 12px; }
.truth-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); color: var(--el-text-color-secondary, #7a8599); font-size: 13px; }
.warning-copy { color: var(--el-color-warning, #b88230); }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 760px) { .panel-header, .toolbar, .comparison-title, .action-buttons { display: block; } .scope-badge { display: inline-block; margin-top: 12px; } .privacy-note { display: block; margin-top: 10px; } .action-buttons button { width: 100%; margin-top: 8px; } .provenance-grid, .hash-grid, .truth-grid { grid-template-columns: 1fr; } }
</style>
