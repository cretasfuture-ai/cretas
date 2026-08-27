/**
 * 上海(GMT+8, 无夏令时)的日历日 —— 与设备时区无关。
 *
 * 这里是「固定时区的日历日」这一语义在 RN 侧的唯一定义。它和另外两种日期语义
 * 是三件不同的事, 混用过一次就出过线上缺陷:
 *
 *   1. 设备本地日历日   `formatDate(d)`(读本机分量)  —— 用户眼里的今天
 *   2. UTC 日           时间戳截断                    —— 后端全线 GMT+8, 送它就是错的
 *   3. **上海日历日**   本文件                        —— 服务端口径的「今天」
 */

const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000;

const pad = (n: number): string => String(n).padStart(2, '0');

/**
 * 返回上海时区的日历日 `YYYY-MM-DD`。
 *
 * ⛔ 曾经写成 `formatDate(new Date(Date.now() + 8h))` —— `formatDate` 读的是
 *    **本机**日历分量, 于是「加 8 小时」这一步只在设备恰好处于 UTC 时才凑对。
 *    真实用户在 UTC+8, 两段偏移叠加等于 UTC+16。
 *    2026-08-27 在一台 UTC+8 机器上实测(与用户同偏移):
 *      上海 15:59 -> 2026-08-31 ✅     上海 16:00 -> 2026-09-01 ❌(应为 08-31)
 *    ⇒ 每天上海时间 16:00 之后它返回**明天**。
 *    后果落在 `AIChatScreen.parseRestaurantAgentLaunch`: `currentMonth` 跟着跳月,
 *    于是**每月最后一天 16:00 之后** `startDate !== ${currentMonth}-01` 恒成立,
 *    整张毛利归因运行卡静默不渲染(不报错、不提示)。其余日子则是 `endDate > today`
 *    这道防未来日期的闸被放松一天。
 *
 * 引入点是 **PR#111 的全仓清扫**: 那一轮把 `toISOString().slice(0,10)` 换成
 * 「本地日历日」是对的, 但这一行是**误伤** —— `Date.now() + 8h` 是把 UTC 轴平移
 * 来假装上海时区, 这个技巧成立的前提就是**用 UTC 读回**; 再套一层本地 getter
 * 等于又叠一次设备偏移。
 *
 * 另一侧的实测(#143, 同一台 Mac 只换 TZ, 跑 `AIChatScreenRestaurantAgent`):
 *   `TZ=Asia/Shanghai` 20 passed  ／  `TZ=America/Los_Angeles` 10 failed。
 * ⚠️ 但那是个**依赖当时钟点**的信号 —— 本文件的单测才是钉住 16:00 边界的那个。
 *
 * ✅ 正确做法: 把瞬时推 8 小时之后读 **UTC** 分量。
 * ⚠️ 手写分量而不是截断 ISO 串, 有两个理由: `noUtcDayTruncation` 那道闸禁掉了
 *    截断写法(它守的是上面第 1 种语义不许写成第 2 种), 而且截断写法会让下一个人
 *    把这里读成「取 UTC 日」。
 *
 * @param now 毫秒时间戳。只为可测 —— 时区缺陷无法靠「在本机跑一遍」发现:
 *            本机是 UTC+8 时, 坏掉的那版一天里仍有 16 小时是对的。
 */
export function currentShanghaiIsoDate(now: number = Date.now()): string {
  const shanghai = new Date(now + SHANGHAI_OFFSET_MS);
  return `${shanghai.getUTCFullYear()}-${pad(shanghai.getUTCMonth() + 1)}-${pad(shanghai.getUTCDate())}`;
}
