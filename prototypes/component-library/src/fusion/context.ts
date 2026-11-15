import type { InjectionKey, Ref } from 'vue'
import type { SampleData } from '../types'
export const fusionDataKey: InjectionKey<Ref<SampleData | undefined>> = Symbol('fusion-business-data')
