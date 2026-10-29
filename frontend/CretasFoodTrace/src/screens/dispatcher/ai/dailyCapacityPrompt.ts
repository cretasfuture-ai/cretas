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

/**
 * 打开补填弹窗时的初始值: 之前填过的保留; 没填过且后端给了建议值 (联产计划按原料投入算) 就预填建议值,
 * 人可改 —— 建议值只有点「保存并排程」才会保存。
 */
export function initialCapacityInputs(required: DailyCapacityRequired[], previous: CapacityInputs): CapacityInputs {
  const next: CapacityInputs = {};
  for (const item of required) {
    const key = capacityKey(item);
    const prev = previous[key];
    if (prev !== undefined && prev.trim() !== '') {
      next[key] = prev;
    } else if (typeof item.suggestedPerDay === 'number' && Number.isFinite(item.suggestedPerDay) && item.suggestedPerDay > 0) {
      next[key] = String(Math.round(item.suggestedPerDay * 100) / 100);
    } else {
      next[key] = undefined;
    }
  }
  return next;
}

/**
 * 历史不足 3 天时把那几天的实际量摆出来: 「近 90 天只有 1 天有记录：10-27 入库 40 盒」;
 * 联产计划写「投入」。没有记录 (0 天) 返回 null, 原因已在 experienceNote 里。
 */
export function describeSampleDays(item: DailyCapacityRequired): string | null {
  const days = item.sampleDays ?? [];
  if (days.length === 0) return null;
  const verb = item.historyBasis === 'RAW_INPUT' ? '投入' : '入库';
  const window = item.historyWindowDays ?? 90;
  const parts = days.map(d => `${String(d.date).slice(5, 10)} ${verb} ${Math.round(Number(d.quantity) * 100) / 100} ${d.unit}`);
  return `近 ${window} 天只有 ${days.length} 天有记录：${parts.join('，')}`;
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
