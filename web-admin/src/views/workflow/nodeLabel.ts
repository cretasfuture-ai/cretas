import { enumLabel } from '@/utils/enumDisplay';

export interface WorkflowNodeDisplay {
  currentNodeLabel?: string | null;
  approverRoles?: string[] | null;
}

const CHINESE_TEXT = /[\u3400-\u9fff]/;

/**
 * 工作流模板允许管理员自定义节点名。历史模板里存在纯英文配置名，面向工厂用户时
 * 优先用同一节点的授权角色中文名；已经是中文的业务节点名保持原样。
 */
export function currentNodeText(row: WorkflowNodeDisplay): string {
  const label = row.currentNodeLabel?.trim() || '';
  if (CHINESE_TEXT.test(label)) return label;

  const roles = (row.approverRoles || []).map((role) => enumLabel(role)).filter(Boolean);
  return roles.join('、') || label || '—';
}

export function approverHint(row: WorkflowNodeDisplay): string {
  const roles = (row.approverRoles || []).map((role) => enumLabel(role)).filter(Boolean);
  if (!roles.length) return '';
  const text = roles.join('、');
  return text === currentNodeText(row) ? '' : text;
}
