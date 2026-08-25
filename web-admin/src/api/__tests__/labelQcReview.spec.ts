import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  adminGet: vi.fn(),
  adminPut: vi.fn(),
}));

vi.mock('../request', () => requestMocks);

import { reviewLabelQcTask } from '../labelQc';

describe('label QC factory review API', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    requestMocks.put.mockResolvedValue({ success: true, data: {} });
  });

  it('bounds batch review requests instead of inheriting the global two-minute timeout', async () => {
    const payload = {
      reviewRequestId: 'same-id-on-retry',
      expectedVersion: 4,
      photos: [{
        photoId: 'photo-1',
        annotations: [{ label: 'NO_DEFECT' as const }],
      }],
    };

    await reviewLabelQcTask('LIUSHANMEN', 'task-1', payload);

    expect(requestMocks.put).toHaveBeenCalledWith(
      '/LIUSHANMEN/label-qc/tasks/task-1/review',
      payload,
      { timeout: 30000 },
    );
  });
});
