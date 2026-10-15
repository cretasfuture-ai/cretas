export const FACTORY_RULE_SCHEMA_VERSION = 'factory-rule/v1' as const
export const FACTORY_RULE_COMPILER_VERSION = 'factory-rule-compiler/v1' as const

export type FactoryRuleKind =
  | 'ROUTE_GUARD' | 'QUALITY_DECISION' | 'CRITICAL_LIMIT'
  | 'CALCULATION' | 'ALLOCATION' | 'ALERT_TRIGGER'
export type FactoryRuleValueType =
  | 'BOOLEAN' | 'DECIMAL' | 'QUANTITY' | 'DURATION'
  | 'STRING' | 'ENUM' | 'DATETIME' | 'INTEGER'
export type FactoryRuleUnknownPolicy = 'HOLD_FOR_REVIEW' | 'BLOCK' | 'DISPLAY_PARTIAL'
export type FactoryRuleTriState = 'TRUE' | 'FALSE' | 'UNKNOWN'

export interface FactoryRuleVariableExpression {
  op: 'VAR'
  variableId: string
  valueType: FactoryRuleValueType
  unit: string
}

export interface FactoryRuleConstantExpression {
  op: 'CONST'
  constantValue: string
  valueType: FactoryRuleValueType
  unit: string
  evidenceRef: string
  effectiveVersion: string
}

export interface FactoryRuleLogicalExpression {
  op: 'ALL' | 'ANY'
  args: FactoryRuleExpression[]
}

export interface FactoryRuleUnaryExpression {
  op: 'NOT' | 'IS_KNOWN' | 'ABS'
  value: FactoryRuleExpression
}

export interface FactoryRuleBinaryExpression {
  op: 'EQ' | 'NE' | 'GT' | 'GTE' | 'LT' | 'LTE'
    | 'ADD' | 'SUBTRACT' | 'MULTIPLY' | 'MIN' | 'MAX' | 'WITHIN'
  left: FactoryRuleExpression
  right: FactoryRuleExpression
}

export interface FactoryRuleDivideExpression {
  op: 'DIVIDE'
  left: FactoryRuleExpression
  right: FactoryRuleExpression
  scale: number
  roundingMode: 'UP' | 'DOWN' | 'CEILING' | 'FLOOR' | 'HALF_UP' | 'HALF_DOWN' | 'HALF_EVEN'
}

export interface FactoryRuleBetweenExpression {
  op: 'BETWEEN'
  value: FactoryRuleExpression
  lower: FactoryRuleExpression
  upper: FactoryRuleExpression
  inclusiveLower: boolean
  inclusiveUpper: boolean
}

export interface FactoryRuleInExpression {
  op: 'IN'
  value: FactoryRuleExpression
  values: FactoryRuleExpression[]
}

export interface FactoryRuleRoundExpression {
  op: 'ROUND'
  value: FactoryRuleExpression
  scale: number
  roundingMode: FactoryRuleDivideExpression['roundingMode']
}

export interface FactoryRuleConsecutiveExpression {
  op: 'CONSECUTIVE_COUNT'
  left: FactoryRuleVariableExpression
  count: number
  window: string
}

export interface FactoryRuleAggregateExpression {
  op: 'WINDOW_AGGREGATE'
  left: FactoryRuleVariableExpression
  aggregate: 'SUM' | 'AVG' | 'MIN' | 'MAX' | 'COUNT'
  window: string
  count: number
}

export type FactoryRuleExpression =
  | FactoryRuleVariableExpression
  | FactoryRuleConstantExpression
  | FactoryRuleLogicalExpression
  | FactoryRuleUnaryExpression
  | FactoryRuleBinaryExpression
  | FactoryRuleDivideExpression
  | FactoryRuleBetweenExpression
  | FactoryRuleInExpression
  | FactoryRuleRoundExpression
  | FactoryRuleConsecutiveExpression
  | FactoryRuleAggregateExpression

export interface FactoryRuleInputValue {
  type: FactoryRuleValueType
  value: string
  unit: string
  observations: Array<{ at: string; value: string; unit: string }>
}

export interface FactoryRuleTestVector {
  id: string
  inputs: Record<string, FactoryRuleInputValue>
  expected: string
  evaluationTime: string
  evidenceRefs: string[]
}

export interface FactoryRuleDefinition {
  schemaVersion: typeof FACTORY_RULE_SCHEMA_VERSION
  factoryId: string
  ruleId: string
  kind: FactoryRuleKind
  resultType: FactoryRuleValueType
  expression: FactoryRuleExpression
  unknownPolicy: FactoryRuleUnknownPolicy
  variableCatalogVersion: string
  variableCatalogDigest: string
  unitCatalogDigest: string
  compilerVersion: typeof FACTORY_RULE_COMPILER_VERSION
  evidenceRefs: string[]
  testVectors: FactoryRuleTestVector[]
}

export interface FactoryRulePreview {
  schemaVersion: typeof FACTORY_RULE_SCHEMA_VERSION
  ruleId: string
  contentHash: string
  status: 'BLOCKED' | 'PREVIEW_READY'
  validation: {
    valid: boolean
    inferredResultType: FactoryRuleValueType | null
    nodeCount: number
    maxDepth: number
    issues: Array<{
      code: string
      severity: 'BLOCKING' | 'WARNING'
      path: string
      message: string
      recovery: string
    }>
    businessWriteCount: 0
  }
  testVectorResults: Array<{
    id: string
    expected: string
    actual: string
    passed: boolean
    message: string
  }>
  publicationState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
  runtimeBindingState: 'NOT_BOUND_TO_RUNTIME'
  businessWriteCount: 0
}

export function summarizeFactoryRulePreview(preview: FactoryRulePreview) {
  const passed = preview.testVectorResults.filter(item => item.passed).length
  return {
    status: preview.status,
    blockingIssues: preview.validation.issues.filter(item => item.severity === 'BLOCKING').length,
    testVectors: `${passed}/${preview.testVectorResults.length}`,
    runtimeBoundary: '仅隔离模拟，未发布、未激活、未绑定生产运行时' as const,
  }
}

export function unknownPolicyLabel(policy: FactoryRuleUnknownPolicy): string {
  return {
    HOLD_FOR_REVIEW: '缺值时隔离并转人工复核',
    BLOCK: '缺值时阻断',
    DISPLAY_PARTIAL: '仅展示已算字段并标出缺口',
  }[policy]
}
