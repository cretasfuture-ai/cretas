<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import UButton from '@nuxt/ui/components/Button.vue';
import UBadge from '@nuxt/ui/components/Badge.vue';
import { askBlacklakeSection, type BlacklakePeriodQuery, type CostDataAlert, type SectionInsightReport } from '@/api/smartbi/blacklake';
import { useAuthStore } from '@/store/modules/auth';
import { localizeBlacklakeText } from '@/views/smart-bi/blacklakePresentation';

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

const props = withDefaults(defineProps<{
  report: SectionInsightReport;
  period?: BlacklakePeriodQuery | null;
  focusAlert?: CostDataAlert | null;
  openRequest?: number;
}>(), {
  focusAlert: null,
  openRequest: undefined,
});

const emit = defineEmits<{
  (event: 'clear-focus'): void;
  (event: 'locate-order', projectId: number): void;
}>();

const question = ref('');
const history = ref<ChatMessage[]>([]);
const loading = ref(false);
const error = ref('');
const requestVersion = ref(0);
const questionInput = ref<HTMLInputElement | null>(null);
const authStore = useAuthStore();

const focusAlertId = computed(() => props.focusAlert?.id ?? null);
const chatOpen = ref(Boolean(focusAlertId.value || (props.openRequest ?? 0) > 0));
const authIdentity = computed(() => [
  authStore.factoryId,
  authStore.user?.id ?? '',
  authStore.currentRole,
].join(':'));

const sectionIdentity = computed(() => [
  authIdentity.value,
  props.report.scope.runId,
  props.report.sectionId,
  JSON.stringify(props.period ?? null),
  props.focusAlert?.id ?? '',
  JSON.stringify(props.report),
  JSON.stringify(props.focusAlert),
].join(':'));

const impact = computed(() => props.report.observations[0] ?? '当前段落暂无可展示的主要观察。');
const remainingObservations = computed(() => props.report.observations.slice(1));
const visibleHistory = computed(() => history.value.slice(-6));
const questionLength = computed(() => question.value.length);
const canAsk = computed(() => question.value.trim().length > 0 && questionLength.value <= 500 && !loading.value);
const chatId = computed(() => `section-followup-${props.report.sectionId}`);
const focusTitle = computed(() => props.focusAlert?.title ?? '');
const evidenceSources = computed(() => props.report.evidenceSources ?? []);
const relatedOrders = computed(() => props.report.relatedOrders ?? []);
const scopeDescription = computed(() => props.report.scopeDescription?.trim() || '分析范围尚未说明，请先核对日期和工单。');

function resetConversation() {
  requestVersion.value += 1;
  loading.value = false;
  error.value = '';
  history.value = [];
  question.value = '';
}

const previousFocusAlertId = ref(focusAlertId.value);
watch(sectionIdentity, () => {
  const focusChanged = previousFocusAlertId.value !== focusAlertId.value;
  previousFocusAlertId.value = focusAlertId.value;
  resetConversation();
  chatOpen.value = Boolean(focusChanged && focusAlertId.value);
  if (chatOpen.value) nextTick(() => questionInput.value?.focus());
});

watch(() => props.openRequest, (value, previous) => {
  if (value === undefined || value === previous || value <= 0) return;
  chatOpen.value = true;
  nextTick(() => questionInput.value?.focus());
});

function toggleChat() {
  chatOpen.value = !chatOpen.value;
  if (chatOpen.value) nextTick(() => questionInput.value?.focus());
}

function useSuggestedQuestion(value: string) {
  question.value = value.slice(0, 500);
  error.value = '';
  if (!chatOpen.value) toggleChat();
}

function clearFocus() {
  emit('clear-focus');
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
  const payload = {
    section_id: props.report.sectionId,
    question: value,
    run_id: props.report.scope.runId,
    ...(props.period ? { period: props.period } : {}),
    history: previousHistory,
    ...(focusAlertId.value ? { alert_id: focusAlertId.value } : {}),
  };
  loading.value = true;
  error.value = '';

  try {
    const result = await askBlacklakeSection(payload);
    const resultAlertId = 'alertId' in result ? result.alertId : null;
    if (
      version !== requestVersion.value
      || identity !== sectionIdentity.value
      || result.sectionId !== props.report.sectionId
      || result.runId !== props.report.scope.runId
      || (resultAlertId ?? null) !== (focusAlertId.value ?? null)
    ) return;
    history.value = [...previousHistory, { role: 'user' as const, content: value }, { role: 'assistant' as const, content: result.answer }].slice(-6);
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
      <div class="insight-heading">
        <div class="insight-kicker">分析与建议</div>
        <h3>{{ report.title }}</h3>
      </div>
      <details class="scope-details">
        <summary>分析范围</summary><p>{{ localizeBlacklakeText(scopeDescription) }}</p><p>覆盖所选周期全部工单，切换图表单位或搜索明细不改变此处结论。</p>
      </details>
    </div>

    <aside v-if="focusAlert" class="focus-context" role="note" :aria-label="`当前核对：${focusTitle}`">
      <div class="focus-context__copy">
        <UBadge color="warning" variant="subtle">当前核对</UBadge>
        <strong>{{ localizeBlacklakeText(focusAlert.title) }}</strong>
        <span>责任角色：{{ focusAlert.owner }}</span>
        <span>动作：{{ localizeBlacklakeText(focusAlert.action) }}</span>
      </div>
      <UButton color="neutral" variant="outline" size="sm" type="button" @click="clearFocus">清除上下文</UButton>
    </aside>

    <div class="insight-strip">
      <article class="insight-strip__item">
        <span class="insight-label">先看结论</span>
        <p>{{ localizeBlacklakeText(report.summary) }}</p>
      </article>
      <article class="insight-strip__item insight-strip__item--impact">
        <span class="insight-label">哪些数字值得关注</span>
        <p class="impact-copy">{{ localizeBlacklakeText(impact) }}</p>
        <details v-if="remainingObservations.length" class="insight-details insight-details--inline">
          <summary>其他观察（{{ remainingObservations.length }}）</summary>
          <ul v-if="remainingObservations.length"><li v-for="item in remainingObservations" :key="item">{{ localizeBlacklakeText(item) }}</li></ul>
        </details>
      </article>
      <article class="insight-strip__item insight-strip__item--next">
        <span class="insight-label">接下来做什么</span>
        <p>{{ report.nextSteps[0] ? localizeBlacklakeText(report.nextSteps[0]) : '暂无建议动作。' }}</p>
      </article>
    </div>

    <div class="detail-list">
      <details v-if="report.localAnalysis" class="insight-details" data-testid="local-business-interpretation"><summary>原因与判断依据</summary><p>{{ report.localAnalysis.whyItMatters }}</p><p>{{ report.localAnalysis.cannotConclude }}</p></details>
      <details v-if="report.hypotheses.length" class="insight-details insight-details--hypothesis">
        <summary><span>可能原因</span><UBadge color="warning" variant="subtle">未证实</UBadge></summary>
        <ul><li v-for="item in report.hypotheses" :key="item">{{ localizeBlacklakeText(item) }}</li></ul>
      </details>
      <details v-if="report.nextSteps.length > 1" class="insight-details">
        <summary>其余建议动作（{{ report.nextSteps.length - 1 }}）</summary>
        <ul><li v-for="item in report.nextSteps.slice(1)" :key="item">{{ localizeBlacklakeText(item) }}</li></ul>
      </details>
      <details v-if="report.calculation.length" class="insight-details">
        <summary>计算口径</summary>
        <ul><li v-for="item in report.calculation" :key="item">{{ localizeBlacklakeText(item) }}</li></ul>
      </details>
      <details v-if="report.limitations.length" class="insight-details">
        <summary>使用边界</summary>
        <ul><li v-for="item in report.limitations" :key="item">{{ localizeBlacklakeText(item) }}</li></ul>
      </details>
      <details v-if="evidenceSources.length || report.evidenceRefs.length" class="insight-details">
        <summary>数据来源</summary>
        <ul v-if="evidenceSources.length" class="source-list"><li v-for="source in evidenceSources" :key="`${source.label}-${source.description}`"><strong>{{ source.label }}</strong><span>{{ localizeBlacklakeText(source.description) }}</span></li></ul>
        <p v-else class="empty-source">暂未提供可读来源说明。</p>
      </details>
      <details v-if="relatedOrders.length" class="insight-details related-orders">
        <summary>需要回到工单核对（{{ relatedOrders.length }}）</summary>
        <div v-for="order in relatedOrders" :key="order.projectId" class="related-order">
          <div class="related-order__heading"><strong>{{ order.projectCode }} · {{ order.product }}</strong><UButton color="primary" variant="outline" size="sm" type="button" @click="emit('locate-order', order.projectId)">核对工单</UButton></div>
          <details v-if="order.reasons.length" class="insight-details insight-details--nested">
            <summary>为什么要核对</summary>
            <ul><li v-for="reason in order.reasons" :key="reason">{{ localizeBlacklakeText(reason) }}</li></ul>
          </details>
        </div>
      </details>
    </div>

    <div class="followup" data-testid="section-followup">
      <div class="followup-heading">
        <div><h4>继续追问</h4><p>针对上面这组数据，继续问原因或处理办法。</p></div>
        <div class="followup-actions">
          <span v-if="loading" class="followup-status" role="status">正在核对…</span>
          <UButton color="primary" variant="outline" type="button" :aria-expanded="chatOpen" :aria-controls="chatId" @click="toggleChat">{{ chatOpen ? '收起追问' : '继续追问' }}</UButton>
        </div>
      </div>
      <div v-if="chatOpen" :id="chatId" class="followup-panel">
        <div v-if="report.suggestedQuestions.length" class="suggested-questions" aria-label="建议问题">
          <button v-for="suggestion in report.suggestedQuestions" :key="suggestion" type="button" @click="useSuggestedQuestion(suggestion)">{{ suggestion }}</button>
        </div>
        <div v-if="visibleHistory.length" class="conversation" aria-live="polite">
          <div v-for="(message, index) in visibleHistory" :key="`${message.role}-${index}-${message.content}`" :class="['message', `message--${message.role}`]">
            <span class="message-role">{{ message.role === 'user' ? '你' : '分析助手' }}</span>
            <p>{{ message.role === 'assistant' ? localizeBlacklakeText(message.content) : message.content }}</p>
          </div>
        </div>
        <form class="question-form" @submit.prevent="submitQuestion">
          <label class="sr-only" :for="`section-question-${report.sectionId}`">向这组数据提问</label>
          <input
            :id="`section-question-${report.sectionId}`"
            ref="questionInput"
            v-model="question"
            type="text"
            maxlength="500"
            placeholder="例如：这段数据最先需要核对什么？"
            :aria-invalid="Boolean(error)"
          >
          <span class="character-count">{{ questionLength }}/500</span>
          <UButton color="primary" type="submit" :disabled="!canAsk">发送</UButton>
        </form>
        <p v-if="error" class="followup-error" role="alert">{{ error }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped lang="scss">
.section-insight {
  --insight-primary: #1b65a8;
  --insight-ink: #1a293d;
  --insight-text: #41536a;
  --insight-page: #f4f6f9;
  --insight-line: #e5eaf1;
  --insight-radius: 10px;
  margin: 18px 0 22px;
  padding: 18px;
  border: 1px solid var(--insight-line);
  border-radius: var(--insight-radius);
  background: #fff;
  box-shadow: 0 5px 24px rgb(27 47 78 / 4%);
}
.insight-header, .followup-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.insight-heading { min-width: 0; }
.insight-kicker { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; color: var(--insight-primary); font-size: 11px; font-weight: 650; letter-spacing: .05em; }
h3, h4 { color: var(--insight-ink); }
h3 { margin: 0 0 6px; font-size: 17px; letter-spacing: -.01em; }
h4 { margin: 0 0 3px; font-size: 14px; }
.insight-summary, .followup-heading p { margin: 0; color: var(--insight-text); line-height: 1.65; }
.insight-scope { margin: 5px 0 0; color: #687f98; font-size: 12px; line-height: 1.6; }
.scope-details { flex: none; color: #8491a3; font-size: 11px; }
.scope-details summary { padding: 4px 8px; border-radius: 999px; color: var(--insight-primary); background: var(--insight-page); cursor: pointer; list-style: none; white-space: nowrap; }
.scope-details summary::-webkit-details-marker { display: none; }
.scope-details > div { margin-top: 7px; white-space: nowrap; }
.focus-context { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-top: 15px; padding: 11px 12px; border: 1px solid #ead9ad; border-radius: 8px; background: #fffaf0; }
.focus-context__copy { display: flex; align-items: center; flex-wrap: wrap; gap: 7px 10px; min-width: 0; color: var(--insight-text); font-size: 12px; }
.focus-context__copy strong { color: var(--insight-ink); }
.focus-context__copy span { color: #846d43; }
.insight-strip { display: grid; grid-template-columns: 1.1fr 1.25fr 1fr; margin-top: 16px; border: 1px solid var(--insight-line); border-radius: 8px; background: #fbfcfe; overflow: hidden; }
.insight-strip__item { min-width: 0; padding: 13px 15px; border-right: 1px solid var(--insight-line); }
.insight-strip__item:last-child { border-right: 0; }
.insight-strip__item--impact { background: #f8fbfe; }
.insight-strip__item--next { background: #f8fbfa; }
.insight-label { display: block; margin-bottom: 5px; color: #8491a3; font-size: 11px; font-weight: 650; letter-spacing: .04em; }
.insight-strip p { margin: 0; color: var(--insight-text); font-size: 13px; line-height: 1.6; }
.impact-copy { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.detail-list { display: grid; gap: 7px; margin-top: 10px; }
.insight-details { border: 1px solid var(--insight-line); border-radius: 7px; color: var(--insight-text); background: #fff; }
.insight-details summary { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 9px 11px; color: var(--insight-primary); cursor: pointer; font-size: 12px; font-weight: 600; list-style-position: inside; }
.insight-details summary::marker { color: var(--insight-primary); }
.insight-details > ul, .insight-details > p { margin: 0; padding: 0 12px 11px 29px; color: var(--insight-text); font-size: 12px; line-height: 1.7; }
.insight-details--inline { margin-top: 8px; border: 0; background: transparent; }
.insight-details--inline summary { padding: 0; font-size: 11px; }
.insight-details--inline > p, .insight-details--inline > ul { padding: 6px 0 0 15px; font-size: 12px; }
.insight-details--hypothesis { border-color: #ead9ad; background: #fffdf8; }
.source-list { display: grid; gap: 7px; margin: 0; padding: 0 12px 11px 29px; color: var(--insight-text); font-size: 12px; line-height: 1.7; }
.source-list strong { margin-right: 7px; color: var(--insight-ink); }
.empty-source { padding-left: 29px !important; color: #8491a3 !important; }
.related-orders { background: #fbfcfe; }
.related-order { padding: 0 12px 11px; }
.related-order + .related-order { padding-top: 9px; border-top: 1px solid var(--insight-line); }
.related-order__heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--insight-ink); font-size: 12px; }
.insight-details--nested { margin-top: 7px; border-color: #d8e2ed; }
.insight-details--nested summary { padding: 7px 9px; font-size: 11px; }
.insight-details--nested > ul { padding: 0 10px 8px 26px; }
.followup { margin-top: 15px; padding-top: 15px; border-top: 1px solid var(--insight-line); }
.followup-heading { align-items: center; }
.followup-actions { display: flex; align-items: center; gap: 10px; flex: none; }
.followup-status { color: var(--insight-primary); font-size: 12px; }
.followup-panel { margin-top: 12px; padding: 12px; border: 1px solid var(--insight-line); border-radius: 8px; background: var(--insight-page); }
.suggested-questions { display: flex; flex-wrap: wrap; gap: 7px; margin-bottom: 11px; }
.suggested-questions button { padding: 6px 10px; border: 1px solid #d8e2ed; border-radius: 999px; color: var(--insight-primary); background: #fff; cursor: pointer; font: inherit; font-size: 12px; }
.suggested-questions button:hover { border-color: var(--insight-primary); background: #edf4fa; }
.conversation { display: grid; gap: 8px; max-height: 260px; margin: 11px 0; overflow: auto; }
.message { max-width: 88%; padding: 8px 11px; border-radius: 8px; }
.message--user { justify-self: end; background: #eaf2fa; }
.message--assistant { justify-self: start; background: #fff; }
.message-role { display: block; margin-bottom: 2px; color: #8491a3; font-size: 11px; }
.message p { margin: 0; color: var(--insight-ink); line-height: 1.6; white-space: pre-wrap; }
.question-form { position: relative; display: flex; align-items: center; gap: 8px; }
.question-form input { min-width: 0; flex: 1; padding: 9px 58px 9px 11px; border: 1px solid #d8e2ed; border-radius: 7px; color: var(--insight-ink); background: #fff; font: inherit; }
.question-form input:focus { outline: 2px solid rgb(27 101 168 / 28%); outline-offset: 1px; }
.character-count { position: absolute; right: 84px; color: #8491a3; font-size: 11px; pointer-events: none; }
.followup-error { margin: 8px 0 0; color: #bb635f; font-size: 12px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 760px) {
  .insight-header, .focus-context, .followup-heading { flex-direction: column; }
  .scope-details { align-self: flex-start; }
  .insight-strip { grid-template-columns: 1fr; }
  .insight-strip__item { border-right: 0; border-bottom: 1px solid var(--insight-line); }
  .insight-strip__item:last-child { border-bottom: 0; }
  .followup-actions { width: 100%; justify-content: space-between; }
}
</style>
