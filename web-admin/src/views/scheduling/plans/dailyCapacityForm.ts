/**
 * 自动排程「当场补日产能」的表单逻辑 (APS v2, Steve 2026-10-28):
 * 后端返回 outcome = CAPACITY_REQUIRED + capacityRequired 清单时, 排程页当场让人填每个产品一天能做多少,
 * 填完带 dailyCapacityOverrides 重新提交 (后端先保存到产品, 再排)。
 *
 * 单位不让人选: 就是计划单位 (后端给的 unit), 填别的单位后端也会拒。
 */
import type { DailyCapacityOverride, DailyCapacityRequired } from '@/api/scheduling';

export type CapacityInputs = Record<string, number | string | null | undefined>;

export function capacityKey(item: Pick<DailyCapacityRequired, 'productTypeId' | 'unit'>): string {
  return `${item.productTypeId}|${item.unit}`;
}

function toPositiveNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number(String(value).trim());
  if (!Number.isFinite(n) || n <= 0) return null;
  return Math.round(n * 100) / 100;
}

/** 预先告诉用户还差什么 (防呆 Rule 1): 空串 = 可以提交。 */
export function capacityBlockReason(required: DailyCapacityRequired[], inputs: CapacityInputs): string {
  for (const item of required) {
    if (toPositiveNumber(inputs[capacityKey(item)]) === null) {
      return `请填写「${item.productName}」一天能做多少 ${item.unit}（大于 0）`;
    }
  }
  return '';
}

/** 把填好的日产能变成请求体; 有没填的直接抛 (调用方先用 capacityBlockReason 挡住)。 */
export function buildCapacityOverrides(
  required: DailyCapacityRequired[],
  inputs: CapacityInputs,
): DailyCapacityOverride[] {
  return required.map(item => {
    const quantityPerDay = toPositiveNumber(inputs[capacityKey(item)]);
    if (quantityPerDay === null) {
      throw new Error(`「${item.productName}」的日产能没有填`);
    }
    return { productTypeId: item.productTypeId, quantityPerDay, unit: item.unit };
  });
}
