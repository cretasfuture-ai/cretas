import { describe, expect, it } from 'vitest';
import { ASSIGN_OPERATOR_FOCUS_QUERY_KEY, shouldFocusAssignmentSection } from '../assignmentDeepLink';

describe('shouldFocusAssignmentSection', () => {
  it('query 带 focusAssignment=1 时触发', () => {
    expect(shouldFocusAssignmentSection({ [ASSIGN_OPERATOR_FOCUS_QUERY_KEY]: '1' })).toBe(true);
  });

  it('vue-router 把重复 query key 解析成数组时也认得', () => {
    expect(shouldFocusAssignmentSection({ [ASSIGN_OPERATOR_FOCUS_QUERY_KEY]: ['0', '1'] })).toBe(true);
  });

  it('普通打开批次详情(无该 query) → 不触发', () => {
    expect(shouldFocusAssignmentSection({})).toBe(false);
    expect(shouldFocusAssignmentSection(null)).toBe(false);
    expect(shouldFocusAssignmentSection(undefined)).toBe(false);
  });

  it('值不是 "1" 时不触发 —— 不能只判断 key 存在', () => {
    expect(shouldFocusAssignmentSection({ [ASSIGN_OPERATOR_FOCUS_QUERY_KEY]: '0' })).toBe(false);
    expect(shouldFocusAssignmentSection({ [ASSIGN_OPERATOR_FOCUS_QUERY_KEY]: '' })).toBe(false);
    expect(shouldFocusAssignmentSection({ [ASSIGN_OPERATOR_FOCUS_QUERY_KEY]: true })).toBe(false);
  });

  it('与生产计划列表那侧用同一个 query key，两边不会各自漂移', () => {
    expect(ASSIGN_OPERATOR_FOCUS_QUERY_KEY).toBe('focusAssignment');
  });
});
