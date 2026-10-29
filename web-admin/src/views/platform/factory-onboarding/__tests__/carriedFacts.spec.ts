import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(resolve(process.cwd(), 'src/views/platform/factory-onboarding/index.vue'), 'utf8')
const api = readFileSync(resolve(process.cwd(), 'src/api/factoryOnboardingApi.ts'), 'utf8')

// 评审 #1303: 上一版/自由文本的值不是「已确认」, 页面必须让用户确认, 且不显示原始字段名。
describe('对话建厂: 上一版已填写的值需要用户确认', () => {
  it('有「全部确认」和逐项确认, 并真的调用确认接口', () => {
    expect(page).toContain('data-testid="carried-facts"')
    expect(page).toContain('data-testid="confirm-all-carried"')
    expect(page).toContain('confirmCarried([item.field])')
    expect(page).toContain('confirmCarriedFacts(factoryId.value, sessionId.value, fields)')
    expect(api).toContain('/confirm-carried')
  })

  it('不再把原始字段名当名称显示', () => {
    expect(page).not.toMatch(/item\.label \|\| item\.field/)
    expect(page).not.toMatch(/answeredLabels\?\.\[field\] \|\| field/)
  })
})
