import AsyncStorage from '@react-native-async-storage/async-storage';
import 'react-native-get-random-values';
export function createWarehouseDeliveryUuid(): string { const bytes = new Uint8Array(16); globalThis.crypto.getRandomValues(bytes); bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40; bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80; const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join(''); return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`; }
export interface DeliveryQuantity { itemId: number; unit: string; pendingQuantity: number; quantity: number; }
export interface WarehouseDeliveryReceipt { requestKey: string; operationId: string; targetBatchId: string; quantity: number; unit: string; actorId: number; createdAt: string; }
export interface WarehouseDeliveryItem { id: number; itemName: string; unit: string; sourceBatchId: string; shippedQuantity: number; receivedQuantity: number; pendingQuantity: number; receipts: WarehouseDeliveryReceipt[]; }
export interface WarehouseTransferDelivery { supported: boolean; staged: boolean; stage: string; transferId: string; transferNumber: string; inTransitByUnit: Record<string, number>; items: WarehouseDeliveryItem[]; }
export interface WarehouseDeliveryPending { factoryId: string; userId: string; transferId: string; requestKey: string; itemQuantities: Record<number, number>; state: 'pending' | 'unknown' | 'resolved'; }
const requestKeys = new Map<string, string>();
export function createWarehouseDeliveryRequestKey(factoryId: string, userId: string, transferId: string): string {
  const key = `warehouse-delivery:${factoryId}:${userId}:${transferId}`;
  const existing = globalThis.localStorage?.getItem(key) || requestKeys.get(key);
  if (existing) return existing;
  const generated = createWarehouseDeliveryUuid();
  try { globalThis.localStorage?.setItem(key, generated); } catch { /* native uses AsyncStorage helper below */ }
  requestKeys.set(key, generated);
  return generated;
}
export async function getOrCreateWarehouseDeliveryRequestKey(factoryId: string, userId: string, transferId: string): Promise<string | null> {
  const key = `warehouse-delivery:${factoryId}:${userId}:${transferId}`;
  try { const persisted = await AsyncStorage.getItem(key); if (persisted) { requestKeys.set(key, persisted); return persisted; } } catch { return null; }
  const generated = requestKeys.get(key) || createWarehouseDeliveryUuid(); requestKeys.set(key, generated);
  try { await AsyncStorage.setItem(key, generated); } catch { requestKeys.delete(key); return null; }
  return generated;
}
export async function saveWarehouseDeliveryPending(pending: WarehouseDeliveryPending): Promise<boolean> { try { await AsyncStorage.setItem(`warehouse-delivery-pending:${pending.factoryId}:${pending.userId}:${pending.transferId}`, JSON.stringify(pending)); return true; } catch { return false; } }
export async function loadWarehouseDeliveryPending(factoryId: string, userId: string, transferId: string): Promise<WarehouseDeliveryPending | null> { try { const raw = await AsyncStorage.getItem(`warehouse-delivery-pending:${factoryId}:${userId}:${transferId}`); return raw ? JSON.parse(raw) as WarehouseDeliveryPending : null; } catch { throw new Error('本机待核收货请求无法读取'); } }
export async function clearWarehouseDeliveryPending(factoryId: string, userId: string, transferId: string): Promise<boolean> { try { await AsyncStorage.removeItem(`warehouse-delivery-pending:${factoryId}:${userId}:${transferId}`); return true; } catch { return false; } }
export async function persistResolvedWarehouseDelivery(pending: WarehouseDeliveryPending, nextRequestKey: string): Promise<boolean> {
  const requestStorageKey = `warehouse-delivery:${pending.factoryId}:${pending.userId}:${pending.transferId}`;
  const pendingStorageKey = `warehouse-delivery-pending:${pending.factoryId}:${pending.userId}:${pending.transferId}`;
  const resolved: WarehouseDeliveryPending = { ...pending, requestKey: nextRequestKey, itemQuantities: {}, state: 'resolved' };
  try {
    await AsyncStorage.setItem(requestStorageKey, nextRequestKey);
    await AsyncStorage.setItem(pendingStorageKey, JSON.stringify(resolved));
    requestKeys.set(requestStorageKey, nextRequestKey);
    return true;
  } catch { return false; }
}
export async function rotateWarehouseDeliveryRequestKey(factoryId: string, userId: string, transferId: string): Promise<string | null> {
  const key = `warehouse-delivery:${factoryId}:${userId}:${transferId}`; const generated = createWarehouseDeliveryUuid(); requestKeys.set(key, generated);
  try { await AsyncStorage.setItem(key, generated); } catch { requestKeys.delete(key); return null; }
  return generated;
}

export function warehouseDeliveryMatchesPending(delivery: WarehouseTransferDelivery, pending: WarehouseDeliveryPending): boolean {
  const submitted = Object.entries(pending.itemQuantities).filter(([, quantity]) => Number(quantity) > 0);
  if (!submitted.length) return false;
  const submittedIds = new Set(submitted.map(([itemId]) => String(itemId)));
  const returnedIds = new Set<string>();
  for (const item of delivery.items) {
    const matching = item.receipts.filter(receipt => receipt.requestKey === pending.requestKey);
    if (matching.length) returnedIds.add(String(item.id));
    if (!submittedIds.has(String(item.id)) && matching.length) return false;
  }
  for (const [itemId, expectedValue] of submitted) {
    const item = delivery.items.find(candidate => String(candidate.id) === String(itemId));
    if (!item) return false;
    const expected = Number(expectedValue);
    const matching = item.receipts.filter(receipt => receipt.requestKey === pending.requestKey);
    if (!matching.length || matching.some(receipt => receipt.unit !== item.unit || !receipt.operationId?.trim() || !receipt.targetBatchId?.trim())) return false;
    const actual = matching.reduce((sum, receipt) => sum + receipt.quantity, 0);
    if (Math.abs(actual - expected) >= 0.00005) return false;
  }
  return returnedIds.size === submittedIds.size;
}
export function validateWarehouseReceipt(items: DeliveryQuantity[]): { valid: boolean; quantities: Record<number, number>; reason?: string } {
  const quantities: Record<number, number> = {};
  let hasPositive = false;
  for (const item of items) {
    const value = Number(item.quantity || 0);
    if (!Number.isFinite(value) || value < 0 || value > item.pendingQuantity || Number(value.toFixed(4)) !== value) {
      return { valid: false, quantities: {}, reason: `${item.unit} 本次收货不能超过待收数量，最多4位小数` };
    }
    if (value > 0) { quantities[item.itemId] = value; hasPositive = true; }
  }
  return hasPositive ? { valid: true, quantities } : { valid: false, quantities: {}, reason: '请至少填写一行本次收货数量' };
}
export function deliveryStageLabel(delivery: { supported: boolean; staged: boolean; stage: string }): string {
  if (!delivery.supported) return '仓内分步交付未启用，请使用原一次确认流程或完善批次后重试。';
  if (!delivery.staged) return '尚未发运，请先完成仓内发运。';
  return delivery.stage || '已发运';
}
