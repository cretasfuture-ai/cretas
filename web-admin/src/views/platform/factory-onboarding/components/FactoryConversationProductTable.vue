<template>
  <section class="product-block" aria-labelledby="product-table-title">
    <div class="product-head">
      <div>
        <span class="kicker">Agent 成品组合确认</span>
        <h3 id="product-table-title">这些成品分别走哪条生产链？</h3>
        <p>先写顺序，再明确有没有并行、分支、合流、返工或不合格处置。复杂路线只记录为待编译事实，不会自动发布 Workflow。</p>
      </div>
      <el-tag :type="confirmed ? 'success' : (suggestedFieldCount ? 'warning' : 'info')" effect="plain">
        {{ confirmed
          ? '已进入 Agent 上下文'
          : (suggestedFieldCount ? `参考预填 ${suggestedFieldCount} 项 · 待确认` : `${completeRows}/${rows.length} 个成品完整`) }}
      </el-tag>
    </div>

    <label class="packaging-field">
      <span>
        <strong>销售规格与包装换算</strong>
        <small v-if="packagingHint">{{ packagingHint }}</small>
        <el-tag v-if="packagingSuggested" size="small" type="warning" effect="plain">参考值待确认</el-tag>
      </span>
      <el-input
        :model-value="packaging"
        type="textarea"
        :rows="2"
        resize="none"
        :disabled="confirmed"
        placeholder="例如：香卤牛肉每袋 200g，一箱装 20 袋"
        aria-label="销售规格与包装换算"
        @update:model-value="$emit('updatePackaging', String($event ?? ''))"
      />
    </label>

    <div class="product-table" role="table" aria-label="AI 理解的成品与工序链">
      <div class="product-row product-header" role="row">
        <span role="columnheader">成品</span>
        <span role="columnheader">经过的工序</span>
        <span role="columnheader">路线形态</span>
        <span role="columnheader">状态</span>
      </div>
      <div v-for="row in rows" :key="row.sequence" class="product-row" role="row">
        <label role="cell">
          <small>成品 {{ String(row.sequence).padStart(2, '0') }}</small>
          <el-input
            :model-value="row.name"
            :disabled="confirmed"
            placeholder="成品名称和规格"
            :aria-label="`第 ${row.sequence} 个成品名称`"
            @update:model-value="update(row.sequence, 'name', $event)"
          />
        </label>
        <label role="cell">
          <small>按先后顺序填写</small>
          <el-input
            :model-value="row.steps"
            :disabled="confirmed"
            placeholder="解冻修整 → 腌制 → 卤煮 → 包装"
            :aria-label="`第 ${row.sequence} 个成品经过的工序`"
            @update:model-value="update(row.sequence, 'steps', $event)"
          />
        </label>
        <label role="cell">
          <small>必须明确确认，可多选</small>
          <el-select
            :model-value="row.routeShape"
            multiple
            collapse-tags
            collapse-tags-tooltip
            :disabled="confirmed"
            placeholder="仅顺序 / 分支 / 返工…"
            :aria-label="`第 ${row.sequence} 个成品路线形态`"
            @update:model-value="update(row.sequence, 'routeShape', $event)"
          >
            <el-option
              v-for="option in routeShapeOptions(row.sequence)"
              :key="option"
              :label="option"
              :value="option"
            />
          </el-select>
        </label>
        <span class="row-status" role="cell">
          <i :class="rowSuggestedCount(row) ? 'suggested' : (rowComplete(row) ? 'ready' : 'missing')" />
          {{ rowSuggestedCount(row) ? `参考 ${rowSuggestedCount(row)} 项` : (rowComplete(row) ? '可确认' : '还需补充') }}
        </span>
        <label class="route-details" role="cell">
          <span>
            <strong>路线条件和例外</strong>
            <small>{{ routeDetailsHint(row.sequence) }}</small>
          </span>
          <el-input
            :model-value="row.routeDetails"
            type="textarea"
            :rows="2"
            resize="none"
            :disabled="confirmed"
            placeholder="例如：金检不合格回到拆包复检；两条包装线在装箱前合流。只有顺序请明确填“没有”"
            :aria-label="`第 ${row.sequence} 个成品路线条件和例外`"
            @update:model-value="update(row.sequence, 'routeDetails', $event)"
          />
          <el-button
            v-if="!confirmed && row.routeShape.length === 1 && row.routeShape[0] === '仅顺序' && !row.routeDetails.trim()"
            size="small"
            plain
            @click="update(row.sequence, 'routeDetails', '没有')"
          >
            确认没有路线例外
          </el-button>
        </label>
      </div>
    </div>

    <div class="product-actions">
      <span>表格只更新访谈上下文；生成、写入和发布仍在后面的独立步骤完成。</span>
      <el-button
        type="primary"
        :loading="sending"
        :disabled="confirmed || completeRows !== rows.length || !packaging.trim()"
        @click="$emit('submit')"
      >
        {{ confirmed ? '这张表已交给 Agent' : '把成品与工序链交给 Agent' }}
      </el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { PendingItem } from '@/api/factoryOnboardingApi'
import { normalizeProductRouteShape, type ProductFlowRow } from '../factoryConversationBlocks'

const props = defineProps<{
  rows: ProductFlowRow[]
  packaging: string
  packagingHint?: string
  suggestions: Record<string, string>
  collected: Record<string, string>
  items: PendingItem[]
  sending: boolean
  confirmed: boolean
}>()

const emit = defineEmits<{
  submit: []
  updateRow: [sequence: number, field: 'name' | 'steps' | 'routeShape' | 'routeDetails', value: string | string[]]
  updatePackaging: [value: string]
}>()

const completeRows = computed(() => props.rows.filter((row) =>
  rowComplete(row)).length)
const packagingSuggested = computed(() => !props.confirmed && isSuggested('productSkus', props.packaging))
const suggestedFieldCount = computed(() => props.confirmed ? 0 : props.rows.reduce(
  (count, row) => count + rowSuggestedCount(row), packagingSuggested.value ? 1 : 0))

function isSuggested(name: string, value: string) {
  if (Object.prototype.hasOwnProperty.call(props.collected, name)) return false
  const suggestion = props.suggestions[name]?.trim()
  return Boolean(suggestion) && value.trim() === suggestion
}

function rowSuggestedCount(row: ProductFlowRow) {
  if (props.confirmed) return 0
  const textFields = (['name', 'steps', 'routeDetails'] as const)
    .filter(field => isSuggested(`product${row.sequence}.${field}`, row[field])).length
  return textFields + (routeShapeSuggested(row) ? 1 : 0)
}

function routeShapeSuggested(row: ProductFlowRow) {
  const name = `product${row.sequence}.routeShape`
  if (Object.prototype.hasOwnProperty.call(props.collected, name)) return false
  const suggestion = props.suggestions[name]
    ?.split(/[、,，;；]/).map(value => value.trim()).filter(Boolean).sort() ?? []
  const selected = row.routeShape.map(value => value.trim()).filter(Boolean).sort()
  return suggestion.length > 0 && JSON.stringify(selected) === JSON.stringify(suggestion)
}

function rowComplete(row: ProductFlowRow) {
  return row.name.trim().length > 0
    && row.steps.trim().length > 0
    && row.routeShape.length > 0
    && row.routeDetails.trim().length > 0
}

function routeItem(sequence: number, field: 'routeShape' | 'routeDetails') {
  return props.items.find(item => item.name === `product${sequence}.${field}`)
}

function routeShapeOptions(sequence: number) {
  return routeItem(sequence, 'routeShape')?.options ?? []
}

function routeDetailsHint(sequence: number) {
  return routeItem(sequence, 'routeDetails')?.hint
    ?? '说清分支条件、合流位置、返工回点或不合格处置。'
}

function update(
  sequence: number,
  field: 'name' | 'steps' | 'routeShape' | 'routeDetails',
  value: unknown,
) {
  const normalizedValue = field === 'routeShape' && Array.isArray(value)
    ? normalizeProductRouteShape(value.map(String))
    : value
  emit('updateRow', sequence, field,
    Array.isArray(normalizedValue) ? normalizedValue.map(String) : String(normalizedValue ?? ''))
}
</script>

<style scoped>
.product-block { container-type: inline-size; flex: 1; min-width: 0; overflow: hidden; border: 1px solid #c9d8e7; border-radius: 12px; background: #f8fbfe; }
.product-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px; }
.kicker { color: #1b65a8; font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
h3 { margin: 4px 0 0; color: #1a2332; font-size: 16px; line-height: 1.4; }
p { margin: 6px 0 0; color: #66758a; font-size: 12px; line-height: 1.55; }
.packaging-field { display: grid; grid-template-columns: minmax(180px, .75fr) minmax(260px, 1.25fr); gap: 14px; align-items: center; margin: 0 16px 14px; padding: 12px; border: 1px solid #dbe7f1; border-radius: 10px; background: #fff; }
.packaging-field:focus-within { border-color: #8fb8d9; box-shadow: 0 0 0 3px rgba(27, 101, 168, .08); }
.packaging-field span, .packaging-field strong, .packaging-field small { display: block; }
.packaging-field strong { color: #1a2332; font-size: 13px; }
.packaging-field small { margin-top: 4px; color: #7a8599; font-size: 11px; line-height: 1.4; }
.product-table { margin: 0 16px 16px; overflow: hidden; border: 1px solid #dbe7f1; border-radius: 10px; background: #fff; }
.product-row { display: grid; grid-template-columns: minmax(160px, .7fr) minmax(250px, 1.15fr) minmax(190px, .9fr) 92px; gap: 12px; align-items: center; padding: 10px 12px; border-top: 1px solid #edf2f7; }
.product-row:not(.product-header):focus-within { position: relative; z-index: 1; box-shadow: inset 3px 0 #1b65a8; }
.product-row:first-child { border-top: 0; }
.product-header { background: #eef5fb; color: #5e6d81; font-size: 11px; font-weight: 700; }
.product-row label { min-width: 0; }
.product-row label small { display: block; margin-bottom: 5px; color: #7a8599; font-size: 10px; }
.row-status { display: inline-flex; align-items: center; gap: 6px; color: #66758a; font-size: 11px; }
.row-status i { width: 7px; height: 7px; border-radius: 50%; background: #d19a2c; }
.row-status i.ready { background: #2f8f67; }
.row-status i.suggested { background: #c78020; }
.route-details { display: grid; grid-column: 1 / -1; grid-template-columns: minmax(210px, .65fr) minmax(360px, 1.35fr) auto; gap: 12px; align-items: center; padding-top: 10px; border-top: 1px dashed #dbe7f1; }
.route-details span, .route-details strong, .route-details small { display: block; }
.route-details strong { color: #1a2332; font-size: 12px; }
.route-details small { margin-top: 4px; color: #7a8599; font-size: 10px; line-height: 1.4; }
.product-actions { position: sticky; bottom: 0; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 16px; border-top: 1px solid #dbe7f1; background: #f8fbfe; color: #66758a; font-size: 12px; }
@container (max-width: 640px) {
  .product-head, .product-actions { align-items: stretch; flex-direction: column; }
  .packaging-field { grid-template-columns: 1fr; margin: 0 12px 12px; }
  .product-table { margin: 0 12px 12px; border: 0; background: transparent; }
  .product-header { display: none; }
  .product-row { grid-template-columns: 1fr; gap: 8px; margin-top: 10px; border: 1px solid #dbe7f1; border-radius: 10px; background: #fff; }
  .route-details { grid-column: auto; grid-template-columns: 1fr; }
  .product-row:first-child { margin-top: 0; border-top: 1px solid #dbe7f1; }
  .product-actions .el-button { width: 100%; }
}
@media (max-width: 760px) {
  .product-head, .product-actions { align-items: stretch; flex-direction: column; }
  .packaging-field { grid-template-columns: 1fr; margin: 0 12px 12px; }
  .product-table { margin: 0 12px 12px; border: 0; background: transparent; }
  .product-header { display: none; }
  .product-row { grid-template-columns: 1fr; gap: 8px; margin-top: 10px; border: 1px solid #dbe7f1; border-radius: 10px; background: #fff; }
  .route-details { grid-column: auto; grid-template-columns: 1fr; }
  .product-row:first-child { margin-top: 0; border-top: 1px solid #dbe7f1; }
  .product-actions .el-button { width: 100%; }
}
</style>
