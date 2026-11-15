<script setup lang="ts">
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { computed, ref, watch } from 'vue';

const props = withDefaults(defineProps<{
  modelValue: string;
  busy: boolean;
  error: string;
  response: string;
  resultTitle: string;
  employeeName: string;
  scopeLabel?: string;
  scopeKey?: string;
  quickQuestions?: readonly string[];
  responseStatus?: string;
  clarificationChoices?: readonly string[];
}>(), {
  scopeLabel: '',
  scopeKey: '',
  quickQuestions: () => [],
  responseStatus: '',
  clarificationChoices: () => [],
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
  send: [value: string];
  retry: [];
  reset: [];
}>();

interface ConversationMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  parts: [{ type: 'text'; text: string }];
}

const messages = ref<ConversationMessage[]>([]);
const pendingQuestion = ref<string | null>(null);
const sending = ref(false);
const lastResponse = ref('');
let messageSequence = 0;

watch(() => props.scopeKey, () => {
  messages.value = [];
  pendingQuestion.value = null;
  sending.value = false;
  lastResponse.value = '';
  messageSequence = 0;
  emit('update:modelValue', '');
}, { flush: 'sync' });

const draft = computed({
  get: () => props.modelValue,
  set: (value: string) => emit('update:modelValue', value),
});

const isBusy = computed(() => props.busy || sending.value);
const canSend = computed(() => !isBusy.value && props.modelValue.trim().length > 0);
const hasConversation = computed(() => messages.value.length > 0);
const showQuickQuestions = computed(() => !hasConversation.value && !props.busy && !props.error && !props.response);
const errorDescription = computed(() => props.error ? `${props.error}。本次为查询；办理单据请使用业务入口。` : '');
const reviewLabel = computed(() => ({
  NEED_MORE_INFO: '需要补充资料',
  WRITE_CONFIRM_REQUIRED: '请核对后确认',
  CONVERSATION_CONTINUE: '可继续提问',
  NEED_CLARIFICATION: '请补充查询条件',
  READ_MODE_WRITE_BLOCKED: '请到业务入口核对后办理',
}[props.responseStatus] ?? ''));

function nextId(role: ConversationMessage['role']): string {
  messageSequence += 1;
  return `${role}-${messageSequence}`;
}

function addMessage(role: ConversationMessage['role'], content: string): void {
  messages.value.push({
    id: nextId(role),
    role,
    content,
    parts: [{ type: 'text', text: content }],
  });
}

function submitQuestion(value = props.modelValue): void {
  if (isBusy.value) return;
  const question = value.trim();
  if (!question) return;

  addMessage('user', question);
  pendingQuestion.value = question;
  sending.value = true;
  emit('update:modelValue', '');
  emit('send', question);
}

function retryQuestion(): void {
  const question = pendingQuestion.value || props.modelValue.trim();
  if (isBusy.value || !question) return;
  sending.value = true;
  emit('update:modelValue', question);
  emit('retry');
}

function newConversation(): void {
  if (isBusy.value) return;
  messages.value = [];
  pendingQuestion.value = null;
  lastResponse.value = '';
  sending.value = false;
  emit('update:modelValue', '');
  emit('reset');
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey) return;
  if (event.ctrlKey || !event.altKey) {
    event.preventDefault();
    submitQuestion();
  }
}

function asText(value: unknown): string {
  return typeof value === 'string' ? value : value == null ? '' : String(value);
}

function renderMarkdown(value: unknown): string {
  const source = asText(value);
  return DOMPurify.sanitize(marked.parse(source, { async: false }));
}

watch(() => props.response, (response) => {
  if (!response) {
    lastResponse.value = '';
    return;
  }
  if (response === lastResponse.value) return;
  lastResponse.value = response;
  addMessage('assistant', response);
  pendingQuestion.value = null;
  sending.value = false;
  emit('update:modelValue', '');
}, { immediate: true });

watch(() => props.error, (error) => {
  if (error && pendingQuestion.value) {
    sending.value = false;
    emit('update:modelValue', pendingQuestion.value);
  }
});

watch(() => props.busy, (busy) => {
  if (!busy && !props.response && !props.error) sending.value = false;
});
</script>

<template>
  <section class="employee-conversation" aria-label="数字专员对话">
    <header class="conversation-header">
      <div>
        <p class="conversation-kicker">{{ employeeName }}<span v-if="scopeLabel"> · {{ scopeLabel }}</span></p>
        <h2>{{ resultTitle }}</h2>
      </div>
      <UButton data-testid="ai-chat-new-conversation" color="neutral" variant="outline" size="md" :disabled="isBusy" @click="newConversation">新对话</UButton>
    </header>

    <div v-if="showQuickQuestions" class="quick-questions" aria-label="推荐问题">
      <UButton v-for="question in quickQuestions" :key="question" color="neutral" variant="soft" size="md" @click="submitQuestion(question)">
        {{ question }}
      </UButton>
    </div>

    <div data-testid="ai-chat-thread" class="conversation-thread">
      <UChatMessages
        :messages="messages"
        :should-auto-scroll="true"
        :should-scroll-to-bottom="true"
        :user="{ side: 'right', variant: 'soft' }"
        :assistant="{ side: 'left', variant: 'naked' }"
        class="conversation-messages"
      >
        <template #content="{ content, role }">
          <div
            :data-testid="role === 'assistant' ? 'ai-chat-assistant-body' : 'ai-chat-user-bubble'"
            :class="role === 'assistant' ? 'assistant-body' : 'user-bubble'"
            v-html="renderMarkdown(content)"
          />
        </template>
      </UChatMessages>

      <div v-if="busy" data-testid="ai-chat-loading-skeleton" class="loading-state" role="status" aria-live="polite">
        <div class="loading-bars" aria-hidden="true"><span /><span /><span /></div>
        <span>正在理解你的话…</span>
      </div>

      <UAlert v-if="error" data-testid="ai-chat-error-banner" color="error" variant="soft" title="这次没有完成" :description="errorDescription">
        <template #actions>
          <UButton data-testid="ai-chat-retry" color="error" variant="outline" size="md" :disabled="isBusy" @click="retryQuestion">再试一次</UButton>
        </template>
      </UAlert>

      <div v-if="reviewLabel" class="response-status" role="status">
        <UBadge :label="reviewLabel" color="warning" variant="soft" size="md" />
      </div>
      <div v-if="responseStatus === 'NEED_CLARIFICATION' && clarificationChoices.length" class="quick-questions" aria-label="选择查询事项">
        <UButton v-for="choice in clarificationChoices" :key="choice" variant="outline" :disabled="isBusy" @click="submitQuestion(choice)">{{ choice }}</UButton>
      </div>
    </div>

    <form class="conversation-input" data-testid="ai-chat-input-frame" @submit.prevent="submitQuestion()">
      <UTextarea
        v-model="draft"
        data-testid="ai-chat-input"
        autoresize
        :maxrows="8"
        :disabled="isBusy"
        placeholder="输入要核对的业务问题"
        aria-label="输入业务问题"
        @keydown="handleKeydown"
      />
      <UButton type="submit" color="primary" variant="solid" size="md" :disabled="!canSend">发送</UButton>
      <span class="input-hint">Enter 发送 · Shift+Enter 换行</span>
    </form>
    <div class="response-action"><slot name="action" /></div>
  </section>
</template>

<style scoped>
.employee-conversation { min-width:0; display:grid; gap:16px; color:var(--cs-text); font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif; }
.conversation-header { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; flex-wrap:wrap; }
.conversation-kicker { margin:0 0 4px; color:var(--cs-muted); font-size:13px; line-height:20px; }
h2 { margin:0; font-size:20px; line-height:28px; }
.quick-questions { display:flex; flex-wrap:wrap; gap:8px; }
.conversation-thread { min-width:0; display:grid; gap:12px; }
.conversation-messages { min-width:0; width:100%; }
.assistant-body, .user-bubble { min-width:0; overflow-wrap:anywhere; font-size:16px; line-height:24px; }
.assistant-body { width:100%; background:transparent; border:0; box-shadow:none; }
.user-bubble { width:100%; max-width:none; padding:0; background:transparent; }
.user-bubble :deep(p) { margin:0; white-space:pre-wrap; word-break:normal; overflow-wrap:anywhere; }
.conversation-messages :deep([data-role='user'] [data-slot='body']) { min-width:0; max-width:min(80%,560px); }
.conversation-messages :deep([data-role='user'] [data-slot='content']) { min-width:0; background:var(--cs-primary-soft); }
.loading-state { display:grid; gap:8px; color:var(--cs-muted); font-size:14px; }
.loading-bars { display:grid; gap:6px; max-width:420px; }
.loading-bars span { display:block; height:10px; border-radius:999px; background:var(--cs-border); animation:conversation-loading 1.5s ease-in-out infinite; }
.loading-bars span:nth-child(2) { width:84%; animation-delay:120ms; }
.loading-bars span:nth-child(3) { width:62%; animation-delay:240ms; }
.loading-bars + span { line-height:20px; }
.response-status { display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
.response-action { min-width:0; }
.conversation-input { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:8px; align-items:end; padding-top:4px; }
.conversation-input :deep(textarea) { min-height:44px; max-height:200px; padding:12px; font:16px/24px inherit; border-radius:8px; }
.input-hint { grid-column:1 / -1; color:var(--cs-muted); font-size:12px; line-height:18px; }
@keyframes conversation-loading { 0%,100% { opacity:.5; } 50% { opacity:1; } }
@media (prefers-reduced-motion: reduce) { .loading-bars span { animation:none; } }
@media (max-width:600px) { .conversation-input { grid-template-columns:minmax(0,1fr); } .conversation-input > :deep(button) { width:100%; } .conversation-messages :deep([data-role='user'] [data-slot='body']) { max-width:100%; } }
</style>
