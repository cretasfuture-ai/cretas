import { describe, expect, it, vi } from 'vitest';
import type { WorkflowBomSyncPreflight } from '../types';
import {
  canPublishWorkflowWithBomSync,
  executeWorkflowPublishMutation,
  workflowBomSyncBlockingIssues,
  seasoningAbsenceWarning,
  workflowBomSyncStatusTitle,
} from '../workflowPublishGate';

function preflight(
  overrides: Partial<WorkflowBomSyncPreflight> = {},
): WorkflowBomSyncPreflight {
  return {
    classification: 'READY',
    activeBomVersion: 3,
    syncDraftVersion: null,
    activeBomWorkflowRevisionId: 175,
    targetWorkflowRevisionId: 222,
    preservedItems: [],
    automaticMappings: [],
    missingItems: [],
    conflicts: [],
    canCompleteAutomatically: true,
    ...overrides,
  };
}

describe('workflowPublishGate', () => {
  it.each(['READY', 'AUTO_MIGRATABLE'] as const)(
    'allows one atomic mutation for %s',
    (classification) => {
      expect(canPublishWorkflowWithBomSync(preflight({ classification }))).toBe(true);
    },
  );

  it.each(['USER_INPUT_REQUIRED', 'CONFLICT'] as const)(
    'blocks the mutation for %s and exposes each issue',
    (classification) => {
      const result = preflight({
        classification,
        canCompleteAutomatically: false,
        missingItems: [{
          code: 'BOM_WORKFLOW_INPUT_ITEM_MISSING',
          materialTypeId: 'RM-1',
          materialName: '鸡肉',
          processNodeId: 'process-1',
          field: 'inputPort',
          message: '鸡肉尚未指定投入工序',
          action: '请选择投入工序',
        }],
      });

      expect(canPublishWorkflowWithBomSync(result)).toBe(false);
      expect(workflowBomSyncBlockingIssues(result)).toEqual(result.missingItems);
      expect(workflowBomSyncStatusTitle(result)).toContain('尚未执行发布');
    },
  );

  it('fails closed when an apparently automatic classification is inconsistent', () => {
    const result = preflight({
      classification: 'AUTO_MIGRATABLE',
      canCompleteAutomatically: false,
    });

    expect(canPublishWorkflowWithBomSync(result)).toBe(false);
    expect(workflowBomSyncBlockingIssues(result)).toHaveLength(1);
  });

  it.each(['USER_INPUT_REQUIRED', 'CONFLICT'] as const)(
    'executes zero mutations for blocked classification %s',
    async (classification) => {
      const mutation = vi.fn().mockResolvedValue('published');
      const result = await executeWorkflowPublishMutation(preflight({
        classification,
        canCompleteAutomatically: false,
      }), mutation);

      expect(result).toBeNull();
      expect(mutation).not.toHaveBeenCalled();
    },
  );
});

/**
 * 🔴 2026-08-25 owner 裁定：辅料一律**选填**，但没配要提示一声，
 * 用户确认后照常保存。
 *
 * 所以这组用例钉的是**「认得出该提示的那一条」且「认不出别的」**——
 * 一旦它把 BLOCKING 的项也认成「可以点继续」的提示，
 * 就等于把一道拦人的闸降级成了一个可以无视的弹框。
 */
describe('成品未配辅料的提示（只提示，不拦人）', () => {
  const warn = {
    code: 'BOM_SEASONING_ABSENT',
    message: '当前没有配置辅料，请确认该成品是否需要配置辅料；不需要可直接继续保存',
    target: 'seasonings',
    severity: 'WARNING',
  };

  it('认得出 WARNING 级的辅料缺失项', () => {
    expect(seasoningAbsenceWarning([warn])).toEqual(warn);
    expect(seasoningAbsenceWarning([
      { code: 'BOM_RAW_REQUIRED', message: 'x', severity: 'BLOCKING' },
      warn,
    ])).toEqual(warn);
  });

  it('🔴 阴性对照：BLOCKING 级的同名码不许被当成「可以点继续」的提示', () => {
    expect(seasoningAbsenceWarning([{ ...warn, severity: 'BLOCKING' }])).toBeNull();
    // severity 缺省按 BLOCKING 处理 —— 忘了标级别时宁可不弹「继续」框
    expect(seasoningAbsenceWarning([{ code: warn.code, message: warn.message }])).toBeNull();
    expect(seasoningAbsenceWarning([{ ...warn, severity: null }])).toBeNull();
  });

  it('🔴 阴性对照：别的 WARNING 不许被认成辅料提示', () => {
    expect(seasoningAbsenceWarning([
      { code: 'SOMETHING_ELSE', message: 'x', severity: 'WARNING' },
    ])).toBeNull();
  });

  it('没有 issues / 不是数组时安全返回 null（读不到就绪报告要能放行）', () => {
    expect(seasoningAbsenceWarning([])).toBeNull();
    expect(seasoningAbsenceWarning(null)).toBeNull();
    expect(seasoningAbsenceWarning(undefined)).toBeNull();
  });
});
