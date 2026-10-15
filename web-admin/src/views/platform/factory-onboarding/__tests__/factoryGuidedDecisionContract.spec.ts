import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const api = readFileSync(resolve(here, '..', '..', '..', '..', 'api', 'factoryOnboardingApi.ts'), 'utf8')
const page = readFileSync(resolve(here, '..', 'index.vue'), 'utf8')
const blocks = readFileSync(resolve(here, '..', 'factoryConversationBlocks.ts'), 'utf8')
const packet = readFileSync(resolve(here, '..', 'components', 'FactoryConversationDecisionPacket.vue'), 'utf8')
const processTable = readFileSync(resolve(here, '..', 'components', 'FactoryConversationProcessTable.vue'), 'utf8')
const productTable = readFileSync(resolve(here, '..', 'components', 'FactoryConversationProductTable.vue'), 'utf8')
const domainCard = readFileSync(resolve(here, '..', 'components', 'FactoryConversationDomainCard.vue'), 'utf8')

describe('Factory Blueprint strong-guidance decision contract', () => {
  it('uses a typed server-owned current decision and unresolved list', () => {
    expect(api).toContain('export interface FactoryDecisionPacket')
    expect(api).toContain("state: 'PENDING' | 'UNRESOLVED' | string")
    expect(api).toContain('unknownAnswerValue: string')
    expect(api).toContain('downstreamImpacts: string[]')
    expect(api).toContain('currentDecision?: FactoryDecisionPacket | null')
    expect(api).toContain('unresolvedDecisions?: FactoryDecisionPacket[]')
  })

  it('renders rationale, choices, recommendation boundary, impact, and explicit unknown', () => {
    expect(packet).toContain('为什么要问')
    expect(packet).toContain('参考建议')
    expect(packet).toContain('确认后会影响')
    expect(packet).toContain('例子只是可编辑提示，不会静默成为答案')
    expect(packet).toContain('暂时不确定，先问下一项')
    expect(packet).toContain('确认本题')
    expect(packet).toContain('服务端可确认选项')
    expect(packet).toContain('directOptions')
    expect(packet).toContain('@click="choose(option)"')
    expect(page).toContain('@confirm-multi="confirmMultiDecision"')
    expect(packet).toContain(':focus-visible')
    expect(packet).toContain('@media (prefers-reduced-motion: reduce)')
  })

  it('submits the backend-provided unknown sentinel and returns to unresolved decisions', () => {
    expect(page).toContain(':packet="currentDecision"')
    expect(page).toContain('@defer="deferCurrentDecision"')
    expect(page).toContain('decision.unknownAnswerValue')
    expect(page).toContain("next.state === 'UNRESOLVED'")
    expect(page).toContain('isChoice && !currentItemInStructuredCard && !currentDecision')
    expect(page).toContain('完整方案还不能确认')
  })

  it('prefills server-owned references without silently confirming them', () => {
    expect(blocks).toContain('suggestedAnswersForItems')
    expect(blocks).toContain('mergeSuggestedAnswers')
    expect(page).toContain('这些内容尚未保存')
    expect(page).toContain('currentDecision && !currentDecisionInStructuredCard')
    expect(page).toContain('currentDecision.value.field === currentItem.value?.name')
    expect(domainCard).toContain('参考值只有提交这张卡后才会成为答案')
    expect(processTable).toContain('参考预填 ${suggestedFieldCount} 项 · 待确认')
    expect(productTable).toContain('参考值待确认')
  })

  it('keeps the reference flow and full checklist keyboard-expandable on compact screens', () => {
    expect(page).toContain('class="progress-disclosure"')
    expect(page).toContain(':open="!compactProgressByDefault"')
    expect(page).toContain('查看平台建议和全部进度')
    expect(page).toContain('.progress-disclosure > summary:focus-visible')
  })

  it('keeps compact process references wide enough to review and edit', () => {
    expect(page).toContain('.structured-turn > .av { display: none; }')
    expect(processTable).toContain('grid-template-columns: minmax(0, 1fr)')
  })

  it('keeps the global customer-service overlay out of the guided workspace', () => {
    expect(page).toContain(':global(.inline-cs) { display: none !important; }')
    expect(page).not.toContain(':global(.inline-cs__bubble)')
  })
})
