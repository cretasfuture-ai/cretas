import { describe, expect, it, vi } from 'vitest';
import type { LabelQcReviewRequest } from '@/api/labelQc';
import { prepareLabelQcReviewAttempt } from './reviewSubmission';

function reviewPayload(label: 'NO_DEFECT' | 'UNJUDGEABLE' = 'NO_DEFECT'): LabelQcReviewRequest {
  return {
    photos: [{
      photoId: 'photo-1',
      annotations: [{ annotationId: 'candidate-1', label }],
    }],
  };
}

describe('label QC retry-safe batch submission', () => {
  it('reuses the same request id when the same task and review payload are retried', () => {
    const createRequestId = vi.fn()
      .mockReturnValueOnce('review-request-1')
      .mockReturnValueOnce('review-request-2');
    const first = prepareLabelQcReviewAttempt(null, 'task-1', 3, reviewPayload(), createRequestId);
    const retry = prepareLabelQcReviewAttempt(first, 'task-1', 3, reviewPayload(), createRequestId);

    expect(retry).toBe(first);
    expect(retry.requestId).toBe('review-request-1');
    expect(createRequestId).toHaveBeenCalledTimes(1);
  });

  it('creates a new request id after the reviewer changes a saved draft', () => {
    const first = prepareLabelQcReviewAttempt(null, 'task-1', 3, reviewPayload(), () => 'old-id');
    const changed = prepareLabelQcReviewAttempt(
      first,
      'task-1',
      3,
      reviewPayload('UNJUDGEABLE'),
      () => 'new-id',
    );

    expect(changed.requestId).toBe('new-id');
  });

  it('never reuses an attempt across tasks or optimistic-lock versions', () => {
    const first = prepareLabelQcReviewAttempt(null, 'task-1', 3, reviewPayload(), () => 'old-id');

    expect(prepareLabelQcReviewAttempt(first, 'task-2', 3, reviewPayload(), () => 'task-2-id').requestId)
      .toBe('task-2-id');
    expect(prepareLabelQcReviewAttempt(first, 'task-1', 4, reviewPayload(), () => 'version-4-id').requestId)
      .toBe('version-4-id');
  });
});
