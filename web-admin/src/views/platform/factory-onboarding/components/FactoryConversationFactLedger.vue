<template>
  <section v-if="facts.length" class="fact-ledger" aria-labelledby="fact-ledger-title">
    <header>
      <div>
        <span>Factory evidence ledger</span>
        <h3 id="fact-ledger-title">已确认事实与来源</h3>
        <p>这里只列已进入会话真值的答案；参考建议未确认前不会出现。</p>
      </div>
      <el-tag :type="legacyCount ? 'warning' : 'success'" effect="plain">
        {{ confirmedCount }} 项有逐字段证据<span v-if="legacyCount"> · {{ legacyCount }} 项旧会话待核</span>
      </el-tag>
    </header>

    <div class="fact-list">
      <article v-for="fact in facts" :key="fact.field" class="fact-row">
        <div class="fact-main">
          <strong>{{ fact.label }}</strong>
          <span>{{ fact.value }}</span>
        </div>
        <div class="fact-evidence">
          <el-tag size="small" :type="sourceTone(fact)" effect="plain">{{ sourceLabel(fact) }}</el-tag>
          <el-tag v-if="fact.referenceMatched" size="small" type="warning" effect="plain">
            与参考一致 · 已由用户确认
          </el-tag>
          <small>{{ evidenceLabel(fact) }}</small>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { FactoryFactEvidenceItem } from '@/api/factoryOnboardingApi'

const props = defineProps<{ facts: FactoryFactEvidenceItem[] }>()

const confirmedCount = computed(() => props.facts.filter(fact => fact.evidenceStatus === 'CONFIRMED').length)
const legacyCount = computed(() => props.facts.length - confirmedCount.value)

function sourceLabel(fact: FactoryFactEvidenceItem) {
  if (fact.sourceKind === 'USER_STRUCTURED') return '用户结构化确认'
  if (fact.sourceKind === 'USER_NARRATIVE') return '用户对话确认'
  return '旧会话 · 来源待核'
}

function sourceTone(fact: FactoryFactEvidenceItem): 'success' | 'info' | 'warning' {
  if (fact.sourceKind === 'USER_STRUCTURED') return 'success'
  if (fact.sourceKind === 'USER_NARRATIVE') return 'info'
  return 'warning'
}

function evidenceLabel(fact: FactoryFactEvidenceItem) {
  const message = fact.evidenceRef.match(/:message:(\d+)$/)?.[1]
  const time = fact.capturedAt ? new Date(fact.capturedAt).toLocaleString('zh-CN', { hour12: false }) : ''
  if (message) return `会话证据 #${message}${time ? ` · ${time}` : ''}`
  return '旧会话没有逐字段回执，需要负责人重新确认'
}
</script>

<style scoped>
.fact-ledger { container-type: inline-size; margin-bottom: 16px; overflow: hidden; border: 1px solid #d7e4dd; border-radius: 10px; background: #f8fcfa; }
header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 14px 16px; border-bottom: 1px solid #e3ece7; }
header span { color: #21776a; font-size: 10px; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
h3 { margin: 3px 0 0; color: #1a2332; font-size: 15px; }
p { margin: 4px 0 0; color: #66758a; font-size: 11px; line-height: 1.5; }
.fact-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.fact-row { min-width: 0; padding: 12px 14px; border-top: 1px solid #edf2ef; }
.fact-row:nth-child(-n+2) { border-top: 0; }
.fact-row:nth-child(odd) { border-right: 1px solid #edf2ef; }
.fact-main strong, .fact-main span { display: block; }
.fact-main strong { color: #52647a; font-size: 11px; }
.fact-main span { margin-top: 4px; overflow-wrap: anywhere; color: #1a2332; font-size: 13px; line-height: 1.5; }
.fact-evidence { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.fact-evidence small { color: #7a8599; font-size: 10px; }
@container (max-width: 520px) {
  header { align-items: stretch; flex-direction: column; }
  header :deep(.el-tag) { align-self: flex-start; }
  .fact-list { grid-template-columns: 1fr; }
  .fact-row, .fact-row:nth-child(-n+2) { border-top: 1px solid #edf2ef; }
  .fact-row:first-child { border-top: 0; }
  .fact-row:nth-child(odd) { border-right: 0; }
}
@media (max-width: 768px) {
  header { align-items: stretch; flex-direction: column; }
  header :deep(.el-tag) { align-self: flex-start; }
  .fact-list { grid-template-columns: 1fr; }
  .fact-row, .fact-row:nth-child(-n+2) { border-top: 1px solid #edf2ef; }
  .fact-row:first-child { border-top: 0; }
  .fact-row:nth-child(odd) { border-right: 0; }
}
</style>
