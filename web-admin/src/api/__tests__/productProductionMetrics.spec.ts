import { expect, it, vi } from 'vitest';
import { get } from '@/api/request';
import { getProductProductionMetrics } from '@/api/productProductionMetrics';
vi.mock('@/api/request', () => ({ get: vi.fn() }));
it('forwards the factory and exact completion period without using browser identity fallback', async () => {
  vi.mocked(get).mockResolvedValueOnce({ success: true, data: { products: [] } });
  await getProductProductionMetrics('F-TEST', ['2026-10-16', '2026-10-16']);
  expect(get).toHaveBeenCalledWith('/F-TEST/production/actual-metrics/by-product', { params: { periodStart: '2026-10-16', periodEnd: '2026-10-16' } });
});
it('keeps unsuccessful responses as failures', async () => {
  vi.mocked(get).mockResolvedValueOnce({ success: false, data: null, message: '无权查看' });
  await expect(getProductProductionMetrics('F-TEST', ['2026-10-16', '2026-10-16'])).rejects.toThrow('无权查看');
});
