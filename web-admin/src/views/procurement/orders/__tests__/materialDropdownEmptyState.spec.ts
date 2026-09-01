import { describe, expect, it } from 'vitest';
import { resolveMaterialDropdownEmpty } from '../materialDropdownEmptyState';

/**
 * 新建采购订单弹窗「该供应商的原料」下拉的空态（2026-09-01 owner 在 LIUSHANMEN 生产上撞到）。
 *
 * <h2>本组用例最重要的一条在「错误象限」那一节</h2>
 * 旧实现按「有没有搜索词」二分，于是**供应商一条原料都没配**的人一打字，
 * 会被告知「试试原料名关键字或拼音首字母」——**一条不可能成功的指令**。
 *
 * ⚠️ 叫人去做一件做不成的事，比什么都不说更糟：它把人支走做无用功，而且看起来很有把握。
 * 与今晚那批「凭空的 0」同族——界面**确信地**陈述一件假的事。
 *
 * 这条断言编码的是**真实的用户伤害**，也是将来重构里最容易被丢掉的一条。
 */
describe('resolveMaterialDropdownEmpty — 四象限', () => {
  describe('🔴 错误象限：供应商零原料 + 有搜索词', () => {
    const ui = resolveMaterialDropdownEmpty({
      hasSupplier: true,
      relationCount: 0,
      query: '海天',
    });

    it('⛔ 绝不建议换关键字/拼音 —— 那里根本没东西可搜', () => {
      expect(ui.suggestSearchTips, [
        '供应商一条原料都没配，却在建议用户换搜索词。',
        '这是一条不可能成功的指令，会把人支走做无用功。',
        '判定必须先看 relationCount === 0，再看 query。',
      ].join('\n')).toBe(false);
    });

    it('落在 supplier-has-none，⛔ 不是 no-match', () => {
      expect(ui.state).toBe('supplier-has-none');
    });

    it('给出「去配置」的动作 —— 这才是这里唯一走得通的下一步', () => {
      expect(ui.showConfigureAction).toBe(true);
    });

    it('文案本身不含「搜索/关键字/拼音」这类字眼', () => {
      // 防止有人把建议塞回 text 里绕过 suggestSearchTips 这个开关。
      expect(ui.text).not.toMatch(/搜索|关键字|拼音|首字母/);
    });
  });

  describe('供应商有原料 + 有搜索词（真的搜不到）', () => {
    const ui = resolveMaterialDropdownEmpty({
      hasSupplier: true,
      relationCount: 12,
      query: '不存在的料',
    });

    it('这里建议换搜索词是**成立**的 —— 阳性对照', () => {
      // 没有这一条，上面那条「不建议搜索」可能只是因为函数永远返回 false。
      expect(ui.suggestSearchTips).toBe(true);
      expect(ui.state).toBe('no-match');
    });

    it('同时仍给「去配置」—— owner 撞到的正是「有原料但要买的那个没配」', () => {
      expect(ui.showConfigureAction).toBe(true);
    });
  });

  describe('供应商零原料 + 无搜索词', () => {
    const ui = resolveMaterialDropdownEmpty({
      hasSupplier: true,
      relationCount: 0,
      query: '',
    });

    it('与「零原料 + 有搜索词」落在同一态 —— 成因相同就该同一个说法', () => {
      expect(ui.state).toBe('supplier-has-none');
      expect(ui.suggestSearchTips).toBe(false);
      expect(ui.showConfigureAction).toBe(true);
    });

    it('⛔ 文案刻意短：完整说明在上方那个全宽 alert 里，这里不重述', () => {
      // orders/list.vue:1516 已有 el-alert，条件同样是 relations.length === 0。
      // 两处写同一段话 = 形态 D，一定会漂。
      // ⚠️ 下一个人看到这里短、上面长，可能会「顺手补全」—— 这条断言拦住它。
      expect(ui.text.length).toBeLessThan(20);
      expect(ui.text).not.toContain('供应原料');
    });
  });

  describe('还没选供应商', () => {
    const ui = resolveMaterialDropdownEmpty({
      hasSupplier: false,
      relationCount: 0,
      query: '随便打的',
    });

    it('无论有没有搜索词都是 no-supplier —— 供应商没选，其它都无从谈起', () => {
      expect(ui.state).toBe('no-supplier');
      expect(ui.suggestSearchTips).toBe(false);
    });

    it('⛔ 不给「去配置」：下一步就在同一行左边，不需要把人支到别的页面', () => {
      expect(ui.showConfigureAction).toBe(false);
    });
  });

  describe('判定顺序（这是本模块的全部要点）', () => {
    it('relationCount === 0 优先于 query —— 换任何搜索词都不改变结论', () => {
      for (const query of ['', ' ', 'ht', '海天', '13.9kg']) {
        const ui = resolveMaterialDropdownEmpty({ hasSupplier: true, relationCount: 0, query });
        expect(ui.state, `query=${JSON.stringify(query)} 时判错了`).toBe('supplier-has-none');
        expect(ui.suggestSearchTips).toBe(false);
      }
    });

    it('阳性对照：relationCount > 0 时 query 确实会改变结论（证明它没被忽略）', () => {
      const withQuery = resolveMaterialDropdownEmpty({ hasSupplier: true, relationCount: 5, query: 'x' });
      expect(withQuery.suggestSearchTips).toBe(true);
      // 若把 query 一并忽略, 上面那组「顺序」用例同样会绿 —— 这条排除那种恒真。
    });
  });

  describe('四个象限各自可辨（⛔ 不许塌成一个）', () => {
    it('三种 state 两两不同', () => {
      const states = [
        resolveMaterialDropdownEmpty({ hasSupplier: false, relationCount: 0, query: '' }).state,
        resolveMaterialDropdownEmpty({ hasSupplier: true, relationCount: 0, query: '' }).state,
        resolveMaterialDropdownEmpty({ hasSupplier: true, relationCount: 3, query: 'x' }).state,
      ];
      expect(new Set(states).size, `state 塌陷: ${states.join(' / ')}`).toBe(3);
    });

    it('三种文案两两不同', () => {
      const texts = [
        resolveMaterialDropdownEmpty({ hasSupplier: false, relationCount: 0, query: '' }).text,
        resolveMaterialDropdownEmpty({ hasSupplier: true, relationCount: 0, query: '' }).text,
        resolveMaterialDropdownEmpty({ hasSupplier: true, relationCount: 3, query: 'x' }).text,
      ];
      expect(new Set(texts).size, `文案塌陷: ${texts.join(' / ')}`).toBe(3);
    });
  });
});
