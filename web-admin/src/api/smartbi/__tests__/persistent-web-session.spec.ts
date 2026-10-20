import { beforeEach, describe, expect, it, vi } from 'vitest';

const { refresh } = vi.hoisted(() => ({ refresh: vi.fn() }));
vi.mock('../../request', () => ({ default: {}, get: vi.fn(), post: refresh }));

import { getPythonAuthHeaders, pythonFetch } from '../common';

const token = (hash: string, exp: number) => `fixture.${btoa(JSON.stringify({ webSessionHash: hash, exp }))}.signature`;
let stored: string | null;

describe('persistent Web access to Python', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    stored = token('session-A', 1);
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => stored),
      setItem: vi.fn((_key: string, value: string) => { stored = value; }),
      clear: vi.fn(() => { stored = null; }),
    });
    vi.stubGlobal('fetch', vi.fn());
  });

  it('renews an expired Web token before sending a Python request', async () => {
    const fresh = token('session-A', Date.now() / 1000 + 3600);
    refresh.mockResolvedValue({ success: true, data: { token: fresh } });
    vi.mocked(fetch).mockResolvedValue({ ok: true, status: 200, json: async () => ({ value: 1 }) } as Response);
    await expect(pythonFetch('/api/smartbi/protected')).resolves.toEqual({ value: 1 });
    expect(refresh).toHaveBeenCalledExactlyOnceWith('/auth/refresh', {});
    expect(fetch).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
      headers: expect.objectContaining({ Authorization: `Bearer ${fresh}` }),
    }));
  });

  it('coalesces concurrent renewal and keeps a valid Web token without extra calls', async () => {
    const fresh = token('session-A', Date.now() / 1000 + 3600);
    refresh.mockResolvedValue({ success: true, data: { token: fresh } });
    const headers = await Promise.all([getPythonAuthHeaders(), getPythonAuthHeaders(), getPythonAuthHeaders()]);
    expect(headers.every(header => header.Authorization === `Bearer ${fresh}`)).toBe(true);
    await getPythonAuthHeaders();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('does not send Python requests after the cookie authority rejects a revoked login', async () => {
    refresh.mockRejectedValue(new Error('revoked'));
    await expect(pythonFetch('/api/smartbi/protected')).rejects.toThrow('revoked');
    expect(fetch).not.toHaveBeenCalled();
  });

  it('rejects refresh into a different browser session', async () => {
    const original = stored;
    refresh.mockResolvedValue({ success: true, data: { token: token('session-B', Date.now() / 1000 + 3600) } });
    await expect(getPythonAuthHeaders()).rejects.toThrow('current Web login');
    expect(stored).toBe(original);
  });

  it('does not restore a login that was cleared or switched during renewal', async () => {
    refresh.mockImplementation(async () => {
      stored = null;
      return { success: true, data: { token: token('session-A', Date.now() / 1000 + 3600) } };
    });
    await expect(getPythonAuthHeaders()).rejects.toThrow('Login changed');
    expect(stored).toBeNull();
  });

  it('keeps legacy and malformed tokens on their existing authentication path', async () => {
    stored = 'legacy-token';
    expect(await getPythonAuthHeaders()).toMatchObject({ Authorization: 'Bearer legacy-token' });
    expect(refresh).not.toHaveBeenCalled();
  });
});
