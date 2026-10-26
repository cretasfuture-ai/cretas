/**
 * 缺口 06「汇合配比校验」—— App 报工领料时的实时配比核对。
 *
 * 口径与后端 `service/workflow/BlendRatioCheck` (以及 web-admin processSheet/blendRatio.ts) 一致,
 * 后端是最终裁决, 这里只负责「提交前就看得见」(防呆 Rule 1):
 * - 只有份额 > 0 的原料参与; 没份额的不进分子分母;
 * - 配方份额是相对量, 按**本次实际领了的**带份额原料归一化;
 * - 实际领了的带份额原料 < 2 种 ⇒ 不是汇合, 不校验;
 * - |实际% − 配方%| > 2 个百分点 ⇒ 超差, 必须选原因 (「其他」需写说明);
 * - 同一单位直接按数量比; 质量单位之间折成克; 折不了 ⇒ 不校验 (后端同样跳过, 不拦报工)。
 *
 * App 报工的领料是「批次 + 用量」, 一个原料可能分几批领 —— 按原料类型 (= 端口 skuId) 汇总。
 */

export const BLEND_TOLERANCE_PP = 2;
export const BLEND_REASON_OTHER = '其他';
/** 与后端 BlendRatioCheck.STANDARD_REASONS 同一份, 改一边要改另一边。 */
export const BLEND_REASONS = ['原料规格差异', '称重误差', '按主管口头调整', '原料不足', BLEND_REASON_OTHER] as const;

export interface BlendLineInput {
  id: string;
  name: string;
  share: number | null | undefined;
  quantity: number | null | undefined;
  unit: string | null | undefined;
}

export interface BlendLineView {
  id: string;
  name: string;
  recipePercent: number;
  actualPercent: number;
  deviationPp: number;
  over: boolean;
}

export type BlendEvaluation =
  | { status: 'NOT_APPLICABLE' }
  | { status: 'SKIPPED'; reason: string }
  | { status: 'WITHIN' | 'OVER'; tolerancePp: number; maxDeviationPp: number; lines: BlendLineView[] };

/** 领料选择器给出的批次 → 原料类型/名称/份额。 */
export interface BlendMaterialOption {
  materialTypeId: string;
  materialName?: string | null;
  inputShareRatio?: number | null;
}

const GRAMS_PER_MASS_UNIT: Record<string, number> = {
  g: 1, '克': 1, kg: 1000, '千克': 1000, '公斤': 1000, jin: 500, '斤': 500, t: 1_000_000, '吨': 1_000_000,
};

function round6(value: number): number {
  return Math.round(value * 1e6) / 1e6;
}

function normUnit(unit: string | null | undefined): string {
  const u = (unit ?? '').trim();
  if (u === '公斤' || u === '千克' || u.toLowerCase() === 'kg') return 'kg';
  if (u === '克' || u.toLowerCase() === 'g') return 'g';
  return u;
}

export function evaluateBlend(inputs: BlendLineInput[], tolerancePp: number = BLEND_TOLERANCE_PP): BlendEvaluation {
  const fed = inputs.filter((l) => l.share != null && l.share > 0 && l.quantity != null && l.quantity > 0);
  const ids = [...new Set(fed.map((l) => l.id))];
  if (ids.length < 2) return { status: 'NOT_APPLICABLE' };

  const first = normUnit(fed[0]?.unit);
  const uniform = first !== '' && fed.every((l) => normUnit(l.unit) === first);
  const qtyById = new Map<string, number>();
  for (const l of fed) {
    let qty = l.quantity as number;
    if (!uniform) {
      const factor = GRAMS_PER_MASS_UNIT[normUnit(l.unit)];
      if (factor == null) {
        return { status: 'SKIPPED', reason: `「${l.name}」的单位 ${l.unit || '(空)'} 无法与其他原料统一折算，本次不做配比核对` };
      }
      qty *= factor;
    }
    qtyById.set(l.id, (qtyById.get(l.id) ?? 0) + qty);
  }
  const shareById = new Map<string, number>();
  const nameById = new Map<string, string>();
  for (const l of fed) {
    if (!shareById.has(l.id)) shareById.set(l.id, l.share as number);
    if (!nameById.has(l.id)) nameById.set(l.id, l.name);
  }
  const totalQty = [...qtyById.values()].reduce((a, b) => a + b, 0);
  const totalShare = [...shareById.values()].reduce((a, b) => a + b, 0);
  if (!(totalQty > 0) || !(totalShare > 0)) return { status: 'NOT_APPLICABLE' };

  let maxDeviationPp = 0;
  const lines = ids.map((id): BlendLineView => {
    const recipePercent = round6(((shareById.get(id) as number) * 100) / totalShare);
    const actualPercent = round6(((qtyById.get(id) as number) * 100) / totalQty);
    const deviationPp = round6(actualPercent - recipePercent);
    maxDeviationPp = Math.max(maxDeviationPp, Math.abs(deviationPp));
    return { id, name: nameById.get(id) || id, recipePercent, actualPercent, deviationPp, over: Math.abs(deviationPp) > tolerancePp };
  });
  return { status: lines.some((l) => l.over) ? 'OVER' : 'WITHIN', tolerancePp, maxDeviationPp, lines };
}

/** 领料批次引用 → 按原料类型汇总的配比输入。不认识的批次 (选项还没加载) 跳过。 */
export function blendInputsFromRefs(
  refs: ReadonlyArray<{ materialBatchId: string; quantity: number; unit?: string }>,
  optionsByBatchId: ReadonlyMap<string, BlendMaterialOption>,
  fallbackUnit: string,
): BlendLineInput[] {
  const out: BlendLineInput[] = [];
  for (const ref of refs) {
    const option = optionsByBatchId.get(ref.materialBatchId);
    if (!option) continue;
    out.push({
      id: option.materialTypeId,
      name: option.materialName || option.materialTypeId,
      share: option.inputShareRatio ?? null,
      quantity: ref.quantity,
      unit: ref.unit || fallbackUnit,
    });
  }
  return out;
}

/** 提交前的拦截文案; null = 不拦。 */
export function blendReasonBlockedReason(evaluation: BlendEvaluation, reason: string, note: string): string | null {
  if (evaluation.status !== 'OVER') return null;
  const over = evaluation.lines.filter((l) => l.over).map((l) => `${l.name} ${formatPp(l.deviationPp)}`).join('、');
  if (!reason) return `原料配比超出 ±${evaluation.tolerancePp} 个百分点（${over}），请在「配比核对」里选一个原因`;
  if (reason === BLEND_REASON_OTHER && !note.trim()) return '原因选了「其他」，请写一句具体原因';
  return null;
}

/** 请求体里的 blendRatio: 只在超差且选了原因时发 (其余由后端计算)。 */
export function blendRatioPayload(
  evaluation: BlendEvaluation,
  reason: string,
  note: string,
): { reason: string; note?: string } | undefined {
  if (evaluation.status !== 'OVER' || !reason) return undefined;
  return note.trim() ? { reason, note: note.trim() } : { reason };
}

export function formatPercent(value: number): string {
  return `${Number(value.toFixed(1))}%`;
}

export function formatPp(value: number): string {
  const v = Number(value.toFixed(1));
  return `${v > 0 ? '+' : ''}${v} 个百分点`;
}
