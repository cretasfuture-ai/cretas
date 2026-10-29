import { describe, expect, it } from 'vitest';
import {
  packagingProblemSummary,
  packagingRuleState,
  splitPackagingRules,
} from '../packagingRuleDraft';

/**
 * D-S2-12: 新建原料类型时默认那条空白包装换算行留空 → 点保存没有任何提示也没有请求。
 * 守的行为: 全空的新行被忽略、能直接保存; 填一半的行被拦下并点名缺什么。
 */
describe('packagingRuleDraft (D-S2-12)', () => {
  it('a completely empty new row is ignored, so the form can save with no packaging conversion', () => {
    const split = splitPackagingRules([
      { packageUnit: '', conversionFactor: undefined },
    ]);
    expect(split.complete).toEqual([]);
    expect(split.problems).toEqual({});
  });

  it('el-input-number cleared to null still counts as empty', () => {
    expect(packagingRuleState({ packageUnit: '  ', conversionFactor: null })).toBe('EMPTY');
  });

  it('keeps complete rows in order and drops only the empty ones', () => {
    const box = { packageUnit: '箱', conversionFactor: 10 };
    const bag = { packageUnit: '袋', conversionFactor: 2 };
    const split = splitPackagingRules([box, { packageUnit: '', conversionFactor: undefined }, bag]);
    expect(split.complete).toEqual([box, bag]);
    expect(split.problems).toEqual({});
  });

  it('a row with only the unit is blocked with a visible reason for that row', () => {
    const split = splitPackagingRules([{ packageUnit: '箱', conversionFactor: undefined }]);
    expect(split.complete).toEqual([]);
    expect(split.problems).toEqual({ 0: '请填写换算数' });
    expect(packagingProblemSummary(split.problems)).toContain('第 1 条：请填写换算数');
  });

  it('a row with only the factor is blocked with a visible reason for that row', () => {
    const split = splitPackagingRules([
      { packageUnit: '箱', conversionFactor: 10 },
      { packageUnit: '', conversionFactor: 5 },
    ]);
    expect(split.problems).toEqual({ 1: '请选择包装单位' });
    expect(packagingProblemSummary(split.problems)).toContain('第 2 条：请选择包装单位');
  });

  it('a non-positive factor is blocked, not silently dropped', () => {
    expect(splitPackagingRules([{ packageUnit: '箱', conversionFactor: 0 }]).problems)
      .toEqual({ 0: '换算数必须大于 0' });
  });

  it('a previously saved rule that was cleared is not silently deleted', () => {
    const split = splitPackagingRules([{ id: 'spec-1', packageUnit: '', conversionFactor: null }]);
    expect(split.complete).toEqual([]);
    expect(split.problems[0]).toContain('删除');
  });
});
