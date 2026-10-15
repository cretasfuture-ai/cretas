export type AutomatedGateStatus = 'PASS' | 'BLOCKED_PLATFORM_GAPS' | 'BLOCKED_EVIDENCE_GAPS'
export type UserAcceptanceStatus = 'ACCEPTED' | 'REJECTED' | 'PENDING'

export interface AcceptanceAuditIssue {
  code: string
  subject: string
  message: string
  recovery: string
}

export interface FactoryAcceptanceAudit {
  schemaVersion: 'factory-blueprint-acceptance-audit/v1'
  factoryId: string
  factoryName: string
  blueprintId: string
  blueprintVersion: number
  candidateDigest: string
  automatedGateStatus: AutomatedGateStatus
  userAcceptanceStatus: UserAcceptanceStatus
  overallStatus: 'ACCEPTED' | 'BLOCKED'
  missingCapabilityCommands: string[]
  missingUserRoles: string[]
  passedScenarios: string[]
  issues: AcceptanceAuditIssue[]
  productionBusinessWriteCount: number
  deploymentState: string
  acceptanceBoundary: 'CODEX_AUDIT_IS_NOT_USER_ACCEPTANCE'
}

export function summarizeFactoryAcceptance(audit: FactoryAcceptanceAudit) {
  const canClaimAccepted = audit.overallStatus === 'ACCEPTED'
    && audit.automatedGateStatus === 'PASS'
    && audit.userAcceptanceStatus === 'ACCEPTED'
    && audit.missingCapabilityCommands.length === 0
    && audit.missingUserRoles.length === 0
    && audit.productionBusinessWriteCount === 0
    && audit.deploymentState === 'ISOLATED_NOT_DEPLOYED'
    && audit.acceptanceBoundary === 'CODEX_AUDIT_IS_NOT_USER_ACCEPTANCE'
  return {
    headline: canClaimAccepted
      ? `${audit.factoryName} 当前版本已完成真实用户验收`
      : `${audit.factoryName} 当前版本尚未完成真实用户验收`,
    automated: audit.automatedGateStatus === 'PASS'
      ? '自动化验收闸通过'
      : `自动化验收闸阻断：${audit.automatedGateStatus}`,
    users: audit.userAcceptanceStatus === 'PENDING'
      ? `待真实用户角色：${audit.missingUserRoles.join('、') || '未明确'}`
      : `真实用户结论：${audit.userAcceptanceStatus}`,
    platform: audit.missingCapabilityCommands.length === 0
      ? '模块命令无缺口'
      : `仍缺 ${audit.missingCapabilityCommands.length} 项模块命令`,
    runtime: `${audit.deploymentState} · 生产业务写入 ${audit.productionBusinessWriteCount}`,
    canClaimAccepted
  }
}
