<script setup lang="ts">
/**
 * AnalysisChatPanel — reusable "整页 AI 分析面板" for analytics pages
 * (2026-07-12, member-analysis is the first consumer).
 *
 * Free-form questions use the unified Java intent orchestrator first. The
 * server then decides whether the turn is a narrow Gold read, a clarification,
 * a trusted context continuation or comprehensive synthesis. This prevents
 * analytics-page context from bypassing QueryPlan and Answer Contract.
 *
 * The focused chart context is sent in a dedicated `page_context` field rather
 * than prepended to `question`. This keeps display copy out of routing, date
 * parsing and semantic-cache matching while preserving chart-aware answers.
 *
 * Markdown + chart rendering is reused as-is from the existing chat UI
 * (ChatBubble.vue + ChatTypingIndicator.vue, same components
 * RestaurantChatPanel.vue uses) rather than re-implemented here — this
 * panel is a slimmer, page-agnostic shell around them (no restaurant-themed
 * styling, adds the 聚焦 selector + context-embedding on top).
 */
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { ElButton, ElInput, ElMessage, ElRadioButton, ElRadioGroup } from 'element-plus';
import ChatBubble from '../chat/ChatBubble.vue';
import ChatTypingIndicator from '../chat/ChatTypingIndicator.vue';
import { askRestaurantIntent } from '@/api/smartbi/restaurant-synthesis';
import { useAuthStore } from '@/store/modules/auth';
import type { ChatTurn } from '@/types/restaurant-chat';
import type { AnalysisChartContext } from './analysisBullets';

const props = defineProps<{
  /** Authenticated tenant used by the unified Java intent endpoint. */
  factoryId: string;
  /** One entry per chart on the page. Order is preserved in the 聚焦 selector. */
  contexts: AnalysisChartContext[];
  /** Page title, used as the "全页" focus label + fallback context title. */
  pageTitle: string;
}>();

/**
 * 版式分流 (AI 对话标准, 2026-10-08 Steve 裁定):
 *  - 工厂租户: 新标准版式 (助手全宽无头像 / 骨架屏 / 30s 超时 / 条件滚动 / 复制 / 示例芯片 ...)
 *  - 餐饮租户: 版式保持原样, 与改前逐字相同 (legacyParity.spec.ts 用改前 fixture 比对)
 * 租户判断与 AIQuery.vue 同源: authStore.businessDomain === 'RESTAURANT'。⛔ 不按页面路径判断:
 * 同一个面板既挂在餐饮页 restaurant/analytics/platform.vue, 也挂在 crm / ops 的分析页。
 */
const authStore = useAuthStore();
const isRestaurantTenant = computed(() => authStore.businessDomain === 'RESTAURANT');
const isStandard = computed(() => !isRestaurantTenant.value);

/** W2: 客户端超时 30 秒, 用 AbortController 中断。 */
const ANALYSIS_TIMEOUT_MS = 30_000;
/** N1: 离底部不超过 120px 才自动滚到底。 */
const STICK_TO_BOTTOM_PX = 120;
const SKELETON_BAR_WIDTHS = [92, 78, 62] as const;
/** 面板是只读分析, 失败 / 超时都不涉及写操作, 文案说人话即可。 */
const FRIENDLY_FAILURE_TEXT = '这次没分析完，请再问一次。';
/** F1: 空对话时的示例问题 (点了直接发)。 */
const EXAMPLE_QUESTIONS = ['用3条要点解读并给出建议', '这个数据说明什么问题', '有哪些值得关注的异常'] as const;

const ALL_FOCUS = '__all__';
const focusKey = ref<string>(ALL_FOCUS);

const turns = ref<ChatTurn[]>([]);
const isTyping = ref(false);
const inputText = ref('');
const chatContainer = ref<HTMLElement | null>(null);
/** 进行中的请求; 清空对话 / 切换聚焦 / 卸载时中断, 迟到的答案不再落到新对话里 (标准版式)。 */
let activeController: AbortController | null = null;
// Multi-turn memory scoped to the CURRENT focus — a focus switch starts a
// fresh session/conversation so the LLM doesn't carry over stale scope.
const sessionId = ref<string>(crypto.randomUUID());

/** Resolved focus target: either the whole page (all contexts concatenated) or one chart. */
interface FocusedAnalysisContext {
  title: string;
  dataSummary: string;
  dataScope: string;
  unavailableMetrics: string[];
  dimensionHints: string[];
}

const focusedContext = computed<FocusedAnalysisContext>(() => {
  if (focusKey.value === ALL_FOCUS) {
    const combined = props.contexts
      .filter((c) => c.dataSummary)
      .map((c) => `【${c.title}】${c.dataSummary}`)
      .join('\n');
    const scopes = props.contexts.map((c) => c.dataScope).filter((v): v is string => Boolean(v));
    const unavailableMetrics = [...new Set(props.contexts.flatMap((c) => c.unavailableMetrics ?? []))];
    return {
      title: props.pageTitle,
      dataSummary: combined,
      dataScope: scopes.join('；'),
      unavailableMetrics,
      dimensionHints: props.contexts.map((c) => c.key),
    };
  }
  const ctx = props.contexts.find((c) => c.key === focusKey.value);
  return ctx
    ? {
        title: ctx.title,
        dataSummary: ctx.dataSummary,
        dataScope: ctx.dataScope ?? '',
        unavailableMetrics: ctx.unavailableMetrics ?? [],
        dimensionHints: [ctx.key],
      }
    : {
        title: props.pageTitle,
        dataSummary: '',
        dataScope: '',
        unavailableMetrics: [],
        dimensionHints: [],
      };
});

const focusLabel = computed(() =>
  focusKey.value === ALL_FOCUS ? `整页 ${props.pageTitle}` : `「${focusedContext.value.title}」`,
);

function resetConversation() {
  if (isStandard.value && activeController) {
    activeController.abort();
    activeController = null;
    isTyping.value = false;
  }
  sessionId.value = crypto.randomUUID();
  turns.value = [];
}

onBeforeUnmount(() => {
  activeController?.abort();
  activeController = null;
});

/** Programmatic focus switch (called by a chart's own 「聚焦」 button via ref). */
function setFocus(key: string) {
  const nextKey = key || ALL_FOCUS;
  if (nextKey === focusKey.value) return;
  focusKey.value = nextKey;
  resetConversation();
  ElMessage.success(`已聚焦到 ${nextKey === ALL_FOCUS ? '全页' : focusedContext.value.title}`);
}

function onFocusChange() {
  resetConversation();
}

/** Build bounded display context; the raw user question remains untouched. */
function buildPageContext(): string {
  const { title, dataSummary, dataScope, unavailableMetrics } = focusedContext.value;
  const lines = [
    `【页面焦点】${focusKey.value === ALL_FOCUS ? `整页「${title}」` : `图表「${title}」`}`,
    `【页面展示数据】${dataSummary || '暂无数据'}`,
  ];
  if (dataScope) lines.push(`【数据口径】${dataScope}`);
  if (unavailableMetrics.length) {
    lines.push(
      `【页面摘要未提供】${unavailableMetrics.join('、')}。未提供不等于 0；不得仅凭页面摘要推算这些指标。`,
    );
  }
  const context = lines.join('\n');
  return context.length <= 1900
    ? context
    : `${context.slice(0, 1860)}\n【提示】页面上下文过长，已安全截断`;
}

/** 把一次请求和 AbortSignal 赛跑: 信号一响立刻以 AbortError 拒绝 (接口层不收 signal, 底层 HTTP 由它自己的超时收尾)。 */
function raceAbort<T>(work: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(new DOMException('aborted', 'AbortError'));
    if (signal.aborted) {
      onAbort();
      return;
    }
    signal.addEventListener('abort', onAbort, { once: true });
    work.then(
      (value) => { signal.removeEventListener('abort', onAbort); resolve(value); },
      (err: unknown) => { signal.removeEventListener('abort', onAbort); reject(err); },
    );
  });
}

/** N1: 当前是否在底部附近 (120px 内)。必须在往消息流里追加内容【之前】量。 */
function isNearBottom(): boolean {
  const el = chatContainer.value;
  if (!el) return true;
  return el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_TO_BOTTOM_PX;
}

async function sendMessage(text?: string) {
  const question = (text ?? inputText.value).trim();
  if (!question || isTyping.value) return;

  const userTurn: ChatTurn = {
    id: crypto.randomUUID(),
    role: 'user',
    content: question,
    timestamp: Date.now(),
  };
  turns.value.push(userTurn);
  inputText.value = '';
  await scrollToBottom();

  isTyping.value = true;
  // 标准版式 (工厂): 30s 客户端超时 + 可中断。餐饮保持原来的无超时行为。
  const controller = isStandard.value ? new AbortController() : null;
  let timedOut = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  if (controller) {
    activeController = controller;
    timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, ANALYSIS_TIMEOUT_MS);
    await scrollToBottom(); // 骨架屏出现后回到底部, 答案到达时的「离底部距离」只反映用户自己有没有往上翻
  }
  // N1: 追加答案之前量好「用户是不是还在底部附近」; 餐饮永远滚到底 (旧行为)
  let followBottom = true;
  try {
    const pending = askRestaurantIntent(props.factoryId, question, sessionId.value, {
      pageContext: buildPageContext(),
      dimensionHints: focusedContext.value.dimensionHints,
    });
    const response = controller ? await raceAbort(Promise.resolve(pending), controller.signal) : await pending;
    if (controller) followBottom = isNearBottom();

    if (!response.success) {
      if (controller) {
        // 标准版式: 说人话, 不露后端原文 (W2)
        turns.value.push({
          id: crypto.randomUUID(),
          role: 'ai',
          content: FRIENDLY_FAILURE_TEXT,
          timestamp: Date.now(),
        });
        ElMessage.error(FRIENDLY_FAILURE_TEXT);
      } else {
        // Honest failure: real backend/network message, never a fabricated answer.
        const errMsg = response.error || '分析失败，请稍后重试';
        turns.value.push({
          id: crypto.randomUUID(),
          role: 'ai',
          content: `**请求失败**\n\n${errMsg}`,
          timestamp: Date.now(),
          error: errMsg,
        });
        ElMessage.error('分析请求失败: ' + errMsg);
      }
    } else {
      turns.value.push({
        id: crypto.randomUUID(),
        role: 'ai',
        content: response.answer || '已完成分析',
        timestamp: Date.now(),
        charts: response.charts,
        alerts: response.alerts,
        followUpActions: response.followUpActions,
        source: response.source,
      });
    }
  } catch (error: unknown) {
    if (controller) {
      if (controller.signal.aborted && !timedOut) {
        // 用户清空对话 / 切换聚焦 / 离开页面: 静默丢弃, 不往新对话里塞迟到的失败
        followBottom = false;
        return;
      }
      followBottom = isNearBottom();
      turns.value.push({
        id: crypto.randomUUID(),
        role: 'ai',
        content: FRIENDLY_FAILURE_TEXT,
        timestamp: Date.now(),
      });
      ElMessage.error(FRIENDLY_FAILURE_TEXT);
    } else {
      const errMsg = error instanceof Error ? error.message : String(error);
      turns.value.push({
        id: crypto.randomUUID(),
        role: 'ai',
        content: `**请求失败**\n\n${errMsg}`,
        timestamp: Date.now(),
        error: errMsg,
      });
      ElMessage.error('分析请求失败: ' + errMsg);
    }
  } finally {
    if (timer) clearTimeout(timer);
    // 被清空 / 切换聚焦中断的旧请求不许把新请求的 isTyping 一起关掉
    if (!controller || activeController === controller) {
      isTyping.value = false;
      activeController = null;
    }
    if (followBottom) await scrollToBottom();
  }
}

async function scrollToBottom() {
  await nextTick();
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
  }
}

/** N2: 悬停出现的「复制」, 复制这条消息的正文。 */
async function copyTurn(turn: ChatTurn) {
  if (!turn.content) return;
  try {
    await navigator.clipboard.writeText(turn.content);
    ElMessage.success('已复制');
  } catch {
    ElMessage.error('复制失败，请手动选中文字复制');
  }
}

function clearConversation() {
  resetConversation();
  ElMessage.success('对话已清空');
}

/** Honest degraded-source note shown under a turn (never hidden — fool-proof 4位一体). */
function sourceNote(turn: ChatTurn): string | null {
  if (turn.role !== 'ai' || turn.error) return null;
  if (turn.source === 'degraded') return 'AI 分析当前降级（额度或服务暂不可用），以上为系统提示，非分析结果';
  return null;
}

defineExpose({ setFocus, sendMessage, clearConversation });
</script>

<template>
  <div class="acp-panel" :class="{ 'acp-std': isStandard }">
    <div class="acp-header">
      <div class="acp-title">
        <span class="acp-title-dot"></span>
        AI 分析 · {{ pageTitle }}
      </div>
      <el-button :size="isStandard ? 'default' : 'small'" link @click="clearConversation">清空对话</el-button>
    </div>

    <div class="acp-focus-row">
      <span class="acp-focus-label">聚焦:</span>
      <el-radio-group v-model="focusKey" :size="isStandard ? 'default' : 'small'" @change="onFocusChange">
        <el-radio-button :value="ALL_FOCUS">全页</el-radio-button>
        <el-radio-button v-for="ctx in contexts" :key="ctx.key" :value="ctx.key">
          {{ ctx.title }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <div ref="chatContainer" class="acp-body">
      <div v-if="turns.length === 0" class="acp-empty">
        <template v-if="isStandard">
          <div class="acp-empty-text">当前聚焦 {{ focusLabel }}。直接问，或点下面的示例问题：</div>
          <div class="acp-examples">
            <el-button
              v-for="q in EXAMPLE_QUESTIONS"
              :key="q"
              round
              class="acp-example"
              data-testid="acp-example"
              :disabled="isTyping"
              @click="sendMessage(q)"
            >
              {{ q }}
            </el-button>
          </div>
        </template>
        <div v-else class="acp-empty-text">
          当前聚焦 {{ focusLabel }} — 问问我，例如: "用3条bullet解读并给营销建议" / "这个数据说明什么问题"
        </div>
      </div>

      <template v-for="turn in turns" :key="turn.id">
        <ChatBubble :turn="turn" :variant="isStandard ? 'standard' : 'legacy'">
          <template #followups>
            <div
              v-if="turn.role === 'ai' && turn.followUpActions?.length"
              class="acp-followups"
            >
              <el-button
                v-for="action in turn.followUpActions"
                :key="action.question"
                :size="isStandard ? 'default' : 'small'"
                round
                @click="sendMessage(action.question)"
              >
                {{ action.label }}
              </el-button>
            </div>
          </template>
          <template v-if="isStandard" #actions>
            <div class="acp-actions">
              <el-button text class="acp-copy" data-testid="acp-copy" @click="copyTurn(turn)">复制</el-button>
            </div>
          </template>
        </ChatBubble>
        <div v-if="sourceNote(turn)" class="acp-source-note">{{ sourceNote(turn) }}</div>
      </template>

      <div
        v-if="isTyping && isStandard"
        class="acp-skeleton"
        role="status"
        aria-live="polite"
        data-testid="acp-loading-skeleton"
      >
        <span
          v-for="w in SKELETON_BAR_WIDTHS"
          :key="w"
          class="acp-skeleton-bar"
          data-testid="acp-skeleton-bar"
          :style="{ width: `${w}%` }"
        ></span>
        <div class="acp-skeleton-caption">正在分析…</div>
      </div>
      <ChatTypingIndicator v-else-if="isTyping" />
    </div>

    <div class="acp-input">
      <el-input
        v-model="inputText"
        placeholder="输入问题, 回车发送..."
        :size="isStandard ? 'large' : undefined"
        :disabled="isTyping"
        @keyup.enter="sendMessage()"
      />
      <el-button
        type="primary"
        :size="isStandard ? 'large' : undefined"
        :loading="isTyping"
        @click="sendMessage()"
      >发送</el-button>
    </div>
  </div>
</template>

<style scoped>
.acp-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 420px;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  overflow: hidden;
  --el-radio-button-checked-text-color: #fff;
}
.acp-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.acp-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
  color: var(--el-text-color-primary, #303133);
}
.acp-title-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #409eff;
  box-shadow: 0 0 6px #409eff;
  flex-shrink: 0;
}
.acp-focus-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  flex-wrap: wrap;
}
.acp-focus-label {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  flex-shrink: 0;
}
.acp-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  min-height: 160px;
}
.acp-empty {
  text-align: center;
  padding: 40px 16px;
  color: var(--el-text-color-secondary, #909399);
}
.acp-empty-text {
  font-size: 13px;
  line-height: 1.6;
}
.acp-source-note {
  margin: -12px 0 16px 46px;
  font-size: 11px;
  color: #e6a23c;
}
.acp-followups {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.acp-input {
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  display: flex;
  gap: 8px;
}

/* ───────── 标准版式 (.acp-std, 仅工厂租户) ─────────
 * T1 系统字体栈 (⛔ 不写 Noto Serif SC / Playfair Display, 也不改全局 --font-family)
 * T2 正文 16px/1.6 · T3 小字 12-13px (不出现 10-11px) · S4 输入框圆角 8 / 单行 40px
 */
.acp-std {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}
.acp-std .acp-title {
  font-size: 16px;
}
.acp-std .acp-empty {
  text-align: left;
  padding: 16px 0;
}
.acp-std .acp-empty-text {
  font-size: 16px;
  line-height: 1.6;
}
.acp-std .acp-examples {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin-top: 12px;
}
.acp-std .acp-examples :deep(.el-button),
.acp-std .acp-followups :deep(.el-button) {
  margin-left: 0;
  max-width: 100%;
  white-space: normal;
  height: auto;
  min-height: 32px;
  font-size: 14px;
}
.acp-std .acp-source-note {
  margin: 0 0 16px;
  font-size: 13px;
}
.acp-std .acp-followups {
  flex-direction: column;
  align-items: flex-start;
  margin-top: 12px;
}
.acp-std .acp-actions {
  display: flex;
  opacity: 0;
  transition: opacity 0.15s;
}
.acp-std .acp-body :deep(.bubble-user) .acp-actions {
  justify-content: flex-end;
}
/* N2: 悬停 / 键盘聚焦时出现「复制」; 触屏没有悬停, 一直显示 */
.acp-std .acp-body :deep(.chat-bubble:hover) .acp-actions,
.acp-std .acp-body :deep(.chat-bubble:focus-within) .acp-actions {
  opacity: 1;
}
@media (hover: none) {
  .acp-std .acp-actions { opacity: 1; }
}
.acp-std .acp-copy {
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
}
/* W1: 骨架屏 3 条, 1.5s 闪烁 */
.acp-std .acp-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 4px 0;
  margin-bottom: 16px;
}
.acp-std .acp-skeleton-bar {
  display: block;
  height: 14px;
  border-radius: 5px;
  background: linear-gradient(
    90deg,
    var(--el-fill-color, #f0f2f5) 25%,
    var(--el-fill-color-darker, #e6e8eb) 37%,
    var(--el-fill-color, #f0f2f5) 63%
  );
  background-size: 400% 100%;
  animation: acp-skeleton-shimmer 1.5s ease-in-out infinite;
}
.acp-std .acp-skeleton-caption {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
}
@keyframes acp-skeleton-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}
@media (prefers-reduced-motion: reduce) {
  .acp-std .acp-skeleton-bar { animation: none; }
}
.acp-std .acp-input :deep(.el-input__wrapper) {
  min-height: 40px;
  padding: 1px 12px;
  border-radius: 8px;
}
.acp-std .acp-input :deep(.el-input__inner) {
  font-size: 16px;
}

:global(:root[data-theme='dark']) .acp-panel {
  background: #1d1e1f;
  border-color: #4c4d4f;
  --el-bg-color: #1d1e1f;
  --el-fill-color-blank: #1d1e1f;
  --el-text-color-primary: #f2f3f5;
  --el-text-color-regular: #dcdfe6;
  --el-text-color-secondary: #b1b3b8;
  --el-border-color: #4c4d4f;
  --el-border-color-lighter: #363637;
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-radio-button__inner) {
  color: #dcdfe6;
  background: #262727;
  border-color: #4c4d4f;
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  color: #fff;
  background: var(--el-color-primary, #409eff);
  border-color: var(--el-color-primary, #409eff);
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-radio-button.is-active .el-radio-button__inner) {
  color: #fff;
  background: var(--el-color-primary, #409eff);
  border-color: var(--el-color-primary, #409eff);
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-input__wrapper) {
  background: #262727;
  box-shadow: 0 0 0 1px #4c4d4f inset;
}
:global(:root[data-theme='dark']) .acp-panel :deep(.el-input__inner) {
  color: #f2f3f5;
}
:global(:root[data-theme='light']) .acp-panel { background: #fff; }
</style>
