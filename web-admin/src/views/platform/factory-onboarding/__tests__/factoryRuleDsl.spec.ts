import { describe, expect, it } from 'vitest'
import {
  FACTORY_RULE_COMPILER_VERSION,
  FACTORY_RULE_SCHEMA_VERSION,
  summarizeFactoryRulePreview,
  unknownPolicyLabel,
  type FactoryRuleDefinition,
  type FactoryRulePreview,
} from '../factoryRuleDsl'

describe('factory-rule/v1 read-only contract', () => {
  it('represents a typed critical limit without raw expression text', () => {
    const rule: FactoryRuleDefinition = {
      schemaVersion: FACTORY_RULE_SCHEMA_VERSION,
      factoryId: 'F999',
      ruleId: 'lsm-cook-release',
      kind: 'CRITICAL_LIMIT',
      resultType: 'BOOLEAN',
      expression: {
        op: 'GTE',
        left: { op: 'VAR', variableId: 'process.coreTemperature', valueType: 'QUANTITY', unit: 'Cel' },
        right: {
          op: 'CONST', constantValue: '75', valueType: 'QUANTITY', unit: 'Cel',
          evidenceRef: 'fact://limit/confirmed', effectiveVersion: '2026-08-31',
        },
      },
      unknownPolicy: 'HOLD_FOR_REVIEW',
      variableCatalogVersion: 'variables/v1',
      variableCatalogDigest: 'variables-digest',
      unitCatalogDigest: 'units-digest',
      compilerVersion: FACTORY_RULE_COMPILER_VERSION,
      evidenceRefs: ['fact://limit/confirmed'],
      testVectors: [],
    }

    const serialized = JSON.stringify(rule)
    expect(serialized).toContain('"op":"GTE"')
    expect(serialized).not.toMatch(/rawSpel|rawAiText|script|execute|sql/i)
  })

  it('makes UNKNOWN disposition explicit', () => {
    expect(unknownPolicyLabel('HOLD_FOR_REVIEW')).toBe('缺值时隔离并转人工复核')
    expect(unknownPolicyLabel('BLOCK')).toBe('缺值时阻断')
    expect(unknownPolicyLabel('DISPLAY_PARTIAL')).toContain('标出缺口')
  })

  it('summarizes validation and test vectors without exposing publish actions', () => {
    const preview: FactoryRulePreview = {
      schemaVersion: FACTORY_RULE_SCHEMA_VERSION,
      ruleId: 'lsm-cook-release', contentHash: 'a'.repeat(64), status: 'PREVIEW_READY',
      validation: { valid: true, inferredResultType: 'BOOLEAN', nodeCount: 3, maxDepth: 2,
        issues: [], businessWriteCount: 0 },
      testVectorResults: [
        { id: 'pass', expected: 'TRUE', actual: 'TRUE', passed: true, message: 'PASS' },
        { id: 'missing', expected: 'UNKNOWN', actual: 'UNKNOWN', passed: true, message: 'PASS' },
      ],
      publicationState: 'NOT_PUBLISHED', activationState: 'NOT_ACTIVATED',
      runtimeBindingState: 'NOT_BOUND_TO_RUNTIME', businessWriteCount: 0,
    }
    expect(summarizeFactoryRulePreview(preview)).toEqual({
      status: 'PREVIEW_READY', blockingIssues: 0, testVectors: '2/2',
      runtimeBoundary: '仅隔离模拟，未发布、未激活、未绑定生产运行时',
    })
    expect(JSON.stringify(preview)).not.toMatch(/publishAction|activateAction|runtimeEndpoint/)
  })
})
