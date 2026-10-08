<!-- AIChatPanel.vue — Right panel: 3 AI agent modes -->
<!-- Round 4 Fix P1-20: explicit mode buttons with data-mode attrs for UI tests -->
<!-- AI 对话标准 (配置助手): 助手全宽无头像 / 用户浅色气泡 / 骨架屏 / 30 秒超时 / 中文模式名与变更类型 / 条件滚动 -->
<template>
  <div class="ai-chat-panel" data-component="ai-chat-panel" data-testid="canvas-ai-chat-panel">
    <div class="mode-selector">
      <button
        v-for="m in modes"
        :key="m.value"
        type="button"
        :data-mode="m.value"
        class="mode-btn"
        :class="{ active: mode === m.value }"
        @click="mode = m.value"
      >
        {{ m.label }}
      </button>
    </div>

    <div class="mode-description" data-testid="canvas-ai-mode-description">
      <template v-if="mode === 'autopilot'">自动配置：描述需求，AI 先生成候选变更，确认后才应用</template>
      <template v-else-if="mode === 'plan'">先出方案：AI 生成变更方案，逐项审核后应用</template>
      <template v-else>影响分析：手动操作时，AI 实时提示关联影响</template>
    </div>

    <div class="chat-messages" data-testid="ai-chat-thread" ref="messagesRef">
      <div v-for="(msg, i) in messages" :key="i" :class="['message', msg.role]">
        <div class="message-content" :data-testid="msg.role === 'user' ? 'ai-chat-user-bubble' : 'ai-chat-assistant-body'">{{ msg.content }}</div>
        <div v-if="msg.diffPreview" class="diff-preview" data-testid="ai-diff-preview">
          <div v-for="(diff, j) in msg.diffPreview" :key="j" class="diff-item" data-testid="ai-diff-item">
            <el-tag :type="(diff.type ?? '').includes('TOGGLE') ? 'warning' : 'info'" class="diff-type" data-testid="ai-diff-type">
              {{ diffTypeLabel(diff.type) }}
            </el-tag>
            <span class="diff-desc">{{ diff.description }}</span>
            <el-button class="diff-apply" type="primary" link data-testid="ai-diff-apply" @click="applyDiff(diff)">应用</el-button>
          </div>
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
          <span class="skeleton-caption">正在生成配置建议…</span>
        </div>
      </div>
    </div>

    <div class="chat-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        placeholder="描述你的配置需求..."
        @keydown.enter.ctrl="send"
      />
      <el-button type="primary" :loading="loading" data-testid="ai-chat-send" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onBeforeUnmount } from 'vue'
import request from '@/api/request'
import type { AIAgentMode, AIMessage, ConfigDiff } from '@/types/canvas'
import {
  AI_MODE_LABELS,
  AI_TIMEOUT_MESSAGE,
  AI_FAILURE_MESSAGE,
  SKELETON_BAR_WIDTHS,
  diffTypeLabel,
  isNearBottom,
  startAiRequestTimer,
  type AiRequestTimer,
} from '@/utils/aiConfigAssistant'

const props = defineProps<{
  factoryId: string
  selectedModule?: string
}>()

const emit = defineEmits<{
  applyDiff: [diffs: ConfigDiff[]]
}>()

// Round 4 Fix P1-20: Default to 'plan' mode — safer for new users (review before apply).
// Autopilot mode is now explicit via data-mode="autopilot" button.
const mode = ref<AIAgentMode>('plan')
// V3: 模式名给用户看中文 (data-mode 仍是英文枚举, 是接口参数不是文案)
const modes: { label: string; value: AIAgentMode }[] = [
  { label: AI_MODE_LABELS.autopilot, value: 'autopilot' },
  { label: AI_MODE_LABELS.plan, value: 'plan' },
  { label: AI_MODE_LABELS.action, value: 'action' },
]

const messages = ref<AIMessage[]>([
  { role: 'system', content: '欢迎使用 Canvas AI 助手。选择模式后开始配置。', timestamp: Date.now() },
])
const input = ref('')
const loading = ref(false)
const messagesRef = ref<HTMLElement>()
let activeTimer: AiRequestTimer | null = null

onBeforeUnmount(() => activeTimer?.cancel())

/** N1: 先量「本来是不是在底部附近」, 再追加内容; 用户自己发消息 (force) 一定滚 */
function pushMessage(msg: AIMessage, force = false) {
  const follow = force || isNearBottom(messagesRef.value)
  messages.value.push(msg)
  if (follow) scrollToBottom()
}

async function scrollToBottom() {
  await nextTick()
  messagesRef.value?.scrollTo({ top: messagesRef.value.scrollHeight, behavior: 'smooth' })
}

async function send() {
  if (!input.value.trim() || loading.value) return
  const userMsg = input.value.trim()
  input.value = ''
  pushMessage({ role: 'user', content: userMsg, timestamp: Date.now() }, true)

  loading.value = true
  const timer = startAiRequestTimer()
  activeTimer = timer
  try {
    const res = await request.post(`/${props.factoryId}/config/v2/ai/chat`, {
      message: userMsg,
      mode: mode.value,
      moduleCode: props.selectedModule,
    }, { signal: timer.signal })
    const data = res.data
    pushMessage({
      role: 'assistant',
      content: data.reply,
      timestamp: Date.now(),
      diffPreview: data.diffs?.map((d: any): ConfigDiff => ({
        type: d.type as ConfigDiff['type'], path: String(d.tool ?? ''), before: null, after: d.params,
        description: d.description,
      })),
    })
  } catch {
    // W2 / H5: 超时与失败都说人话, 并明说配置没有任何改动 (这一步根本没走到「应用」)
    pushMessage({
      role: 'assistant',
      content: timer.timedOut ? AI_TIMEOUT_MESSAGE : AI_FAILURE_MESSAGE,
      timestamp: Date.now(),
    })
  } finally {
    timer.done()
    if (activeTimer === timer) activeTimer = null
  }
  loading.value = false
}

function applyDiff(diff: ConfigDiff) {
  emit('applyDiff', [diff])
}
</script>

<style scoped>
/* T1: 对话区自己声明系统字体栈 (不改全局 --font-family) */
.ai-chat-panel {
  display: flex; flex-direction: column; height: 100%; padding: 12px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}
.mode-selector { margin-bottom: 8px; display: flex; gap: 4px; }
.mode-btn {
  flex: 1; min-height: 32px; padding: 4px 8px; font-size: 13px; cursor: pointer;
  border: 1px solid var(--el-border-color); background: var(--el-fill-color-light);
  border-radius: 8px; color: var(--el-text-color-regular);
}
.mode-btn.active {
  background: var(--el-color-primary); color: #fff; border-color: var(--el-color-primary);
}
.mode-btn:hover:not(.active) { background: var(--el-fill-color); }
.mode-description { font-size: 13px; line-height: 1.6; color: var(--el-text-color-secondary); margin-bottom: 12px; }
.chat-messages { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }

/* L1: 助手 / 欢迎语全宽, 无头像、无气泡 (无背景 / 边框 / 阴影) */
.message { font-size: 16px; line-height: 1.6; min-width: 0; word-break: break-word; }
.message.assistant,
.message.system { align-self: stretch; width: 100%; }
.message.system { color: var(--el-text-color-regular); }
/* L2: 只有用户消息是浅色气泡, 靠右, 最宽 min(78%, 320px), 四角圆角 12, 内边距 10px 12px */
.message.user {
  align-self: flex-end; max-width: min(78%, 320px);
  padding: 10px 12px; border-radius: 12px;
  background: var(--el-color-primary-light-9);
}
.message-content { white-space: pre-wrap; }

.diff-preview { margin-top: 8px; display: flex; flex-direction: column; gap: 8px; }
.diff-item { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 13px; line-height: 1.6; }
.diff-desc { flex: 1; min-width: 0; }
.diff-apply { min-height: 32px; }

/* W1: 骨架屏 —— 3 条, 高 14, 圆角 5, 1.5s 闪烁 */
.loading-skeleton { display: flex; flex-direction: column; gap: 8px; padding: 4px 0; }
.skeleton-bar {
  display: block; height: 14px; border-radius: 5px;
  background: linear-gradient(90deg, var(--el-fill-color, #f0f2f5) 25%, var(--el-fill-color-darker, #e6e8eb) 37%, var(--el-fill-color, #f0f2f5) 63%);
  background-size: 400% 100%;
  animation: canvas-ai-skeleton-shimmer 1.5s ease-in-out infinite;
}
.skeleton-caption { margin-top: 4px; font-size: 13px; color: var(--el-text-color-secondary); }
@keyframes canvas-ai-skeleton-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}
@media (prefers-reduced-motion: reduce) {
  .skeleton-bar { animation: none; }
}

/* S4: 输入框圆角 8 */
.chat-input { display: flex; gap: 8px; margin-top: 12px; }
.chat-input :deep(.el-textarea__inner) { border-radius: 8px; font-size: 16px; line-height: 1.6; }
</style>
