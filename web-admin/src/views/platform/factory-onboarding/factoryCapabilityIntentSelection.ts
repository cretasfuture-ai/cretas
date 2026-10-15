import type { CanonicalCapabilityCatalog, CanonicalCapabilityDefinition } from '@/api/factoryOnboardingApi'

export function draftCapabilityIntentDefinitions(
  catalog: CanonicalCapabilityCatalog | null | undefined,
): CanonicalCapabilityDefinition[] {
  const seen = new Set<string>()
  return [...(catalog?.definitions ?? [])]
    .filter((definition) => {
      const id = definition.id.trim()
      if (!id || !definition.draftIntentEligible || seen.has(id)) return false
      seen.add(id)
      return true
    })
    .sort((left, right) => left.displayOrder - right.displayOrder)
}

/**
 * Reconcile a server payload with the current server catalog. Unknown,
 * duplicate or ineligible IDs never reach the draft request, while an empty
 * selection stays a valid explicit [].
 */
export function normalizeDraftCapabilityIntents(
  selected: readonly string[] | null | undefined,
  catalog: CanonicalCapabilityCatalog | null | undefined,
): string[] {
  const selectedSet = new Set((selected ?? []).filter((id): id is string => typeof id === 'string'))
  return draftCapabilityIntentDefinitions(catalog)
    .filter((definition) => selectedSet.has(definition.id))
    .map((definition) => definition.id)
}

export function questionnaireCardIsVisible(options: {
  hasUserExample: boolean
  questionnaireCount: number
  productTableActive: boolean
  activeDomainConfig: boolean
}): boolean {
  return options.hasUserExample
    && options.questionnaireCount > 0
    && !options.productTableActive
    && !options.activeDomainConfig
}
