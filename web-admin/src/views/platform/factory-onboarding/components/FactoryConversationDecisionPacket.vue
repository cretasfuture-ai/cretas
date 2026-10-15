<template>
  <section
    class="decision-packet"
    :class="{ unresolved: packet.state === 'UNRESOLVED' }"
    :aria-labelledby="`${packet.decisionId}-title`"
  >
    <header class="decision-head">
      <div>
        <span class="decision-kicker">{{ packet.state === 'UNRESOLVED' ? '回到一个待补决定' : '当前只决定这一件事' }}</span>
        <h3 :id="`${packet.decisionId}-title`">{{ packet.label }}</h3>
      </div>
      <el-tag :type="packet.state === 'UNRESOLVED' ? 'warning' : 'info'" effect="plain">
        {{ packet.state === 'UNRESOLVED' ? '尚未确定' : '等待你的确认' }}
      </el-tag>
    </header>

    <p class="decision-question">{{ packet.question }}</p>

    <div class="why-block">
      <strong>为什么要问</strong>
      <p>{{ packet.whyItMatters }}</p>
    </div>

    <div v-if="packet.guidanceChoices.length" class="guidance-choices" aria-label="可选方式和例子">
      <button
        v-for="choice in packet.guidanceChoices"
        :key="`${choice.label}:${choice.value ?? choice.example ?? ''}`"
        type="button"
        class="guidance-choice"
        :class="{ selected: !!choice.value && selectedValues.includes(choice.value) }"
        :aria-pressed="choice.value ? selectedValues.includes(choice.value) : undefined"
        :disabled="sending"
        @click="choose(choice.value, choice.example)"
      >
        <span>{{ choice.label }}</span>
        <small v-if="choice.consequence">{{ choice.consequence }}</small>
      </button>
    </div>

    <div v-if="directOptions.length" class="direct-options" aria-label="服务端可确认选项">
      <strong>{{ packet.multiSelect ? '可以选择一项或多项' : '直接确认一个选项' }}</strong>
      <div class="direct-option-list">
        <button
          v-for="option in directOptions"
          :key="option"
          type="button"
          class="direct-option"
          :class="{ selected: selectedValues.includes(option) }"
          :aria-pressed="selectedValues.includes(option)"
          :disabled="sending"
          @click="choose(option)"
        >
          {{ option }}
        </button>
      </div>
    </div>

    <div v-if="packet.recommendation" class="recommendation">
      <div>
        <span>参考建议</span>
        <strong>{{ packet.recommendation }}</strong>
        <small>{{ packet.recommendationReason }}</small>
      </div>
      <el-button size="small" plain :disabled="sending" @click="$emit('useRecommendation', packet.recommendation)">
        先用这个回答
      </el-button>
    </div>

    <div v-if="packet.multiSelect && selectedValues.length" class="multi-confirm" role="status">
      <span>已选 {{ selectedValues.length }} 项：{{ selectedValues.join('、') }}</span>
      <el-button
        type="primary"
        size="small"
        :loading="sending"
        @click="$emit('confirmMulti', selectedValues)"
      >
        确认本题
      </el-button>
    </div>

    <div class="impact-row">
      <strong>确认后会影响</strong>
      <span v-for="impact in packet.downstreamImpacts" :key="impact">{{ impact }}</span>
    </div>

    <div v-if="packet.unresolvedFollowUp" class="unresolved-copy" role="status">
      {{ packet.unresolvedFollowUp }}
    </div>

    <footer class="decision-footer">
      <span>例子只是可编辑提示，不会静默成为答案。</span>
      <el-button
        v-if="packet.allowUnknown"
        text
        :loading="sending"
        @click="$emit('defer')"
      >
        {{ packet.state === 'UNRESOLVED' ? '仍不确定，先保留待补' : '暂时不确定，先问下一项' }}
      </el-button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { FactoryDecisionPacket } from '@/api/factoryOnboardingApi'

const props = defineProps<{
  packet: FactoryDecisionPacket
  selectedValues: string[]
  sending: boolean
}>()

const emit = defineEmits<{
  select: [value: string]
  useExample: [example: string]
  useRecommendation: [recommendation: string]
  confirmMulti: [values: string[]]
  defer: []
}>()

const directOptions = computed(() => {
  const represented = new Set(props.packet.guidanceChoices
    .map(choice => choice.value)
    .filter((value): value is string => typeof value === 'string'))
  return props.packet.options.filter(option => !represented.has(option))
})

function choose(value?: string | null, example?: string | null) {
  if (value) emit('select', value)
  else if (example) emit('useExample', example)
}
</script>

<style scoped>
.decision-packet { width: 100%; overflow: hidden; border: 1px solid #cddce9; border-radius: 12px; background: #f8fbfe; color: #1a2332; }
.decision-packet.unresolved { border-color: #e0c584; background: #fffaf0; }
.decision-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 16px 16px 10px; }
.decision-kicker { color: #1b65a8; font-size: 10px; font-weight: 800; letter-spacing: .08em; }
.unresolved .decision-kicker { color: #8a641d; }
h3 { margin: 4px 0 0; font-size: 18px; line-height: 1.35; }
.decision-question { margin: 0; padding: 0 16px 14px; color: #3f5066; font-size: 14px; line-height: 1.65; }
.why-block { margin: 0 16px 14px; padding: 11px 12px; border-left: 3px solid #6c9fc7; background: #fff; }
.why-block strong { font-size: 11px; color: #23577f; }
.why-block p { margin: 4px 0 0; color: #53647a; font-size: 12px; line-height: 1.55; }
.guidance-choices { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 0 16px 14px; }
.guidance-choice { min-width: 0; min-height: 58px; padding: 10px 11px; border: 1px solid #d7e1ea; border-radius: 9px; background: #fff; color: #31445c; text-align: left; font: inherit; cursor: pointer; }
.guidance-choice:hover { border-color: #75a5cc; }
.guidance-choice:focus-visible { outline: 3px solid #1b65a8; outline-offset: 2px; }
.guidance-choice.selected { border-color: #1b65a8; background: #eaf3fb; color: #15558e; }
.guidance-choice span, .guidance-choice small { display: block; }
.guidance-choice span { font-size: 13px; font-weight: 700; }
.guidance-choice small { margin-top: 3px; color: #708096; font-size: 10px; line-height: 1.4; }
.direct-options { margin: 0 16px 14px; padding: 11px 12px; border: 1px solid #d7e1ea; border-radius: 9px; background: #fff; }
.direct-options > strong { display: block; margin-bottom: 8px; color: #52647a; font-size: 11px; }
.direct-option-list { display: flex; flex-wrap: wrap; gap: 7px; }
.direct-option { min-height: 34px; padding: 6px 11px; border: 1px solid #cbd8e3; border-radius: 8px; background: #f8fbfe; color: #31445c; font: inherit; font-size: 12px; cursor: pointer; }
.direct-option:hover { border-color: #75a5cc; }
.direct-option:focus-visible { outline: 3px solid #1b65a8; outline-offset: 2px; }
.direct-option.selected { border-color: #1b65a8; background: #eaf3fb; color: #15558e; }
.recommendation { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin: 0 16px 14px; padding: 11px 12px; border-radius: 9px; background: #eaf4ed; }
.recommendation div > * { display: block; }
.recommendation span { color: #39714b; font-size: 10px; font-weight: 800; }
.recommendation strong { margin-top: 3px; font-size: 13px; }
.recommendation small { margin-top: 3px; color: #607566; font-size: 10px; line-height: 1.45; }
.multi-confirm { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 16px 14px; padding: 10px 12px; border-radius: 9px; background: #eaf3fb; color: #285b84; font-size: 12px; }
.impact-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 0 16px 14px; }
.impact-row strong { margin-right: 2px; color: #52647a; font-size: 11px; }
.impact-row span { padding: 4px 7px; border-radius: 999px; background: #e8eef4; color: #41546b; font-size: 10px; }
.unresolved-copy { margin: 0 16px 14px; padding: 9px 11px; border-radius: 8px; background: #f8ebcb; color: #705216; font-size: 11px; line-height: 1.5; }
.decision-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 16px; border-top: 1px solid #dce5ed; color: #69798d; font-size: 11px; }
.unresolved .decision-footer { border-top-color: #ead9ad; }
@media (max-width: 560px) {
  .decision-head, .recommendation, .multi-confirm, .decision-footer { align-items: stretch; flex-direction: column; }
  .guidance-choices { grid-template-columns: 1fr; }
  .direct-option { flex: 1 1 calc(50% - 7px); }
  .decision-footer :deep(.el-button) { align-self: flex-start; margin-left: -12px; }
}
@media (prefers-reduced-motion: reduce) {
  .guidance-choice, .direct-option { transition: none; }
}
</style>
