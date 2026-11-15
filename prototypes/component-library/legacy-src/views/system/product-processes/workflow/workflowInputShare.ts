/**
 * 主原料份额的推算与校验 —— 画布 / 报工表 / 领料单三处共用同一份口径。
 *
 * owner 2026-09-02: 「哪怕是多个原料, 它都有配比的。30% 跟 70%, 我填入第一个原料 30 吨,
 * 第二个它会自动跳出 70 吨。」
 *
 * 口径(与后端 InputShareRatios 逐字一致):
 * - 份额是**相对量**: 3/7 与 30/70 同解, 推算按「刚填的那个」归一化;
 * - 份额为 null 的行不参与推算(既不被推, 也不当分母);
 * - 刚填的那行没有份额或份额为 0 ⇒ 无法作为基准, **不推**(⛔ 不猜)。
 */
export interface ShareLine {
  id: string;
  share: number | null | undefined;
}

/** 返回 除 changedId 外 每个有份额行应得的量。changedId 无份额/份额 0 ⇒ 空表。 */
export function deriveByShare(lines: ShareLine[], changedId: string, changedQty: number | null): Map<string, number> {
  const out = new Map<string, number>();
  if (changedQty == null || !Number.isFinite(changedQty)) return out;
  const base = lines.find((l) => l.id === changedId)?.share;
  if (base == null || !(base > 0)) return out;
  for (const l of lines) {
    // 份额 0 = 不参与推算(与后端 InputShareRatios 同口径), ⛔ 不推成 0
    if (l.id === changedId || l.share == null || !(l.share > 0)) continue;
    out.set(l.id, Number(((changedQty * l.share) / base).toFixed(6)));
  }
  return out;
}

/** 画布软提示: 已填份额之和 ≠ 100 时给一句话; 没填或只填一个不提示(相对量本来就不要求和为 100)。 */
export function shareSumWarning(shares: Array<number | null | undefined>): string | null {
  const filled = shares.filter((s): s is number => s != null && Number.isFinite(s));
  if (filled.length < 2) return null;
  const sum = filled.reduce((a, b) => a + b, 0);
  if (Math.abs(sum - 100) < 0.005) return null;
  return `已填份额合计 ${Number(sum.toFixed(2))}%，不是 100%（按相对比例推算仍可用）`;
}
