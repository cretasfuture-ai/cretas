import type { FactoryJointUploads } from '@/api/smartbi/factory-charts';

export function validJointUploads(value: unknown, anchor: number | null): value is FactoryJointUploads {
  if (!value || typeof value !== 'object' || !Number.isSafeInteger(anchor) || Number(anchor) <= 0) return false;
  const selected = value as Partial<FactoryJointUploads>;
  return Number.isSafeInteger(selected.inspectionUploadId) && Number(selected.inspectionUploadId) > 0
    && Number.isSafeInteger(selected.costUploadId) && Number(selected.costUploadId) > 0
    && selected.inspectionUploadId !== selected.costUploadId
    && selected.inspectionUploadId !== anchor && selected.costUploadId !== anchor;
}

/** Missing server metadata cannot prove a three-table selection. */
export function sameJointUploads(actual: unknown, expected: FactoryJointUploads | null): boolean {
  if (expected === null) return actual == null;
  if (!actual || typeof actual !== 'object') return false;
  const value = actual as Partial<FactoryJointUploads>;
  return value.inspectionUploadId === expected.inspectionUploadId && value.costUploadId === expected.costUploadId;
}
