<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import {
  evaluateProposalComparison,
  type ProposalCandidateEvaluation,
  type ProposalComparisonCandidate,
  type ProposalComparisonRequest,
  type ProposalComparisonReason,
  type ProposalComparisonResult,
  type ResourceBudgetUnit,
} from '@/api/proposalComparison'

type CandidateDraft = {
  id: string
  comparisonBasisId: string
  currency: string
  costUnit: string
  resourceUnit: ResourceBudgetUnit
  cost: string
  completionTime: string
  requiredResource: string
}

const ALLOWED_ROLES = new Set(['factory_super_admin', 'permission_admin'])
const REASON_LABELS: Record<ProposalComparisonReason, string> = {
  BASIS_MISMATCH: '比较口径不同',
  CURRENCY_MISMATCH: '币种不同',
  COST_UNIT_MISMATCH: '成本单位不同',
  RESOURCE_UNIT_MISMATCH: '资源单位不同',
  MISSING_COST: '缺少成本',
  MISSING_COMPLETION_TIME: '缺少完成时间',
  MISSING_RESOURCE_DEMAND: '缺少资源需求',
  MISSING_RESOURCE_CAPACITY: '缺少可用资源容量',
  COMPLETION_OUTSIDE_WINDOW: '完成时间不在比较窗口内',
  RESOURCE_SHORTAGE: '资源不足',
  DEADLINE_MISSED: '超过交付截止时间',
}
const RESULT_REASON_LABELS: Record<string, string> = {
  PARETO_FRONTIER_OF_SUPPLIED_FACTS: '按输入事实形成非支配集合',
  MISSING_OR_INCOMPATIBLE_FACTS: '输入事实缺失或口径不一致',
  INSUFFICIENT_ELIGIBLE_PROPOSALS: '可比较候选不足',
}
const RESULT_REASONS = new Set(Object.keys(RESULT_REASON_LABELS))
const CANDIDATE_STATUSES = new Set<ProposalCandidateEvaluation['status']>([
  'ELIGIBLE_BY_SUPPLIED_FACTS', 'EXCLUDED_BY_SUPPLIED_FACTS', 'NOT_COMPARABLE',
])
const CANDIDATE_REASONS = new Set(Object.keys(REASON_LABELS))

const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId || '')
const currentRole = computed(() => String(authStore.currentRole || ''))
const hasAccess = computed(() => !!factoryId.value && ALLOWED_ROLES.has(currentRole.value))

const form = reactive({
  comparisonBasisId: '',
  currency: '',
  costUnit: '',
  resourceUnit: 'PERSON_HOUR' as ResourceBudgetUnit,
  windowStart: '',
  windowEnd: '',
  requiredBy: '',
  availableResource: '',
  candidates: [] as CandidateDraft[],
})

const loading = ref(false)
const errorMessage = ref('')
const validationMessage = ref('')
const result = ref<ProposalComparisonResult | null>(null)
let requestEpoch = 0

function emptyCandidate(): CandidateDraft {
  return {
    id: '', comparisonBasisId: '', currency: '', costUnit: '', resourceUnit: form.resourceUnit,
    cost: '', completionTime: '', requiredResource: '',
  }
}

form.candidates.push(emptyCandidate(), emptyCandidate())

function invalidatePendingResult() {
  requestEpoch += 1
  result.value = null
  errorMessage.value = ''
  validationMessage.value = ''
  loading.value = false
}

watch([factoryId, currentRole], invalidatePendingResult)
watch(form, invalidatePendingResult, { deep: true })

function addCandidate() {
  if (form.candidates.length < 20) form.candidates.push(emptyCandidate())
}

function removeCandidate(index: number) {
  if (form.candidates.length > 2) form.candidates.splice(index, 1)
}

function parseNullableNumber(value: string, label: string, errors: string[]): number | null {
  if (!value.trim()) return null
  const trimmed = value.trim()
  if (!/^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/.test(trimmed)) {
    errors.push(`${label}必须是非负数字，整数最多12位且小数最多6位`)
    return null
  }
  const parsed = Number(trimmed)
  const serialized = JSON.stringify(parsed)
  const integerPart = trimmed.split('.')[0]
  if (!Number.isFinite(parsed) || !Number.isSafeInteger(Math.trunc(parsed)) || serialized === undefined
    || Number(serialized) !== parsed || integerPart.length > 12
    || canonicalDecimalText(trimmed) !== canonicalDecimalText(serialized)) {
    errors.push(`${label}超出可精确传输的数字范围`)
    return null
  }
  return parsed
}

function canonicalDecimalText(value: string) {
  const [integer, fraction = ''] = value.split('.')
  const normalizedInteger = integer.replace(/^0+(?=\d)/, '')
  const normalizedFraction = fraction.replace(/0+$/, '')
  return normalizedFraction ? `${normalizedInteger}.${normalizedFraction}` : normalizedInteger
}

function toOffsetDateTime(value: string, label: string, errors: string[]): string | null {
  if (!value) return null
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    errors.push(`${label}格式无效`)
    return null
  }
  return parsed.toISOString()
}

function buildRequest(): ProposalComparisonRequest | null {
  const errors: string[] = []
  if (!factoryId.value) errors.push('当前没有可用工厂')
  if (!hasAccess.value) errors.push('当前账号没有方案比较权限')
  if (!form.comparisonBasisId.trim()) errors.push('请填写共同比较口径')
  if (!/^[A-Z]{3}$/.test(form.currency.trim())) errors.push('币种请填写三位大写代码')
  if (!form.costUnit.trim()) errors.push('请填写成本单位')
  if (!form.windowStart || !form.windowEnd) errors.push('请填写完整的比较时间窗口')
  if (form.candidates.length < 2 || form.candidates.length > 20) errors.push('候选方案数量必须为2至20个')

  const windowStart = toOffsetDateTime(form.windowStart, '窗口开始时间', errors)
  const windowEnd = toOffsetDateTime(form.windowEnd, '窗口结束时间', errors)
  const requiredBy = toOffsetDateTime(form.requiredBy, '交付截止时间', errors)
  const availableResource = parseNullableNumber(form.availableResource, '可用资源', errors)
  const candidates: ProposalComparisonCandidate[] = form.candidates.map((candidate, index) => ({
    id: candidate.id.trim(),
    comparisonBasisId: candidate.comparisonBasisId.trim(),
    currency: candidate.currency.trim(),
    costUnit: candidate.costUnit.trim(),
    resourceUnit: candidate.resourceUnit,
    cost: parseNullableNumber(candidate.cost, `候选${index + 1}成本`, errors),
    completionTime: toOffsetDateTime(candidate.completionTime, `候选${index + 1}完成时间`, errors),
    requiredResource: parseNullableNumber(candidate.requiredResource, `候选${index + 1}资源需求`, errors),
  }))
  candidates.forEach((candidate, index) => {
    if (!candidate.id) errors.push(`请填写候选${index + 1}编号`)
    if (!candidate.comparisonBasisId) errors.push(`请填写候选${index + 1}比较口径`)
    if (!candidate.currency) errors.push(`请填写候选${index + 1}币种`)
    if (!candidate.costUnit) errors.push(`请填写候选${index + 1}成本单位`)
  })
  if (errors.length) {
    validationMessage.value = errors[0]
    return null
  }
  return {
    comparisonBasisId: form.comparisonBasisId.trim(),
    currency: form.currency.trim(),
    costUnit: form.costUnit.trim(),
    resourceUnit: form.resourceUnit,
    windowStart: windowStart as string,
    windowEnd: windowEnd as string,
    requiredBy,
    availableResource,
    candidates,
  }
}

function isResult(value: unknown, request: ProposalComparisonRequest): value is ProposalComparisonResult {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<ProposalComparisonResult>
  if ((candidate.status !== 'SUPPLIED_FACTS_COMPARABLE' && candidate.status !== 'NOT_COMPARABLE')
    || candidate.comparisonBasisId !== request.comparisonBasisId
    || typeof candidate.reason !== 'string' || !RESULT_REASONS.has(candidate.reason)
    || !Array.isArray(candidate.candidates) || !Array.isArray(candidate.nonDominatedCandidateIds)
    || candidate.factsVerified !== false || candidate.businessExecutionPerformed !== false
    || candidate.resourcesReserved !== false) return false
  const requestIds = new Set(request.candidates.map(item => item.id))
  const resultIds = new Set<string>()
  if (candidate.candidates.length !== request.candidates.length) return false
  for (const item of candidate.candidates) {
    if (!item || typeof item !== 'object' || typeof item.id !== 'string' || resultIds.has(item.id)
      || !requestIds.has(item.id) || !CANDIDATE_STATUSES.has(item.status as ProposalCandidateEvaluation['status'])
      || !Array.isArray(item.reasons) || item.reasons.some(reason => typeof reason !== 'string' || !CANDIDATE_REASONS.has(reason))
      || !Array.isArray(item.dominatedByCandidateIds)
      || item.dominatedByCandidateIds.some(id => typeof id !== 'string' || !requestIds.has(id))) return false
    resultIds.add(item.id)
  }
  if (resultIds.size !== requestIds.size
    || candidate.nonDominatedCandidateIds.some(id => typeof id !== 'string' || !requestIds.has(id))) return false
  const excludedReasons = new Set(['RESOURCE_SHORTAGE', 'DEADLINE_MISSED'])
  const eligible = candidate.candidates.filter(item => item.status === 'ELIGIBLE_BY_SUPPLIED_FACTS')
  const eligibleIds = new Set(eligible.map(item => item.id))
  const hasUnknown = candidate.candidates.some(item => item.status === 'NOT_COMPARABLE')
  for (const item of candidate.candidates) {
    if (item.status === 'ELIGIBLE_BY_SUPPLIED_FACTS' && item.reasons.length !== 0) return false
    if (item.status === 'EXCLUDED_BY_SUPPLIED_FACTS'
      && (!item.reasons.length || item.reasons.some(reason => !excludedReasons.has(reason)))) return false
    if (item.status === 'NOT_COMPARABLE'
      && (!item.reasons.length || item.reasons.some(reason => excludedReasons.has(reason)))) return false
    if (new Set(item.dominatedByCandidateIds).size !== item.dominatedByCandidateIds.length
      || item.dominatedByCandidateIds.some(id => id === item.id || !eligibleIds.has(id))
      || (!eligibleIds.has(item.id) && item.dominatedByCandidateIds.length !== 0)) return false
  }
  if (new Set(candidate.nonDominatedCandidateIds).size !== candidate.nonDominatedCandidateIds.length) return false
  if (candidate.nonDominatedCandidateIds.some(id => {
    const evaluation = candidate.candidates.find(item => item.id === id)
    return evaluation.status !== 'ELIGIBLE_BY_SUPPLIED_FACTS' || evaluation.dominatedByCandidateIds.length > 0
  })) return false
  if (candidate.status === 'SUPPLIED_FACTS_COMPARABLE') {
    const expectedFrontier = eligible.filter(item => item.dominatedByCandidateIds.length === 0).map(item => item.id)
    if (candidate.reason !== 'PARETO_FRONTIER_OF_SUPPLIED_FACTS' || hasUnknown || eligible.length < 2
      || expectedFrontier.length === 0 || expectedFrontier.length !== candidate.nonDominatedCandidateIds.length
      || expectedFrontier.some(id => !candidate.nonDominatedCandidateIds.includes(id))) return false
  } else {
    if (candidate.nonDominatedCandidateIds.length !== 0
      || candidate.candidates.some(item => item.dominatedByCandidateIds.length !== 0)) return false
    if (candidate.reason === 'MISSING_OR_INCOMPATIBLE_FACTS') {
      if (!hasUnknown) return false
    } else if (candidate.reason !== 'INSUFFICIENT_ELIGIBLE_PROPOSALS' || hasUnknown || eligible.length >= 2) return false
  }
  return true
}

async function evaluate() {
  if (loading.value) return
  validationMessage.value = ''
  errorMessage.value = ''
  result.value = null
  const body = buildRequest()
  if (!body) return
  const epoch = ++requestEpoch
  const requestedFactory = factoryId.value
  loading.value = true
  try {
    const response = await evaluateProposalComparison(requestedFactory, body)
    if (epoch !== requestEpoch || requestedFactory !== factoryId.value) return
    if (!response.success) {
      errorMessage.value = response.message || '比较未完成，请检查输入后重试'
      return
    }
    if (!isResult(response.data, body)) {
      errorMessage.value = '返回结果无法核实，未展示旧结果'
      return
    }
    result.value = response.data
  } catch (error) {
    if (epoch !== requestEpoch || requestedFactory !== factoryId.value) return
    const candidate = error as { response?: { data?: { message?: string } }; message?: string }
    errorMessage.value = candidate.response?.data?.message || candidate.message || '比较请求失败，请稍后重试'
  } finally {
    if (epoch === requestEpoch) loading.value = false
  }
}

function statusLabel(status: ProposalComparisonResult['status']) {
  return status === 'SUPPLIED_FACTS_COMPARABLE' ? '同口径事实可比较' : '暂不可比较'
}

function candidateStatusLabel(status: ProposalCandidateEvaluation['status']) {
  return status === 'ELIGIBLE_BY_SUPPLIED_FACTS' ? '按输入事实可纳入比较'
    : status === 'EXCLUDED_BY_SUPPLIED_FACTS' ? '因输入约束排除'
      : '事实缺失或口径不一致'
}

function reasonLabel(reason: string) {
  return REASON_LABELS[reason as ProposalComparisonReason] || '后端返回了未识别的原因'
}

function resultReasonLabel(reason: string) {
  return RESULT_REASON_LABELS[reason] || '返回了未识别的比较结论'
}

function booleanStatement(value: boolean, yes: string, no: string) {
  return value ? yes : no
}
</script>

<template>
  <main class="proposal-comparison page-container">
    <header class="page-header">
      <div>
        <h1>多方案比较</h1>
        <p class="subtitle">按同一口径查看候选方案的成本、交期与资源取舍</p>
      </div>
    </header>

    <section v-if="!hasAccess" class="state-panel" role="alert">
      <h2>暂时无法使用方案比较</h2>
      <p v-if="!factoryId">当前账号没有可用工厂范围。</p>
      <p v-else>仅工厂超级管理员或权限管理员可查看此页面。</p>
    </section>

    <template v-else>
      <section class="notice-panel" aria-label="安全说明">
        <strong>使用边界</strong>
        <span>输入事实未核实</span>
        <span>本页不会执行方案</span>
        <span>本页不会预留资源</span>
      </section>

      <form class="comparison-form" @submit.prevent="evaluate">
        <section class="form-card">
          <div class="section-heading">
            <div>
              <h2>共同口径</h2>
              <p>窗口首尾均包含；缺失事实会保留为未知，不会自动补零。</p>
            </div>
          </div>
          <div class="form-grid">
            <label>比较口径<input v-model="form.comparisonBasisId" maxlength="64" autocomplete="off" placeholder="例如：同一订单批次" /></label>
            <label>币种<input v-model="form.currency" maxlength="3" autocomplete="off" placeholder="例如：CNY（人民币）" /></label>
            <label>成本单位<input v-model="form.costUnit" maxlength="32" autocomplete="off" placeholder="例如：每批、每个订单" /></label>
            <label>资源预算单位
              <select v-model="form.resourceUnit">
                <option value="PERSON_HOUR">人时</option>
                <option value="MACHINE_HOUR">机时</option>
              </select>
            </label>
            <label>窗口开始<input v-model="form.windowStart" type="datetime-local" /></label>
            <label>窗口结束<input v-model="form.windowEnd" type="datetime-local" /></label>
            <label>可选交付截止<input v-model="form.requiredBy" type="datetime-local" /></label>
            <label>可用资源容量<input v-model="form.availableResource" inputmode="decimal" placeholder="留空表示未知" /></label>
          </div>
        </section>

        <section class="form-card">
          <div class="section-heading candidate-heading">
            <div>
              <h2>候选方案（{{ form.candidates.length }}/20）</h2>
              <p>候选之间口径不同会被标记为不可比，不会被悄悄换算。</p>
            </div>
            <button class="secondary-button" type="button" :disabled="form.candidates.length >= 20" @click="addCandidate">增加候选</button>
          </div>
          <div v-for="(candidate, index) in form.candidates" :key="index" class="candidate-card">
            <div class="candidate-title"><strong>候选 {{ index + 1 }}</strong><button v-if="form.candidates.length > 2" type="button" class="text-button" @click="removeCandidate(index)">移除</button></div>
            <div class="form-grid candidate-grid">
              <label>编号<input v-model="candidate.id" maxlength="64" autocomplete="off" placeholder="例如：A、B（字母或数字）" /></label>
              <label>比较口径<input v-model="candidate.comparisonBasisId" maxlength="64" autocomplete="off" placeholder="与共同口径一致" /></label>
              <label>币种<input v-model="candidate.currency" maxlength="3" autocomplete="off" placeholder="例如：CNY" /></label>
              <label>成本单位<input v-model="candidate.costUnit" maxlength="32" autocomplete="off" placeholder="与共同口径一致" /></label>
              <label>成本<input v-model="candidate.cost" inputmode="decimal" placeholder="留空表示未知" /></label>
              <label>完成时间<input v-model="candidate.completionTime" type="datetime-local" /></label>
              <label>资源需求<input v-model="candidate.requiredResource" inputmode="decimal" placeholder="留空表示未知" /></label>
              <label>资源单位
                <select v-model="candidate.resourceUnit">
                  <option value="PERSON_HOUR">人时</option>
                  <option value="MACHINE_HOUR">机时</option>
                </select>
              </label>
            </div>
          </div>
          <p v-if="validationMessage" class="inline-error" role="alert">{{ validationMessage }}</p>
          <button class="primary-button" type="submit" :disabled="loading">
            {{ loading ? '正在比较…' : '查看比较结果' }}
          </button>
        </section>
      </form>

      <section v-if="errorMessage" class="state-panel error-panel" role="alert">
        <h2>比较未完成</h2>
        <p>{{ errorMessage }}</p>
        <p>请检查输入或稍后重试；未展示旧结果。</p>
      </section>
      <section v-if="loading" class="state-panel" aria-live="polite"><p>正在核对输入口径，尚未执行方案或预留资源。</p></section>

      <section v-if="result" class="result-card" aria-live="polite">
        <div class="result-heading">
          <div>
            <span class="eyebrow">比较结果</span>
            <h2>{{ statusLabel(result.status) }}</h2>
          </div>
          <span class="result-reason">{{ resultReasonLabel(result.reason) }}</span>
        </div>
        <div class="truth-grid">
          <span>{{ booleanStatement(result.factsVerified, '输入事实已标记为已核实', '输入事实未核实') }}</span>
          <span>{{ booleanStatement(result.businessExecutionPerformed, '发生了业务执行', '本次未执行方案') }}</span>
          <span>{{ booleanStatement(result.resourcesReserved, '已预留资源', '本次未预留资源') }}</span>
        </div>
        <div v-if="result.status === 'SUPPLIED_FACTS_COMPARABLE'" class="frontier-panel">
          <strong>非支配候选</strong>
          <span v-if="result.nonDominatedCandidateIds.length">{{ result.nonDominatedCandidateIds.join('、') }}</span>
          <span v-else>没有可展示的非支配候选</span>
        </div>
        <p v-else class="empty-result">当前输入不足以形成可靠的比较集合；请修正口径或补齐事实后重新提交。</p>
        <div class="evaluation-list">
          <article v-for="evaluation in result.candidates" :key="evaluation.id" class="evaluation-row">
            <div class="evaluation-main"><strong>{{ evaluation.id }}</strong><span>{{ candidateStatusLabel(evaluation.status) }}</span></div>
            <div v-if="evaluation.reasons.length" class="reason-list">{{ evaluation.reasons.map(reasonLabel).join('、') }}</div>
            <div v-if="evaluation.dominatedByCandidateIds.length" class="reason-list">被以下候选支配：{{ evaluation.dominatedByCandidateIds.join('、') }}</div>
          </article>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.proposal-comparison { padding: 20px; color: var(--el-text-color-primary, #1A2332); }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
h1, h2, p { margin: 0; }
h1 { font-size: 24px; line-height: 1.3; }
h2 { font-size: 17px; line-height: 1.4; }
.subtitle, .section-heading p { color: var(--el-text-color-secondary, #7A8599); margin-top: 6px; }
.notice-panel, .form-card, .result-card, .state-panel { background: var(--el-bg-color, #fff); border: 1px solid var(--el-border-color-lighter, #EDF2F7); border-radius: 10px; box-shadow: 0 2px 12px rgba(27,101,168,.06); }
.notice-panel { display: flex; flex-wrap: wrap; gap: 12px 20px; padding: 12px 16px; margin-bottom: 16px; color: var(--el-text-color-secondary, #7A8599); }
.notice-panel strong { color: var(--el-color-primary, #1B65A8); }
.form-card, .result-card { padding: 20px; margin-bottom: 16px; }
.section-heading { margin-bottom: 16px; }
.candidate-heading { display: flex; justify-content: space-between; align-items: flex-start; }
.form-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px 16px; }
label { display: flex; flex-direction: column; gap: 6px; color: var(--el-text-color-regular, #303133); font-size: 13px; }
input, select { width: 100%; min-height: 36px; border: 1px solid var(--el-border-color, #dcdfe6); border-radius: 6px; padding: 7px 10px; background: #fff; color: inherit; font: inherit; box-sizing: border-box; }
input:focus, select:focus { outline: 2px solid rgba(64,158,255,.28); border-color: var(--el-color-primary, #409EFF); }
.candidate-card { border: 1px solid var(--el-border-color-lighter, #EDF2F7); border-radius: 8px; padding: 14px; margin-bottom: 12px; }
.candidate-title { display: flex; justify-content: space-between; margin-bottom: 12px; }
.primary-button, .secondary-button { border-radius: 6px; min-height: 36px; padding: 0 16px; cursor: pointer; font: inherit; }
.primary-button { background: var(--el-color-primary, #409EFF); color: #fff; border: 1px solid var(--el-color-primary, #409EFF); margin-top: 8px; }
.secondary-button { background: #fff; color: var(--el-color-primary, #1B65A8); border: 1px solid var(--el-color-primary, #1B65A8); }
.primary-button:disabled, .secondary-button:disabled { opacity: .55; cursor: not-allowed; }
.text-button { border: 0; background: transparent; color: var(--el-color-danger, #F56C6C); cursor: pointer; }
.inline-error, .error-panel { color: var(--el-color-danger, #F56C6C); }
.inline-error { margin-top: 12px; }
.state-panel { padding: 24px; margin-bottom: 16px; }
.state-panel p { margin-top: 8px; line-height: 1.6; }
.error-panel { border-color: rgba(245,108,108,.35); }
.result-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.eyebrow { color: var(--el-color-primary, #1B65A8); font-size: 12px; }
.result-reason { color: var(--el-text-color-secondary, #7A8599); font-size: 13px; }
.truth-grid { display: flex; flex-wrap: wrap; gap: 8px 16px; margin: 16px 0; color: var(--el-text-color-secondary, #7A8599); font-size: 13px; }
.frontier-panel { background: #f4f8fc; border-radius: 8px; padding: 12px; display: flex; gap: 12px; margin-bottom: 16px; }
.empty-result { padding: 12px; background: #fff8eb; border-radius: 8px; color: #8a5a00; margin-bottom: 16px; }
.evaluation-list { display: grid; gap: 8px; }
.evaluation-row { padding: 12px; border: 1px solid var(--el-border-color-lighter, #EDF2F7); border-radius: 8px; }
.evaluation-main { display: flex; gap: 12px; align-items: center; }
.evaluation-main span { color: var(--el-text-color-secondary, #7A8599); }
.reason-list { margin-top: 6px; color: var(--el-text-color-secondary, #7A8599); font-size: 13px; }
@media (max-width: 1100px) { .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .proposal-comparison { padding: 12px; } .form-grid { grid-template-columns: 1fr; } .candidate-heading, .result-heading { flex-direction: column; } }
</style>
