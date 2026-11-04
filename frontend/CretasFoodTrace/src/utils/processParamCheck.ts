import type { ProcessCustomFieldDef } from '../services/api/yieldReportApi';

/**
 * 工艺标准参数比对 —— 与 web-admin/src/utils/processParamCheck.ts、后端 ProcessParamCheck 同一判据:
 * 只判「数字字段 + 配了上下限 + 填了数字」, 边界值算合格; 没范围、没填、不是数字都不判 (不猜, 不补 0)。
 * 文案函数也与 web 逐字一致 (「标准 155℃，范围 150–160℃」「不高于 12%」)。
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

export function hasParamRange(def: ProcessCustomFieldDef): boolean {
  return def.type === 'number' && (toNumber(def.min) !== null || toNumber(def.max) !== null);
}

/** 返回 null 表示不判 (没范围 / 没填 / 不是数字)。 */
export function checkParam(def: ProcessCustomFieldDef, value: unknown): ParamStatus | null {
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
export function formatParamStandard(def: ProcessCustomFieldDef): string {
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
  defs: ProcessCustomFieldDef[],
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

/** 只给操作员显示/填写的字段: enabled !== false。 */
export function enabledParamDefs(schema: ProcessCustomFieldDef[] | null | undefined): ProcessCustomFieldDef[] {
  return (schema ?? []).filter((d) => d.enabled !== false);
}

/** 数字字段里填的不是数字 (空串不算, 没填就是没填)。 */
export function isInvalidNumberText(def: ProcessCustomFieldDef, text: string | undefined): boolean {
  if (def.type !== 'number') return false;
  const t = (text ?? '').trim();
  return t !== '' && toNumber(t) === null;
}

export interface ParamRequestBuild {
  /** 随请求发的值; 空请求体用 undefined 而不是 {} */
  customFields: Record<string, number | string> | undefined;
  /** 数字字段里填了非数字的字段名 (提交要挡) */
  invalidLabels: string[];
}

/** 填了的才发; 数字字段发 number, 其余发去掉首尾空白的字符串; 非数字文本不发并记入 invalidLabels。 */
export function buildCustomFieldsRequest(
  defs: ProcessCustomFieldDef[],
  values: Record<string, string>,
): ParamRequestBuild {
  const fields: Record<string, number | string> = {};
  const invalidLabels: string[] = [];
  for (const def of defs) {
    const text = (values[def.key] ?? '').trim();
    if (text === '') continue;
    if (def.type === 'number') {
      const n = toNumber(text);
      if (n === null) invalidLabels.push(def.label);
      else fields[def.key] = n;
    } else {
      fields[def.key] = text;
    }
  }
  return {
    customFields: Object.keys(fields).length > 0 ? fields : undefined,
    invalidLabels,
  };
}
