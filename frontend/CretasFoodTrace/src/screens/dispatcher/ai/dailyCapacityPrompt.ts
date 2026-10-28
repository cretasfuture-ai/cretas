/**
 * APS v2 (Steve 2026-10-28) 排程页「当场补日产能」与「按天分配」展示的纯逻辑。
 * 后端返回 outcome = CAPACITY_REQUIRED 时什么都没保存; 让人填每个产品一天能做多少,
 * 带 dailyCapacityOverrides 重新提交 (后端先保存到产品再排)。单位不让人选: 就是计划单位。
 */
import type {
  DailyAllocations,
  DailyCapacityOverride,
  DailyCapacityRequired,
} from '../../../services/api/schedulingApiClient';

export type CapacityInputs = Record<string, string | undefined>;

export function capacityKey(item: Pick<DailyCapacityRequired, 'productTypeId' | 'unit'>): string {
  return `${item.productTypeId}|${item.unit}`;
}

function positive(value: string | undefined): number | null {
  if (value === undefined || value.trim() === '') return null;
  const n = Number(value.trim());
  if (!Number.isFinite(n) || n <= 0) return null;
  return Math.round(n * 100) / 100;
}

/** 空串 = 可以提交; 否则告诉用户还差哪个。 */
export function capacityBlockReason(required: DailyCapacityRequired[], inputs: CapacityInputs): string {
  for (const item of required) {
    if (positive(inputs[capacityKey(item)]) === null) {
      return `请填写「${item.productName}」一天能做多少 ${item.unit}（大于 0）`;
    }
  }
  return '';
}

export function buildCapacityOverrides(
  required: DailyCapacityRequired[],
  inputs: CapacityInputs,
): DailyCapacityOverride[] {
  return required.map(item => {
    const quantityPerDay = positive(inputs[capacityKey(item)]);
    if (quantityPerDay === null) throw new Error(`「${item.productName}」的日产能没有填`);
    return { productTypeId: item.productTypeId, quantityPerDay, unit: item.unit };
  });
}

function qty(n: number): string {
  return Number(n).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/** 「10-29 300袋 · 10-30 300袋 · 10-31 100袋」; 没有按天分配返回 null。 */
export function describeDays(alloc: DailyAllocations | null | undefined): string | null {
  if (!alloc || !Array.isArray(alloc.days) || alloc.days.length === 0) return null;
  return alloc.days.map(d => `${String(d.date).slice(5)} ${qty(d.quantity)}${alloc.unit}`).join(' · ');
}

/** 「10-29 ~ 10-31」或单日「10-29」。 */
export function describeDateRange(alloc: DailyAllocations | null | undefined): string | null {
  const firstDay = alloc?.days?.[0];
  const lastDay = alloc?.days?.[alloc.days.length - 1];
  if (!firstDay || !lastDay) return null;
  const first = String(firstDay.date).slice(5);
  const last = String(lastDay.date).slice(5);
  return first === last ? first : `${first} ~ ${last}`;
}

/** 「日产能 300袋/天（人工填写）」。 */
export function describeCapacity(alloc: DailyAllocations | null | undefined): string | null {
  if (!alloc) return null;
  return `日产能 ${qty(alloc.capacityPerDay)}${alloc.unit}/天（${alloc.capacitySourceLabel}）`;
}
