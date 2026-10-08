<template>
  <div class="approval-ai-composer" data-testid="approval-workflow-ai-composer">
    <el-input
      v-model="input"
      type="textarea"
      :autosize="{ minRows: 1, maxRows: 4 }"
      resize="none"
      :disabled="disabled || loading"
      :placeholder="placeholder"
      name="approval-workflow-ai-request"
      autocomplete="off"
      aria-label="描述要对当前审批草稿进行的修改"
      @keydown.enter.exact.prevent="send"
      @keydown.ctrl.enter.prevent="send"
    />

    <div class="composer-footer">
      <div class="composer-tools">
        <el-popover v-model:visible="historyVisible" placement="top-start" :width="520" trigger="click">
          <template #reference>
            <el-button text>AI 对话</el-button>
          </template>
          <div class="history-panel" data-testid="approval-ai-history">
            <strong>审批 Workflow AI</strong>
            <span>只修改当前草稿；运行版本和在途审批不受影响。</span>
            <div ref="messageListRef" class="message-list" data-testid="ai-chat-thread">
              <div v-for="(message, index) in messages" :key="index" :class="['message', message.role]">
                <div class="message-content" :data-testid="message.role === 'user' ? 'ai-chat-user-bubble' : 'ai-chat-assistant-body'">{{ message.content }}</div>
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
                  <span class="skeleton-caption">正在生成审批草稿修改…</span>
                </div>
              </div>
            </div>
          </div>
        </el-popover>

        <el-dropdown trigger="click" :disabled="disabled || loading" @command="runQuickPrompt">
          <el-button text>快捷问题</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="prompt in quickPrompts" :key="prompt" :command="prompt">
                {{ prompt }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <span v-if="lastStatus" class="last-status" role="status">{{ lastStatus }}</span>
      </div>

      <el-button
        type="primary"
        :loading="loading"
        :disabled="disabled || !input.trim()"
        @click="send"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import request from '@/api/request'
import {
  AI_TIMEOUT_MESSAGE,
  AI_FAILURE_MESSAGE,
  SKELETON_BAR_WIDTHS,
  isNearBottom,
  startAiRequestTimer,
  type AiRequestTimer,
} from '@/utils/aiConfigAssistant'

interface ChatDiff {
  type: string
  params?: Record<string, unknown>
}

interface AIChatResponse {
  reply?: string
  diffs?: ChatDiff[]
}

interface Message {
  role: 'user' | 'assistant'
  content: string
}

const props = withDefaults(defineProps<{
  factoryId: string
  context: Record<string, unknown>
  contextLabel?: string
  disabled?: boolean
  applySpec: (spec: unknown) => Promise<boolean>
}>(), {
  contextLabel: '当前范围：整个审批画布',
  disabled: false,
})

const quickPrompts = [
  '把当前审批节点改为采购主管审批',
  '金额超过 5 万元时由工厂总管理员审批',
  '审批超过 12 小时自动转派并通知仓储主管',
]
const input = ref('')
const loading = ref(false)
const historyVisible = ref(false)
const lastStatus = ref('')
const messages = ref<Message[]>([])
const messageListRef = ref<HTMLElement>()
let requestGeneration = 0
let activeTimer: AiRequestTimer | null = null

onBeforeUnmount(() => activeTimer?.cancel())

/** N1: 先量「本来是不是在底部附近」, 再追加内容; 用户自己发消息 (force) 一定滚 */
function pushMessage(message: Message, force = false) {
  const follow = force || isNearBottom(messageListRef.value)
  messages.value.push(message)
  if (follow) {
    void nextTick().then(() => {
      messageListRef.value?.scrollTo?.({ top: messageListRef.value.scrollHeight, behavior: 'smooth' })
    })
  }
}

const placeholder = computed(() => `输入想要的修改（${props.contextLabel.replace(/^当前范围：/, '')}）`)

watch(() => props.context, () => {
  requestGeneration += 1
  // 上下文换了, 在途请求的结果会被丢弃: 一并取消请求, 免得骨架屏 / 发送按钮一直转
  activeTimer?.cancel()
  activeTimer = null
  loading.value = false
}, { deep: false })

async function send() {
  const text = input.value.trim()
  if (!text || props.disabled || loading.value) return
  const generation = ++requestGeneration
  input.value = ''
  lastStatus.value = ''
  historyVisible.value = true // 让骨架屏 / 回复看得见
  pushMessage({ role: 'user', content: text }, true)
  loading.value = true
  const timer = startAiRequestTimer()
  activeTimer = timer
  try {
    const response = await request.post<AIChatResponse>(
      `/${props.factoryId}/config/v2/ai/chat`,
      {
        message: text,
        mode: 'plan',
        moduleCode: 'approval_workflow_config',
        params: { context: props.context },
      },
      { signal: timer.signal },
    )
    // 回复已到手: 30 秒超时只管「等 AI」, 不管后面本地套用草稿这一步
    timer.done()
    if (generation !== requestGeneration) return
    const data = response.data ?? {}
    const workflowDiff = data.diffs?.find((diff) => (
      diff.type === 'APPROVAL_WORKFLOW_SPEC'
      && diff.params
      && typeof diff.params === 'object'
    ))
    const spec = workflowDiff?.params?.spec
    if (!spec) {
      const guidance = data.reply || 'AI 暂时无法完成这项修改，请换一种说法重试。'
      pushMessage({ role: 'assistant', content: guidance })
      lastStatus.value = guidance
      return
    }
    const applied = await props.applySpec(spec)
    if (!applied) {
      const rejected = '这次修改未通过画布校验，草稿保持不变。'
      pushMessage({ role: 'assistant', content: rejected })
      lastStatus.value = rejected
      return
    }
    const success = '已完成，当前草稿已更新，可用“撤销”恢复。'
    pushMessage({ role: 'assistant', content: success })
    lastStatus.value = success
  } catch (error) {
    if (generation !== requestGeneration) return
    console.error('[ApprovalWorkflowAIComposer] request failed', error)
    // W2 / H5: 超时与失败都说人话, 并明说配置 (审批草稿) 没有任何改动 —— applySpec 根本没被调用
    const message = timer.timedOut ? AI_TIMEOUT_MESSAGE : AI_FAILURE_MESSAGE
    pushMessage({ role: 'assistant', content: message })
    lastStatus.value = message
  } finally {
    timer.done()
    if (activeTimer === timer) activeTimer = null
    if (generation === requestGeneration) {
      loading.value = false
      await nextTick()
    }
  }
}

async function runQuickPrompt(prompt: string) {
  input.value = prompt
  await send()
}
</script>

<style scoped>
.approval-ai-composer {
  width: 100%;
  padding: 10px 10px 8px;
  border: 1px solid #d9e5f2;
  border-radius: 8px;
  /* T1: 对话区自己声明系统字体栈 (不改全局 --font-family) */
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  background: #fff;
  box-shadow: 0 6px 20px rgb(31 62 92 / 16%);
}

.approval-ai-composer:focus-within {
  border-color: #409eff;
}

.composer-footer,
.composer-tools {
  display: flex;
  align-items: center;
}

.composer-footer {
  justify-content: space-between;
  gap: 10px;
  margin-top: 4px;
}

.composer-tools {
  min-width: 0;
  gap: 2px;
}

.last-status {
  overflow: hidden;
  margin-left: 6px;
  color: #5d6879;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}

.history-panel > strong {
  font-size: 16px;
}

.history-panel > span {
  color: #7a8599;
  font-size: 13px;
}

/* S4: 输入框 16px 字, 单行 40px */
.approval-ai-composer :deep(.el-textarea__inner) {
  min-height: 40px !important;
  font-size: 16px;
  line-height: 24px;
}

.message-list {
  display: flex;
  max-height: 320px;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  margin-top: 10px;
}

/* T2: 正文 16px / 行高 1.6 */
.message {
  min-width: 0;
  font-size: 16px;
  line-height: 1.6;
  word-break: break-word;
}

/* L1: 助手回答全宽, 无头像、无气泡 (无背景 / 边框 / 阴影) */
.message.assistant {
  align-self: stretch;
  width: 100%;
  color: #1a2332;
}

/* L2: 只有用户消息是浅色气泡, 靠右, 最宽 min(78%, 320px), 四角圆角 12, 内边距 10px 12px */
.message.user {
  align-self: flex-end;
  max-width: min(78%, 320px);
  padding: 10px 12px;
  border-radius: 12px;
  background: #eef6ff;
}

/* W1: 骨架屏 —— 3 条, 高 14, 圆角 5, 1.5s 闪烁 */
.loading-skeleton { display: flex; flex-direction: column; gap: 8px; padding: 4px 0; }
.skeleton-bar {
  display: block; height: 14px; border-radius: 5px;
  background: linear-gradient(90deg, var(--el-fill-color, #f0f2f5) 25%, var(--el-fill-color-darker, #e6e8eb) 37%, var(--el-fill-color, #f0f2f5) 63%);
  background-size: 400% 100%;
  animation: approval-ai-skeleton-shimmer 1.5s ease-in-out infinite;
}
.skeleton-caption { margin-top: 4px; font-size: 13px; color: var(--el-text-color-secondary, #909399); }
@keyframes approval-ai-skeleton-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}
@media (prefers-reduced-motion: reduce) {
  .skeleton-bar { animation: none; }
}
</style>
