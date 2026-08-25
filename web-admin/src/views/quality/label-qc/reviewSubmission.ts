import type { LabelQcReviewRequest } from '@/api/labelQc';

export interface LabelQcReviewAttempt {
  taskId: string;
  signature: string;
  requestId: string;
}

export function prepareLabelQcReviewAttempt(
  existing: LabelQcReviewAttempt | null,
  taskId: string,
  expectedVersion: number,
  payload: LabelQcReviewRequest,
  createRequestId: () => string = () => (
    globalThis.crypto?.randomUUID?.() ?? `web-${taskId}-${Date.now()}`
  ),
): LabelQcReviewAttempt {
  const signature = JSON.stringify({ expectedVersion, photos: payload.photos });
  if (existing?.taskId === taskId && existing.signature === signature) return existing;
  return { taskId, signature, requestId: createRequestId() };
}
