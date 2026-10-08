<template>
  <div ref="panelRef" class="work-process-ai-chat-panel" data-testid="workflow-ai-compose-bar">
    <div v-if="hasSelectionContext" ref="contextChipRef" class="ai-context-attachment" :title="contextLabel">
      <el-icon><Aim /></el-icon>
      <span>{{ contextDisplayLabel }}</span>
    </div>

    <el-input
      v-model="input"
      class="ai-single-input"
      type="textarea"
      :autosize="{ minRows: 1, maxRows: 4 }"
      resize="none"
      :disabled="disabled || loading"
      :placeholder="placeholder"
      @keydown.enter.exact.prevent="send"
      @keydown.ctrl.enter.prevent="send"
    />

    <div class="ai-composer-footer">
      <div class="ai-composer-tools">
        <el-popover
          v-model:visible="historyVisible"
          placement="top-start"
          :width="720"
          trigger="click"
          popper-class="workflow-ai-history-popper"
        >
          <template #reference>
            <el-button class="ai-history-button" text aria-label="打开 AI 对话记录">
              <el-icon><MagicStick /></el-icon>
              <span>AI 对话</span>
            </el-button>
          </template>
          <div class="ai-history" data-testid="ai-history">
          <div class="panel-header">
            <div>
              <div class="panel-title">{{ title }}</div>
              <div class="panel-subtitle">AI 只修改当前指定范围，生成草稿后仍需人工确认</div>
            </div>
            <el-tag size="small" type="info" data-testid="ai-mode-label">{{ modeLabel }}</el-tag>
          </div>
          <div ref="messagesRef" class="chat-messages" data-testid="ai-chat-thread">
            <div v-for="(msg, index) in messages" :key="index" :class="['message', msg.role]">
              <div class="message-content" :data-testid="msg.role === 'user' ? 'ai-chat-user-bubble' : 'ai-chat-assistant-body'">{{ msg.content }}</div>
              <div v-if="msg.diffPreview?.length" class="diff-preview" data-testid="ai-diff-preview">
                <template v-for="(diff, diffIndex) in msg.diffPreview" :key="diffIndex">
                  <div v-if="diff.type === 'PRODUCT_PROCESS_WORKFLOW_SPEC'" class="workflow-change-preview" data-testid="workflow-spec-preview">
                    <template v-if="diff.proposal">
                      <strong>全部 {{ diff.proposal.rows.length }} 项改动</strong>
                      <p class="preview-impact">生成后将替换整个当前画布，可撤销；未匹配的工序会在应用时新建。原字段和未支持字段照原样列出，请核对生成结果后保存。</p>
                      <div class="workflow-plan-steps" aria-label="AI 方案工序明细">
                        <div v-for="(step, stepIndex) in getSpecSteps(diff.proposal.spec)" :key="stepIndex" data-testid="workflow-spec-step">
                          <strong>{{ stepIndex + 1 }} · {{ step.process }}</strong>
                          <div>类别：{{ step.processCategory ?? '未提供（按工序目录解析）' }}；负责人：{{ step.responsibleWorkerName ?? step.responsibleWorkerId ?? '未提供' }}</div>
                          <div>产出：{{ formatSpecValue(step.outputs) }}</div>
                          <div>额外原料：{{ formatSpecValue(step.inputs) }}；辅料 / 调料：{{ formatSpecValue(step.seasonings) }}</div>
                        </div>
                      </div>
                      <p v-if="diff.previewError" role="status">{{ diff.previewError }}</p>
                      <ol class="workflow-change-list">
                        <li v-for="row in diff.proposal.rows" :key="row.path" :data-change-path="row.path" data-testid="workflow-change-row">{{ row.text }}</li>
                      </ol>
                      <div v-if="diff.proposal.state === 'pending'" class="workflow-preview-actions">
                        <el-button type="primary" :disabled="disabled || loading" data-testid="workflow-spec-apply" @click="applySpecProposal(diff, msg.sourceIdentity)">应用配置</el-button>
                        <el-button data-testid="workflow-spec-cancel" @click="cancelSpecProposal(diff)">取消</el-button>
                      </div>
                      <p v-else role="status">{{ diff.proposal.state === 'cancelled' ? '已取消，画布没有改动。' : '已提交应用，请检查画布后保存。' }}</p>
                    </template>
                    <p v-else role="status">{{ diff.previewError }}</p>
                  </div>
                  <div v-else-if="diff.type === 'PRODUCT_WORK_PROCESS_DRAFT' && isDraftParams(diff.params)" class="draft-step-list" data-testid="ai-diff-item">
                    <div
                      v-for="(step, stepIndex) in getDraftSteps(diff.params)"
                      :key="stepIndex"
                      class="draft-step-card"
                      data-testid="ai-draft-step"
                    >
                      <span class="step-order">{{ step.processOrder }}</span>
                      <span class="step-name">{{ step.processName }}</span>
                      <el-tag v-if="step.processCategory" size="small" type="info">{{ step.processCategory }}</el-tag>
                      <span v-if="step.responsibleWorkerName" class="step-assignee">{{ step.responsibleWorkerName }}</span>
                      <el-tag size="small" :type="step.operation === 'update' ? 'warning' : 'success'">
                        {{ step.operation === 'update' ? '更新' : '新建' }}
                      </el-tag>
                    </div>
                    <div v-if="getMissingProcesses(diff.params).length" class="missing-warning">
                      <el-alert
                        :title="`${getMissingProcesses(diff.params).length} 个工序未匹配，请先在工序管理中新建`"
                        type="warning"
                        :closable="false"
                        show-icon
                      />
                    </div>
                    <el-button class="apply-draft-btn" type="primary" data-testid="ai-diff-apply" @click="emitDraft(diff.params, msg.sourceIdentity)">
                      应用 {{ getDraftSteps(diff.params).length }} 道工序到草稿
                    </el-button>
                  </div>
                  <div v-else class="diff-item" data-testid="ai-diff-item">
                    <div class="diff-text">
                      <el-tag size="small" type="warning" data-testid="ai-diff-type">{{ diffTypeLabel(diff.type) }}</el-tag>
                      <span>{{ diff.description }}</span>
                    </div>
                    <el-button class="diff-apply" type="primary" link data-testid="ai-diff-apply" @click="emitDraft(diff.params, msg.sourceIdentity)">
                      应用到草稿
                    </el-button>
                  </div>
                </template>
              </div>
            </div>

            <!-- W1: 加载用骨架屏 (3 条 + 一行文字), 不用转圈 -->
            <div v-if="loading" class="message assistant">
              <div class="loading-skeleton" data-testid="ai-chat-loading-skeleton" role="status" aria-live="polite">
                <span
                  v-for="w in SKELETON_BAR_WIDTHS"
                  :key="w"
                  class="skeleton-bar"
                  data-testid="ai-chat-skeleton-bar"
                  :style="{ width: `${w}%` }"
                />
                <span class="skeleton-caption">正在生成工序草稿…</span>
              </div>
            </div>
          </div>
          </div>
        </el-popover>

        <el-dropdown
          v-if="quickPrompts.length"
          trigger="click"
          :disabled="disabled || loading"
          @command="runQuickPrompt"
        >
          <el-button class="quick-prompt-button" text>
            <el-icon><Menu /></el-icon>
            <span>快捷问题</span>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="prompt in quickPrompts" :key="prompt" :command="prompt">
                {{ prompt }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <el-button
        class="ai-send-button"
        type="primary"
        circle
        aria-label="发送给 Workflow AI"
        :loading="loading"
        :disabled="disabled || !input.trim()"
        @click="send"
      >
        <el-icon><Top /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Aim, MagicStick, Menu, Top } from '@element-plus/icons-vue';
import { gsap } from 'gsap';
import request from '@/api/request';
import { workflowAiStableJson, type WorkflowAiPreview, type WorkflowAiChangeRow } from '@/views/system/product-processes/workflow/workflowAiPreview';
import {
  AI_MODE_LABELS,
  AI_TIMEOUT_MESSAGE,
  AI_FAILURE_MESSAGE,
  SKELETON_BAR_WIDTHS,
  diffTypeLabel,
  isNearBottom,
  startAiRequestTimer,
  type AiRequestTimer,
} from '@/utils/aiConfigAssistant';

type ChatRole = 'system' | 'user' | 'assistant';

interface ChatDiff {
  type: string;
  tool: string;
  params: Record<string, unknown>;
  description: string;
  previewError?: string;
  proposal?: {
    spec: unknown;
    rows: WorkflowAiChangeRow[];
    revision: string;
    state: 'pending' | 'submitted' | 'cancelled';
  };
}

interface SourceIdentity {
  factoryId: string;
  productTypeId: string;
}

interface ChatMessage {
  role: ChatRole;
  content: string;
  sourceIdentity?: SourceIdentity;
  diffPreview?: ChatDiff[];
}

interface AIChatResponse {
  reply?: string;
  diffs?: ChatDiff[];
  applied?: boolean;
}

const props = withDefaults(defineProps<{
  factoryId: string;
  productTypeId?: string;
  endpoint: string;
  moduleCode: string;
  title?: string;
  disabled?: boolean;
  context?: Record<string, unknown>;
  contextLabel?: string;
  quickPrompts?: string[];
  previewSpec?: (spec: unknown) => WorkflowAiPreview | Promise<WorkflowAiPreview>;
}>(), {
  title: 'AI 配工序',
  productTypeId: '',
  disabled: false,
  context: () => ({}),
  contextLabel: '当前范围：整个 Workflow',
  quickPrompts: () => [],
});

const emit = defineEmits<{
  applyDraft: [draft: Record<string, unknown>, sourceIdentity: SourceIdentity];
}>();

// V3: 模式名给用户看中文 (这个面板固定走 plan: 只出草稿, 点「应用」才写进编辑器草稿)
const modeLabel = computed(() => AI_MODE_LABELS.plan);
const hasSelectionContext = computed(() => (
  Boolean(props.contextLabel)
  && props.contextLabel !== '当前范围：整个 Workflow'
));
const contextDisplayLabel = computed(() => props.contextLabel.replace(/^当前范围：/, ''));
const placeholder = computed(() => (
  props.productTypeId
    ? '输入想要的修改'
    : '请先选择产品'
));

const input = ref('');
const loading = ref(false);
const historyVisible = ref(false);
const panelRef = ref<HTMLElement | null>(null);
const contextChipRef = ref<HTMLElement | null>(null);
const messagesRef = ref<HTMLElement>();
const messages = ref<ChatMessage[]>(initialMessages());
let requestGeneration = 0;
let activeTimer: AiRequestTimer | null = null;

onMounted(() => {
  if (!panelRef.value || prefersReducedMotion()) return;
  gsap.from(panelRef.value, {
    y: 10,
    autoAlpha: 0,
    duration: 0.24,
    ease: 'power3.out',
    clearProps: 'transform,opacity,visibility',
  });
});

onBeforeUnmount(() => {
  activeTimer?.cancel();
  gsap.killTweensOf(panelRef.value);
  gsap.killTweensOf(contextChipRef.value);
});

watch(() => props.contextLabel, async () => {
  await nextTick();
  if (!contextChipRef.value || prefersReducedMotion()) return;
  gsap.fromTo(contextChipRef.value, { scale: 0.97, autoAlpha: 0.72 }, {
    scale: 1,
    autoAlpha: 1,
    duration: 0.2,
    ease: 'power3.out',
    overwrite: 'auto',
    clearProps: 'transform,opacity,visibility',
  });
});

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

watch(() => `${props.factoryId}:${props.productTypeId}`, () => {
  requestGeneration += 1;
  activeTimer?.cancel();
  activeTimer = null;
  input.value = '';
  loading.value = false;
  messages.value = initialMessages();
}, { immediate: true });

async function send(): Promise<void> {
  const text = input.value.trim();
  if (!text || props.disabled || !props.productTypeId) {
    return;
  }

  const sourceIdentity = {
    factoryId: props.factoryId,
    productTypeId: props.productTypeId,
  };
  const endpoint = props.endpoint;
  const moduleCode = props.moduleCode;
  const context = props.context;
  const generation = ++requestGeneration;
  messages.value.forEach(message => message.diffPreview?.forEach(cancelSpecProposal));
  historyVisible.value = true;
  input.value = '';
  pushMessage({ role: 'user', content: text, sourceIdentity }, true);
  loading.value = true;
  const timer = startAiRequestTimer();
  activeTimer = timer;
  try {
    const res = await request.post<AIChatResponse>(endpoint, {
      message: text,
      mode: 'plan',
      moduleCode,
      params: {
        productTypeId: sourceIdentity.productTypeId,
        context,
      },
    }, { signal: timer.signal });
    if (!isCurrentRequest(generation, sourceIdentity)) return;
    const data = res.data ?? {};
    // D1 B-1: when diffs are empty (tool failure surfaced as error reply), show guidance text
    const hasDiffs = Array.isArray(data.diffs) && data.diffs.length > 0;
    pushMessage({
      role: 'assistant',
      content: data.reply || (hasDiffs ? '已生成草稿，请审核后应用。' : '无法生成草稿，请检查工序描述'),
      sourceIdentity,
      diffPreview: await Promise.all((data.diffs || []).map(prepareDiff)),
    });
  } catch (e) {
    if (!isCurrentRequest(generation, sourceIdentity)) return;
    console.error('[WorkProcessAIChatPanel] send failed:', e);
    // W2 / H5: 超时与失败都说人话, 并明说配置没有任何改动 (「应用到草稿」要用户自己点)
    pushMessage({
      role: 'assistant',
      content: timer.timedOut ? AI_TIMEOUT_MESSAGE : AI_FAILURE_MESSAGE,
      sourceIdentity,
    });
  } finally {
    timer.done();
    if (activeTimer === timer) activeTimer = null;
    if (isCurrentRequest(generation, sourceIdentity)) loading.value = false;
  }
}

/** N1: 先量「本来是不是在底部附近」, 再追加内容; 用户自己发消息 (force) 一定滚 */
function pushMessage(msg: ChatMessage, force = false): void {
  const follow = force || isNearBottom(messagesRef.value);
  messages.value.push(msg);
  if (follow) void nextTick().then(scrollMessagesToBottom);
}

function initialMessages(): ChatMessage[] {
  return [{ role: 'system', content: '选择产品后，描述工序顺序和责任小组长，AI 会生成草稿供你保存。' }];
}

function isCurrentRequest(generation: number, identity: SourceIdentity): boolean {
  return generation === requestGeneration
    && identity.factoryId === props.factoryId
    && identity.productTypeId === props.productTypeId;
}

async function runQuickPrompt(prompt: string): Promise<void> {
  input.value = prompt;
  await send();
}

function scrollMessagesToBottom(): void {
  const messageContainer = messagesRef.value;
  messageContainer?.scrollTo({ top: messageContainer.scrollHeight, behavior: 'smooth' });
}

function emitDraft(draft: Record<string, unknown>, sourceIdentity?: SourceIdentity): void {
  if (!sourceIdentity || !isCurrentSource(sourceIdentity)) return;
  emit('applyDraft', draft, sourceIdentity);
}

function isCurrentSource(identity: SourceIdentity): boolean {
  return identity.factoryId === props.factoryId
    && identity.productTypeId === props.productTypeId;
}

async function prepareDiff(diff: ChatDiff): Promise<ChatDiff> {
  if (diff.type !== 'PRODUCT_PROCESS_WORKFLOW_SPEC') return diff;
  const spec = JSON.parse(JSON.stringify(diff.params.spec ?? null));
  const preview = await props.previewSpec?.(spec);
  if (!preview || preview.ok === false) return { ...diff, previewError: preview && preview.ok === false ? preview.message : '当前画布无法预览，未应用配置。' };
  return { ...diff, proposal: { spec, rows: preview.rows, revision: preview.revision, state: 'pending' } };
}

function getSpecSteps(spec: unknown): Record<string, unknown>[] {
  if (!spec || typeof spec !== 'object' || !Array.isArray((spec as Record<string, unknown>).steps)) return [];
  return (spec as { steps: Record<string, unknown>[] }).steps;
}

function formatSpecValue(value: unknown): string {
  return value === undefined ? '未提供' : JSON.stringify(value);
}

function cancelSpecProposal(diff: ChatDiff): void {
  if (diff.proposal?.state === 'pending') diff.proposal.state = 'cancelled';
}

async function applySpecProposal(diff: ChatDiff, sourceIdentity?: SourceIdentity): Promise<void> {
  const proposal = diff.proposal;
  if (!proposal || proposal.state !== 'pending' || props.disabled || loading.value
    || !sourceIdentity || !isCurrentSource(sourceIdentity)) return;
  const recheck = await props.previewSpec?.(proposal.spec);
  if (!recheck || recheck.ok === false) {
    diff.previewError = recheck && recheck.ok === false ? recheck.message : '当前画布无法预览，未应用配置。';
    diff.proposal = undefined;
    return;
  }
  if (recheck.revision !== proposal.revision
    || workflowAiStableJson(recheck.rows) !== workflowAiStableJson(proposal.rows)) {
    proposal.rows = recheck.rows;
    proposal.revision = recheck.revision;
    diff.previewError = '画布已变化，已刷新全部改动，请重新审核并应用。';
    return;
  }
  proposal.state = 'submitted';
  emitDraft({ ...diff.params, spec: proposal.spec }, sourceIdentity);
}

interface DraftStep {
  operation: string;
  productWorkProcessId: unknown;
  workProcessId: string;
  processName: string;
  processCategory: string | null;
  unit: string | null;
  processOrder: number;
  responsibleWorkerId: number | null;
  responsibleWorkerName: string | null;
}

interface MissingProcess {
  name: string;
  reason: string;
}

function isDraftParams(params: Record<string, unknown>): boolean {
  return Array.isArray(params.draft);
}

function getDraftSteps(params: Record<string, unknown>): DraftStep[] {
  if (!Array.isArray(params.draft)) return [];
  return params.draft as DraftStep[];
}

function getMissingProcesses(params: Record<string, unknown>): MissingProcess[] {
  if (!Array.isArray(params.missingProcesses)) return [];
  return params.missingProcesses as MissingProcess[];
}
</script>

<style scoped>
.work-process-ai-chat-panel {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 2px;
  width: 100%;
  min-height: 84px;
  padding: 10px 10px 8px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  box-shadow: 0 4px 10px rgb(31 62 92 / 13%);
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.work-process-ai-chat-panel:focus-within {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 5px 12px rgb(31 62 92 / 16%);
}

/* T1: 对话区自己声明系统字体栈 (不改全局 --font-family) */
.ai-history {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.panel-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.panel-subtitle {
  margin-top: 2px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.chat-messages {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  max-height: min(480px, 56vh);
  overflow-y: auto;
  margin-top: 12px;
  padding-right: 4px;
}

.ai-composer-footer,
.ai-composer-tools,
.ai-context-attachment {
  display: flex;
  align-items: center;
}
.ai-composer-footer { justify-content: space-between; gap: 10px; min-height: 32px; }
.ai-composer-tools { gap: 2px; min-width: 0; }
.ai-history-button, .quick-prompt-button { flex: 0 0 auto; }
.ai-history-button {
  min-height: 32px; margin: 0; padding-inline: 9px; border-radius: 8px;
  color: var(--el-text-color-regular); font-weight: 550;
}
.ai-history-button :deep(.el-icon) { font-size: 14px; }
.ai-context-attachment {
  align-self: flex-start; gap: 6px; max-width: min(100%, 520px); min-height: 32px;
  overflow: hidden; padding: 0 9px; border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px; color: var(--el-text-color-regular); background: var(--el-fill-color-lighter);
  font-size: 13px; font-weight: 550; white-space: nowrap;
}
.ai-context-attachment > span { overflow: hidden; text-overflow: ellipsis; }
.ai-context-attachment :deep(.el-icon) { flex: 0 0 auto; color: var(--el-color-primary); }
.quick-prompt-button {
  min-height: 32px; margin: 0; padding-inline: 9px; border-radius: 8px;
  color: var(--el-text-color-regular);
}
.quick-prompt-button:hover { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.ai-single-input { width: 100%; }
.ai-single-input :deep(.el-textarea__inner) {
  min-height: 40px !important; max-height: 104px; padding: 8px 2px;
  border: 0; border-radius: 0; background: transparent; box-shadow: none;
  font-size: 16px; line-height: 24px; overflow-y: auto;
}
.ai-single-input :deep(.el-textarea__inner:hover),
.ai-single-input :deep(.el-textarea__inner:focus) { box-shadow: none; }
.ai-single-input :deep(.el-textarea__inner::placeholder) { color: var(--el-text-color-placeholder); }
.ai-send-button {
  width: 32px; height: 32px; min-width: 32px; margin: 0;
  transition: transform 160ms ease, background-color 160ms ease;
}
.ai-send-button:not(.is-disabled):active { transform: scale(0.96); }
:global(.workflow-ai-history-popper) {
  max-width: calc(100vw - 32px); padding: 14px !important; border-radius: 12px !important;
}

/* T2: 正文 16px / 行高 1.6 */
.message {
  font-size: 16px;
  line-height: 1.6;
  min-width: 0;
  word-break: break-word;
}

/* L1 / L3: 助手回答与欢迎语全宽, 无头像、无气泡 (无背景 / 边框 / 阴影) */
.message.system,
.message.assistant {
  align-self: stretch;
  width: 100%;
}

.message.system {
  color: var(--el-text-color-regular);
}

/* L2: 只有用户消息是浅色气泡, 靠右, 最宽 min(78%, 320px), 四角圆角 12, 内边距 10px 12px */
.message.user {
  align-self: flex-end;
  max-width: min(78%, 320px);
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
}

.message-content {
  white-space: pre-wrap;
}

/* W1: 骨架屏 —— 3 条, 高 14, 圆角 5, 1.5s 闪烁 */
.loading-skeleton { display: flex; flex-direction: column; gap: 8px; padding: 4px 0; }
.skeleton-bar {
  display: block; height: 14px; border-radius: 5px;
  background: linear-gradient(90deg, var(--el-fill-color, #f0f2f5) 25%, var(--el-fill-color-darker, #e6e8eb) 37%, var(--el-fill-color, #f0f2f5) 63%);
  background-size: 400% 100%;
  animation: wp-ai-skeleton-shimmer 1.5s ease-in-out infinite;
}
.skeleton-caption { margin-top: 4px; font-size: 13px; color: var(--el-text-color-secondary); }
@keyframes wp-ai-skeleton-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

.diff-preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.workflow-change-preview { min-width: 0; }
.workflow-plan-steps { display: grid; gap: 8px; margin: 8px 0; }
.workflow-plan-steps > div { padding: 6px 8px; border-left: 2px solid var(--el-border-color); overflow-wrap: anywhere; }
.preview-impact { color: var(--el-text-color-secondary); margin: 6px 0; }
.workflow-change-list { padding-left: 22px; margin: 8px 0; }
.workflow-change-list li { margin: 5px 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.workflow-preview-actions { display: flex; gap: 8px; margin-top: 10px; }

.diff-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px;
  font-size: 13px;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.diff-text {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

/* PRODUCT_WORK_PROCESS_DRAFT step card renderer */
.draft-step-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
}

.draft-step-card {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-bg-color);
  font-size: 13px;
}

.step-order {
  min-width: 20px;
  font-weight: 700;
  color: var(--el-color-primary);
  text-align: center;
}

.step-name {
  flex: 1;
  font-weight: 500;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-assignee {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.missing-warning {
  margin-top: 4px;
}

.diff-apply {
  min-height: 32px;
}

.apply-draft-btn {
  margin-top: 8px;
  align-self: flex-start;
}

@media (max-width: 760px) {
  .work-process-ai-chat-panel { min-height: 78px; padding-inline: 8px; }
  .ai-context-attachment { max-width: 100%; }
  .ai-history-button span, .quick-prompt-button span { display: none; }
  .ai-history-button, .quick-prompt-button { width: 32px; padding-inline: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .work-process-ai-chat-panel, .ai-send-button { transition: none; }
  .skeleton-bar { animation: none; }
}
</style>
