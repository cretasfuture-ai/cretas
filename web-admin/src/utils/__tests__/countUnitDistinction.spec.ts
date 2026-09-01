import { describe, expect, it } from 'vitest';
import { canonicalUnitCode, canonicalUnitCodeKeepingCount, displayUnit, sameUnit } from '../unitPricing';

/**
 * 只 / 个 / 件 不能在销售链路上被合并成同一个字。
 *
 * 🔴 2026-08-13 真机 E2E 抓到: 销售订单选中物料 YL060 温氏黄油鸡(物料字典单位「只」)后,
 * 订单行显示「件」; BC001 吸塑盒(单位「个」)也显示「件」。
 *
 * 根因不在 displayUnit —— 它有 RAW_COUNT_LABELS, 传「只」会原样返回。
 * 坏在**更早**: 选品时 canonicalUnitCode('只') → 'pcs' 就把原标签抹掉了,
 * 显示层再想还原也没有了。
 *
 * 后果: 仓管在发货单上看到「2 件黄油鸡」而不是「2 只」。数量算术不受影响,
 * 坏的是**人读到的东西** —— 与同文件 #1672 / #2097 修过两次的是同一类缺陷。
 *
 * ⚠️ 名单(DISTINCT_COUNT_LABELS)早就存在, 只是**只接在 sameUnit 上**,
 * 没接到落库/展示这条路 —— 「规则写对了但没接上」比没写更难发现。
 *
 * <h2>🔴🔴 2026-09-02 根治 (缺陷 33): 上面这段描述的是历史, 不再是当前行为</h2>
 *
 * `canonicalUnitCodeKeepingCount` 曾经是**唯一**保 只/个 不被合并的地方, 靠调用点
 * 各自记得改用它 (#474 就在 list.vue 补了 7 处)。这条防线**只在被记得调用的地方生效**,
 * 而 `MergePurchaseDialog.vue`/`resolvePurchaseSuggestionUnits` 从没接过它 —— 同一个
 * 缺陷会在下一个调用点重演。
 *
 * 根治是往上一层走: 让 {@link UNIT_ALIASES} 的目标码本身对齐后端 `normalize()` 的
 * 不动点, 使 `canonicalUnitCode` 自己就不再把 个/只/件 折成共享的 `pcs`。
 * `canonicalUnitCodeKeepingCount` 因此从「唯一防线」降级为「双重防线的第二层」——
 * 下面的测试改成验证**这个新状态**, 不再把旧的折叠行为当成需要专门钉住的东西。
 */
describe('计件单位 只/个 不被折叠', () => {
  it('canonicalUnitCode 本身已不再折叠 只/个/件 —— 根治在表这一层, 不靠调用点记得', () => {
    expect(canonicalUnitCode('只')).toBe('只');
    expect(canonicalUnitCode('个')).toBe('个');
    expect(canonicalUnitCode('件')).toBe('件');
    // 三者互不相同 —— 这正是 #1976「一只鸡不是一件包材」要求的
    expect(new Set([canonicalUnitCode('只'), canonicalUnitCode('个'), canonicalUnitCode('件')]).size).toBe(3);
  });

  it('KeepingCount 版本与 canonicalUnitCode 现在结果一致 (双重防线, 不是各管一半)', () => {
    expect(canonicalUnitCodeKeepingCount('只')).toBe('只');
    expect(canonicalUnitCodeKeepingCount('个')).toBe('个');
    expect(canonicalUnitCodeKeepingCount('件')).toBe('件');
    expect(canonicalUnitCodeKeepingCount('只')).toBe(canonicalUnitCode('只'));
    expect(canonicalUnitCodeKeepingCount('个')).toBe(canonicalUnitCode('个'));
  });

  it('「件」不在 DISTINCT_COUNT_LABELS 例外名单里, 但两条路径殊途同归 —— 它本来就是 pcs 的中文名', () => {
    // KeepingCount 走的是 canonicalUnitCode 分支(名单没收），但目标码本身已是「件」
    expect(canonicalUnitCodeKeepingCount('件')).toBe('件');
    expect(canonicalUnitCodeKeepingCount('pcs')).toBe('件');
  });

  it('其它单位行为不变(不能顺手改坏)', () => {
    expect(canonicalUnitCodeKeepingCount('kg')).toBe(canonicalUnitCode('kg'));
    expect(canonicalUnitCodeKeepingCount('公斤')).toBe(canonicalUnitCode('公斤'));
    expect(canonicalUnitCodeKeepingCount('箱')).toBe(canonicalUnitCode('箱'));
    expect(canonicalUnitCodeKeepingCount('')).toBe('');
  });

  it('端到端: 存什么就显示什么 —— 只→只, 个→个 (两条路径都对, 不再依赖走 KeepingCount)', () => {
    expect(displayUnit(canonicalUnitCodeKeepingCount('只'))).toBe('只');
    expect(displayUnit(canonicalUnitCodeKeepingCount('个'))).toBe('个');
    // 🔴 这条是本次修复的核心断言: 以前"对照"走合并版本会变成「件」(缺陷现场),
    // 现在两条路径给出同一个答案 —— 根治的判据就是这两行不再有分歧。
    expect(displayUnit(canonicalUnitCode('只'))).toBe('只');
    expect(displayUnit(canonicalUnitCode('个'))).toBe('个');
  });

  it('一只 ≠ 一个 ≠ 一件(sameUnit 与本归一同源, 不再各写一份)', () => {
    expect(sameUnit('只', '个')).toBe(false);
    expect(sameUnit('只', '件')).toBe(false);
    expect(sameUnit('件', 'pcs')).toBe(true);
    expect(sameUnit('kg', '公斤')).toBe(true);
  });

  /**
   * 变异对照 (硬约束 C″): 用「注入旧的折叠实现」证明上面那些断言不是恒真式 ——
   * 换回旧行为, 「只→只」那条会立刻塌成「只→件」。
   */
  it('注入旧的折叠函数会复现缺陷 —— 证明上面的断言不是在守空气', () => {
    const oldFoldingCanonicalUnitCode = (value: unknown): string => {
      const raw = String(value ?? '').trim();
      const OLD_ALIASES: Record<string, string> = { '个': 'pcs', '只': 'pcs', '件': 'pcs', pcs: 'pcs' };
      return OLD_ALIASES[raw] || raw;
    };
    const oldDisplay = (code: string): string => (code === 'pcs' ? '件' : code);
    expect(oldDisplay(oldFoldingCanonicalUnitCode('只'))).toBe('件');
    expect(oldDisplay(oldFoldingCanonicalUnitCode('只'))).not.toBe('只');
    // 与之相对, 当前实现在同一个输入上给出正确答案
    expect(displayUnit(canonicalUnitCode('只'))).toBe('只');
  });
});
