import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

describe('Factory Blueprint 权威前端契约', () => {
  const here = dirname(fileURLToPath(import.meta.url))
  const api = readFileSync(resolve(here, '..', '..', '..', '..', 'api', 'factoryOnboardingApi.ts'), 'utf8')
  const page = readFileSync(resolve(here, '..', 'index.vue'), 'utf8')

  it('声明版本、作用域、生命周期、验证、模拟和结构化回执', () => {
    expect(api).toContain("schemaVersion: 'factory-blueprint/v1'")
    expect(api).toContain("scopeType: 'FACTORY'")
    expect(api).toContain("| 'SUPERSEDED'")
    expect(api).toContain("| 'ROLLED_BACK'")
    expect(api).toContain('businessWriteCount: number')
    expect(api).toContain('moduleResults: Array<Record<string, unknown>>')
    expect(api).toContain("workflowState: 'NOT_PUBLISHED'")
    expect(api).toContain('createdBy?: number | null')
    expect(api).toContain('updatedBy?: number | null')
    expect(api).toContain('customerSupplied?: string | null')
    expect(api).toContain('conversionEvidence: CanonicalConversionEvidence[]')
    expect(api).toContain('interface CanonicalConversionEvidence')
    expect(api).toContain('sourcePayload: ApplyAIInitRequest')
    expect(api).toContain('configurationRefs: string[]')
    expect(api).toContain('capabilityCatalogVersion: string')
    expect(api).toContain('capabilityCatalogDigest: string')
    expect(api).toContain('configurationTypes: string[]')
    expect(api).toContain('moduleSchemaCodes: string[]')
    expect(api).toContain('sectorProfiles?: string[]')
    expect(api).toContain('authorityRefs?: string[]')
    expect(api).toContain('requirementCodes?: string[]')
    expect(api).toContain("source: 'SERVER_VERSIONED_CAPABILITY_CATALOG'")
    expect(api).toContain("confirmationState: 'PENDING' | 'CONFIRMED'")
    expect(api).toContain('validationIssues: CanonicalBlueprintValidationIssue[]')
    expect(api).toContain('configurationReceipts: Array<Record<string, unknown>>')
    expect(api).toContain('acceptanceReceipts: Array<Record<string, unknown>>')
    expect(api).toContain("acceptanceState: 'NOT_READY' | 'PENDING' | 'PARTIAL' | 'ACCEPTED' | 'REJECTED'")
    expect(api).toContain('operatedBy?: number | null')
    expect(api).toContain("safetyScope: 'CONFIGURATION_APPLY_PREFLIGHT'")
    expect(api).toContain('checks: Record<string, boolean>')
    expect(api).toContain('blockingIssues: CanonicalBlueprintValidationIssue[]')
    expect(api).toContain('limitations: string[]')
  })

  it('所有状态动作都带工厂作用域、行版本和幂等键', () => {
    expect(api).toContain('/ai-initialize/blueprints/draft`')
    expect(api).toContain('/ai-initialize/blueprints/current`')
    expect(api).toContain('/ai-initialize/blueprints/catalog${versionQuery}`')
    expect(api).toContain('getCanonicalFactoryBlueprintVersions')
    expect(api).toContain('/versions/${version}`')
    expect(api).toContain('expectedRowVersion: number')
    expect(api).toContain('idempotencyKey: string')
    expect(api).toContain('customerSupplied?: string')
    expect(api).toContain('conversionEvidence?: CanonicalConversionEvidence[]')
    for (const operation of ['validate', 'simulate', 'publish', 'apply', 'recovery-plan']) {
      expect(api).toContain(`'${operation}'`)
    }
    expect(api).toContain("operationType: 'VALIDATE' | 'SIMULATE' | 'PUBLISH' | 'APPLY' | 'ACCEPT' | 'RECOVERY_PLAN'")
    expect(api).toContain('acceptCanonicalFactoryBlueprintCapabilities')
    expect(api).toContain('/accept`')
    expect(page).toContain('operationKey(operation)')
    expect(page).toContain('expectedRowVersion: blueprint.rowVersion')
    expect(page).toContain('getCanonicalFactoryCapabilityCatalog(factoryId.value, catalogVersion)')
  })

  it('历史版本可按精确版本读取不可变快照，配置预检不冒充 Workflow 仿真', () => {
    expect(api).toContain('interface CanonicalFactoryBlueprintVersionDetail')
    expect(api).toContain('snapshot: Record<string, unknown>')
    expect(api).toContain('simulationExecuted: false')
    expect(page).toContain('Workflow 引擎尚未仿真或发布')
  })

  it('发布和应用只有独立人工确认路径，模拟不复用应用 API', () => {
    const publish = page.slice(page.indexOf('async function publishBlueprint()'), page.indexOf('async function restart()'))
    const apply = page.slice(page.indexOf('async function confirmApply()'), page.indexOf('async function validateBlueprint()'))
    const simulate = page.slice(page.indexOf('async function simulateBlueprint()'), page.indexOf('async function publishBlueprint()'))
    expect(publish).toContain('ElMessageBox.confirm(')
    expect(apply).toContain('ElMessageBox.confirm(')
    expect(simulate).not.toContain('ElMessageBox.confirm(')
    expect(simulate).not.toContain('applyCanonicalFactoryBlueprint')
  })
})
