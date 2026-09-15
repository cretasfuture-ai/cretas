import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 源头个体档案列表 —— `?keyword=` 带参打开。
 *
 * 溯源页个体抽屉的「编辑」跳到这里并带 `?keyword=<individualCode>`; 本页原来不读任何
 * query, 跳过来只会看到未过滤的全表 —— 那个跳转就等于没接上 (形态 B)。
 * 这里钉住: 首载时 keyword 来自 query, 并且**真的**带进了列表请求。
 */

const routeMock: { query: Record<string, string> } = { query: {} };
vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));

const listMock = vi.fn();
vi.mock('@/api/sourceIndividual', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/sourceIndividual')>()),
  listSourceIndividuals: (...args: unknown[]) => listMock(...args),
}));
vi.mock('@/api/request', () => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }));

import SourceIndividualsPage from '../index.vue';

beforeEach(() => {
  routeMock.query = {};
  listMock.mockReset();
  listMock.mockResolvedValue({
    success: true,
    data: { content: [], page: 1, size: 20, totalElements: 0, totalPages: 0 },
    message: '',
  });
});

describe('源头个体档案 — ?keyword= 带参打开', () => {
  it('query 里的 keyword 进了首载请求, 也进了搜索框', async () => {
    routeMock.query = { keyword: 'SL-002' };
    const wrapper = mount(SourceIndividualsPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(listMock).toHaveBeenCalledTimes(1);
    expect(listMock).toHaveBeenCalledWith('F006', expect.objectContaining({ keyword: 'SL-002' }));
    const input = wrapper.find('.toolbar input').element as HTMLInputElement;
    expect(input.value).toBe('SL-002');
  });

  it('阴性对照: 没带 keyword ⇒ 首载不过滤', async () => {
    mount(SourceIndividualsPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(listMock).toHaveBeenCalledTimes(1);
    expect(listMock).toHaveBeenCalledWith('F006', expect.objectContaining({ keyword: '' }));
  });
});
