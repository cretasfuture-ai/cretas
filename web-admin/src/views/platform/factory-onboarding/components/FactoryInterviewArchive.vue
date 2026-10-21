<template>
  <details class="interview-archive" data-testid="factory-interview-archive">
    <summary>
      <strong>查看完整访谈与全部问题</strong>
      <span>已答 {{ answers.length }} 项 · 待答 {{ pending.length }} 项 · {{ history.length }} 条对话</span>
    </summary>
    <div class="archive-body">
      <p>这里只回看当前工厂已保存的访谈，不会重新开始、修改答案或应用配置。问题会随工序和成品数量展开。</p>
      <section v-if="answers.length" aria-label="全部已答项目">
        <h3>全部已答项目（{{ answers.length }}）</h3>
        <dl>
          <div v-for="answer in answers" :key="answer.field" class="archive-item">
            <dt>{{ answer.label }}</dt>
            <dd>{{ answer.value }}</dd>
          </div>
        </dl>
      </section>
      <section v-if="pending.length" aria-label="全部待答问题">
        <h3>全部待答问题（{{ pending.length }}）</h3>
        <article v-for="item in pending" :key="item.name" class="archive-item">
          <h4>{{ item.label }}</h4>
          <p v-if="item.hint">{{ item.hint }}</p>
          <p v-if="item.options?.length">可选答案：{{ item.options.join(' / ') }}</p>
          <p v-if="item.suggestion">待确认建议：{{ item.suggestion }}</p>
        </article>
      </section>
      <section v-if="progress.skippedItems?.length || progress.unresolvedDecisions?.length" aria-label="需要补充的项目">
        <h3>需要补充的项目</h3>
        <p v-for="item in progress.skippedItems" :key="item.name">{{ item.label }}：{{ item.why || '尚未取得答案' }}</p>
        <p v-for="item in progress.unresolvedDecisions" :key="item.field">{{ item.label }}：暂时不确定，仍需确认</p>
      </section>
      <section aria-label="完整对话记录">
        <h3>完整对话记录（{{ history.length }}）</h3>
        <ol v-if="history.length">
          <li v-for="(message, index) in history" :key="index" class="archive-item">
            <strong>{{ message.role === 'user' ? '我' : (message.role === 'assistant' ? 'Agent' : message.role) }}</strong>
            <p>{{ message.content }}</p>
          </li>
        </ol>
        <p v-else>当前会话没有可回读的历史消息。</p>
      </section>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { FactoryBuildProgress } from '@/api/factoryOnboardingApi'

const props = defineProps<{ progress: FactoryBuildProgress }>()
const answers = computed(() => Object.entries(props.progress.collected ?? {}).map(([field, value]) => ({
  field, value, label: props.progress.answeredLabels?.[field] ?? field,
})))
const pending = computed(() => props.progress.pendingItems ?? [])
const history = computed(() => props.progress.history ?? [])
</script>

<style scoped>
.interview-archive { margin-bottom: 16px; border: 1px solid var(--el-border-color-light); border-radius: 10px; background: var(--el-bg-color); }
summary { padding: 16px; cursor: pointer; color: var(--el-text-color-primary); }
summary span { margin-left: 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.archive-body { max-height: 70vh; overflow: auto; padding: 0 16px 16px; border-top: 1px solid var(--el-border-color-lighter); overflow-wrap: anywhere; }
section { margin-top: 20px; }
h3 { font-size: 15px; }
h4, dl, dd { margin: 0; }
dt, h4 { font-weight: 600; }
.archive-item { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
dd, p { margin: 6px 0 0; white-space: pre-wrap; line-height: 1.6; }
ol { padding-left: 24px; }
@media (max-width: 600px) { summary span { display: block; margin: 8px 0 0; } }
</style>
