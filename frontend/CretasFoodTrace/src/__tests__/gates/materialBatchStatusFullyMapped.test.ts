/**
 * 🔒 原料批次的 12 个后端枚举值，屏上的映射表**一个都不能少**。
 *
 * <h3>为什么（2026-08-29 逐屏走查）</h3>
 *
 * 走查发现状态徽章从来没工作过（映射表小写、后端发大写），修完之后又发现：
 * 我补的表只覆盖 5 个值，而后端 `MaterialBatchStatus` 有 **12 个**
 * （取自 `utils/materialBatchStatus.ts` 那张照枚举注释写的表）。
 *
 * 生产库当前只出现 AVAILABLE / DEPLETED / USED_UP 三种，所以另外 9 个**今天看不出问题** ——
 * 一旦出现就会走 `|| status` 兜底，把英文漏到仓管面前。
 * ▎「今天的数据里没有」不等于「代码里覆盖了」。
 *
 * ⚠️ 这道闸量的是**译文键**是否齐全，⛔ 不是「屏上渲染对不对」。
 * 后者要靠走查 —— 前者能被机械钉住，就不该留给走查。
 */
import zhHome from '../../i18n/locales/zh-CN/home.json';
import { BATCH_STATUS_WORK_MAP } from '../../utils/materialBatchStatus';
import { statusKey } from '../../utils/statusKey';

const NAMESPACES = ['materialBatch', 'materialBatchDetail'] as const;

describe('原料批次状态映射覆盖后端全部枚举', () => {
  const backendValues = Object.keys(BATCH_STATUS_WORK_MAP);

  it('自检：后端枚举表本身不是空的（⛔ 空表会让下面每条断言恒真）', () => {
    expect(backendValues.length).toBe(12);
  });

  it.each(NAMESPACES)('%s.status 覆盖全部 12 个值', (ns) => {
    const labels = (zhHome as Record<string, { status?: Record<string, string> }>)[ns]?.status ?? {};
    const missing = backendValues.filter((v) => !(statusKey(v) in labels));
    expect(missing).toEqual([]);
  });

  it.each(NAMESPACES)('%s.status 里没有译文是空串的键', (ns) => {
    const labels = (zhHome as Record<string, { status?: Record<string, string> }>)[ns]?.status ?? {};
    const blank = Object.entries(labels).filter(([, v]) => !v || !String(v).trim());
    expect(blank).toEqual([]);
  });

  it('⛔ 阴性对照：一个后端不存在的值不该被要求出现在表里', () => {
    expect(backendValues).not.toContain('DEFINITELY_NOT_A_STATUS');
  });
});
