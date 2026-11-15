/**
 * 「这一行的可用量里，有多少是被占着的」——库存列表那一列的说明文字。
 *
 * ## 为什么需要它
 *
 * `MaterialBatch.getCurrentQuantity()` 的口径是 `收货量 − 已用 − 已占用`。
 * 在 2026-08-19 之前，`reserved_quantity` **全库恒为 0**（prod 实测 65 个在用批次
 * 0 个有值）—— 没有任何代码在写它，所以那一列等价于 `收货量 − 已用`。
 *
 * 「报工即占用」上线之后，**已提交未结单**的报工会给对应车间批次写上占用量。
 * 于是同一列会**变小**，而界面上没有任何东西解释它为什么变小。
 *
 * ▎仓管看到的是「库存凭空少了」。
 *
 * 本仓已经为「一个会误报/不解释的提示比没有更糟」付过账（形态 D′）：
 * 数字变了却不说为什么，烧掉的是用户对整个库存数的信任。
 *
 * ## 口径
 *
 * ⛔ 不做「读不到就当 0」的兜底判断之外的任何加工：这里的 0 是**合法读数**
 * （没有占用就是没有占用），与「我不知道」不同——后者返回 `null`，调用方不显示这一行。
 */

/** 库存行上与占用有关的那几个字段（其余字段与本模块无关）。 */
export interface ReservedFlagged {
  reservedQuantity?: unknown;
  quantityUnit?: unknown;
  unit?: unknown;
}

/**
 * 取这一行的占用量。
 *
 * @returns 占用量（≥ 0）；字段缺失/不是数字时返回 `null`
 *          ——⛔ 不返回 0，「没有这个字段」和「占用量是 0」是两件事，
 *          把前者写成 0 就等于替后端断言「这批没被占用」。
 */
export function reservedQuantityOf(row: ReservedFlagged | null | undefined): number | null {
  if (row === null || row === undefined) return null;
  const raw = row.reservedQuantity;
  if (raw === null || raw === undefined || raw === '') return null;
  const value = typeof raw === 'number' ? raw : Number(raw);
  if (!Number.isFinite(value)) return null;
  return value < 0 ? null : value;
}

/**
 * 列表那一列要不要显示占用说明。
 *
 * 只有**确实被占着**（> 0）才显示——占用量为 0 时多印一行「被占用 0」是噪音，
 * 而噪音正是让人以后忽略这行字的原因（形态 E：宁可窄而可信）。
 */
export function shouldShowReservedHold(row: ReservedFlagged | null | undefined): boolean {
  const reserved = reservedQuantityOf(row);
  return reserved !== null && reserved > 0;
}

/**
 * 说明文字本身。
 *
 * ⚠️ 措辞刻意说「未结报工」而不是「其他计划」——占着它的通常正是**本计划自己**
 * 已提交但还没结单的报工。本仓刚因为一句「已被其他未结生产计划占用…请先结清冲突计划」
 * 把人支去找一个不存在的计划而返工过。
 */
export function reservedHoldLabel(row: ReservedFlagged | null | undefined): string {
  const reserved = reservedQuantityOf(row);
  if (reserved === null || reserved <= 0) return '';
  const unitValue = row?.quantityUnit ?? row?.unit;
  const unit = typeof unitValue === 'string' ? unitValue.trim() : '';
  return `另有 ${reserved}${unit} 被未结报工占用`;
}
