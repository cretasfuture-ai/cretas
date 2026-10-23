import { get, post } from '@/api/request';

export interface WarehouseDeliveryReceipt { requestKey: string; operationId: string; targetBatchId: string; quantity: number; unit: string; actorId: number; createdAt: string; }
export interface WarehouseDeliveryItem { id: number; itemName: string; unit: string; sourceBatchId: string; shippedQuantity: number; receivedQuantity: number; pendingQuantity: number; receipts: WarehouseDeliveryReceipt[]; }
export interface WarehouseTransferDelivery { supported: boolean; staged: boolean; stage: string; transferId: string; transferNumber: string; inTransitByUnit: Record<string, number>; items: WarehouseDeliveryItem[]; }

function path(factoryId: string, transferId: string, suffix = 'warehouse-delivery'): string {
  return `/${factoryId}/transfers/${transferId}/${suffix}`;
}
export const getWarehouseTransferDelivery = (factoryId: string, transferId: string) =>
  get<WarehouseTransferDelivery>(path(factoryId, transferId));
export const dispatchWarehouseTransfer = (factoryId: string, transferId: string) =>
  post<{ id: string; status: string }>(path(factoryId, transferId, 'warehouse-dispatch'));
export const receiveWarehouseTransfer = (factoryId: string, transferId: string, body: { requestKey: string; itemQuantities: Record<number, number> }) =>
  post<WarehouseTransferDelivery>(path(factoryId, transferId, 'warehouse-receipts'), body);
