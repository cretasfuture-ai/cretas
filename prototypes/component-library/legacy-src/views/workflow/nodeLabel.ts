import { enumLabel } from '@/utils/enumDisplay';

export interface WorkflowNodeDisplay {
  currentNodeLabel?: string | null;
  approverRoles?: string[] | null;
}

const CHINESE_TEXT = /[\u3400-\u9fff]/;

/**
 * \u300c\u8fd9\u4e32\u6587\u672c\u662f\u7ed9\u4eba\u770b\u7684\u4e2d\u6587\u540d\uff0c\u8fd8\u662f\u5185\u90e8\u82f1\u6587\u914d\u7f6e key\uff1f\u300d
 *
 * \u5224\u636e\u662f**\u542b\u4e0d\u542b\u4e2d\u6587**\u2014\u2014\u5de5\u4f5c\u6d41/\u8282\u70b9\u540d\u5141\u8bb8\u7ba1\u7406\u5458\u81ea\u5b9a\u4e49\uff0c\u5386\u53f2\u4e0e\u81ea\u52a8\u5efa\u94fe\u7559\u4e0b\u7684
 * \u662f\u7eaf\u82f1\u6587 key\uff08\u5982 `LIUSHANMEN admin approval`\u3001`PURCHASE_ORDER_APPROVAL - \u2026`\uff09\uff0c
 * \u800c\u771f\u6b63\u7ed9\u4e00\u7ebf\u770b\u7684\u540d\u5b57\u90fd\u662f\u4e2d\u6587\u3002
 *
 * \ud83d\udd34 \u5bfc\u51fa\u662f\u4e3a\u4e86\u8ba9**\u5ba1\u6279\u94fe\u914d\u7f6e\u9875**\u4e0e\u672c\u6587\u4ef6\u5171\u7528\u540c\u4e00\u6761\u5224\u636e \u2014\u2014
 * \u7f3a\u9677 23 (2026-09-01, LIUSHANMEN) \u7684\u82f1\u6587 key \u6cc4\u6f0f\u5728**\u4e24\u4e2a\u754c\u9762**\u4e0a\uff0c
 * \u26d4 \u4e0d\u8bb8\u5404\u5199\u4e00\u4efd\u300c\u50cf\u4e0d\u50cf\u82f1\u6587 key\u300d\u7684\u5224\u65ad\uff08\u5f62\u6001 D\uff1a\u4e24\u4efd\u4e00\u5b9a\u4f1a\u6f02\uff09\u3002
 *
 * \u26a0\ufe0f \u5b83\u662f**\u4ee3\u7406\u5224\u636e**\uff0c\u4e0d\u662f\u7cbe\u786e\u5224\u65ad\uff1a\u4e00\u4e2a\u7eaf\u82f1\u6587\u4f46\u786e\u5b9e\u662f\u4eba\u8d77\u7684\u540d\u5b57
 * \uff08\u4f8b\u5982\u7ba1\u7406\u5458\u5c31\u60f3\u53eb "Fast Track"\uff09\u4f1a\u88ab\u8bef\u5224\u6210\u914d\u7f6e key\u3002\u5f53\u524d\u53d6\u820d\u662f\u53ef\u63a5\u53d7\u7684 \u2014\u2014
 * \u8bef\u5224\u7684\u540e\u679c\u53ea\u662f\u5c11\u663e\u793a\u4e00\u4e2a\u5197\u4f59\u526f\u6807\u9898\uff0c\u800c\u6f0f\u5224\u7684\u540e\u679c\u662f\u628a\u5185\u90e8 key \u7529\u7ed9\u4e00\u7ebf\u3002
 */
export function isHumanReadableName(text?: string | null): boolean {
  return CHINESE_TEXT.test(text?.trim() || '');
}

/**
 * 工作流模板允许管理员自定义节点名。历史模板里存在纯英文配置名，面向工厂用户时
 * 优先用同一节点的授权角色中文名；已经是中文的业务节点名保持原样。
 */
export function currentNodeText(row: WorkflowNodeDisplay): string {
  const label = row.currentNodeLabel?.trim() || '';
  if (isHumanReadableName(label)) return label;

  const roles = (row.approverRoles || []).map((role) => enumLabel(role)).filter(Boolean);
  return roles.join('、') || label || '—';
}

export function approverHint(row: WorkflowNodeDisplay): string {
  const roles = (row.approverRoles || []).map((role) => enumLabel(role)).filter(Boolean);
  if (!roles.length) return '';
  const text = roles.join('、');
  return text === currentNodeText(row) ? '' : text;
}
