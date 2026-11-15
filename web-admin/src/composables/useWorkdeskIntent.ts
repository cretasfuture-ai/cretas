import { ref, watch } from 'vue';
import request from '@/api/request';

export interface WorkdeskIdentity {
  factoryId: string;
  actorId: string;
  role?: string;
}

export interface WorkdeskIntentResponse {
  intentRecognized?: boolean;
  intentCode?: string;
  intentName?: string;
  status?: string;
  message?: string;
  formattedText?: string;
  resultData?: Record<string, unknown>;
  sessionId?: string;
  metadata?: Record<string, unknown>;
  suggestedActions?: Array<{ actionCode?: string; actionName?: string }>;
}

/** Role workdesks query through the shared recognizer. Writes use the native domain forms. */
export function useWorkdeskIntent(identity: () => WorkdeskIdentity) {
  const sessionId = ref('');
  const clarificationQuestions = ref<string[]>([]);
  let generation = 0;
  function reset() {
    generation += 1;
    sessionId.value = '';
    clarificationQuestions.value = [];
  }
  const scope = () => `${identity().factoryId}:${identity().actorId}:${identity().role || ''}`;
  watch(scope, reset, { flush: 'sync' });

  async function execute(userInput: string, context?: Record<string, unknown>, options?: { intentCode?: string; parameters?: Record<string, unknown> }): Promise<WorkdeskIntentResponse> {
    const current = identity();
    if (!current.factoryId || !current.actorId) throw new Error('账号未绑定企业，请重新登录');
    if (!userInput.trim()) throw new Error('请先填写要查询的问题');
    const requestedScope = scope();
    const requestedGeneration = generation;
    sessionId.value ||= crypto.randomUUID();
    const response = await request.post<WorkdeskIntentResponse>(
      `/${current.factoryId}/ai-intents/execute`,
      { userInput, sessionId: sessionId.value, mode: 'READ', ...(context ? { context } : {}),
        ...(options?.intentCode ? { intentCode: options.intentCode } : {}),
        ...(options?.parameters ? { parameters: options.parameters } : {}) },
    );
    if (requestedScope !== scope() || requestedGeneration !== generation) {
      throw new Error('企业或对话已切换，请重新查询');
    }
    const result = (response as { data: WorkdeskIntentResponse }).data;
    if (!result) throw new Error('没有收到查询结果，请重试');
    const returnedSession = result.sessionId || result.metadata?.sessionId;
    if (typeof returnedSession === 'string' && returnedSession) sessionId.value = returnedSession;
    clarificationQuestions.value = result.status === 'NEED_CLARIFICATION'
      ? (result.suggestedActions || []).filter((action) => action.actionCode === 'SELECT_INTENT' && typeof action.actionName === 'string')
        .map((action) => action.actionName!).slice(0, 8) : [];
    if (['DENIED', 'NO_PERMISSION', 'PERMISSION_DENIED'].includes(result.status || '')) {
      throw new Error('当前查询未获准执行，请联系管理员核对岗位能力；也可以从右侧业务入口查询');
    }
    if (['FAILED', 'ERROR', 'NOT_RECOGNIZED'].includes(result.status || '')) {
      const message = result.message && /[\u4e00-\u9fff]/.test(result.message) ? result.message : '本次未能完成查询，请换一种说法或从业务入口查询';
      throw new Error(message);
    }
    if (result.status === 'READ_MODE_WRITE_BLOCKED') {
      return { ...result, formattedText: '这是办理单据的请求。请从下方业务入口继续，核对表单后再保存。' };
    }
    // This tool reports its actual bounded window; the shared unsupported-date notice says
    // "all time" even though this result still uses that window. Preserve the warning accurately.
    if (result.intentCode === 'MATERIAL_TODAY_RECEIVING_QUERY') {
      const data = result.resultData?.data || result.resultData;
      if (data && typeof data === 'object' && 'today' in data && 'rangeEnd' in data && 'includeOverdue' in data
        && typeof data.today === 'string' && typeof data.rangeEnd === 'string' && typeof data.includeOverdue === 'boolean') {
        const text = result.formattedText || result.message || '';
        const notice = /^注意：这个查询不能按时间筛选（[^）]*），下面是不限时间的结果。\s*/;
        if (notice.test(text)) {
          const range = data.includeOverdue ? `截至 ${data.rangeEnd}，包含逾期待收` : `${data.today} 至 ${data.rangeEnd}`;
          return { ...result, formattedText: `本次未按你指定的日期筛选。实际查询范围：${range}；仅统计已维护预计到货日期的订单。\n\n${text.replace(notice, '')}` };
        }
      }
    }
    return result;
  }
  return { execute, reset, sessionId, clarificationQuestions };
}
