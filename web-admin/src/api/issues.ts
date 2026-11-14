/**
 * 迁移 P4「发现的问题」处置接口。
 *
 * Backend: IssueDispositionController @ /api/mobile/{factoryId}/issues/dispositions
 *   GET  /                  本厂已关闭 / 已转待办的处置
 *   GET  /assigned-to-me    转给我的待办（「待办 › 指派给我」）
 *   POST /close             关闭：必须选结论 + 写一句
 *   POST /convert           转成我的待办
 *   POST /reopen            恢复
 * 问题本身仍由页面从各来源已有接口读取；这里只存处置，不改来源单据。
 */
import { get, post } from './request';

export type IssueSourceType =
  | 'SHORTAGE_CASE'
  | 'DELIVERY_RISK'
  | 'PROCESS_PARAM_DEVIATION'
  | 'COST_DEVIATION'
  | 'COLD_CHAIN_ALERT'
  | 'DAILY_RECONCILE_RUN';

/** 关闭结论：沿用原型清单（DECISIONS M8），码与后端 IssueDisposition.Conclusion 一致。 */
export type IssueConclusion = 'HANDLED' | 'FALSE_ALARM' | 'ACCEPTED_DEVIATION';

export const ISSUE_CONCLUSIONS: ReadonlyArray<{ code: IssueConclusion; label: string; description: string }> = [
  { code: 'HANDLED', label: '已处理', description: '问题已经处理掉了' },
  { code: 'FALSE_ALARM', label: '误报', description: '数据或规则误判，不是真问题' },
  { code: 'ACCEPTED_DEVIATION', label: '接受偏差', description: '是真的，但在可接受范围内' },
];

export const CLOSE_REASON_MIN = 2;
export const CLOSE_REASON_MAX = 200;

export interface IssueDispositionView {
  sourceType: IssueSourceType;
  sourceId: string;
  status: 'OPEN' | 'CLOSED' | 'CONVERTED';
  conclusion: IssueConclusion | null;
  conclusionLabel: string | null;
  reason: string | null;
  title: string;
  linkPath: string | null;
  assigneeUserId: number | null;
  actorType: string;
  actorId: number;
  actedAt: string;
}

export interface IssueRef {
  sourceType: IssueSourceType;
  sourceId: string;
  title: string;
  linkPath?: string | null;
}

const base = (factoryId: string) => `/${factoryId}/issues/dispositions`;
/** 写入失败由页面在对话框 / 行内写明原因，不再弹全局提示。 */
const SILENT = { _silent: true };

export const listIssueDispositions = (factoryId: string, config?: Record<string, unknown>) =>
  get<IssueDispositionView[]>(base(factoryId), config);

export const listIssuesAssignedToMe = (factoryId: string, config?: Record<string, unknown>) =>
  get<IssueDispositionView[]>(`${base(factoryId)}/assigned-to-me`, config);

export const closeIssue = (factoryId: string, ref: IssueRef, conclusion: IssueConclusion, reason: string) =>
  post<IssueDispositionView>(`${base(factoryId)}/close`, { ...ref, conclusion, reason }, SILENT);

export const convertIssueToTodo = (factoryId: string, ref: IssueRef) =>
  post<IssueDispositionView>(`${base(factoryId)}/convert`, ref, SILENT);

export const reopenIssue = (factoryId: string, ref: IssueRef) =>
  post<IssueDispositionView>(`${base(factoryId)}/reopen`, ref, SILENT);

/**
 * 关闭前的前端校验（后端同样校验，这里只是让按钮和提示先拦住）：
 * 没选结论 / 结论不在清单 / 说明去空白后不足 2 字 / 超过 200 字 ⇒ 返回原因；可以提交 ⇒ 返回 ''。
 */
export function validateClose(conclusion: string | null | undefined, reason: string | null | undefined): string {
  if (!conclusion) return '先选结论（已处理 / 误报 / 接受偏差）';
  if (!ISSUE_CONCLUSIONS.some((c) => c.code === conclusion)) return '结论只能是 已处理 / 误报 / 接受偏差';
  const why = (reason ?? '').trim();
  if (why.length < CLOSE_REASON_MIN) return `写一句说明（至少 ${CLOSE_REASON_MIN} 个字）`;
  if (why.length > CLOSE_REASON_MAX) return `说明最多 ${CLOSE_REASON_MAX} 个字`;
  return '';
}
