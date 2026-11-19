/**
 * 工序产出分布: 后端 /production-analytics/dashboard 的 byProcess 是「工序 × 单位」一行 (不同单位不相加)。
 * 这里按工序归并成一行一工序 (同名工序不重复), 每个单位一条子项;
 * 占比只在同单位内算 (kg 和盒不能放进同一个分母), 不良率直接用后端给的 defectRate / defectStatus。
 */

export type DefectStatus = 'COUNTED' | 'NOT_COUNTED' | 'NO_OUTPUT';

export interface ProcessUnitLine {
  unit: string | null;
  output: number;
  /** 本单位下, 本工序产出占全部工序同单位产出的百分比; 同单位总产出为 0 时 null */
  sharePct: number | null;
  /** 0.0125 = 1.25%; null = 不知道 (没清点 / 产量为 0) */
  defectRate: number | null;
  defectStatus: DefectStatus;
}

export interface ProcessGroup {
  process: string;
  lines: ProcessUnitLine[];
}

function toNum(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function toStatus(v: unknown, rate: number | null): DefectStatus {
  if (v === 'COUNTED' || v === 'NOT_COUNTED' || v === 'NO_OUTPUT') return v;
  // 旧后端没有 defectStatus: 有率算已清点, 没有率就当不知道 (不当 0)
  return rate === null ? 'NOT_COUNTED' : 'COUNTED';
}

export function groupProcessBreakdown(rows: Record<string, unknown>[] | null | undefined): ProcessGroup[] {
  if (!rows || rows.length === 0) return [];
  const unitKey = (u: string | null) => (u === null ? '' : u.trim().toLowerCase());
  const unitTotals = new Map<string, number>();
  for (const r of rows) {
    const unit = typeof r.unit === 'string' ? r.unit : null;
    unitTotals.set(unitKey(unit), (unitTotals.get(unitKey(unit)) ?? 0) + (toNum(r.output) ?? 0));
  }
  const groups = new Map<string, ProcessGroup>();
  for (const r of rows) {
    const process = r.process_category == null ? '未分类' : String(r.process_category);
    const unit = typeof r.unit === 'string' ? r.unit : null;
    const output = toNum(r.output) ?? 0;
    const total = unitTotals.get(unitKey(unit)) ?? 0;
    const defectRate = toNum(r.defectRate);
    let g = groups.get(process);
    if (!g) {
      g = { process, lines: [] };
      groups.set(process, g);
    }
    const same = g.lines.find((l) => unitKey(l.unit) === unitKey(unit));
    if (same) {
      // 防御: 后端已按工序 × 单位合并, 万一重复只合并产出, 不良率不知道怎么合 ⇒ 置为未知
      same.output += output;
      same.sharePct = total > 0 ? (same.output / total) * 100 : null;
      same.defectRate = null;
      same.defectStatus = 'NOT_COUNTED';
      continue;
    }
    g.lines.push({
      unit,
      output,
      sharePct: total > 0 ? (output / total) * 100 : null,
      defectRate,
      defectStatus: toStatus(r.defectStatus, defectRate),
    });
  }
  return Array.from(groups.values());
}

/** 不良率文字: 已清点 → 百分比 (0 也显示 0.0%); 没清点 → 未清点不良; 产量为 0 → 无产量。 */
export function defectRateLabel(line: Pick<ProcessUnitLine, 'defectRate' | 'defectStatus'>): string {
  if (line.defectStatus === 'NOT_COUNTED') return '不良未清点';
  if (line.defectStatus === 'NO_OUTPUT' || line.defectRate === null) return '不良率 —';
  return `不良率 ${(line.defectRate * 100).toFixed(1)}%`;
}
