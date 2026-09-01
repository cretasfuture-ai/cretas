import { describe, expect, it } from 'vitest';
import { isPathAllowedForRole } from '../guards';

/**
 * 缺陷 9 (2026-09-01, LIUSHANMEN 生产走查): 采购订单详情上「查看财务详情」和
 * 「前往仓储收货任务」两个按钮对采购主管是**死链** —— 点了整页跳 `/403`,
 * 上下文全丢, 只能「返回上页」。
 *
 * 修法是按「点下去会不会 403」隐藏按钮。而路由守卫的 403 是**两道闸串联**:
 *   ① 角色路径白名单 (本函数)
 *   ② 模块闸 `permissionStore.canAccess(meta.module)`
 *
 * <h2>这个测试守的是「①不能被漏掉」</h2>
 * 最容易写错的实现是在组件里只写一句 `canAccess('finance')` 就算数 ——
 * 那只覆盖了 ②。下面 `finance_manager` 那两条用例正是**反例存在的证明**:
 * 它的白名单收了 `/warehouse/stocktakes` 却**没有** `/warehouse/materials`,
 * 所以哪怕模块权限通, 路径闸照样把它挡在 `/warehouse/materials` 外面。
 *
 * ⇒ 只判 ② 会把按钮显示给一个点了必然 403 的角色 —— 缺陷 9 原样复发。
 *
 * ⚠️ 判定必须与 `beforeEach` **同源**(它现在调的就是本函数)。⛔ 不要在组件里
 * 另抄一份前缀匹配逻辑 —— 那是形态 D「同一个判定有两份, 它一定会漂」。
 */
describe('isPathAllowedForRole —— 角色路径白名单 (403 的第一道闸)', () => {
  describe('不在白名单表里的角色: 这道闸不约束它 (由模块闸单独裁决)', () => {
    it('采购主管去任何路径, 本闸都放行', () => {
      expect(isPathAllowedForRole('procurement_manager', '/finance/ar-ap')).toBe(true);
      expect(isPathAllowedForRole('procurement_manager', '/warehouse/materials')).toBe(true);
      expect(isPathAllowedForRole('procurement_manager', '/procurement/orders')).toBe(true);
    });

    it('⚠️ 所以采购主管的 403 来自**模块闸**, 不是本闸 —— 两道都要判', () => {
      // 这条用例本身不断言权限, 它记录的是「本闸放行 ≠ 进得去」这个事实,
      // 免得下一个人看到上面全是 true 就以为采购主管能进财务页。
      expect(isPathAllowedForRole('procurement_manager', '/finance/ar-ap')).toBe(true);
    });
  });

  describe('finance_manager: 在白名单表里, 本闸真的会拦', () => {
    it('白名单内的前缀放行', () => {
      expect(isPathAllowedForRole('finance_manager', '/finance/ar-ap')).toBe(true);
      expect(isPathAllowedForRole('finance_manager', '/warehouse/stocktakes')).toBe(true);
      expect(isPathAllowedForRole('finance_manager', '/workflow/my-created')).toBe(true);
    });

    it('🔴 /warehouse/materials 不在白名单 ⇒ 拦下 (即使模块权限可能是通的)', () => {
      // 这就是「只判 canAccess 会错」的那个反例。
      expect(isPathAllowedForRole('finance_manager', '/warehouse/materials')).toBe(false);
    });

    it('白名单外的路径一律拦下', () => {
      expect(isPathAllowedForRole('finance_manager', '/procurement/orders')).toBe(false);
      expect(isPathAllowedForRole('finance_manager', '/system/users')).toBe(false);
    });
  });

  describe('前缀匹配的边界: 按路径段匹配, ⛔ 不是裸字符串 startsWith', () => {
    it('精确等于前缀本身 → 放行', () => {
      expect(isPathAllowedForRole('finance_manager', '/finance')).toBe(true);
    });

    it('前缀 + "/" 才算子路径 → 放行', () => {
      expect(isPathAllowedForRole('finance_manager', '/finance/ar-ap/detail')).toBe(true);
    });

    it('🔴 同前缀开头但不是子路径 → 拦下 ("/financexxx" ⊄ "/finance")', () => {
      // 裸 startsWith('/finance') 会把 /financexxx 放行 —— 这条钉住那个坑。
      expect(isPathAllowedForRole('finance_manager', '/financexxx')).toBe(false);
      expect(isPathAllowedForRole('finance_manager', '/warehouse/stocktakes-archive')).toBe(false);
    });
  });
});
