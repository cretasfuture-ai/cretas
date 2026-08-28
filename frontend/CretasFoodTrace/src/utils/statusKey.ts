/**
 * 把后端的枚举串归一成前端映射表用的键。
 *
 * <h3>为什么需要它（2026-08-29 逐屏走查实测）</h3>
 *
 * 后端发的是**大写下划线**枚举，而屏上的颜色/文案映射表普遍写成**小写驼峰**键：
 *
 * ```
 * 生产库实测  material_batches.status   = AVAILABLE(106) / DEPLETED(60) / USED_UP(15)
 *             production_batches.status = IN_PROGRESS(57) / COMPLETED(8)
 * 前端映射表  { available, reserved, depleted, expired }
 * ```
 *
 * `map[status]` 于是**全部落空**：文案兜底到 `|| status`（屏上直接显示英文 `AVAILABLE`），
 * 颜色兜底到灰色 —— 原料批次那 181 条的状态徽章**从来没有工作过**。
 *
 * ⛔ 不要把映射表的键改成大写：接口哪天改回小写就又全落空。归一化两边都能吃。
 *
 * ⚠️ 它**不**负责补齐缺失的枚举值（例如 `USED_UP` 当时表里根本没有）——
 * 归一化只保证「拼写对得上」，值全不全要单独核。
 */
export function statusKey(status: string | null | undefined): string {
  const raw = status ?? '';
  // ⚠️ 只在「看起来就是 UPPER_SNAKE 枚举」时才转换。
  //    第一版无条件 `.toLowerCase()` —— 于是已经是驼峰的 `usedUp` 被压成 `usedup`，
  //    接口哪天改发驼峰反而会被这个 helper 弄坏。是它自己的单测抓到的。
  if (!/^[A-Z0-9_]+$/.test(raw)) {
    return raw;
  }
  return raw.toLowerCase().replace(/_(.)/g, (_, c: string) => c.toUpperCase());
}

export default statusKey;
