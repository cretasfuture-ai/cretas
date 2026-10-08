/**
 * AI 对话式建单 —— 抽屉里的对话状态机。
 *
 * 2026-07-28 迁移: `/api/mobile/ai/chat` (裸 LLM，路径无 factoryId，无配额计数/遥测，
 * prompt 由浏览器发) → `/api/mobile/{factoryId}/form-assistant/parse`。
 *
 * 两个契约变化：
 * 1. 不再从自由文本里正则抠 ```json {"action":"FILL_FORM"} ``` —— 后端直接返回 `fieldValues`。
 * 2. prompt 不再由前端发，只传 entityType；后端按 entityType 查
 *    `resources/ai/form-prompts/factory/{ENTITY}.md`。
 *
 * `/parse` 是**无状态单次解析**（后端 DTO 收 `context` 但 service 不消费），所以多轮追问
 * 靠这里做两件事：把历史折进 userInput，并在本地累积已收集字段。
 */
import { ref } from 'vue';
import { parseFormInput, type FormAssistantField } from '@/api/formAssistant';
import { useFactoryId } from '@/composables/useFactoryId';
import type { AiEntryConfig, ChatMessage, FieldDef } from '@/components/ai-entry/types';
import type { TableRow } from '@/types/api';
import { normalizeAiOrderDates } from '@/utils/aiOrderNormalization';

function toFormFields(fields: FieldDef[]): FormAssistantField[] {
  return fields.map((field) => ({
    name: field.key,
    title: field.label,
    type: field.type ?? 'string',
    required: Boolean(field.required),
  }));
}

function isFilled(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'string') return value.trim() !== '';
  if (Array.isArray(value)) return value.length > 0;
  return true;
}

/**
 * 新一轮解析出的值覆盖旧值（用户改口 "不是500，是300" 必须以最新为准），
 * 但模型这一轮没提到的字段保留 —— 它每轮只被问了缺什么，不保证复述全部。
 */
function mergeValues(base: TableRow, incoming: Record<string, unknown> | null | undefined): TableRow {
  const merged: TableRow = { ...base };
  if (!incoming) return merged;
  for (const [key, value] of Object.entries(incoming)) {
    if (isFilled(value)) merged[key] = value;
  }
  return merged;
}

/**
 * 缺哪些必填项由**本地字段定义**判定，不采信模型自报的 missingRequiredFields ——
 * 模型漏报会让预览卡片带着空必填项弹出来，用户点「填入表单」才发现填不了。
 */
function missingRequiredFields(config: AiEntryConfig, values: TableRow): FieldDef[] {
  return config.fields.filter((field) => field.required && !isFilled(values[field.key]));
}

/** 把历史对话折进单次 userInput —— `/parse` 不记上下文。 */
function buildUserInput(history: ChatMessage[], latest: string): string {
  if (history.length === 0) return latest;
  const transcript = history
    .map((msg) => `${msg.role === 'user' ? '用户' : '助手'}：${msg.content}`)
    .join('\n');
  return [
    '以下是本次建单对话的记录，请结合全部内容解析（相互矛盾时以最后一条用户消息为准）：',
    transcript,
    `用户：${latest}`,
  ].join('\n');
}

/**
 * 请求抛错时给业务用户看的话 (AI 对话标准 W2): 超时说超时; 英文 / HTTP 码原文不露;
 * 后端给的中文业务原因 (如「权限不足」) 保留。「没有保存任何数据」由抽屉在失败消息下统一补。
 */
function failureText(error: unknown): string {
  const e = error as { message?: string; code?: string } | null;
  const detail = (e?.message ?? '').trim();
  if (e?.code === 'ECONNABORTED' || /timeout|超时/i.test(detail)) {
    return 'AI 响应超时，请重试或手动填写表单。';
  }
  const chineseReason = /[一-龥]/.test(detail) && !/[A-Za-z]{4,}/.test(detail) && !/\b[45]\d\d\b/.test(detail);
  return chineseReason ? `AI 解析失败：${detail}` : 'AI 解析失败，请重试或手动填写表单。';
}

export function useAiChat(config: AiEntryConfig) {
  const factoryId = useFactoryId();
  const messages = ref<ChatMessage[]>([]);
  const loading = ref(false);
  const previewParams = ref<TableRow | null>(null);

  /** 跨轮累积的字段值；预览卡片基于它，而不是单轮返回。 */
  let collected: TableRow = {};

  function say(content: string) {
    messages.value.push({ role: 'assistant', content });
  }

  /** 解析失败 / 超时: 同样是一条助手消息, 但带 failed 标记, 抽屉据此补「没有保存任何数据」和「重试」。 */
  function fail(content: string) {
    messages.value.push({ role: 'assistant', content, failed: true });
  }

  async function sendMessage(text: string) {
    const trimmed = text.trim();
    if (!trimmed || loading.value) return;

    if (!factoryId.value) {
      messages.value.push({ role: 'user', content: trimmed });
      say('当前账号未绑定工厂，无法使用 AI 建单。请用工厂账号登录后重试。');
      return;
    }

    const history = [...messages.value];
    messages.value.push({ role: 'user', content: trimmed });
    loading.value = true;

    try {
      const response = await parseFormInput(factoryId.value, {
        userInput: buildUserInput(history, trimmed),
        entityType: config.entityType,
        formFields: toFormFields(config.fields),
      });

      const result = response.data;
      // 后端在 AI 不可用/解析失败时返回 success=false + message（HTTP 仍 200）。
      // 按「禁止降级处理」原样显示，不要伪装成一次正常回答。
      if (!response.success || !result || result.success === false) {
        // 🔴 2026-08-13: 兜底链原本第二项是 `response.message` —— 那是 ApiResponse
        // 【包装层】的文案, 这个接口恒为「操作成功」。于是一次解析失败在界面上
        // 显示成「操作成功」, 然后什么也没发生: 用户看到"成功"却拿不到结果,
        // 排查的人也不会想到去看一个写着成功的气泡。
        // 包装层文案描述的是 HTTP 调用本身, 与业务成败无关, 不能进这条链。
        fail(result?.message || 'AI 解析失败，请重试或手动填写表单。');
        return;
      }

      collected = mergeValues(collected, result.fieldValues);
      const missing = missingRequiredFields(config, collected);

      if (missing.length === 0) {
        const userContext = messages.value
          .filter((message) => message.role === 'user')
          .map((message) => message.content)
          .join('\n');
        previewParams.value = normalizeAiOrderDates(config.entityType, collected, userContext);
        say('已整理好下面这些信息，请核对预览卡片，确认无误后点「填入表单」。');
      } else {
        previewParams.value = null;
        const missingLabels = missing.map((field) => field.label).join('、');
        const followUp = result.followUpQuestion?.trim() || result.suggestedQuestions?.[0]?.trim();
        say(followUp
          ? `${followUp}\n\n（还缺：${missingLabels}）`
          : `还需要补充：${missingLabels}`);
      }
    } catch (error: unknown) {
      fail(failureText(error));
    } finally {
      loading.value = false;
    }
  }

  /**
   * 重试: 撤掉失败的那条助手回复和触发它的用户消息, 再把同一句话发一遍
   * (历史里不留失败的那一轮, 不重复出现用户气泡)。只重新解析, 不写任何东西。
   */
  async function retry() {
    if (loading.value) return;
    const lastReply = messages.value[messages.value.length - 1];
    const lastUser = messages.value[messages.value.length - 2];
    if (!lastReply?.failed || lastUser?.role !== 'user') return;
    messages.value.splice(messages.value.length - 2, 2);
    await sendMessage(lastUser.content);
  }

  function continueEditing() {
    // 只收起预览卡片，已收集的字段留着 —— 用户接着说的是补充/更正，不是从头再来。
    previewParams.value = null;
  }

  function confirmParams(): TableRow {
    const params = previewParams.value || {};
    return { ...params };
  }

  function reset() {
    messages.value = [];
    loading.value = false;
    previewParams.value = null;
    collected = {};
  }

  return {
    messages,
    loading,
    previewParams,
    sendMessage,
    retry,
    continueEditing,
    confirmParams,
    reset,
  };
}
