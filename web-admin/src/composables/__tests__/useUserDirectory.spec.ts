import { describe, expect, it, vi, beforeEach } from 'vitest';

/**
 * 缺陷 5 (2026-09-01, LIUSHANMEN 生产走查): 请购单三个页面把请购人渲染成 `#1638`
 * —— 一个数据库主键, 采购主管看不出是谁提的单。
 *
 * <h2>这个测试守的是「回退口径」, 不是「能不能查到名字」</h2>
 * 查得到名字是显而易见的正路; 真正容易改坏的是**查不到的时候显示什么**。
 *
 * ▎`GET /users/directory` **只返回在职用户** ⇒ 离职/停用的请购人必然查不到。
 * ▎此时必须回退 `#id`, ⛔ 绝不能回退成空字符串。
 *
 * 「查不到这个人」和「这一格没有值」是两件事。回退成空白会把前者伪装成后者 ——
 * 正是本仓 `measurement-and-wiring.md` 形态 A¹⁰ 说的
 * 「兜底的默认值会把『我不知道』翻译成一个假读数」。
 * 空白会让人以为这单没有请购人, 而事实是这单有请购人、只是他离职了。
 */

const getMock = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => getMock(...args),
}));

const { useUserDirectory } = await import('../useUserDirectory');

describe('useUserDirectory —— 人名解析与回退口径', () => {
  beforeEach(() => {
    getMock.mockReset();
  });

  it('走的是只读名录端点, ⛔ 不是要 hr 权限的 GET /users', async () => {
    getMock.mockResolvedValue({ success: true, data: [] });
    const { load } = useUserDirectory();
    await load('LIUSHANMEN');

    expect(getMock).toHaveBeenCalledTimes(1);
    const calledUrl = String(getMock.mock.calls[0][0]);
    expect(calledUrl).toBe('/LIUSHANMEN/users/directory');
    // 阴性对照: 打到 /users 就是把全厂通讯录(含手机邮箱)拉下来, 且需要 hr 权限。
    expect(calledUrl).not.toMatch(/\/users$/);
  });

  it('查得到 → 显示姓名 (fullName 优先)', async () => {
    getMock.mockResolvedValue({
      success: true,
      data: [{ id: 1638, fullName: '张三', username: 'lsm003' }],
    });
    const { load, personLabel } = useUserDirectory();
    await load('LIUSHANMEN');

    expect(personLabel(1638)).toBe('张三');
    // id 在响应里是 number、在单据上可能是 string —— 两边都要能对上。
    expect(personLabel('1638')).toBe('张三');
  });

  it('没有 fullName 时退到 username, ⛔ 不直接跳到 #id', async () => {
    getMock.mockResolvedValue({
      success: true,
      data: [{ id: 7, fullName: null, username: 'lsm009' }],
    });
    const { load, personLabel } = useUserDirectory();
    await load('LIUSHANMEN');

    expect(personLabel(7)).toBe('lsm009');
  });

  it('🔴 查不到 (离职/停用) → 回退 `#id`, ⛔ 不是空白', async () => {
    getMock.mockResolvedValue({
      success: true,
      data: [{ id: 1, fullName: '在职的人' }],
    });
    const { load, personLabel } = useUserDirectory();
    await load('LIUSHANMEN');

    const label = personLabel(1638);
    expect(label).toBe('#1638');
    // 阴性对照 —— 这三条才是这个测试真正在守的东西:
    // 回退成空白 / 空格 / '-' 都会把「查不到这个人」伪装成「这一格没有值」。
    expect(label).not.toBe('');
    expect(label.trim()).not.toBe('');
    expect(label).not.toBe('-');
  });

  it('🔴 名录请求失败时也回退 `#id`, ⛔ 不是空白', async () => {
    getMock.mockRejectedValue(new Error('network down'));
    const { load, personLabel } = useUserDirectory();
    await load('LIUSHANMEN'); // 不抛, 由 request 拦截器提示

    expect(personLabel(1638)).toBe('#1638');
  });

  it('真的没有请购人 (null/undefined/空串) 才显示 `-`', async () => {
    getMock.mockResolvedValue({ success: true, data: [] });
    const { load, personLabel } = useUserDirectory();
    await load('LIUSHANMEN');

    // 这一组与上面那组是**相反**的语义, 所以读数必须不同。
    expect(personLabel(null)).toBe('-');
    expect(personLabel(undefined)).toBe('-');
    expect(personLabel('')).toBe('-');
  });

  it('factoryId 为空时不发请求 (登录态还没就绪)', async () => {
    const { load } = useUserDirectory();
    await load('');
    await load(null);
    await load(undefined);

    expect(getMock).not.toHaveBeenCalled();
  });
});
