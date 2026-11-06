import { onActivated, onBeforeUnmount, onDeactivated, onMounted, shallowRef } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import { sandboxSession } from './f006Sandbox'
import { DEMO_RUN_KEY, DEMO_STEPS, type DemoContext, type DemoEntity, type DemoState, type DemoStepId } from './f006Flow'
const programIds = DEMO_STEPS.flatMap(step => [step.id, ...(step.id === 'sales-order-create' ? ['purchase-link-sales'] : []), ...(step.id === 'finished-goods-receive' ? ['supplemental-costs'] : [])])

export type NativeDemoStepId = DemoStepId | 'supplemental-costs' | 'purchase-link-sales'
export interface NativeResponseMatch { method: 'POST' | 'PUT' | 'PATCH' | 'DELETE'; path: string | RegExp }
export interface NativeDemoUi {
  fill(label: string, setter: () => void | Promise<void>, selector?: string): Promise<void>
  click(selector: string, label?: string): Promise<void>
  select(selector: string, optionText: string): Promise<void>
  input(selector: string, value: string | number): Promise<void>
  upload(selector: string, file: File): Promise<void>
  waitFor(selector: string, timeoutMs?: number): Promise<HTMLElement>
  until(predicate: () => boolean | Promise<boolean>, label: string, timeoutMs?: number): Promise<void>
  hold(label: string, seconds?: number): Promise<void>
  navigate(path: string): Promise<void>
  read(path: string): Promise<unknown>
  capture(match: NativeResponseMatch, action: () => Promise<void>, timeoutMs?: number): Promise<unknown>
  remember(key: string, entity: DemoEntity): void
  receiptFile(purchaseOrderNumber: string): Promise<File>
}
export interface NativeDemoContext { state: DemoState; context: DemoContext; ui: NativeDemoUi }
export interface NativeDemoAdapter {
  step: NativeDemoStepId
  canUse: () => boolean
  prepare: (context: NativeDemoContext) => Promise<void>
  commit: (context: NativeDemoContext) => Promise<Record<string, DemoEntity> | void>
}
export const nativeDemoState = shallowRef<DemoState | null>(null)
const adapters = new Map<NativeDemoStepId, Map<symbol, NativeDemoAdapter>>()
export function assertNativeDemoScope(state: DemoState): void {
  const auth = useAuthStore()
  if (auth.factoryId !== 'F006' || Number(auth.user?.id) !== 1309 || auth.currentRole !== 'factory_super_admin' || sandboxSession.value?.status !== 'READY' || sandboxSession.value.sessionId !== state.sandboxSessionId || state.factoryId !== 'F006' || !state.runKey.startsWith(DEMO_RUN_KEY + '-') || state.schemaVersion !== 2 || !Number.isInteger(state.currentStep) || state.currentStep < 0 || state.currentStep > programIds.length || nativeDemoState.value !== state) throw new Error('原生演示身份、会话或资料不一致，已停止')
}
export function useNativeDemoAdapters(owner: string, definitions: readonly NativeDemoAdapter[]): void {
  const token = Symbol(owner)
  const register = () => { for (const adapter of definitions) { const live=adapters.get(adapter.step)||new Map<symbol,NativeDemoAdapter>();live.delete(token);live.set(token,adapter);adapters.set(adapter.step,live) } }
  const dispose = () => { for (const adapter of definitions) {const live=adapters.get(adapter.step);live?.delete(token);if(live?.size===0)adapters.delete(adapter.step)} }
  onMounted(register); onActivated(register); onDeactivated(dispose); onBeforeUnmount(dispose)
}
export function nativeAdapterFor(step: NativeDemoStepId, state: DemoState): NativeDemoAdapter | undefined {
  assertNativeDemoScope(state)
  const adapter = Array.from(adapters.get(step)?.values()||[]).at(-1)
  if (!adapter || !adapter.canUse()) return undefined
  const ensure = () => { assertNativeDemoScope(state); if (!adapter.canUse()) throw new Error('当前原生页面没有此操作权限，演示已停止') }
  return { step, canUse: adapter.canUse, prepare: async context => { ensure(); await adapter.prepare(context) }, commit: async context => { ensure(); return adapter.commit(context) } }
}
export function nativePurchaseApprovalContext(entityId: unknown): { runKey: string } | undefined {
  const state = nativeDemoState.value
  if (!state) return undefined
  try { assertNativeDemoScope(state) } catch { return undefined }
  return programIds[state.currentStep] === 'purchase-approve' && String(state.entities.purchaseOrder?.id) === String(entityId) ? {runKey:state.runKey} : undefined
}
