import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

describe('建厂写后复核的同厂权限与移动端布局', () => {
  const here = dirname(fileURLToPath(import.meta.url))
  const page = readFileSync(resolve(here, '..', 'index.vue'), 'utf8')
  const acceptance = readFileSync(resolve(here, '..', 'components', 'FactoryBlueprintAcceptance.vue'), 'utf8')

  it('只有平台超管加载跨工厂选择器，工厂超管沿用 token 所属工厂', () => {
    expect(page).toContain("authStore.hasRole('super_admin')")
    expect(page).toContain('v-if="canSelectFactory"')
    expect(page).toContain('if (canSelectFactory.value)')
    expect(page).toContain('else if (!factoryId.value && authStore.factoryId)')
    expect(page).toContain("factoryId.value = authStore.factoryId")
  })

  it('手机宽度收起并排头部、步骤标题和横排操作，内容保留完整宽度', () => {
    expect(page).toContain('@media (max-width: 560px)')
    expect(page).toContain('.head, .cls-head { align-items: stretch; flex-direction: column; }')
    expect(page).toContain('.steps :deep(.el-step__title) { display: none; }')
    expect(page).toContain('.acts { align-items: stretch; flex-direction: column; }')
    expect(page).toContain('.col { min-width: 0; padding: 10px; }')
  })

  it('三份证据有稳定的视觉角色，类别切换保持短促且支持减弱动态', () => {
    expect(page).toContain('class="col col-planned"')
    expect(page).toContain('class="col col-applied"')
    expect(page).toContain('class="col col-live"')
    expect(page).toContain('<Transition name="review-panel" mode="out-in">')
    expect(page).toContain(':key="current.key"')
    expect(page).toContain('review-panel-enter-active')
    expect(page).toContain('180ms var(--review-ease-out)')
    expect(page).toContain('@media (prefers-reduced-motion: reduce)')
    expect(page).not.toContain('transition: all')
  })

  it('逐屏复核与人工能力验收分开，提交只追加回执且不发布 Workflow', () => {
    expect(page).toContain('getCurrentCanonicalFactoryBlueprint')
    expect(page).toContain('acceptCanonicalFactoryBlueprintCapabilities')
    expect(page).toContain("canonicalBlueprint?.lifecycleState === 'APPLIED'")
    expect(page).toContain('expectedRowVersion: blueprint.rowVersion')
    expect(page).toContain('ElMessageBox.confirm(')
    expect(page).toContain("blueprint.acceptanceState !== 'ACCEPTED'")
    expect(acceptance).toContain('配置写入成功不等于已经验收')
    expect(acceptance).toContain('不会重写配置，也不会发布 Workflow')
    expect(acceptance).toContain("value=\"ACCEPTED\"")
    expect(acceptance).toContain("value=\"REJECTED\"")
    expect(acceptance).toContain("draft.decision !== 'REJECTED' || draft.note.trim().length > 0")
    expect(acceptance).toContain('capability.configurationReceipts.length')
    expect(acceptance).toContain('capability.acceptanceReceipts.length')
    expect(acceptance).toContain('@media (max-width: 900px)')
    expect(acceptance).toContain('.acceptance-item { grid-template-columns: 1fr; }')
  })
})
