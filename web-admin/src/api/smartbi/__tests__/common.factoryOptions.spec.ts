import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { pythonFetch } from '../common';

beforeEach(() => {
  localStorage.setItem('cretas_access_token', 'test-token');
  localStorage.setItem('cretas_user', JSON.stringify({ factoryId: 'F006' }));
});
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

it('preserves chart option keys on request without forwarding adapter options to fetch', async () => {
  const payload = { period_type: 'all', chart: { _emphasis_template: { line_style: { width: 2 } }, xAxis: { data: ['产量'] } } };
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => payload });
  vi.stubGlobal('fetch', fetchMock);
  const result = await pythonFetch('/api/smartbi/financial-dashboard/factory-recommendations', { preserveResponseKeys: true });
  expect(result).toEqual(payload);
  expect(fetchMock.mock.calls[0][1]).not.toHaveProperty('preserveResponseKeys');
  expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer test-token');
});
