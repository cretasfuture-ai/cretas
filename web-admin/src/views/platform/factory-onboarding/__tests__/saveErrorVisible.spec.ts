import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { moduleConfigProblems } from '../interviewModuleCatalog'

const here = dirname(fileURLToPath(import.meta.url))
const page = readFileSync(resolve(here, '../index.vue'), 'utf8')
const editor = readFileSync(resolve(here, '../components/FactoryAssemblyDetailEditor.vue'), 'utf8')

describe('an empty module row is flagged before save (D-S2-17 follow-up)', () => {
  it('names the row and what to do, for empty and unknown ids', () => {
    expect(moduleConfigProblems([{ moduleId: 'quality' }, { moduleId: '' }])).toEqual([
      '前端模块裁剪第 2 行还没有选模块，请选一个模块或删除这一行'])
    expect(moduleConfigProblems([{ moduleId: 'quality-check' }])[0]).toContain('不可用')
    expect(moduleConfigProblems([{ moduleId: 'quality' }, { moduleId: ' Hr ' }])).toEqual([])
    expect(moduleConfigProblems(undefined)).toEqual([])
  })

  it('the row shows its own error and the save path refuses before calling the server', () => {
    expect(editor).toContain('data-testid="module-id-empty"')
    expect(page).toMatch(/moduleConfigProblems\(generated\.value\.suggestedData\.moduleConfigs\)/)
  })
})

describe('a failed save/validate is always visible (not only the far-away top banner)', () => {
  it('every flow issue also raises a sticky error toast and scrolls the banner into view', () => {
    const show = page.slice(page.indexOf('function showFlowIssue('), page.indexOf('async function retryFlowIssue'))
    expect(show).toContain('ElMessage.error(')
    expect(show).toContain('duration: 0')
    expect(show).toContain("scrollIntoView({ block: 'center'")
  })

  it('clearing the issue closes the toast so a stale error never lingers', () => {
    const clear = page.slice(page.indexOf('function clearFlowIssue'), page.indexOf('function showFlowIssue('))
    expect(clear).toContain('flowToast?.close()')
  })

  it('every catch in the save/validate/simulate/publish paths reports through showFlowIssue', () => {
    for (const fn of ['async function refreshPreview', 'async function validateBlueprint', 'async function simulateBlueprint', 'async function publishBlueprint']) {
      const start = page.indexOf(fn)
      expect(start, fn).toBeGreaterThan(-1)
      const body = page.slice(start, page.indexOf('\nasync function', start + 10) > 0 ? page.indexOf('\nasync function', start + 10) : undefined)
      expect(body, `${fn} 的 catch 必须走 showFlowIssue`).toContain('showFlowIssue(')
    }
  })
})
