import { describe, expect, it } from 'vitest'
import {
  summarizeFactoryAcceptance,
  type FactoryAcceptanceAudit
} from '../factoryAcceptanceAudit'

const blocked = (): FactoryAcceptanceAudit => ({
  schemaVersion: 'factory-blueprint-acceptance-audit/v1',
  factoryId: 'F999',
  factoryName: '六扇门',
  blueprintId: 'liushanmen-blueprint',
  blueprintVersion: 1,
  candidateDigest: 'candidate-digest',
  automatedGateStatus: 'BLOCKED_PLATFORM_GAPS',
  userAcceptanceStatus: 'PENDING',
  overallStatus: 'BLOCKED',
  missingCapabilityCommands: Array.from({ length: 23 }, (_, index) => `capability-${index}`),
  missingUserRoles: ['FACTORY_OWNER', 'PRODUCTION_OWNER', 'QUALITY_OWNER', 'WAREHOUSE_OWNER'],
  passedScenarios: ['HAPPY_PATH', 'ZERO_PRODUCTION_WRITE'],
  issues: [],
  productionBusinessWriteCount: 0,
  deploymentState: 'ISOLATED_NOT_DEPLOYED',
  acceptanceBoundary: 'CODEX_AUDIT_IS_NOT_USER_ACCEPTANCE'
})

describe('factory acceptance audit presentation', () => {
  it('states the current blocked and pending truth without calling Codex audit user acceptance', () => {
    const summary = summarizeFactoryAcceptance(blocked())

    expect(summary.headline).toBe('六扇门 当前版本尚未完成真实用户验收')
    expect(summary.automated).toContain('BLOCKED_PLATFORM_GAPS')
    expect(summary.users).toContain('FACTORY_OWNER')
    expect(summary.platform).toBe('仍缺 23 项模块命令')
    expect(summary.runtime).toBe('ISOLATED_NOT_DEPLOYED · 生产业务写入 0')
    expect(summary.canClaimAccepted).toBe(false)
  })

  it('does not claim accepted when only one of automated or user gates passes', () => {
    expect(summarizeFactoryAcceptance({
      ...blocked(), automatedGateStatus: 'PASS', missingCapabilityCommands: []
    }).canClaimAccepted).toBe(false)
    expect(summarizeFactoryAcceptance({
      ...blocked(), userAcceptanceStatus: 'ACCEPTED', missingUserRoles: []
    }).canClaimAccepted).toBe(false)
  })

  it('claims accepted only when both gates and all explicit absence checks agree', () => {
    const accepted: FactoryAcceptanceAudit = {
      ...blocked(),
      automatedGateStatus: 'PASS',
      userAcceptanceStatus: 'ACCEPTED',
      overallStatus: 'ACCEPTED',
      missingCapabilityCommands: [],
      missingUserRoles: []
    }
    expect(summarizeFactoryAcceptance(accepted).canClaimAccepted).toBe(true)
    expect(summarizeFactoryAcceptance(accepted).headline).toContain('已完成真实用户验收')
  })

  it('keeps acceptance blocked when runtime truth reports deployment or a production write', () => {
    const otherwiseAccepted: FactoryAcceptanceAudit = {
      ...blocked(),
      automatedGateStatus: 'PASS',
      userAcceptanceStatus: 'ACCEPTED',
      overallStatus: 'ACCEPTED',
      missingCapabilityCommands: [],
      missingUserRoles: []
    }

    expect(summarizeFactoryAcceptance({
      ...otherwiseAccepted, deploymentState: 'PRODUCTION'
    }).canClaimAccepted).toBe(false)
    expect(summarizeFactoryAcceptance({
      ...otherwiseAccepted, productionBusinessWriteCount: 1
    }).canClaimAccepted).toBe(false)
  })
})
