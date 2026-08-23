/**
 * 登录后 authStore.user.fullName 必须带上姓名。
 *
 * 🔴 为什么要这道闸 (2026-08-22 实测):
 * applyLoginData 构造用户对象时**漏了 fullName**, 而后端 /auth/unified-login
 * 一直在返回 profile.name。于是 authStore.user.fullName 恒为 undefined,
 * 销售订单里 `fullName || username` 每次都落到**登录名**, 并写进
 * sales_orders.salesperson —— F006 该列实测 f006_admin ×16 / f006_sales_mgr ×6
 * / 六膳门销售主管 ×5 / 空 ×7, 同一个人两种写法。
 *
 * ▎这一列是**自由文本**而不是用户 id, 一旦写脏就没法靠 join 修正,
 *   所以闸钉在「登录时姓名有没有接上」这个最上游的点。
 *
 * ⚠️ 第一版这个测试是**恒真式**: 它调 `store.applyLoginData?.(...)`,
 *    而 applyLoginData **没有从 store 导出** ⇒ 可选调用静默空跑, 两条断言无论如何都绿。
 *    ⇒ 改成走**公开入口** login() + mock 掉 API, 让变异真能打进来。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const loginApiMock = vi.fn();

vi.mock('@/api/auth', () => ({
  login: (...args: unknown[]) => loginApiMock(...args),
  demoLogin: vi.fn(),
  logout: vi.fn(),
  getCurrentUser: vi.fn(),
}));

import { useAuthStore } from '../auth';

const BASE = {
  token: 'test-token',
  userId: 1556,
  username: 'f006_sales_mgr',
  factoryId: 'F006',
  factoryName: '六膳门食品科技',
  factoryType: 'FACTORY',
  role: 'sales_manager',
  permissions: [],
};

describe('authStore 登录态携带姓名', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    localStorage.clear();
    loginApiMock.mockReset();
  });

  it('响应带 profile.name 时, user.fullName 是姓名而不是登录名', async () => {
    loginApiMock.mockResolvedValue({
      success: true,
      data: { ...BASE, profile: { name: '六膳门销售主管', department: '销售部' } },
    });
    const store = useAuthStore();
    const ok = await store.login('f006_sales_mgr', 'x');

    // ⚠️ 阳性对照: 登录这一步本身必须真的成功 —— 否则下面两条断言在
    //    「mock 没生效 / login 早退」时也会因为 user 为 null 而给出误导性结果。
    expect(ok).toBe(true);
    expect(store.user).not.toBeNull();

    expect(store.user?.fullName).toBe('六膳门销售主管');
    // ⚠️ 阴性对照: 必须**不等于**登录名 —— 否则 fullName 再次丢失、
    //    下游回落到 username 时, 上面那条断言换成 username 也能绿。
    expect(store.user?.fullName).not.toBe('f006_sales_mgr');
  });

  it('响应没有 profile 时, fullName 落成空串(让下游能回落到用户名)', async () => {
    loginApiMock.mockResolvedValue({ success: true, data: { ...BASE } });
    const store = useAuthStore();
    await store.login('f006_sales_mgr', 'x');

    expect(store.user).not.toBeNull();
    // ⛔ 必须是空串: 下游普遍写 `fullName || username`, 真没名字时要能回落
    expect(store.user?.fullName).toBe('');
  });
});
