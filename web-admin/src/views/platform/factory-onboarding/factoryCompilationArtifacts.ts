export const FACTORY_BLUEPRINT_ARTIFACT_SCHEMA = 'factory-blueprint-artifact/v1' as const
export const FACTORY_CAPABILITY_CHANGE_SET_SCHEMA = 'factory-capability-change-set/v1' as const

export type FactoryFactSource = 'USER_CONFIRMED' | 'EXISTING_CONFIG' | 'AI_SUGGESTED' | 'AI_INFERRED'
export type FactoryFactConfirmation = 'CONFIRMED' | 'PENDING' | 'REJECTED'
export type FactoryCompilationStatus = 'BLOCKED' | 'EMPTY' | 'PREVIEW_READY'
export type FactoryAdapterStatus = 'AVAILABLE' | 'MISSING'
export type FactoryCapabilityChangeStatus = 'PREVIEW_READY' | 'BLOCKED' | 'NOT_REQUESTED'

export interface FactoryCompilationFact {
  factId: string
  factoryId: string
  capabilityId: string
  subjectType: string
  subjectId: string
  fieldPath: string
  valueType: string
  value: string
  source: FactoryFactSource
  confirmationState: FactoryFactConfirmation
  evidenceRefs: string[]
}

export interface FactoryCompilationIssue {
  code: string
  severity: 'BLOCKING' | 'WARNING'
  capabilityId: string
  factId: string
  message: string
  recovery: string
}

export interface FactoryCapabilityArtifact {
  capabilityId: string
  artifactType: string
  targetSchemaVersion: string
  payload: Readonly<Record<string, unknown>>
  sourceFactRefs: string[]
  publishState: 'NOT_PUBLISHED'
  businessWriteCount: 0
}

export interface FactoryAdapterReadiness {
  capabilityId: string
  status: FactoryAdapterStatus
  adapterId: string
  artifactType: string
  targetSchemaVersion: string
  nextStep: string
}

export interface FactoryCapabilityChange {
  capabilityId: string
  status: FactoryCapabilityChangeStatus
  fieldPaths: string[]
  sourceFactRefs: string[]
  blockingReason: string
}

export interface FactoryCompilationArtifact {
  schemaVersion: typeof FACTORY_BLUEPRINT_ARTIFACT_SCHEMA
  factoryId: string
  blueprintId: string
  blueprintVersion: number
  catalogVersion: string
  catalogDigest: string
  contentDigest: string
  status: FactoryCompilationStatus
  executionMode: 'PREVIEW_ONLY'
  publishState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
  confirmedFacts: FactoryCompilationFact[]
  capabilityArtifacts: FactoryCapabilityArtifact[]
  changeSet: {
    schemaVersion: typeof FACTORY_CAPABILITY_CHANGE_SET_SCHEMA
    changes: FactoryCapabilityChange[]
    businessWriteCount: 0
  }
  adapterReadiness: FactoryAdapterReadiness[]
  issues: FactoryCompilationIssue[]
  businessWriteCount: 0
}

export interface FactoryCompilationReadinessSummary {
  totalCapabilities: number
  availableAdapters: number
  missingAdapters: number
  blockingIssues: number
  previewArtifacts: number
  writeBoundary: '只读预览，不会写入、发布或激活'
}

/** Read-only view model. Intentionally exposes no mutation URL, action, SQL or execute callback. */
export function summarizeFactoryCompilation(
  artifact: FactoryCompilationArtifact,
): FactoryCompilationReadinessSummary {
  return {
    totalCapabilities: artifact.adapterReadiness.length,
    availableAdapters: artifact.adapterReadiness.filter(item => item.status === 'AVAILABLE').length,
    missingAdapters: artifact.adapterReadiness.filter(item => item.status === 'MISSING').length,
    blockingIssues: artifact.issues.filter(issue => issue.severity === 'BLOCKING').length,
    previewArtifacts: artifact.capabilityArtifacts.length,
    writeBoundary: '只读预览，不会写入、发布或激活',
  }
}

export function isAuthoritativeCompilationFact(fact: FactoryCompilationFact): boolean {
  return fact.confirmationState === 'CONFIRMED'
    && (fact.source === 'USER_CONFIRMED' || fact.source === 'EXISTING_CONFIG')
}
