import { beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('@/api/request', () => ({ default: { post: h.post } }));

import { postFactoryAnalysis } from '../factory-analysis';

describe('factory analysis API', () => {
  beforeEach(() => h.post.mockReset().mockResolvedValue({ success: true, data: { contextId: 'ctx-1' }, message: 'ok' }));

  it('sends only the fixed camelCase contract to the authenticated Java route', async () => {
    const payload = {
      question: '生产和质量有什么变化？',
      source: 'business' as const,
      year: 2026,
      periodType: 'all' as const,
      startMonth: 1,
      endMonth: 12,
      analysisSubject: null,
    };
    await postFactoryAnalysis('F006', payload);
    expect(h.post).toHaveBeenCalledWith(
      '/F006/smart-bi/factory-analysis',
      payload,
      expect.objectContaining({ timeout: 120_000 }),
    );
    expect(JSON.stringify(h.post.mock.calls[0][1])).not.toMatch(/role|factoryId|userId|sql|endpoint/i);
  });

  it('keeps follow-up requests narrow and accepts an opaque context token', async () => {
    const payload = { question: '再看质量', contextId: 'opaque-last-token' };
    await postFactoryAnalysis('F006', payload);
    expect(h.post.mock.calls[0][1]).toEqual(payload);
  });
});
