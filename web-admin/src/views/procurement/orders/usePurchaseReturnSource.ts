import { ref, watch, type Ref } from 'vue';
import { buildPurchaseReturnItems, type PurchaseReturnSourceLine } from './purchaseReturnItems';
import type { PurchaseReturnAvailability } from '@/api/returnOrder';
import { canonicalUnitCode } from '@/utils/unitPricing';

export interface PurchaseReturnSourceOrder extends Record<string, unknown> {
  id: string | number;
  factoryId: string;
  items: PurchaseReturnSourceLine[];
}
interface Readers {
  order(factoryId: string, id: string): Promise<PurchaseReturnSourceOrder>;
  baseUnit(factoryId: string, materialId: string): Promise<string>;
  availability(factoryId: string, id: string): Promise<PurchaseReturnAvailability>;
}

export function usePurchaseReturnSource(factoryId: Readonly<Ref<string>>, readers: Readers) {
  const visible = ref(false);
  const source = ref<PurchaseReturnSourceOrder | null>(null);
  const items = ref<ReturnType<typeof buildPurchaseReturnItems>>([]);
  const sourceFactoryId = ref('');
  let requestId = 0;
  function close() {
    ++requestId;
    visible.value = false;
    source.value = null;
    items.value = [];
    sourceFactoryId.value = '';
  }
  watch(factoryId, close, { flush: 'sync' });
  async function open(id: string): Promise<boolean> {
    close();
    const token = requestId, factory = factoryId.value;
    const current = () => token === requestId && factoryId.value === factory;
    try {
      const order = await readers.order(factory, id);
      if (!current()) return false;
      if (order.factoryId !== factory || String(order.id) !== id) throw new Error('采购退货来源与当前工厂或单据不一致');
      if (!Array.isArray(order.items) || order.items.length === 0) throw new Error('采购单无明细，无法发起退货');
      const missing = [...new Set(order.items.filter(it => !it.inventoryBaseUnitSnapshot).map(it => String(it.materialTypeId || '')).filter(Boolean))];
      const baseUnits = new Map<string, string>();
      const [availability] = await Promise.all([
        readers.availability(factory, id),
        Promise.all(missing.map(async materialId => baseUnits.set(materialId, await readers.baseUnit(factory, materialId)))),
      ]);
      if (!current()) return false;
      if (availability.purchaseOrderId !== id) throw new Error('累计可退额度与源采购单不一致');
      items.value = buildPurchaseReturnItems(order.items, baseUnits).map(item => {
        const matches = availability.items.filter(line => line.materialTypeId === item.materialTypeId);
        const capacity = matches[0];
        if (matches.length !== 1 || canonicalUnitCode(capacity.baseUnit) !== canonicalUnitCode(item.unit) ||
            !Number.isFinite(capacity.remainingQuantity) || capacity.remainingQuantity < 0) {
          throw new Error('无法确定物料的剩余可退数量或库存单位');
        }
        return { ...item, maxQuantity: capacity.remainingQuantity };
      });
      source.value = order;
      sourceFactoryId.value = factory;
      visible.value = true;
      return true;
    } catch (error: unknown) {
      if (!current()) return false;
      throw error;
    }
  }
  return { visible, source, items, sourceFactoryId, open, close };
}
