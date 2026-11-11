import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { oaInvalidatedModules } from '@/utils/workspaceInvalidation';

/**
 * 1551-11: 强制插单接进审批画布后 (#1591), 审批人只能在「待我审批」里批 —— 旧的直接审批入口对
 * 走画布的计划返回 409。待我审批页如果不把 FORCE_INSERT 列为可操作, 审批人就没有任何地方能批,
 * 计划永远待审批、时段永远被占。
 */
const pending = readFileSync(resolve(__dirname, '../pending.vue'), 'utf8');

function actionableCodes(): string[] {
  const matched = pending.match(/ACTIONABLE_MODULE_CODES = new Set\(\[([\s\S]*?)\]\)/);
  expect(matched, '找不到 ACTIONABLE_MODULE_CODES').not.toBeNull();
  return matched![1].match(/'([A-Z_]+)'/g)?.map((item) => item.replace(/'/g, '')) ?? [];
}

describe('强制插单在待我审批里点得动', () => {
  it('FORCE_INSERT 在可操作业务码清单里', () => {
    expect(actionableCodes()).toContain('FORCE_INSERT');
  });

  it('批完之后生产计划与排程标签失效 (批准会排入并顺延被挤计划)', () => {
    expect(oaInvalidatedModules('FORCE_INSERT')).toEqual(
      expect.arrayContaining(['dashboard', 'production', 'scheduling']),
    );
  });

  it('本人提交的强制插单在允许本人审批时要求写原因', () => {
    expect(pending).toMatch(/row\.moduleCode === 'FORCE_INSERT'\) return isInitiator \? 'required' : 'none'/);
  });
});
