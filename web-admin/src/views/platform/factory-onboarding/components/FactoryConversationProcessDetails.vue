<template>
  <section class="detail-block" aria-labelledby="process-detail-title">
    <div class="detail-head">
      <div>
        <span class="kicker">Agent 逐工序确认</span>
        <h3 id="process-detail-title">{{ group.processName }} · 还有 {{ group.items.length }} 个细节</h3>
        <p>一次只看一道工序。能从样例判断的直接点“采纳”，确实没有就点“没有”，只补 Agent 不知道的部分。</p>
      </div>
      <el-tag :type="suggestedCount ? 'warning' : 'info'" effect="plain">
        {{ suggestedCount ? `参考预填 ${suggestedCount} 项 · 待确认` : `已答 ${answeredCount}/${group.items.length}` }}
      </el-tag>
    </div>

    <div class="detail-grid">
      <div v-for="item in group.items" :key="item.name" class="detail-field">
        <div class="field-copy">
          <strong>{{ compactLabel(item.label) }}</strong>
          <small v-if="item.hint">{{ item.hint }}</small>
          <el-tag v-if="isSuggested(item)" size="small" type="warning" effect="plain">参考值待确认</el-tag>
        </div>
        <el-select
          v-if="item.options?.length"
          :model-value="answers[item.name]"
          :multiple="item.multiSelect"
          clearable
          collapse-tags
          placeholder="请选择"
          @update:model-value="update(item.name, $event)"
        >
          <el-option v-for="option in item.options" :key="option" :label="option" :value="option" />
        </el-select>
        <el-input
          v-else
          :model-value="textValue(answers[item.name])"
          :aria-label="item.label"
          placeholder="只补充这个样例没说清的部分"
          @update:model-value="update(item.name, $event)"
        />
        <div v-if="item.suggestion || item.allowNone" class="quick-actions">
          <el-button v-if="item.suggestion" size="small" plain @click="update(item.name, item.suggestion)">
            采纳：{{ item.suggestion }}
          </el-button>
          <el-button v-if="item.allowNone" size="small" text @click="update(item.name, '无')">没有</el-button>
        </div>
      </div>
    </div>

    <div class="detail-actions">
      <span>未回答的项目会留到下一轮，不会被猜填。</span>
      <el-button type="primary" :loading="sending" :disabled="answeredCount === 0" @click="$emit('submit')">
        把这道工序的细节交给 Agent
      </el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { suggestedAnswerMatches, type ProcessDetailGroup } from '../factoryConversationBlocks'

const props = defineProps<{
  group: ProcessDetailGroup
  answers: Record<string, string | string[]>
  suggestions: Record<string, string>
  sending: boolean
}>()

const emit = defineEmits<{
  submit: []
  updateAnswer: [name: string, value: string | string[]]
}>()

const answeredCount = computed(() => props.group.items.filter((item) => {
  const value = props.answers[item.name]
  return Array.isArray(value) ? value.length > 0 : String(value ?? '').trim().length > 0
}).length)
const suggestedCount = computed(() => props.group.items.filter(isSuggested).length)

function isSuggested(item: ProcessDetailGroup['items'][number]) {
  return suggestedAnswerMatches(item, props.answers[item.name], props.suggestions)
}

function compactLabel(label: string) {
  return label.replace(/^第\s*\d+\s*道[·・:：\s]*/, '')
}

function textValue(value: string | string[] | undefined) {
  return Array.isArray(value) ? value.join('、') : (value ?? '')
}

function update(name: string, value: unknown) {
  emit('updateAnswer', name, Array.isArray(value) ? value.map(String) : String(value ?? ''))
}
</script>

<style scoped>
.detail-block { flex: 1; min-width: 0; overflow: hidden; border: 1px solid #c8d9cf; border-radius: 12px; background: #f8fcfa; }
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px; }
.kicker { color: #21776a; font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
h3 { margin: 4px 0 0; color: #1a2332; font-size: 16px; line-height: 1.4; }
p { margin: 6px 0 0; color: #66758a; font-size: 12px; line-height: 1.55; }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 0 16px 16px; }
.detail-field { display: flex; min-width: 0; flex-direction: column; gap: 8px; padding: 12px; border: 1px solid #dfeae4; border-radius: 10px; background: #fff; }
.field-copy { min-height: 48px; }
.field-copy strong, .field-copy small { display: block; }
.field-copy strong { color: #1a2332; font-size: 13px; }
.field-copy small { margin-top: 4px; color: #7a8599; font-size: 11px; line-height: 1.45; }
.field-copy .el-tag { display: inline-flex; margin-top: 6px; }
.quick-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.quick-actions .el-button { margin-left: 0; max-width: 100%; overflow: hidden; text-overflow: ellipsis; }
.detail-actions { position: sticky; bottom: 0; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 16px; border-top: 1px solid #dfeae4; background: #f8fcfa; color: #66758a; font-size: 12px; }
@media (max-width: 700px) {
  .detail-head, .detail-actions { align-items: stretch; flex-direction: column; }
  .detail-grid { grid-template-columns: 1fr; padding: 0 12px 12px; }
  .detail-actions .el-button { width: 100%; }
}
</style>
