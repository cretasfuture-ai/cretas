import { beforeEach, expect, it, vi } from 'vitest';
import { getCurrentModulePermissions } from './permissionApi';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('./request', () => ({ default: api }));

beforeEach(() => vi.resetAllMocks());

it('reads only the encoded current factory endpoint and preserves every server level', async () => {
  const data = { procurement: 'rw', warehouse: 'r', finance: '-', '*': 'w' };
  api.get.mockResolvedValue({ success: true, data });
  expect(await getCurrentModulePermissions('TEST/NEW')).toEqual(data);
  expect(api.get).toHaveBeenCalledWith('/TEST%2FNEW/permissions/current');
});

it.each([undefined, null, [], { sales: 'admin' }, { sales: null }])('rejects malformed authority response %j', async data => {
  api.get.mockResolvedValue({ success: true, data });
  await expect(getCurrentModulePermissions('TEST')).rejects.toThrow('当前账号权限响应无效');
});

it('does not replace a failed authoritative read with defaults', async () => {
  api.get.mockRejectedValue(new Error('503'));
  await expect(getCurrentModulePermissions('TEST')).rejects.toThrow('503');
});
