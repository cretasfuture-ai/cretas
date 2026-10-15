export type CapabilityAdapterAvailability =
  | 'AVAILABLE_READ_ONLY'
  | 'AVAILABLE_DRAFT'
  | 'BLOCKED_OWNER_COMMAND_MISSING'

export interface CapabilityAdapterReadiness {
  capabilityId: string
  availability: CapabilityAdapterAvailability
  moduleOwner: string
  artifactType: string
  targetSchemaVersion: string
  requiredPermission: string
  dependencies: string[]
  allowedTargetStates: Array<'READ_ONLY' | 'PREVIEW_ONLY' | 'DRAFT' | 'TEMPLATE' | 'PLAN'>
  readbackAssertions: string[]
  recoveryStrategy: string
  dedicatedCommand: string
  blockingCode: string
  nextStep: string
  expectedBusinessWriteCount: number
  publishState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
}

export interface CapabilityAdapterReadinessReport {
  schemaVersion: 'factory-capability-adapter-readiness/v1'
  capabilities: CapabilityAdapterReadiness[]
  capabilityCount: number
  availableReadOnlyCount: number
  availableDraftCount: number
  blockedCount: number
  expectedBusinessWriteCount: 0
  publishState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
}

export interface CapabilityAdapterReadinessSummary {
  headline: string
  available: string
  blocked: string
  writeBoundary: string
  publishBoundary: string
  blockedCapabilities: Array<{
    capabilityId: string
    moduleOwner: string
    blockingCode: string
    nextStep: string
  }>
}

export function summarizeCapabilityAdapterReadiness(
  report: CapabilityAdapterReadinessReport
): CapabilityAdapterReadinessSummary {
  const blockedCapabilities = report.capabilities
    .filter((item) => item.availability === 'BLOCKED_OWNER_COMMAND_MISSING')
    .map((item) => ({
      capabilityId: item.capabilityId,
      moduleOwner: item.moduleOwner,
      blockingCode: item.blockingCode,
      nextStep: item.nextStep
    }))

  return {
    headline: `${report.capabilityCount} 项能力：${report.availableReadOnlyCount} 项只读、${report.availableDraftCount} 项可进入模块草稿、${report.blockedCount} 项待专用命令`,
    available: `可用边界 ${report.availableReadOnlyCount + report.availableDraftCount}/${report.capabilityCount}`,
    blocked: blockedCapabilities.length === 0
      ? '无 adapter 阻断'
      : `${blockedCapabilities.length} 项不能写入，必须由模块 owner 补专用 DRAFT command`,
    writeBoundary: `本 readiness 预计业务写入 ${report.expectedBusinessWriteCount}；实际写入必须另行逐项确认`,
    publishBoundary: `${report.publishState} · ${report.activationState}`,
    blockedCapabilities
  }
}

export function canOpenCapabilityDraftConfirmation(item: CapabilityAdapterReadiness): boolean {
  return item.availability === 'AVAILABLE_DRAFT'
    && item.expectedBusinessWriteCount === 1
    && item.allowedTargetStates.every((state) => ['DRAFT', 'TEMPLATE', 'PLAN'].includes(state))
    && item.publishState === 'NOT_PUBLISHED'
    && item.activationState === 'NOT_ACTIVATED'
}
