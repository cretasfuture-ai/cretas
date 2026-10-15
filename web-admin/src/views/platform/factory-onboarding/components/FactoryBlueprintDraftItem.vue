<template>
  <section class="draft-item" :data-testid="`draft-item-${item.itemKey}`">
    <header class="draft-item-head">
      <strong>{{ item.label }}</strong>
      <code>{{ item.itemKey }}</code>
    </header>

    <!-- 1. 证据: 只读, 预填是从蓝图的哪些值来的 -->
    <div v-if="evidence.carried.length" class="draft-evidence" data-testid="draft-evidence">
      <span class="draft-caption">预填依据（来自蓝图，只读）</span>
      <ul>
        <li v-for="e in evidence.carried" :key="`${e.path}:${e.value}`"><code>{{ e.path }}</code>：{{ e.value }}</li>
      </ul>
    </div>
    <el-alert
      v-for="e in evidence.notCarried"
      :key="`nc:${e.path}`"
      type="warning"
      :closable="false"
      show-icon
      class="draft-not-carried"
      data-testid="draft-not-carried"
      :title="e.value"
      :description="`蓝图字段 ${e.path}；这份草稿不会带上它`"
    />

    <!-- 2. 待填项: 控件只看后端给的 inputType / options; 边界先显示。顶层与结构里的子字段共用 DraftFieldControl -->
    <el-alert
      v-if="contractErrors.length"
      type="error" :closable="false" show-icon
      data-testid="draft-contract-error"
      title="这份草稿的待填项格式与页面不一致，暂时不能填写"
      :description="`${contractErrors.join('；')}。请联系管理员升级后端或页面。`"
    />
    <div v-if="rows.length" class="draft-inputs">
      <span class="draft-caption" data-testid="draft-fill-summary">{{ fillSentence }}</span>
      <span v-if="fill.optionalEmpty.length" class="draft-caption" data-testid="draft-optional-empty">可选：{{ fill.optionalEmpty.join('、') }}</span>
      <DraftFieldControl
        v-for="r in rows"
        :key="r.input.path"
        :input="r.input"
        :spec="r.spec"
        :answer="answers[r.input.path]"
        :abs-path="r.input.path"
        :error-path="errorPath"
        :error-message="errorMessage"
        @update="(a: DraftAnswer | undefined) => setAnswer(r.input.path, a)"
      />
    </div>
    <p v-else class="draft-caption">蓝图已经给全了这份草稿需要的值，可直接预览。</p>

    <!-- 3. 预览 -->
    <div class="draft-actions">
      <el-button
        data-testid="draft-preview"
        type="primary"
        plain
        :disabled="!canPreview"
        :loading="previewing"
        @click="runPreview"
      >预览</el-button>
      <span v-if="missingCount" class="draft-hint" data-testid="draft-missing">还差 {{ missingCount }} 项没填，填完才能预览：{{ missingNames }}</span>
    </div>
    <el-alert v-if="previewError" type="error" :closable="false" show-icon data-testid="draft-preview-error"
              :title="previewError" />

    <div v-if="preview" class="draft-preview" data-testid="draft-preview-result">
      <div>预览结果：<el-tag :type="previewReady ? 'success' : 'danger'" size="small" disable-transitions>{{ preview.status }}</el-tag></div>
      <ul v-if="issues.length" class="draft-issues" data-testid="draft-issues">
        <li v-for="(issue, i) in issues" :key="`${issue.code}:${i}`">
          <strong>{{ issue.message }}</strong>
          <small v-if="issue.target">（{{ issue.target }}）</small>
          <span v-if="issue.recovery">下一步：{{ issue.recovery }}</span>
        </li>
      </ul>
      <details>
        <summary>完整预览</summary>
        <pre>{{ JSON.stringify(preview, null, 2) }}</pre>
      </details>

      <div class="draft-actions">
        <el-button
          data-testid="draft-confirm"
          :data-key-ready="idempotency.key ? 'true' : 'false'"
          type="warning"
          :disabled="!canMaterialize"
          :loading="materializing"
          @click="confirmMaterialize"
        >确认写入</el-button>
        <span v-if="issues.length" class="draft-hint">有 {{ issues.length }} 个问题，改完重新预览后才能写入</span>
        <span v-else-if="idempotency.error" class="draft-hint">{{ idempotency.error }}</span>
      </div>
    </div>
    <el-alert v-if="materializeError" type="error" :closable="false" show-icon data-testid="draft-materialize-error"
              :title="materializeError" />

    <!-- 4. 回执 + 回读 -->
    <div v-if="receipt" class="draft-receipt" data-testid="draft-receipt">
      <span>已写入草稿 <code>{{ receipt.draftId ?? '（未返回编号）' }}</code></span>
      <el-tag v-if="receipt.replayed" type="info" size="small" disable-transitions>重复提交，沿用已写入的那份</el-tag>
      <el-tag v-if="receipt.status" size="small" disable-transitions>{{ receipt.status }}</el-tag>
    </div>
    <div v-if="readBack" class="draft-readback" data-testid="draft-readback">
      <template v-if="readBack.kind === 'ok'">
        <details>
          <summary>回读结果（服务端实际存下的草稿）</summary>
          <pre>{{ JSON.stringify(readBack.data, null, 2) }}</pre>
        </details>
      </template>
      <span v-else-if="readBack.kind === 'unsupported'" class="draft-hint">
        这类草稿暂不支持回读：{{ readBack.message }}。写入回执已取得，可在「已写入」里核对。
      </span>
      <el-alert v-else type="error" :closable="false" show-icon :title="`回读失败：${readBack.message}`" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  materializeDraft,
  previewDraft,
  readBackDraft,
  type DraftMaterializeReceipt,
  type DraftPreviewResult,
  type DraftProposalItem,
} from '@/api/factoryBlueprintDraftApi'
import {
  buildFilledDraftRequest,
  buildMaterializeBody,
  draftErrorCode,
  draftErrorInputPath,
  draftErrorMessage,
  draftFieldPaths,
  draftFillSummary,
  draftPreviewIdempotencyKey,
  draftInputSpec,
  draftItemContractErrors,
  draftMissingDetails,
  draftStructFromValue,
  readAtDraftPath,
  splitDraftEvidence,
  type DraftAnswer,
  type DraftAnswers,
  type DraftInputSpec,
} from '../factoryBlueprintDrafts'
import DraftFieldControl from './DraftFieldControl.vue'
import { useAuthStore } from '@/store/modules/auth'

const authStore = useAuthStore()

const props = defineProps<{
  factoryId: string
  factoryName: string
  blueprintId: string
  blueprintVersion: number
  sourceDigest: string
  capabilityId: string
  capabilityLabel: string
  item: DraftProposalItem
}>()

const emit = defineEmits<{
  (e: 'missing', itemKey: string, count: number): void
  (e: 'written', capabilityId: string): void
}>()

const evidence = computed(() => splitDraftEvidence(props.item.evidence))
const contractErrors = computed(() => draftItemContractErrors(props.item))

const answers = reactive<DraftAnswers>({})

/** 每个顶层待填项的规格(契约错误时为 null) */
const rows = computed(() => props.item.requiredInputs.map((input) => {
  const r = draftInputSpec(input)
  return { input, spec: (r.ok ? r.spec : null) as DraftInputSpec | null }
}))

/**
 * 结构化待填项一打开就给初始状态: 请求里已有值(提案器部分预填)就用它, 否则 LIST 按 minItems 给空行、
 * OBJECT 给空对象。
 */
for (const input of props.item.requiredInputs) {
  const r = draftInputSpec(input)
  if (!r.ok || r.spec.type !== 'STRUCTURE') continue
  let prefill: unknown = null
  try {
    prefill = readAtDraftPath(props.item.request, input.path)
  } catch {
    prefill = null
  }
  answers[input.path] = { kind: 'value', value: draftStructFromValue(r.spec, prefill) }
}

/** 后端 400 指向的那个字段(可以是结构里某一行的子字段, 如 positions[1].minimumHeadcount) */
const errorPath = ref<string | null>(null)
const errorMessage = ref('')
function markFieldError(e: unknown) {
  errorPath.value = draftErrorInputPath(e, draftFieldPaths(props.item, answers))
  errorMessage.value = errorPath.value ? draftErrorMessage(e) : ''
}

/** 顶层每一处没填好的地方(含结构里每行每个子字段), 逐处命名 */
const missing = computed(() => draftMissingDetails(props.item, answers))
const missingCount = computed(() => missing.value.length)
const missingNames = computed(() => {
  const names = missing.value.map((m) => m.label)
  return names.length > 6 ? `${names.slice(0, 6).join('、')} 等 ${names.length} 处` : names.join('、')
})
watch(missingCount, (n) => emit('missing', props.item.itemKey, n), { immediate: true })

/** 「还需你补充 N 项」只数还挡着预览的项; 可选的空列表不算, 单独列为「可选」(与行状态「可预览」一致) */
const fill = computed(() => draftFillSummary(props.item, answers))
const fillSentence = computed(() => (fill.value.blocking.length
  ? `还需你补充 ${fill.value.blocking.length} 项`
  : '待填项都已填好，可以预览'))

const preview = ref<DraftPreviewResult | null>(null)
/** 预览时发出去的那份请求; 写入用它, 不重新拼 —— 确认的必须就是预览过的 */
const previewedRequest = ref<Record<string, unknown> | null>(null)
const previewing = ref(false)
const previewError = ref('')
const materializing = ref(false)
const materializeError = ref('')
const receipt = ref<DraftMaterializeReceipt | null>(null)
type ReadBackState = { kind: 'ok'; data: unknown } | { kind: 'unsupported'; message: string } | { kind: 'error'; message: string }
const readBack = ref<ReadBackState | null>(null)
let previewRun = 0

/** 任何输入一改, 之前的预览就作废(不能拿旧预览的摘要确认新内容) */
function invalidatePreview() {
  previewRun += 1
  preview.value = null
  previewedRequest.value = null
  previewError.value = ''
  materializeError.value = ''
  previewing.value = false
}

/** 某个顶层待填项(或它里面的任意子字段)改了: 记下回答, 撤掉指向它内部的高亮, 作废旧预览 */
function setAnswer(path: string, answer: DraftAnswer | undefined) {
  answers[path] = answer
  const err = errorPath.value
  if (err && (err === path || err.startsWith(`${path}.`) || err.startsWith(`${path}[`))) {
    errorPath.value = null
    errorMessage.value = ''
  }
  invalidatePreview()
}

const canPreview = computed(() => missingCount.value === 0 && !previewing.value)

async function runPreview() {
  if (!canPreview.value) return
  previewError.value = ''
  let request: Record<string, unknown>
  try {
    request = buildFilledDraftRequest(props.item, answers)
  } catch (e) {
    previewError.value = e instanceof Error ? e.message : String(e)
    return
  }
  const run = ++previewRun
  previewing.value = true
  try {
    const res = await previewDraft(props.factoryId, props.blueprintId, props.capabilityId, request)
    if (run !== previewRun) return
    preview.value = res.data
    previewedRequest.value = request
  } catch (e) {
    if (run !== previewRun) return
    preview.value = null
    previewedRequest.value = null
    previewError.value = draftErrorMessage(e)
    markFieldError(e)
  } finally {
    if (run === previewRun) previewing.value = false
  }
}

const issues = computed(() => (preview.value?.issues ?? []).filter(Boolean))
const previewReady = computed(() => preview.value?.status === 'READY' && issues.value.length === 0 && !!preview.value?.previewDigest)

/** 幂等键要 SHA-256(异步); 输入一变就重算, 过期的结果不落地 */
const idempotency = ref<{ key: string; error: string }>({ key: '', error: '' })
let idempotencyRun = 0
watch(
  () => [props.factoryId, props.blueprintId, props.blueprintVersion, props.sourceDigest, props.capabilityId, props.item.itemKey,
    authStore.user?.userType, authStore.user?.id, previewReady.value ? preview.value?.previewDigest : null] as const,
  async ([factoryId, blueprintId, blueprintVersion, sourceDigest, capabilityId, itemKey, userType, actorId, previewDigest]) => {
    const run = ++idempotencyRun
    idempotency.value = { key: '', error: '' }
    if (typeof previewDigest !== 'string' || previewDigest.trim() === '') return
    let next: { key: string; error: string }
    try {
      if ((userType !== 'factory' && userType !== 'platform') || typeof actorId !== 'number') {
        throw new Error('无法生成幂等键：登录身份不完整，请重新登录')
      }
      const actorType = userType === 'platform' ? 'PLATFORM_ADMIN' : 'FACTORY_USER'
      next = {
        key: await draftPreviewIdempotencyKey(
          { actorType, actorId, factoryId, blueprintId, blueprintVersion, sourceDigest, capabilityId, itemKey },
          previewDigest
        ),
        error: '',
      }
    } catch (e) {
      next = { key: '', error: e instanceof Error ? e.message : String(e) }
    }
    if (run === idempotencyRun) idempotency.value = next
  },
  { immediate: true }
)

const canMaterialize = computed(() =>
  previewReady.value && !!previewedRequest.value && !!idempotency.value.key && !materializing.value)

type TypedActor = { actorType: 'FACTORY_USER' | 'PLATFORM_ADMIN'; actorId: number }
type MaterializePropsSnapshot = {
  factoryId: string
  blueprintId: string
  blueprintVersion: number
  sourceDigest: string
  capabilityId: string
  itemKey: string
}
type MaterializeSnapshot = {
  request: Record<string, unknown>
  previewDigest: string
  confirmation: Record<string, string> | null
  idempotencyKey: string
  actor: TypedActor
  props: MaterializePropsSnapshot
  previewRun: number
  idempotencyRun: number
}

function currentTypedActor(): TypedActor | null {
  const userType = authStore.user?.userType
  const actorId = authStore.user?.id
  if ((userType !== 'factory' && userType !== 'platform')
      || typeof actorId !== 'number' || !Number.isSafeInteger(actorId) || actorId <= 0) return null
  return { actorType: userType === 'platform' ? 'PLATFORM_ADMIN' : 'FACTORY_USER', actorId }
}

function currentMaterializeProps(): MaterializePropsSnapshot {
  return {
    factoryId: props.factoryId,
    blueprintId: props.blueprintId,
    blueprintVersion: props.blueprintVersion,
    sourceDigest: props.sourceDigest,
    capabilityId: props.capabilityId,
    itemKey: props.item.itemKey,
  }
}

function cloneRequest(request: Record<string, unknown>): Record<string, unknown> {
  return JSON.parse(JSON.stringify(request)) as Record<string, unknown>
}

function cloneConfirmation(confirmation: Record<string, string> | null | undefined): Record<string, string> | null {
  return confirmation ? { ...confirmation } : null
}

function materializeSnapshotStillCurrent(snapshot: MaterializeSnapshot): boolean {
  const actor = currentTypedActor()
  const currentPreview = preview.value
  return !!actor
    && actor.actorType === snapshot.actor.actorType
    && actor.actorId === snapshot.actor.actorId
    && JSON.stringify(currentMaterializeProps()) === JSON.stringify(snapshot.props)
    && previewRun === snapshot.previewRun
    && idempotencyRun === snapshot.idempotencyRun
    && previewReady.value
    && currentPreview?.previewDigest === snapshot.previewDigest
    && idempotency.value.key === snapshot.idempotencyKey
    && JSON.stringify(previewedRequest.value) === JSON.stringify(snapshot.request)
    && JSON.stringify(cloneConfirmation(currentPreview?.confirmation)) === JSON.stringify(snapshot.confirmation)
}

async function confirmMaterialize() {
  if (!canMaterialize.value || !preview.value?.previewDigest || !previewedRequest.value) return
  const actor = currentTypedActor()
  if (!actor) {
    materializeError.value = '当前登录身份不可确认写入，请重新登录后再预览'
    return
  }
  materializeError.value = ''
  const snapshot: MaterializeSnapshot = {
    request: cloneRequest(previewedRequest.value),
    previewDigest: preview.value.previewDigest,
    confirmation: cloneConfirmation(preview.value.confirmation),
    idempotencyKey: idempotency.value.key,
    actor,
    props: currentMaterializeProps(),
    previewRun,
    idempotencyRun,
  }
  const who = props.factoryName || props.factoryId
  try {
    await ElMessageBox.confirm(
      `确认写入草稿 — ${who}（${props.factoryId}）· ${props.capabilityLabel}·${props.item.label}`,
      '确认写入草稿',
      { type: 'warning', confirmButtonText: '确认写入', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  if (!materializeSnapshotStillCurrent(snapshot)) {
    materializeError.value = '确认期间预览、输入或登录身份发生变化，请重新预览后再确认'
    return
  }
  materializing.value = true
  try {
    const body = buildMaterializeBody(snapshot.request, snapshot.previewDigest, snapshot.idempotencyKey, snapshot.confirmation)
    const res = await materializeDraft(snapshot.props.factoryId, snapshot.props.blueprintId, snapshot.props.capabilityId, body)
    receipt.value = res.data
    emit('written', props.capabilityId)
    const draftId = res.data?.draftId
    if (draftId) await loadReadBack(draftId)
  } catch (e) {
    materializeError.value = draftErrorMessage(e)
    markFieldError(e)
  } finally {
    materializing.value = false
  }
}

async function loadReadBack(draftId: string) {
  try {
    const res = await readBackDraft(props.factoryId, props.blueprintId, props.capabilityId, draftId)
    readBack.value = { kind: 'ok', data: res.data }
  } catch (e) {
    readBack.value = draftErrorCode(e) === 'BLUEPRINT_DRAFT_READBACK_UNSUPPORTED'
      ? { kind: 'unsupported', message: draftErrorMessage(e) }
      : { kind: 'error', message: draftErrorMessage(e) }
  }
}
</script>

<style scoped>
.draft-item { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 12px; margin-top: 8px; }
.draft-item-head { display: flex; gap: 8px; align-items: baseline; margin-bottom: 8px; }
.draft-caption { display: block; color: var(--el-text-color-secondary); font-size: 12px; margin: 6px 0; }
.draft-evidence ul { margin: 0; padding-left: 18px; font-size: 12px; }
.draft-not-carried { margin: 6px 0; }
.draft-actions { display: flex; align-items: center; gap: 12px; margin: 10px 0; flex-wrap: wrap; }
.draft-hint { color: var(--el-text-color-secondary); font-size: 12px; }
.draft-issues { margin: 6px 0; padding-left: 18px; color: var(--el-color-danger); }
.draft-issues span { display: block; color: var(--el-text-color-regular); }
.draft-preview pre, .draft-readback pre { max-height: 240px; overflow: auto; font-size: 12px; background: var(--el-fill-color-lighter); padding: 8px; }
.draft-receipt { display: flex; gap: 8px; align-items: center; margin-top: 8px; }
</style>
