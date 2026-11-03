<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { askBlacklakeSection, type SectionInsightReport } from '@/api/smartbi/blacklake';
import { useAuthStore } from '@/store/modules/auth';

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

const props = defineProps<{
  report: SectionInsightReport;
}>();

const question = ref('');
const history = ref<ChatMessage[]>([]);
const loading = ref(false);
const error = ref('');
const requestVersion = ref(0);
const authStore = useAuthStore();

const visibleObservations = computed(() => props.report.observations.slice(0, 3));
const additionalObservations = computed(() => props.report.observations.slice(3));
const authIdentity = computed(() => [
  authStore.factoryId,
  authStore.user?.id ?? '',
  authStore.currentRole,
].join(':'));

const sectionIdentity = computed(() => [
  props.report.sectionId,
  props.report.scope.runId,
  props.report.scope.projectId ?? '',
  authIdentity.value,
].join(':'));

const visibleHistory = computed(() => history.value.slice(-6));
const questionLength = computed(() => question.value.length);
const canAsk = computed(() => question.value.trim().length > 0 && questionLength.value <= 500 && !loading.value);

function resetConversation() {
  requestVersion.value += 1;
  loading.value = false;
  error.value = '';
  history.value = [];
  question.value = '';
}

watch(sectionIdentity, resetConversation);

function useSuggestedQuestion(value: string) {
  question.value = value.slice(0, 500);
  error.value = '';
}

async function submitQuestion() {
  const value = question.value.trim();
  if (!value) {
    error.value = '请输入想核对的问题。';
    return;
  }
  if (value.length > 500) {
    error.value = '问题最多 500 个字符。';
    return;
  }
  if (loading.value) return;

  const version = ++requestVersion.value;
  const identity = sectionIdentity.value;
  const previousHistory = history.value.slice(-6);
  loading.value = true;
  error.value = '';

  const payload: {
    section_id: string;
    question: string;
    run_id: string;
    history?: ChatMessage[];
  } = {
    section_id: props.report.sectionId,
    question: value,
    run_id: props.report.scope.runId,
    history: previousHistory,
  };

  try {
    const result = await askBlacklakeSection(payload);
    // A report refresh or a changed section must never let an old answer leak into
    // the new conversation. The request can still finish, but its response is stale.
    if (version !== requestVersion.value || identity !== sectionIdentity.value || result.sectionId !== props.report.sectionId || result.runId !== props.report.scope.runId) return;
    history.value = [...previousHistory, { role: 'user', content: value }, { role: 'assistant', content: result.answer }].slice(-6);
    question.value = '';
  } catch (cause) {
    if (version !== requestVersion.value || identity !== sectionIdentity.value) return;
    error.value = cause instanceof Error ? cause.message : '追问失败，请稍后重试。';
  } finally {
    if (version === requestVersion.value && identity === sectionIdentity.value) loading.value = false;
  }
}
</script>

<template>
  <section class="section-insight" :data-testid="`section-insight-${report.sectionId}`">
    <div class="insight-header">
      <div>
        <p class="insight-kicker">本段分析</p>
        <h3>{{ report.title }}</h3>
        <p class="insight-summary">{{ report.summary }}</p>
      </div>
      <details class="scope-details">
        <summary>当前数据快照</summary>
        <p>分析运行标识：{{ report.scope.runId }}</p>
      </details>
    </div>

    <div class="insight-grid">
      <article v-if="report.observations.length" class="insight-block">
        <h4>观察</h4>
        <ul><li v-for="item in visibleObservations" :key="item">{{ item }}</li></ul>
        <details v-if="additionalObservations.length" class="insight-more">
          <summary>展开其余 {{ additionalObservations.length }} 条观察</summary>
          <ul><li v-for="item in additionalObservations" :key="item">{{ item }}</li></ul>
        </details>
      </article>
      <article v-if="report.hypotheses.length" class="insight-block insight-block--quiet">
        <h4>待验证假设</h4>
        <ul><li v-for="item in report.hypotheses" :key="item">{{ item }}</li></ul>
      </article>
      <article v-if="report.nextSteps.length" class="insight-block insight-block--next">
        <h4>下一步</h4>
        <ul><li v-for="item in report.nextSteps" :key="item">{{ item }}</li></ul>
      </article>
    </div>

    <details v-if="report.calculation.length || report.limitations.length || report.evidenceRefs.length" class="insight-evidence">
      <summary>查看计算口径与证据边界</summary>
      <div class="evidence-content">
        <div v-if="report.calculation.length">
          <h4>计算口径</h4>
          <ul><li v-for="item in report.calculation" :key="item">{{ item }}</li></ul>
        </div>
        <div v-if="report.limitations.length">
          <h4>使用边界</h4>
          <ul><li v-for="item in report.limitations" :key="item">{{ item }}</li></ul>
        </div>
        <div v-if="report.evidenceRefs.length" class="evidence-refs">
          <h4>证据引用</h4>
          <code v-for="reference in report.evidenceRefs" :key="reference">{{ reference }}</code>
        </div>
      </div>
    </details>

    <div class="followup" data-testid="section-followup">
      <div class="followup-heading">
        <div><h4>针对本段追问</h4><p>只基于本次运行和本段证据回答，不补造缺失数据。</p></div>
        <span v-if="loading" class="followup-status" role="status">正在核对…</span>
      </div>
      <div v-if="report.suggestedQuestions.length" class="suggested-questions" aria-label="建议问题">
        <button v-for="suggestion in report.suggestedQuestions" :key="suggestion" type="button" @click="useSuggestedQuestion(suggestion)">{{ suggestion }}</button>
      </div>
      <div v-if="visibleHistory.length" class="conversation" aria-live="polite">
        <div v-for="(message, index) in visibleHistory" :key="`${message.role}-${index}-${message.content}`" :class="['message', `message--${message.role}`]">
          <span class="message-role">{{ message.role === 'user' ? '你' : '分析助手' }}</span>
          <p>{{ message.content }}</p>
        </div>
      </div>
      <form class="question-form" @submit.prevent="submitQuestion">
        <label class="sr-only" :for="`section-question-${report.sectionId}`">向本段提问</label>
        <input
          :id="`section-question-${report.sectionId}`"
          v-model="question"
          type="text"
          maxlength="500"
          placeholder="例如：这段数据最先需要核对什么？"
          :aria-invalid="Boolean(error)"
        >
        <span class="character-count">{{ questionLength }}/500</span>
        <button type="submit" :disabled="!canAsk">发送</button>
      </form>
      <p v-if="error" class="followup-error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<style scoped lang="scss">
.section-insight {
  --insight-ink: var(--text-primary, #1a2332);
  --insight-muted: var(--text-secondary, #7a8599);
  --insight-line: var(--el-border-color-light, #e6e9ef);
  --insight-accent: var(--el-color-primary, #1b65a8);
  margin: 18px 0 22px;
  padding: 18px;
  border: 1px solid var(--insight-line);
  border-radius: 12px;
  background: var(--el-fill-color-blank, #fff);
}
.insight-header, .followup-heading, .question-form { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.insight-kicker { margin: 0 0 4px; color: var(--insight-accent); font-size: 12px; font-weight: 700; letter-spacing: .04em; }
h3, h4 { color: var(--insight-ink); }
h3 { margin: 0 0 8px; font-size: 17px; }
h4 { margin: 0 0 8px; font-size: 14px; }
.insight-summary, .followup-heading p { margin: 0; color: var(--insight-muted); line-height: 1.65; }
.scope-details { flex: none; color: var(--insight-accent); font-size: 12px; }
.scope-details summary { padding: 4px 8px; border-radius: 999px; background: var(--el-color-primary-light-9, #ecf5ff); cursor: pointer; white-space: nowrap; }
.scope-details p { margin: 8px 0 0; color: var(--insight-muted); white-space: nowrap; }
.insight-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }
.insight-block { min-width: 0; padding: 12px; border: 1px solid var(--insight-line); border-radius: 9px; background: var(--el-fill-color-lighter, #fafafa); }
.insight-block--quiet { background: var(--el-color-warning-light-9, #fdf6ec); }
.insight-block--next { background: var(--el-color-success-light-9, #f0f9eb); }
.insight-more { margin-top: 8px; color: var(--insight-accent); font-size: 12px; }
.insight-more summary { cursor: pointer; }
ul { margin: 0; padding-left: 18px; color: var(--insight-muted); line-height: 1.65; }
.insight-evidence { margin-top: 12px; border-top: 1px solid var(--insight-line); color: var(--insight-ink); }
.insight-evidence summary { padding: 12px 0 4px; cursor: pointer; color: var(--insight-accent); font-size: 13px; font-weight: 600; }
.evidence-content { display: grid; gap: 14px; padding-top: 8px; }
.evidence-content h4 { margin-bottom: 5px; }
.evidence-refs { display: flex; flex-wrap: wrap; gap: 6px; }
.evidence-refs code { padding: 3px 6px; border-radius: 5px; background: var(--el-fill-color-light, #f5f7fa); color: var(--insight-muted); font-size: 12px; }
.followup { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--insight-line); }
.followup-heading { align-items: center; }
.followup-heading h4 { margin-bottom: 3px; }
.followup-status { color: var(--insight-accent); font-size: 12px; }
.suggested-questions { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.suggested-questions button { padding: 6px 10px; border: 1px solid var(--insight-line); border-radius: 999px; color: var(--insight-accent); background: var(--el-fill-color-blank, #fff); cursor: pointer; font: inherit; font-size: 12px; }
.suggested-questions button:hover { border-color: var(--insight-accent); background: var(--el-color-primary-light-9, #ecf5ff); }
.conversation { display: grid; gap: 8px; max-height: 260px; margin: 12px 0; overflow: auto; }
.message { max-width: 88%; padding: 8px 11px; border-radius: 9px; }
.message--user { justify-self: end; background: var(--el-color-primary-light-9, #ecf5ff); }
.message--assistant { justify-self: start; background: var(--el-fill-color-light, #f5f7fa); }
.message-role { display: block; margin-bottom: 2px; color: var(--insight-muted); font-size: 11px; }
.message p { margin: 0; color: var(--insight-ink); line-height: 1.6; white-space: pre-wrap; }
.question-form { position: relative; align-items: center; }
.question-form input { min-width: 0; flex: 1; padding: 9px 58px 9px 11px; border: 1px solid var(--insight-line); border-radius: 8px; color: var(--insight-ink); background: var(--el-fill-color-blank, #fff); font: inherit; }
.question-form input:focus { outline: 2px solid color-mix(in srgb, var(--insight-accent) 30%, transparent); outline-offset: 1px; }
.question-form button { flex: none; padding: 9px 16px; border: 0; border-radius: 8px; color: #fff; background: var(--insight-accent); cursor: pointer; font: inherit; font-weight: 600; }
.question-form button:disabled { cursor: not-allowed; opacity: .45; }
.character-count { position: absolute; right: 88px; color: var(--insight-muted); font-size: 11px; pointer-events: none; }
.followup-error { margin: 8px 0 0; color: var(--el-color-danger, #f56c6c); font-size: 12px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 760px) {
  .insight-header { flex-direction: column; }
  .insight-grid { grid-template-columns: 1fr; }
  .question-form { align-items: stretch; flex-wrap: wrap; }
  .question-form input { flex-basis: 100%; }
  .character-count { right: 12px; top: 11px; }
}
</style>
