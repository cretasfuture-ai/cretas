import { describe, expect, it } from 'vitest'
import {
  FACTORY_BLUEPRINT_ARTIFACT_SCHEMA,
  isAuthoritativeCompilationFact,
  summarizeFactoryCompilation,
  type FactoryCompilationArtifact,
  type FactoryCompilationFact,
} from '../factoryCompilationArtifacts'

const artifact = (): FactoryCompilationArtifact => ({
  schemaVersion: FACTORY_BLUEPRINT_ARTIFACT_SCHEMA,
  factoryId: 'F999',
  blueprintId: 'blueprint-b20',
  blueprintVersion: 1,
  catalogVersion: 'factory-capability-catalog/v2',
  catalogDigest: 'catalog-digest',
  contentDigest: 'a'.repeat(64),
  status: 'PREVIEW_READY',
  executionMode: 'PREVIEW_ONLY',
  publishState: 'NOT_PUBLISHED',
  activationState: 'NOT_ACTIVATED',
  confirmedFacts: [],
  capabilityArtifacts: [{
    capabilityId: 'routes',
    artifactType: 'ROUTE_GRAPH_PREVIEW',
    targetSchemaVersion: 'factory-route-graph/v1',
    payload: {},
    sourceFactRefs: ['route-shape'],
    publishState: 'NOT_PUBLISHED',
    businessWriteCount: 0,
  }],
  changeSet: {
    schemaVersion: 'factory-capability-change-set/v1',
    changes: [],
    businessWriteCount: 0,
  },
  adapterReadiness: Array.from({ length: 26 }, (_, index) => ({
    capabilityId: index === 0 ? 'routes' : `capability-${index}`,
    status: index === 0 ? 'AVAILABLE' : 'MISSING',
    adapterId: index === 0 ? 'factory-route-artifact-adapter/v1' : '',
    artifactType: index === 0 ? 'ROUTE_GRAPH_PREVIEW' : '',
    targetSchemaVersion: index === 0 ? 'factory-route-graph/v1' : '',
    nextStep: index === 0 ? '可生成只读预览' : '实现只读适配器',
  })),
  issues: [{
    code: 'NON_AUTHORITATIVE_FACT_IGNORED',
    severity: 'WARNING',
    capabilityId: 'products',
    factId: 'suggested-shelf-life',
    message: 'AI 建议未进入权威产物',
    recovery: '等待用户确认',
  }],
  businessWriteCount: 0,
})

describe('factory compilation artifact read-only contract', () => {
  it('summarizes all 26 capabilities without implying unsupported adapters are ready', () => {
    expect(summarizeFactoryCompilation(artifact())).toEqual({
      totalCapabilities: 26,
      availableAdapters: 1,
      missingAdapters: 25,
      blockingIssues: 0,
      previewArtifacts: 1,
      writeBoundary: '只读预览，不会写入、发布或激活',
    })
  })

  it('allows only confirmed user or existing-config facts into authoritative presentation', () => {
    const base: FactoryCompilationFact = {
      factId: 'fact-1', factoryId: 'F999', capabilityId: 'products',
      subjectType: 'product', subjectId: 'beef', fieldPath: 'name',
      valueType: 'STRING', value: '五香牛肉', source: 'USER_CONFIRMED',
      confirmationState: 'CONFIRMED', evidenceRefs: ['conversation:F999:b20'],
    }
    expect(isAuthoritativeCompilationFact(base)).toBe(true)
    expect(isAuthoritativeCompilationFact({ ...base, source: 'EXISTING_CONFIG' })).toBe(true)
    expect(isAuthoritativeCompilationFact({ ...base, source: 'AI_SUGGESTED' })).toBe(false)
    expect(isAuthoritativeCompilationFact({ ...base, confirmationState: 'PENDING' })).toBe(false)
  })

  it('has no executable write surface in the view model', () => {
    const serialized = JSON.stringify(artifact())
    expect(serialized).toContain('PREVIEW_ONLY')
    expect(serialized).toContain('NOT_PUBLISHED')
    expect(serialized).toContain('NOT_ACTIVATED')
    expect(serialized).not.toMatch(/mutationUrl|executeSql|publishAction|activateAction/)
  })
})
