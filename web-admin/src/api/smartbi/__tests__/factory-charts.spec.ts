import { beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({ pythonFetch: vi.fn() }));
vi.mock('../common', () => ({ pythonFetch: h.pythonFetch, PYTHON_LLM_TIMEOUT_MS: 300000 }));

import { getFactoryChartRecommendations } from '../factory-charts';

describe('factory chart recommendations API', () => {
  beforeEach(() => {
    h.pythonFetch.mockReset().mockResolvedValue({ success: true, data: {} });
  });

  it('posts the factory contract and preserves raw response option keys', async () => {
    const payload = {
      source: 'business' as const,
      factory_id: 'F006',
      year: 2026,
      period_type: 'all' as const,
      start_month: 1,
      end_month: 12,
      include_optional: true,
      use_ai: true,
    };
    await getFactoryChartRecommendations(payload);
    expect(h.pythonFetch).toHaveBeenCalledWith(
      '/api/smartbi/financial-dashboard/factory-recommendations',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(payload),
        preserveResponseKeys: true,
      }),
    );
  });
});
