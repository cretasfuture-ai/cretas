import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const ledger = readFileSync(resolve(here, '..', 'components', 'FactoryConversationFactLedger.vue'), 'utf8')
const productTable = readFileSync(resolve(here, '..', 'components', 'FactoryConversationProductTable.vue'), 'utf8')
const api = readFileSync(resolve(here, '..', '..', '..', '..', 'api', 'factoryOnboardingApi.ts'), 'utf8')

describe('Factory B17 evidence and route-intent surface contract', () => {
  it('只展示服务端事实证据并明确区分旧会话、对话确认和结构化确认', () => {
    expect(api).toContain('factEvidence?: FactoryFactEvidenceItem[]')
    expect(ledger).toContain('用户结构化确认')
    expect(ledger).toContain('用户对话确认')
    expect(ledger).toContain('旧会话 · 来源待核')
    expect(ledger).toContain('参考建议未确认前不会出现')
    expect(ledger).toContain('会话证据 #')
  })

  it('成品卡显式采集复杂路线，但不冒充已经发布 Workflow', () => {
    expect(productTable).toContain('并行、分支、合流、返工或不合格处置')
    expect(productTable).toContain('routeShapeOptions')
    expect(productTable).toContain('routeShapeSuggested')
    expect(productTable).toContain('routeDetailsHint')
    expect(productTable).toContain('确认没有路线例外')
    expect(productTable).toContain('不会自动发布 Workflow')
  })
})
