import type { ProcessSheetCustomFieldDef } from '@/api/processProduction';

/**
 * 工艺标准参数比对 —— 与后端 ProcessParamCheck 同一判据:
 * 只判「数字字段 + 配了上下限 + 填了数字」, 边界值算合格; 没范围、没填、不是数字都不判 (不猜, 不补 0)。
 */
export type ParamStatus = 'OK' | 'LOW' | 'HIGH';

export interface ParamDeviation {
  key: string;
  label: string;
  status: Exclude<ParamStatus, 'OK'>;
  text: string;
}

function toNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '' || typeof value === 'boolean') return null;
  const n = typeof value === 'number' ? value : Number(String(value).trim());
  return Number.isFinite(n) ? n : null;
}

function fmt(n: number): string {
  return String(Number(n.toFixed(4)));
}

export function hasParamRange(def: ProcessSheetCustomFieldDef): boolean {
  return def.type === 'number' && (toNumber(def.min) !== null || toNumber(def.max) !== null);
}

/** 返回 null 表示不判 (没范围 / 没填 / 不是数字)。 */
export function checkParam(def: ProcessSheetCustomFieldDef, value: unknown): ParamStatus | null {
  if (!hasParamRange(def)) return null;
  const v = toNumber(value);
  if (v === null) return null;
  const min = toNumber(def.min);
  const max = toNumber(def.max);
  if (min !== null && v < min) return 'LOW';
  if (max !== null && v > max) return 'HIGH';
  return 'OK';
}

/** 「标准 155℃，范围 150–160℃」/「不高于 12%」; 什么都没配返回空串。 */
export function formatParamStandard(def: ProcessSheetCustomFieldDef): string {
  if (def.type !== 'number') return '';
  const unit = def.unit?.trim() || '';
  const std = toNumber(def.standardValue);
  const min = toNumber(def.min);
  const max = toNumber(def.max);
  let range = '';
  if (min !== null && max !== null) range = `${fmt(min)}–${fmt(max)}${unit}`;
  else if (min !== null) range = `不低于 ${fmt(min)}${unit}`;
  else if (max !== null) range = `不高于 ${fmt(max)}${unit}`;
  const parts: string[] = [];
  if (std !== null) parts.push(`标准 ${fmt(std)}${unit}`);
  if (range) parts.push(std !== null ? `范围 ${range}` : range);
  return parts.join('，');
}

/** 一行里所有超出范围的参数, 用于正式报工前的确认。 */
export function collectParamDeviations(
  defs: ProcessSheetCustomFieldDef[],
  values: Record<string, unknown>,
): ParamDeviation[] {
  const out: ParamDeviation[] = [];
  for (const def of defs) {
    const status = checkParam(def, values[def.key]);
    if (status === 'LOW' || status === 'HIGH') {
      const unit = def.unit?.trim() || '';
      const v = toNumber(values[def.key]) as number;
      out.push({
        key: def.key,
        label: def.label,
        status,
        text: `${def.label} ${fmt(v)}${unit}，${status === 'HIGH' ? '高于' : '低于'}${formatParamStandard(def)}`,
      });
    }
  }
  return out;
}

export interface ParamStandardDraft {
  type: string;
  unit: string;
  standardValue: string;
  min: string;
  max: string;
}

/**
 * 配置弹窗的输入 → 落库值 + 错误提示 (与后端 WorkProcessCustomFieldSchema 同一组规则, 先在前端挡住)。
 * 非数字字段一律不带标准参数。
 */
export function parseParamStandard(draft: ParamStandardDraft): {
  value: Pick<ProcessSheetCustomFieldDef, 'unit' | 'standardValue' | 'min' | 'max'>;
  error: string | null;
} {
  if (draft.type !== 'number') return { value: {}, error: null };
  const parse = (s: string): number | null | 'invalid' => {
    const t = s.trim();
    if (!t) return null;
    const n = Number(t);
    return Number.isFinite(n) ? n : 'invalid';
  };
  const std = parse(draft.standardValue);
  const min = parse(draft.min);
  const max = parse(draft.max);
  if (std === 'invalid' || min === 'invalid' || max === 'invalid') {
    return { value: {}, error: '标准值和上下限必须是数字' };
  }
  const unit = draft.unit.trim();
  if (unit.length > 20) return { value: {}, error: '单位不能超过 20 个字' };
  if (min !== null && max !== null && min > max) return { value: {}, error: '下限不能大于上限' };
  if (std !== null && ((min !== null && std < min) || (max !== null && std > max))) {
    return { value: {}, error: '标准值必须在上下限范围内' };
  }
  const value: Pick<ProcessSheetCustomFieldDef, 'unit' | 'standardValue' | 'min' | 'max'> = {};
  if (unit) value.unit = unit;
  if (std !== null) value.standardValue = std;
  if (min !== null) value.min = min;
  if (max !== null) value.max = max;
  return { value, error: null };
}
