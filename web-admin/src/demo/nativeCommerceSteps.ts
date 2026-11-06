import type { DemoEntity } from './f006Flow'
import type { NativeDemoAdapter, NativeDemoContext, NativeDemoStepId } from './f006NativeBridge'

/** Small, page-owned adapter builders for the F006 native demonstration.
 * The callbacks intentionally receive the real page context: callers fill the
 * existing Vue model and click the existing DOM button/handler.  This module
 * contains no request client and therefore cannot bypass the native page.
 */
export type NativePageAction = (context: NativeDemoContext) => Promise<void | DemoEntity>

export function commerceAdapter(
  step: NativeDemoStepId,
  canUse: () => boolean,
  action: NativePageAction,
  prepare?: NativePageAction,
): NativeDemoAdapter {
  return {
    step,
    canUse,
    prepare: async context => { if (prepare) await prepare(context) },
    commit: async context => {
      const entity = await action(context)
      return entity ? { [stepEntity(step)]: entity } : undefined
    },
  }
}

function stepEntity(step: NativeDemoStepId): string {
  if (step.startsWith('purchase-')) return step.includes('receive') ? 'purchaseReceipt' : 'purchaseOrder'
  if (step.startsWith('sales-order')) return 'salesOrder'
  if (step.startsWith('sales-delivery')) return 'salesDelivery'
  if (step.startsWith('sales-shipment') || step.startsWith('sales-warehouse')) return 'salesShipment'
  if (step === 'sales-batch-allocate') return 'salesBatchAllocation'
  return 'commerce'
}

export function entityFromResponse(value: unknown): DemoEntity | undefined {
  if (!value || typeof value !== 'object') return undefined
  const candidate = value as { data?: unknown }
  const entity = candidate.data && typeof candidate.data === 'object' ? candidate.data : value
  return entity as DemoEntity
}
