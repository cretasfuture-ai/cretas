/**
 * 产品「一天能做多少」(APS v2 日产能, Steve 2026-10-28) 在产品编辑框里的判据。
 * 留空 = 没填 (排程用历史估算或当场问), 填了就要数值 > 0 且有单位。
 */

function normalizeAmount(amount: number | string | null | undefined): number | null | 'invalid' {
  if (amount === null || amount === undefined || amount === '') return null;
  const n = typeof amount === 'number' ? amount : Number(String(amount).trim());
  if (!Number.isFinite(n) || n <= 0) return 'invalid';
  return Math.round(n * 100) / 100;
}

/** 预先告诉用户还差什么 (防呆 Rule 1): 空串 = 可以保存。 */
export function dailyCapacityBlockReason(
  amount: number | string | null | undefined,
  unit: string | null | undefined,
): string {
  const q = normalizeAmount(amount);
  if (q === null) return '';
  if (q === 'invalid') return '日产能要大于 0；不知道就留空，排程时会用历史估算或当场问你';
  if (!String(unit || '').trim()) return '请填写日产能的单位（与这个产品生产计划的单位一致，如 袋、kg）';
  return '';
}

/**
 * 产品存好之后再存日产能 (单独接口)。没变不发请求; 返回 'unchanged' / 'saved'; 请求失败抛给调用方。
 * 抽出来是为了让「真的调了那个接口、带的是哪个产品」能被测到, 而不只是 grep 源码。
 */
export async function saveDailyCapacityIfChanged(
  productId: string,
  original: { quantity: number | null; unit: string },
  amount: number | string | null | undefined,
  unit: string | null | undefined,
  save: (productId: string, quantityPerDay: number | null, unit: string | null) => Promise<unknown>,
): Promise<'unchanged' | 'saved'> {
  const change = dailyCapacityChange(original, amount, unit);
  if (!change) return 'unchanged';
  if (!productId) throw new Error('产品还没有 id，日产能没法保存');
  await save(productId, change.quantityPerDay, change.unit);
  return 'saved';
}

/** 与打开编辑框时比较: 没变返回 null (不发请求); 变了返回要提交的值 (quantityPerDay = null 表示清掉)。 */
export function dailyCapacityChange(
  original: { quantity: number | null; unit: string },
  amount: number | string | null | undefined,
  unit: string | null | undefined,
): { quantityPerDay: number | null; unit: string | null } | null {
  const q = normalizeAmount(amount);
  if (q === 'invalid') throw new Error('日产能无效');
  const u = String(unit || '').trim();
  if (q === null) {
    return original.quantity === null ? null : { quantityPerDay: null, unit: null };
  }
  if (original.quantity !== null && Math.abs(original.quantity - q) < 0.005 && original.unit.trim() === u) {
    return null;
  }
  return { quantityPerDay: q, unit: u };
}
