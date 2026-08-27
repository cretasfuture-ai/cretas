import { currentShanghaiIsoDate } from '../../../utils/shanghaiDate';

/**
 * 钉住「上海日历日」不随设备时区漂。
 *
 * 缺陷长相(2026-08-27 从 CI 换机器时炸出来的):
 *   `formatDate(new Date(Date.now() + 8h))` —— 先加 8 小时, 再按**本机**分量读日历日。
 *   设备恰好在 UTC 时两步刚好抵消, 所以它在托管 runner(UTC) 上一直是绿的;
 *   真实用户在 UTC+8, 等于 UTC+16 ⇒ 每天上海 16:00 之后返回**明天**。
 *
 * ⚠️ 这条测试在 **UTC 机器上是恒真式** —— 坏掉的那版在 UTC 上本来就是对的。
 *    它能开火的前提是 CI 跑在非 UTC 时区。仓库的两道前端闸已钉 `TZ: Asia/Shanghai`
 *    (与 prod 全线 GMT+8 一致), 在那个时区下, 下面第一组断言对旧实现是红的。
 *    ⇒ 谁把 CI 的 TZ 改回 UTC, 就等于把这条测试关掉了。
 */

// 上海时间的某个瞬间 -> 对应的 UTC 毫秒。上海无夏令时, 固定 -8h。
const atShanghai = (iso: string): number => Date.parse(`${iso}+08:00`);

describe('currentShanghaiIsoDate', () => {
  it('16:00 那个边界不许把日历日推到明天 (旧实现正是在这里翻车)', () => {
    // 月末最后一天的下午/晚上 —— 旧实现会给出 2026-09-01, 于是
    // AIChatScreen.parseRestaurantAgentLaunch 的 currentMonth 跟着跳月,
    // `startDate !== ${currentMonth}-01` 恒成立 ⇒ 毛利归因运行卡静默不渲染。
    expect(currentShanghaiIsoDate(atShanghai('2026-08-31T15:59:00'))).toBe('2026-08-31');
    expect(currentShanghaiIsoDate(atShanghai('2026-08-31T16:00:00'))).toBe('2026-08-31');
    expect(currentShanghaiIsoDate(atShanghai('2026-08-31T23:59:59'))).toBe('2026-08-31');
    // 真·跨天: 上海 00:00 才是新的一天
    expect(currentShanghaiIsoDate(atShanghai('2026-09-01T00:00:00'))).toBe('2026-09-01');
  });

  it('UTC 那一侧的跨天也要对 —— 上海 08:00 之前 UTC 还是昨天', () => {
    // 2026-08-27T00:30+08:00 == 2026-08-26T16:30Z
    expect(currentShanghaiIsoDate(atShanghai('2026-08-27T00:30:00'))).toBe('2026-08-27');
    // 跨年
    expect(currentShanghaiIsoDate(atShanghai('2027-01-01T00:00:00'))).toBe('2027-01-01');
    expect(currentShanghaiIsoDate(atShanghai('2026-12-31T23:59:00'))).toBe('2026-12-31');
  });

  it('与「本机分量」无关: 逐小时扫一整天, 每一小时都等于按 UTC+8 算出来的那一天', () => {
    // 独立算法(只用 Date.UTC 分量, 不碰本机时区), 与被测实现不共用代码路径。
    const expected = (ms: number): string => {
      const d = new Date(ms + 8 * 60 * 60 * 1000);
      const p = (n: number) => String(n).padStart(2, '0');
      return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`;
    };
    const base = Date.UTC(2026, 7, 31, 0, 0, 0); // 2026-08-31T00:00Z
    const seen = new Set<string>();
    for (let h = 0; h < 48; h += 1) {
      const ms = base + h * 60 * 60 * 1000;
      expect(currentShanghaiIsoDate(ms)).toBe(expected(ms));
      seen.add(currentShanghaiIsoDate(ms));
    }
    // 阳性对照: 48 小时必须真的跨过日期, 否则上面 48 次断言可能是同一个样本跑了 48 遍。
    expect(seen.size).toBeGreaterThanOrEqual(3);
  });

  it('不传参数时读当前时钟 (默认参数没写反)', () => {
    const now = Date.now();
    expect(currentShanghaiIsoDate()).toBe(currentShanghaiIsoDate(now));
    expect(currentShanghaiIsoDate()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
