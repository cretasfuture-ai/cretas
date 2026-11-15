import { describe, expect, it } from 'vitest';
import { buildResults, draftsToItems, emptyDraft, itemToDraft, rowActions, splitList } from '../firstArticleForm';
import type { FirstArticleItem } from '@/api/firstArticle';

const items: FirstArticleItem[] = [
  { key: 'i1', name: '块重', type: 'NUMERIC', required: true, unit: 'g', lower: 180, upper: 220, target: 200, options: null, passOptions: null },
  { key: 'i2', name: '封口', type: 'OPTION', required: false, unit: null, lower: null, upper: null, target: null, options: ['平整', '漏气'], passOptions: ['平整'] },
];

describe('首检表单换算', () => {
  it('选项按逗号 / 顿号 / 空格切分并去重', () => {
    expect(splitList('平整、轻微褶皱, 漏气  平整')).toEqual(['平整', '轻微褶皱', '漏气']);
  });

  it('数值格子填了非数字 → 报错不提交; 空着 → null (不是 0)', () => {
    const bad = { ...emptyDraft('NUMERIC'), name: '块重', lower: '18O' };
    expect(draftsToItems([bad]).error).toContain('块重');
    const ok = { ...emptyDraft('NUMERIC'), name: '块重', upper: '220' };
    const { items: out, error } = draftsToItems([ok]);
    expect(error).toBeNull();
    expect(out[0].lower).toBeNull();
    expect(out[0].upper).toBe(220);
  });

  it('标准 → 表单 → 标准 往返不丢字段', () => {
    const back = draftsToItems(items.map(itemToDraft)).items;
    expect(back).toEqual(items);
  });

  it('实测值: 空着的不提交, 非数字报错, 选项原样提交', () => {
    expect(buildResults(items, { i1: ' 205 ' }).results).toEqual([{ key: 'i1', numericValue: 205, optionValue: null }]);
    expect(buildResults(items, { i1: 'abc' }).error).toContain('块重');
    expect(buildResults(items, { i1: '200', i2: '漏气' }).results[1]).toEqual({ key: 'i2', numericValue: null, optionValue: '漏气' });
  });

  it('行操作: 送检只在没结束且没送检/不合格; 判定要质量写权限且有待判定单', () => {
    const perms = { canRequest: true, canJudge: false };
    expect(rowActions({ taskStatus: 'IN_PROGRESS', firstArticleStatus: 'NOT_REQUESTED', latestInspectionId: null }, perms).request).toBe(true);
    expect(rowActions({ taskStatus: 'IN_PROGRESS', firstArticleStatus: 'FAILED', latestInspectionId: 3 }, perms).request).toBe(true);
    expect(rowActions({ taskStatus: 'IN_PROGRESS', firstArticleStatus: 'PASSED', latestInspectionId: 3 }, perms).request).toBe(false);
    // 待开始也能送检 (web 逐道录入的任务录入前一直是待开始); 已完成不能
    expect(rowActions({ taskStatus: 'PENDING', firstArticleStatus: 'NOT_REQUESTED', latestInspectionId: null }, perms).request).toBe(true);
    expect(rowActions({ taskStatus: 'COMPLETED', firstArticleStatus: 'NOT_REQUESTED', latestInspectionId: null }, perms).request).toBe(false);
    // 没有质量写权限 → 不给判定按钮 (服务端同样会 403)
    expect(rowActions({ taskStatus: 'IN_PROGRESS', firstArticleStatus: 'PENDING', latestInspectionId: 3 }, perms).judge).toBe(false);
    expect(rowActions({ taskStatus: 'IN_PROGRESS', firstArticleStatus: 'PENDING', latestInspectionId: 3 }, { canRequest: false, canJudge: true }).judge).toBe(true);
  });
});
