<template>
  <section class="process-block" aria-labelledby="process-block-title">
    <div class="block-head">
      <div>
        <span class="kicker">Agent 可操作表格</span>
        <h3 id="process-block-title">这是我从样例里理解出的生产流程</h3>
        <p>直接在表格里改，不用重新组织一大段文字。空白处代表还没确认，Agent 后面会继续追问。</p>
      </div>
      <el-tag :type="confirmed ? 'success' : (suggestedFieldCount ? 'warning' : 'info')" effect="plain">
        {{ confirmed ? '已返回 Agent' : (suggestedFieldCount ? `参考预填 ${suggestedFieldCount} 项 · 待确认` : '待你确认') }}
      </el-tag>
    </div>

    <div class="process-table" role="table" aria-label="AI 理解的生产流程">
      <div class="process-row process-header" role="row">
        <span role="columnheader">顺序</span>
        <span role="columnheader">工序</span>
        <span role="columnheader">主要投入</span>
        <span role="columnheader">主要产出</span>
        <span role="columnheader">理解状态</span>
      </div>
      <div v-for="row in rows" :key="row.sequence" class="process-row" role="row">
        <span class="sequence" role="cell"><b>{{ String(row.sequence).padStart(2, '0') }}</b></span>
        <label role="cell">
          <small>工序</small>
          <el-input :model-value="row.name" :aria-label="`第 ${row.sequence} 道工序名称`" placeholder="待确认" @update:model-value="update(row.sequence, 'name', $event)" />
        </label>
        <label role="cell">
          <small>主要投入</small>
          <el-input :model-value="row.inputMaterial" :aria-label="`第 ${row.sequence} 道主要投入`" placeholder="可稍后补充" @update:model-value="update(row.sequence, 'inputMaterial', $event)" />
        </label>
        <label role="cell">
          <small>主要产出</small>
          <el-input :model-value="row.output" :aria-label="`第 ${row.sequence} 道主要产出`" placeholder="可稍后补充" @update:model-value="update(row.sequence, 'output', $event)" />
        </label>
        <span class="row-status" role="cell">
          <i :class="rowSuggestedCount(row) ? 'suggested' : (row.name.trim() ? 'understood' : 'missing')" />
          {{ rowSuggestedCount(row) ? `参考 ${rowSuggestedCount(row)} 项` : (row.name.trim() ? '已理解' : '待确认') }}
        </span>
      </div>
    </div>

    <div class="block-actions">
      <span><b>结构化返回</b> · 提交后 Agent 会按这张表继续，不再猜你的文字含义。</span>
      <el-button type="primary" :loading="sending" :disabled="confirmed || !hasContent" @click="$emit('submit')">
        {{ confirmed ? '这张表已交给 Agent' : '确认这张表并继续' }}
      </el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ProcessSkeletonRow } from '../factoryConversationBlocks'

const props = defineProps<{
  rows: ProcessSkeletonRow[]
  suggestions: Record<string, string>
  collected: Record<string, string>
  sending: boolean
  confirmed: boolean
}>()

const emit = defineEmits<{
  submit: []
  updateRow: [sequence: number, field: 'name' | 'inputMaterial' | 'output', value: string]
}>()

const hasContent = computed(() => props.rows.some((row) =>
  row.name.trim() || row.inputMaterial.trim() || row.output.trim()))
const suggestedFieldCount = computed(() => props.confirmed ? 0 : props.rows.reduce(
  (count, row) => count + rowSuggestedCount(row), 0))

function isSuggested(sequence: number, field: 'name' | 'inputMaterial' | 'output', value: string) {
  const name = `step${sequence}.${field}`
  if (Object.prototype.hasOwnProperty.call(props.collected, name)) return false
  const suggestion = props.suggestions[name]?.trim()
  return Boolean(suggestion) && value.trim() === suggestion
}

function rowSuggestedCount(row: ProcessSkeletonRow) {
  if (props.confirmed) return 0
  return (['name', 'inputMaterial', 'output'] as const)
    .filter(field => isSuggested(row.sequence, field, row[field])).length
}

function update(sequence: number, field: 'name' | 'inputMaterial' | 'output', value: string) {
  emit('updateRow', sequence, field, value)
}
</script>

<style scoped>
.process-block { flex: 1; min-width: 0; overflow: hidden; border: 1px solid #b8d6e8; border-radius: 12px; background: #f7fbfe; }
.block-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px; }
.kicker { color: #21776a; font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
h3 { margin: 4px 0 0; color: #1a2332; font-size: 16px; line-height: 1.4; }
p { margin: 6px 0 0; color: #66758a; font-size: 12px; line-height: 1.55; }
.process-table { border-top: 1px solid #dce8f0; border-bottom: 1px solid #dce8f0; background: #fff; }
.process-row { display: grid; grid-template-columns: 52px minmax(120px, .9fr) minmax(150px, 1.1fr) minmax(150px, 1.1fr) 92px; align-items: center; gap: 10px; min-height: 54px; padding: 8px 14px; border-top: 1px solid #edf2f7; }
.process-row:first-child { border-top: 0; }
.process-header { min-height: 34px; background: #eef5f9; color: #66758a; font-size: 11px; font-weight: 700; }
.process-row label { min-width: 0; }
.process-row label small { display: none; }
.sequence b { display: inline-grid; place-items: center; width: 30px; height: 30px; border-radius: 8px; background: #e6f0f7; color: #1b65a8; font-size: 11px; }
.row-status { display: flex; align-items: center; gap: 6px; color: #66758a; font-size: 11px; white-space: nowrap; }
.row-status i { width: 7px; height: 7px; border-radius: 50%; }
.row-status i.understood { background: #2e9c83; }
.row-status i.missing { background: #d29a37; }
.row-status i.suggested { background: #c78020; }
.block-actions { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 16px; color: #66758a; font-size: 12px; }
.block-actions b { color: #21776a; }

@media (max-width: 700px) {
  .block-head, .block-actions { align-items: stretch; flex-direction: column; }
  .process-header { display: none; }
  .process-table { padding: 10px; background: #f7fbfe; }
  .process-row:not(.process-header) { grid-template-columns: 38px minmax(0, 1fr); gap: 9px; margin-bottom: 10px; padding: 12px; border: 1px solid #dce8f0; border-radius: 10px; background: #fff; }
  .process-row:not(.process-header):last-child { margin-bottom: 0; }
  .sequence { grid-row: 1 / 5; align-self: start; }
  .process-row label { display: grid; grid-template-columns: minmax(0, 1fr); align-items: stretch; gap: 4px; }
  .process-row label small { display: block; color: #66758a; font-size: 11px; font-weight: 700; }
  .row-status { grid-column: 2; }
  .block-actions .el-button { width: 100%; }
}
</style>
