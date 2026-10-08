/**
 * 配置助手面板 (canvas AIChatPanel / WorkProcessAIChatPanel x2 / ApprovalWorkflowAIComposer) 共用的小工具。
 * 对应 docs AI 对话界面标准: W1 骨架屏、W2 30 秒超时、N1 条件滚动、V3 不给用户看英文类型码。
 *
 * 这些助手会「产出配置改动」, 所以超时 / 失败文案必须说明配置没有任何改动 (H5 的 web-admin 版)。
 * 本文件只管呈现与请求超时, 不碰「应用 / 确认」那一步的触发条件与参数。
 */

/** W2: 客户端超时 30 秒 */
export const AI_REQUEST_TIMEOUT_MS = 30_000;

/** W1: 骨架屏 3 条, 宽度 92% / 78% / 62% */
export const SKELETON_BAR_WIDTHS = [92, 78, 62] as const;

/** N1: 离底部不超过这个距离才自动滚到底 */
export const NEAR_BOTTOM_PX = 120;

/** W2 / H5: 超时 —— 说人话, 并说明配置没有任何改动 */
export const AI_TIMEOUT_MESSAGE = 'AI 响应超过 30 秒，已停止等待。配置没有任何改动，请稍后重试。';

/** W2 / H5: 失败兜底 */
export const AI_FAILURE_MESSAGE = 'AI 服务暂时没有响应。配置没有任何改动，请稍后重试。';

export interface AiRequestTimer {
  /** 传给 axios 的 signal */
  readonly signal: AbortSignal;
  /** 是不是我们自己的 30 秒定时器把它掐掉的 (不要靠错误形状判断) */
  readonly timedOut: boolean;
  /** 请求结束 (无论成败) 都要调, 否则定时器会晚到 30 秒后去 abort 一个已经完成的请求 */
  done: () => void;
  /** 面板切换上下文时主动取消 (不算超时) */
  cancel: () => void;
}

export function startAiRequestTimer(ms: number = AI_REQUEST_TIMEOUT_MS): AiRequestTimer {
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, ms);
  return {
    signal: controller.signal,
    get timedOut() { return timedOut; },
    done: () => clearTimeout(timer),
    cancel: () => {
      clearTimeout(timer);
      controller.abort();
    },
  };
}

/** 失败文案: 后端给的中文业务原因保留 (含汉字才算), 英文 / 状态码一律不上屏; 末尾统一补「配置没有任何改动」。 */
export function aiFailureText(backendMessage?: string | null): string {
  const reason = (backendMessage ?? '').trim();
  if (reason && /[一-鿿]/.test(reason) && !/\b[45]\d{2}\b/.test(reason)) {
    const sentence = /[。！？.!?]$/.test(reason) ? reason : `${reason}。`;
    return `${sentence}配置没有任何改动，请稍后重试。`;
  }
  return AI_FAILURE_MESSAGE;
}

/** N1: 滚动容器是否在底部附近。量不到容器时按「在底部」处理 (首次渲染) */
export function isNearBottom(el: HTMLElement | null | undefined, threshold: number = NEAR_BOTTOM_PX): boolean {
  if (!el) return true;
  return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold;
}

/** V3 / E1: diff 类型码 -> 中文。没登记的类型一律落成「配置变更」, 不把英文码原样给用户。 */
export const DIFF_TYPE_LABELS: Record<string, string> = {
  ADD: '新增',
  CREATE: '新增',
  UPDATE: '修改',
  MODIFY: '修改',
  REMOVE: '删除',
  DELETE: '删除',
  TOOL_TOGGLE: '开关切换',
  FIELD_CHANGE: '字段修改',
  TRIGGER_CHAIN_CHANGE: '触发链修改',
  VALIDATION_RULE_CHANGE: '校验规则修改',
  DEFAULT_VALUE_CHANGE: '默认值修改',
  WORKFLOW_CHANGE: '流程修改',
  WORK_PROCESS_CATALOG: '工序目录变更',
  PRODUCT_WORK_PROCESS_DRAFT: '工序草稿',
  PRODUCT_PROCESS_WORKFLOW_SPEC: '工序流程调整',
  PRODUCT_PROCESS_WORKFLOW_PATCH: '工序流程调整',
  APPROVAL_WORKFLOW_SPEC: '审批流程调整',
  INFO: '说明',
};

export const DIFF_TYPE_FALLBACK_LABEL = '配置变更';

export function diffTypeLabel(type: string | null | undefined): string {
  const key = (type ?? '').trim();
  return DIFF_TYPE_LABELS[key] ?? DIFF_TYPE_FALLBACK_LABEL;
}

/** V3: AI 模式名 -> 中文 (后端语义: autopilot 只生成候选、首次不写入; plan 生成方案逐项审核; action 只分析影响、不产出改动) */
export const AI_MODE_LABELS = {
  autopilot: '自动配置',
  plan: '先出方案',
  action: '影响分析',
} as const;

export type AiModeKey = keyof typeof AI_MODE_LABELS;
