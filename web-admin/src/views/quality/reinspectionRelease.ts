interface BatchContext {
  id?: unknown;
  qualityStatus?: unknown;
}

interface InspectionContext {
  productionBatchId?: unknown;
  result?: unknown;
}

export function isFailedBatchContext(batch: BatchContext | null): boolean {
  return batch?.id != null && String(batch.id).trim() !== '' && batch.qualityStatus === 'FAILED';
}

export function canReviewPassingReinspection(batch: BatchContext | null, row: InspectionContext): boolean {
  return isFailedBatchContext(batch) && row.result === 'PASS'
    && row.productionBatchId != null && String(row.productionBatchId) === String(batch?.id);
}

export function dispositionResultFilter(batch: BatchContext | null): string {
  return isFailedBatchContext(batch) ? 'FAIL,CONDITIONAL,PASS' : 'FAIL,CONDITIONAL';
}
