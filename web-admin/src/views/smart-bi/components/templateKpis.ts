/**
 * Which kpiValues entries a TemplateCard shows in its 4 KPI slots.
 *
 * Templates put context fields next to the KPIs (monthly_trend emits
 * `unit: "元"` and `aggregation: "sum"`). They are not KPIs, but Postgres
 * jsonb stores keys shortest-first, so they sorted to the front and took two
 * of the four slots as raw English ("unit / 元 / aggregation / sum" on the
 * F006 经营驾驶舱, 2026-10-12). They describe the values; never show them as one.
 */
export const KPI_META_KEYS: ReadonlySet<string> = new Set(['unit', 'aggregation']);

export const KPI_SLOT_COUNT = 4;

export function visibleKpiEntries(
  kv: Record<string, unknown> | null | undefined,
  hideKey: (key: string) => boolean = () => false,
): Array<[string, unknown]> {
  if (!kv || typeof kv !== 'object') return [];
  return Object.entries(kv)
    .filter(([key]) => !KPI_META_KEYS.has(key) && !hideKey(key))
    .slice(0, KPI_SLOT_COUNT);
}
