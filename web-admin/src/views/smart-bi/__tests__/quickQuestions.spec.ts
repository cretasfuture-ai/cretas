import { describe, expect, it } from 'vitest';
import { FACTORY_QUICK_QUESTIONS, pickDefaultQuickQuestions } from '../quickQuestions';

const RESTAURANT = ['畅销品 Top 5', '哪家店业绩最好'];

describe('问数据(AI) 默认快捷问题按租户类型', () => {
  it('食品工厂 (非 RESTAURANT) 走默认 domain 时给工厂向, 不含餐饮词', () => {
    for (const t of ['FACTORY', 'MANUFACTURING', undefined, null, '']) {
      const out = pickDefaultQuickQuestions(RESTAURANT, true, t as string | undefined);
      expect(out).toEqual([...FACTORY_QUICK_QUESTIONS]);
      expect(out.join('')).not.toMatch(/畅销品|爆款|门店|菜/);
    }
    expect(FACTORY_QUICK_QUESTIONS).toContain('哪个客户欠款最多');
  });

  it('阴性对照: 餐饮租户保持原餐饮题', () => {
    expect(pickDefaultQuickQuestions(RESTAURANT, true, 'RESTAURANT')).toEqual(RESTAURANT);
  });

  it('阴性对照: 数据集推断出专属 domain 时不被替换 (即使是工厂租户)', () => {
    const inventory = ['库存周转情况'];
    expect(pickDefaultQuickQuestions(inventory, false, 'FACTORY')).toEqual(inventory);
  });
});
