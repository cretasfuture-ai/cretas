import { effectScope, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { usePurchaseReturnSource, type PurchaseReturnSourceOrder } from './usePurchaseReturnSource';

const source = (id = 'po'): PurchaseReturnSourceOrder => ({ id, factoryId: 'A', items: [{ materialTypeId: 'm', unit: 'kg', inventoryBaseUnitSnapshot: 'kg', quantity: 10, receivedQuantity: 10, unitPrice: 5, taxRate: 0 }] });
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
const availability = async (_factory: string, id: string) => ({ purchaseOrderId: id, items: [{ materialTypeId: 'm', baseUnit: 'kg', receivedQuantity: 10, returnedQuantity: 0, remainingQuantity: 10 }] });

describe('purchase return source context', () => {
  it('binds a successful source to the factory that read it and clears it on factory switch', async () => {
    const factory = ref('A'), scope = effectScope();
    const state = scope.run(() => usePurchaseReturnSource(factory, { order: vi.fn().mockResolvedValue(source()), baseUnit: vi.fn(), availability }))!;
    expect(await state.open('po')).toBe(true);
    expect(state.sourceFactoryId.value).toBe('A');
    expect(state.visible.value).toBe(true);
    factory.value = 'B';
    expect(state.visible.value).toBe(false);
    expect(state.source.value).toBeNull();
    expect(state.sourceFactoryId.value).toBe('');
    expect(state.items.value).toEqual([]);
    scope.stop();
  });
  it('invalidates a pending read even if the factory switches away and back', async () => {
    const factory = ref('A'), waiting = deferred<PurchaseReturnSourceOrder>(), scope = effectScope();
    const state = scope.run(() => usePurchaseReturnSource(factory, { order: () => waiting.promise, baseUnit: vi.fn(), availability }))!;
    const opening = state.open('po'); factory.value = 'B'; factory.value = 'A'; waiting.resolve(source());
    expect(await opening).toBe(false); expect(state.visible.value).toBe(false); scope.stop();
  });
  it('discards an older row response after a newer row has opened', async () => {
    const old = deferred<PurchaseReturnSourceOrder>(), recent = deferred<PurchaseReturnSourceOrder>(), scope = effectScope();
    const order = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(recent.promise);
    const state = scope.run(() => usePurchaseReturnSource(ref('A'), { order, baseUnit: vi.fn(), availability }))!;
    const a = state.open('old'), b = state.open('new'); recent.resolve(source('new'));
    expect(await b).toBe(true); old.resolve(source('old')); expect(await a).toBe(false);
    expect(state.source.value?.id).toBe('new'); scope.stop();
  });
  it('uses server remaining capacity after previous pending and completed returns', async () => {
    const scope = effectScope();
    const state = scope.run(() => usePurchaseReturnSource(ref('A'), { order: vi.fn().mockResolvedValue(source()), baseUnit: vi.fn(), availability: async (factory, id) => {
      const value = await availability(factory, id); value.items[0].returnedQuantity = 7; value.items[0].remainingQuantity = 3; return value;
    } }))!;
    expect(await state.open('po')).toBe(true); expect(state.items.value[0].maxQuantity).toBe(3); scope.stop();
  });
  it('refuses a capacity result with a mismatching inventory unit', async () => {
    const scope = effectScope();
    const state = scope.run(() => usePurchaseReturnSource(ref('A'), { order: vi.fn().mockResolvedValue(source()), baseUnit: vi.fn(), availability: async (factory, id) => {
      const value = await availability(factory, id); value.items[0].baseUnit = '箱'; return value;
    } }))!;
    await expect(state.open('po')).rejects.toThrow(/库存单位/); expect(state.visible.value).toBe(false); scope.stop();
  });
});
