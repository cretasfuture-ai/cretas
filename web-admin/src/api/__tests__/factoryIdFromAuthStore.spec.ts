/**
 * 回归: sales-needs / reminders 的 API 曾读 localStorage['user'] (应用从不写这个 key),
 * 只有 cretas_user 时在发请求前就抛「未登录」, 页面显示「加载失败,请检查网络」。
 * 现在统一走 auth store (它从 cretas_user 恢复)。
 * 用真实 pinia + 真实 auth store, 只桩掉 HTTP 层。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../request', () => ({ default: http }));

import { listSalesNeeds } from '../salesNeed';
import { listReminders } from '../reminder';

const cretasUser = {
  id: 1, username: 'f006_admin', email: '', isActive: true, createdAt: '', updatedAt: '', userType: 'factory',
  factoryUser: { role: 'factory_super_admin', factoryId: 'F006', factoryType: 'FACTORY', permissions: [] },
};

beforeEach(() => {
  http.get.mockReset();
  http.get.mockResolvedValue({ success: true, data: { content: [], totalElements: 0 } });
  localStorage.clear();
  localStorage.setItem('cretas_user', JSON.stringify(cretasUser));
  setActivePinia(createPinia());
});

describe('factoryId comes from the auth store (cretas_user), not localStorage["user"]', () => {
  it('sales-needs list requests /F006/sales-needs when only cretas_user exists', async () => {
    expect(localStorage.getItem('user')).toBeNull();
    await listSalesNeeds({ page: 0, size: 20 });
    expect(http.get).toHaveBeenCalledTimes(1);
    expect(http.get.mock.calls[0][0]).toMatch(/^\/F006\/sales-needs\?/);
  });
  it('reminders list requests /F006/reminders when only cretas_user exists', async () => {
    await listReminders({});
    expect(http.get).toHaveBeenCalledTimes(1);
    expect(http.get.mock.calls[0][0]).toMatch(/^\/F006\/reminders\?/);
  });
  it('an explicit factoryId still wins', async () => {
    await listSalesNeeds({ factoryId: 'OTHER' });
    expect(http.get.mock.calls[0][0]).toMatch(/^\/OTHER\/sales-needs\?/);
  });
  it('no logged-in user fails loudly before any request', async () => {
    localStorage.clear();
    setActivePinia(createPinia());
    await expect(Promise.resolve().then(() => listSalesNeeds({}))).rejects.toThrow('未登录');
    expect(http.get).not.toHaveBeenCalled();
  });
});
