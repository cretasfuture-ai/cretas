import { beforeEach, expect, it, vi } from 'vitest';
import { get } from '@/api/request';
import { listIndicatorVersions } from '@/api/indicator';

vi.mock('@/api/request', () => ({ get: vi.fn(), post: vi.fn() }));
beforeEach(() => {
  vi.mocked(get).mockReset();
  vi.mocked(get).mockResolvedValue({ success: true, data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 }, message: '' });
});

it('transmits exact-period mode and the one-based history page to the business API', async () => {
  await listIndicatorVersions('F-TEST', 'FACTORY_ACTUAL_TOTAL_COST', {
    page: 2, size: 10, windowStart: '2026-10-16', windowEnd: '2026-10-16', exactPeriod: true,
  });
  expect(get).toHaveBeenCalledWith('/F-TEST/indicators/FACTORY_ACTUAL_TOTAL_COST/versions', { params: {
    page: 2, size: 10, windowStart: '2026-10-16', windowEnd: '2026-10-16', exactPeriod: true,
  } });
});

it('keeps the existing overlap contract when exact-period mode was not requested', async () => {
  await listIndicatorVersions('F-TEST', 'KPI', { page: 1, size: 10 });
  expect(get).toHaveBeenCalledWith('/F-TEST/indicators/KPI/versions', { params: { page: 1, size: 10 } });
});
