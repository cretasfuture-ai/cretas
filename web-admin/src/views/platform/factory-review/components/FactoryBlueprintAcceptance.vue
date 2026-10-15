<template>
  <section class="acceptance-panel" aria-labelledby="blueprint-acceptance-title">
    <div class="acceptance-head">
      <div>
        <span class="eyebrow">配置应用后 · 人工验收</span>
        <h3 id="blueprint-acceptance-title">逐能力留下明确结论</h3>
        <p>配置写入成功不等于已经验收。这里仅追加不可变验收回执，不会重写配置，也不会发布 Workflow。</p>
      </div>
      <el-tag :type="overallTone" effect="plain">{{ overallLabel }}</el-tag>
    </div>

    <el-alert
      v-if="!eligibleCapabilities.length"
      type="warning"
      :closable="false"
      show-icon
      title="当前没有可提交验收的能力"
      description="只有当前 APPLIED 蓝图中具备完整成功配置回执的能力才能验收；失败或需人工复核的配置必须先修正并创建新版本。"
    />

    <div v-else class="acceptance-list">
      <article v-for="capability in eligibleCapabilities" :key="capability.id" class="acceptance-item">
        <div class="capability-copy">
          <div class="capability-title">
            <strong>{{ capability.label }}</strong>
            <el-tag size="small" :type="stateTone(capability.acceptanceState)" effect="plain">
              {{ stateLabel(capability.acceptanceState) }}
            </el-tag>
          </div>
          <p>{{ capability.description }}</p>
          <small>
            配置技术回执 {{ capability.configurationReceipts.length }} 条 ·
            人工验收历史 {{ capability.acceptanceReceipts.length }} 条
          </small>
        </div>
        <div class="decision-form">
          <el-radio-group v-model="drafts[capability.id].decision" :aria-label="`${capability.label} 验收决定`">
            <el-radio-button value="ACCEPTED">验收通过</el-radio-button>
            <el-radio-button value="REJECTED">需要调整</el-radio-button>
          </el-radio-group>
          <el-input
            v-if="drafts[capability.id].decision === 'REJECTED'"
            v-model="drafts[capability.id].note"
            type="textarea"
            :rows="2"
            maxlength="2000"
            show-word-limit
            placeholder="必填：说明哪里不符合预期，以及修正后如何复验"
          />
          <el-input
            v-model="drafts[capability.id].evidence"
            type="textarea"
            :rows="2"
            maxlength="4000"
            placeholder="可选：每行一个截图、工单或核对记录引用"
          />
        </div>
      </article>
    </div>

    <div v-if="eligibleCapabilities.length" class="acceptance-actions">
      <p>提交后会记录操作者、时间、来源 apply 回执和证据引用；历史决定不会被覆盖。</p>
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!canSubmit"
        @click="submit"
      >提交本次验收决定</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type {
  CanonicalBlueprintCapability,
  CanonicalCapabilityAcceptanceDecision,
  CanonicalFactoryBlueprint,
} from '@/api/factoryOnboardingApi'

const props = defineProps<{
  blueprint: CanonicalFactoryBlueprint
  loading?: boolean
}>()

const emit = defineEmits<{
  submit: [decisions: CanonicalCapabilityAcceptanceDecision[]]
}>()

type DecisionDraft = { decision: '' | 'ACCEPTED' | 'REJECTED'; note: string; evidence: string }
const drafts = reactive<Record<string, DecisionDraft>>({})

const eligibleCapabilities = computed(() => (props.blueprint.capabilities ?? [])
  .filter((capability) => capability.acceptanceEligible))

function resetDrafts() {
  for (const key of Object.keys(drafts)) delete drafts[key]
  for (const capability of eligibleCapabilities.value) {
    drafts[capability.id] = { decision: '', note: '', evidence: '' }
  }
}

watch(
  () => `${props.blueprint.id}:${props.blueprint.rowVersion}:${props.blueprint.latestReceipt?.id ?? ''}`,
  resetDrafts,
  { immediate: true },
)

const selectedDrafts = computed(() => eligibleCapabilities.value
  .filter((capability) => Boolean(drafts[capability.id]?.decision)))

const canSubmit = computed(() => selectedDrafts.value.length > 0
  && selectedDrafts.value.every((capability) => {
    const draft = drafts[capability.id]
    const refs = evidenceRefs(draft.evidence)
    return (draft.decision !== 'REJECTED' || draft.note.trim().length > 0)
      && refs.length <= 20
      && refs.every((item) => item.length <= 500)
  }))

const overallLabel = computed(() => ({
  NOT_READY: '尚不可验收',
  PENDING: '等待验收',
  PARTIAL: '部分已验收',
  ACCEPTED: '全部已通过',
  REJECTED: '存在需调整项',
})[props.blueprint.acceptanceState])

const overallTone = computed(() => ({
  NOT_READY: 'info',
  PENDING: 'warning',
  PARTIAL: 'warning',
  ACCEPTED: 'success',
  REJECTED: 'danger',
})[props.blueprint.acceptanceState] as 'info' | 'warning' | 'success' | 'danger')

const stateLabel = (state: CanonicalBlueprintCapability['acceptanceState']) => ({
  NOT_APPLICABLE: '本次不适用',
  NOT_READY: '配置未就绪',
  PENDING: '待验收',
  ACCEPTED: '已通过',
  REJECTED: '需调整',
})[state]

const stateTone = (state: CanonicalBlueprintCapability['acceptanceState']) => ({
  NOT_APPLICABLE: 'info',
  NOT_READY: 'info',
  PENDING: 'warning',
  ACCEPTED: 'success',
  REJECTED: 'danger',
})[state] as 'info' | 'warning' | 'success' | 'danger'

function evidenceRefs(value: string): string[] {
  return [...new Set(value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean))]
}

function submit() {
  if (!canSubmit.value) return
  emit('submit', selectedDrafts.value.map((capability) => {
    const draft = drafts[capability.id]
    return {
      capabilityId: capability.id,
      decision: draft.decision as 'ACCEPTED' | 'REJECTED',
      note: draft.note.trim() || undefined,
      evidenceRefs: evidenceRefs(draft.evidence),
    }
  }))
}
</script>

<style scoped>
.acceptance-panel { margin-top: 18px; padding: 20px; border: 1px solid #cbd9e6; border-radius: 14px; background: #f8fbfd; }
.acceptance-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 16px; }
.acceptance-head h3 { margin: 4px 0 0; color: #18263a; font-size: 19px; }
.acceptance-head p { max-width: 760px; margin: 7px 0 0; color: #607086; line-height: 1.55; }
.eyebrow { color: #217468; font-size: 12px; font-weight: 800; letter-spacing: .05em; }
.acceptance-list { display: grid; gap: 10px; }
.acceptance-item { display: grid; grid-template-columns: minmax(220px, .8fr) minmax(320px, 1.2fr); gap: 18px; padding: 15px; border: 1px solid #dce5ed; border-radius: 12px; background: white; }
.capability-title { display: flex; align-items: center; gap: 8px; }
.capability-copy p { margin: 6px 0; color: #5f6f83; font-size: 13px; line-height: 1.5; }
.capability-copy small { color: #7b8797; }
.decision-form { display: grid; gap: 9px; }
.acceptance-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 14px; padding-top: 14px; border-top: 1px solid #dce5ed; }
.acceptance-actions p { margin: 0; color: #66758a; font-size: 12px; }
/* 复核页在平板宽度仍保留左侧导航，实际内容宽度小于 viewport。 */
@media (max-width: 900px) {
  .acceptance-panel { padding: 14px; }
  .acceptance-head, .acceptance-actions { align-items: stretch; flex-direction: column; }
  .acceptance-item { grid-template-columns: 1fr; }
  .decision-form :deep(.el-radio-group) { display: grid; grid-template-columns: 1fr 1fr; }
  .decision-form :deep(.el-radio-button__inner) { width: 100%; }
}
</style>
