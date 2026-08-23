/**
 * 发货确认 — 纯逻辑 (无 RN 依赖, 便于单测).
 * 防呆 Rule 1: 实发数量不得超过计划数量 / 不得为负 / 不得为空.
 */

/** 校验单行实发数量. 返回错误文案 (null = 合法). */
export function validateQty(text: string, plannedQty: number): string | null {
  const trimmed = (text ?? '').trim();
  if (trimmed === '') return '不能为空';
  const n = Number(trimmed);
  if (!Number.isFinite(n)) return '请输入数字';
  if (n < 0) return '不能为负';
  if (n > plannedQty) return `不能超过计划 ${plannedQty}`;
  return null;
}

export interface QtyRow {
  id: string;
  plannedQty: number;
  actualQtyText: string;
}

/** 全部行是否可提交 (任一非法则不可提交). */
export function canSubmitRows(rows: QtyRow[]): boolean {
  if (rows.length === 0) return false;
  return rows.every((r) => validateQty(r.actualQtyText, r.plannedQty) == null);
}

/**
 * 系统现在凑不凑得够 —— 返回缺口，凑得够时返回 `undefined`。
 *
 * 🔴 2026-08-21: 这个判断原来**根本不存在**。进屏就调了 `recommendFifo` 拿到推荐批次，
 * 够不够那一刻就在手里，但代码只拿它显示「该搬哪一批」；结果仓管仍然是
 * **填完点了才被后端 409 挡回来**，只是挡回来之后多了条「去调拨」。
 * ⇒ 事实在手里就该事先说（防呆 Rule 1：预先显示边界，不要事后报错）。
 *
 * ⚠️ 抽成纯函数不是为了整洁，是因为**源码闸量不到这件事**：
 * 实测把 `shortfallQty: shortfall > 0 ? shortfall : undefined` 改成
 * `shortfallQty: undefined`（即退回「只显示不预警」），扫源码的那道闸
 * 6 条全绿 —— 因为 `shortfallQty` 这个串和那行 `reduce` 都还在。
 * ▎变异生效了，只是打在了闸看不见的地方（本仓形态 C″）。
 * ⇒ 判据必须落在**行为**上，而行为要可测就得先抽得出来。
 *
 * @param need 本次要发的量
 * @param picks 系统按 FIFO 推荐的批次（qty 之和 = 现在能凑出来的量）
 */
export function shortfallOf(
  need: number,
  picks: { qty: number }[],
): number | undefined {
  if (!Number.isFinite(need) || need <= 0) return undefined;
  const coverable = picks.reduce((sum, p) => sum + (Number(p.qty) || 0), 0);
  const gap = need - coverable;
  // ⚠️ 容差: 浮点相减会留下 1e-15 这种尾巴，没有它会对着「还差约 0.0000000001」报警。
  // ⛔ 一个会误报的提示比没有提示更糟 —— 它烧掉的是信任，
  //    而「敢说不准」这件事的全部本钱就是每一句都站得住。
  return gap > 0.0001 ? gap : undefined;
}

/** 构建 confirm 请求的 actualQuantities map (key = 行 id 字符串). */
export function buildActualQuantities(rows: QtyRow[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const r of rows) {
    if (r.id) out[r.id] = Number(r.actualQtyText);
  }
  return out;
}
