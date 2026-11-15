import { displayUnit } from '@/utils/unitPricing';

/**
 * 盲盘（blind stocktake）录入契约。
 *
 * Steve 2026-08-25 拍板的形状:
 *
 *     录入这一行时:  只有 品名 + 大字输入框 + [提交]      ← 看不到账面数
 *          ↓ 点提交
 *     立刻显示:      账面 135 / 实盘 120 / 差异 -15 🔴   ← 该行锁定, 不可再改
 *          ↓
 *     下一行
 *
 * 两条不能动的约束:
 *   1. **提交前看不到账面数** —— 否则实盘数会被账面数锚定, 盘点等于抄一遍系统。
 *   2. **提交后锁定该行** —— 否则"看到差异再回头改"就把盲盘退化成了明盘,
 *      反馈闭环反而成了作弊通道。
 *
 * 反馈闭环本身**保留**: SP7 §7 的用户画像("仓管员年纪偏大、文化素质参差",
 * 客户原话)决定了"填完立刻知道对不对"这个设计有真实依据 —— 我们只是把它
 * 从**填之前**挪到了**填之后**。
 *
 * ⚠️ 这里藏起来的只是**界面**。`StocktakeController` 返回的是裸 JPA 实体,
 *    `systemQty` 仍然在 HTTP 响应体里。**前端藏起来是 UX 纪律, 不是防舞弊** ——
 *    真正的抑制需要后端加 DTO 层, 那是独立的后续项。
 */

/**
 * 盲盘只关心这几个字段。原料盘点和半成品盘点的行结构不同, 但盲盘纪律是同一条 ——
 * 所以出口函数收窄到这个最小接口, 两边共用一份实现。
 * ⛔ 不要在半成品那边复制一份 —— 同一个东西有两份, 它一定会漂。
 */
export interface BlindCountRow {
  /**
   * 账面数量。
   * ⛔ 不要在模板里直接读它 —— 一律走 `revealedSystemQty(row)`,
   *    否则未提交的行会把账面数漏给录入人。
   */
  systemQty: number;
  actualQty: number | null;
  /** 本行是否已提交。提交 = 落库 + 锁定, 两件事同时发生, 不可分。 */
  committed: boolean;
  /**
   * 服务端算好的差异 (`FactoryStocktakeItem.differenceQty`)。未提交时为 null。
   * ⛔ 前端不再自己减 —— 见 `revealedDifference` 的说明。
   */
  differenceQty: number | null;
  /** 服务端给的 SURPLUS / SHORTAGE / MATCH。未提交时为 null。 */
  differenceType: string | null;
}

export interface StocktakeCountRow extends BlindCountRow {
  id: string;
  batchId: string;
  batchNo: string;
  materialCode: string;
  materialName: string;
  unit: string;
  notes: string;
}

/** 服务端 item 的字段子集, 用于提交后回填 —— 差异只认服务端算的那一份。 */
export interface ServerCountedItem {
  actualQty: number | null;
  differenceQty: number | null;
  differenceType: string | null;
}

export function countDisplayUnit(unit: string | null | undefined): string {
  return displayUnit(unit || '') || '-';
}

/**
 * 未盘点 = **未提交**, 不是"输入框空着"。
 * 打了字但没点提交的行不算数 —— 它还没落库, 也还没锁定。
 */
export function countUncountedRows(rows: BlindCountRow[]): number {
  return rows.filter((row) => !row.committed).length;
}

/** 提交后锁定。锁定是盲盘的必要条件, 不是可选的体验优化。 */
export function isRowLocked(row: BlindCountRow): boolean {
  return row.committed;
}

/**
 * 账面数量的**唯一**出口。未提交 → null（界面上不许出现任何账面数字）。
 *
 * 返回 null 而不是 0: 0 是账面数量的合法值, 拿它当"看不到"的哨兵,
 * 下游就分不清"这行账面就是 0"和"这行还没提交"。
 */
export function revealedSystemQty(row: BlindCountRow): number | null {
  return row.committed ? row.systemQty : null;
}

/**
 * 差异的**唯一**出口, 取服务端算好的值。
 *
 * ⛔ 不要写成 `row.differenceQty ?? (row.actualQty - row.systemQty)`。
 *    那个兜底会把"服务端没算/没返回"翻译成一个**看起来像真的**的数字,
 *    而这两件事对审批人完全不同。读不到就显示"—", 让它可见地缺失。
 */
export function revealedDifference(row: BlindCountRow): number | null {
  return row.committed ? row.differenceQty : null;
}

/** 差异方向, 同样只认服务端。null = 未提交或服务端没给。 */
export function revealedDifferenceType(row: BlindCountRow): string | null {
  return row.committed ? row.differenceType : null;
}

/**
 * 提交成功后, 用服务端返回的那一行回填并锁定。
 * 只有这条路径能把 `committed` 置为 true —— 本地输入永远不能自称已提交。
 */
export function commitRowFromServer<T extends BlindCountRow>(
  row: T,
  serverItem: ServerCountedItem,
): T {
  return {
    ...row,
    actualQty: serverItem.actualQty,
    differenceQty: serverItem.differenceQty,
    differenceType: serverItem.differenceType,
    committed: true,
  };
}

export function nextCountInputIndex(currentIndex: number, rowCount: number): number | null {
  return currentIndex >= 0 && currentIndex + 1 < rowCount ? currentIndex + 1 : null;
}
