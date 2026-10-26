import { beforeEach, describe, expect, it, vi } from 'vitest';

const fetchMock = vi.hoisted(() => vi.fn());
vi.mock('@/api/smartbi/common', () => ({ pythonFetch: fetchMock, PYTHON_LLM_TIMEOUT_MS: 30_000 }));
import { getFactoryChartDetails } from '@/api/smartbi/factory-charts';

describe('factory chart details API', () => {
  beforeEach(() => fetchMock.mockReset().mockResolvedValue({ success: true, data: { rows: [] } }));
  it('sends opaque context, point and bounded server page without changing filters', async () => {
    await getFactoryChartDetails({ context_id: 'ctx-opaque', page: 3, page_size: 999, filters: [{ field: 'batch', value: 'B1' }], point: { name: '三月', dataIndex: 2 } });
    expect(fetchMock).toHaveBeenCalledWith('/api/smartbi/financial-dashboard/factory-details', expect.objectContaining({ method: 'POST', body: JSON.stringify({ context_id: 'ctx-opaque', page: 3, page_size: 100, filters: [{ field: 'batch', value: 'B1' }], point: { name: '三月', dataIndex: 2 } }) }));
  });
});
