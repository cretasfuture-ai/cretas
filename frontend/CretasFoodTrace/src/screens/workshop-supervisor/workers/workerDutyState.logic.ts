/**
 * 在岗状态的判定 —— 单独抽出来是为了**能被行为断言钉住**。
 *
 * 起因: 回归闸里那条「考勤拿不到时不许把所有人显示成离岗」原先是源码字面断言
 * (扫 `attendanceAvailable` 这几个词)。变异对照当场证明它守的是字面不是行为 ——
 * 把 `attendanceAvailable ? dutyOf(record) : undefined` 改成 `dutyOf(record)`,
 * 那几个词一个不少地还在文件里，闸纹丝不动。(本仓形态 C‴)
 *
 * ⇒ 判定逻辑放进纯函数，闸直接调它、断言返回值。
 *
 * 核心区分（CLAUDE.md 核心原则 1 的一个具体形态）:
 *   考勤接口挂了        -> undefined  「我不知道这个人在不在岗」
 *   考勤接口好的、没打卡 -> 'not_clocked_in'  「他今天确实没打卡」
 * 这两件事对看屏幕的车间主管完全不同，⛔ 不许折叠成同一个显示。
 *
 * 另: 「未打卡」也**不等于**请假或缺勤。考勤表上这三者是同一个长相(没有记录)，
 * 后端没有能区分它们的字段 —— 数据里不存在的区分，写多少代码都造不出来。
 */

/** 有考勤数据时的三态。⛔ 没有第四态叫「请假」——那需要一个后端没有的数据源。 */
export type DutyState = 'on_duty' | 'clocked_out' | 'not_clocked_in';

/** 判定所需的最小打卡记录形状（后端 TimeClockRecord 的子集）。 */
export interface DutyClockRecord {
  clockInTime?: string | null;
  clockOutTime?: string | null;
}

/** 有记录时的三态判定。 */
export function dutyOf(record: DutyClockRecord | undefined | null): DutyState {
  if (!record || !record.clockInTime) return 'not_clocked_in';
  return record.clockOutTime ? 'clocked_out' : 'on_duty';
}

/**
 * 屏幕真正用的那个 —— 把「考勤数据可不可用」这一维带进来。
 *
 * @param record             这个人今天的打卡记录，没有就是 undefined
 * @param attendanceAvailable 考勤接口这一轮**是否成功返回**（不是「有没有记录」）
 * @returns 考勤不可用时返回 undefined，调用方必须渲染成「未知」而不是任何具体状态
 */
export function resolveDuty(
  record: DutyClockRecord | undefined | null,
  attendanceAvailable: boolean,
): DutyState | undefined {
  if (!attendanceAvailable) return undefined;
  return dutyOf(record);
}
