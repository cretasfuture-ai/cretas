import type { FactoryRouteCompilation } from '@/api/factoryOnboardingApi'

export interface RoutePreviewSummary {
  total: number
  ready: number
  blocked: number
  incomplete: number
}

export function summarizeRoutePreviews(previews: FactoryRouteCompilation[]): RoutePreviewSummary {
  return previews.reduce<RoutePreviewSummary>((summary, preview) => {
    summary.total += 1
    if (preview.status === 'READY') summary.ready += 1
    else if (preview.status === 'BLOCKED') summary.blocked += 1
    else summary.incomplete += 1
    return summary
  }, { total: 0, ready: 0, blocked: 0, incomplete: 0 })
}

export function routePreviewStatus(preview: FactoryRouteCompilation) {
  if (preview.status === 'READY') {
    return {
      label: preview.previewKind === 'COMPILED_EXPLICIT' ? '复杂拓扑已编译' : '顺序路线已编译',
      tone: 'success' as const,
    }
  }
  if (preview.status === 'BLOCKED') {
    return { label: '复杂拓扑待确认', tone: 'warning' as const }
  }
  return { label: '路线事实未齐', tone: 'info' as const }
}
