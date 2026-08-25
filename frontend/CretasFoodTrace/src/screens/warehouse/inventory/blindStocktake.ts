/**
 * 盲盘（blind stocktake）录入契约 —— RN 仓管端。
 *
 * Steve 2026-08-25 拍板的形状:
 *
 *     录入这一行时:  只有 品名 + 大字输入框 + [提交]      ← 看不到账面数
 *          ↓ 点提交
 *     立刻显示:      账面 135 / 实盘 120 / 差异 -15 🔴   ← 该行锁定, 不可再改
 *          ↓
 *     下一品
 *
 * 为什么原来会显示账面数（背景, 别再"优化"回去）:
 * SP7 §7「UX Flow Analysis」的用户画像是客户原话 ——「做仓管的他年纪都比较大
 * 文化素质很低」。摩擦点 F4 因此要求「盘点表要有账面数字回显 + 实时红绿差异」。
 * 那个理由是真的, ⛔ 所以不能简单删掉反馈了事 —— 只是把它从**填之前**挪到
 * **填之后**: 填完立刻知道对不对, 但那一行的独立性保住了。
 *
 * 锁定是**必须**的: 看到差异再回头改, 等于没盲。
 *
 * ⚠️ 这里藏起来的只是**界面**。`StocktakeController` 返回的是裸 JPA 实体,
 *    `systemQty` 仍然在 HTTP 响应体里。**前端藏起来是 UX 纪律, 不是防舞弊。**
 */

export interface BlindEntryRow {
  /** 本行是否已提交。提交 = 落库 + 锁定 + 揭示, 三件事同时发生, 不可分。 */
  committed: boolean;
  /**
   * 账面数量。
   * ⛔ 不要在 JSX 里直接读它 —— 一律走 `revealedSystemQty(row)`。
   */
  systemQty: number;
  actualQty: number | null;
  /**
   * 服务端算好的差异 (`FactoryStocktakeItem.differenceQty`)。
   * ⛔ 前端不再自己减 —— 见 `revealedDifference`。
   */
  differenceQty: number | null;
  /** 服务端给的 SURPLUS / SHORTAGE / MATCH。 */
  differenceType: string | null;
}

/** 提交后锁定 —— 盲盘的必要条件, 不是可选的体验优化。 */
export function isRowLocked(row: BlindEntryRow): boolean {
  return row.committed;
}

/**
 * 账面数量的**唯一**出口。未提交 → null（屏幕上不许出现任何账面数字）。
 *
 * 返回 null 而不是 0: 0 是账面数量的合法值, 拿它当"看不到"的哨兵,
 * 下游就分不清"这行账面就是 0"和"这行还没提交"。
 */
export function revealedSystemQty(row: BlindEntryRow): number | null {
  return row.committed ? row.systemQty : null;
}

/**
 * 差异的**唯一**出口, 取服务端算好的值。
 *
 * ⛔ 不要写成 `row.differenceQty ?? (row.actualQty - row.systemQty)`。
 *    兜底会把"服务端没算/没返回"翻译成一个**看起来像真的**的数字,
 *    而这两件事对审批人完全不同。读不到就显示"—", 让它可见地缺失。
 */
export function revealedDifference(row: BlindEntryRow): number | null {
  return row.committed ? row.differenceQty : null;
}

export function revealedDifferenceType(row: BlindEntryRow): string | null {
  return row.committed ? row.differenceType : null;
}

/** 能不能点「提交」: 填了数、且这一行还没锁。 */
export function canSubmitRow(row: BlindEntryRow): boolean {
  return !row.committed && row.actualQty !== null;
}

export interface BlindCountSummary {
  total: number;
  /** 已提交 */
  committed: number;
  /** 服务端判为账实相符 (差异 = 0) */
  matched: number;
  shortage: number;
  surplus: number;
  /**
   * 已提交但服务端没给出差异 —— 既不是相符也不是差异, 单独一栏。
   * ⛔ 不许并进 `matched`: 那等于把"不知道"报成"没问题"。
   */
  unknown: number;
}

/** 汇总一律基于服务端的 differenceQty; 前端不做任何减法。 */
export function summarizeBlindCount(rows: BlindEntryRow[]): BlindCountSummary {
  const committed = rows.filter((row) => row.committed);
  return {
    total: rows.length,
    committed: committed.length,
    matched: committed.filter((row) => row.differenceQty === 0).length,
    shortage: committed.filter((row) => row.differenceQty !== null && row.differenceQty < 0).length,
    surplus: committed.filter((row) => row.differenceQty !== null && row.differenceQty > 0).length,
    unknown: committed.filter((row) => row.differenceQty === null).length,
  };
}
