<template>
  <section class="drafts-panel" aria-labelledby="drafts-panel-title" data-testid="blueprint-drafts-panel">
    <header class="drafts-head">
      <strong id="drafts-panel-title">建厂草稿</strong>
      <span>按写入顺序完成草稿和转正；配置应用与 Workflow 发布分别确认。</span>
    </header>

    <el-alert
      v-if="!canConfirm"
      type="info" :closable="false" show-icon
      data-testid="drafts-factory-only"
      title="当前账号没有建厂确认权限"
      description="请使用本厂工厂超级管理员或具有权限的平台管理员账号。服务端会重新核对当前权限。"
    />
    <el-alert
      v-if="hasWrittenDrafts"
      type="warning" :closable="false" show-icon
      data-testid="drafts-lineage-warning"
      title="修改并重新保存蓝图后，已写入的草稿将不再属于当前版本"
    />
    <el-alert v-if="writeOrderError" type="error" :closable="false" show-icon data-testid="drafts-write-order-error"
              :title="`读不到写入顺序：${writeOrderError}`" description="刷新页面重试；仍失败请联系管理员。" />
    <el-alert v-if="writtenError" type="warning" :closable="false" show-icon data-testid="drafts-written-error"
              :title="`读不到已写入的草稿：${writtenError}`" description="下面不显示「已写入 / 未写入」，以免误判。" />
    <el-alert v-if="promotionsError" type="warning" :closable="false" show-icon data-testid="drafts-promotions-error"
              :title="`读不到转正记录：${promotionsError}`"
              description="下面无法标出哪些草稿已经转正；点「转正」时服务端仍会拦下重复转正。" />

    <p v-if="loadingWriteOrder" class="drafts-muted">正在读取写入顺序…</p>

    <ol v-if="continuationRows.length" class="drafts-rows">
      <li v-for="(row, index) in continuationRows" :key="row.capabilityId" class="drafts-row"
          :data-testid="`draft-row-${row.capabilityId}`">
        <div class="drafts-row-main">
          <span class="drafts-index">{{ index + 1 }}</span>
          <div class="drafts-row-name">
            <strong>{{ labelOf(row.capabilityId) }}</strong>
            <code>{{ row.capabilityId }}</code>
          </div>
          <el-tag :type="tagType(stateOf(row).tone)" size="small" disable-transitions data-testid="draft-row-state">
            {{ stateOf(row).label }}
          </el-tag>
          <el-tag v-if="writtenLabelOf(row)" :type="tagType(writtenLabelOf(row)!.tone)" size="small"
                  effect="plain" disable-transitions data-testid="draft-row-written">
            {{ writtenLabelOf(row)!.label }}
          </el-tag>
          <el-button
            v-if="stateOf(row).actionable"
            size="small"
            text
            type="primary"
            :data-testid="`draft-row-toggle-${row.capabilityId}`"
            @click="toggle(row.capabilityId)"
          >{{ expanded[row.capabilityId] ? '收起' : '展开填写' }}</el-button>
        </div>
        <p v-if="stateOf(row).detail" class="drafts-row-detail" data-testid="draft-row-detail">{{ stateOf(row).detail }}</p>

        <!-- 转正: 只给本厂工厂超管, 只对产品 / 物料 / 工序已写入的草稿 -->
        <div v-if="promotableDraftsOf(row).length" class="drafts-promotions" :data-testid="`draft-promotions-${row.capabilityId}`">
          <span class="drafts-promotions-title">已写入的草稿 · 可转正为正式{{ promotionNoun(row.capabilityId) }}</span>
          <DraftPromotionCard
            v-for="d in promotableDraftsOf(row)"
            :key="d.draftId"
            :factory-id="factoryId"
            :factory-name="factoryName"
            :blueprint-id="blueprint.id"
            :blueprint-version="writtenLineage?.blueprintVersion ?? blueprint.version"
            :source-digest="writtenLineage?.sourceDigest ?? ''"
            :capability-id="row.capabilityId"
            :draft="d"
            :promoted="promotedOf(row.capabilityId, d.draftId)"
            @changed="loadPromotions"
          />
        </div>

        <!-- v-show 而不是 v-if: 收起时不卸载, 已填的待填项不丢 -->
        <div v-if="stateOf(row).actionable && proposals[row.capabilityId]" v-show="expanded[row.capabilityId]"
             class="drafts-items" :data-testid="`draft-items-${row.capabilityId}`">
          <FactoryBlueprintDraftItem
            v-for="item in proposals[row.capabilityId]!.items"
            :key="item.itemKey"
            :factory-id="factoryId"
            :factory-name="factoryName"
            :blueprint-id="blueprint.id"
            :blueprint-version="proposals[row.capabilityId]!.blueprintVersion ?? blueprint.version"
            :source-digest="proposals[row.capabilityId]!.sourceDigest ?? ''"
            :capability-id="row.capabilityId"
            :capability-label="labelOf(row.capabilityId)"
            :item="item"
            @missing="(itemKey: string, n: number) => setMissing(row.capabilityId, itemKey, n)"
            @written="loadWritten"
          />
        </div>
      </li>
    </ol>
    <FactoryBlueprintActivationPanel :factory-id="factoryId" :blueprint-id="blueprint.id"
      :can-confirm="canConfirm" :written="written" @changed="loadPromotions"
      @formal-state="(capability, result) => emit('formalState', capability, result)" />
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import {
  getDraftProposal,
  getDraftWriteOrder,
  getWrittenDrafts,
  type DraftProposal,
  type DraftWriteOrderRow,
  type WrittenCapability,
  type WrittenDraft,
} from '@/api/factoryBlueprintDraftApi'
import {
  canConfirmBlueprintDrafts,
  deriveDraftRowState,
  deriveWrittenLabel,
  draftErrorMessage,
  type DraftTone,
} from '../factoryBlueprintDrafts'
import FactoryBlueprintDraftItem from './FactoryBlueprintDraftItem.vue'
import DraftPromotionCard from './DraftPromotionCard.vue'
import FactoryBlueprintActivationPanel from './FactoryBlueprintActivationPanel.vue'
import type { FormalCapability, FormalExecution } from '@/api/factoryBlueprintActivationApi'
import { listPromotions, type PromotionRecord } from '@/api/factoryBlueprintPromotionApi'
import { indexPromotedRecords, isPromotable, promotionNoun, promotionRecordKey } from '../factoryBlueprintPromotion'

const props = defineProps<{
  factoryId: string
  factoryName: string
  blueprint: {
    id: string
    version: number
    lifecycleState?: string
    capabilities?: Array<{ id: string; label: string }> | null
  }
}>()
const emit = defineEmits<{ formalState: [FormalCapability, FormalExecution | null] }>()

const authStore = useAuthStore()
const canConfirm = computed(() => canConfirmBlueprintDrafts({
  isFactory: !!authStore.isFactory,
  isPlatform: !!authStore.isPlatform,
  platformRole: authStore.user?.userType === 'platform'
    ? String(authStore.user.platformUser?.role ?? '') : '',
  currentRole: String(authStore.currentRole ?? ''),
  factoryId: String(authStore.factoryId ?? ''),
}, props.factoryId))

const rows = ref<DraftWriteOrderRow[]>([])
const continuationRows = computed(() => ['PUBLISHED', 'APPLIED'].includes(props.blueprint.lifecycleState ?? '')
  ? rows.value.filter(row => ['workflow', 'bom'].includes(row.capabilityId))
  : rows.value)
const loadingWriteOrder = ref(false)
const writeOrderError = ref('')
const written = ref<WrittenCapability[] | null>(null)
const writtenError = ref('')
/** 已写入列表所属的血缘(版本 + 血缘摘要), 转正幂等键用它 */
const writtenLineage = ref<{ blueprintVersion: number; sourceDigest: string } | null>(null)
const promoted = ref<Map<string, PromotionRecord>>(new Map())
const promotionsError = ref('')
const proposals = reactive<Record<string, DraftProposal | null | undefined>>({})
const proposalErrors = reactive<Record<string, string | undefined>>({})
const missing = reactive<Record<string, Record<string, number>>>({})
const expanded = reactive<Record<string, boolean>>({})

function labelOf(capabilityId: string): string {
  return props.blueprint.capabilities?.find((c) => c.id === capabilityId)?.label || capabilityId
}

function stateOf(row: DraftWriteOrderRow) {
  return deriveDraftRowState(row, {
    canConfirm: canConfirm.value,
    proposal: proposals[row.capabilityId],
    proposalError: proposalErrors[row.capabilityId],
    missingByItem: missing[row.capabilityId],
    labelOf,
  })
}

function writtenLabelOf(row: DraftWriteOrderRow) {
  if (!written.value) return null
  return deriveWrittenLabel(written.value.find((w) => w.capabilityId === row.capabilityId))
}

const hasWrittenDrafts = computed(() =>
  (written.value ?? []).some((w) => w.supported && (w.drafts ?? []).length > 0))

function tagType(tone: DraftTone): 'info' | 'warning' | 'danger' | 'success' {
  return tone
}

function toggle(capabilityId: string) {
  expanded[capabilityId] = !expanded[capabilityId]
}

function setMissing(capabilityId: string, itemKey: string, n: number) {
  missing[capabilityId] = { ...(missing[capabilityId] ?? {}), [itemKey]: n }
}

async function loadWriteOrder() {
  loadingWriteOrder.value = true
  writeOrderError.value = ''
  try {
    const res = await getDraftWriteOrder(props.factoryId, props.blueprint.id)
    rows.value = res.data?.capabilities ?? []
  } catch (e) {
    rows.value = []
    writeOrderError.value = draftErrorMessage(e)
  } finally {
    loadingWriteOrder.value = false
  }
}

async function loadWritten() {
  // Current authenticated namespace is checked again by the server.
  if (!canConfirm.value) return
  writtenError.value = ''
  try {
    const res = await getWrittenDrafts(props.factoryId, props.blueprint.id)
    written.value = res.data?.capabilities ?? []
    writtenLineage.value = res.data ? { blueprintVersion: res.data.blueprintVersion, sourceDigest: res.data.sourceDigest } : null
  } catch (e) {
    written.value = null
    writtenLineage.value = null
    writtenError.value = draftErrorMessage(e)
  }
}

/** 当前血缘下的转正记录, 用来标「已转正」。 */
async function loadPromotions() {
  if (!canConfirm.value) return
  promotionsError.value = ''
  try {
    const res = await listPromotions(props.factoryId, props.blueprint.id)
    promoted.value = indexPromotedRecords(res.data?.promotions)
  } catch (e) {
    promoted.value = new Map()
    promotionsError.value = draftErrorMessage(e)
  }
}

function promotedOf(capabilityId: string, draftId: string): PromotionRecord | null {
  return promoted.value.get(promotionRecordKey(capabilityId, draftId)) ?? null
}

/** 能显示转正入口的已写入草稿: 本厂工厂超管 + 可转正的能力 + 后端列得出的草稿 */
function promotableDraftsOf(row: DraftWriteOrderRow): WrittenDraft[] {
  if (!canConfirm.value || !isPromotable(row.capabilityId) || !written.value) return []
  const w = written.value.find((x) => x.capabilityId === row.capabilityId)
  return w && w.supported ? (w.drafts ?? []) : []
}

async function loadProposal(capabilityId: string) {
  proposalErrors[capabilityId] = undefined
  try {
    const res = await getDraftProposal(props.factoryId, props.blueprint.id, capabilityId)
    proposals[capabilityId] = res.data
  } catch (e) {
    proposals[capabilityId] = null
    proposalErrors[capabilityId] = draftErrorMessage(e)
  }
}

/** 只给真能走到预填那一步的行取提案: 入口开放、依赖入口都开放、有预填, 且当前账号能确认 */
function needsProposal(row: DraftWriteOrderRow): boolean {
  return row.adapterMode === 'MODULE_DRAFT'
    && row.entryOpen
    && row.proposalOpen
    && (!['PUBLISHED', 'APPLIED'].includes(props.blueprint.lifecycleState ?? '')
      || ['workflow', 'bom'].includes(row.capabilityId))
    && (row.dependenciesWithoutOpenEntry ?? []).length === 0
}

async function loadAll() {
  for (const key of Object.keys(proposals)) delete proposals[key]
  for (const key of Object.keys(proposalErrors)) delete proposalErrors[key]
  for (const key of Object.keys(missing)) delete missing[key]
  written.value = null
  promoted.value = new Map()
  await loadWriteOrder()
  if (!canConfirm.value) return
  await Promise.all([
    loadWritten(),
    loadPromotions(),
    ...rows.value.filter(needsProposal).map((row) => loadProposal(row.capabilityId)),
  ])
}

watch(() => [props.factoryId, props.blueprint.id, props.blueprint.lifecycleState, canConfirm.value] as const, () => { void loadAll() }, { immediate: true })
</script>

<style scoped>
.drafts-panel { border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 14px 16px; margin: 16px 0; }
.drafts-head { display: flex; flex-direction: column; gap: 2px; margin-bottom: 10px; }
.drafts-head span, .drafts-muted { color: var(--el-text-color-secondary); font-size: 12px; }
.drafts-panel :deep(.el-alert) { margin-bottom: 10px; }
.drafts-rows { list-style: none; margin: 0; padding: 0; }
.drafts-row { border-top: 1px solid var(--el-border-color-lighter); padding: 8px 0; }
.drafts-row-main { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.drafts-index { width: 22px; height: 22px; border-radius: 50%; background: var(--el-fill-color); display: inline-flex; align-items: center; justify-content: center; font-size: 12px; }
.drafts-row-name { display: flex; gap: 6px; align-items: baseline; min-width: 180px; }
.drafts-row-name code { color: var(--el-text-color-secondary); font-size: 12px; }
.drafts-row-detail { margin: 4px 0 0 32px; color: var(--el-text-color-secondary); font-size: 12px; }
.drafts-items { margin-left: 32px; }
.drafts-promotions { margin: 6px 0 0 32px; }
.drafts-promotions-title { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
