/**
 * 问数据（AI）带给后端的「上下文历史」(味美实测 A7, 2026-11-19)。
 *
 * 改前: 最近 20 组问答不分数据源全部带上。同一对话里切换数据源后, 后端 LLM 看到上一张表的
 * 问答, 新问题就被答成上一题的答案 (实测第 13 条原样复读第 12 条)。
 * 规则: 历史只带「同一数据源」下产生的消息; 换源后从空历史开始。数据源以消息发出时的
 * 选择为准 (sourceUploadId), 不是现在的下拉值。
 */
export interface HistoryMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  loading?: boolean;
  /** 这条消息发出/生成时选中的上传数据源 id; 没选 (全部数据) 记 null */
  sourceUploadId?: number | null;
}

export function historyForSource(
  messages: HistoryMessage[],
  currentUploadId: number | null | undefined,
  excludeIds: string[],
  maxMessages = 40,
): Array<{ role: 'user' | 'assistant'; content: string }> {
  const current = currentUploadId ?? null;
  return messages
    .filter((m) => !excludeIds.includes(m.id) && !m.loading && m.content.trim())
    // undefined (旧消息/欢迎语以外的无标记消息) 按「没选数据源」算, 与 null 等价
    .filter((m) => (m.sourceUploadId ?? null) === current)
    .slice(-maxMessages)
    .map((m) => ({ role: m.role, content: m.content }));
}
