import { beforeEach, describe, expect, it, vi } from 'vitest';

const get = vi.fn();
vi.mock('../request', () => ({ get }));

describe('scheduling capabilities API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses the factory scoped endpoint and preserves the response envelope', async () => {
    const response = { success: true, data: { contractVersion: 'scheduling-capabilities/v1' }, message: 'OK' };
    get.mockResolvedValue(response);
    const { getSchedulingCapabilities } = await import('../scheduling');
    await expect(getSchedulingCapabilities('F-P05')).resolves.toBe(response);
    expect(get).toHaveBeenCalledWith('/F-P05/scheduling/capabilities');
  });
});
