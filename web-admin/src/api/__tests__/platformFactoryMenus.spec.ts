import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMocks = vi.hoisted(() => ({
  adminGet: vi.fn(),
  adminPost: vi.fn(),
  adminPut: vi.fn(),
}));

vi.mock('../request', () => requestMocks);

import {
  createFactoryViewContext,
  getFactoryMenuConfig,
  listPlatformFactories,
  updateFactoryMenuConfig,
} from '../platformFactoryMenus';

describe('platform factory menu API', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads the real platform factory list and normalizes the response', async () => {
    requestMocks.adminGet.mockResolvedValue({ success: true, data: { content: [{ id: 'F006', factoryName: '六膳门', factoryType: 'FACTORY' }] } });
    await expect(listPlatformFactories()).resolves.toEqual([{ id: 'F006', name: '六膳门', factoryType: 'FACTORY', isActive: true }]);
    expect(requestMocks.adminGet).toHaveBeenCalledWith('/api/platform/factories', { baseURL: '' });
  });

  it('keeps menu config and view context on platform endpoints', async () => {
    requestMocks.adminGet.mockResolvedValue({ success: true, data: { factoryId: 'F006', modules: [] } });
    requestMocks.adminPut.mockResolvedValue({ success: true, data: {} });
    requestMocks.adminPost.mockResolvedValue({ success: true, data: { accessToken: 'view-token', expiresIn: 300, readOnly: true } });

    await getFactoryMenuConfig('F/006');
    await updateFactoryMenuConfig('F/006', 'analytics', { visible: true, disabledMenuPaths: ['/smart-bi'] });
    await createFactoryViewContext('F/006');

    expect(requestMocks.adminGet).toHaveBeenCalledWith('/api/platform/factories/F%2F006/menu-config', { baseURL: '' });
    expect(requestMocks.adminPut).toHaveBeenCalledWith('/api/platform/factories/F%2F006/menu-config/analytics', { visible: true, disabledMenuPaths: ['/smart-bi'] });
    expect(requestMocks.adminPost).toHaveBeenCalledWith('/api/platform/factories/F%2F006/view-context', {}, { baseURL: '' });
  });
});
