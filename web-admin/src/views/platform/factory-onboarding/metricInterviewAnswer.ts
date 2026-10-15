export interface MetricInterviewChoice {
  label: string
  direction: string
  unit: string
  checked: boolean
  alreadyConfigured: boolean
  threshold?: number
}

/** Only explicit catalog choices become user-confirmed interview facts. */
export function selectedMetricInterviewAnswer(rows: readonly MetricInterviewChoice[]): string | undefined {
  const selected = rows.filter(row => row.checked && !row.alreadyConfigured
    && row.label.trim().length > 0 && row.direction.trim().length > 0
    && typeof row.threshold === 'number' && Number.isFinite(row.threshold) && row.threshold >= 0)
  return selected.length > 0
    ? selected.map(row => `${row.label}${row.direction}${row.threshold}${row.unit}`).join('；')
    : undefined
}
