import { describe, expect, it } from 'vitest'
import type { CanonicalCapabilityCatalog } from '@/api/factoryOnboardingApi'
import {
  draftCapabilityIntentDefinitions,
  normalizeDraftCapabilityIntents,
  questionnaireCardIsVisible,
} from '../factoryCapabilityIntentSelection'

function catalog(): CanonicalCapabilityCatalog {
  const ids = [
    'quality-release', 'hygienic-zones', 'future-capability', 'disabled-capability',
  ]
  return {
    catalogVersion: 'test/v1',
    digest: 'digest',
    source: 'SERVER_VERSIONED_CAPABILITY_CATALOG',
    capabilityCount: ids.length,
    definitions: ids.map((id, index) => ({
      id,
      label: id,
      description: id,
      displayOrder: ids.length - index,
      dependsOn: [],
      configurationTypes: [],
      moduleSchemaCodes: [],
      sectorProfiles: [],
      authorityRefs: [],
      requirementCodes: [],
      draftIntentEligible: id !== 'disabled-capability',
    })),
    loadedAt: '2026-10-09T00:00:00Z',
  }
}

describe('factory onboarding draft capability intent selection', () => {
  it('uses only server-eligible allowlisted definitions and keeps catalog order', () => {
    expect(draftCapabilityIntentDefinitions(catalog()).map((definition) => definition.id))
      .toEqual(['future-capability', 'hygienic-zones', 'quality-release'])
  })

  it('drops unknown, duplicate and unflagged IDs while preserving an explicit empty selection', () => {
    expect(normalizeDraftCapabilityIntents(
      ['quality-release', 'quality-release', 'future-capability', 'disabled-capability', 'unknown'], catalog(),
    )).toEqual(['future-capability', 'quality-release'])
    expect(normalizeDraftCapabilityIntents([], catalog())).toEqual([])
  })

  it('does not claim a questionnaire card owns the current question while a specialized card is active', () => {
    expect(questionnaireCardIsVisible({
      hasUserExample: true, questionnaireCount: 1, productTableActive: true, activeDomainConfig: false,
    })).toBe(false)
    expect(questionnaireCardIsVisible({
      hasUserExample: true, questionnaireCount: 1, productTableActive: false, activeDomainConfig: true,
    })).toBe(false)
    expect(questionnaireCardIsVisible({
      hasUserExample: true, questionnaireCount: 1, productTableActive: false, activeDomainConfig: false,
    })).toBe(true)
  })
})
