/**
 * 工序产出分布饼图: Java byProcess 是「工序 × 单位」一行 (不同单位不相加)。
 * 饼图的占比只能在同一个单位里算 (kg 和盒不能进同一个分母), 所以只画一个单位:
 * 出现工序最多的单位 (并列取先出现的); 其它单位列在 otherUnits 里由页面如实说明「未计入」。
 */
export interface ProcessPie {
  unit: string | null;
  slices: { name: string; value: number }[];
  otherUnits: string[];
}

const unitKey = (u: unknown): string => (typeof u === 'string' ? u.trim().toLowerCase() : '');

export function processPieByUnit(rows: Record<string, unknown>[] | null | undefined): ProcessPie {
  const empty: ProcessPie = { unit: null, slices: [], otherUnits: [] };
  if (!rows || rows.length === 0) return empty;
  const order: string[] = [];
  const display = new Map<string, string | null>();
  const counts = new Map<string, number>();
  for (const r of rows) {
    const k = unitKey(r.unit);
    if (!counts.has(k)) {
      order.push(k);
      display.set(k, typeof r.unit === 'string' && r.unit.trim() !== '' ? r.unit.trim() : null);
    }
    if (Number(r.output) > 0) counts.set(k, (counts.get(k) ?? 0) + 1);
    else if (!counts.has(k)) counts.set(k, 0);
  }
  let main = order[0]!;
  for (const k of order) if ((counts.get(k) ?? 0) > (counts.get(main) ?? 0)) main = k;
  const byProcess = new Map<string, number>();
  for (const r of rows) {
    if (unitKey(r.unit) !== main) continue;
    const v = Number(r.output);
    if (!(v > 0)) continue;
    const name = r.process_category == null ? '未分类' : String(r.process_category);
    byProcess.set(name, (byProcess.get(name) ?? 0) + v);
  }
  return {
    unit: display.get(main) ?? null,
    slices: Array.from(byProcess, ([name, value]) => ({ name, value })),
    otherUnits: order.filter((k) => k !== main && (counts.get(k) ?? 0) > 0).map((k) => display.get(k) ?? '单位未知'),
  };
}
