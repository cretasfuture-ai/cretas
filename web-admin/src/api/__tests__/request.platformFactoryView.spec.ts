import axios, { type AxiosAdapter } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { factoryViewContext, resetFactoryViewContext } from '@/store/modules/platformFactoryContext';

vi.mock('element-plus', () => ({
  ElMessage: vi.fn(), ElNotification: vi.fn(),
  ElMessageBox: { alert: vi.fn(), confirm: vi.fn() },
}));
vi.mock('@/router', () => ({ default: { push: vi.fn(), currentRoute: { value: { fullPath: '/' } } } }));

import request from '../request';
import { getFactoryId, getSmartBIBasePath, pythonFetch } from '../smartbi/common';

function enterView() {
  factoryViewContext.value = {
    factoryId: 'F006', factoryName: '指定工厂', factoryType: 'FACTORY',
    accessToken: 'scoped-view-token', expiresIn: 300, expiresAt: Date.now() + 300000, readOnly: true,
  };
  sessionStorage.setItem('cretas_platform_factory_view', JSON.stringify(factoryViewContext.value));
}

describe('factory view through real request adapters', () => {
  beforeEach(() => {
    resetFactoryViewContext();
    localStorage.setItem('cretas_access_token', 'original-platform-token');
    localStorage.setItem('cretas_user', JSON.stringify({ factoryId: 'PLATFORM', role: 'super_admin' }));
    enterView();
  });
  afterEach(() => {
    resetFactoryViewContext(); localStorage.clear(); vi.restoreAllMocks();
  });

  function adapterSpy() {
    return vi.fn<AxiosAdapter>(async (config) => ({
      config, status: 200, statusText: 'OK', headers: {}, data: { success: true, data: {} },
    }));
  }

  it('resolves a relative business GET while retaining the view credential', async () => {
    const adapter = adapterSpy();
    await request.get('/F006/production-plans', { adapter });
    expect(adapter.mock.calls[0]?.[0].headers.Authorization).toBe('Bearer scoped-view-token');
    expect(adapter.mock.calls[0]?.[0].headers['X-Client-Type']).toBe('platform-factory-view');
  });

  it('keeps the original platform credential only for management', async () => {
    const adapter = adapterSpy();
    await request.get('/api/platform/factories', { baseURL: '', adapter });
    expect(adapter.mock.calls[0]?.[0].headers.Authorization).toBe('Bearer original-platform-token');
    await request.get('/api/admin/role-permissions', { baseURL: '', adapter });
    expect(adapter.mock.calls[1]?.[0].headers.Authorization).toBe('Bearer original-platform-token');
  });

  it('denies business writes before calling the adapter', async () => {
    const adapter = adapterSpy();
    await expect(request.put('/F006/sales/orders/1', {}, { adapter })).rejects.toThrow('只读查看');
    expect(adapter).not.toHaveBeenCalled();
    await expect(request.post('/api/admin/cache/purge', {}, { baseURL: '', adapter })).rejects.toThrow('只读查看');
    await expect(request.put('/api/admin/role-permissions', {}, { baseURL: '', adapter })).rejects.toThrow('只读查看');
    expect(adapter).not.toHaveBeenCalled();
  });

  it('uses the effective factory for SmartBI without changing login identity', () => {
    expect(getFactoryId()).toBe('F006');
    expect(getSmartBIBasePath()).toBe('/F006/smart-bi');
    expect(JSON.parse(localStorage.getItem('cretas_user') || '{}').factoryId).toBe('PLATFORM');
    factoryViewContext.value!.expiresAt = Date.now() - 1;
    expect(() => getFactoryId()).toThrow('凭证已过期');
  });

  it('does not fall back to the original token when the view expires or storage disappears', async () => {
    sessionStorage.removeItem('cretas_platform_factory_view');
    const adapter = adapterSpy();
    await request.get('/F006/production/batches', { adapter });
    expect(adapter.mock.calls[0]?.[0].headers.Authorization).toBe('Bearer scoped-view-token');
    factoryViewContext.value!.expiresAt = 0;
    adapter.mockClear();
    const refresh = vi.spyOn(axios, 'post');
    await expect(request.get('/F006/production/batches', { adapter })).rejects.toThrow('凭证已过期');
    expect(adapter).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });

  it('locks Python authorization to the scoped token despite caller headers', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', {
      status: 200, headers: { 'content-type': 'application/json' },
    }));
    await pythonFetch('/api/smartbi/financial-dashboard/factory-details', {
      method: 'GET', headers: { Authorization: 'Bearer original-platform-token' },
    });
    expect(fetchMock).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer scoped-view-token' }),
    }));
  });
});
