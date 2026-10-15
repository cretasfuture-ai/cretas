<template>
  <section class="domain-block" :class="`domain-${group.kind}`" :aria-labelledby="`${group.kind}-card-title`">
    <div class="domain-head">
      <div>
        <span class="kicker">{{ group.kicker }}</span>
        <h3 :id="`${group.kind}-card-title`">{{ group.title }}</h3>
        <p>{{ group.description }}</p>
      </div>
      <el-tag :type="suggestedCount ? 'warning' : 'info'" effect="plain">
        {{ suggestedCount ? `参考预填 ${suggestedCount} 项 · 待确认` : `已答 ${answeredCount}/${group.items.length}` }}
      </el-tag>
    </div>

    <div class="domain-fields">
      <section v-for="item in group.items" :key="item.name" class="domain-field">
        <div class="field-copy">
          <strong>{{ item.label }}</strong>
          <small v-if="item.hint">{{ item.hint }}</small>
          <el-tag v-if="isSuggested(item)" size="small" type="warning" effect="plain">参考值待确认</el-tag>
        </div>

        <div v-if="item.options?.length" class="option-grid" :aria-label="item.label">
          <button
            v-for="option in item.options"
            :key="option"
            type="button"
            class="option-button"
            :class="{ selected: selected(item, option) }"
            :aria-pressed="selected(item, option)"
            @click="toggleOption(item, option)"
          >
            <span class="option-mark" aria-hidden="true">{{ selected(item, option) ? '✓' : '' }}</span>
            {{ option }}
          </button>
        </div>

        <el-input
          v-else
          :model-value="textValue(answers[item.name])"
          type="textarea"
          :rows="3"
          resize="none"
          :aria-label="item.label"
          placeholder="用一两句话说明；没有就点下面的“没有”"
          @update:model-value="update(item.name, $event)"
        />

        <div v-if="item.suggestion || item.allowNone" class="quick-actions">
          <el-button v-if="item.suggestion" size="small" plain @click="update(item.name, item.suggestion)">
            采纳：{{ item.suggestion }}
          </el-button>
          <el-button v-if="item.allowNone" size="small" text @click="update(item.name, '无')">没有</el-button>
        </div>
      </section>
    </div>

    <div class="domain-caution">
      <span aria-hidden="true">!</span>
      <p>{{ group.caution }}</p>
    </div>

    <div class="domain-actions">
      <span>每个项目都需要明确选择或回答；参考值只有提交这张卡后才会成为答案。</span>
      <el-button
        type="primary"
        :loading="sending"
        :disabled="answeredCount !== group.items.length"
        @click="$emit('submit')"
      >
        {{ group.submitLabel }}
      </el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { PendingItem } from '@/api/factoryOnboardingApi'
import { suggestedAnswerMatches, type FactoryDomainGroup } from '../factoryConversationBlocks'

const props = defineProps<{
  group: FactoryDomainGroup
  answers: Record<string, string | string[]>
  suggestions: Record<string, string>
  sending: boolean
}>()

const emit = defineEmits<{
  submit: []
  updateAnswer: [name: string, value: string | string[]]
}>()

const EXCLUSIVE_OPTIONS = new Set(['都要', '都不用', '暂不设'])

const answeredCount = computed(() => props.group.items.filter((item) => {
  const value = props.answers[item.name]
  return Array.isArray(value) ? value.length > 0 : String(value ?? '').trim().length > 0
}).length)
const suggestedCount = computed(() => props.group.items.filter(isSuggested).length)

function isSuggested(item: PendingItem) {
  return suggestedAnswerMatches(item, props.answers[item.name], props.suggestions)
}

function textValue(value: string | string[] | undefined) {
  return Array.isArray(value) ? value.join('、') : (value ?? '')
}

function selected(item: PendingItem, option: string) {
  const value = props.answers[item.name]
  return Array.isArray(value) ? value.includes(option) : value === option
}

function toggleOption(item: PendingItem, option: string) {
  if (!item.multiSelect) {
    update(item.name, option)
    return
  }
  const current = Array.isArray(props.answers[item.name]) ? [...props.answers[item.name] as string[]] : []
  if (EXCLUSIVE_OPTIONS.has(option)) {
    update(item.name, current.length === 1 && current[0] === option ? [] : [option])
    return
  }
  const withoutExclusive = current.filter((value) => !EXCLUSIVE_OPTIONS.has(value))
  const index = withoutExclusive.indexOf(option)
  if (index >= 0) withoutExclusive.splice(index, 1)
  else withoutExclusive.push(option)
  update(item.name, withoutExclusive)
}

function update(name: string, value: unknown) {
  emit('updateAnswer', name, Array.isArray(value) ? value.map(String) : String(value ?? ''))
}
</script>

<style scoped>
.domain-block { flex: 1; min-width: 0; overflow: hidden; border: 1px solid #d8dfe8; border-radius: 12px; background: #fbfcfe; }
.domain-quality { border-color: #dfd7c5; background: #fffdf8; }
.domain-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px; }
.kicker { color: #1b65a8; font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
.domain-quality .kicker { color: #8a641d; }
h3 { margin: 4px 0 0; color: #1a2332; font-size: 16px; line-height: 1.4; }
p { margin: 6px 0 0; color: #66758a; font-size: 12px; line-height: 1.55; }
.domain-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 0 16px 14px; }
.domain-field { min-width: 0; padding: 13px; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; }
.domain-field:focus-within { border-color: #8fb8d9; box-shadow: 0 0 0 3px rgba(27, 101, 168, .08); }
.field-copy { min-height: 62px; }
.field-copy strong, .field-copy small { display: block; }
.field-copy strong { color: #1a2332; font-size: 13px; }
.field-copy small { margin-top: 4px; color: #7a8599; font-size: 11px; line-height: 1.45; }
.field-copy .el-tag { display: inline-flex; margin-top: 6px; }
.option-grid { display: flex; flex-wrap: wrap; gap: 7px; }
.option-button { display: inline-flex; align-items: center; gap: 6px; min-height: 36px; padding: 7px 10px; border: 1px solid #d9e1ea; border-radius: 8px; background: #fff; color: #46566b; font: inherit; font-size: 12px; cursor: pointer; transition: transform 140ms cubic-bezier(0.23, 1, 0.32, 1), opacity 140ms ease-out; }
.option-button:hover { border-color: #7ba8ce; }
.option-button:active { transform: scale(.97); }
.option-button:focus-visible { outline: 2px solid #1b65a8; outline-offset: 2px; }
.option-button.selected { border-color: #1b65a8; background: #edf5fc; color: #15558e; font-weight: 700; }
.option-mark { display: inline-grid; width: 16px; height: 16px; place-items: center; border: 1px solid #bcc9d8; border-radius: 4px; font-size: 10px; }
.selected .option-mark { border-color: #1b65a8; background: #1b65a8; color: #fff; }
.quick-actions { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.quick-actions .el-button { margin-left: 0; }
.domain-caution { display: flex; align-items: flex-start; gap: 9px; margin: 0 16px 14px; padding: 10px 12px; border-radius: 8px; background: #f3f6f9; }
.domain-quality .domain-caution { background: #fbf4e5; }
.domain-caution > span { display: inline-grid; flex: 0 0 auto; width: 18px; height: 18px; place-items: center; border-radius: 50%; background: #6f7f93; color: #fff; font-size: 11px; font-weight: 800; }
.domain-quality .domain-caution > span { background: #9a721f; }
.domain-caution p { margin: 0; }
.domain-actions { position: sticky; bottom: 0; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 16px; border-top: 1px solid #e2e8f0; background: inherit; color: #66758a; font-size: 12px; }
@media (max-width: 760px) {
  .domain-head, .domain-actions { align-items: stretch; flex-direction: column; }
  .domain-fields { grid-template-columns: 1fr; padding: 0 12px 12px; }
  .field-copy { min-height: 0; margin-bottom: 10px; }
  .domain-caution { margin: 0 12px 12px; }
  .domain-actions .el-button { width: 100%; }
}
@media (prefers-reduced-motion: reduce) {
  .option-button { transition: opacity 120ms ease-out; }
  .option-button:active { transform: none; }
}
</style>
