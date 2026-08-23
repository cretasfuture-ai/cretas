import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  adminGet: vi.fn(),
  adminPut: vi.fn(),
}));

vi.mock('../request', () => requestMocks);

import {
  listLabelQcPlatformCandidates,
  reviewLabelQcPlatformCandidate,
} from '../labelQc';

describe('Label QC platform API', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    requestMocks.adminGet.mockResolvedValue({ success: true, data: { content: [] } });
    requestMocks.adminPut.mockResolvedValue({ success: true, data: {} });
  });

  it('uses the platform-only endpoint instead of a factory mobile endpoint', async () => {
    await listLabelQcPlatformCandidates('LIUSHANMEN', {
      reviewed: false,
      page: 2,
      size: 50,
    });

    expect(requestMocks.adminGet).toHaveBeenCalledWith(
      '/api/platform/label-qc/LIUSHANMEN/candidates',
      { params: { reviewed: false, page: 2, size: 50 } },
    );
    expect(requestMocks.get).not.toHaveBeenCalled();
  });

  it('writes platform review decisions through the platform-only endpoint', async () => {
    const payload = {
      expectedVersion: 3,
      label: 'NO_DEFECT' as const,
      bbox: { xMin: 0.1, yMin: 0.2, xMax: 0.3, yMax: 0.4 },
      notes: 'hard negative',
    };

    await reviewLabelQcPlatformCandidate('LIUSHANMEN', 'candidate/1', payload);

    expect(requestMocks.adminPut).toHaveBeenCalledWith(
      '/api/platform/label-qc/LIUSHANMEN/candidates/candidate%2F1/review',
      payload,
    );
    expect(requestMocks.put).not.toHaveBeenCalled();
  });
});
