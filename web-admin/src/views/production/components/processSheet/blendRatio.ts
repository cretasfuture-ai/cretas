/**
 * 缺口 06「汇合配比校验」—— 报工录入时的实时配比核对 (前端预显示边界, 防呆 Rule 1)。
 *
 * 口径与后端 `service/workflow/BlendRatioCheck` 逐条一致 (后端是最终裁决, 这里只负责「提交前就看得见」):
 * - 只有份额 > 0 的端口参与; 没份额的投入(调料等)不进分子分母;
 * - 配方份额是相对量, 按**本次实际投了的**带份额端口归一化 (没选用的替代料不进分母);
 * - 实际投了的带份额端口 < 2 ⇒ 不是汇合, 不校验;
 * - 偏差 = 实际% − 配方%, |偏差| > 容差(默认 2 个百分点) ⇒ 超差, 正式报工前必须选原因;
 * - 单位: 全部同一单位按投料数比 (与「填一个推其余」的预填同一口径); 否则折成克 (计数单位用端口克重);
 *   折不了 ⇒ 不校验 (后端同样跳过并留痕, 不拦报工)。
 */
import { sameUnit } from '@/utils/unitPricing';

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
  gramsPerUnit?: number | null;
  /** 工艺保密: 这个端口的配方份额对当前账号隐藏 (后端仍按真实份额校验)。 */
  shareHidden?: boolean;
}

export interface BlendLineView {
  id: string;
  name: string;
  recipePercent: number;
  actualPercent: number;
  /** 实际 − 配方 (百分点, 带符号)。 */
  deviationPp: number;
  over: boolean;
}

export type BlendEvaluation =
  | { status: 'NOT_APPLICABLE' }
  /** 工艺保密: 份额看不到, 本页不能预先核对; 后端照常校验, 超差会要求原因 —— 原因框要一直给, 否则卡死 (防呆 Rule 5)。 */
  | { status: 'SECRET' }
  | { status: 'SKIPPED'; reason: string }
  | { status: 'WITHIN' | 'OVER'; basis: string; tolerancePp: number; maxDeviationPp: number; lines: BlendLineView[] };

const GRAMS_PER_MASS_UNIT: Record<string, number> = {
  g: 1, '克': 1, kg: 1000, '千克': 1000, '公斤': 1000, jin: 500, '斤': 500, t: 1_000_000, '吨': 1_000_000,
};
/** 有固定换算但不是质量的单位 —— 不能借 SKU 克重 (与后端 FgQuantityUnitConverter 一致)。 */
const NON_MASS_SCIENTIFIC = new Set(['ml', '毫升', 'l', '升', 'mm', '毫米', 'cm', '厘米', 'm', '米', 'km', '千米']);
/** 外箱没有固有净重, 后端需要包装快照才折算 —— 这里同样不折。 */
const PACKAGING_NEEDS_SNAPSHOT = new Set(['箱']);

function round6(value: number): number {
  return Math.round(value * 1e6) / 1e6;
}

function toGrams(quantity: number, unit: string, gramsPerUnit: number | null | undefined): number | null {
  const key = unit.trim();
  const mass = GRAMS_PER_MASS_UNIT[key] ?? GRAMS_PER_MASS_UNIT[key.toLowerCase()];
  if (mass != null) return quantity * mass;
  if (NON_MASS_SCIENTIFIC.has(key.toLowerCase()) || PACKAGING_NEEDS_SNAPSHOT.has(key)) return null;
  if (gramsPerUnit == null || !(gramsPerUnit > 0)) return null;
  return quantity * gramsPerUnit;
}

export function evaluateBlend(inputs: BlendLineInput[], tolerancePp: number = BLEND_TOLERANCE_PP): BlendEvaluation {
  const fedAny = inputs.filter((l) => l.quantity != null && l.quantity > 0
    && (l.shareHidden || (l.share != null && l.share > 0)));
  if (fedAny.some((l) => l.shareHidden) && new Set(fedAny.map((l) => l.id)).size >= 2) {
    return { status: 'SECRET' };
  }
  const fed = inputs.filter((l) => l.share != null && l.share > 0 && l.quantity != null && l.quantity > 0);
  const portIds = [...new Set(fed.map((l) => l.id))];
  if (portIds.length < 2) return { status: 'NOT_APPLICABLE' };

  const firstUnit = fed[0].unit ?? '';
  const uniform = firstUnit !== '' && fed.every((l) => sameUnit(l.unit, firstUnit));
  const qtyById = new Map<string, number>();
  let basis = firstUnit;
  if (uniform) {
    for (const l of fed) qtyById.set(l.id, (qtyById.get(l.id) ?? 0) + (l.quantity as number));
  } else {
    basis = 'g';
    for (const l of fed) {
      const grams = l.unit ? toGrams(l.quantity as number, l.unit, l.gramsPerUnit) : null;
      if (grams == null) {
        return { status: 'SKIPPED', reason: `「${l.name}」的单位 ${l.unit || '(空)'} 无法与其他主料统一折算，本次不做配比核对` };
      }
      qtyById.set(l.id, (qtyById.get(l.id) ?? 0) + grams);
    }
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
  const lines = portIds.map((id): BlendLineView => {
    const recipePercent = round6(((shareById.get(id) as number) * 100) / totalShare);
    const actualPercent = round6(((qtyById.get(id) as number) * 100) / totalQty);
    const deviationPp = round6(actualPercent - recipePercent);
    maxDeviationPp = Math.max(maxDeviationPp, Math.abs(deviationPp));
    return { id, name: nameById.get(id) || id, recipePercent, actualPercent, deviationPp, over: Math.abs(deviationPp) > tolerancePp };
  });
  return {
    status: lines.some((l) => l.over) ? 'OVER' : 'WITHIN',
    basis,
    tolerancePp,
    maxDeviationPp,
    lines,
  };
}

/** 正式报工前的拦截文案; null = 不拦。草稿不调用。 */
export function blendReasonBlockedReason(
  evaluation: BlendEvaluation,
  reason: string | null | undefined,
  note: string | null | undefined,
): string | null {
  if (evaluation.status === 'SECRET') {
    // 本页核对不了, 不预先拦; 选了「其他」就要写明 (与后端同一条)
    return reason === BLEND_REASON_OTHER && !(note && note.trim()) ? '配比偏差原因选了「其他」，请写明具体原因' : null;
  }
  if (evaluation.status !== 'OVER') return null;
  const over = evaluation.lines.filter((l) => l.over).map((l) => `「${l.name}」偏差 ${formatPp(l.deviationPp)}`).join('、');
  if (!reason) return `主料配比超出 ±${evaluation.tolerancePp} 个百分点（${over}），请在「配比核对」里选择偏差原因`;
  if (reason === BLEND_REASON_OTHER && !(note && note.trim())) return '配比偏差原因选了「其他」，请写明具体原因';
  return null;
}

export function formatPercent(value: number): string {
  return `${Number(value.toFixed(1))}%`;
}

export function formatPp(value: number): string {
  const v = Number(value.toFixed(1));
  return `${v > 0 ? '+' : ''}${v} 个百分点`;
}
