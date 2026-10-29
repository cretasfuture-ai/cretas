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

/**
 * 打开补填面板时的初始值: 这一轮之前已经填过的保留; 没填过且后端给了建议值 (联产计划按原料投入算) 就预填建议值,
 * 人可改 —— 建议值只有点「保存日产能并排程」才会保存。
 */
export function initialCapacityInputs(required: DailyCapacityRequired[], previous: CapacityInputs): CapacityInputs {
  const inputs: CapacityInputs = {};
  for (const item of required) {
    const key = capacityKey(item);
    const prev = previous[key];
    const hasPrev = prev !== null && prev !== undefined && prev !== '';
    inputs[key] = hasPrev ? prev : (toPositiveNumber(item.suggestedPerDay ?? null) ?? null);
  }
  return inputs;
}

function shortDate(date: string): string {
  return /^\d{4}-\d{2}-\d{2}/.test(date) ? date.slice(5, 10) : date;
}

function plainQuantity(n: number): string {
  return String(Math.round(Number(n) * 100) / 100);
}

/**
 * 历史不足 3 天时把那几天的实际量摆出来: 「近 90 天只有 1 天有记录：10-27 入库 40 盒」;
 * 联产计划写「投入」。没有记录 (0 天) 返回空串, 原因已在 experienceNote 里。
 */
export function describeSampleDays(item: DailyCapacityRequired): string {
  const days = item.sampleDays ?? [];
  if (days.length === 0) return '';
  const verb = item.historyBasis === 'RAW_INPUT' ? '投入' : '入库';
  const window = item.historyWindowDays ?? 90;
  const parts = days.map(d => `${shortDate(d.date)} ${verb} ${plainQuantity(d.quantity)} ${d.unit}`);
  return `近 ${window} 天只有 ${days.length} 天有记录：${parts.join('，')}`;
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
