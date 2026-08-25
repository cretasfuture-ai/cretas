import type {
  WorkflowBomSyncIssue,
  WorkflowBomSyncPreflight,
} from './types';

const AUTO_PUBLISHABLE_CLASSIFICATIONS = new Set([
  'READY',
  'AUTO_MIGRATABLE',
]);

export function canPublishWorkflowWithBomSync(
  preflight: WorkflowBomSyncPreflight,
): boolean {
  return AUTO_PUBLISHABLE_CLASSIFICATIONS.has(preflight.classification)
    && preflight.canCompleteAutomatically;
}

export function workflowBomSyncBlockingIssues(
  preflight: WorkflowBomSyncPreflight | null,
): WorkflowBomSyncIssue[] {
  if (!preflight || canPublishWorkflowWithBomSync(preflight)) return [];
  const issues = [...preflight.missingItems, ...preflight.conflicts];
  if (issues.length > 0) return issues;

  return [{
    code: `WORKFLOW_BOM_SYNC_${preflight.classification}`,
    materialTypeId: null,
    materialName: null,
    processNodeId: null,
    field: null,
    message: preflight.classification === 'CONFLICT'
      ? '当前 BOM 或 Workflow 草稿存在冲突，系统无法安全自动同步。'
      : '当前 BOM 与 Workflow 还需要人工补充信息后才能发布。',
    action: '请按提示处理冲突或缺失项，然后重新执行自动同步。',
  }];
}

export function workflowBomSyncStatusTitle(
  preflight: WorkflowBomSyncPreflight,
): string {
  if (preflight.classification === 'READY') {
    return 'BOM 与 Workflow 已一致，可以直接发布';
  }
  if (preflight.classification === 'AUTO_MIGRATABLE') {
    return '系统已准备好自动同步 BOM 并发布 Workflow';
  }
  if (preflight.classification === 'CONFLICT') {
    return '发现 BOM 或 Workflow 草稿冲突，尚未执行发布';
  }
  return '还需要补充信息，尚未执行发布';
}

export async function executeWorkflowPublishMutation<T>(
  preflight: WorkflowBomSyncPreflight,
  mutation: () => Promise<T>,
): Promise<T | null> {
  if (!canPublishWorkflowWithBomSync(preflight)) return null;
  return mutation();
}

/**
 * 配置就绪报告里的一条。`severity` 缺省按 `BLOCKING` 处理 ——
 * ⛔ 反过来(缺省 WARNING)会让一条本该拦人的项悄悄失效。
 */
export interface ProductConfigurationIssue {
  code: string;
  message: string;
  target?: string | null;
  severity?: string | null;
}

export const SEASONING_ABSENT_CODE = 'BOM_SEASONING_ABSENT';

/**
 * 「成品没配辅料」这条**只提示、不拦人**的项（2026-08-25 owner 裁定：
 * 辅料一律选填，但没配要提示一声，用户确认后照常保存）。
 *
 * @returns 需要提示时返回那一条；不需要提示返回 `null`
 */
export function seasoningAbsenceWarning(
  issues: ProductConfigurationIssue[] | null | undefined,
): ProductConfigurationIssue | null {
  if (!Array.isArray(issues)) return null;
  return issues.find((item) => item
    && item.code === SEASONING_ABSENT_CODE
    // ⛔ 必须是 WARNING 才走「确认后继续」这条路 —— 若哪天后端把它改成
    //    BLOCKING, 这里不该把它降级成一个可以点「继续」的提示框。
    && String(item.severity ?? '').toUpperCase() === 'WARNING') ?? null;
}
