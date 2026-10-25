<template>
  <section data-testid="formal-activation-panel" aria-label="配置应用与 Workflow 发布">
    <h3>配置应用与 Workflow 发布</h3>
    <p>这两项分别预览、分别确认。申请人和审批人必须不同；审批人不能执行自己的审批。</p>
    <article v-for="capability in capabilities" :key="capability" :data-testid="`formal-${capability}`" class="formal-card">
      <h4>{{ label(capability) }}</h4>
      <template v-if="capability === 'modules'">
        <label>配置草稿
          <select v-model="configDraftId" data-testid="formal-config-selection" :disabled="busy">
            <option value="">请选择已写入的配置草稿</option>
            <option v-for="draft in drafts('modules')" :key="draft.draftId" :value="draft.draftId">{{ draft.label }} · {{ draft.draftId }}</option>
          </select>
        </label>
      </template>
      <template v-else>
        <label>Workflow 草稿
          <select v-model="workflowDraftId" data-testid="formal-workflow-selection" :disabled="busy">
            <option value="">请选择 Workflow 草稿</option>
            <option v-for="draft in drafts('workflow')" :key="draft.draftId" :value="draft.draftId">{{ draft.label }} · {{ draft.draftId }}</option>
          </select>
        </label>
        <label>已转正的配方草稿
          <select v-model="bomDraftId" data-testid="formal-bom-selection" :disabled="busy">
            <option value="">请选择已转正的配方草稿</option>
            <option v-for="draft in drafts('bom')" :key="draft.draftId" :value="draft.draftId">{{ draft.label }} · {{ draft.draftId }}</option>
          </select>
        </label>
        <label>关键控制点草稿
          <select v-model="criticalControlsDraftId" data-testid="formal-critical-selection" :disabled="busy">
            <option value="">请选择关键控制点草稿</option>
            <option v-for="draft in drafts('critical-controls')" :key="draft.draftId" :value="draft.draftId">{{ draft.label }} · {{ draft.draftId }}</option>
          </select>
        </label>
      </template>
      <el-button :disabled="!canConfirm || busy" :data-testid="`formal-preview-${capability}`" @click="preview(capability)">预览{{ label(capability) }}</el-button>
      <div v-if="plans[capability]" :data-testid="`formal-plan-${capability}`">
        <p>预览状态：{{ plans[capability]!.status }} · 摘要 {{ plans[capability]!.planDigest }}</p>
        <ul><li v-for="issue in plans[capability]!.issues" :key="issue.code">{{ issue.message }} · {{ issue.recovery }}</li></ul>
        <el-button :disabled="!canConfirm || busy || plans[capability]!.status !== 'READY_FOR_APPROVAL'"
          :data-testid="`formal-request-${capability}`" @click="requestApproval(capability)">确认申请{{ label(capability) }}</el-button>
      </div>
      <div class="recoverable" :data-testid="`formal-recoverable-${capability}`">
        <p v-if="recoverableError" role="alert">{{ recoverableError }}</p>
        <p v-else-if="!recoverable(capability).length" class="muted">本蓝图暂无可恢复的{{ label(capability) }}申请</p>
        <ul v-else>
          <li v-for="item in recoverable(capability)" :key="item.approval.approvalId"
            :data-testid="`formal-recoverable-item-${item.approval.approvalId}`">
            <span>{{ statusText(item.approval.status) }} · 蓝图第{{ item.blueprintVersion ?? '?' }}版 · {{ item.approval.approvalId }}</span>
            <span v-if="item.approval.status === 'APPROVED'"> · 有效期至 {{ item.approval.expiresAt ?? '未设定' }}</span>
            <el-button v-if="continuable(item)" size="small" :disabled="!canConfirm || busy"
              :data-testid="`formal-resume-${item.approval.approvalId}`" @click="resume(capability, item.approval.approvalId)">
              {{ item.recoverability === 'RECEIPT_ONLY' ? '查看原执行回执' : '继续此申请' }}
            </el-button>
            <span v-else class="muted" :data-testid="`formal-readonly-${item.approval.approvalId}`">{{ readonlyReason(item) }}</span>
          </li>
        </ul>
      </div>
      <label>确认申请编号
        <input v-model="approvalIds[capability]" :data-testid="`formal-approval-id-${capability}`" :disabled="busy" placeholder="输入本步骤的确认申请编号" />
      </label>
      <el-button :disabled="!canConfirm || busy || !approvalIds[capability]" :data-testid="`formal-read-${capability}`" @click="readApproval(capability)">读取申请及审批结果</el-button>
      <div v-if="approvals[capability]" :data-testid="`formal-approval-${capability}`">
        <p>申请 {{ approvals[capability]!.approvalId }} · {{ approvals[capability]!.status }}</p>
        <p>申请人 {{ actorText(approvals[capability]!.requestedActor) }}；审批人 {{ actorText(approvals[capability]!.approvedActor) }}</p>
        <label v-if="approvals[capability]!.status === 'REQUESTED'">审批说明<input v-model="reasons[capability]" :data-testid="`formal-reason-${capability}`" /></label>
        <el-button v-if="approvals[capability]!.status === 'REQUESTED'" :disabled="!canConfirm || busy || !reasons[capability].trim()"
          :data-testid="`formal-approve-${capability}`" @click="approve(capability)">由另一位操作人确认审批</el-button>
        <el-button v-if="approvals[capability]!.status === 'APPROVED' || approvals[capability]!.status === 'EXECUTED'" :disabled="!canConfirm || busy"
          :data-testid="`formal-execute-${capability}`" @click="execute(capability)">
          {{ approvals[capability]!.status === 'EXECUTED' ? '回放原执行回执' : `执行${label(capability)}` }}
        </el-button>
      </div>
      <p v-if="errors[capability]" role="alert" :data-testid="`formal-error-${capability}`">{{ errors[capability] }}</p>
      <div v-if="results[capability]" :data-testid="`formal-result-${capability}`">
        <p>执行状态 {{ results[capability]!.status }} · 本次写入 {{ results[capability]!.invocationBusinessWriteCount }} · {{ results[capability]!.readonlyReadback ? '只读回读' : results[capability]!.idempotentReplay ? '幂等回放' : '首次执行' }}</p>
        <p v-for="runtime in results[capability]!.verifiedRuntime" :key="runtime.targetRuntimeId">
          {{ runtime.runtimeState === 'ACTIVE' ? `${label(capability)}已完成并回读` : `当前运行状态 ${runtime.runtimeState}` }} · 正式编号 {{ runtime.targetRuntimeId }} · 版本 {{ runtime.activeRevision }}
        </p>
        <p v-for="module in results[capability]!.modules.filter(module => module.status !== 'ACTIVATED')" :key="module.capabilityId" role="alert">{{ module.failureMessage }} · {{ module.recovery }}</p>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { WrittenCapability } from '@/api/factoryBlueprintDraftApi'
import { previewFormal, requestFormal, readFormal, readFormalReceipt, approveFormal, executeFormal,
  listRecoverableFormal,
  type FormalCapability, type FormalSelection, type FormalPlan, type FormalApproval,
  type FormalExecution, type FormalActor, type FormalRecoverable } from '@/api/factoryBlueprintActivationApi'
import { draftErrorMessage } from '../factoryBlueprintDrafts'
const props = defineProps<{ factoryId: string; blueprintId: string; canConfirm: boolean; written: WrittenCapability[] | null }>()
const emit = defineEmits<{ changed: []; formalState: [FormalCapability, FormalExecution | null] }>()
const capabilities: FormalCapability[] = ['modules', 'workflow']
const configDraftId = ref(''), workflowDraftId = ref(''), bomDraftId = ref(''), criticalControlsDraftId = ref('')
const plans = reactive<Partial<Record<FormalCapability, FormalPlan>>>({})
const approvals = reactive<Partial<Record<FormalCapability, FormalApproval>>>({})
const results = reactive<Partial<Record<FormalCapability, FormalExecution>>>({})
const approvalIds = reactive({ modules: '', workflow: '' }), reasons = reactive({ modules: '', workflow: '' })
const errors = reactive({ modules: '', workflow: '' }), busy = ref(false)
const label = (capability: FormalCapability) => capability === 'modules' ? '配置应用' : 'Workflow 发布'
const drafts = (capability: string) => props.written?.find(value => value.capabilityId === capability)?.drafts ?? []
const actorText = (actor: FormalActor | null) => actor ? `${actor.type} / ${actor.id}` : '尚未审批'
function selection(capability: FormalCapability): FormalSelection {
  return capability === 'modules' ? { capabilityId: capability, configDraftId: configDraftId.value }
    : { capabilityId: capability, workflowDraftId: Number(workflowDraftId.value), bomDraftId: bomDraftId.value,
      criticalControlsDraftId: criticalControlsDraftId.value }
}
async function action(capability: FormalCapability, operation: () => Promise<void>) {
  if (busy.value || !props.canConfirm) return
  errors[capability] = ''; busy.value = true
  try { await operation() } catch (error) {
    if (error !== 'cancel' && error !== 'close') errors[capability] = draftErrorMessage(error)
  } finally { busy.value = false }
}
async function preview(capability: FormalCapability) {
  await action(capability, async () => { plans[capability] = (await previewFormal(props.factoryId, props.blueprintId, selection(capability))).data })
}
async function requestApproval(capability: FormalCapability) {
  await action(capability, async () => {
    const plan = plans[capability]; if (!plan || plan.status !== 'READY_FOR_APPROVAL') return
    await ElMessageBox.confirm(`单独申请${label(capability)}。服务端将在审批和执行时重新核对前置条件。`, label(capability), { type: 'warning' })
    const approval = (await requestFormal(props.factoryId, props.blueprintId, selection(capability), plan.planDigest)).data
    approvals[capability] = approval; approvalIds[capability] = approval.approvalId
    void loadRecoverable()
  })
}
async function readApproval(capability: FormalCapability) {
  await action(capability, async () => {
    delete results[capability]; emit('formalState', capability, null)
    const approval = (await readFormal(props.factoryId, props.blueprintId, approvalIds[capability])).data
    if (approval.capabilityId !== capability) throw new Error('确认申请属于另一项操作，请核对编号')
    approvals[capability] = approval
    if (approval.status === 'EXECUTED') {
      const result = (await readFormalReceipt(props.factoryId, props.blueprintId, approval.approvalId)).data
      results[capability] = result; emit('formalState', capability, result)
    }
  })
}
async function approve(capability: FormalCapability) {
  await action(capability, async () => {
    const approval = approvals[capability]; if (!approval) return
    await ElMessageBox.confirm(`由当前登录人审批${label(capability)}，不执行写入。`, '确认审批', { type: 'warning' })
    approvals[capability] = (await approveFormal(props.factoryId, props.blueprintId, approval, reasons[capability])).data
    void loadRecoverable()
  })
}
async function execute(capability: FormalCapability) {
  await action(capability, async () => {
    const approval = approvals[capability]; if (!approval || !approval.approvalDigest) return
    await ElMessageBox.confirm(`单独执行${label(capability)}，将改变正式运行数据。`, label(capability), { type: 'warning' })
    const result = (await executeFormal(props.factoryId, props.blueprintId, approval)).data
    results[capability] = result; approvals[capability] = result.approval
    emit('formalState', capability, result); emit('changed')
    void loadRecoverable()
  })
}
// Persisted approvals of this blueprint, judged by the server against its current blueprint. Listing
// only fills in the number; approve and execute still go through every server check.
const recoverableItems = ref<FormalRecoverable[]>([]), recoverableError = ref('')
let recoverableSequence = 0
const recoverable = (capability: FormalCapability) =>
  recoverableItems.value.filter(item => item.approval.capabilityId === capability)
const continuable = (item: FormalRecoverable) =>
  item.recoverability === 'CONTINUE_APPROVAL' || item.recoverability === 'CONTINUE_EXECUTION'
  || item.recoverability === 'RECEIPT_ONLY'
const statusText = (status: FormalApproval['status']) =>
  status === 'REQUESTED' ? '待审批' : status === 'APPROVED' ? '已审批待执行' : '已执行'
const readonlyReason = (item: FormalRecoverable) => ({
  EXPIRED: '审批已过期，不能执行，请重新预览并申请',
  HISTORICAL_VERSION: '属于其他蓝图版本，仅供查看',
  BLUEPRINT_CHANGED: '蓝图已变更，仅供查看，请重新预览并申请',
  SNAPSHOT_UNREADABLE: '申请记录无法核对，不能继续',
} as Record<string, string>)[item.recoverability] ?? '不能继续'
async function loadRecoverable() {
  const own = ++recoverableSequence, factoryId = props.factoryId, blueprintId = props.blueprintId
  recoverableItems.value = []; recoverableError.value = ''
  if (!factoryId || !blueprintId) return
  try {
    const list = (await listRecoverableFormal(factoryId, blueprintId)).data
    if (own === recoverableSequence) recoverableItems.value = list.approvals
  } catch (error) {
    if (own === recoverableSequence) recoverableError.value = draftErrorMessage(error)
  }
}
async function resume(capability: FormalCapability, approvalId: string) {
  approvalIds[capability] = approvalId
  await readApproval(capability)
}
watch([configDraftId, workflowDraftId, bomDraftId, criticalControlsDraftId], () => {
  // Changing selections invalidates a preview; an existing persisted approval must be explicitly read again.
  delete plans.modules; delete plans.workflow
})
watch(() => [props.factoryId, props.blueprintId], () => {
  for (const capability of capabilities) {
    delete plans[capability]; delete approvals[capability]; delete results[capability]
    approvalIds[capability] = ''; errors[capability] = ''
  }
})
watch(() => [props.factoryId, props.blueprintId], () => { void loadRecoverable() }, { immediate: true })
</script>

<style scoped>
.formal-card { border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 14px; margin: 12px 0; }
label { display: block; margin: 8px 0; }
select, input { display: block; width: min(100%, 640px); padding: 8px; }
[role="alert"] { color: var(--el-color-danger); }
p { overflow-wrap: anywhere; }
</style>
