/**
 * AI 对话里查询结果表格的表头 (AI 对话标准 V3 + G1)。
 *
 * 表头的取值顺序:
 *   1. 后端随结果给的中文 label (NL2SQL 的 fields[].label / displayName / labelZh, 按列名对上);
 *   2. 列名本身含中文 → 原样;
 *   3. web-admin 已有的列名映射 (api/smartbi/common.ts 的 COLUMN_NAME_MAP);
 *   4. 都没有 → 英文列名「照原样显示」。
 *
 * ⛔ 这里只负责「这一列叫什么」, 不负责「这一列要不要显示」: 查询结果的列一列都不许隐藏
 *    (隐藏数据列等于篡改答案)。仍是英文的列要靠后端 SQL 别名补中文名, 不靠前端藏。
 */
import { lookupColumnLabel } from '@/api/smartbi/common';

const HAS_CJK = /[一-鿿]/;

function labelFromField(field: Record<string, unknown>): string | null {
  for (const k of ['label', 'displayName', 'labelZh', 'nameZh']) {
    const v = field[k];
    if (typeof v === 'string' && HAS_CJK.test(v)) return v;
  }
  return null;
}

/** 把 NL2SQL 的 fields 描述转成「列名 → 中文 label」; fields 没给 label 就是空表。 */
export function buildBackendColumnLabels(
  fields: Array<Record<string, unknown>> | undefined | null,
): Record<string, string> {
  const map: Record<string, string> = {};
  if (!Array.isArray(fields)) return map;
  for (const f of fields) {
    if (!f || typeof f !== 'object') continue;
    const label = labelFromField(f);
    if (!label) continue;
    for (const k of ['name', 'originalName', 'matchedName']) {
      const key = f[k];
      if (typeof key === 'string' && key && map[key] === undefined) map[key] = label;
    }
  }
  return map;
}

export function resolveColumnLabel(col: string, backendLabels?: Record<string, string>): string {
  const fromBackend = backendLabels?.[col];
  if (fromBackend) return fromBackend;
  if (HAS_CJK.test(col)) return col;
  return lookupColumnLabel(col) ?? col;
}

/** 表格列数 ≤ 8: 全部列自适应宽度, 1440 宽下不横向滚动; 更宽的表维持固定最小列宽 (可横滚)。 */
export const AI_TABLE_FIT_MAX_COLUMNS = 8;

export function columnMinWidth(columnCount: number): string | undefined {
  return columnCount <= AI_TABLE_FIT_MAX_COLUMNS ? undefined : '100';
}
