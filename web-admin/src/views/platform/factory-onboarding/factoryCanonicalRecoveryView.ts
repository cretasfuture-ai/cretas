export interface CanonicalRecoveryHydrationResult {
  canonicalBlueprintId?: string | null
  canonicalFactoryId?: string | null
  canonicalSessionId?: string | null
}

export interface CanonicalRecoveryHydrationOptions {
  expectedBlueprintId: string
  expectedFactoryId: string
  expectedSessionId: string
  hydrate: () => Promise<CanonicalRecoveryHydrationResult>
  onStart: () => void
  onReady: () => void
  onError: (error: unknown) => void
}

/**
 * Keep the saved blueprint view closed until its canonical source identity has
 * been read back. A canonical session may belong to another typed actor, so
 * this view deliberately does not probe that session's conversation progress.
 */
export async function hydrateCanonicalRecoveryView(
  options: CanonicalRecoveryHydrationOptions,
): Promise<boolean> {
  options.onStart()
  try {
    const result = await options.hydrate()
    if (result.canonicalBlueprintId !== options.expectedBlueprintId
      || result.canonicalFactoryId !== options.expectedFactoryId
      || result.canonicalSessionId !== options.expectedSessionId) {
      throw new Error('已保存蓝图的蓝图、工厂或访谈身份不一致，暂未打开编辑器')
    }
    options.onReady()
    return true
  } catch (error) {
    options.onError(error)
    return false
  }
}

export interface RecoveredConversationProgress {
  factoryId?: string | null
  sessionId?: string | null
}

export interface RecoveredConversationRestoreOptions<T extends RecoveredConversationProgress> {
  expectedFactoryId: string
  expectedSessionId: string
  load: () => Promise<T>
  onStart: () => void
  onRestored: (progress: T) => void
  onError: (error: unknown) => void
}

/**
 * Return to the exact server conversation that was shown before opening the
 * saved blueprint. A mismatched response is rejected before page state changes.
 */
export async function restoreRecoveredConversationView<T extends RecoveredConversationProgress>(
  options: RecoveredConversationRestoreOptions<T>,
): Promise<boolean> {
  options.onStart()
  try {
    const progress = await options.load()
    if (progress.factoryId !== options.expectedFactoryId
      || progress.sessionId !== options.expectedSessionId) {
      throw new Error('恢复的工厂或访谈身份已变化，暂未切换回该访谈')
    }
    options.onRestored(progress)
    return true
  } catch (error) {
    options.onError(error)
    return false
  }
}
