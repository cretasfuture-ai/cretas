/**
 * The readiness page opens the first business form with `?fromReadiness=1&<ids>`. The ids come from the
 * factory's own master data (server side); the target page still verifies each id against what it loaded
 * and says so when one is gone, instead of silently opening an empty form.
 */
export type ReadinessPrefill = Record<string, string>

export function readReadinessPrefill(
  query: Record<string, unknown>, keys: readonly string[],
): ReadinessPrefill | null {
  if (query.fromReadiness !== '1') return null
  const found: ReadinessPrefill = {}
  for (const key of keys) {
    const value = query[key]
    if (typeof value === 'string' && value.trim()) found[key] = value.trim()
  }
  return Object.keys(found).length > 0 ? found : null
}

export function pickPrefilled<T extends { id?: unknown }>(rows: readonly T[], id: string | undefined): T | undefined {
  return id ? rows.find((row) => String(row.id) === id) : undefined
}
