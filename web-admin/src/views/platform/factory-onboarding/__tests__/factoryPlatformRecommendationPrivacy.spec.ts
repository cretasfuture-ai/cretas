import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const page = readFileSync(resolve(here, '..', 'index.vue'), 'utf8')
const api = readFileSync(resolve(here, '..', '..', '..', '..', 'api', 'factoryOnboardingApi.ts'), 'utf8')

describe('Factory Onboarding platform recommendation privacy', () => {
  it('shows platform-owned suggestions without source-customer language', () => {
    expect(page).toContain('平台建议流程')
    expect(page).toContain('请按当前工厂实际情况确认')
    expect(page).not.toContain('六膳门食品科技')
    expect(page).not.toContain('东海冷冻水产加工厂')
    expect(page).not.toContain('同行业真实工厂')
    expect(page).not.toContain('相似工厂')
    expect(page).not.toContain('相似客户')
    expect(page).not.toContain('取自 {{')
  })

  it('does not expose source-factory provenance in the public TypeScript contract', () => {
    expect(api).toContain('platformRecommendation?: PlatformInterviewRecommendation | null')
    expect(api).toContain('recommendationNote?: string | null')
    expect(api).not.toContain('caseRef?:')
    expect(api).not.toContain('caseNote?:')
    expect(api).not.toContain('derivedFrom?:')
    expect(api).not.toContain('sourceFactoryId')
    expect(api).not.toContain('sourceFactoryName')
  })
})
