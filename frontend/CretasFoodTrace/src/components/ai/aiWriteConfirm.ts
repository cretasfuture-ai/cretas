/**
 * AI 对话写操作确认 —— 纯逻辑（无 React、无网络）。
 *
 * 链路（对齐 web-admin AIQuery.vue:1276-1310 + AiOperationCard.vue）:
 *   流式结果 WRITE_CONFIRM_REQUIRED
 *     → 前端用「原话 + 被拦的 intentCode + sessionId」POST /ai-intents/preview
 *     → PREVIEW + confirmableAction (ToolDispatchService.java:812-859)
 *     → 用户点「确认执行」→ POST /ai-intents/confirm (token 只走请求头)
 *     → IntentExecutionOrchestrator.confirm 原子认领 token, 成功 = SUCCESS / COMPLETED
 *
 * 设计与摩擦点见 docs/specs/rn-ai-chat-write-confirm-2026-10-04.md。
 */
import type {
  ConfirmableAction,
  IntentExecuteResponse,
} from '../../services/api/intentConfigApiClient';

export type WriteConfirmPhase =
  /** 正在生成预览 */
  | 'preparing'
  /** 预览可确认 */
  | 'ready'
  /** 预览没通过 / 不支持 / 无权限 —— 没有「确认执行」按钮 */
  | 'blocked'
  /** 确认请求在途 */
  | 'executing'
  /** 已执行 (或后端明确说没执行, 如演示环境 —— 见 doneLabel) */
  | 'done'
  | 'cancelled'
  /** 确认失败 */
  | 'failed';

export type WriteConfirmFailureKind = 'expired' | 'permission' | 'network' | 'rejected';

export interface WriteConfirmState {
  phase: WriteConfirmPhase;
  intentCode?: string;
  intentName: string;
  /** 用户原话 —— 预览请求用它, 与被拦的那次请求同一句 */
  userInput: string;
  /**
   * 流式那一轮已经定下来的工具参数 (服务端 metadata.previewParams): 预览请求原样放进 context,
   * 预览就不用再从原话重新抽一遍 (prod 2026-10-06: 那一遍失败, 「开始1827号工序任务」被反问任务号);
   * 追问确认按钮带来的 taskId / 产量也靠它到得了预览。
   */
  previewContext?: Record<string, unknown>;
  /** 气泡正文 (不是 PREVIEW 那串原始 JSON) */
  leadText: string;
  action?: ConfirmableAction;
  /** 收到预览的本地时刻 (ms)。倒计时用本地时钟 + expiresInSeconds, 不比服务器时钟 (会有时差) */
  receivedAtMs?: number;
  /** blocked / failed 时给用户看的话 (后端原话优先) */
  notice?: string;
  failureKind?: WriteConfirmFailureKind;
  /** 是否给「重新生成预览」 */
  canRegenerate?: boolean;
  /** done 态的一行收起文案 */
  doneLabel?: string;
  /** 每次 (重新) 请求预览 +1: 旧预览的迟到回包不许覆盖新状态 */
  previewAttempt: number;
}

export type ConfirmOutcome =
  | { kind: 'done'; content: string; doneLabel: string; executed: boolean }
  | { kind: 'failed'; failureKind: WriteConfirmFailureKind; notice: string; canRegenerate: boolean };

/** 不知道意图中文名时的显示 (不加书名号) */
export const GENERIC_INTENT_NAME = '这个操作';

/** 「意图名」; 不知道名字时就说「这个操作」, 不出现「「这个操作」」 */
export function quoteIntent(name: string): string {
  return name && name !== GENERIC_INTENT_NAME ? `「${name}」` : GENERIC_INTENT_NAME;
}

const MANUAL_FALLBACK = '这个操作暂时不能在对话里确认执行，请到对应功能页面手工操作。';

const PERMISSION_STATUSES = new Set(['PERMISSION_DENIED', 'NO_PERMISSION']);
const SUCCESS_STATUSES = new Set(['SUCCESS', 'COMPLETED']);

/** 预览/确认失败里「凭证不能再用了」的那一类: 重新生成预览就能继续 */
const PROOF_UNUSABLE = /令牌|过期|重新发起预览|不匹配|expired|invalid/i;

/** 预览数据里不给用户看的系统键 */
const HIDDEN_KEYS = new Set([
  'factoryId', 'factory_id', 'userId', 'user_id', 'userRole', 'role', 'intentCode', 'userInput',
  'status', 'canDo', 'message', 'success', 'confirmed', 'permissions', 'scopes', 'principal',
  'tenantId', 'tenant_id',
]);

/**
 * 已确认存在的业务键 → 中文标签 (取自 ReceiveWithLimitTool.doPreview 等写工具的预览载荷)。
 * 不在表里的键原样显示 —— ⛔ 不靠猜去翻译没见过的键。
 */
const KEY_LABELS: Record<string, string> = {
  orderNumber: '单号',
  supplierName: '供应商',
  materialName: '物料',
  productName: '产品',
  specification: '规格',
  unit: '单位',
  ordered: '订购数量',
  alreadyReceived: '已收',
  pending: '未收',
  overReceiveLimit: '超收上限',
  remainingCap: '还可入',
  yourProposedQty: '本次数量',
  quantity: '数量',
  actualQuantity: '实际数量',
  batchNumber: '批次号',
  notes: '备注',
  reason: '原因',
  warningIfOver: '提醒',
};

function str(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function upper(value: unknown): string {
  return typeof value === 'string' ? value.toUpperCase() : '';
}

function permissionNotice(intentName: string, backendMessage?: string): string {
  return backendMessage
    ?? `你的账号没有执行${quoteIntent(intentName)}的权限，请联系工厂管理员开通后再试。`;
}

/**
 * 服务端随 WRITE_CONFIRM_REQUIRED 给的 metadata.previewParams → 预览请求的 context。
 * 只收标量 (字符串 / 有限数字 / 布尔); 没有或不合规返回 undefined (预览照旧只带原话)。
 */
export function readPreviewParams(metadata: unknown): Record<string, unknown> | undefined {
  const raw = asRecord(asRecord(metadata)?.previewParams);
  if (!raw) return undefined;
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(raw)) {
    if (typeof value === 'string' || typeof value === 'boolean'
        || (typeof value === 'number' && Number.isFinite(value))) {
      out[key] = value;
    }
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/**
 * 流式结果 → 确认卡初始状态。不是写确认就返回 undefined (普通读结果不出卡)。
 * PREVIEW + confirmableAction 也可能直接从流里来, 那就直接进 ready。
 */
export function writeConfirmFromStreamResult(
  result: Record<string, unknown>,
  userInput: string,
  nowMs: number = Date.now(),
): WriteConfirmState | undefined {
  const status = upper(result.status);
  const intentCode = str(result.intentCode);
  // SITE B (ToolDispatchService:200) 的拦截响应不带 intentName; ⛔ 不拿英文意图码顶替给一线看,
  // 预览回来会带上真名替换掉。
  const intentName = str(result.intentName) ?? GENERIC_INTENT_NAME;
  if (status === 'WRITE_CONFIRM_REQUIRED') {
    const previewContext = readPreviewParams(result.metadata);
    return {
      phase: 'preparing',
      intentCode,
      intentName,
      userInput,
      ...(previewContext ? { previewContext } : {}),
      leadText: str(result.message) ?? `${quoteIntent(intentName)}会修改数据，需要你确认后才会执行。`,
      previewAttempt: 0,
    };
  }
  if (status === 'PREVIEW' && asRecord(result.confirmableAction)) {
    const base: WriteConfirmState = {
      phase: 'preparing',
      intentCode,
      intentName,
      userInput,
      leadText: '',
      previewAttempt: 0,
    };
    const applied = applyPreviewResponse(base, result as IntentExecuteResponse, nowMs);
    return { ...applied, leadText: applied.action?.description ?? `${quoteIntent(applied.intentName)}需要你确认后才会执行。` };
  }
  return undefined;
}

/**
 * 预览被拦下的原因 (null = 预览通过)。
 * ⚠️ 后端对失败 / 被业务边界拦住的预览**照样铸 token** (ToolDispatchService 只看是不是写工具),
 *    所以「能不能确认」必须由前端读 previewData 判断:
 *    - `{success:false, error|message}` —— 工具预览失败 / 缺参数
 *    - `{success:true, data:{canDo:false, message}}` —— 业务边界 (超收 / 已完成 …)
 */
export function blockedPreviewReason(previewData: unknown): string | null {
  const root = asRecord(previewData);
  if (!root) return null;
  if (root.success === false) {
    return str(root.message) ?? str(root.error) ?? '预览没有通过，未执行任何操作。';
  }
  const data = asRecord(root.data) ?? root;
  if (data.canDo === false) {
    return str(data.message) ?? '当前条件下不能执行这个操作，未执行任何操作。';
  }
  return null;
}

/** 预览响应 → 卡片状态 (ready / blocked)。 */
export function applyPreviewResponse(
  state: WriteConfirmState,
  response: IntentExecuteResponse | null | undefined,
  nowMs: number,
): WriteConfirmState {
  const intentName = str(response?.intentName) ?? state.intentName;
  const intentCode = str(response?.intentCode) ?? state.intentCode;
  const status = upper(response?.status);
  const backendMessage = str(response?.formattedText) ?? str(response?.message);
  const base = { ...state, intentName, intentCode, action: undefined, notice: undefined, failureKind: undefined };

  if (!response) {
    return { ...base, phase: 'blocked', notice: `服务器没有返回预览结果。${MANUAL_FALLBACK}`, canRegenerate: true };
  }
  if (status === 'PREVIEW') {
    const action = response.confirmableAction;
    if (!action || !str(action.confirmToken) || !str(action.commandDigest) || !str(action.expiresAt)) {
      return { ...base, phase: 'blocked', notice: MANUAL_FALLBACK, canRegenerate: false };
    }
    const blocked = blockedPreviewReason(action.previewData);
    if (blocked) {
      return { ...base, phase: 'blocked', notice: blocked, canRegenerate: false };
    }
    return { ...base, phase: 'ready', action, receivedAtMs: nowMs, canRegenerate: false };
  }
  if (PERMISSION_STATUSES.has(status)) {
    return {
      ...base,
      phase: 'blocked',
      failureKind: 'permission',
      notice: permissionNotice(intentName, backendMessage),
      canRegenerate: false,
    };
  }
  if (status === 'NEED_MORE_INFO') {
    const questions = Array.isArray(response.clarificationQuestions)
      ? response.clarificationQuestions.filter((q): q is string => typeof q === 'string' && q.trim() !== '')
      : [];
    const ask = questions.length > 0
      ? `还需要以下信息：\n${questions.map((q, i) => `${i + 1}. ${q}`).join('\n')}\n请补充后重新说一遍。`
      : (backendMessage ?? '还缺少必要信息，请补充后重新说一遍。');
    return { ...base, phase: 'blocked', notice: ask, canRegenerate: false };
  }
  if (status === 'PREVIEW_BLOCKED') {
    // 后端 (#907) 预览没通过 (业务规则不允许 / 预览失败) 就不发令牌, 原因在 message 里。
    // 这是「这一单现在不能做」(例: 工序不在进行中), 不是「功能不支持」—— 不拼手工兜底那句。
    return {
      ...base,
      phase: 'blocked',
      notice: backendMessage ?? '这个操作现在不能执行。',
      canRegenerate: false,
    };
  }
  // PREVIEW_UNSUPPORTED / FAILED / 其它: 后端原话 + 下一步
  return {
    ...base,
    phase: 'blocked',
    notice: backendMessage ? `${backendMessage}\n${MANUAL_FALLBACK}` : MANUAL_FALLBACK,
    canRegenerate: false,
  };
}

interface HttpishError {
  message?: unknown;
  response?: { status?: unknown; data?: { message?: unknown } };
}

function errorStatus(error: unknown): number | undefined {
  const status = (error as HttpishError | null)?.response?.status;
  return typeof status === 'number' ? status : undefined;
}

function errorBackendMessage(error: unknown): string | undefined {
  return str((error as HttpishError | null)?.response?.data?.message);
}

/** 预览请求本身抛错 (网络 / HTTP)。预览是只读的, 所以可以放心给「重新生成预览」。 */
export function previewRequestFailed(state: WriteConfirmState, error: unknown): WriteConfirmState {
  const status = errorStatus(error);
  const backendMessage = errorBackendMessage(error);
  if (status === 403) {
    return {
      ...state,
      phase: 'blocked',
      failureKind: 'permission',
      notice: permissionNotice(state.intentName, backendMessage),
      canRegenerate: false,
    };
  }
  const notice = status === undefined
    ? '网络异常，没能生成操作预览（没有执行任何操作）。请检查网络后点「重新生成预览」。'
    : `${backendMessage ?? '没能生成操作预览'}（没有执行任何操作）。可以点「重新生成预览」再试一次。`;
  return { ...state, phase: 'blocked', failureKind: status === undefined ? 'network' : 'rejected', notice, canRegenerate: true };
}

/** 剩余有效秒数; 没有 expiresInSeconds 时返回 null (不显示倒计时)。 */
export function remainingSeconds(state: WriteConfirmState, nowMs: number): number | null {
  const ttl = state.action?.expiresInSeconds;
  if (typeof ttl !== 'number' || state.receivedAtMs === undefined) return null;
  return Math.max(0, Math.ceil(ttl - (nowMs - state.receivedAtMs) / 1000));
}

export function isPreviewExpired(state: WriteConfirmState, nowMs: number): boolean {
  const left = remainingSeconds(state, nowMs);
  return left !== null && left <= 0;
}

export const EXPIRED_NOTICE = '预览已过期（没有执行任何操作）。请点「重新生成预览」后再确认。';

/** 确认接口返回 (HTTP 200) → 结果分类。 */
export function classifyConfirmResponse(
  response: IntentExecuteResponse | null | undefined,
  intentName: string,
): ConfirmOutcome {
  const status = upper(response?.status);
  const backendMessage = str(response?.formattedText) ?? str(response?.message);
  if (SUCCESS_STATUSES.has(status)) {
    return {
      kind: 'done',
      executed: true,
      content: backendMessage ?? `${quoteIntent(intentName)}已执行。`,
      doneLabel: '已执行',
    };
  }
  if (status === 'DEMO_WRITE_BLOCKED' || status === 'PENDING_APPROVAL') {
    return {
      kind: 'done',
      executed: false,
      content: backendMessage
        ?? (status === 'PENDING_APPROVAL' ? '已提交审批，等待审批人处理。' : '演示环境不执行真实写入。'),
      doneLabel: status === 'PENDING_APPROVAL' ? '已提交审批' : '未执行（演示环境）',
    };
  }
  if (PERMISSION_STATUSES.has(status)) {
    return {
      kind: 'failed',
      failureKind: 'permission',
      notice: permissionNotice(intentName, backendMessage),
      canRegenerate: false,
    };
  }
  if (backendMessage && PROOF_UNUSABLE.test(backendMessage)) {
    return {
      kind: 'failed',
      failureKind: 'expired',
      notice: `${backendMessage}（没有执行任何操作）。请点「重新生成预览」后再确认。`,
      canRegenerate: true,
    };
  }
  // 执行失败 / 未知状态: 原话显示, ⛔ 不给一键重试 —— 有的失败 (如「状态记录失败, 请勿重复提交」)
  // 重试会双写 (防呆 Rule 4)。用户看清原因后可以重新说一遍。
  return {
    kind: 'failed',
    failureKind: 'rejected',
    notice: backendMessage ?? `${quoteIntent(intentName)}没有执行成功，服务器没有给出原因。请稍后到对应页面查看。`,
    canRegenerate: false,
  };
}

/** 确认请求抛错 (网络 / HTTP 4xx-5xx) → 结果分类。 */
export function classifyConfirmError(error: unknown, intentName: string): ConfirmOutcome {
  const status = errorStatus(error);
  const backendMessage = errorBackendMessage(error);
  if (status === undefined) {
    // 请求可能已经到达服务器并执行了 —— ⛔ 不能自动重试, 也不给一键重试 (会双写)。
    return {
      kind: 'failed',
      failureKind: 'network',
      notice: `网络异常，无法确认${quoteIntent(intentName)}是否已经执行。请先到对应页面查看结果；确认没有执行，再重新说一遍。`,
      canRegenerate: false,
    };
  }
  if (status === 403) {
    return { kind: 'failed', failureKind: 'permission', notice: permissionNotice(intentName, backendMessage), canRegenerate: false };
  }
  if (status === 401) {
    return { kind: 'failed', failureKind: 'rejected', notice: '登录已过期，请重新登录后再试（没有执行任何操作）。', canRegenerate: false };
  }
  if (status === 400 || status === 409 || status === 410) {
    // 凭证校验失败 (@Future expiresAt / 格式) —— 请求在认领前就被拒, 没有执行
    return {
      kind: 'failed',
      failureKind: 'expired',
      notice: `${backendMessage ?? '确认凭证已失效'}（没有执行任何操作）。请点「重新生成预览」后再确认。`,
      canRegenerate: true,
    };
  }
  return {
    kind: 'failed',
    failureKind: 'rejected',
    notice: `${backendMessage ?? `服务器出错（${status}）`}。无法确认${quoteIntent(intentName)}是否已经执行，请先到对应页面查看结果。`,
    canRegenerate: false,
  };
}

export interface PreviewRow {
  key: string;
  label: string;
  value: string;
  /** diff 行: 修改前的值 */
  before?: string;
  changed?: boolean;
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'object') {
    try { return JSON.stringify(value); } catch { return String(value); }
  }
  return String(value);
}

function labelOf(key: string): string {
  return KEY_LABELS[key] ?? key;
}

/**
 * 给用户看的关键内容: 有 newValues 时出「当前 → 修改后」对比行;
 * 否则出 previewData.data 的键值 (剥掉 success/data 外壳和系统键)。
 */
export function previewRows(action: ConfirmableAction | undefined): PreviewRow[] {
  if (!action) return [];
  const next = asRecord(action.newValues);
  if (next && Object.keys(next).length > 0) {
    const cur = asRecord(action.currentValues) ?? {};
    const keys = Array.from(new Set([...Object.keys(cur), ...Object.keys(next)]));
    return keys
      .filter((k) => !HIDDEN_KEYS.has(k))
      .map((k) => {
        const before = formatValue(cur[k]);
        const value = formatValue(next[k]);
        return { key: k, label: labelOf(k), value, before, changed: before !== value };
      });
  }
  const root = asRecord(action.previewData);
  if (!root) return [];
  const data = asRecord(root.data) ?? root;
  return Object.entries(data)
    .filter(([k]) => !HIDDEN_KEYS.has(k) && k !== 'data')
    .map(([k, v]) => ({ key: k, label: labelOf(k), value: formatValue(v) }));
}

/** 预览里的提示语 (例如 data.message 是一句「可入库」之类的说明) */
export function previewNote(action: ConfirmableAction | undefined): string | undefined {
  const root = asRecord(action?.previewData);
  const data = asRecord(root?.data) ?? root;
  return str(data?.message);
}

export function impactText(state: WriteConfirmState): string {
  const backend = str(state.action?.impactSummary);
  if (backend) return backend;
  const at = upper(state.action?.actionType);
  if (at === 'DELETE') return `此操作将删除${quoteIntent(state.intentName)}相关数据，确认后立即生效且不可自动恢复。`;
  if (at === 'UPDATE') return '此操作将修改现有数据，确认后立即生效。';
  return '此操作将写入数据，确认后立即生效。';
}

export function isDestructive(state: WriteConfirmState): boolean {
  const at = upper(state.action?.actionType);
  const rl = upper(state.action?.riskLevel);
  return at === 'DELETE' || rl === 'HIGH' || rl === 'CRITICAL';
}

export const CANCELLED_REPLY = '已取消，没有执行任何操作';
