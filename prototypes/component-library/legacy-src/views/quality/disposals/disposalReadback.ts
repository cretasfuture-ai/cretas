import type { TableRow } from '@/types/api';

export function getBatchNumber(row: TableRow): string {
  return row.batchNumber
    || row.materialBatch?.batchNumber
    || row.finishedGoodsBatch?.batchNumber
    || row.productionBatch?.batchNumber
    || '-';
}

export function getBatchDisplayName(row: TableRow): string {
  return row.batchDisplayName
    || row.materialName
    || row.materialTypeName
    || row.materialBatch?.materialName
    || row.materialBatch?.materialTypeName
    || row.materialBatch?.materialType?.name
    || row.finishedGoodsBatch?.productName
    || row.finishedGoodsBatch?.productTypeName
    || row.finishedGoodsBatch?.productType?.name
    || getBatchNumber(row)
    || '报废记录';
}

export function getQuantityDisplay(row: TableRow): string {
  const quantity = row.quantity ?? row.disposalQuantity;
  if (quantity == null) return '-';
  return row.quantityUnit ? `${quantity} ${row.quantityUnit}` : String(quantity);
}

export function getApplicantName(row: TableRow): string {
  return row.applicantName
    || row.createdByName
    || row.createdByUser?.fullName
    || row.createdByUser?.name
    || row.createdByUser?.username
    || '历史未记录';
}
