export interface FactoryPreviewRecoveryOptions {
  generationReceived: boolean
  retryGeneration: () => Promise<void>
  retrySave: () => Promise<void>
}

export function factoryPreviewRecovery(options: FactoryPreviewRecoveryOptions) {
  if (options.generationReceived) {
    return {
      title: '配置建议还需修正，尚未保存蓝图',
      sideEffect: '没有写入工厂业务数据。下方配置建议仍可编辑，修正后重新保存。',
      retryLabel: '保存修正后的装配预览',
      retry: options.retrySave,
    }
  }
  return {
    title: '装配预览没有生成，对话内容仍然保留',
    sideEffect: '本阶段没有写入工厂，可以保留对话重新生成建议。',
    retryLabel: '重试生成装配预览',
    retry: options.retryGeneration,
  }
}

export async function saveAndEnterFactoryPreview(
  persist: () => Promise<boolean>, enter: () => void,
) {
  if (await persist()) enter()
}
