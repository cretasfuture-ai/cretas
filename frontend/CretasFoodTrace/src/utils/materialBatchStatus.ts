/**
 * 原料批次状态 → 「这条批次还需不需要仓管处理」的**唯一**映射。
 *
 * 🔴 2026-08-20 建这个文件的原因：同一份映射原来有**两份**，而且**已经漂了**：
 *   · `WHHomeScreen.mapBatchStatus`        —— 认 4 个值，没有 fresh/frozen
 *   · `WHInboundListScreen.mapBatchStatusToInbound` —— 认 6 个值，把 fresh/frozen 判成 pending
 *
 * 后端 `MaterialBatchStatus` 枚举有 **12** 个值，两份都只认了一小半，
 * 其余全部落到 `default → pending` ⇒ **已入库、已用完、已报废的批次统统被算成「入库任务」**。
 * prod 实测：F006 首页「入库任务」里混着 5 条 `USED_UP`（已用完）。
 *
 * ⚠️ 这是 M-11 修完之后**残留的同一个形状** —— 当时补了「已完成的不算待办」的过滤，
 *    但过滤依据的就是这份不全的映射，所以只挡住了它认识的那几个值。
 *
 * ▎判据取自后端枚举**自己的注释**，⛔ 不是我猜的语义。
 */

/** 批次在仓管视角下的处理状态。 */
export type BatchWorkStatus = 'pending' | 'inspecting' | 'completed';

/**
 * 后端 `MaterialBatchStatus` 的全部 12 个取值 → 仓管视角。
 *
 * 分三类，理由逐条对应枚举里的 displayName/description：
 *
 * | 后端值 | 中文 | 归类 | 为什么 |
 * |---|---|---|---|
 * | `IN_STOCK` | 库存中 | completed | 已经在库，入库这件事做完了 |
 * | `AVAILABLE` | 可用 | completed | 同上 |
 * | `FRESH` | 鲜品 | completed | 已入库的品类标记，⛔ 不是待办 |
 * | `FROZEN` | 冻品 | completed | 同上 |
 * | `RESERVED` | 已预留 | completed | 已入库并被预留，⛔ 原来错判成「质检中」 |
 * | `PRODUCING_RESERVED` | 生产预留 | completed | 已被在产批次占用 |
 * | `DEPLETED` | 已耗尽 | completed | 终结态 |
 * | `USED_UP` | 已用完 | completed | 终结态 —— **本次修的就是它** |
 * | `EXPIRED` | 已过期 | completed | 终结态 |
 * | `SCRAPPED` | 已报废 | completed | 终结态 |
 * | `INSPECTING` | 质检中 | inspecting | **真的还在流程里** |
 * | `DEFECTIVE` | 不良品 | pending | 枚举注释写明「需单独处理」⇒ 是个待办 |
 */
export const BATCH_STATUS_WORK_MAP: Record<string, BatchWorkStatus> = {
  IN_STOCK: 'completed',
  AVAILABLE: 'completed',
  FRESH: 'completed',
  FROZEN: 'completed',
  RESERVED: 'completed',
  PRODUCING_RESERVED: 'completed',
  DEPLETED: 'completed',
  USED_UP: 'completed',
  EXPIRED: 'completed',
  SCRAPPED: 'completed',
  INSPECTING: 'inspecting',
  DEFECTIVE: 'pending',
};

/**
 * 把后端批次状态翻成仓管视角。
 *
 * ⚠️ 认不出来的值一律回落 `pending`（**故意的**）：仓管的待办列表宁可多显示一条让人去看，
 * 也不要把一条需要处理的批次**藏起来**。
 * ⛔ 但这不是「随它去」—— `materialBatchStatus.contract.test.ts` 会对着 Java 枚举源文件
 * 断言「每个后端取值都被显式登记」，新增枚举值会让那道闸变红，而不是悄悄变成一条假待办。
 */
export function toBatchWorkStatus(status: string | undefined | null): BatchWorkStatus {
  if (!status) return 'pending';
  return BATCH_STATUS_WORK_MAP[status.trim().toUpperCase()] ?? 'pending';
}

/** 这条批次还需要仓管处理吗（用于「入库任务」计数与列表）。 */
export function isPendingInboundTask(status: string | undefined | null): boolean {
  return toBatchWorkStatus(status) !== 'completed';
}
