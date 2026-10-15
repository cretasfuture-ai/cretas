<template>
  <div class="promo" :data-testid="`promo-${capabilityId}-${draft.draftId}`">
    <div class="promo-head">
      <span class="promo-name">
        <strong>{{ draft.label }}</strong>
        <code>{{ draft.naturalKey }}</code>
        <el-tag size="small" effect="plain" disable-transitions>草稿 {{ draft.status }}</el-tag>
      </span>

      <el-tag v-if="promoted" type="success" size="small" disable-transitions data-testid="promo-done">
        已转正 · 正式记录 {{ promoted.liveTargetId }}
      </el-tag>
      <el-button
        v-else
        size="small"
        type="primary"
        plain
        :loading="previewing"
        data-testid="promo-start"
        @click="runPreview"
      >{{ actionLabel }}</el-button>
    </div>

    <!-- 预览卡片: 写入前把效果和字段摆出来 -->
    <section v-if="!promoted && preview" class="promo-card" data-testid="promo-preview">
      <p class="promo-effect" data-testid="promo-effect">{{ effectText }}</p>
      <section v-if="preview.action === 'UPDATE'" data-testid="promo-before">
        <strong>修改前的正式记录</strong>
        <dl class="promo-fields">
          <template v-for="row in beforeRows" :key="row.key"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></template>
        </dl>
        <strong>修改后</strong>
      </section>
      <dl class="promo-fields">
        <template v-for="row in fieldRows" :key="row.key">
          <dt>{{ row.label }}</dt>
          <dd>{{ row.value }}</dd>
        </template>
      </dl>
      <el-form-item v-if="capabilityId === 'materials' && preview.after?.['classification.packagingCategory'] === true" label="含税采购参考价">
        <el-input-number v-model="taxIncludedUnitPrice" :min="preview.action === 'UPDATE' ? 0.0001 : 0.01" :precision="preview.action === 'UPDATE' ? 4 : 2" :step="0.01" placeholder="留空则保留现有价格" data-testid="promo-material-price" @change="materialPriceChanged" />
      </el-form-item>

      <label v-for="choice in packagingChoices" :key="choice.workflowPortId">
        {{ choice.label }} · 包装角色
        <select v-model="packagingRoles[choice.workflowPortId]"
          :data-testid="`promo-bom-role-${choice.workflowPortId}`" :disabled="previewing || executing"
          @change="packagingRoleChanged">
          <option value="">请选择包装角色</option>
          <option value="PRIMARY_CONTAINER">成品容器</option>
          <option value="SEAL">封装</option>
          <option value="OUTER_CASE">外包装</option>
        </select>
      </label>

      <ul v-if="blockers.length" class="promo-blockers" data-testid="promo-blockers">
        <li v-for="(b, i) in blockers" :key="`${b.code}:${i}`">
          <strong>{{ b.message }}</strong>
          <span v-if="b.nextStep">下一步：{{ b.nextStep }}</span>
        </li>
      </ul>

      <el-alert
        v-if="stale"
        type="warning" :closable="false" show-icon
        data-testid="promo-stale"
        :title="staleMessage"
        :description="staleNextStep ? `下一步：${staleNextStep}` : undefined"
      />

      <div class="promo-actions">
        <el-button
          type="warning"
          :disabled="!canExecute"
          :loading="executing"
          :data-key-ready="idempotencyKey ? 'true' : 'false'"
          data-testid="promo-execute"
          @click="confirmExecute"
        >{{ actionLabel }}</el-button>
        <el-button v-if="stale" data-testid="promo-repreview" :loading="previewing" @click="runPreview">重新预览</el-button>
        <small v-if="blockers.length" class="promo-hint">有 {{ blockers.length }} 个阻断项，处理后重新预览才能转正</small>
        <small v-else-if="keyError" class="promo-hint">{{ keyError }}</small>
      </div>
    </section>

    <el-alert v-if="error" type="error" :closable="false" show-icon data-testid="promo-error"
              :title="error" :description="errorNextStep ? `下一步：${errorNextStep}` : undefined" />

    <div v-if="receipt" class="promo-receipt" data-testid="promo-receipt">
      已转正为正式{{ noun }}，正式记录 <code>{{ receipt.liveTargetId }}</code>
      <el-tag v-if="receipt.replayed" type="info" size="small" disable-transitions>重复提交，沿用已转正的那一条</el-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 一份已写入的主数据草稿的「转正」: 预览(效果句 + 将写入的字段 + 阻断项) → 确认框(身份 + 不可撤销)
 * → 执行(带预览摘要 + 固定幂等键) → 回执。已转正的只显示「已转正 · 正式记录 X」。
 */
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import type { WrittenDraft } from '@/api/factoryBlueprintDraftApi'
import {
  executePromotion,
  previewPromotion,
  type PromotionPreview,
  type PromotionReceipt,
  type PromotionRecord,
} from '@/api/factoryBlueprintPromotionApi'
import { draftErrorCode, draftErrorMessage } from '../factoryBlueprintDrafts'
import {
  PROMOTION_REFRESH_CODES,
  PROMOTION_STALE_CODE,
  canExecutePromotion,
  promotionActionLabel,
  promotionConfirmText,
  promotionEffectText,
  promotionErrorNextStep,
  promotionExecuteBody,
  promotionFieldRows,
  promotionIdempotencyKey,
  promotionNoun,
} from '../factoryBlueprintPromotion'

const props = defineProps<{
  factoryId: string
  factoryName: string
  blueprintId: string
  blueprintVersion: number
  sourceDigest: string
  capabilityId: string
  draft: WrittenDraft
  /** 这份草稿的已转正记录(没有为 null) */
  promoted: PromotionRecord | null
}>()

const emit = defineEmits<{ (e: 'changed'): void }>()

const noun = computed(() => promotionNoun(props.capabilityId))
const actionLabel = computed(() => promotionActionLabel(props.capabilityId, preview.value?.action))
const effectText = computed(() => promotionEffectText(props.capabilityId, preview.value?.action))

const preview = ref<PromotionPreview | null>(null)
const previewing = ref(false)
const executing = ref(false)
const stale = ref(false)
const staleMessage = ref('')
const staleNextStep = ref('')
const error = ref('')
const errorNextStep = ref('')
const receipt = ref<PromotionReceipt | null>(null)
const taxIncludedUnitPrice = ref<number | undefined>(undefined)
const packagingRoles = ref<Record<string, string>>({})
const packagingChoices = computed(() => {
  const choices = props.capabilityId === 'bom' ? preview.value?.after?.packagingRoles : null
  if (!Array.isArray(choices)) return []
  return choices.flatMap((value: unknown) => {
    if (value === null || typeof value !== 'object') return []
    const row = value as Record<string, unknown>
    return typeof row.workflowPortId === 'string' && typeof row.label === 'string'
      ? [{ workflowPortId: row.workflowPortId, label: row.label }] : []
  })
})

function materialPriceChanged() {
  stale.value = true
  staleMessage.value = '采购参考价已改变，请重新预览含税价和未税价后确认转正'
  staleNextStep.value = '重新预览并核对价格'
}

function packagingRoleChanged() {
  stale.value = true
  staleMessage.value = '包装角色已改变，请重新预览后确认转正'
}

const fieldRows = computed(() => promotionFieldRows(props.capabilityId, preview.value?.after))
const beforeRows = computed(() => promotionFieldRows(props.capabilityId,
  preview.value?.before && typeof preview.value.before === 'object' && !Array.isArray(preview.value.before)
    ? preview.value.before as Record<string, unknown> : null))
const blockers = computed(() => preview.value?.blockers ?? [])

/** 固定幂等键(SHA-256, 异步); 输入一变就重算, 过期结果不落地 */
const idempotencyKey = ref('')
const keyError = ref('')
let keyRun = 0
watch(
  () => [props.factoryId, props.blueprintId, props.blueprintVersion, props.sourceDigest, props.capabilityId, props.draft.draftId] as const,
  async ([factoryId, blueprintId, blueprintVersion, sourceDigest, capabilityId, draftId]) => {
    const run = ++keyRun
    idempotencyKey.value = ''
    keyError.value = ''
    try {
      const key = await promotionIdempotencyKey({ factoryId, blueprintId, blueprintVersion, sourceDigest, capabilityId, draftId })
      if (run === keyRun) idempotencyKey.value = key
    } catch (e) {
      if (run === keyRun) keyError.value = e instanceof Error ? e.message : String(e)
    }
  },
  { immediate: true }
)

const canExecute = computed(() => canExecutePromotion(preview.value, idempotencyKey.value, stale.value) && !executing.value && !previewing.value)

function clearMessages() {
  error.value = ''
  errorNextStep.value = ''
  stale.value = false
  staleMessage.value = ''
  staleNextStep.value = ''
}

async function runPreview() {
  clearMessages()
  previewing.value = true
  try {
    const input = {
      ...(taxIncludedUnitPrice.value === undefined ? {} : { taxIncludedUnitPrice: taxIncludedUnitPrice.value }),
      ...(props.capabilityId === 'bom' ? { packagingRoles: { ...packagingRoles.value } } : {}),
    }
    const res = Object.keys(input).length === 0
      ? await previewPromotion(props.factoryId, props.blueprintId, props.capabilityId, props.draft.draftId)
      : await previewPromotion(props.factoryId, props.blueprintId, props.capabilityId, props.draft.draftId,
        input)
    preview.value = res.data
  } catch (e) {
    preview.value = null
    error.value = draftErrorMessage(e)
    errorNextStep.value = promotionErrorNextStep(e)
  } finally {
    previewing.value = false
  }
}

async function confirmExecute() {
  if (!canExecute.value || !preview.value?.promotionPreviewDigest) return
  try {
    await ElMessageBox.confirm(
      promotionConfirmText(props.capabilityId, props.factoryName, props.factoryId, props.draft.label, preview.value?.action),
      actionLabel.value,
      { type: 'warning', confirmButtonText: actionLabel.value, cancelButtonText: '先不转正' }
    )
  } catch {
    return
  }
  executing.value = true
  clearMessages()
  try {
    const body = {
      ...promotionExecuteBody(preview.value.promotionPreviewDigest, idempotencyKey.value, taxIncludedUnitPrice.value),
      ...(props.capabilityId === 'bom' ? { packagingRoles: { ...packagingRoles.value } } : {}),
    }
    const res = await executePromotion(props.factoryId, props.blueprintId, props.capabilityId, props.draft.draftId, body)
    receipt.value = res.data
    emit('changed')
  } catch (e) {
    const code = draftErrorCode(e)
    if (code === PROMOTION_STALE_CODE) {
      // 数据变了 / 预览过期: 留在卡片上, 给「重新预览」
      stale.value = true
      staleMessage.value = draftErrorMessage(e)
      staleNextStep.value = promotionErrorNextStep(e)
    } else {
      error.value = draftErrorMessage(e)
      errorNextStep.value = promotionErrorNextStep(e)
      if (code && PROMOTION_REFRESH_CODES.includes(code)) emit('changed')
    }
  } finally {
    executing.value = false
  }
}
</script>

<style scoped>
.promo { padding: 8px 0; border-top: 1px dashed var(--el-border-color-lighter); }
.promo-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.promo-name { display: inline-flex; align-items: center; gap: 6px; }
.promo-name code { color: var(--el-text-color-secondary); font-size: 12px; }
.promo-card {
  margin-top: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
}
.promo-effect { margin: 0 0 8px; font-weight: 600; color: var(--el-color-primary); }
.promo-fields { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 12px; margin: 0; font-size: 13px; }
.promo-fields dt { color: var(--el-text-color-secondary); }
.promo-fields dd { margin: 0; word-break: break-all; }
.promo-blockers { margin: 8px 0; padding-left: 18px; color: var(--el-color-danger); }
.promo-blockers span { display: block; color: var(--el-text-color-regular); }
.promo-actions { display: flex; align-items: center; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.promo-hint { color: var(--el-text-color-secondary); }
.promo-receipt { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.promo :deep(.el-alert) { margin-top: 8px; }
</style>
