/**
 * 方案是 generate() 那一刻按访谈答案拼出来的描述生成的。之后答案变了
 * （例如在「已确认资料」里选了「保留已确认值」，被驳回的回答从会话里退回），
 * 页面里的方案还带着旧答案（被驳回的物料「盐」），保存时才被服务端拒绝。
 * 这里只回答一个问题：现在服务端的描述和生成方案时用的还是同一份吗。
 */
export function isPlanStale(
  generatedFromDescription: string,
  currentDescription: string | null | undefined,
  hasPlan: boolean,
): boolean {
  if (!hasPlan || !generatedFromDescription) return false
  return (currentDescription ?? '') !== generatedFromDescription
}

export const PLAN_STALE_MESSAGE =
  '访谈答案已经变化（例如在「已确认资料」里保留了已确认值，被驳回的回答已从访谈里退回），当前方案还是按旧答案生成的。'
  + '请先点「按最新答案重新生成」；被驳回的回答仍可在「已确认资料」的历史里查看。'
