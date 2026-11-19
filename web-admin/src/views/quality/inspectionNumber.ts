/**
 * 质检记录给用户看的编号 (PROTO-QC-NUMBER)。
 *
 * 后端 `inspectionNumber` 是数据库按工厂按天发的业务编号 QI-YYYYMMDD-NNNN (含软删行不复用)。
 * 没有编号 (极少数两个日期都缺的历史行) 时显示「未编号」——⛔ 不拿内部 id 截一段当编号,
 * 那串 UUID 对质检员、仓管没有意义, 也没法拿去对单。
 */
export const UNNUMBERED_INSPECTION_LABEL = '未编号';

export interface InspectionNumberSource {
  inspectionNumber?: string | null;
}

export function inspectionNumberLabel(row: InspectionNumberSource | null | undefined): string {
  const value = typeof row?.inspectionNumber === 'string' ? row.inspectionNumber.trim() : '';
  return value || UNNUMBERED_INSPECTION_LABEL;
}

export function hasInspectionNumber(row: InspectionNumberSource | null | undefined): boolean {
  return inspectionNumberLabel(row) !== UNNUMBERED_INSPECTION_LABEL;
}
