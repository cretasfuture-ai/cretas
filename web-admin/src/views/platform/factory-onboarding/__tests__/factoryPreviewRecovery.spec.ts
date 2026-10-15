import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { factoryPreviewRecovery, saveAndEnterFactoryPreview } from '../factoryPreviewRecovery'

describe('recovery of real generated proposals rejected at canonical save', () => {
  it('saves the current corrected proposal without regenerating or losing edits', async () => {
    const proposal: { unit: string; level1Unit: string; coefficient: number | null } = {
      unit: '盒', level1Unit: '盒', coefficient: 500,
    }
    const retryGeneration = vi.fn(async () => { proposal.coefficient = 500 })
    const persist = vi.fn(async () => proposal.level1Unit === '' && proposal.coefficient === null)
    const enter = vi.fn()
    const retrySave = async () => { await saveAndEnterFactoryPreview(persist, enter) }
    const recovery = factoryPreviewRecovery({ generationReceived: true, retryGeneration, retrySave })
    expect(recovery.retryLabel).toBe('保存修正后的装配预览')
    await recovery.retry()
    expect(enter).not.toHaveBeenCalled()
    proposal.level1Unit = ''; proposal.coefficient = null
    await recovery.retry()
    expect(persist).toHaveBeenCalledTimes(2)
    expect(enter).toHaveBeenCalledTimes(1)
    expect(retryGeneration).not.toHaveBeenCalled()
    expect(proposal.coefficient).toBeNull()
  })

  it('regenerates when no result arrived, instead of saving stale prior suggestions', async () => {
    const retryGeneration = vi.fn(async () => {})
    const retrySave = vi.fn(async () => {})
    const recovery = factoryPreviewRecovery({ generationReceived: false, retryGeneration, retrySave })
    await recovery.retry()
    expect(retryGeneration).toHaveBeenCalledTimes(1)
    expect(retrySave).not.toHaveBeenCalled()
  })

  it('does not enter preview for a rejected persistence result', async () => {
    const enter = vi.fn()
    await saveAndEnterFactoryPreview(async () => false, enter)
    expect(enter).not.toHaveBeenCalled()
  })

  it('does not enter preview when persistence throws', async () => {
    const enter = vi.fn()
    await expect(saveAndEnterFactoryPreview(async () => { throw new Error('canonical rejected') }, enter))
      .rejects.toThrow('canonical rejected')
    expect(enter).not.toHaveBeenCalled()
  })

  it('waits for authoritative persistence to resolve before entering', async () => {
    let release!: (value: boolean) => void
    const result = new Promise<boolean>(resolve => { release = resolve })
    const enter = vi.fn()
    const pending = saveAndEnterFactoryPreview(() => result, enter)
    expect(enter).not.toHaveBeenCalled()
    release(true)
    await pending
    expect(enter).toHaveBeenCalledTimes(1)
  })

  it('wires the live page catch and repeated save rejection to the same recovery action', () => {
    const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '..', 'index.vue'), 'utf8')
      .replace(/<!--[\s\S]*?-->|\/\*[\s\S]*?\*\/|^\s*\/\/.*$/gm, '')
    expect(source).toContain('let generationReceived = false')
    expect(source).toContain('generationReceived = true')
    expect(source).toContain('generationReceived, retryGeneration: generate, retrySave: retrySavedPreview')
    expect(source).toContain('}, recovery.retry)')
    expect(source).toContain('}, retrySavedPreview)')
    expect(source).toContain('saveAndEnterFactoryPreview(refreshPreview, () => {')
    expect(source).toContain('proposalNeedsCorrection.value = false')
    expect(source).toContain('proposalNeedsCorrection.value = generationReceived')
    expect(source).toContain('v-if="step === 0 && proposalNeedsCorrection && generated?.suggestedData && generated.schemas"')
    const rejectedEditor = source.split('data-testid="rejected-proposal-editor"')[1]?.split('</el-card>')[0]
    expect(rejectedEditor).toContain('<FactoryAssemblyDetailEditor')
    expect(rejectedEditor).toContain(':suggested-data="generated.suggestedData"')
    expect(rejectedEditor).toContain('@changed="markPreviewDirty"')
    expect(rejectedEditor).toContain('@replace="replaceGeneratedPayload"')
  })
})
