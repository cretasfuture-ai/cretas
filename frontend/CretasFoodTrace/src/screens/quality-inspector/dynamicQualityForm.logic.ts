/**
 * 动态质检表 — 纯逻辑 (无 RN 依赖, 便于单测)。
 *
 * D-S302-02: 检验项配了「不合格必须写原因」时, 后端按项目判定、拒绝空原因 (手机和网页同一入口)。
 * 这里提前算一遍, 让质检员填完实测值就看到要写原因, 而不是点了提交才被挡回来 (防呆 Rule 1)。
 * ⚠️ 判定以后端为准: 这里只负责提前提示, 判定口径与 ProcessingServiceImpl#submitDynamicInspection 一致。
 */
import type { DynamicQualityItem } from '../../types/qualityInspector';

/** 全空白 (含全角空格、不换行空格) 不算写了原因。JS 的 \S 把这些都算空白。 */
export function hasReasonText(text: string | null | undefined): boolean {
  return /\S/.test(text ?? '');
}

function itemFails(item: DynamicQualityItem, raw: string): boolean {
  const type = (item.valueType ?? '').toUpperCase();
  const standard = item.effectiveStandardValue?.trim() ?? '';
  if (type === 'BOOLEAN') return standard !== '' && raw.toLowerCase() !== standard.toLowerCase();
  if (type === 'TEXT') return standard !== '' && raw !== standard;
  const value = Number(raw);
  if (!Number.isFinite(value)) return false;
  const min = item.effectiveMinValue;
  const max = item.effectiveMaxValue;
  if (min != null || max != null) {
    return (min != null && value < min) || (max != null && value > max);
  }
  return standard !== '' && Number.isFinite(Number(standard)) && value !== Number(standard);
}

/** 这次填写里, 判不合格且要求写原因的项目名称。没填的项目不算。 */
export function itemsNeedingFailReason(
  items: DynamicQualityItem[],
  values: Record<string, string | undefined>,
): string[] {
  return items
    .filter((item) => {
      const raw = values[item.bindingId]?.trim();
      return Boolean(item.requireNoteOnFail) && Boolean(raw) && itemFails(item, raw as string);
    })
    .map((item) => item.label);
}
