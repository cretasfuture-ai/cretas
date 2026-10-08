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
            <span>先列出改动，点「应用配置」才写入当前草稿；运行版本和在途审批不受影响。</span>
            <div ref="messageListRef" class="message-list" data-testid="ai-chat-thread">
              <div v-for="(message, index) in messages" :key="index" :class="['message', message.role]">
                <div class="message-content" :data-testid="message.role === 'user' ? 'ai-chat-user-bubble' : 'ai-chat-assistant-body'">{{ message.content }}</div>
                <!-- 预览 → 用户确认 → 执行: 逐项改动一条不藏, 点「应用配置」才调 applySpec -->
                <div v-if="message.proposal" class="change-preview" data-testid="ai-diff-preview">
                  <ul class="change-list">
                    <li
                      v-for="(row, rowIndex) in message.proposal.rows"
                      :key="rowIndex"
                      :class="['change-row', row.kind]"
                      data-testid="ai-diff-item"
                    >
                      <span class="change-kind">{{ CHANGE_KIND_LABELS[row.kind] }}</span>
                      <span class="change-text">{{ row.text }}</span>
                    </li>
                  </ul>
                  <div v-if="message.proposal.state === 'pending' || message.proposal.state === 'applying'" class="change-actions">
                    <el-button
                      type="primary"
                      data-testid="ai-diff-apply"
                      :loading="message.proposal.state === 'applying'"
                      :disabled="disabled || message.proposal.state === 'applying'"
                      @click="applyProposal(message.proposal)"
                    >
                      应用配置
                    </el-button>
                    <el-button
                      data-testid="ai-diff-cancel"
                      :disabled="message.proposal.state === 'applying'"
                      @click="cancelProposal(message.proposal)"
                    >
                      取消
                    </el-button>
                  </div>
                  <span v-else class="change-state" data-testid="ai-diff-state">{{ PROPOSAL_STATE_LABELS[message.proposal.state] }}</span>
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
import { computed, nextTick, onBeforeUnmount, ref, toRaw, watch } from 'vue'
import request from '@/api/request'
import {
  AI_TIMEOUT_MESSAGE,
  AI_FAILURE_MESSAGE,
  SKELETON_BAR_WIDTHS,
  isNearBottom,
  startAiRequestTimer,
  type AiRequestTimer,
} from '@/utils/aiConfigAssistant'
import type { ApprovalAiPreview, ApprovalWorkflowChangeRow } from '../lib/approvalWorkflowAi'

interface ChatDiff {
  type: string
  params?: Record<string, unknown>
}

interface AIChatResponse {
  reply?: string
  diffs?: ChatDiff[]
}

type ProposalState = 'pending' | 'applying' | 'applied' | 'cancelled' | 'rejected' | 'expired'

interface Proposal {
  spec: unknown
  rows: ApprovalWorkflowChangeRow[]
  state: ProposalState
}

interface Message {
  role: 'user' | 'assistant'
  content: string
  proposal?: Proposal
}

const CHANGE_KIND_LABELS: Record<ApprovalWorkflowChangeRow['kind'], string> = {
  add: '新增',
  remove: '删除',
  change: '修改',
}
const PROPOSAL_STATE_LABELS: Record<ProposalState, string> = {
  pending: '',
  applying: '',
  applied: '已应用到草稿',
  cancelled: '已取消，草稿没有改动',
  rejected: '未通过画布校验，草稿没有改动',
  expired: '已有更新的方案，这一份不再可用',
}

const props = withDefaults(defineProps<{
  factoryId: string
  context: Record<string, unknown>
  contextLabel?: string
  disabled?: boolean
  /** 只校验 + 算逐项改动, 不动画布 */
  previewSpec: (spec: unknown) => ApprovalAiPreview
  /** 用户点「应用配置」后才调用 */
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
  // 新问题一发, 旧的待确认方案就作废: 免得用户点到一份过期方案
  messages.value.forEach((message) => {
    if (message.proposal?.state === 'pending') message.proposal.state = 'expired'
  })
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
    // 回复已到手: 30 秒超时只管「等 AI」, 不管后面用户确认 / 套用草稿
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
    const preview = props.previewSpec(spec)
    if (preview.ok === false) {
      const rejected = `这次修改未通过画布校验，草稿保持不变：${preview.message}`
      pushMessage({ role: 'assistant', content: rejected })
      lastStatus.value = rejected
      return
    }
    if (!preview.rows.length) {
      const same = 'AI 给出的方案和当前草稿一样，没有需要应用的改动。'
      pushMessage({ role: 'assistant', content: same })
      lastStatus.value = same
      return
    }
    // 不拼 AI 的 reply: 实测后端回的是「当前审批草稿已更新」—— 按旧的自动套用写的, 和「还没写入」矛盾。
    // 改了什么由下面逐项列表说全, 不靠这句话。
    pushMessage({
      role: 'assistant',
      content: `下面是全部 ${preview.rows.length} 项改动，确认后点「应用配置」才会写入草稿：`,
      proposal: { spec, rows: preview.rows, state: 'pending' },
    })
    lastStatus.value = `有 ${preview.rows.length} 项改动待确认`
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

async function applyProposal(proposal: Proposal) {
  if (proposal.state !== 'pending' || props.disabled) return
  // 预览之后用户可能手动改过画布: 应用前重新算一次, 和看到的不一样就刷新列表、让用户再确认
  // 方案存在响应式的消息记录里; 交出去的必须是 AI 给的原对象, 不是代理
  const proposedSpec = toRaw(proposal.spec)
  const recheck = props.previewSpec(proposedSpec)
  if (recheck.ok === false) {
    proposal.state = 'rejected'
    const rejected = `这次修改未通过画布校验，草稿保持不变：${recheck.message}`
    pushMessage({ role: 'assistant', content: rejected })
    lastStatus.value = rejected
    return
  }
  if (JSON.stringify(recheck.rows) !== JSON.stringify(proposal.rows)) {
    proposal.rows = recheck.rows
    const refreshed = '草稿在预览之后有变动，上面的改动已按当前草稿重新列出，请再确认一次。'
    pushMessage({ role: 'assistant', content: refreshed })
    lastStatus.value = refreshed
    return
  }
  proposal.state = 'applying'
  const applied = await props.applySpec(proposedSpec)
  if (!applied) {
    proposal.state = 'rejected'
    const rejected = '这次修改未通过画布校验，草稿保持不变。'
    pushMessage({ role: 'assistant', content: rejected })
    lastStatus.value = rejected
    return
  }
  proposal.state = 'applied'
  const success = '已完成，当前草稿已更新，可用“撤销”恢复。'
  pushMessage({ role: 'assistant', content: success })
  lastStatus.value = success
}

function cancelProposal(proposal: Proposal) {
  if (proposal.state !== 'pending') return
  proposal.state = 'cancelled'
  const cancelled = '已取消，草稿没有任何改动。'
  pushMessage({ role: 'assistant', content: cancelled })
  lastStatus.value = cancelled
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

/* 逐项改动: 全部列出, 不折叠、不截断 (长了靠对话记录本身滚动) */
.change-preview { margin-top: 8px; }
.change-list { margin: 0; padding: 0; list-style: none; }
.change-row {
  display: flex;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid #edf1f6;
  font-size: 14px;
  line-height: 1.5;
}
.change-kind { flex: none; font-weight: 600; }
.change-row.add .change-kind { color: #2f7d32; }
.change-row.remove .change-kind { color: #c62828; }
.change-row.change .change-kind { color: #a15c00; }
.change-text { min-width: 0; word-break: break-word; }
.change-actions { display: flex; gap: 8px; margin-top: 10px; }
.change-state { display: block; margin-top: 8px; color: #5d6879; font-size: 13px; }

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
